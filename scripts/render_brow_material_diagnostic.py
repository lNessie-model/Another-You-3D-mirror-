"""Separate texture shadows from actual brow surface deformation in Blender."""
import argparse
import hashlib
import json
import sys
from pathlib import Path

import bpy
from mathutils import Matrix, Vector

parser = argparse.ArgumentParser()
parser.add_argument('--model', required=True)
parser.add_argument('--poses', required=True)
parser.add_argument('--out', required=True)
args = parser.parse_args(sys.argv[sys.argv.index('--')+1:])
model = Path(args.model)
data = json.loads(Path(args.poses).read_text('utf-8'))
assert hashlib.sha256(model.read_bytes()).hexdigest() == data['modelSha256']
out = Path(args.out)
assert not out.exists()
out.mkdir()
scene = bpy.context.scene
objects = {obj.name: obj for obj in scene.objects}
mesh_objects = [obj for obj in objects.values() if obj.type == 'MESH']
original_materials = {obj.name: list(obj.data.materials) for obj in mesh_objects}
original_smoothing = {obj.name: [p.use_smooth for p in obj.data.polygons] for obj in mesh_objects}
convert = Matrix(((1, 0, 0, 0), (0, 0, -1, 0), (0, 1, 0, 0), (0, 0, 0, 1)))
inverse = convert.inverted()
camera = scene.camera
center = Vector((.003, -.082, .033))
camera.data.type = 'ORTHO'
camera.data.ortho_scale = .09
camera.location = center + Vector((0, -1, 0))
camera.rotation_euler = (center-camera.location).to_track_quat('-Z', 'Y').to_euler()
scene.render.resolution_x = scene.render.resolution_y = 800
rows = []
for mode in ('clay-smooth', 'clay-flat', 'albedo-only'):
    for obj in mesh_objects:
        for index, original in enumerate(original_materials[obj.name]):
            material = original.copy()
            tree = material.node_tree
            if mode.startswith('clay'):
                tree.nodes.clear()
                output = tree.nodes.new('ShaderNodeOutputMaterial')
                shader = tree.nodes.new('ShaderNodeBsdfPrincipled')
                shader.inputs['Base Color'].default_value = (.45, .45, .45, 1)
                shader.inputs['Roughness'].default_value = .8
                tree.links.new(shader.outputs['BSDF'], output.inputs['Surface'])
            else:
                shader = next(node for node in tree.nodes if node.type == 'BSDF_PRINCIPLED')
                output = next(node for node in tree.nodes if node.type == 'OUTPUT_MATERIAL')
                base = shader.inputs['Base Color']
                emission = tree.nodes.new('ShaderNodeEmission')
                if base.is_linked:
                    tree.links.new(base.links[0].from_socket, emission.inputs['Color'])
                else:
                    emission.inputs['Color'].default_value = base.default_value
                tree.links.new(emission.outputs[0], output.inputs['Surface'])
            obj.data.materials[index] = material
        for polygon, smooth in zip(obj.data.polygons, original_smoothing[obj.name], strict=True):
            polygon.use_smooth = False if mode == 'clay-flat' else smooth
    for pose in data['poses']:
        if pose['name'] not in ('neutral', 'brow-up'):
            continue
        for node in pose['nodes']:
            obj = objects[node['name']]
            values = node['worldMatrixColumnMajor']
            matrix = Matrix(tuple(tuple(values[c*4+r] for c in range(4)) for r in range(4)))
            obj.matrix_world = convert @ matrix @ inverse
            if node['mesh'] >= 0 and obj.data.shape_keys:
                mesh = pose['meshes'][node['mesh']]
                weights = dict(zip(mesh['targetNames'], mesh['weights'], strict=True))
                for key in obj.data.shape_keys.key_blocks:
                    if key.name != 'Basis':
                        key.value = weights[key.name]
        bpy.context.view_layer.update()
        image = out/(mode+'-'+pose['name']+'.png')
        scene.render.filepath = str(image)
        bpy.ops.render.render(write_still=True)
        rows.append(dict(mode=mode, pose=pose['name'], image=str(image)))
(out/'diagnostic-report.json').write_text(json.dumps(dict(
    modelSha256=data['modelSha256'], states=rows,
    scope='Actual production pose matrices and shapes. Clay removes all texture/normal maps; flat clay also removes smooth normal interpolation. Albedo emission removes lighting. No GLB or original workfile changed.'),
    indent=2), 'utf-8')
