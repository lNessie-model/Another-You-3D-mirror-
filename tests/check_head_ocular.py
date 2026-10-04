"""Measure eye readability and actual-pose occlusion, independent of the eye author."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from tripo_head_adapter import Glb


def triangles(g,pose,direction):
    direction=np.array(direction,dtype=float);direction/=np.linalg.norm(direction)
    basis=np.array([[direction[2],0,-direction[0]],[0,1,0],direction])
    positions=[];colors=[];tags=[];centers={}
    for node in pose['nodes']:
        if node['mesh']<0:continue
        matrix=np.array(node['worldMatrixColumnMajor']).reshape(4,4).T
        tag={'LeftEye':1,'RightEye':2}.get(node['name'],0)
        if tag:centers[tag]=matrix[:3,3]@basis.T
        for primitive in g.doc['meshes'][node['mesh']]['primitives']:
            p=g.read(primitive['attributes']['POSITION']).astype(float)
            for weight,target in zip(pose['meshes'][node['mesh']]['weights'],primitive.get('targets',[])):
                if weight:p+=weight*g.read(target['POSITION'])
            p=(p@matrix[:3,:3].T+matrix[:3,3])@basis.T
            indices=g.read(primitive['indices']).reshape(-1,3);v=p[indices]
            front=np.cross(v[:,1]-v[:,0],v[:,2]-v[:,0])[:,2]>1e-12
            positions.extend(v[front]);tags.extend([tag]*int(front.sum()))
            colors.extend(g.read(primitive['attributes']['COLOR_0'])[indices[front],:3])
    return np.asarray(positions),np.asarray(colors),np.array(tags),centers


def visible_eye(g,pose,direction):
    all_v,all_color,all_tag,centers=triangles(g,pose,direction);rows=[]
    for tag,center in centers.items():
        xs=center[0]+np.linspace(-.012,.012,65)
        ys=center[1]+np.linspace(-.007,.007,33)
        probes=np.stack(np.meshgrid(xs,ys),axis=-1).reshape(-1,2)
        low=probes.min(axis=0);high=probes.max(axis=0)
        relevant=(all_v[:,:,:2].max(axis=1)>=low).all(axis=1)&(all_v[:,:,:2].min(axis=1)<=high).all(axis=1)
        v=all_v[relevant];color=all_color[relevant];tags=all_tag[relevant]
        a=v[:,0,:2];b=v[:,1,:2]-a;c=v[:,2,:2]-a;det=b[:,0]*c[:,1]-b[:,1]*c[:,0]
        eye_count=0;white_count=0;exposed=[]
        for begin in range(0,len(probes),96):
            q=probes[begin:begin+96,None,:]-a
            s=(q[:,:,0]*c[:,1]-q[:,:,1]*c[:,0])/det
            t=(b[:,0]*q[:,:,1]-b[:,1]*q[:,:,0])/det
            inside=(s>=-1e-7)&(t>=-1e-7)&(s+t<=1.0000001)
            z=v[:,0,2]+s*(v[:,1,2]-v[:,0,2])+t*(v[:,2,2]-v[:,0,2]);z[~inside]=-np.inf
            hit=np.argmax(z,axis=1);row=np.arange(len(hit))
            eye=(tags[hit]==tag)&np.isfinite(z[row,hit])
            rgb=color[hit,0]+s[row,hit,None]*(color[hit,1]-color[hit,0])+t[row,hit,None]*(color[hit,2]-color[hit,0])
            pigment=(rgb.mean(axis=1)<.035)|(rgb[:,0]/np.maximum(rgb[:,2],1e-6)>2)
            eye_count+=int(eye.sum());white_count+=int((eye&~pigment).sum())
            exposed.extend({'planeXY':xy.tolist(),'worldDepth':float(depth)} for xy,depth in
                           zip(probes[begin:begin+96][eye],z[row,hit][eye]) if len(exposed)<6)
        rows.append({'side':'Left' if tag==1 else 'Right','eyeProbes':eye_count,'scleraProbes':white_count,
                     'scleraFraction':white_count/eye_count if eye_count else None,'gridProbes':len(probes),
                     'firstExposedEyeSamples':exposed[:6]})
    return rows


def point_surface_distances(points,vertices,indices):
    """Independent Euclidean distance to triangles, including edges and corners."""
    tri=vertices[indices];a=tri[:,0];ab=tri[:,1]-a;ac=tri[:,2]-a
    d00=(ab*ab).sum(1);d01=(ab*ac).sum(1);d11=(ac*ac).sum(1);den=d00*d11-d01*d01
    valid=den>1e-22;result=[]
    for begin in range(0,len(points),32):
        p=points[begin:begin+32];q=p[:,None]-a
        d20=np.einsum('pti,ti->pt',q,ab);d21=np.einsum('pti,ti->pt',q,ac)
        u=np.zeros_like(d20);v=u.copy()
        u[:,valid]=(d11[valid]*d20[:,valid]-d01[valid]*d21[:,valid])/den[valid]
        v[:,valid]=(d00[valid]*d21[:,valid]-d01[valid]*d20[:,valid])/den[valid]
        plane=((q-u[:,:,None]*ab-v[:,:,None]*ac)**2).sum(2)
        plane[:,~valid]=np.inf;plane[(u<0)|(v<0)|(u+v>1)]=np.inf
        for j,k in [(0,1),(1,2),(2,0)]:
            edge=tri[:,k]-tri[:,j];q=p[:,None]-tri[:,j];length=(edge*edge).sum(1)
            t=np.clip(np.einsum('pti,ti->pt',q,edge)/np.maximum(length,1e-30),0,1)
            plane=np.minimum(plane,((q-t[:,:,None]*edge)**2).sum(2))
        result.extend(np.sqrt(plane.min(1)))
    return np.asarray(result)


def bridge_attachment(g,old,data,own_fields=False):
    before=old.doc['meshes'][0]['primitives'];after=g.doc['meshes'][0]['primitives'][1]
    prefix=len(old.read(before[1]['attributes']['POSITION']));p=g.read(after['attributes']['POSITION'])[prefix:].astype(float)
    assert len(p)>0,'No new orbital bridge endpoints'
    checks=[]
    for pose in data['poses']:
        weights=pose['meshes'][0]['weights'];actual=p.copy();surfaces=[];indices=[];offset=0
        for pi,pr in enumerate(before[:2]):
            vertices=old.read(pr['attributes']['POSITION']).astype(float)
            reader=g if own_fields else old
            targets=g.doc['meshes'][0]['primitives'][pi]['targets'] if own_fields else pr['targets']
            for weight,target in zip(weights,targets):
                if weight:vertices+=weight*reader.read(target['POSITION'])[:len(vertices)]
            ids=old.read(pr['indices']).reshape(-1,3)
            # Local orbital patch, with a wide guard for the actual brow/cheek poses.
            near=(vertices[ids,1].max(1)>.095)&(vertices[ids,2].max(1)>.03)
            indices.extend(ids[near]+offset);surfaces.extend(vertices);offset+=len(vertices)
        for weight,target in zip(weights,after['targets']):
            if weight:actual+=weight*g.read(target['POSITION'])[prefix:]
        distance=point_surface_distances(actual,np.asarray(surfaces),np.asarray(indices))
        checks.append({'pose':pose['name'],'maxEndpointAttachmentErrorMeters':float(distance.max()),'endpoints':len(p)})
    assert len(checks)==19,'Actual production combination coverage changed'
    maximum=max(row['maxEndpointAttachmentErrorMeters'] for row in checks)
    assert maximum<2e-7,('Bridge separates from source skin/lid under actual morph weights',maximum)
    return {'poses':checks,'maxErrorMeters':maximum,'originalGeometryCurrentMorphFields':own_fields,
            'scope':'Every appended endpoint remains on the pre-bridge skin/lid triangles in all 19 exported poses; current fields used when explicitly requested'}


def check(directory,source=None,poses=None,bridge_template=None):
    root=Path(directory);g=Glb(root/'character.glb')
    data=json.loads((Path(poses) if poses else root/'production-poses.json').read_text('utf-8'))
    assert data['modelSha256']==hashlib.sha256(g.raw).hexdigest()
    eye=g.doc['meshes'][next(n['mesh'] for n in g.doc['nodes'] if n['name']=='LeftEye')]['primitives'][0]
    p=g.read(eye['attributes']['POSITION']);color=g.read(eye['attributes']['COLOR_0'])[:,:3]
    pigment=(color.mean(axis=1)<.035)|(color[:,0]/np.maximum(color[:,2],1e-6)>2)
    diameter=float(np.ptp(p[pigment,0])*1000)
    measurements=[]
    for pose in data['poses']:
        if pose['name'] not in ('neutral','blink-both','blink-wide','blink-squint','gaze-blink','blink-brow-up'):continue
        for view,direction in [('front',[0,0,1]),('right',[.45,0,1]),('left',[-.45,0,1])]:
            measurements.append({'pose':pose['name'],'view':view,'eyes':visible_eye(g,pose,direction)})
    assert {r['pose'] for r in measurements}>={'neutral','blink-both','blink-wide','blink-squint','gaze-blink'}
    neutral=[eye for row in measurements if row['pose']=='neutral' and row['view']=='front' for eye in row['eyes']]
    closure=[eye for row in measurements if row['pose'] in ('blink-both','blink-wide','blink-squint','gaze-blink','blink-brow-up') for eye in row['eyes']]
    passed=9.5<=diameter<=12.5 and all(e['eyeProbes']>30 and e['scleraFraction']<=.45 for e in neutral)
    passed=passed and all(e['eyeProbes']==0 for e in closure)
    preserved=False;attachment=None
    if source:
        old=Glb(Path(source)/'character.glb')
        assert len(old.doc['nodes'])==len(g.doc['nodes']) and old.doc['materials']==g.doc['materials']
        for a,b in zip(old.doc['nodes'],g.doc['nodes']):
            if a['name'] in ('LeftEye','RightEye'):
                assert a.keys()==b.keys()
                for name,value in a.items():
                    if name!='translation':assert value==b[name]
                assert a['translation'][:2]==b['translation'][:2]
                assert 0<=a['translation'][2]-b['translation'][2]<=.0015
            else:assert a==b
        for mi,(before,after) in enumerate(zip(old.doc['meshes'],g.doc['meshes'])):
            assert len(before['primitives'])==len(after['primitives'])
            for pi,(a,b) in enumerate(zip(before['primitives'],after['primitives'])):
                bridge=mi==0 and pi==1
                old_count=len(old.read(a['attributes']['POSITION']))
                indices=old.read(a['indices']);actual_indices=g.read(b['indices'])
                assert np.array_equal(indices,actual_indices[:len(indices)] if bridge else actual_indices)
                for name,index in a['attributes'].items():
                    if mi==1 and name=='COLOR_0':continue
                    if mi==1 and name=='POSITION':
                        values=old.read(index);actual=g.read(b['attributes'][name])
                        ratio=np.linalg.norm(actual,axis=1).mean()/np.linalg.norm(values,axis=1).mean()
                        assert .91<=ratio<=1 and np.max(abs(actual-values*ratio))<1e-7
                        continue
                    values=old.read(index);actual=g.read(b['attributes'][name])
                    assert np.array_equal(values,actual[:old_count] if bridge else actual),(mi,name)
                for ta,tb in zip(a.get('targets',[]),b.get('targets',[])):
                    for name,index in ta.items():
                        values=old.read(index);actual=g.read(tb[name])
                        assert np.array_equal(values,actual[:old_count] if bridge else actual)
        for a,b in zip(old.doc['images'],g.doc['images']):
            va=old.doc['bufferViews'][a['bufferView']];vb=g.doc['bufferViews'][b['bufferView']]
            sa=old.start+va.get('byteOffset',0);sb=g.start+vb.get('byteOffset',0)
            assert old.raw[sa:sa+va['byteLength']]==g.raw[sb:sb+vb['byteLength']]
        preserved=True
        attachment=bridge_attachment(g,old,data)
    if bridge_template:
        template=Glb(Path(bridge_template)/'character.glb')
        attachment=bridge_attachment(g,template,data,own_fields=True)
    return {'modelSha256':data['modelSha256'],'passed':passed,'pigmentedCapDiameterMm':diameter,
            'existingFaceGeometryAndMorphsPreserved':preserved,'orbitalBridgeAttachment':attachment,'measurements':measurements,
            'scope':'Art-readability targets and 2145 orthographic rays per eye/view in the listed actual poses; no anatomy, all-angle or live optical acceptance'}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('directory');parser.add_argument('--source');parser.add_argument('--report')
    parser.add_argument('--poses');parser.add_argument('--bridge-template')
    args=parser.parse_args();result=check(args.directory,args.source,args.poses,args.bridge_template)
    if args.report:
        path=Path(args.report);assert not path.exists();path.write_text(json.dumps(result,indent=2),'utf-8')
    print(json.dumps(result));raise SystemExit(0 if result['passed'] else 1)
