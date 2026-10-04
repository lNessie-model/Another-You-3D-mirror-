"""Render actual GLB control nodes and deforming surface edges, not an invented armature."""
from pathlib import Path
import argparse,hashlib,json,sys
import bpy
from mathutils import Vector
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parent))
from tripo_head_adapter import Glb

parser=argparse.ArgumentParser();parser.add_argument('--model',required=True);parser.add_argument('--manifest',required=True);parser.add_argument('--out',required=True)
a=parser.parse_args(sys.argv[sys.argv.index('--')+1:]);model=Path(a.model);out=Path(a.out);out.mkdir(parents=True,exist_ok=False)
g=Glb(model);manifest=json.loads(Path(a.manifest).read_text('utf-8'));assert manifest['modelSha256']==hashlib.sha256(g.raw).hexdigest()
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False);bpy.ops.import_scene.gltf(filepath=str(model.resolve()))
scene=bpy.context.scene;face=bpy.data.objects['Face'];head=bpy.data.objects['Head'];objects={o.name:o for o in scene.objects}
scene.render.engine='BLENDER_EEVEE_NEXT';scene.render.resolution_x=1100;scene.render.resolution_y=1100;scene.render.resolution_percentage=100
scene.render.image_settings.file_format='PNG';scene.view_settings.view_transform='AgX'
scene.world.use_nodes=True;scene.world.node_tree.nodes['Background'].inputs[0].default_value=(.014,.023,.036,1);scene.world.node_tree.nodes['Background'].inputs[1].default_value=.5
def emission(name,rgb):
    mat=bpy.data.materials.new(name);mat.use_nodes=True;tree=mat.node_tree;tree.nodes.clear();output=tree.nodes.new('ShaderNodeOutputMaterial');light=tree.nodes.new('ShaderNodeEmission');light.inputs[0].default_value=(*rgb,1);light.inputs[1].default_value=1;tree.links.new(light.outputs[0],output.inputs[0]);return mat
cyan=emission('Head joint',(.15,.92,1));green=emission('Actual eyelid surface',(.18,1,.38));orange=emission('Actual lip surface',(1,.42,.12));yellow=emission('Eye nodes',(1,.8,.14));white=emission('Labels',(.86,.93,1))
gray=bpy.data.materials.new('Diagram surface');gray.use_nodes=True
shader=next(node for node in gray.node_tree.nodes if node.type=='BSDF_PRINCIPLED')
shader.inputs['Base Color'].default_value=(.16,.21,.28,1)
shader.inputs['Roughness'].default_value=.85
for slot in face.material_slots:slot.material=gray
points=[o.matrix_world@Vector(v) for o in objects.values() if o.type=='MESH' for v in o.bound_box]
low=Vector(tuple(min(v[i] for v in points) for i in range(3)));high=Vector(tuple(max(v[i] for v in points) for i in range(3)));center=(low+high)*.5;size=max(high-low)
bpy.ops.object.camera_add(location=center+Vector((0,-size*3,0)));camera=bpy.context.object;camera.data.type='ORTHO';camera.data.ortho_scale=size*1.65;camera.rotation_euler=(center-camera.location).to_track_quat('-Z','Y').to_euler();scene.camera=camera
for offset,power in [(Vector((-.2,-.4,.3)),50),(Vector((.3,-.3,.1)),30)]:
    bpy.ops.object.light_add(type='AREA',location=center+offset);light=bpy.context.object;light.data.energy=power*size*size;light.data.size=size*3;light.rotation_euler=(center-light.location).to_track_quat('-Z','Y').to_euler()
def lines(name,segments,material,width):
    curve=bpy.data.curves.new(name,'CURVE');curve.dimensions='3D';curve.bevel_depth=width;curve.bevel_resolution=0
    for start,end in segments:
        spline=curve.splines.new('POLY');spline.points.add(1)
        for point,coordinate in zip(spline.points,(start,end)):point.co=(*coordinate,1)
    obj=bpy.data.objects.new(name,curve);scene.collection.objects.link(obj);obj.data.materials.append(material);return obj
def text(name,label,x,z,material=white,scale=.006):
    curve=bpy.data.curves.new(name,'FONT');curve.body=label;curve.size=scale;curve.align_x='CENTER';curve.align_y='CENTER'
    obj=bpy.data.objects.new(name,curve);scene.collection.objects.link(obj);obj.location=(x,-.175,z);obj.rotation_euler=(np.pi/2,0,0);obj.data.materials.append(material)
def world(point):return np.array([point[0],-point[2]-.0007,point[1]])
primitive=g.doc['meshes'][0]['primitives'][0];p=g.read(primitive['attributes']['POSITION']).astype(float)+np.array(g.doc['nodes'][1]['translation']);ids=g.read(primitive['indices']).reshape(-1,3)
names=g.doc['meshes'][0]['extras']['targetNames'];blink=sum(np.linalg.norm(g.read(primitive['targets'][names.index(name)]['POSITION']),axis=1) for name in ('eyeBlinkLeft','eyeBlinkRight'))>1e-6
color=g.read(primitive['attributes']['COLOR_0']);cx=.004956244253708775;cy=-.031624458490810525
lip=(((p[:,0]-cx)/.0315)**2+((p[:,1]-cy)/.0141)**2<1.001)&(np.abs(color[:,:3]-1).max(axis=1)>1e-4)&(p[:,2]>.035)
for name,mask,mat in [('Eyelid deform edges',blink,green),('Lip deform edges',lip,orange)]:
    edges=set()
    for tri in ids:
        for u,v in ((tri[0],tri[1]),(tri[1],tri[2]),(tri[2],tri[0])):
            if mask[u] and mask[v]:edges.add(tuple(sorted((int(u),int(v)))))
    lines(name,[(world(p[u]),world(p[v])) for u,v in sorted(edges)],mat,.00012)
node_records=[];controls=[]
for name,material in [('Head',cyan),('LeftEye',yellow),('RightEye',yellow)]:
    actual=objects[name].matrix_world.translation.copy();node_records.append({'name':name,'blender_world_xyz':list(actual)})
    projected=actual.copy();projected.y=-.16
    bpy.ops.mesh.primitive_uv_sphere_add(segments=12,ring_count=8,radius=.002,location=projected);bpy.context.object.data.materials.append(material)
    controls.append((name,projected))
head_point=controls[0][1]
lines('Actual hierarchy, projected forward',[(head_point,point) for name,point in controls[1:]],cyan,.00028)
text('Title','ACTUAL HEAD RIG',0,.167,white,.009)
text('Eye label left','RightEye',-.106,.068,yellow)
text('Eye label right','LeftEye',.108,.068,yellow)
for name,point in controls[1:]:
    label_x=.108 if point.x>0 else -.106;lines(name+' label',[(point,(label_x,-.16,.061))],yellow,.00018)
text('Head label','Head pivot',0,-.13,cyan)
text('Lids legend','GREEN: blink / squint / wide surface',0,-.152,green,.0055)
text('Mouth legend','ORANGE: lip and jaw morph surface',0,-.163,orange,.0055)
text('Depth note','Control nodes projected forward for visibility',0,-.177,white,.0045)
scene.render.filepath=str(out/'binding-front.png');bpy.ops.render.render(write_still=True)
report={'model_sha256':manifest['modelSha256'],'nodes':g.doc['nodes'],'glb_skins':g.doc.get('skins',[]),'rig':manifest['rig'],
        'rendered_control_nodes':node_records,'target_count':len(names),'direct_bindings':len(manifest['bindings']),'derived_bindings':len(manifest['derivedBindings']),
        'scope':'Actual neutral geometry/control-node positions. Green/orange are actual deforming mesh edges. Display nodes/links projected toward camera; no invented face bones, skin weights, optical qualification or art acceptance.'}
(out/'binding-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
bpy.ops.file.pack_all();bpy.ops.wm.save_as_mainfile(filepath=str(out/'binding-visualization.blend'))
print('BINDING_RENDER_COMPLETE '+str(out),flush=True)
