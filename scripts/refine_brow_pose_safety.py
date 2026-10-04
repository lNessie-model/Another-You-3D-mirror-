"""Constrain the wide brow's low eye annulus in 33 fixed poses.

Preserve all measured gray-brow parent vertices and outer/inner boundary rows.
Rows 5/6 use the accepted Stage 11 Up response. Remaining low sectors of rows
3/4 are adjusted by cutting planes, excluding all measured gray parent nodes.
The original paired-ring blink products and
bridge attachments evaluated as linear functions of the same Up field.
"""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

import numpy as np

from head_glb_edit import GlbEdit
from refine_head_expression import attached_delta
from refine_visible_brow import SOURCE_SHA, JAVA, JAVA_CP, REPO, smoothstep, validated_probes

INPUT_SHA = '375d853ae57e603976f1344c512f9f3ef86b60fefe99c601fe83409828309015'


def pose_cases(names, manifest, pose_paths):
    result = []
    for path in pose_paths:
        data = json.loads(Path(path).read_text('utf-8'))
        assert data['modelSha256'] == INPUT_SHA
        for row in data['poses']:
            mesh = row['meshes'][0]
            assert mesh['targetNames'] == names
            weights = np.asarray(mesh['weights'], float)
            assert weights.shape == (len(names),) and np.isfinite(weights).all()
            result.append((row['name'], weights))

    active = {'browInnerUp', 'eyeBlinkLeft', 'eyeBlinkRight', 'eyeWideLeft',
              'eyeWideRight', 'browOuterUpLeft', 'browOuterUpRight'}
    for binding in manifest['bindings']:
        if binding['source'] in active:
            assert binding.get('gain', 1) == 1 and 'curve' not in binding

    def products(weights):
        for binding in manifest['derivedBindings']:
            assert binding['operation'] == 'product' and binding['mesh'] == 'Face'
            weights[names.index(binding['target'])] = np.prod([
                weights[names.index(source)] for source in binding['sources']
            ]) * binding.get('gain', 1)
        return weights

    for amount in (.25, .5, .75):
        for wide in (0, 1):
            w = np.zeros(len(names))
            w[names.index('browInnerUp')] = 1
            for side in ('Left', 'Right'):
                w[names.index('eyeBlink'+side)] = amount
                w[names.index('eyeWide'+side)] = wide
            result.append(('half-blink-%.2f-wide-%s' % (amount, wide), products(w)))
    for outer in (0, 1):
        w = np.zeros(len(names))
        w[names.index('browInnerUp')] = 1
        for side in ('Left', 'Right'):
            w[names.index('browOuterUp'+side)] = outer
        result.append(('inner-up-endpoint-outer-%s' % outer, products(w)))
    assert len(result) == 33
    return result


def deformed(g, primitive, weights):
    result = g.read(primitive['attributes']['POSITION']).astype(float)
    for weight, target in zip(weights, primitive['targets'], strict=True):
        if weight:
            result += weight * g.read(target['POSITION'])
    return result


def project_qp(q, rows, bounds):
    """Small active-set metric projection, without a full inequality Gram matrix."""
    a, b = np.asarray(rows), np.asarray(bounds)
    if not len(a):
        return np.zeros(len(q))
    h = np.linalg.solve(q, a.T)
    active = []
    point = np.zeros(len(q))
    for _ in range(400):
        residual = b - a @ point
        worst = int(np.argmax(residual))
        if residual[worst] < 1e-7:
            return point
        assert worst not in active, 'Constraint projection did not converge'
        active.append(worst)
        while active:
            gram = a[active] @ h[:, active]
            multipliers = np.linalg.lstsq(gram, b[active], rcond=1e-12)[0]
            if multipliers.min() < -1e-12:
                active.pop(int(np.argmin(multipliers)))
            else:
                point = h[:, active] @ multipliers
                break
        if not active:
            point[:] = 0
    raise RuntimeError('Local constraint active set did not converge')


def author(source, candidate, probes, pose_paths, out):
    source, candidate, out = Path(source), Path(candidate), Path(out)
    assert not out.exists()
    old = GlbEdit(source/'character.glb').source
    edit = GlbEdit(candidate/'character.glb')
    g = edit.source
    assert hashlib.sha256(old.raw).hexdigest() == SOURCE_SHA
    assert hashlib.sha256(g.raw).hexdigest() == INPUT_SHA
    manifest = json.loads((candidate/'avatar.json').read_text('utf-8'))
    assert manifest['modelSha256'] == INPUT_SHA
    mesh = g.doc['meshes'][0]
    names = mesh['extras']['targetNames']
    assert names == old.doc['meshes'][0]['extras']['targetNames'] and len(names) == 58
    skin, ring = mesh['primitives'][:2]
    old_ring = old.doc['meshes'][0]['primitives'][1]
    ui = names.index('browInnerUp')
    product_ids = [names.index('correctiveBlinkBrowInnerUp'+side) for side in ('Left', 'Right')]
    pivot = np.asarray(next(node['translation'] for node in g.doc['nodes'] if node['name'] == 'Head'))
    rp = g.read(ring['attributes']['POSITION']).astype(float) + pivot
    sp = g.read(skin['attributes']['POSITION']).astype(float) + pivot
    ids = g.read(ring['indices']).reshape(-1, 3)
    assert len(rp) == 3003 and len(sp) == 13975
    probe_lookup = np.full(len(rp), -1, dtype=int)
    probe_lookup[1056:2080] = np.arange(1024)
    probe_rows = validated_probes(probes, rp, ids, probe_lookup)
    protected = np.unique(np.concatenate([row['vertexIds'] for row in probe_rows]))
    original_up = g.read(ring['targets'][ui]['POSITION']).astype(float)
    old_up = old.read(old_ring['targets'][ui]['POSITION']).astype(float)
    up = original_up.copy()
    groups = [np.arange(1056, 1568), np.arange(1568, 2080)]
    restored = np.concatenate([group[5*64:7*64] for group in groups])
    assert not np.isin(restored, protected).any()
    up[restored] = old_up[restored]
    low = np.concatenate([group[3*64:5*64] for group in groups])
    free = low[(rp[low, 1] < .028) & ~np.isin(low, protected)]
    assert 2 <= len(free) <= 256
    count = len(free)
    basis = np.zeros((len(rp), count))
    basis[free, np.arange(count)] = 1
    joined = np.concatenate([sp, rp[:2080]])
    skin_ids = g.read(skin['indices']).reshape(-1, 3)
    original_ring_ids = ids[(ids < 2080).all(1)] + len(sp)
    triangles = np.concatenate([skin_ids, original_ring_ids])
    near = (joined[triangles, 1].max(1) > .008) & (joined[triangles, 2].max(1) > .03)
    errors = []

    def reattach(field, skin_field):
        field[2080:3001], error = attached_delta(
            rp[2080:3001], joined, triangles[near], np.concatenate([skin_field, field[:2080]]))
        field[[3001, 3002]] = skin_field[[11829, 11904]]
        errors.append(float(error))

    skin_up = g.read(skin['targets'][ui]['POSITION']).astype(float)
    reattach(up, skin_up)
    reattach(basis, np.zeros((len(sp), count)))
    corrections, product_basis = [], []
    for group, pi in zip(groups, product_ids, strict=True):
        correction = g.read(ring['targets'][pi]['POSITION']).astype(float)
        operator = np.zeros_like(basis)
        for row in range(8):
            vertices = group[row*64:(row+1)*64]
            paired = vertices[(-np.arange(64)) % 64]
            fade = .5 * smoothstep(row / 7)
            correction[vertices] = (up[paired] - up[vertices]) * fade
            operator[vertices] = (basis[paired] - basis[vertices]) * fade
        reattach(correction, np.zeros_like(sp))
        reattach(operator, np.zeros((len(sp), count)))
        corrections.append(correction)
        product_basis.append(operator)
    cases = pose_cases(names, manifest, pose_paths)
    base_triangles = rp[ids]
    base_cross = np.cross(base_triangles[:, 1]-base_triangles[:, 0], base_triangles[:, 2]-base_triangles[:, 0])
    norm2 = np.sum(base_cross**2, axis=1)
    region = (base_triangles[:, :, 1].max(1) > .005) & (base_triangles[:, :, 1].min(1) < .112) & (base_triangles[:, :, 2].min(1) > .045)
    valid_xy = region & (abs(base_cross[:, 2]) > 1e-10)
    valid_3d = region & (norm2 > 1e-20)
    plans = []
    for name, w in cases:
        reference = deformed(old, old_ring, w) + pivot
        current = deformed(g, ring, w) + pivot
        current += w[ui] * (up-original_up)
        operator = w[ui] * basis
        for pi, correction, derivative in zip(product_ids, corrections, product_basis, strict=True):
            current += w[pi] * (correction-g.read(ring['targets'][pi]['POSITION']))
            operator += w[pi] * derivative
        v = reference[ids]
        cross = np.cross(v[:, 1]-v[:, 0], v[:, 2]-v[:, 0])
        xy = cross[:, 2] / np.where(abs(base_cross[:, 2]) > 1e-30, base_cross[:, 2], 1)
        dot = np.sum(cross*base_cross, axis=1) / np.maximum(norm2, 1e-30)
        plans.append(dict(name=name, position=current, operator=operator,
                          sourceXY=xy, source3D=dot,
                          validXY=valid_xy & (xy >= -1e-8), valid3D=valid_3d & (dot >= -1e-8)))
    q = np.eye(count)
    # Local adjacent changes prefer a smooth transition to the fixed vertices.
    index = {int(vertex): i for i, vertex in enumerate(free)}
    edges = np.unique(np.sort(np.concatenate([ids[:, [0, 1]], ids[:, [1, 2]], ids[:, [2, 0]]]), axis=1), axis=0)
    for x, y in edges:
        if x not in index and y not in index:
            continue
        row = np.zeros(count)
        if x in index: row[index[int(x)]] = 1
        if y in index: row[index[int(y)]] -= 1
        q += .3 * np.outer(row, row)
    delta = np.zeros(count)
    constraint_rows, bounds, keys, history = [], [], set(), []
    for iteration in range(8):
        violations = []
        for plan in plans:
            points = plan['position'].copy()
            points[:, 1] += plan['operator'] @ delta
            v = points[ids]
            cross = np.cross(v[:, 1]-v[:, 0], v[:, 2]-v[:, 0])
            xy = cross[:, 2] / np.where(abs(base_cross[:, 2]) > 1e-30, base_cross[:, 2], 1)
            dot = np.sum(cross*base_cross, axis=1) / np.maximum(norm2, 1e-30)
            for metric, values, valid in (('XY', xy, plan['validXY']), ('3D', dot, plan['valid3D'])):
                for ti in np.flatnonzero(valid & (values < -1e-8)):
                    key = (plan['name'], metric, int(ti))
                    violations.append(dict(pose=key[0], metric=metric, triangle=int(ti), ratio=float(values[ti])))
                    if key in keys:
                        continue
                    vv = plan['position'][ids[ti]]
                    coeff_x = np.array([vv[1, 2]-vv[2, 2], vv[2, 2]-vv[0, 2], vv[0, 2]-vv[1, 2]])
                    coeff_z = np.array([vv[2, 0]-vv[1, 0], vv[0, 0]-vv[2, 0], vv[1, 0]-vv[0, 0]])
                    neutral = base_cross[ti]
                    if metric == 'XY':
                        coefficients = coeff_z / neutral[2]
                    else:
                        coefficients = (coeff_x*neutral[0]+coeff_z*neutral[2]) / norm2[ti]
                    row = coefficients @ plan['operator'][ids[ti]]
                    assert np.linalg.norm(row) > 1e-6, ('Fold is outside the permitted local ROI', key, ids[ti].tolist(), float(values[ti]))
                    initial = values[ti] - row @ delta
                    source_ratio = plan['source'+metric][ti]
                    margin = max(1e-4, min(.005, .5*max(source_ratio, 0)))
                    constraint_rows.append(row)
                    bounds.append(margin-initial)
                    keys.add(key)
        history.append(dict(iteration=iteration, violations=violations, constraints=len(keys)))
        print('POSE_SAFETY', iteration, 'new reversals', len(violations), 'constraints', len(keys), flush=True)
        if not violations:
            break
        delta = project_qp(q, constraint_rows, bounds)
    else:
        raise RuntimeError('Local safety cutting planes did not converge')
    up[:, 1] += basis @ delta
    for correction, derivative in zip(corrections, product_basis, strict=True):
        correction[:, 1] += derivative @ delta
    assert np.array_equal(up[protected], original_up[protected])
    for group in groups:
        assert np.array_equal(up[group[:64]], original_up[group[:64]])
        assert np.max(abs(up[group[-64:]])) == 0
    reattach(up, skin_up)
    for correction in corrections:
        reattach(correction, np.zeros_like(sp))
        assert np.max(abs(correction[np.concatenate([group[-64:] for group in groups])])) == 0
    out.mkdir()
    after = edit.doc['meshes'][0]['primitives'][1]
    after['targets'][ui]['POSITION'] = edit.add(up, 'VEC3')
    for pi, correction in zip(product_ids, corrections, strict=True):
        after['targets'][pi]['POSITION'] = edit.add(correction, 'VEC3')
    model = out/'character.glb'
    edit.write(model)
    sha = hashlib.sha256(model.read_bytes()).hexdigest()
    manifest['modelSha256'] = sha
    manifest['id'] += '-local-pose-safety'
    (out/'avatar.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), 'utf-8')
    budget_path = out/'production-budget.json'
    subprocess.run([str(JAVA), '-cp', JAVA_CP, str(REPO/'scripts/AvatarAssetBudgetCheck.java'), str(model), str(budget_path)], check=True)
    budget = json.loads(budget_path.read_text('utf-8'))
    assert budget['passed'] and budget['modelSha256'] == sha
    report = dict(modelSha256=sha, inputModelSha256=INPUT_SHA, sourceModelSha256=SOURCE_SHA,
                  caseCount=len(cases), restoredSourceVertices=restored.tolist(), localFreeVertices=free.tolist(),
                  protectedGrayVertices=protected.tolist(), maximumLocalAdjustmentMeters=float(abs(delta).max()),
                  maximumRingUpChangeMeters=float(abs(up-original_up).max()),
                  restoredRowMaximumChangeMeters=float(abs(old_up[restored]-original_up[restored]).max()),
                  budget=budget,
                  bridgeAttachmentErrorMeters=max(errors), cuttingPlaneHistory=history,
                  modifiedFields='Ring Up and its two blink products only; wide Skin and all static attributes/other morphs retained',
                  artistAccepted=False, deviceInstalled=False,
                  scope='33 fixed-pose source-relative XY and neutral-cross constraints, float64 author proof. Independent float32/readback/closure and actual artwork remain required.')
    (out/'pose-safety-authoring.json').write_text(json.dumps(report, indent=2), 'utf-8')
    print(json.dumps({k: v for k, v in report.items() if not isinstance(v, list)}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', required=True)
    parser.add_argument('--candidate', required=True)
    parser.add_argument('--probes', required=True)
    parser.add_argument('--poses', action='append', required=True)
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    author(args.source, args.candidate, args.probes, args.poses, args.out)
