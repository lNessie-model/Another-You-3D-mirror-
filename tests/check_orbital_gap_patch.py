"""Independent preservation, attachment, and actual-ray gate for the local patch."""
import argparse,hashlib,json,sys
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from tripo_head_adapter import Glb
from check_head_ocular import visible_eye

def posed(g,primitive,weights):
    p=g.read(primitive['attributes']['POSITION']).astype(float)
    for weight,target in zip(weights,primitive.get('targets',[])):
        if weight:p+=weight*g.read(target['POSITION'])
    return p

def check(source,directory,extra_poses,closure_report):
    source=Path(source);root=Path(directory);old=Glb(source/'character.glb');new=Glb(root/'character.glb')
    sha=hashlib.sha256(new.raw).hexdigest();report=json.loads((root/'orbital-gap-patch.json').read_text('utf-8'))
    assert report['sourceModelSha256']==hashlib.sha256(old.raw).hexdigest() and report['modelSha256']==sha
    assert old.doc['nodes']==new.doc['nodes'] and old.doc['materials']==new.doc['materials']
    for key in ('scenes','scene','images','textures','samplers','extensionsUsed','extensionsRequired'):
        assert old.doc.get(key)==new.doc.get(key),key
    comparisons=0;prefix=None
    for mi,(before,after) in enumerate(zip(old.doc['meshes'],new.doc['meshes'])):
        assert {k:v for k,v in before.items() if k!='primitives'}=={k:v for k,v in after.items() if k!='primitives'}
        assert len(before['primitives'])==len(after['primitives'])
        for pi,(a,b) in enumerate(zip(before['primitives'],after['primitives'])):
            patch=mi==0 and pi==1;count=len(old.read(a['attributes']['POSITION']))
            assert {k:v for k,v in a.items() if k not in ('attributes','targets','indices')}=={k:v for k,v in b.items() if k not in ('attributes','targets','indices')}
            assert a['attributes'].keys()==b['attributes'].keys() and len(a.get('targets',[]))==len(b.get('targets',[]))
            for name,index in a['attributes'].items():
                expected=old.read(index);actual=new.read(b['attributes'][name]);assert np.array_equal(expected,actual[:count] if patch else actual),(mi,pi,name);comparisons+=1
            expected=old.read(a['indices']);actual=new.read(b['indices']);assert np.array_equal(expected,actual[:len(expected)] if patch else actual);comparisons+=1
            for ta,tb in zip(a.get('targets',[]),b.get('targets',[])):
                assert ta.keys()==tb.keys()
                for key,index in ta.items():
                    expected=old.read(index);actual=new.read(tb[key]);assert np.array_equal(expected,actual[:count] if patch else actual),(mi,pi,key);comparisons+=1
            if patch:prefix=count
    assert len(old.doc['meshes'])==len(new.doc['meshes'])
    for image_old,image_new in zip(old.doc['images'],new.doc['images']):
        a=old.doc['bufferViews'][image_old['bufferView']];b=new.doc['bufferViews'][image_new['bufferView']]
        aa=old.start+a.get('byteOffset',0);bb=new.start+b.get('byteOffset',0)
        assert old.raw[aa:aa+a['byteLength']]==new.raw[bb:bb+b['byteLength']]
    manifest_old=json.loads((source/'avatar.json').read_text('utf-8'));manifest_new=json.loads((root/'avatar.json').read_text('utf-8'))
    assert manifest_new['modelSha256']==sha
    ignored={'id','displayName','modelSha256'}
    assert {k:v for k,v in manifest_old.items() if k not in ignored}=={k:v for k,v in manifest_new.items() if k not in ignored}
    skin=new.doc['meshes'][0]['primitives'][0];ring=new.doc['meshes'][0]['primitives'][1]
    clones=report['skinVertexClones'];assert len(clones)==2 and [c['sourceVertex'] for c in clones]==[11829,11904]
    assert [c['targetVertex'] for c in clones]==[prefix,prefix+1]
    for name in ('POSITION','NORMAL'):
        actual=new.read(ring['attributes'][name])[prefix:];expected=new.read(skin['attributes'][name])[[11829,11904]];assert np.array_equal(expected,actual)
    for target_skin,target_ring in zip(skin['targets'],ring['targets']):
        assert np.array_equal(new.read(target_skin['POSITION'])[[11829,11904]],new.read(target_ring['POSITION'])[prefix:])
    uv=new.read(ring['attributes']['TEXCOORD_0'])[prefix:];white_uv=new.read(ring['attributes']['TEXCOORD_0'])[0];assert np.array_equal(uv,np.tile(white_uv,(2,1)))
    white=new.sample_base_colour(white_uv[None],ring['material'])[0,:3]
    rgb=new.sample_base_colour(new.read(skin['attributes']['TEXCOORD_0'])[[11829,11904]],skin['material'])[:,:3]*new.read(skin['attributes']['COLOR_0'])[[11829,11904],:3]
    assert np.max(abs(np.clip(rgb/white,0,1)-new.read(ring['attributes']['COLOR_0'])[prefix:,:3]))<1e-7
    added=new.read(ring['indices']).reshape(-1,3)[len(old.read(old.doc['meshes'][0]['primitives'][1]['indices']))//3:]
    assert np.array_equal(added,np.array([[prefix,2304,2303],[prefix,2303,prefix+1]]))
    pose_sets=[]
    for path in (root/'production-poses.json',root/'feedback-poses.json',Path(extra_poses)):
        data=json.loads(path.read_text('utf-8'));assert data['modelSha256']==sha;pose_sets.append(data)
    attachment=[];min_area=float('inf')
    for data in pose_sets:
        for pose in data['poses']:
            weights=pose['meshes'][0]['weights'];s=posed(new,skin,weights)[[11829,11904]];r=posed(new,ring,weights)
            error=float(np.max(np.linalg.norm(s-r[prefix:],axis=1)));assert error==0
            tri=r[added];areas=np.linalg.norm(np.cross(tri[:,1]-tri[:,0],tri[:,2]-tri[:,0]),axis=1)*.5
            assert np.isfinite(areas).all() and areas.min()>1e-12;min_area=min(min_area,float(areas.min()))
            attachment.append({'pose':pose['name'],'setScope':data.get('scope'),'maxClonedPointErrorMeters':error})
    closure=json.loads(Path(closure_report).read_text('utf-8'));assert closure['modelSha256']==sha and closure['passed']
    assert len(closure['measurements'])==18 and all(e['eyeProbes']==0 for row in closure['measurements'] for e in row['eyes'])
    covered=[]
    directions=[('front',[0,0,1]),('right',[.45,0,1]),('left',[-.45,0,1])]
    for pose in pose_sets[-1]['poses']:
        weights=pose['meshes'][0]['weights'];p=posed(new,ring,weights)[added]
        matrix=np.array(next(n for n in pose['nodes'] if n['name']=='Face')['worldMatrixColumnMajor']).reshape(4,4).T
        world=p@matrix[:3,:3].T+matrix[:3,3]
        for view,direction in directions:
            d=np.array(direction,dtype=float);d/=np.linalg.norm(d);basis=np.array([[d[2],0,-d[0]],[0,1,0],d]);tri=world@basis.T
            a=tri[:,0,:2];ab=tri[:,1,:2]-a;ac=tri[:,2,:2]-a;det=ab[:,0]*ac[:,1]-ab[:,1]*ac[:,0]
            for eye in visible_eye(old,pose,direction):
                for sample in eye['firstExposedEyeSamples']:
                    q=np.array(sample['planeXY'])-a;s=(q[:,0]*ac[:,1]-q[:,1]*ac[:,0])/det;t=(ab[:,0]*q[:,1]-ab[:,1]*q[:,0])/det
                    inside=(det>1e-12)&(s>=-1e-7)&(t>=-1e-7)&(s+t<=1.0000001)
                    z=tri[:,0,2]+s*(tri[:,1,2]-tri[:,0,2])+t*(tri[:,2,2]-tri[:,0,2])
                    ahead=inside&(z>sample['worldDepth']+1e-6);assert ahead.any(),(pose['name'],view,sample)
                    covered.append({'pose':pose['name'],'view':view,'sourceEyeSample':sample,'frontPatchTriangle':int(np.flatnonzero(ahead)[0]),'occluderAheadMeters':float(z[ahead].max()-sample['worldDepth'])})
    assert len(covered)>=10
    return {'sourceModelSha256':hashlib.sha256(old.raw).hexdigest(),'modelSha256':sha,'passed':True,'originalFieldComparisons':comparisons,
            'embeddedMapsNodesBindingsAllOriginalMeshesExact':True,'clonedVertexPoseChecks':len(attachment),'maxClonedPointErrorMeters':0,
            'minimumNewTriangleAreaSquareMeters':min_area,'sourceLeakSamplesCoveredByFrontFacingPatch':covered,'attachment':attachment,
            'scope':'Exact original geometry/material/map/node/binding preservation, 44 actual-pose clone checks and six-combination sampled closure. No all-angle, live or artistic acceptance.'}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--model',required=True);p.add_argument('--extra-poses',required=True);p.add_argument('--closure-report',required=True);p.add_argument('--out',required=True);a=p.parse_args()
    result=check(a.source,a.model,a.extra_poses,a.closure_report);out=Path(a.out);assert not out.exists();out.write_text(json.dumps(result,indent=2),'utf-8');print(json.dumps(result))
