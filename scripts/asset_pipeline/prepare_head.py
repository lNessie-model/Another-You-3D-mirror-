"""Own-model transform and source-owned 2K/1K/1K PBR base, no expressions yet."""
from pathlib import Path
import argparse,hashlib,io,json,sys
import numpy as np
from PIL import Image
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from asset_pipeline.profile import normalize_geometry,validate_profile
from tripo_head_adapter import Glb,write_glb
from head_glb_edit import GlbEdit

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def node_matrix(node):
    if 'matrix' in node:return np.asarray(node['matrix'],dtype=float).reshape(4,4,order='F')
    t=np.eye(4);t[:3,3]=node.get('translation',[0,0,0])
    x,y,z,w=node.get('rotation',[0,0,0,1]);r=np.eye(4)
    r[:3,:3]=[[1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w)],
               [2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w)],
               [2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)]]
    return t@r@np.diag([*node.get('scale',[1,1,1]),1])

def owned_map(g,material,slot,size,normal=False):
    texture=material['pbrMetallicRoughness']['baseColorTexture'] if slot=='baseColor' else material['normalTexture'] if slot=='normal' else material['pbrMetallicRoughness']['metallicRoughnessTexture']
    source=g.doc['textures'][texture['index']]['source'];im=g.doc['images'][source];view=g.doc['bufferViews'][im['bufferView']]
    encoded=g.raw[g.start+view.get('byteOffset',0):g.start+view.get('byteOffset',0)+view['byteLength']]
    pixels=Image.open(io.BytesIO(encoded)).convert('RGB');original=np.asarray(pixels)
    if pixels.size!=(size,size):pixels=pixels.resize((size,size),Image.Resampling.BOX)
    if normal:
        v=np.asarray(pixels,dtype=float)/127.5-1;lengths=np.linalg.norm(v,axis=2,keepdims=True)
        v/=np.maximum(lengths,1e-6);pixels=Image.fromarray(np.rint(np.clip((v+1)*127.5,0,255)).astype('uint8'))
    stream=io.BytesIO();pixels.save(stream,format='PNG',compress_level=9)
    return stream.getvalue(),{'slot':slot,'sourceImage':source,'originalEncodedSha256':hashlib.sha256(encoded).hexdigest(),
        'originalSize':list(original.shape[:2][::-1]),'outputSize':list(pixels.size),'sameDecodedRgb':np.array_equal(original,np.asarray(pixels)),
        'resampling':'BOX then unit-vector normalization' if normal else 'BOX when resized; RGB PNG encoding'}

def prepare(geometry,original,profile,out):
    geometry,original,profile,out=map(Path,(geometry,original,profile,out));spec=validate_profile(json.loads(profile.read_text('utf-8')))
    if sha(original)!=spec['sourceSha256'] or sha(geometry)!=spec.get('geometrySha256',spec['sourceSha256']):raise ValueError('Own source/geometry hash differs')
    if out.exists():raise ValueError('Preserve candidate directory')
    g=Glb(geometry);old=Glb(original)
    if any(g.doc.get(k) for k in ('skins','animations','extensionsRequired')):raise ValueError('Static original required')
    if len(g.doc['meshes'])!=1 or len(g.doc['meshes'][0]['primitives'])!=1:raise ValueError('Single primitive stage required')
    instances=[n for n in g.doc['nodes'] if n.get('mesh')==0]
    if len(instances)!=1 or len(g.doc['nodes'])!=1:raise ValueError('Transformed hierarchy needs explicit baking first')
    pr=g.doc['meshes'][0]['primitives'][0];p=g.read(pr['attributes']['POSITION']).astype(float);n=g.read(pr['attributes']['NORMAL']).astype(float)
    world=node_matrix(instances[0]);p=np.c_[p,np.ones(len(p))]@world.T;p=p[:,:3];n=n@np.linalg.inv(world[:3,:3])
    p,n,pivot,scale=normalize_geometry(p,n,spec);uv=g.read(pr['attributes']['TEXCOORD_0']);tri=g.read(pr['indices']).reshape(-1,3)
    if len(p)>12000 or len(tri)>12000:raise ValueError('Base geometry has insufficient expression/eye/oral reserve')
    originalMat=old.doc['materials'][old.doc['meshes'][0]['primitives'][0].get('material',0)]
    maps=[];reports=[]
    for slot,size in [('baseColor',2048),('normal',1024),('ORM',1024)]:
        data,report=owned_map(old,originalMat,slot,size,normal=slot=='normal');maps.append(data);reports.append(report)
    # LOD preserves material UVs; all maps come from the original model, never another character.
    out.mkdir(parents=True);base=out/'head-base-albedo.glb'
    factor=originalMat['pbrMetallicRoughness'].get('baseColorFactor',[1,1,1,1])
    write_glb(base,p.astype('<f4'),n.astype('<f4'),np.ones((len(p),4),dtype='<f4'),tri,head_pivot=pivot,uv=uv,atlas_png=maps[0],base_colour_factor=factor)
    edit=GlbEdit(base)
    for name,data in zip(('OwnNormal1K','OwnORM1K'),maps[1:]):
        while len(edit.binary)%4:edit.binary.append(0)
        view=len(edit.doc['bufferViews']);edit.doc['bufferViews'].append({'buffer':0,'byteOffset':len(edit.binary),'byteLength':len(data)})
        edit.binary.extend(data);edit.doc['images'].append({'name':name,'bufferView':view,'mimeType':'image/png'})
    edit.doc['textures']=[{'source':i} for i in range(3)];edit.doc['extras']['mirrorPbrAtlas']=1
    mat=edit.doc['materials'][0];mat['name']='OwnSkinHairPBR';mat['pbrMetallicRoughness']['metallicFactor']=0
    mat['pbrMetallicRoughness']['roughnessFactor']=1;mat['pbrMetallicRoughness']['metallicRoughnessTexture']={'index':2}
    mat['normalTexture']={'index':1,'scale':.65};mat['occlusionTexture']={'index':2,'strength':.5}
    target=out/'head-base.glb';edit.write(target)
    (out/'profile.json').write_text(json.dumps(spec,ensure_ascii=False,indent=2),'utf-8')
    report={'source':str(original),'sourceSha256':sha(original),'geometrySource':str(geometry),'geometrySha256':sha(geometry),'modelSha256':sha(target),
        'vertices':len(p),'triangles':len(tri),'frontEvidence':spec['frontEvidence'],'heightMeters':spec['heightMeters'],'headPivot':pivot.tolist(),
        'sourceToMeterScale':scale,'mapReports':reports,'landmarkApproval':spec['landmarkApproval'],'artistAccepted':False,
        'scope':'Own-model standard base/PBR only; no facial binding, eye/mouth closure or runtime artist acceptance'}
    (out/'prepare-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(report,ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--geometry',required=True);p.add_argument('--original',required=True);p.add_argument('--profile',required=True);p.add_argument('--out',required=True)
    a=p.parse_args();prepare(a.geometry,a.original,a.profile,a.out)
