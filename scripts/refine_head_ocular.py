"""Refine eye pigment and bridge the existing skin cut to its eyelid outer rim.

Existing vertices and morph deltas remain exact. New bridge endpoints interpolate
the source skin or eyelid attributes, including every named corrective channel.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from head_glb_edit import GlbEdit
from head_surface_topology import FrontSurface


def cross2(a,b):
    return a[...,0]*b[...,1]-a[...,1]*b[...,0]


def attributes(g,primitive):
    return np.c_[g.read(primitive['attributes']['POSITION']),g.read(primitive['attributes']['NORMAL']),
                 g.read(primitive['attributes']['TEXCOORD_0']),g.read(primitive['attributes']['COLOR_0']),
                 *[g.read(t['POSITION']) for t in primitive['targets']]].astype(float)


def bridge_orbits(edit):
    g=edit.source;before=g.doc['meshes'][0]['primitives'];after=edit.doc['meshes'][0]['primitives'][1]
    skin=attributes(g,before[0]);ring=attributes(g,before[1]);pivot=np.array(next(n['translation'] for n in g.doc['nodes'] if n['name']=='Head'))
    skin_ids=g.read(before[0]['indices']).reshape(-1,3)
    surface=FrontSurface(skin,skin_ids)
    # The cut mesh contains narrow but valid float32 slivers. Do not discard
    # them as a raster sampler would: this operation must attach to their skin.
    surface.valid=abs(surface.det)>1e-18
    edge_ids=np.r_[skin_ids[:,[0,1]],skin_ids[:,[1,2]],skin_ids[:,[2,0]]]
    edge_positions=skin[edge_ids,:2];c=edge_positions[:,0];d=edge_positions[:,1]-c
    added=[];faces=[];lookup={};details=[];white_uv=ring[0,6:8]
    # Reuse the existing rim vertices when a bridge endpoint is already there.
    for index,value in enumerate(ring):
        normalized=value.copy();normalized[3:6]/=max(np.linalg.norm(normalized[3:6]),1e-12)
        lookup[tuple(np.round(normalized,9))]=index
    linear_tolerance=np.r_[np.full(3,2e-8),np.full(3,3e-6),np.full(2,2e-7),np.full(4,1e-6),np.full(ring.shape[1]-12,2e-8)]
    white=g.sample_base_colour(white_uv[None,:],before[1]['material'])[0,:3]
    def vertex(value):
        value=value.copy();value[3:6]/=max(np.linalg.norm(value[3:6]),1e-12)
        # Quantize only the lookup, keeping the original interpolated coordinates.
        key=tuple(np.round(value,9));index=lookup.get(key)
        if index is None:index=len(ring)+len(added);lookup[key]=index;added.append(value)
        return index
    def face_at(xy):
        q=xy-surface.a;valid=surface.valid
        s=np.zeros(len(valid));t=s.copy()
        s[valid]=cross2(q[valid],surface.c[valid])/surface.det[valid]
        t[valid]=cross2(surface.b[valid],q[valid])/surface.det[valid]
        hit=valid&(s>=-1e-7)&(t>=-1e-7)&(s+t<=1.0000001)
        z=surface.v[:,0,2]+s*(surface.v[:,1,2]-surface.v[:,0,2])+t*(surface.v[:,2,2]-surface.v[:,0,2])
        candidates=np.flatnonzero(hit&(z>.035))
        if not len(candidates):
            # Independently rounded UV-island vertices leave sub-micron projected
            # gaps. Attach to an actual front triangle edge within float precision.
            distances=np.full(len(valid),np.inf)
            for j,k in [(0,1),(1,2),(2,0)]:
                a=surface.v[:,j,:2];edge=surface.v[:,k,:2]-a
                f=np.clip(((xy-a)*edge).sum(1)/np.maximum((edge*edge).sum(1),1e-30),0,1)
                distances=np.minimum(distances,((xy-a-f[:,None]*edge)**2).sum(1))
            candidates=np.flatnonzero(valid&(z>.035)&(distances<(2e-7)**2))
        assert len(candidates),'No front skin within float precision of orbital bridge'
        face=int(candidates[np.argmax(z[candidates])])
        assert z[face]>.035,('Bridge must attach to front orbital skin, never the back of the head',xy.tolist(),float(z[face]))
        return face
    def sample_face(face,xy):
        q=xy-surface.a[face];s=cross2(q,surface.c[face])/surface.det[face]
        t=cross2(surface.b[face],q)/surface.det[face]
        weights=np.array([1-s-t,s,t])
        if weights.min()<0:
            choices=[]
            for j,k in [(0,1),(1,2),(2,0)]:
                a=surface.v[face,j,:2];edge=surface.v[face,k,:2]-a
                f=np.clip(((xy-a)*edge).sum()/max((edge*edge).sum(),1e-30),0,1)
                w=np.zeros(3);w[j]=1-f;w[k]=f
                choices.append((float(np.linalg.norm(xy-a-f*edge)),w))
            error,weights=min(choices,key=lambda v:v[0]);assert error<2e-7,'Bridge interval crosses an unrelated skin triangle'
        return weights@surface.v[face]
    world=ring[:,:3]+pivot
    for side,sign in [('Left',1),('Right',-1)]:
        group=np.flatnonzero((world[:,1]>.01)&(sign*world[:,0]>.004))
        assert len(group)==512,'Expected the selected head’s eight by 64 eyelid annulus'
        loop=group[:64];xy=ring[loop,:2];ab=np.roll(xy,-1,axis=0)-xy
        outward=np.c_[ab[:,1],-ab[:,0]];outward/=np.linalg.norm(outward,axis=1)[:,None]
        offset=outward+np.roll(outward,1,axis=0);offset*=5e-5/(offset*outward).sum(axis=1)[:,None]
        skin_xy=xy+offset;intervals=[];raw_count=0
        for j in range(64):
            start=skin_xy[j];delta=skin_xy[(j+1)%64]-start;det=cross2(delta,d);valid=abs(det)>1e-14
            q=c-start;t=np.zeros(len(d));u=t.copy()
            t[valid]=cross2(q[valid],d[valid])/det[valid];u[valid]=cross2(q[valid],delta)/det[valid]
            hit=valid&(t>1e-6)&(t<1-1e-6)&(u>=-1e-7)&(u<=1.0000001)
            breaks=np.r_[0,np.unique(np.round(t[hit],7)),1]
            local=[]
            for lo,hi in zip(breaks[:-1],breaks[1:]):
                if hi-lo<1e-7:continue
                face=face_at(start+delta*((lo+hi)/2));a=sample_face(face,start+delta*lo);b=sample_face(face,start+delta*hi)
                # Adjacent cut fragments of one original linear surface need no
                # extra split. Retain UV, normal and morph discontinuities.
                if local:
                    oldlo,oldhi,olda,oldb=local[-1];combined=olda+(b-olda)*((lo-oldlo)/(hi-oldlo))
                    if np.all(abs(oldb-a)<linear_tolerance) and np.all(abs(a-combined)<linear_tolerance):
                        local[-1]=(oldlo,hi,olda,b);continue
                local.append((lo,hi,a,b))
            raw_count+=len(breaks)-1
            for lo,hi,a,b in local:
                ra=ring[loop[j]]+(ring[loop[(j+1)%64]]-ring[loop[j]])*lo
                rb=ring[loop[j]]+(ring[loop[(j+1)%64]]-ring[loop[j]])*hi
                for value in (a,b):
                    rgb=g.sample_base_colour(value[6:8][None,:],before[0]['material'])[0,:3]*value[8:11]
                    value[6:8]=white_uv;value[8:11]=np.clip(rgb/white,0,1)
                aa,bb,cc,dd=map(vertex,(a,b,ra,rb))
                for tri in ([aa,bb,cc],[bb,dd,cc]):
                    p=np.array([(ring[v] if v<len(ring) else added[v-len(ring)])[:3] for v in tri])
                    if np.linalg.norm(np.cross(p[1]-p[0],p[2]-p[0]))>1e-12:faces.append(tri)
                intervals.append((j,lo,hi))
        details.append({'side':side,'rawIntervals':raw_count,'linearIntervals':len(intervals),'skinOverlapMeters':5e-5})
    values=np.r_[ring,added];ids=np.r_[g.read(before[1]['indices']).reshape(-1,3),faces]
    for name,column,width,kind in [('POSITION',0,3,'VEC3'),('NORMAL',3,3,'VEC3'),('TEXCOORD_0',6,2,'VEC2'),('COLOR_0',8,4,'VEC4')]:
        after['attributes'][name]=edit.add(values[:,column:column+width],kind)
    after['indices']=edit.add(ids.reshape(-1),'SCALAR')
    for i,target in enumerate(after['targets']):target['POSITION']=edit.add(values[:,12+3*i:15+3*i],'VEC3')
    return {'addedVertices':len(added),'addedTriangles':len(faces),'sides':details,
            'attachment':'Interpolated original skin triangles and eyelid outer edges, including all morph deltas'}


def author(source,out,recess_mm=0,radius_mm=11.5):
    source=Path(source);out=Path(out);assert not out.exists()
    assert 0<=recess_mm<=1.5 and 10.5<=radius_mm<=11.5
    edit=GlbEdit(source/'character.glb');g=edit.source;doc=edit.doc;radius=.0115
    eye_index=next(n['mesh'] for n in doc['nodes'] if n['name']=='LeftEye')
    ocular=doc['meshes'][eye_index]['primitives'][0];old_eye=g.doc['meshes'][eye_index]['primitives'][0]
    positions=g.read(old_eye['attributes']['POSITION']).astype(float)
    assert np.max(abs(np.linalg.norm(positions,axis=1)-radius))<1e-7
    uv=g.read(old_eye['attributes']['TEXCOORD_0']);white=g.sample_base_colour(uv[:1],old_eye['material'])[0,:3]
    phi=np.arccos(np.clip(positions[:,2]/radius,-1,1));theta=np.arctan2(positions[:,1],positions[:,0])
    rgb=np.tile([.275,.255,.235],(len(positions),1))
    pupil=phi<=.16001;iris=(phi>.16001)&(phi<.50);limbus=(phi>=.50)&(phi<=.55001)
    radial=np.clip((phi[iris]-.16)/(.50-.16),0,1)
    streak=.73+.16*np.sin(theta[iris]*7+phi[iris]*39)**2+.11*np.sin(theta[iris]*11-phi[iris]*27)**2
    rgb[iris]=np.array([.125,.088,.032])[None,:]*streak[:,None]*(1-.3*radial[:,None])
    rgb[pupil]=[.006,.005,.004];rgb[limbus]=[.018,.014,.009]
    color=np.c_[rgb/white,np.ones(len(rgb))];assert color.min()>=0 and color.max()<=1
    ocular['attributes']['COLOR_0']=edit.add(color,'VEC4')
    ocular['attributes']['POSITION']=edit.add(positions*(radius_mm/11.5),'VEC3')
    for side in ('Left','Right'):
        node=next(n for n in doc['nodes'] if n['name']==side+'Eye');node['translation'][2]-=recess_mm/1000
    bridge=bridge_orbits(edit)
    out.mkdir();target=out/'character.glb';edit.write(target)
    manifest=json.loads((source/'avatar.json').read_text('utf-8'));manifest['id']+='-ocular-seat'
    manifest['displayName']='白发头模 · 眼球定位与虹膜精修候选';manifest['modelSha256']=hashlib.sha256(target.read_bytes()).hexdigest()
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    report={'sourceModelSha256':hashlib.sha256(g.raw).hexdigest(),'modelSha256':manifest['modelSha256'],
            'eyeRecessMeters':recess_mm/1000,'existingFaceGeometryAndMorphDeltasPreserved':True,'orbitalBridge':bridge,'pbrMapsPreserved':True,
            'eyeRadiusMeters':radius_mm/1000,'artistAccepted':False,'tripoCreditsSpent':0,
            'scope':'Same head, muted larger iris and source-attached orbital bridge; require actual pose occlusion and artwork review'}
    (out/'ocular-authoring.json').write_text(json.dumps(report,indent=2),'utf-8');print(json.dumps(report))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--source',required=True);parser.add_argument('--out',required=True)
    parser.add_argument('--recess-mm',type=float,default=0);parser.add_argument('--radius-mm',type=float,default=11.5)
    args=parser.parse_args();author(args.source,args.out,args.recess_mm,args.radius_mm)
