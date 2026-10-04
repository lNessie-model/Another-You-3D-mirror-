"""Locate the actual surfaces behind one failed eye-occlusion sample."""
import argparse,hashlib,json
from pathlib import Path
import numpy as np
from tripo_head_adapter import Glb
p=argparse.ArgumentParser();p.add_argument('--model',required=True);p.add_argument('--poses',required=True);p.add_argument('--pose',required=True);p.add_argument('--out',required=True)
p.add_argument('--x',type=float,default=.04291492403637979);p.add_argument('--y',type=float,default=.023468067999999998);a=p.parse_args()
g=Glb(Path(a.model)/'character.glb');data=json.loads(Path(a.poses).read_text('utf-8'))
assert data['modelSha256']==hashlib.sha256(g.raw).hexdigest(),'Pose data belongs to a different asset'
pose=next(x for x in data['poses'] if x['name']==a.pose)
direction=np.array([-.45,0,1]);direction/=np.linalg.norm(direction);basis=np.array([[direction[2],0,-direction[0]],[0,1,0],direction]);point=np.array([a.x,a.y]);rows=[];closest=[]
for node in pose['nodes']:
    if node['mesh']<0:continue
    matrix=np.array(node['worldMatrixColumnMajor']).reshape(4,4).T
    for pi,primitive in enumerate(g.doc['meshes'][node['mesh']]['primitives']):
        v=g.read(primitive['attributes']['POSITION']).astype(float)
        for weight,target in zip(pose['meshes'][node['mesh']]['weights'],primitive.get('targets',[])):
            if weight:v+=weight*g.read(target['POSITION'])
        v=(v@matrix[:3,:3].T+matrix[:3,3])@basis.T;ids=g.read(primitive['indices']).reshape(-1,3);tri=v[ids]
        aa=tri[:,0,:2];ab=tri[:,1,:2]-aa;ac=tri[:,2,:2]-aa;det=ab[:,0]*ac[:,1]-ab[:,1]*ac[:,0];good=abs(det)>1e-15
        q=point-aa;s=np.zeros(len(tri));t=s.copy();s[good]=(q[good,0]*ac[good,1]-q[good,1]*ac[good,0])/det[good]
        t[good]=(ab[good,0]*q[good,1]-ab[good,1]*q[good,0])/det[good]
        inside=good&(s>=-1e-7)&(t>=-1e-7)&(s+t<=1.0000001)
        if node['name']=='Face':
            distances=np.full(len(tri),np.inf);nearest=np.zeros((len(tri),3))
            for j,k in [(0,1),(1,2),(2,0)]:
                edge=tri[:,k,:2]-tri[:,j,:2];q2=point-tri[:,j,:2]
                f=np.clip((q2*edge).sum(1)/np.maximum((edge*edge).sum(1),1e-30),0,1)
                hit3=tri[:,j]+f[:,None]*(tri[:,k]-tri[:,j]);distance=((hit3[:,:2]-point)**2).sum(1)
                choose=distance<distances;distances[choose]=distance[choose];nearest[choose]=hit3[choose]
            valid=(det>1e-12)&(tri[:,:,2].max(1)>.035)
            candidate=np.flatnonzero(valid);candidate=candidate[np.argsort(distances[candidate])[:8]]
            for i in candidate:
                closest.append({'primitive':pi,'triangle':int(i),'distanceToProjectedBoundaryMeters':float(np.sqrt(distances[i])),
                  'closestWorldPoint':(nearest[i]@basis).tolist(),'worldVertices':(tri[i]@basis).tolist(),'indices':ids[i].tolist()})
        for i in np.flatnonzero(inside):
            z=tri[i,0,2]+s[i]*(tri[i,1,2]-tri[i,0,2])+t[i]*(tri[i,2,2]-tri[i,0,2])
            rows.append({'node':node['name'],'primitive':pi,'triangle':int(i),'front':bool(det[i]>1e-12),'projectedAreaSquareMm':float(det[i]*.5*1e6),
                         'viewDepth':float(z),'worldPoint':(np.r_[point,z]@basis).tolist(),'bary':[float(1-s[i]-t[i]),float(s[i]),float(t[i])],
                         'worldVertices':(tri[i]@basis).tolist(),'indices':ids[i].tolist()})
rows.sort(key=lambda x:x['viewDepth'],reverse=True);closest.sort(key=lambda x:x['distanceToProjectedBoundaryMeters']);report={'modelSha256':data['modelSha256'],'pose':a.pose,'planeXY':point.tolist(),'direction':direction.tolist(),'hitSurfacesFrontToBack':rows,'closestFaceBoundaries':closest[:12],'scope':'Known failed eye sample; includes culled back faces and closest projected front surfaces for diagnosis.'}
out=Path(a.out);assert not out.exists();out.write_text(json.dumps(report,indent=2),'utf-8');print(json.dumps(report))
