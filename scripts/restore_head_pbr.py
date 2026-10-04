"""Reuse source PBR maps without regenerating the face or modifying existing expression geometry."""
from pathlib import Path
import argparse,copy,hashlib,io,json,struct
import numpy as np
from PIL import Image
from tripo_head_adapter import Glb


def sha(data):return hashlib.sha256(data).hexdigest()


def restore(source,original,out):
    source,original,out=map(Path,(source,original,out));assert not out.exists()
    g=Glb(source/'character.glb');old=Glb(original);doc=copy.deepcopy(g.doc)
    assert doc.get('extras')=={'mirrorAlbedoAtlas':1}
    face=g.doc['meshes'][0]['primitives'][0]
    uv=g.read(face['attributes']['TEXCOORD_0']);colors=g.read(face['attributes']['COLOR_0']);ids=g.read(face['indices']).reshape(-1,3)
    values,counts=np.unique(uv,axis=0,return_counts=True);white=values[counts.argmax()]
    ring=np.all(uv==white,axis=1);ringFaces=ring[ids].all(axis=1)
    assert not (ring[ids].any(axis=1)&~ringFaces).any(),'UV boundaries must coincide with primitive boundaries'
    assert np.array_equal(colors[~ring],np.ones_like(colors[~ring]))
    # Prove the original maps belong to this UV layout, not a different Geralt generation.
    base=Glb(source.parent/'geralt-atlas/head-base.glb')
    originalUv=old.read(old.doc['meshes'][0]['primitives'][0]['attributes']['TEXCOORD_0'])
    assert np.array_equal(base.read(base.doc['meshes'][0]['primitives'][0]['attributes']['TEXCOORD_0']),originalUv)
    # Every surviving original triangle must map into an original UV triangle. Hole clipping
    # adds barycentric vertices, so the topology and ring UVs are deliberately not replaced.
    binary=bytearray();doc['bufferViews']=[];doc['accessors']=[]
    def blob(data):
        while len(binary)%4:binary.append(0)
        view=len(doc['bufferViews']);doc['bufferViews'].append({'buffer':0,'byteOffset':len(binary),'byteLength':len(data)});binary.extend(data);return view
    def add(values,kind):
        values=np.asarray(values,dtype='<u2' if kind=='SCALAR' else '<f4')
        view=blob(values.tobytes());a={'bufferView':view,'componentType':5123 if kind=='SCALAR' else 5126,'count':len(values),'type':kind}
        if kind=='VEC3':a.update(min=values.min(axis=0).tolist(),max=values.max(axis=0).tolist())
        index=len(doc['accessors']);doc['accessors'].append(a);return index
    mappings=[]
    def primitive(p,triangles,material):
        used=np.unique(triangles);lookup=np.full(len(g.read(p['attributes']['POSITION'])),-1);lookup[used]=np.arange(len(used))
        result={'mode':4,'material':material,'attributes':{}}
        for key,index in p['attributes'].items():
            kind=g.doc['accessors'][index]['type'];result['attributes'][key]=add(g.read(index)[used],kind)
        result['indices']=add(lookup[triangles].reshape(-1),'SCALAR')
        if p.get('targets'):result['targets']=[{key:add(g.read(index)[used],'VEC3') for key,index in target.items()} for target in p['targets']]
        mappings.append((p,result,used,triangles));return result
    skin=doc['materials'][0];skin['name']='OriginalSkinHairPBR';skin['normalTexture']={'index':1,'scale':.65}
    skin['occlusionTexture']={'index':2,'strength':.5};skin['pbrMetallicRoughness']['roughnessFactor']=1
    skin['pbrMetallicRoughness']['metallicRoughnessTexture']={'index':2}
    plain=copy.deepcopy(g.doc['materials'][0]);plain['name']='RetopologizedSkin';plain['pbrMetallicRoughness']['roughnessFactor']=.66
    eye=copy.deepcopy(plain);eye['name']='OcularSurface';eye['pbrMetallicRoughness']['roughnessFactor']=.18
    doc['materials'].extend([plain,eye]);doc['extras']['mirrorPbrAtlas']=1
    doc['meshes'][0]['primitives']=[primitive(face,ids[~ringFaces],0),primitive(face,ids[ringFaces],2)]
    for p in g.doc['meshes'][0]['primitives'][1:]:doc['meshes'][0]['primitives'].append(primitive(p,g.read(p['indices']).reshape(-1,3),p['material']))
    for m,mesh in enumerate(g.doc['meshes'][1:],1):doc['meshes'][m]['primitives']=[primitive(p,g.read(p['indices']).reshape(-1,3),3) for p in mesh['primitives']]
    def embedded(model,imageIndex):
        image=model.doc['images'][imageIndex];v=model.doc['bufferViews'][image['bufferView']];off=model.start+v.get('byteOffset',0);return model.raw[off:off+v['byteLength']]
    color=embedded(g,0)
    oldMat=old.doc['materials'][0];normal=embedded(old,old.doc['textures'][oldMat['normalTexture']['index']]['source'])
    ormOriginal=embedded(old,old.doc['textures'][oldMat['pbrMetallicRoughness']['metallicRoughnessTexture']['index']]['source'])
    pixels=np.asarray(Image.open(io.BytesIO(ormOriginal)).convert('RGB'));encoded=io.BytesIO();Image.fromarray(pixels).save(encoded,format='PNG',compress_level=9);orm=encoded.getvalue()
    assert np.array_equal(pixels,np.asarray(Image.open(io.BytesIO(orm)))),'Format conversion must preserve every decoded ORM texel'
    doc['images']=[{'name':name,'bufferView':blob(data),'mimeType':'image/png'} for name,data in [('AlbedoUnchanged',color),('OriginalNormalGL',normal),('OriginalORM-LosslessContainer',orm)]]
    doc['textures']=[{'source':i} for i in range(3)];doc['buffers']=[{'byteLength':len(binary)}]
    while len(binary)%4:binary.append(0)
    encoded=json.dumps(doc,separators=(',',':')).encode();encoded+=b' '*((-len(encoded))%4)
    out.mkdir();target=out/'character.glb';target.write_bytes(struct.pack('<III',0x46546c67,2,28+len(encoded)+len(binary))+struct.pack('<II',len(encoded),0x4e4f534a)+encoded+struct.pack('<II',len(binary),0x004e4942)+binary)
    checked=Glb(target)
    for before,after,used,triangles in mappings:
        for key,index in before['attributes'].items():assert np.array_equal(g.read(index)[used],checked.read(after['attributes'][key]))
        for t,new in zip(before.get('targets',[]),after.get('targets',[])):
            for key,index in t.items():assert np.array_equal(g.read(index)[used],checked.read(new[key]))
        newids=checked.read(after['indices']).reshape(-1,3);assert np.array_equal(used[newids],triangles)
    assert checked.doc['nodes']==g.doc['nodes'] and checked.doc['meshes'][0]['extras']==g.doc['meshes'][0]['extras']
    manifest=json.loads((source/'avatar.json').read_text('utf-8'));manifest['id']+='-pbr';manifest['displayName']='杰洛特方向 · 原始 PBR 材质恢复';manifest['modelSha256']=sha(target.read_bytes());manifest['materialProfile']='mirror-pbr-v1';manifest['normalPolicy']='smooth-deformed-v1'
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    report={'source_model_sha256':sha(g.raw),'source_pbr_model':str(original.resolve()),'source_pbr_sha256':sha(old.raw),'model_sha256':manifest['modelSha256'],
      'albedo_bytes_unchanged':True,'normal_bytes_unchanged':True,'orm_decoded_texels_unchanged':True,'geometry_morph_nodes_verified_exact':True,
      'original_textured_triangles':int((~ringFaces).sum()),'retopologized_triangles':int(ringFaces.sum()),'map_resolution':2048,'gpu_map_level_zero_bytes':3*2048*2048*4,
      'limits':['Retopologized lips/eyelids still use sampled vertex colour; no original high-frequency texture restored there','Procedural eyes have separate roughness but no anatomical iris normal map','Derivative tangent frame approximates rather than reproduces authored MikkTSpace'],
      'artistAccepted':False,'tripo_credits_spent':0}
    (out/'pbr-material-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(report,ensure_ascii=False))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--original',required=True);p.add_argument('--out',required=True);a=p.parse_args();restore(a.source,a.original,a.out)
