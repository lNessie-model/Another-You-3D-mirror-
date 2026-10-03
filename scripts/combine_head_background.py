"""Append a static vertex-colour prop without changing head morph or atlas bytes.

Input orientation must have been observed separately: these Tripo assets face +X.
The prop belongs to Root, so head and jaw controls never rotate the architecture.
"""
from pathlib import Path
import argparse,copy,hashlib,json,struct
import numpy as np
from tripo_head_adapter import Glb,rotate_x_front

def combine(head_dir,background,out,background_mesh=None,static_fit=None):
    head_dir=Path(head_dir);background=Path(background);out=Path(out)
    if out.exists():raise FileExistsError('Preserve prior combined asset')
    h=Glb(head_dir/'character.glb');bg=Glb(background);doc=copy.deepcopy(h.doc)
    assert len(bg.doc['nodes'])==1 and not any(bg.doc.get(k) for k in ['skins','animations'])
    assert len(bg.doc['meshes'])==1 and len(bg.doc['meshes'][0]['primitives'])==1
    assert doc['nodes'][0]['name']=='Root' and doc['nodes'][1]['name']=='Head'
    pr=bg.doc['meshes'][0]['primitives'][0]
    if background_mesh:
        data=np.load(background_mesh,allow_pickle=False)
        assert str(data['source_sha256'])==hashlib.sha256(bg.raw).hexdigest(),'LOD is for a different source'
        p=rotate_x_front(data['positions']).astype(float);n=rotate_x_front(data['normals']).astype(float)
        colour=bg.sample_base_colour(data['uv'],pr['material']);ids=data['indices'].reshape(-1)
    else:
        p=rotate_x_front(bg.read(pr['attributes']['POSITION'])).astype(float)
        n=rotate_x_front(bg.read(pr['attributes']['NORMAL'])).astype(float)
        colour=bg.bake_base_colour(pr);ids=bg.read(pr['indices']).reshape(-1)
    assert len(p)==len(n)==len(colour) and ids.max()<len(p) and np.isfinite(p).all() and np.isfinite(n).all()
    scale=.40/np.ptp(p[:,1]);p*=scale;p[:,0]-=(p[:,0].min()+p[:,0].max())/2;p[:,1]-=(p[:,1].min()+p[:,1].max())/2
    p[:,2]*=.28;p[:,2]-=.14;n[:,2]/=.28;n/=np.linalg.norm(n,axis=1)[:,None]
    visibility=None
    if static_fit:
        from static_view_mesh import visible_subset
        camera=json.loads(Path(static_fit).read_text('utf-8-sig'))
        assert camera['displayAspect']==.625 and camera['cameraDistance']==3 and camera['maxEyeOffset']==.2
        p,n,colour,ids,visibility=visible_subset(p,n,colour,ids,camera['fitMatrix'])
    original_vertices=sum(h.doc['accessors'][r['attributes']['POSITION']]['count'] for m in h.doc['meshes'] for r in m['primitives'])
    assert original_vertices+len(p)<=20000
    binary=bytearray(h.raw[h.start:h.start+doc['buffers'][0]['byteLength']])
    original_binary=bytes(binary)
    def add(array,kind):
        array=np.asarray(array,dtype='<u2' if kind=='SCALAR' else '<f4')
        while len(binary)%4:binary.append(0)
        view=len(doc['bufferViews']);doc['bufferViews'].append({'buffer':0,'byteOffset':len(binary),'byteLength':array.nbytes});binary.extend(array.tobytes())
        row={'bufferView':view,'componentType':5123 if kind=='SCALAR' else 5126,'count':len(array),'type':kind}
        if kind=='VEC3':row.update(min=array.min(axis=0).tolist(),max=array.max(axis=0).tolist())
        index=len(doc['accessors']);doc['accessors'].append(row);return index
    material=len(doc['materials']);doc['materials'].append({'name':'StaticBackgroundColour','pbrMetallicRoughness':{'baseColorFactor':[1,1,1,1],'metallicFactor':0,'roughnessFactor':.9}})
    mesh=len(doc['meshes']);doc['meshes'].append({'name':'StaticBackground','primitives':[{'attributes':{'POSITION':add(p,'VEC3'),'NORMAL':add(n,'VEC3'),'COLOR_0':add(colour,'VEC4')},'indices':add(ids,'SCALAR'),'material':material,'mode':4}]})
    node=len(doc['nodes']);doc['nodes'].append({'name':'StaticBackground','mesh':mesh});doc['nodes'][0]['children'].append(node)
    doc['buffers'][0]['byteLength']=len(binary)
    assert bytes(binary[:len(original_binary)])==original_binary
    while len(binary)%4:binary.append(0)
    encoded=json.dumps(doc,separators=(',',':')).encode();encoded+=b' '*((-len(encoded))%4)
    raw=struct.pack('<III',0x46546c67,2,28+len(encoded)+len(binary))+struct.pack('<II',len(encoded),0x4e4f534a)+encoded+struct.pack('<II',len(binary),0x004e4942)+binary
    out.mkdir(parents=True);(out/'character.glb').write_bytes(raw)
    manifest=json.loads((head_dir/'avatar.json').read_text('utf-8'));manifest['id']+='-background';manifest['displayName']+='＋静态背景';manifest['modelSha256']=hashlib.sha256(raw).hexdigest()
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    report={'head_source':str(head_dir),'head_sha256':hashlib.sha256(h.raw).hexdigest(),'background_source':str(background),'background_sha256':hashlib.sha256(bg.raw).hexdigest(),'sha256':manifest['modelSha256'],
        'total_vertices':original_vertices+len(p),'background_vertices':len(p),'background_triangles':len(ids)//3,'background_mesh':str(background_mesh) if background_mesh else None,'static_visibility':visibility,'background_height_m':.40,'background_depth_scale':.28,'background_z_offset_m':-.14,'head_binary_prefix_identical':True,'background_parent':'Root','new_textures':0,'artistAccepted':False,
        'scope':'Static architecture vertex-colour candidate, source normals adjusted for depth compression. Head atlas and morph data retained byte-for-byte. Prop detail/visual framing and combined performance require separate device checks.'}
    (out/'combine-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8');return report

if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('--head',required=True);a.add_argument('--background',required=True);a.add_argument('--out',required=True);a.add_argument('--background-mesh');a.add_argument('--static-fit');v=a.parse_args();print(json.dumps(combine(v.head,v.background,v.out,v.background_mesh,v.static_fit),ensure_ascii=False,indent=2))
