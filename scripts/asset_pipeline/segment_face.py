"""Partition static hair by this model's albedo and calibrated feature evidence."""
from pathlib import Path
import argparse,hashlib,json,sys
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from asset_pipeline.profile import project_landmarks
from tripo_head_adapter import Glb
from head_glb_edit import GlbEdit

def hair_mask(points,rgb,pts):
    rgb=np.asarray(rgb);p=np.asarray(points)
    neutral=rgb.max(axis=1)<=rgb.min(axis=1)*1.5+.012
    dark=rgb.mean(axis=1)<.23
    candidate=neutral&dark
    # Protect the visible facial core, not occluded image landmarks.
    protected=np.zeros(len(p),bool)
    for side,outer,inner,top,bottom in [('Right',33,133,159,145),('Left',263,362,386,374)]:
        lo,hi=sorted([pts[outer,0],pts[inner,0]]);height=abs(pts[top,1]-pts[bottom,1])
        if side=='Right':lo+=(hi-lo)*.18 # Own-profile visible part of the hair-covered eye.
        protected|=(p[:,0]>lo)&(p[:,0]<hi)&(abs(p[:,1]-(pts[top,1]+pts[bottom,1])*.5)<height*.8)
    cx,cy=(pts[13]+pts[14])*.5;width=abs(pts[61,0]-pts[291,0])
    protected|=(abs(p[:,0]-cx)<width*.6)&(abs(p[:,1]-cy)<width*.35)
    return candidate&~protected

def author(source,landmarks,projection,out):
    source,landmarks,projection,out=map(Path,(source,landmarks,projection,out))
    if out.exists():raise ValueError('Preserve output')
    g=Glb(source/'head-base.glb');pr=g.doc['meshes'][0]['primitives'][0]
    pivot=np.asarray(g.doc['nodes'][1]['translation']);p=g.read(pr['attributes']['POSITION']);world=p+pivot
    pts=project_landmarks(json.loads(landmarks.read_text('utf-8')),json.loads(projection.read_text('utf-8')))
    rgb=g.sample_base_colour(g.read(pr['attributes']['TEXCOORD_0']),pr['material'])[:,:3]
    mask=hair_mask(world,rgb,pts);tri=g.read(pr['indices']).reshape(-1,3)
    is_hair=mask[tri].sum(axis=1)>=2
    if not 100<is_hair.sum()<len(tri)-300:raise ValueError('Own hair segmentation needs manual profile correction')
    edit=GlbEdit(source/'head-base.glb');parts=[]
    for name,ids,material in [('Face',tri[~is_hair],0),('Hair',tri[is_hair],1)]:
        used=np.unique(ids);remap=np.full(len(p),-1);remap[used]=np.arange(len(used))
        attrs={k:edit.add(g.read(index)[used],g.doc['accessors'][index]['type']) for k,index in pr['attributes'].items()}
        mesh={'name':name,'primitives':[{'attributes':attrs,'indices':edit.add(remap[ids].reshape(-1),'SCALAR'),'material':material,'mode':4}]}
        parts.append(mesh)
    edit.doc['meshes']=parts
    material=json.loads(json.dumps(edit.doc['materials'][0]));material['name']='OwnStaticBlackHairPBR'
    material['pbrMetallicRoughness']['metallicFactor']=0
    # Preserve this head's paired normal/ORM maps. The production profile requires
    # the pair, and the raw hair albedo is already near-black. Lighting, rather
    # than a fabricated dark atlas, must be evaluated separately.
    material['pbrMetallicRoughness']['roughnessFactor']=1
    edit.doc['materials'].append(material);edit.doc['nodes'][2]['mesh']=0
    edit.doc['nodes'].append({'name':'StaticHair','mesh':1});edit.doc['nodes'][1]['children'].append(len(edit.doc['nodes'])-1)
    out.mkdir(parents=True);target=out/'head-base.glb';edit.write(target)
    np.savez_compressed(out/'segmentation.npz',hair_vertices_mask=mask,hair_triangles_mask=is_hair,source_positions=p,source_triangles=tri)
    spec=json.loads((source/'profile.json').read_text('utf-8'));spec['landmarkVisibility']={'33':'occluded-by-hair','105':'occluded-by-hair','234':'occluded-by-hair','334':'approximate-needs-brown-brow-relocation','13':'visible-lip-seam-initial','14':'visible-lip-seam-initial'}
    spec['landmarkApproval']='pending';(out/'profile.json').write_text(json.dumps(spec,ensure_ascii=False,indent=2),'utf-8')
    report={'sourceSha256':hashlib.sha256(g.raw).hexdigest(),'modelSha256':hashlib.sha256(target.read_bytes()).hexdigest(),'faceTriangles':int((~is_hair).sum()),'hairTriangles':int(is_hair.sum()),'hairVertexCount':len(np.unique(tri[is_hair])),
        'partitionComplete':True,'hairStaticNoMorphTargets':True,'ownHairFactorMultiplier':1,'hairRoughnessFactor':1,'hairOrmRoughnessDetached':False,'hairDiffuseBeforeMeanLinear':rgb[mask].mean(axis=0).tolist(),'hairDiffuseAfterMeanLinear':rgb[mask].mean(axis=0).tolist(),
        'landmarkVisibility':spec['landmarkVisibility'],'artistAccepted':False,'scope':'Albedo-guided segmentation candidate; UV/geometric surface retained, skin/hair boundary and real eyebrow mask need preview approval.'}
    (out/'segmentation-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(report,ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--landmarks',required=True);p.add_argument('--projection',required=True);p.add_argument('--out',required=True)
    a=p.parse_args();author(a.source,a.landmarks,a.projection,a.out)
