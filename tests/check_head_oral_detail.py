"""Independent preservation, oral seam, dental hierarchy and closed-lip occlusion gates."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from tripo_head_adapter import Glb
from check_dentition import check as check_dentition


def deformed(g,pr,weights):
    p=g.read(pr['attributes']['POSITION']).astype(float)
    for weight,t in zip(weights,pr.get('targets',[])):
        if weight:p+=weight*g.read(t['POSITION'])
    return p


def world(points,matrix):
    return points@matrix[:3,:3].T+matrix[:3,3]


def exposed(points,triangles,direction):
    direction=np.asarray(direction,dtype=float);direction/=np.linalg.norm(direction)
    u=np.cross([0,1,0],direction);u/=np.linalg.norm(u);v=np.cross(direction,u)
    basis=np.array([u,v,direction]);q=points@basis.T;t=triangles@basis.T
    a=t[:,0];ab=t[:,1]-a;ac=t[:,2]-a
    det=ab[:,0]*ac[:,1]-ab[:,1]*ac[:,0];good=abs(det)>1e-18
    a=a[good];ab=ab[good];ac=ac[good];det=det[good]
    output=[]
    for start in range(0,len(q),128):
        sample=q[start:start+128];d=sample[:,None,:2]-a[None,:,:2]
        b=(d[:,:,0]*ac[None,:,1]-d[:,:,1]*ac[None,:,0])/det
        c=(ab[None,:,0]*d[:,:,1]-ab[None,:,1]*d[:,:,0])/det
        hit=(b>=-1e-10)&(c>=-1e-10)&(b+c<=1+1e-10)
        z=a[None,:,2]+b*ab[None,:,2]+c*ac[None,:,2]
        output.extend(~(hit&(z>sample[:,None,2]+1e-7)).any(axis=1))
    return np.asarray(output)


def tongue_wall_contacts(points,indices):
    """Segment intersections, independent of the authoring report's position claims."""
    edges=np.unique(np.sort(np.r_[indices[:,[0,1]],indices[:,[1,2]],indices[:,[2,0]]],axis=1),axis=0)
    edges=edges[(edges[:,0]>=289)&(edges[:,0]<310)]
    tri=points[indices[(indices<289).all(axis=1)]];a=tri[:,0];ab=tri[:,1]-a;ac=tri[:,2]-a;count=0
    for edge in edges:
        origin=points[edge[0]];direction=points[edge[1]]-origin
        h=np.cross(np.tile(direction,(len(tri),1)),ac);det=np.einsum('ij,ij->i',ab,h);valid=abs(det)>1e-18
        f=np.zeros(len(tri));f[valid]=1/det[valid];s=origin-a;u=f*np.einsum('ij,ij->i',s,h)
        q=np.cross(s,ab);v=f*(q@direction);distance=f*np.einsum('ij,ij->i',ac,q)
        count+=int((valid&(u>=0)&(v>=0)&(u+v<=1)&(distance>=0)&(distance<=1)).sum())
    return count


def check(directory,source,poses):
    directory=Path(directory);g=Glb(directory/'character.glb');old=Glb(Path(source)/'character.glb')
    report=json.loads((directory/'oral-detail-authoring.json').read_text('utf-8'))
    assert report['sourceModelSha256']==hashlib.sha256(old.raw).hexdigest()
    manifest=json.loads((directory/'avatar.json').read_text('utf-8'))
    original_manifest=json.loads((Path(source)/'avatar.json').read_text('utf-8'))
    mutable={'id','displayName','modelSha256'}
    assert {k:v for k,v in manifest.items() if k not in mutable}=={k:v for k,v in original_manifest.items() if k not in mutable},'Manifest control bindings changed'
    assert g.doc['nodes']==old.doc['nodes'] and g.doc['materials']==old.doc['materials']
    assert g.doc['meshes'][0]['extras']==old.doc['meshes'][0]['extras']
    for mi in (0,1):
        for pi,(a,b) in enumerate(zip(old.doc['meshes'][mi]['primitives'],g.doc['meshes'][mi]['primitives'])):
            if mi==0 and pi==2:continue
            for field,index in a['attributes'].items():assert np.array_equal(old.read(index),g.read(b['attributes'][field])),field
            assert np.array_equal(old.read(a['indices']),g.read(b['indices']))
            for ta,tb in zip(a.get('targets',[]),b.get('targets',[])):
                for field,index in ta.items():assert np.array_equal(old.read(index),g.read(tb[field]))
    for a,b in zip(old.doc['images'],g.doc['images']):
        oa=old.doc['bufferViews'][a['bufferView']];ob=g.doc['bufferViews'][b['bufferView']]
        ba=old.raw[old.start+oa.get('byteOffset',0):old.start+oa.get('byteOffset',0)+oa['byteLength']]
        bb=g.raw[g.start+ob.get('byteOffset',0):g.start+ob.get('byteOffset',0)+ob['byteLength']]
        assert ba==bb,'Embedded PBR map changed'
    oral=g.doc['meshes'][0]['primitives'][2];ring=g.doc['meshes'][0]['primitives'][1]
    rp=g.read(ring['attributes']['POSITION']);op=g.read(oral['attributes']['POSITION']);inner=report['lipAttachmentRingIndices']
    assert np.array_equal(op[:96],rp[inner]),'Wall is detached from the actual lip boundary'
    for r,o in zip(ring['targets'],oral['targets']):assert np.array_equal(g.read(o['POSITION'])[:96],g.read(r['POSITION'])[inner]),'Wall shape does not match lip boundary'
    normal=g.read(oral['attributes']['NORMAL']);assert np.max(abs(np.linalg.norm(normal,axis=1)-1))<1e-5
    assert normal[288,2]>.99,'Back cavity cap faces away from viewer'
    assert report['lipReferenceDepth']-report['backWallDepth']>.025
    assert report['tongueRootDepth']<report['backWallDepth']-.002,'Tongue root floats in front of rear cavity'
    assert report['tongueVertices']>=70
    dental=check_dentition(directory,source,poses)
    vertices=triangles=draws=0
    for node in g.doc['nodes']:
        if 'mesh' not in node:continue
        for pr in g.doc['meshes'][node['mesh']]['primitives']:
            vertices+=len(g.read(pr['attributes']['POSITION']));triangles+=len(g.read(pr['indices']))//3;draws+=1
    assert vertices<=20000 and triangles<=30000 and draws<=8 and len(g.raw)<=32*1024*1024
    data=json.loads(Path(poses).read_text('utf-8'));assert data['modelSha256']==hashlib.sha256(g.raw).hexdigest()
    occlusion=[];root_contacts=[];max_seam_error=0
    for pose in data['poses']:
        weight=pose['meshes'][0]['weights'];worlds={n['name']:np.array(n['worldMatrixColumnMajor']).reshape(4,4).T for n in pose['nodes']}
        oral_points=deformed(g,oral,weight)
        max_seam_error=max(max_seam_error,float(np.max(abs(oral_points[:96]-deformed(g,ring,weight)[inner]))))
        contacts=tongue_wall_contacts(oral_points,g.read(oral['indices']).reshape(-1,3))
        assert contacts>0,'Tongue root floats without floor/cavity contact in '+pose['name']
        root_contacts.append({'pose':pose['name'],'rootSegmentWallIntersections':contacts})
        if pose['name'] not in ('neutral','jaw-open-mouth-close'):continue
        surface=[]
        for pr in g.doc['meshes'][0]['primitives'][:2]:
            points=world(deformed(g,pr,weight),worlds['Face']);surface.append(points[g.read(pr['indices']).reshape(-1,3)])
        surface=np.concatenate(surface)
        for name in ('UpperDentition','LowerDentition'):
            node=next(n for n in g.doc['nodes'] if n['name']==name);pr=g.doc['meshes'][node['mesh']]['primitives'][0]
            points=world(g.read(pr['attributes']['POSITION']),worlds[name]);indices=g.read(pr['indices']).reshape(-1,3)
            enamel=g.read(pr['attributes']['COLOR_0'])[:,:3].mean(axis=1)>.3
            samples=np.r_[points[enamel],points[indices[enamel[indices].all(axis=1)]].mean(axis=1)]
            for direction in ([0,0,1],[.45,0,1],[-.45,0,1]):
                visible=int(exposed(samples,surface,direction).sum())
                occlusion.append({'pose':pose['name'],'arch':name,'direction':direction,'probes':len(samples),'exposed':visible})
                assert visible==0,'Teeth visible through closed lips: '+str(occlusion[-1])
    assert max_seam_error<2e-8
    return {'passed':True,'modelSha256':hashlib.sha256(g.raw).hexdigest(),'sourceModelSha256':hashlib.sha256(old.raw).hexdigest(),
            'unchangedFaceEyeLipChannelsNodesMaterialsPbrImages':True,'lipWallMaxPoseGapMeters':max_seam_error,
            'instanceVertices':vertices,'triangles':triangles,'draws':draws,'glbBytes':len(g.raw),'dentition':dental,
            'closedLipDentalOcclusion':occlusion,'tongueRootWallContacts':root_contacts,
            'scope':'Actual 19 production poses; dental sample occlusion in three views, rigid arches and resource budgets. Embedded tongue root is intersecting tissue volumes, not a medically qualified mesh or all-view collision proof.'}


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--asset',required=True);p.add_argument('--source',required=True);p.add_argument('--poses',required=True);p.add_argument('--report',required=True)
    a=p.parse_args();result=check(a.asset,a.source,a.poses);target=Path(a.report);assert not target.exists();target.write_text(json.dumps(result,indent=2),'utf-8');print(json.dumps(result))
