"""Independent read-only brow image/ray coordinates; writes a fresh report only."""
import argparse,json,sys
from pathlib import Path
import bpy
from mathutils import Matrix,Vector

p=argparse.ArgumentParser();p.add_argument('--poses',required=True);p.add_argument('--out',required=True);p.add_argument('--points')
a=p.parse_args(sys.argv[sys.argv.index('--')+1:]);data=json.loads(Path(a.poses).read_text('utf-8'))
objects={o.name:o for o in bpy.context.scene.objects};scene=bpy.context.scene
conversion=Matrix(((1,0,0,0),(0,0,-1,0),(0,1,0,0),(0,0,0,1)));inverse=conversion.inverted()
pose=data['poses'][0]
for node in pose['nodes']:
    obj=objects[node['name']];values=node['worldMatrixColumnMajor'];matrix=Matrix(tuple(tuple(values[c*4+r] for c in range(4)) for r in range(4)))
    obj.matrix_world=conversion@matrix@inverse
    if node['mesh']>=0 and obj.data.shape_keys:
        for key in obj.data.shape_keys.key_blocks:
            if key.name!='Basis':key.value=0
bpy.context.view_layer.update()
points=[o.matrix_world@Vector(v) for o in objects.values() if o.type=='MESH' for v in o.bound_box]
low=Vector(tuple(min(v[i] for v in points) for i in range(3)));high=Vector(tuple(max(v[i] for v in points) for i in range(3)))
center=(low+high)*.5;scale=max(high-low)*1.14;radius=max(high-low)*3
depsgraph=bpy.context.evaluated_depsgraph_get()
rows=[]
query=json.loads(Path(a.points).read_text('utf-8')) if a.points else [(384,307,'left inner brow top'),(388,315,'left inner brow bottom'),(372,303,'left brow body'),(350,298,'left arch'),(424,308,'right inner brow top'),(420,317,'right inner brow bottom'),(440,303,'right brow body'),(466,298,'right arch')]
for x,y,label in query:
    origin=center+Vector(((x+.5-400)/800*scale,-radius,(400-y-.5)/800*scale))
    hit,pos,normal,face,obj,matrix=scene.ray_cast(depsgraph,origin,Vector((0,1,0)),distance=radius*2)
    row={'pixel':[x,y],'label':label,'worldY':origin.z,'worldX':origin.x,'hit':hit}
    if hit:
        row.update({'gltfWorld':list(inverse@pos),'object':obj.name,'face':face})
        if obj.data.shape_keys:
            # The actual evaluated neutral hit is located on a triangle; record
            # its barycentric interpolation of the authored eyebrow delta.
            tri=obj.evaluated_get(depsgraph).data.polygons[face]
            row['material']=obj.data.materials[tri.material_index].name
            ids=list(tri.vertices)
            if len(ids)==3:
                v=[obj.matrix_world@obj.data.vertices[i].co for i in ids]
                ab=v[1]-v[0];ac=v[2]-v[0];q=pos-v[0]
                d00=ab.dot(ab);d01=ab.dot(ac);d11=ac.dot(ac);den=d00*d11-d01*d01
                u=(d11*q.dot(ab)-d01*q.dot(ac))/den;w=(d00*q.dot(ac)-d01*q.dot(ab))/den
                key=obj.data.shape_keys.key_blocks.get('browInnerUp')
                if key:
                    dv=[key.data[i].co-obj.data.vertices[i].co for i in ids]
                    delta=dv[0]*(1-u-w)+dv[1]*u+dv[2]*w
                    row['authoredUpDeltaGltf']=list(inverse.to_3x3()@delta)
    rows.append(row)
report={'modelSha256':data['modelSha256'],'cameraCenterBlender':list(center),'orthoScale':scale,'rows':rows,'scope':'Neutral front image pixels hit in the actual Blender scene; artist-picked eyebrow positions are approximate.'}
out=Path(a.out);assert not out.exists();out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(report,indent=2),'utf-8');print(json.dumps(report))
