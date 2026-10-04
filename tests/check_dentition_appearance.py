"""Gate the tooth appearance fix while requiring exact complete face and cavity preservation."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from tripo_head_adapter import Glb
from check_dentition import check as dental_structure
from check_head_oral_detail import deformed,world,exposed


def preserved_array(before,after,label):
    assert before.dtype==after.dtype and before.shape==after.shape and before.tobytes()==after.tobytes(),label+' changed'


def preserved_non_dental(g,old,material):
    # Accessor/buffer offsets may be repacked; their decoded values and actual
    # image bytes must remain exact. All render/rig metadata stays symmetric.
    storage={'meshes','materials','images','accessors','bufferViews','buffers'}
    assert {k:v for k,v in g.doc.items() if k not in storage}=={k:v for k,v in old.doc.items() if k not in storage},'Rig/render metadata changed'
    assert len(g.doc['meshes'])==len(old.doc['meshes']),'Mesh count changed'
    dental_meshes={n['mesh'] for n in old.doc['nodes'] if n['name'] in ('UpperDentition','LowerDentition')}
    assert len(dental_meshes)==2,'Expected two independent dental meshes'
    assert all(pr['material']==material for mi in dental_meshes for pr in old.doc['meshes'][mi]['primitives']),'Dental material slot differs'
    for mi,(a,b) in enumerate(zip(old.doc['meshes'],g.doc['meshes'])):
        assert {k:v for k,v in a.items() if k!='primitives'}=={k:v for k,v in b.items() if k!='primitives'},'Mesh metadata changed'
        assert len(a['primitives'])==len(b['primitives']),'Primitive count changed'
        for pa,pb in zip(a['primitives'],b['primitives']):
            fields={'attributes','indices','targets'}
            assert {k:v for k,v in pa.items() if k not in fields}=={k:v for k,v in pb.items() if k not in fields},'Primitive binding changed'
            assert pa['attributes'].keys()==pb['attributes'].keys(),'Attribute keys changed'
            assert len(pa.get('targets',[]))==len(pb.get('targets',[])),'Target count changed'
            for ta,tb in zip(pa.get('targets',[]),pb.get('targets',[])):
                assert ta.keys()==tb.keys(),'Target keys changed'
            if mi in dental_meshes:continue
            for key,index in pa['attributes'].items():preserved_array(old.read(index),g.read(pb['attributes'][key]),key)
            preserved_array(old.read(pa['indices']),g.read(pb['indices']),'Indices')
            for ta,tb in zip(pa.get('targets',[]),pb.get('targets',[])):
                for key,index in ta.items():preserved_array(old.read(index),g.read(tb[key]),'Facial/oral target')
    assert len(g.doc['materials'])==len(old.doc['materials']),'Material count changed'
    assert 0<=material<len(old.doc['materials']),'Invalid dental material slot'
    assert all(a==b for i,(a,b) in enumerate(zip(g.doc['materials'],old.doc['materials'])) if i!=material),'Other material changed'
    for key in ('textures','samplers'):
        assert len(g.doc.get(key,[]))==len(old.doc.get(key,[])) and g.doc.get(key)==old.doc.get(key),key+' changed'
    assert len(g.doc['images'])==len(old.doc['images']),'Image count changed'
    for a,b in zip(old.doc['images'],g.doc['images']):
        assert {k:v for k,v in a.items() if k!='bufferView'}=={k:v for k,v in b.items() if k!='bufferView'},'Image metadata changed'
        av=old.doc['bufferViews'][a['bufferView']];bv=g.doc['bufferViews'][b['bufferView']]
        assert old.raw[old.start+av.get('byteOffset',0):old.start+av.get('byteOffset',0)+av['byteLength']]==g.raw[g.start+bv.get('byteOffset',0):g.start+bv.get('byteOffset',0)+bv['byteLength']],'Image bytes changed'


def check(directory,source,poses):
    directory=Path(directory);source=Path(source);g=Glb(directory/'character.glb');old=Glb(source/'character.glb')
    report=json.loads((directory/'dentition-appearance-authoring.json').read_text('utf-8'))
    assert report['sourceModelSha256']==hashlib.sha256(old.raw).hexdigest()
    model_sha=hashlib.sha256(g.raw).hexdigest()
    assert report['modelSha256']==model_sha,'Authoring report model fingerprint differs'
    data=json.loads(Path(poses).read_text('utf-8'))
    assert data['modelSha256']==model_sha and len(data['poses'])==19,'Expected 19 actual poses for this model'
    pose_names=[pose['name'] for pose in data['poses']]
    assert len(set(pose_names))==19,'Duplicate pose names'
    assert {'neutral','jaw-open-mouth-close'}<=set(pose_names),'Required closed-lip poses missing'
    material=report['dentitionMaterialIndex']
    preserved_non_dental(g,old,material)
    pbr=g.doc['materials'][material]['pbrMetallicRoughness']
    assert 'baseColorTexture' not in pbr and pbr['metallicFactor']==0 and .25<=pbr['roughnessFactor']<=.40
    current=json.loads((directory/'avatar.json').read_text('utf-8'));previous=json.loads((source/'avatar.json').read_text('utf-8'))
    assert current['modelSha256']==model_sha and previous['modelSha256']==report['sourceModelSha256'],'Manifest model fingerprint differs'
    except_keys={'id','displayName','modelSha256'}
    assert {k:v for k,v in current.items() if k not in except_keys}=={k:v for k,v in previous.items() if k not in except_keys}
    dental=dental_structure(directory,source,poses);front=[];bite=[]
    for description in report['arches']:
        node=next(n for n in g.doc['nodes'] if n['name']==description['node']);pr=g.doc['meshes'][node['mesh']]['primitives'][0]
        p=g.read(pr['attributes']['POSITION']).astype(float);n=g.read(pr['attributes']['NORMAL']);colour=g.read(pr['attributes']['COLOR_0'])
        enamel_count=sum(x['count'] for x in description['crowns'])
        assert colour[:enamel_count,:3].mean(axis=0).min()>.60,'Enamel remains dark'
        assert np.max(abs(colour[enamel_count:,:3]-[.068,.020,.029]))<1e-7,'Gingiva was accidentally brightened'
        levels=[]
        for crown in description['crowns']:
            points=p[crown['start']:crown['start']+crown['count']]
            normals=n[crown['start']:crown['start']+crown['count']]
            # Central facial wall of the crown, excluding biting/root caps.
            local=points-points.mean(axis=0)
            # Each tooth follows the curved arch. Global maximum Z can be a side
            # face: infer its actual transverse axis from its own XZ covariance.
            _,vectors=np.linalg.eigh(np.cov(local[:,[0,2]].T));tangent=vectors[:,-1]
            if tangent[0]<0:tangent=-tangent
            forward=np.array([-tangent[1],0,tangent[0]])
            depth=local@forward;visible=(abs(local[:,1])<crown['height']*.32)&(depth>.92*depth.max())
            dot=normals[visible]@forward
            assert visible.any() and dot.min()>.80 and normals[visible,2].min()>.40,'Central forward crown normals face away'
            front.append({'arch':description['node'],'kind':crown['kind'],'minimumCentralFrontNormalZ':float(normals[visible,2].min()),'minimumCentralNormalDotInferredForward':float(dot.min())})
            levels.append(float(points[:,1].max() if description['node']=='LowerDentition' else points[:,1].min()))
        span=float(np.ptp(levels));assert span<.00050,'Bite line remains sawtoothed'
        bite.append({'arch':description['node'],'biteHeightSpanMeters':span,'enamelMeanLinearRGB':colour[:enamel_count,:3].mean(axis=0).tolist()})
    vertices=triangles=draws=0
    for node in g.doc['nodes']:
        if 'mesh' not in node:continue
        for pr in g.doc['meshes'][node['mesh']]['primitives']:
            vertices+=len(g.read(pr['attributes']['POSITION']));triangles+=len(g.read(pr['indices']))//3;draws+=1
    assert vertices<=20000 and triangles<=30000 and draws<=8 and len(g.raw)<=32*1024*1024
    occlusion=[]
    for pose in data['poses']:
        if pose['name'] not in ('neutral','jaw-open-mouth-close'):continue
        weight=pose['meshes'][0]['weights'];worlds={x['name']:np.asarray(x['worldMatrixColumnMajor']).reshape(4,4).T for x in pose['nodes']};surface=[]
        for pr in g.doc['meshes'][0]['primitives'][:2]:
            points=world(deformed(g,pr,weight),worlds['Face']);surface.append(points[g.read(pr['indices']).reshape(-1,3)])
        surface=np.concatenate(surface)
        for name in ('UpperDentition','LowerDentition'):
            node=next(x for x in g.doc['nodes'] if x['name']==name);pr=g.doc['meshes'][node['mesh']]['primitives'][0]
            points=world(g.read(pr['attributes']['POSITION']),worlds[name]);indices=g.read(pr['indices']).reshape(-1,3)
            enamel=g.read(pr['attributes']['COLOR_0'])[:,:3].mean(axis=1)>.3
            samples=np.r_[points[enamel],points[indices[enamel[indices].all(axis=1)]].mean(axis=1)]
            assert len(samples)>0,'Closed-lip dental sample set is empty'
            for direction in ([0,0,1],[.45,0,1],[-.45,0,1]):
                visibility=exposed(samples,surface,direction)
                assert visibility.shape==(len(samples),),'Closed-lip ray result is incomplete'
                count=int(visibility.sum());row={'pose':pose['name'],'arch':name,'direction':direction,'probes':len(samples),'exposed':count}
                assert count==0,'New dental surface protrudes through closed lips: '+str(row);occlusion.append(row)
    assert len(occlusion)==12 and all(row['probes']>0 for row in occlusion),'Required closed-lip sampling is incomplete'
    return {'passed':True,'modelSha256':hashlib.sha256(g.raw).hexdigest(),'sourceModelSha256':hashlib.sha256(old.raw).hexdigest(),
            'completeFaceEyesOralTissueControlsNodesMapsPreserved':True,'oralMaterialUnchanged':True,'gingivaEffectiveColorPreserved':True,
            'instanceVertices':vertices,'triangles':triangles,'draws':draws,'glbBytes':len(g.raw),'actual19PoseDentalStructure':dental,
            'biteAndEnamel':bite,'centralCrownFrontNormals':front,'closedLipDentalOcclusion':occlusion,
            'scope':'19 real production poses, dense sampled three-direction closed-lip occlusion, watertight rigid teeth and exact non-dental preservation; preview naturalness and final device shader appearance remain for review.'}


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--asset',required=True);p.add_argument('--source',required=True);p.add_argument('--poses',required=True);p.add_argument('--report',required=True)
    a=p.parse_args();result=check(a.asset,a.source,a.poses);out=Path(a.report);assert not out.exists();out.write_text(json.dumps(result,indent=2),'utf-8');print(json.dumps(result))
