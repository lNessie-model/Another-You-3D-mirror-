"""Use actual production poses and front-facing rays to reject enamel visible through closed lips."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from tripo_head_adapter import Glb


def check(directory):
    root=Path(directory);g=Glb(root/'character.glb');data=json.loads((root/'production-poses.json').read_text('utf-8'))
    assert data['modelSha256']==hashlib.sha256(g.raw).hexdigest()
    report=[]
    for pose in data['poses']:
        if pose['name'] not in ('neutral','jaw-open-mouth-close'):continue
        components=[]
        for node in pose['nodes']:
            if node['mesh']<0:continue
            m=np.array(node['worldMatrixColumnMajor']).reshape(4,4).T;mesh=g.doc['meshes'][node['mesh']]
            for pr in mesh['primitives']:
                p=g.read(pr['attributes']['POSITION']).astype(float)
                for weight,target in zip(pose['meshes'][node['mesh']]['weights'],pr.get('targets',[])):
                    if weight:p+=weight*g.read(target['POSITION'])
                world=p@m[:3,:3].T+m[:3,3];ids=g.read(pr['indices']).reshape(-1,3)
                enamel=np.zeros(len(p),bool)
                if node['name'] in ('UpperDentition','LowerDentition'):
                    enamel=g.read(pr['attributes']['COLOR_0'])[:,:3].mean(axis=1)>.3
                components.append((world,ids,enamel))
        for label,dx in [('front',0),('right',.5),('left',-.5)]:
            direction=np.array([dx,0,1.]);direction/=np.linalg.norm(direction)
            basis=np.array([[direction[2],0,-direction[0]],[0,1,0],direction])
            triangles=[];classes=[];probes=[]
            bary=np.array([[1/3,1/3,1/3],[.6,.2,.2],[.2,.6,.2],[.2,.2,.6]])
            for p,ids,enamel in components:
                v=(p@basis.T)[ids]
                cross=np.cross(v[:,1]-v[:,0],v[:,2]-v[:,0]);front=cross[:,2]>1e-12
                tooth=enamel[ids].all(axis=1)
                triangles.extend(v[front]);classes.extend(tooth[front].astype(int))
                probes.extend(np.einsum('bc,tcd->tbd',bary,v[front&tooth]).reshape(-1,3))
            v=np.asarray(triangles);classes=np.array(classes);probes=np.asarray(probes);assert len(probes)>1000
            low=probes[:,:2].min(axis=0);high=probes[:,:2].max(axis=0)
            relevant=(v[:,:,:2].max(axis=1)>=low).all(axis=1)&(v[:,:,:2].min(axis=1)<=high).all(axis=1)
            v=v[relevant];classes=classes[relevant]
            a=v[:,0,:2];b=v[:,1,:2]-a;c=v[:,2,:2]-a;det=b[:,0]*c[:,1]-b[:,1]*c[:,0]
            failures=[]
            for begin in range(0,len(probes),128):
                sample=probes[begin:begin+128];q=sample[:,None,:2]-a
                s=(q[:,:,0]*c[:,1]-q[:,:,1]*c[:,0])/det;t=(b[:,0]*q[:,:,1]-b[:,1]*q[:,:,0])/det
                inside=(s>=-1e-7)&(t>=-1e-7)&(s+t<=1.0000001)
                z=v[:,0,2]+s*(v[:,1,2]-v[:,0,2])+t*(v[:,2,2]-v[:,0,2]);z[~inside]=-np.inf
                frontmost=np.argmax(z,axis=1);bad=classes[frontmost]==1
                failures.extend((sample[bad]@basis).tolist())
            report.append({'pose':pose['name'],'view':label,'probes':len(probes),'exposedEnamelProbes':len(failures),
                    'firstExposedWorldPoints':failures[:6]})
    return {'modelSha256':data['modelSha256'],'passed':all(r['exposedEnamelProbes']==0 for r in report),
            'results':report,'scope':'Dental front-face centroid and 3 barycentric rays in two closed poses/three directions; no all-angle collision or optical acceptance'}


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('directory');p.add_argument('--report');a=p.parse_args();result=check(a.directory)
    if a.report:
        path=Path(a.report);assert not path.exists();path.write_text(json.dumps(result,indent=2),'utf-8')
    print(json.dumps(result));raise SystemExit(0 if result['passed'] else 1)
