"""Local lit-PBR oral colour edit for Geralt-layout candidate bundles.

Works with an upstream geometry candidate; source SHA is recorded, not hard-coded.
Does not edit POSITION, NORMAL, morph targets, UV, images, indices or tooth crowns.
Effective colours include the source sRGB texture decode AND material base factor.
"""
from pathlib import Path
import argparse
import copy
import hashlib
import json
import sys
import numpy as np


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write_json(path, data):
    with Path(path).open('x', encoding='utf-8') as stream:
        json.dump(data, stream, ensure_ascii=False, indent=2)


def layout_fingerprints(source):
    """Typed indices+UV pins fix the 96/96/96/1/72 and 500/80 partitions.

    This is intentionally a Geralt-family tool, not a generic same-count editor.
    Geometry and morph values may change in an upstream authoring candidate.
    """
    result = {}
    for name, pi in [('Face',2), ('UpperDentition',0), ('LowerDentition',0)]:
        mesh = next(m for m in source.doc['meshes'] if m.get('name') == name)
        primitive = mesh['primitives'][pi]
        digest = hashlib.sha256()
        for key, index in [('indices',primitive['indices']), ('uv',primitive['attributes']['TEXCOORD_0'])]:
            accessor = source.doc['accessors'][index]
            meaning = {k:accessor[k] for k in ['componentType','count','type']}
            meaning['normalized'] = accessor.get('normalized',False)
            if 'sparse' in accessor:
                raise ValueError('Sparse layout pins are unsupported')
            digest.update(json.dumps([key,meaning],sort_keys=True,separators=(',',':')).encode())
            digest.update(source.read(index).tobytes())
        result[name] = digest.hexdigest()
    def semantic(index):
        accessor=source.doc['accessors'][index]
        return {k:v for k,v in accessor.items() if k not in ['bufferView','byteOffset','min','max']}
    meshes=[]
    for mesh in source.doc['meshes']:
        primitives=[]
        for p in mesh['primitives']:
            primitives.append({'mode':p.get('mode',4),'material':p.get('material'),
                'attributes':{k:semantic(v) for k,v in p['attributes'].items()},
                'indices':semantic(p['indices']),
                'targets':[{k:semantic(v) for k,v in t.items()} for t in p.get('targets',[])]})
        meshes.append({'name':mesh.get('name'),'extras':mesh.get('extras'),'primitives':primitives})
    stable={'meshes':meshes,'nodes':source.doc['nodes'],'scenes':source.doc.get('scenes'),
            'scene':source.doc.get('scene'),'materials':source.doc['materials'],
            'textures':source.doc.get('textures'),'samplers':source.doc.get('samplers')}
    result['semantic-layout'] = hashlib.sha256(json.dumps(stable,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    return result


GERALT_LAYOUT_PINS = {'Face': 'ae4e792c9b8864455753e39728da6edb2ccb88a2c053d9d30545fb9b261a58b5', 'UpperDentition': '565c056fcaea35b158027fee737edcae8f752239e2bf13555b780dd4982f3287', 'LowerDentition': 'd863a2a2e2ce7f95ec01170c1c6668242803799bc2eedfb351fa5f43b3e3b56c', 'semantic-layout': 'ac1f7d53564f2a4f03a20398dca2f2f3f51b641e17b93e6069967fb1d92b559d'}


def edit(model, manifest, out, scripts_root):
    sys.path.insert(0, str(scripts_root))
    from head_glb_edit import GlbEdit
    from tripo_head_adapter import Glb
    model, manifest, out = map(Path, (model, manifest, out))
    if out.exists():
        raise ValueError('Refusing to overwrite any existing output directory')
    manifest_doc = json.loads(manifest.read_text('utf-8'))
    source_sha = sha(model)
    if manifest_doc.get('modelSha256') != source_sha:
        raise ValueError('Input avatar.json modelSha256 does not match input GLB')
    editor = GlbEdit(model)
    source = editor.source
    actual_layout = layout_fingerprints(source)
    if actual_layout not in (GERALT_LAYOUT_PINS, {'Face': 'ae4e792c9b8864455753e39728da6edb2ccb88a2c053d9d30545fb9b261a58b5', 'UpperDentition': '565c056fcaea35b158027fee737edcae8f752239e2bf13555b780dd4982f3287', 'LowerDentition': 'd863a2a2e2ce7f95ec01170c1c6668242803799bc2eedfb351fa5f43b3e3b56c', 'semantic-layout': '83aa1405e0e260785d00640c73227255b3bd7a77dc995f7e6fdf20040cde69c4'}):
        raise ValueError('Not the pinned Geralt topology/UV/material/node/control layout; refuse same-count assets')
    original_binary = bytes(editor.binary)
    source_doc = copy.deepcopy(source.doc)
    named = {mesh.get('name'): (mi, mesh) for mi, mesh in enumerate(source.doc['meshes'])}
    if len(named) != len(source.doc['meshes']):
        raise ValueError('Duplicate mesh names make oral attribution ambiguous')
    mi, face = named['Face']
    if len(face['primitives']) != 3:
        raise ValueError('Expected the explicit three-primitive Face layout')
    oral = face['primitives'][2]
    material_index = oral['material']
    material = source.doc['materials'][material_index]
    if material.get('name') != 'OralTissuePBR' or 'KHR_materials_unlit' in material.get('extensions', {}):
        raise ValueError('Expected isolated lit OralTissuePBR')
    users = [(a, b) for a, m in enumerate(source.doc['meshes'])
             for b, p in enumerate(m['primitives']) if p.get('material') == material_index]
    if users != [(mi, 2)]:
        raise ValueError('Oral roughness cannot safely alter a material shared outside oral tissue')
    affected = []
    allowed_bytes = np.zeros(len(original_binary), dtype=bool)

    def effective_base(primitive, output=False):
        mat = source.doc['materials'][primitive['material']]
        pbr = mat.get('pbrMetallicRoughness', {})
        uv = source.read(primitive['attributes']['TEXCOORD_0'])
        if 'baseColorTexture' in pbr:
            values = source.sample_base_colour(uv, primitive['material'])[:, :3].astype(np.float64)
            if output and primitive['material'] == material_index:
                values = values / np.asarray(pbr['baseColorFactor'][:3],dtype=np.float64)
            return values
        factor = np.asarray(pbr.get('baseColorFactor', [1, 1, 1, 1]), dtype=np.float64)
        return np.broadcast_to(factor[:3], (len(uv), 3)).copy()

    def patch_colour(mesh_index, primitive_index, primitive, rows, desired, region):
        index = primitive['attributes']['COLOR_0']
        accessor = source.doc['accessors'][index]
        if accessor['componentType'] != 5126 or accessor['type'] != 'VEC4' or accessor.get('normalized') or 'sparse' in accessor:
            raise ValueError('A dense float32 linear RGBA colour accessor is required')
        references = [(a, b, name) for a, m in enumerate(source.doc['meshes'])
                      for b, p in enumerate(m['primitives'])
                      for name, v in p['attributes'].items() if v == index]
        if references != [(mesh_index, primitive_index, 'COLOR_0')]:
            raise ValueError('Colour accessor alias outside the intended primitive')
        view = source.doc['bufferViews'][accessor['bufferView']]
        stride = view.get('byteStride', 16)
        offset = view.get('byteOffset', 0) + accessor.get('byteOffset', 0)
        old = source.read(index)
        base = effective_base(primitive)
        output_base = effective_base(primitive, output=True)
        rows = np.asarray(rows, dtype=int)
        desired = np.broadcast_to(np.asarray(desired, dtype=np.float64), (len(rows), 3))
        if not np.isfinite(desired).all() or (base[rows] <= 0).any():
            raise ValueError('Non-finite desired colour or zero base sample')
        values = (desired / output_base[rows]).astype('<f4')
        if (values < 0).any() or (values > 1).any():
            raise ValueError('Target exceeds the actual texture/factor range; refuse clipping')
        for r, value in zip(rows, values):
            begin = offset + int(r) * stride
            editor.binary[begin:begin+12] = value.tobytes()
            allowed_bytes[begin:begin+12] = True
        updated = old.copy()
        updated[rows, :3] = values
        if 'min' in accessor or 'max' in accessor:
            raise ValueError('Colour bounds metadata needs explicit support before modification')
        affected.append({'mesh': mesh_index, 'meshName': source.doc['meshes'][mesh_index]['name'],
            'primitive': primitive_index, 'attribute': 'COLOR_0', 'accessor': index,
            'region': region, 'rows': rows.tolist(), 'count': len(rows),
            'beforeEffectiveLinearMin': (old[rows,:3]*base[rows]).min(axis=0).tolist(),
            'beforeEffectiveLinearMax': (old[rows,:3]*base[rows]).max(axis=0).tolist(),
            'afterEffectiveLinearMin': (values*output_base[rows]).min(axis=0).tolist(),
            'afterEffectiveLinearMax': (values*output_base[rows]).max(axis=0).tolist(),
            'alphaPreserved': np.array_equal(old[:,3], updated[:,3])})

    if len(source.read(oral['attributes']['POSITION'])) != 361:
        raise ValueError('Expected 96/96/96/1/72 oral source layout')
    # Factor .2 -> 1 recovers dynamic range; compensate the own front rim's effective colour.
    front = source.read(oral['attributes']['COLOR_0'])[:96,:3] * effective_base(oral)[:96]
    patch_colour(mi, 2, oral, range(96), front, 'front-rim-effective-colour-preserved')
    patch_colour(mi, 2, oral, range(96,192), [.180,.040,.060], 'middle-wall')
    patch_colour(mi, 2, oral, range(192,288), [.060,.012,.020], 'rear-wall')
    patch_colour(mi, 2, oral, [288], [.035,.006,.010], 'rear-centre')
    tongue_rows = np.arange(289,361)
    old_tongue = source.read(oral['attributes']['COLOR_0'])[tongue_rows,:3] * effective_base(oral)[tongue_rows]
    # Preserve this model's original root-to-tip variation instead of a red flat plane.
    tongue = old_tongue * np.asarray([.280,.070,.095]) / np.asarray([.033,.0085,.013])
    patch_colour(mi, 2, oral, tongue_rows, tongue, 'own-tongue-original-gradient')
    for name in ['UpperDentition', 'LowerDentition']:
        dental_mi, mesh = named[name]
        if len(mesh['primitives']) != 1:
            raise ValueError('Unexpected dental primitive structure')
        primitive = mesh['primitives'][0]
        if len(source.read(primitive['attributes']['POSITION'])) != 580:
            raise ValueError('Expected 500 crown + 80 gum source layout')
        if source.doc['materials'][primitive['material']].get('name') != 'WarmWhiteDentition':
            raise ValueError('Unexpected shared dental material')
        patch_colour(dental_mi, 0, primitive, range(500,580), [.190,.045,.062], 'gum-only-not-crown')
    editor.doc['materials'][material_index]['pbrMetallicRoughness']['roughnessFactor'] = .62
    editor.doc['materials'][material_index]['pbrMetallicRoughness']['baseColorFactor'] = [1,1,1,1]
    changed = np.frombuffer(original_binary, dtype=np.uint8) != np.frombuffer(editor.binary, dtype=np.uint8)
    if np.any(changed & ~allowed_bytes):
        raise AssertionError('Binary edit escaped its exact colour byte mask')
    out.mkdir(parents=True)
    editor.write(out/'character.glb')
    actual = Glb(out/'character.glb')
    expected_doc = copy.deepcopy(source_doc)
    expected_doc['materials'][material_index]['pbrMetallicRoughness']['roughnessFactor'] = .62
    expected_doc['materials'][material_index]['pbrMetallicRoughness']['baseColorFactor'] = [1,1,1,1]
    if actual.doc != expected_doc:
        raise AssertionError('Unexpected GLB semantic field change')
    changed_colour_accessors = {row['accessor'] for row in affected}
    for index in range(len(source.doc['accessors'])):
        if index not in changed_colour_accessors and source.read(index).tobytes() != actual.read(index).tobytes():
            raise AssertionError('A protected accessor aliases the modified colour bytes')
    for a, b in [(mi,2), (named['UpperDentition'][0],0), (named['LowerDentition'][0],0)]:
        p, q = source.doc['meshes'][a]['primitives'][b], actual.doc['meshes'][a]['primitives'][b]
        for key in p['attributes']:
            x, y = source.read(p['attributes'][key]), actual.read(q['attributes'][key])
            if key != 'COLOR_0' and x.tobytes() != y.tobytes():
                raise AssertionError('Protected attribute changed: '+key)
        x, y = source.read(p['attributes']['COLOR_0']), actual.read(q['attributes']['COLOR_0'])
        protected = slice(0,0) if (a,b)==(mi,2) else slice(0,500)
        if x[protected].tobytes() != y[protected].tobytes() or x[:,3].tobytes() != y[:,3].tobytes():
            raise AssertionError('Front rim, tooth crown or alpha changed')
    output_front = actual.read(oral['attributes']['COLOR_0'])[:96,:3] * effective_base(oral,output=True)[:96]
    front_error = float(np.max(np.abs(output_front-front)))
    if front_error > 2e-9:
        raise AssertionError('Factor compensation changed effective front rim colour')
    updated_manifest = copy.deepcopy(manifest_doc)
    updated_manifest['modelSha256'] = sha(out/'character.glb')
    write_json(out/'avatar.json', updated_manifest)
    report = {'sourceModel': str(model.resolve()), 'sourceModelSha256': source_sha,
        'sourceManifestSha256': sha(manifest), 'modelSha256': sha(out/'character.glb'),
        'manifestSha256': sha(out/'avatar.json'), 'changedColours': affected,
        'changedMaterialFields': [{'index':material_index,'name':'OralTissuePBR',
            'field':'pbrMetallicRoughness.roughnessFactor','before':material['pbrMetallicRoughness']['roughnessFactor'],'after':.62},
            {'index':material_index,'name':'OralTissuePBR','field':'pbrMetallicRoughness.baseColorFactor',
             'before':material['pbrMetallicRoughness']['baseColorFactor'],'after':[1,1,1,1]}],
        'changedBinaryBytes': int(changed.sum()), 'allOtherBinaryBytesExact': True,
        'allOtherGlbSemanticFieldsExact': True, 'front96ColourByteExact': False,
        'front96EffectiveColourPreserved': True,'front96EffectiveMaxAbsLinearError':front_error,
        'toothCrownColoursExact': True, 'toothMaterialExact': True,
        'geometryNormalsUvsTargetsImagesNodesExact': True, 'manifestChangeOnlyModelSha256': True,
        'dependencyPins': [{'path':str(scripts_root/n),'sha256':sha(scripts_root/n)}
                           for n in ['head_glb_edit.py','tripo_head_adapter.py']],
        'geraltLayoutPins': actual_layout,
        'scope':'Only own oral RGB and gum RGB, OralTissuePBR factor .2 -> 1 with compensated effective rim colour and roughness .94 -> .62; no geometry, lighting or renderer change.',
        'artAccepted': False, 'deviceShaderChecked': False, 'cameraChecked': False,
        'fpsChecked': False, 'containsUnlitMaterialChange': False}
    write_json(out/'oral-colour-report.json', report)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', required=True, type=Path)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--scripts-root', type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args()
    report = edit(args.model,args.manifest,args.out,args.scripts_root)
    print(json.dumps({'modelSha256':report['modelSha256'],'changedBinaryBytes':report['changedBinaryBytes'],
                      'protectedFieldsExact':report['geometryNormalsUvsTargetsImagesNodesExact']},indent=2))
