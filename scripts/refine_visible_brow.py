"""Fit the actual textured eyebrow on the accepted Stage 11 eye annulus.

The final wide-carrier mode moves the forehead above the eyebrows smoothly,
then fits the actual gray eyebrow texture on the original eye annulus. The
reference-scaling mode preserves the earlier reviewed diagnostic candidate.
Only Skin Up and Ring Up/two blink products change. Artistic and ocular
acceptance remain separate from author-time constraints and decoder budgets.
"""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

import numpy as np

from head_glb_edit import GlbEdit
from refine_head_expression import attached_delta
from head_surface_topology import FrontSurface
from solve_brow_constraints import solve as solve_constraints

SOURCE_SHA = '11397607700b2223d63b5cd05bb2f929fbc18d3dbc80b71ab47e0375a909041d'
LABELS = (
    'left gray body', 'left gray inner body', 'left gray end candidate',
    'left gray inner edge', 'right gray end candidate',
    'right gray inner body', 'right gray body',
)
SKIN_SCALE = .65
TARGETS = np.array([.001, .0035, .006, .006, .006, .003, .001]) * SKIN_SCALE
REFERENCE_SHA = '18c89183989a271f721d1bfd2c6a767ddae3a261c6ee0621f7f018624e1a026e'
CARRIER_SHA = 'f7f66125d316b7da7c145024d70a3663263198f8f39365a54c4b29dab67fa5cb'
PREFIX = 2080
JAVA = Path('C:/Program Files/Java/jdk-17/bin/java.exe')
REPO = Path(__file__).resolve().parents[1]
JAVA_CP = ';'.join(str(p) for p in (
    REPO.parent/'output/mirror-assets/20261005/face-geometry-review/java-classes',
    REPO/'app/build/blender-controls-reference',
    REPO/'app/build/avatar-tests/json-20240303.jar',
))


def smoothstep(value):
    value = np.clip(value, 0, 1)
    return value * value * (3 - 2 * value)


def wide_forehead_field(carrier_path, pivot, skin_positions, ring_positions, groups):
    """Evaluate once before cuts, then interpolate to keep T junctions exact."""
    carrier = GlbEdit(Path(carrier_path)/'character.glb').source
    assert hashlib.sha256(carrier.raw).hexdigest() == CARRIER_SHA
    primitive = carrier.doc['meshes'][0]['primitives'][0]
    positions = carrier.read(primitive['attributes']['POSITION']).astype(float) + pivot
    triangles = carrier.read(primitive['indices']).reshape(-1, 3)
    assert len(positions) == 12973 and len(triangles) == 10932
    carrier_head = [node for node in carrier.doc['nodes'] if node['name'] == 'Head']
    assert len(carrier_head) == 1 and np.array_equal(carrier_head[0]['translation'], pivot)
    value = np.zeros_like(positions)
    lower = smoothstep((positions[:, 1] - .015) / .018)
    upper = 1 - smoothstep((positions[:, 1] - .065) / .050)
    sideways = 1 - smoothstep((abs(positions[:, 0] - .0015) - .04) / .02)
    front = smoothstep((positions[:, 2] - .025) / .025)
    value[:, 1] = .0045 * lower * upper * sideways * front
    skin_field = np.zeros_like(skin_positions)
    affected = positions[triangles[np.any(value[triangles, 1] > 0, axis=1)]].reshape(-1, 3)
    low, high = affected.min(0) - 2e-7, affected.max(0) + 2e-7
    selection = np.flatnonzero(((skin_positions >= low) & (skin_positions <= high)).all(1))
    assert len(selection) > 100, 'Wide carrier did not select the forehead'
    skin_field[selection], error = attached_delta(
        skin_positions[selection], positions, triangles, value)
    assert error < 1e-7, 'New Skin point is not on its source carrier'
    projected = FrontSurface(np.concatenate([positions, value], axis=1), triangles)
    ring_field = np.zeros_like(ring_positions)
    for group in groups:
        for row in range(8):
            fade = 1 - smoothstep(row / 7)
            for vertex in group[row*64:(row+1)*64]:
                ring_field[vertex] = projected.sample(*ring_positions[vertex, :2])[3:] * fade
        # Outer row follows the actual 3D Skin carrier, rather than a view ray.
        ring_field[group[:64]], outer_error = attached_delta(
            ring_positions[group[:64]], positions, triangles, value)
        assert outer_error < 1e-7
        error = max(error, outer_error)
    return skin_field, ring_field, float(error)


def validated_probes(path, positions, triangles, lookup):
    data = json.loads(Path(path).read_text('utf-8'))
    assert data['modelSha256'] == SOURCE_SHA, 'Probe source fingerprint differs'
    rows = data['rows']
    assert len(rows) == len(LABELS) == len(TARGETS), 'Expected seven real-pigment probes'
    assert tuple(row['label'] for row in rows) == LABELS, 'Probe order or anatomy changed'
    for row in rows:
        ids = np.asarray(row['vertexIds'])
        bary = np.asarray(row['barycentric'], dtype=float)
        assert ids.shape == bary.shape == (3,)
        assert np.issubdtype(ids.dtype, np.integer)
        assert (ids >= 0).all() and (ids < PREFIX).all()
        assert (lookup[ids] >= 0).all(), 'Probe is outside the original eye annulus'
        triangle_id = row['ringTriangle']
        assert isinstance(triangle_id, int) and 0 <= triangle_id < len(triangles)
        assert np.array_equal(ids, triangles[triangle_id]), 'Probe parent triangle differs'
        assert np.isfinite(bary).all() and (bary >= -1e-8).all()
        assert abs(float(bary.sum()) - 1) < 1e-8
        point = np.asarray(row['gltfWorld'], dtype=float)
        assert point.shape == (3,) and np.isfinite(point).all()
        assert np.max(abs(bary @ positions[ids] - point)) < 1e-7, 'Probe position mismatch'
    return rows


def author(source, probes, out, reference, carrier=None):
    source, out = Path(source), Path(out)
    assert not out.exists(), 'Preserve existing assets and evidence'
    edit = GlbEdit(source/'character.glb')
    g = edit.source
    assert hashlib.sha256(g.raw).hexdigest() == SOURCE_SHA, 'Use accepted Stage 11 only'
    manifest = json.loads((source/'avatar.json').read_text('utf-8'))
    assert manifest['modelSha256'] == SOURCE_SHA
    mesh = g.doc['meshes'][0]
    assert mesh['name'] == 'Face' and len(mesh['primitives']) == 3
    names = mesh['extras']['targetNames']
    assert len(names) == 58 and len(set(names)) == 58
    ui = names.index('browInnerUp')
    product_names = ['correctiveBlinkBrowInnerUp'+side for side in ('Left', 'Right')]
    products = [names.index(name) for name in product_names]
    skin, ring = mesh['primitives'][:2]
    head = [node for node in g.doc['nodes'] if node['name'] == 'Head']
    assert len(head) == 1 and len(head[0]['translation']) == 3
    face_nodes = [node for node in g.doc['nodes'] if node.get('mesh') == 0]
    assert len(face_nodes) == 1 and face_nodes[0]['name'] == 'Face'
    pivot = np.asarray(head[0]['translation'], dtype=float)
    sp = g.read(skin['attributes']['POSITION']).astype(float) + pivot
    rp = g.read(ring['attributes']['POSITION']).astype(float) + pivot
    assert len(sp) == 13975 and len(rp) == 3003, 'Stage 11 anatomy changed'
    ids = g.read(ring['indices']).reshape(-1, 3)
    assert len(ids) == 4532
    assert np.array_equal(ids[-2:], [[3001, 2304, 2303], [3001, 2303, 3002]])
    assert np.max(abs(rp[[3001, 3002]] - sp[[11829, 11904]])) < 1e-8
    old = g.read(ring['targets'][ui]['POSITION']).astype(float)
    skin_old = g.read(skin['targets'][ui]['POSITION']).astype(float)
    assert np.max(abs(skin_old[:, (0, 2)])) == 0
    skin_new = skin_old * SKIN_SCALE
    new = old.copy()
    groups = [np.arange(1056, 1568), np.arange(1568, 2080)]
    for group, sign in zip(groups, (1, -1), strict=True):
        assert (rp[group, 1] > .01).all() and (sign * rp[group, 0] > .004).all()
        assert np.max(abs(old[group[-64:]])) == 0, 'Inner eyelid is not fixed'
    carrier_error = 0
    targets = TARGETS
    if carrier is not None:
        skin_new, new, carrier_error = wide_forehead_field(carrier, pivot, sp, rp, groups)
        targets = np.array([.0017, .003, .0045, .0045, .0045, .003, .0017])
    eye = np.concatenate(groups)
    lookup = np.full(len(rp), -1, dtype=int)
    lookup[eye] = np.arange(len(eye))
    rows = validated_probes(probes, rp, ids, lookup)
    out.mkdir()
    n = len(eye)
    a, b, labels = [], [], []

    def add(vertices, weights, target, label):
        assert len(vertices) == len(weights)
        constraint = np.zeros(n)
        for vertex, value in zip(vertices, weights, strict=True):
            assert 0 <= vertex < len(lookup) and lookup[vertex] >= 0
            constraint[lookup[vertex]] += value
        a.append(constraint)
        b.append(target)
        labels.append(label)

    for group in groups:
        for vertex in group[:64]:
            add([vertex], [1], new[vertex, 1] if carrier is not None else old[vertex, 1] * SKIN_SCALE, 'scaled outer')
        for vertex in group[-64:]:
            add([vertex], [1], 0, 'fixed inner')
    for row, target in zip(rows, targets, strict=True):
        add(row['vertexIds'], row['barycentric'], target, row['label'])
    eye_tri = ids[(ids < PREFIX).all(1) & np.isin(ids, eye).all(1)]
    assert len(eye_tri) == 1792
    edges = np.unique(np.sort(np.concatenate([
        eye_tri[:, [0, 1]], eye_tri[:, [1, 2]], eye_tri[:, [2, 0]],
    ]), axis=1), axis=0)
    adjacent = [set() for _ in range(n)]
    for x, y in edges:
        i, j = lookup[x], lookup[y]
        adjacent[i].add(j)
        adjacent[j].add(i)
    assert all(adjacent), 'Laplacian has an isolated vertex'
    lap = np.eye(n)
    for i, neighbors in enumerate(adjacent):
        lap[i, list(neighbors)] = -1 / len(neighbors)
    q = lap.T @ lap + .06 * np.eye(n)
    rhs = .06 * (new[eye, 1] if carrier is not None else old[eye, 1] * SKIN_SCALE)
    triangle = rp[eye_tri]
    ab = triangle[:, 1, :2] - triangle[:, 0, :2]
    ac = triangle[:, 2, :2] - triangle[:, 0, :2]
    determinant = ab[:, 0] * ac[:, 1] - ab[:, 1] * ac[:, 0]
    valid = np.flatnonzero(abs(determinant) > 1e-10)
    d = []
    for ti in valid:
        constraint = np.zeros(n)
        values = (ac[ti, 0]-ab[ti, 0], -ac[ti, 0], ab[ti, 0])
        for vertex, value in zip(eye_tri[ti], values, strict=True):
            constraint[lookup[vertex]] += value / determinant[ti]
        d.append(constraint)
    system = dict(a=np.asarray(a), b=np.asarray(b), qmat=q, rhs=rhs, areaRows=np.asarray(d))
    np.savez(out/'visible-brow-system.npz', **system, eyeVertices=eye, eyeTriangles=eye_tri[valid])
    # Either fit the wide field to actual pigment points, or scale a frozen
    # feasible response as a convex interpolation with neutral.
    if carrier is not None:
        fit, report = solve_constraints(system, out/'visible-brow-dual.npz')
        report.update(carrierModelSha256=CARRIER_SHA, carrierAttachmentErrorMeters=carrier_error,
                      method='Wide forehead field evaluated on pre-cut carrier, constrained actual eyebrow annulus',
                      field=dict(peakMeters=.0045, lowerY=[.015, .033], plateauUpperY=.065,
                                 upperY=.115, xFlatRadius=.04, xFadeRadius=.06, xCenter=.0015,
                                 zFrontFade=[.025, .050]))
    else:
        reference_path = Path(reference)
        ref = GlbEdit(reference_path/'character.glb').source
        assert hashlib.sha256(ref.raw).hexdigest() == REFERENCE_SHA
        ref_report = json.loads((reference_path/'visible-brow-fit.json').read_text('utf-8'))
        assert ref_report['sourceModelSha256'] == SOURCE_SHA
        assert ref_report['modelSha256'] == REFERENCE_SHA and ref_report['feasible']
        ref_mesh = ref.doc['meshes'][0]
        assert ref_mesh['extras']['targetNames'] == names
        for source_primitive, ref_primitive in zip(mesh['primitives'], ref_mesh['primitives'], strict=True):
            assert source_primitive['attributes'].keys() == ref_primitive['attributes'].keys()
            for attr, index in source_primitive['attributes'].items():
                assert np.array_equal(g.read(index), ref.read(ref_primitive['attributes'][attr]))
            assert np.array_equal(g.read(source_primitive['indices']), ref.read(ref_primitive['indices']))
        assert np.array_equal(skin_old, ref.read(ref_mesh['primitives'][0]['targets'][ui]['POSITION']))
        ref_up = ref.read(ref_mesh['primitives'][1]['targets'][ui]['POSITION']).astype(float)
        assert np.max(abs(ref_up[:, (0, 2)])) == 0
        fit = ref_up[eye, 1] * SKIN_SCALE
        equality_error = float(np.max(abs(system['a'] @ fit - system['b'])))
        minimum = float(np.min(1 + system['areaRows'] @ fit))
        assert equality_error < 1e-8 and minimum >= .2
        report = dict(feasible=True, referenceModelSha256=REFERENCE_SHA,
                      minimumProjectedAreaRatio=minimum, hardEqualityResidualMeters=equality_error,
                      method='0.65 of the verified reference Up response, convex interpolation with neutral',
                      scope='Author geometry and budget gates only; artistic and closed-eye acceptance require separate checks')
    report.update(sourceModelSha256=SOURCE_SHA, changedPrimitives=[0, 1],
                  changedTargets=['browInnerUp']+product_names, skinUpScale=None if carrier is not None else SKIN_SCALE,
                  modifiedFields=[dict(mesh='Face', primitive=0, targets=['browInnerUp']),
                                  dict(mesh='Face', primitive=1, targets=['browInnerUp']+product_names)])
    if not report['feasible']:
        (out/'visible-brow-fit.json').write_text(json.dumps(report, indent=2), 'utf-8')
        raise RuntimeError('Brow constraints failed; evidence saved and no GLB authored')
    new[eye, 1] = fit
    base = np.concatenate([sp, rp[:PREFIX]])
    skin_ids = g.read(skin['indices']).reshape(-1, 3)
    ring_ids = ids[(ids < PREFIX).all(1)] + len(sp)
    triangles = np.concatenate([skin_ids, ring_ids])
    near = (base[triangles, 1].max(1) > .008) & (base[triangles, 2].max(1) > .03)
    assert near.any()
    errors = []

    def reattach(field, skin_field):
        combined = np.concatenate([skin_field, field[:PREFIX]])
        field[PREFIX:3001], error = attached_delta(rp[PREFIX:3001], base, triangles[near], combined)
        field[[3001, 3002]] = skin_field[[11829, 11904]]
        errors.append(float(error))

    reattach(new, skin_new)
    skin_after, ring_after = edit.doc['meshes'][0]['primitives'][:2]
    skin_after['targets'][ui]['POSITION'] = edit.add(skin_new, 'VEC3')
    ring_after['targets'][ui]['POSITION'] = edit.add(new, 'VEC3')
    # Equalize paired upper/lower annulus displacements as in the source.
    # This corrects the inner eyelid gradually, not the entire visible brow.
    inner = np.concatenate([group[-64:] for group in groups])
    for group, pi in zip(groups, products, strict=True):
        correction = g.read(ring['targets'][pi]['POSITION']).astype(float)
        skin_product = g.read(skin['targets'][pi]['POSITION']).astype(float)
        assert np.max(abs(skin_product)) == 0
        for row in range(8):
            fade = smoothstep(row / 7)
            vertices = group[row*64:(row+1)*64]
            paired = vertices[(-np.arange(64)) % 64]
            correction[vertices] = (new[paired] - new[vertices]) * .5 * fade
        reattach(correction, skin_product)
        assert np.max(abs(correction[inner])) == 0, 'Blink product moved the inner rim'
        ring_after['targets'][pi]['POSITION'] = edit.add(correction, 'VEC3')
    assert np.max(abs(new[inner])) == 0
    target_path = out/'character.glb'
    edit.write(target_path)
    sha = hashlib.sha256(target_path.read_bytes()).hexdigest()
    manifest['id'] += '-visible-brow-v3-wide' if carrier is not None else '-visible-brow-v2'
    manifest['displayName'] += ' · 可见灰眉与眉间平滑候选'
    manifest['modelSha256'] = sha
    (out/'avatar.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), 'utf-8')
    assert target_path.stat().st_size < 32*1024*1024
    budget_path = out/'production-budget.json'
    subprocess.run([str(JAVA), '-cp', JAVA_CP, str(REPO/'scripts/AvatarAssetBudgetCheck.java'),
                    str(target_path), str(budget_path)], check=True)
    budget = json.loads(budget_path.read_text('utf-8'))
    assert budget['passed'] and budget['modelSha256'] == sha
    report.update(modelSha256=sha, fileBytes=target_path.stat().st_size,
                  budget=budget,
                  bridgeAttachmentErrorMeters=max(errors), innerEyelidResponseUnchanged=True,
                  fullBlinkSourceSurfacePreserved=False,
                  blinkCorrection='Source paired-annulus geometric formula, zero Skin, independently rebound bridge; visible eyebrow is not wholly cancelled',
                  constraints=[dict(label=label, targetMeters=float(target), actualMeters=float(row@fit))
                               for row, target, label in zip(a, b, labels, strict=True) if label in LABELS],
                  artistAccepted=False, deviceInstalled=False)
    (out/'visible-brow-fit.json').write_text(json.dumps(report, indent=2), 'utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', required=True)
    parser.add_argument('--probes', required=True)
    parser.add_argument('--out', required=True)
    parser.add_argument('--reference')
    parser.add_argument('--carrier')
    args = parser.parse_args()
    assert (args.reference is None) != (args.carrier is None), 'Select reference scaling or wide carrier'
    author(args.source, args.probes, args.out, args.reference, args.carrier)
