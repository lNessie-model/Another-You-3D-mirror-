"""Read-only test of two source-attached nasal orbital triangles; no asset writes."""
import argparse,json,sys,hashlib
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tests'))
from check_head_ocular import Glb,triangles

def patches(g,pose,direction):
    primitives=g.doc['meshes'][0]['primitives'];positions=[]
    for primitive in primitives[:2]:
        p=g.read(primitive['attributes']['POSITION']).astype(float)
        for weight,target in zip(pose['meshes'][0]['weights'],primitive['targets']):
            if weight:p+=weight*g.read(target['POSITION'])
        positions.append(p)
    combos=[[(0,11829),(1,2304),(1,2303)],[(0,11829),(1,2303),(0,11904)]];out=[];orient=[]
    matrix=np.array(next(n for n in pose['nodes'] if n['name']=='Face')['worldMatrixColumnMajor']).reshape(4,4).T
    direction=np.asarray(direction,dtype=float);direction/=np.linalg.norm(direction);basis=np.array([[direction[2],0,-direction[0]],[0,1,0],direction])
    for combo in combos:
        neutral=np.array([g.read(primitives[pi]['attributes']['POSITION'])[i] for pi,i in combo])
        normals=np.array([g.read(primitives[pi]['attributes']['NORMAL'])[i] for pi,i in combo]).mean(0)
        invert=np.dot(np.cross(neutral[1]-neutral[0],neutral[2]-neutral[0]),normals)<0
        p=np.array([positions[pi][i] for pi,i in combo]);p=p[[0,2,1]] if invert else p
        p=(p@matrix[:3,:3].T+matrix[:3,3])@basis.T
        area=np.cross(p[1]-p[0],p[2]-p[0])[2];orient.append({'sources':combo,'reversed':bool(invert),'projectedAreaSquareMm':float(area*.5*1e6)})
        if area>1e-12:out.append(p)
    return np.asarray(out),orient

def check_pose(g,pose,direction):
    v,_,tags,centers=triangles(g,pose,direction);patch,orient=patches(g,pose,direction)
    if len(patch):v=np.r_[v,patch];tags=np.r_[tags,np.zeros(len(patch),dtype=int)]
    eyes=[]
    for tag,center in centers.items():
        probes=np.stack(np.meshgrid(center[0]+np.linspace(-.012,.012,65),center[1]+np.linspace(-.007,.007,33)),axis=-1).reshape(-1,2)
        low=probes.min(0);high=probes.max(0);relevant=(v[:,:,:2].max(1)>=low).all(1)&(v[:,:,:2].min(1)<=high).all(1)
        tri=v[relevant];tagged=tags[relevant];a=tri[:,0,:2];ab=tri[:,1,:2]-a;ac=tri[:,2,:2]-a;det=ab[:,0]*ac[:,1]-ab[:,1]*ac[:,0];count=0
        for begin in range(0,len(probes),96):
            q=probes[begin:begin+96,None,:]-a;s=(q[:,:,0]*ac[:,1]-q[:,:,1]*ac[:,0])/det;t=(ab[:,0]*q[:,:,1]-ab[:,1]*q[:,:,0])/det
            inside=(s>=-1e-7)&(t>=-1e-7)&(s+t<=1.0000001);z=tri[:,0,2]+s*(tri[:,1,2]-tri[:,0,2])+t*(tri[:,2,2]-tri[:,0,2]);z[~inside]=-np.inf
            hit=z.argmax(1);ri=np.arange(len(hit));count+=int(((tagged[hit]==tag)&np.isfinite(z[ri,hit])).sum())
        eyes.append({'side':'Left' if tag==1 else 'Right','eyeProbes':count})
    return eyes,orient

p=argparse.ArgumentParser();p.add_argument('--model',required=True);p.add_argument('--poses',required=True);p.add_argument('--out',required=True);a=p.parse_args()
g=Glb(Path(a.model)/'character.glb');data=json.loads(Path(a.poses).read_text('utf-8'));assert data['modelSha256']==hashlib.sha256(g.raw).hexdigest();rows=[]
for pose in data['poses']:
    for view,direction in [('front',[0,0,1]),('right',[.45,0,1]),('left',[-.45,0,1])]:
        eyes,orient=check_pose(g,pose,direction);rows.append({'pose':pose['name'],'view':view,'eyes':eyes,'patches':orient})
result={'unchangedModelSha256':data['modelSha256'],'passed':all(e['eyeProbes']==0 for row in rows for e in row['eyes']),
         'measurements':rows,'scope':'Two added triangles in in-memory ray test only, normal-consistent winding, all original Face/Eye geometry retained. Not an authored asset, appearance, or deployment proof.'}
out=Path(a.out);assert not out.exists();out.write_text(json.dumps(result,indent=2),'utf-8');print(json.dumps(result))
