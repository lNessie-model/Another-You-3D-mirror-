"""Require volumetric teeth on independent upper/head and lower/jaw attachment nodes."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from tripo_head_adapter import Glb


def check(directory,source,poses=None):
    g=Glb(Path(directory)/'character.glb');old=Glb(Path(source)/'character.glb')
    nodes={n['name']:i for i,n in enumerate(g.doc['nodes'])};assert 'UpperDentition' in nodes and 'LowerDentition' in nodes,'Teeth lack independent rigid nodes'
    parents={child:i for i,n in enumerate(g.doc['nodes']) for child in n.get('children',[])}
    assert parents[nodes['UpperDentition']]==nodes['Head']
    assert parents[nodes['LowerDentition']]==nodes['JawAttachments']
    assert 'KHR_materials_unlit' not in g.doc['materials'][1].get('extensions',{}),'Oral tissue still uses flat unlit color'
    checks=[]
    for name in ('UpperDentition','LowerDentition'):
        mesh=g.doc['meshes'][g.doc['nodes'][nodes[name]]['mesh']];assert len(mesh['primitives'])==1
        pr=mesh['primitives'][0];assert not pr.get('targets'),'Dentition should use rigid jaw/head transforms'
        p=g.read(pr['attributes']['POSITION']).astype(float);ids=g.read(pr['indices']).reshape(-1,3)
        edges=np.sort(np.concatenate([ids[:,[0,1]],ids[:,[1,2]],ids[:,[2,0]]]),axis=1)
        _,counts=np.unique(edges,axis=0,return_counts=True);assert (counts==2).all(),'Dentition has open tooth cards or nonmanifold edges'
        cross=np.cross(p[ids[:,1]]-p[ids[:,0]],p[ids[:,2]]-p[ids[:,0]])
        assert np.linalg.norm(cross,axis=1).min()>1e-12,'Degenerate dental triangle'
        volume=np.einsum('ij,ij->i',p[ids[:,0]],cross).sum()/6;assert volume>0,'Dental surface faces inward'
        normals=g.read(pr['attributes']['NORMAL']);assert np.max(abs(np.linalg.norm(normals,axis=1)-1))<1e-5
        color=g.read(pr['attributes']['COLOR_0']);enamel=color[:,:3].mean(axis=1)>.3
        assert enamel.sum()>200 and np.ptp(p[enamel,2])>.004,'Enamel remains a flat plane'
        checks.append({'node':name,'vertices':len(p),'triangles':len(ids),'volumeMeters3':float(volume)})
    for a,b in zip(old.doc['meshes'][0]['primitives'][:2],g.doc['meshes'][0]['primitives'][:2]):
        for name,index in a['attributes'].items():assert np.array_equal(old.read(index),g.read(b['attributes'][name]))
        assert np.array_equal(old.read(a['indices']),g.read(b['indices']))
        for ta,tb in zip(a['targets'],b['targets']):
            for name,index in ta.items():assert np.array_equal(old.read(index),g.read(tb[name])),'Unrelated facial channel changed'
    rigid_error=None
    if poses:
        data=json.loads(Path(poses).read_text('utf-8'));assert data['modelSha256']==hashlib.sha256(g.raw).hexdigest()
        worlds={pose['name']:{n['name']:np.array(n['worldMatrixColumnMajor']).reshape(4,4).T for n in pose['nodes']} for pose in data['poses']}
        assert np.allclose(worlds['jaw-open']['LowerDentition'],worlds['jaw-open-mouth-close']['LowerDentition'],atol=1e-7), 'Lip closure cancelled jaw bone'
        rigid_error=0
        for name in ('UpperDentition','LowerDentition'):
            pr=g.doc['meshes'][g.doc['nodes'][nodes[name]]['mesh']]['primitives'][0]
            p=g.read(pr['attributes']['POSITION']).astype(float)[[0,17,43,107,179,251]];reference=np.linalg.norm(p[:,None]-p[None,:],axis=2)
            for state in worlds.values():
                m=state[name];world=p@m[:3,:3].T+m[:3,3]
                error=float(np.max(abs(np.linalg.norm(world[:,None]-world[None,:],axis=2)-reference)))
                rigid_error=max(rigid_error,error);assert error<1e-7,'Rigid teeth deform under a facial pose'
    return {'modelSha256':hashlib.sha256(g.raw).hexdigest(),'dentition':checks,'sourceFaceAndEyeControlsUnchanged':True,
            'actualPoseMaxRigidDistanceError':rigid_error,'scope':'Watertight volumetric arches, rigid hierarchy and sampled distances; no all-view collision or anatomical/artist qualification'}


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--asset',required=True);p.add_argument('--source',required=True);p.add_argument('--poses');p.add_argument('--report')
    a=p.parse_args();result=check(a.asset,a.source,a.poses)
    if a.report:
        path=Path(a.report);assert not path.exists();path.write_text(json.dumps(result,indent=2),'utf-8')
    print(json.dumps(result))
