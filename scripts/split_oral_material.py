"""Finalize the temporary mouth marker into a separate opaque, unlit oral primitive."""
from pathlib import Path
import argparse,copy,hashlib,json,struct
import numpy as np
from tripo_head_adapter import Glb


def finalize(source,out):
    source=Path(source);out=Path(out);assert not out.exists();g=Glb(source/'character.glb');doc=copy.deepcopy(g.doc)
    face=g.doc['meshes'][0]['primitives'][0];color=g.read(face['attributes']['COLOR_0']);oral=abs(color[:,3]-.875)<1e-6
    ids=g.read(face['indices']).reshape(-1,3);oral_faces=oral[ids].all(axis=1);skin_faces=(~oral[ids]).all(axis=1)
    assert oral.any() and (oral_faces|skin_faces).all(),'Missing marker or mixed oral/skin triangle'
    binary=bytearray();doc['bufferViews']=[];doc['accessors']=[]
    def add(values,kind):
        if kind=='SCALAR':values=np.asarray(values,dtype='<u2' if np.max(values)<65535 else '<u4')
        else:values=np.asarray(values,dtype='<f4')
        while len(binary)%4:binary.append(0)
        view=len(doc['bufferViews']);doc['bufferViews'].append({'buffer':0,'byteOffset':len(binary),'byteLength':values.nbytes});binary.extend(values.tobytes())
        accessor={'bufferView':view,'componentType':5126 if values.dtype.kind=='f' else 5123 if values.dtype.itemsize==2 else 5125,'count':len(values),'type':kind}
        if kind=='VEC3':accessor.update(min=values.min(axis=0).tolist(),max=values.max(axis=0).tolist())
        index=len(doc['accessors']);doc['accessors'].append(accessor);return index
    def primitive(original,faces=None,material=None):
        result={'attributes':{},'mode':4,'material':original.get('material',0) if material is None else material}
        indices=g.read(original['indices']).reshape(-1,3) if faces is None else faces
        used=np.unique(indices);mapping=np.full(g.read(original['attributes']['POSITION']).shape[0],-1);mapping[used]=np.arange(len(used))
        kinds={'POSITION':'VEC3','NORMAL':'VEC3','TEXCOORD_0':'VEC2','COLOR_0':'VEC4'}
        for name,index in original['attributes'].items():
            values=g.read(index)[used]
            if name=='COLOR_0':values[:,3]=1
            result['attributes'][name]=add(values,kinds[name])
        result['indices']=add(mapping[indices].reshape(-1),'SCALAR')
        if original.get('targets'):result['targets']=[{name:add(g.read(index)[used],'VEC3') for name,index in target.items()} for target in original['targets']]
        return result
    material=copy.deepcopy(doc['materials'][0]);material['name']='OralMatte';material.setdefault('extensions',{})['KHR_materials_unlit']={}
    material['pbrMetallicRoughness']['roughnessFactor']=1;material['alphaMode']='OPAQUE';oral_material=len(doc['materials']);doc['materials'].append(material)
    doc['extensionsUsed']=sorted(set(doc.get('extensionsUsed',[]))|{'KHR_materials_unlit'})
    doc['meshes'][0]['primitives']=[primitive(face,ids[skin_faces]),primitive(face,ids[oral_faces],oral_material)]
    for index,mesh in enumerate(g.doc['meshes'][1:],1):doc['meshes'][index]['primitives']=[primitive(p) for p in mesh['primitives']]
    for index,image in enumerate(g.doc['images']):
        view=g.doc['bufferViews'][image['bufferView']];off=g.start+view.get('byteOffset',0)
        while len(binary)%4:binary.append(0)
        doc['images'][index]['bufferView']=len(doc['bufferViews']);doc['bufferViews'].append({'buffer':0,'byteOffset':len(binary),'byteLength':view['byteLength']});binary.extend(g.raw[off:off+view['byteLength']])
    doc['buffers']=[{'byteLength':len(binary)}]
    while len(binary)%4:binary.append(0)
    encoded=json.dumps(doc,separators=(',',':')).encode();encoded+=b' '*((-len(encoded))%4)
    out.mkdir();target=out/'character.glb';target.write_bytes(struct.pack('<III',0x46546c67,2,28+len(encoded)+len(binary))+struct.pack('<II',len(encoded),0x4e4f534a)+encoded+struct.pack('<II',len(binary),0x004e4942)+binary)
    manifest=json.loads((source/'avatar.json').read_text('utf-8'));manifest['id']+='-oral-matte';manifest['displayName']='杰洛特方向 · 唇圈与哑光口腔候选';manifest['modelSha256']=hashlib.sha256(target.read_bytes()).hexdigest()
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    report={'source':str(source),'model_sha256':manifest['modelSha256'],'skin_triangles':int(skin_faces.sum()),'oral_triangles':int(oral_faces.sum()),
            'oral_vertices':int(oral.sum()),'alpha_marker_removed':True,'oral_material':'opaque KHR_materials_unlit, original atlas, vertex colors','artistAccepted':False}
    (out/'oral-material-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(report,ensure_ascii=False))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--out',required=True);a=p.parse_args();finalize(a.source,a.out)
