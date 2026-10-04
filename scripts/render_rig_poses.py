"""Blender previews driven by exported production AvatarRig world matrices and weights."""
from pathlib import Path
import argparse, hashlib, json, sys
import bpy
from mathutils import Matrix, Vector

parser=argparse.ArgumentParser();parser.add_argument('--model',required=True);parser.add_argument('--poses',required=True);parser.add_argument('--out',required=True);parser.add_argument('--save-workfile')
a=parser.parse_args(sys.argv[sys.argv.index('--')+1:]);model=Path(a.model);data=json.loads(Path(a.poses).read_text('utf-8'))
assert hashlib.sha256(model.read_bytes()).hexdigest()==data['modelSha256']
out=Path(a.out);out.mkdir(parents=True,exist_ok=False)
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False);bpy.ops.import_scene.gltf(filepath=str(model.resolve()))
objects={o.name:o for o in bpy.context.scene.objects};scene=bpy.context.scene
conversion=Matrix(((1,0,0,0),(0,0,-1,0),(0,1,0,0),(0,0,0,1)));inverse=conversion.inverted()
scene.render.engine='BLENDER_EEVEE_NEXT';scene.render.resolution_x=800;scene.render.resolution_y=800;scene.render.resolution_percentage=100
scene.render.image_settings.file_format='PNG';scene.render.film_transparent=False;scene.view_settings.view_transform='AgX'
scene.world.use_nodes=True;scene.world.node_tree.nodes['Background'].inputs[0].default_value=(.055,.06,.075,1);scene.world.node_tree.nodes['Background'].inputs[1].default_value=.65
points=[o.matrix_world@Vector(v) for o in objects.values() if o.type=='MESH' for v in o.bound_box]
low=Vector(tuple(min(v[i] for v in points) for i in range(3)));high=Vector(tuple(max(v[i] for v in points) for i in range(3)));center=(low+high)*.5;size=high-low;radius=max(size)*3
bpy.ops.object.camera_add();camera=bpy.context.object;camera.data.type='ORTHO';camera.data.ortho_scale=max(size)*1.14;scene.camera=camera
for offset,power,factor in [(Vector((-.55,-.7,.6)),850,1),(Vector((.65,-.6,.2)),500,.75),(Vector((0,.65,.65)),800,.8)]:
    bpy.ops.object.light_add(type='AREA',location=center+offset*radius);light=bpy.context.object;light.data.energy=power*max(size)**2;light.data.shape='DISK';light.data.size=max(size)*3*factor
    light.rotation_euler=(center-light.location).to_track_quat('-Z','Y').to_euler()
rows=[]
for pose in data['poses']:
    for node in pose['nodes']:
        obj=objects[node['name']];values=node['worldMatrixColumnMajor'];matrix=Matrix(tuple(tuple(values[c*4+r] for c in range(4)) for r in range(4)))
        obj.matrix_world=conversion@matrix@inverse
        if node['mesh']>=0 and obj.data.shape_keys:
            mesh=pose['meshes'][node['mesh']];weights=dict(zip(mesh['targetNames'],mesh['weights']))
            for key in obj.data.shape_keys.key_blocks:
                if key.name!='Basis':key.value=weights[key.name]
    bpy.context.view_layer.update()
    views=[('front',Vector((0,-1,0)))]
    if pose['name'] in ('neutral','blink-half','blink-both','gaze-left','gaze-blink','jaw-open','jaw-open-mouth-close','smile-jaw','smile','smile-mild','brow-mixed','brow-up','wide'):
        views.append(('oblique',Vector((.5,-1,.10)).normalized()))
    for name,direction in views:
        camera.location=center+direction*radius;camera.rotation_euler=(center-camera.location).to_track_quat('-Z','Y').to_euler()
        scene.render.filepath=str(out/(pose['name']+'-'+name+'.png'));bpy.ops.render.render(write_still=True)
        rows.append({'pose':pose['name'],'view':name,'image':scene.render.filepath})
    print('RIG_POSE_RENDERED '+pose['name'],flush=True)
report={'model':str(model.resolve()),'modelSha256':data['modelSha256'],'blender_version':bpy.app.version_string,'states':rows,
        'scope':'Actual production rig weights and joint matrices rendered offline; not device optical output, FPS, or artist acceptance'}
(out/'render-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
if a.save_workfile:
    for pose in data['poses'][:1]:
        for node in pose['nodes']:
            obj=objects[node['name']];values=node['worldMatrixColumnMajor'];matrix=Matrix(tuple(tuple(values[c*4+r] for c in range(4)) for r in range(4)));obj.matrix_world=conversion@matrix@inverse
            if node['mesh']>=0 and obj.data.shape_keys:
                for key in obj.data.shape_keys.key_blocks:
                    if key.name!='Basis':key.value=0
    bpy.context.view_layer.update();workfile=Path(a.save_workfile);assert not workfile.exists();bpy.ops.file.pack_all();bpy.ops.wm.save_as_mainfile(filepath=str(workfile))
print('RIG_POSES_COMPLETE '+str(out),flush=True)
