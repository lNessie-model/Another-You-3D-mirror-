"""Local brow/smile/lower-lip refinement on the selected head; retain its PBR maps."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from head_glb_edit import GlbEdit
from refine_jaw_close import lip_loops
from tripo_head_adapter import Glb
from head_surface_topology import FrontSurface


def smoothstep(a,b,value):
    t=np.clip((value-a)/(b-a),0,1);return t*t*(3-2*t)


def attached_delta(points,vertices,triangles,deltas):
    """Interpolate a new field at the bridge's actual existing triangle attachments."""
    v=vertices[triangles];a=v[:,0];ab=v[:,1]-a;ac=v[:,2]-a
    d00=(ab*ab).sum(1);d01=(ab*ac).sum(1);d11=(ac*ac).sum(1);den=d00*d11-d01*d01
    valid=den>1e-22;result=[];errors=[]
    for begin in range(0,len(points),16):
        p=points[begin:begin+16];q=p[:,None]-a;d20=np.einsum('pti,ti->pt',q,ab);d21=np.einsum('pti,ti->pt',q,ac)
        u=np.zeros_like(d20);w=u.copy();u[:,valid]=(d11[valid]*d20[:,valid]-d01[valid]*d21[:,valid])/den[valid]
        w[:,valid]=(d00[valid]*d21[:,valid]-d01[valid]*d20[:,valid])/den[valid]
        bary=np.stack([1-u-w,u,w],axis=-1);distance=((q-u[:,:,None]*ab-w[:,:,None]*ac)**2).sum(2)
        distance[:,~valid]=np.inf;distance[(u<0)|(w<0)|(u+w>1)]=np.inf
        for j,k in [(0,1),(1,2),(2,0)]:
            edge=v[:,k]-v[:,j];q=p[:,None]-v[:,j]
            t=np.clip(np.einsum('pti,ti->pt',q,edge)/np.maximum((edge*edge).sum(1),1e-30),0,1)
            candidate=((q-t[:,:,None]*edge)**2).sum(2);better=candidate<distance
            replacement=np.zeros_like(bary);replacement[:,:,j]=1-t;replacement[:,:,k]=t
            bary[better]=replacement[better];distance=np.minimum(distance,candidate)
        closest=distance.argmin(1);rows=np.arange(len(p));weights=bary[rows,closest]
        result.extend(np.einsum('pc,pcv->pv',weights,deltas[triangles[closest]]));errors.extend(np.sqrt(distance[rows,closest]))
    assert max(errors)<2e-7,'New eyebrow field lost a bridge attachment'
    return np.asarray(result),float(max(errors))


def author(source,landmark_report,out,brow_carrier):
    source=Path(source);out=Path(out);assert not out.exists()
    spec=json.loads(Path(landmark_report).read_text('utf-8'));cx,cy=spec['mouth_center'];half=spec['half_width']
    edit=GlbEdit(source/'character.glb');g=edit.source;mesh=g.doc['meshes'][0];names=mesh['extras']['targetNames']
    pivot=np.array(next(n['translation'] for n in g.doc['nodes'] if n['name']=='Head'))
    ji=names.index('jawOpen');ci=names.index('correctiveJawOpenMouthClose')
    pr=mesh['primitives'][1];rp=g.read(pr['attributes']['POSITION']).astype(float)+pivot
    j=g.read(pr['targets'][ji]['POSITION']).astype(float)
    inner,outer,distance,lip=lip_loops(rp,g.read(pr['indices']).reshape(-1,3),cy)
    lower=inner[j[inner,1]<-1e-7];q=np.clip(abs(rp[lower,0]-cx)/half,0,1)
    center=lower[q<.10];assert len(center)>0
    drop=float(-j[center,1].mean());assert .010<drop<.025
    desired=-drop*np.sqrt(np.maximum(0,1-q**4));changed=np.zeros_like(rp)
    changed[lower,1]=desired-j[lower,1]
    # A flatter central U-shaped lower rim blends to zero at the fixed outer lip.
    order=np.argsort(rp[lower,0]);xs=np.r_[cx-half,rp[lower[order],0],cx+half]
    ys=np.r_[0,changed[lower[order],1],0]
    upper=inner[j[inner,1]>=-1e-7];order=np.argsort(rp[upper,0]);line_x=rp[upper[order],0];line_y=rp[upper[order],1]
    for vertex in np.flatnonzero(lip):
        if rp[vertex,1]<np.interp(rp[vertex,0],line_x,line_y):
            blend=smoothstep(0,1,distance[vertex]/10)
            changed[vertex,1]=np.interp(rp[vertex,0],xs,ys)*blend
    changed[lower,1]=desired-j[lower,1]
    change_by_primitive={1:changed}
    oral=mesh['primitives'][2];op=g.read(oral['attributes']['POSITION']).astype(float)+pivot
    colors=g.read(oral['attributes']['COLOR_0']);oj=g.read(oral['targets'][ji]['POSITION'])
    groups,counts=np.unique(colors,axis=0,return_counts=True);wall=groups[counts==96]
    wall=wall[np.argsort(wall[:,:3].mean(1))[::-1]];assert len(wall)==3
    oral_change=np.zeros_like(op)
    for group,fade in zip(wall,(1,.5,0)):
        for vertex in np.flatnonzero((colors==group).all(1)):
            if fade==1:
                other=inner[np.argmin(np.linalg.norm(rp[inner]-op[vertex],axis=1))]
                assert np.linalg.norm(rp[other]-op[vertex])<1e-7
                oral_change[vertex]=changed[other]
            elif oj[vertex,1]<-1e-7:oral_change[vertex,1]=np.interp(op[vertex,0],xs,ys)*fade
    change_by_primitive[2]=oral_change
    # Author on the pre-socket triangle carrier, then interpolate at cut vertices.
    # Evaluating a nonlinear field independently at T junctions creates cracks.
    carrier=Glb(Path(brow_carrier)/'character.glb');cp=carrier.doc['meshes'][0]['primitives'][0]
    carrier_p=carrier.read(cp['attributes']['POSITION']).astype(float)+pivot
    carrier_ids=carrier.read(cp['indices']).reshape(-1,3)
    region=((carrier_p[:,0]-cx)/.029)**2+((carrier_p[:,1]-.031)/.026)**2
    carrier_up=np.zeros_like(carrier_p)
    carrier_up[:,1]=.006*(1-smoothstep(.12,1,region))*smoothstep(.019,.025,carrier_p[:,1])*smoothstep(.025,.050,carrier_p[:,2])
    # Keep the nasal root from forming a pointed central hump while the two
    # actual inner brow ends rise. Do this on the carrier, before interpolation.
    nasal=(1-smoothstep(.0025,.007,abs(carrier_p[:,0]-.0015)))*(1-smoothstep(.026,.034,carrier_p[:,1]))
    carrier_up[:,1]*=1-.65*nasal
    affected=carrier_p[carrier_ids[np.any(carrier_up[carrier_ids,1]>0,axis=1)]].reshape(-1,3)
    low=affected.min(0)-2e-7;high=affected.max(0)+2e-7
    up_fields=[];positions=[]
    for primitive in mesh['primitives']:
        p=g.read(primitive['attributes']['POSITION']).astype(float)+pivot;positions.append(p)
        up_fields.append(np.zeros_like(p))
    selection=np.flatnonzero(((positions[0]>=low)&(positions[0]<=high)).all(1))
    up_fields[0][selection],carrier_error=attached_delta(positions[0][selection],carrier_p,carrier_ids,carrier_up)
    ocular=json.loads((source/'ocular-authoring.json').read_text('utf-8'))
    prefix=len(rp)-ocular['orbitalBridge']['addedVertices'];assert prefix==2080
    # Outer lids share their source field. Fade toward an unmoved inner rim so
    # brow lift does not open the eye, including at full blink.
    carrier_surface=FrontSurface(np.c_[carrier_p,carrier_up],carrier_ids)
    for side,sign in [('Left',1),('Right',-1)]:
        group=np.flatnonzero((rp[:prefix,1]>.01)&(sign*rp[:prefix,0]>.004));assert len(group)==512
        for row in range(8):
            for vertex in group[row*64:(row+1)*64]:
                up_fields[1][vertex]=carrier_surface.sample(*rp[vertex,:2])[3:]*(1-smoothstep(0,1,row/7))
    brow_fields={'browInnerUp':up_fields}
    eye_groups=[np.flatnonzero((rp[:prefix,1]>.01)&(sign*rp[:prefix,0]>.004)) for sign in (1,-1)]
    for name in ('browDownLeft','browDownRight','browOuterUpLeft','browOuterUpRight'):
        values=[g.read(primitive['targets'][names.index(name)]['POSITION']).astype(float) for primitive in mesh['primitives']]
        if name.startswith('browDown'):values=[value*.65 for value in values]
        for group in eye_groups:
            for row in range(8):values[1][group[row*64:(row+1)*64]]*=1-smoothstep(0,1,row/7)
        brow_fields[name]=values
    combined=np.r_[positions[0],positions[1][:prefix]];fields=np.r_[up_fields[0],up_fields[1][:prefix]]
    skin_ids=g.read(mesh['primitives'][0]['indices']).reshape(-1,3)
    ring_ids=g.read(mesh['primitives'][1]['indices']).reshape(-1,3)
    ring_ids=ring_ids[(ring_ids<prefix).all(1)]+len(positions[0])
    triangles=np.r_[skin_ids,ring_ids];near=(combined[triangles,1].max(1)>.008)&(combined[triangles,2].max(1)>.03)
    attachment_error=0
    for values in brow_fields.values():
        field=np.r_[values[0],values[1][:prefix]]
        values[1][prefix:],error=attached_delta(rp[prefix:],combined,triangles[near],field)
        attachment_error=max(attachment_error,error)
    smile_fields={}
    for side,sign in [('Left',1),('Right',-1)]:
        name='mouthSmile'+side;index=names.index(name)
        old=carrier.read(cp['targets'][index]['POSITION']).astype(float)
        smile=old*np.array([1.6,1.6,1])
        q=np.clip(sign*(carrier_p[:,0]-cx)/half,0,1)
        smile[:,1]*=q*q
        smile[:,2]-=.0006*np.clip(old[:,1]/.003,0,1)
        region=((carrier_p[:,0]-(cx+sign*half*1.4))/.027)**2+((carrier_p[:,1]-(cy+.024))/.026)**2
        cheek=(1-smoothstep(.12,1,region))*smoothstep(.030,.060,carrier_p[:,2])*(1-smoothstep(.003,.010,carrier_p[:,1]))
        smile[:,1]+=.0012*cheek;smile[:,2]+=.0008*cheek
        affected=carrier_p[carrier_ids[np.any(np.linalg.norm(smile[carrier_ids],axis=2)>0,axis=1)]].reshape(-1,3)
        low=affected.min(0)-2e-7;high=affected.max(0)+2e-7
        values=[np.zeros_like(p) for p in positions]
        for pi,count in [(0,len(positions[0])),(1,prefix)]:
            p=positions[pi][:count];selection=np.flatnonzero(((p>=low)&(p<=high)).all(1))
            # Eye annuli are above the smile carrier; their original positions
            # were lifted onto eyeballs and should retain zero smile motion.
            if pi==1:selection=selection[p[selection,1]<.01]
            values[pi][selection],_=attached_delta(p[selection],carrier_p,carrier_ids,smile)
        field=np.r_[values[0],values[1][:prefix]]
        values[1][prefix:],_=attached_delta(rp[prefix:],combined,triangles[near],field)
        for group,fade in zip(wall,(1,.5,0)):
            for vertex in np.flatnonzero((colors==group).all(1)):
                other=inner[np.argmin(np.linalg.norm(rp[inner]-op[vertex],axis=1))]
                values[2][vertex]=values[1][other]*fade
        smile_fields[name]=values
    extra=[]
    for side,sign in [('Left',1),('Right',-1)]:
        group=np.flatnonzero((rp[:prefix,1]>.01)&(sign*rp[:prefix,0]>.004));assert len(group)==512
        correction=np.zeros_like(rp);source_up=up_fields[1]
        for row in range(8):
            fade=smoothstep(0,1,row/7)
            for column in range(64):
                vertex=group[row*64+column];other=group[row*64+(-column)%64]
                correction[vertex]=(source_up[other]-source_up[vertex])*.5*fade
        extra.append(('correctiveBlinkBrowInnerUp'+side,side,correction))
    for pi,primitive in enumerate(mesh['primitives']):
        actual=edit.doc['meshes'][0]['primitives'][pi];p=g.read(primitive['attributes']['POSITION']).astype(float)+pivot
        for name,values in {**brow_fields,**smile_fields}.items():
            # Existing skin/other primitives of outer-up/down are byte exact;
            # only their eyelid/bridge deltas require independent brow response.
            if name.startswith('browOuterUp') and pi!=1:continue
            actual['targets'][names.index(name)]['POSITION']=edit.add(values[pi],'VEC3')
        if pi in change_by_primitive:
            change=change_by_primitive[pi]
            actual['targets'][ji]['POSITION']=edit.add(g.read(primitive['targets'][ji]['POSITION'])+change,'VEC3')
            # Cancel only the additional lip re-shaping at closed lips. The
            # mandible and its teeth keep their existing independent movement.
            actual['targets'][ci]['POSITION']=edit.add(g.read(primitive['targets'][ci]['POSITION'])-change,'VEC3')
        for name,side,correction in extra:
            actual['targets'].append({'POSITION':edit.add(correction if pi==1 else np.zeros_like(p),'VEC3')})
    edit.doc['meshes'][0]['extras']['targetNames']+= [row[0] for row in extra]
    edit.doc['meshes'][0]['weights']+= [0.0]*len(extra)
    for node in edit.doc['nodes']:
        if node.get('mesh')==0 and 'weights' in node:node['weights']+=[0.0]*len(extra)
    out.mkdir();target=out/'character.glb';edit.write(target)
    manifest=json.loads((source/'avatar.json').read_text('utf-8'));manifest['id']+='-expression-refined'
    for name,side,correction in extra:
        manifest['derivedBindings'].append({'operation':'product','sources':['eyeBlink'+side,'browInnerUp'],'mesh':'Face','target':name,'gain':1})
    manifest['displayName']='白发头模 · 眉眼与自然唇形精修候选';manifest['modelSha256']=hashlib.sha256(target.read_bytes()).hexdigest()
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    report={'sourceModelSha256':hashlib.sha256(g.raw).hexdigest(),'modelSha256':manifest['modelSha256'],
            'mouthCenter':[cx,cy],'halfWidth':half,'centerLowerLipDropMeters':drop,
            'maxAdditionalLipDropMeters':float(abs(changed[:,1]).max()),'browInnerUpRegion':'Pre-socket carrier Y .031m, radiusY .026m, lower fade .019-.025m, 6mm peak; lid inner rim held fixed',
            'browCarrierSha256':hashlib.sha256(carrier.raw).hexdigest(),'maxBrowCarrierAttachmentErrorMeters':carrier_error,
            'nasalRootSuppression':'.65 below Y .026-.034, X centered .0015 radius .0025-.007',
            'maxNeutralBridgeAttachmentErrorMeters':attachment_error,'newCorrectives':[row[0] for row in extra],
            'smileCornerXYScale':1.6,'smileLiftProfile':'q squared on pre-socket carrier; center quiet, outer corner rises',
            'allBrowLidInnerRimsHeldFixed':True,'browDownGeometryScale':.65,'pbrMapsPreserved':True,'teethAndJawNodesUnchanged':True,
            'artistAccepted':False,'tripoCreditsSpent':0,
            'scope':'Brow lift, cheek/corner smile linkage and flatter rounded lower-lip opening; mouth interior remains lower-priority and naturalness requires actual preview/live review'}
    (out/'expression-authoring.json').write_text(json.dumps(report,indent=2),'utf-8');print(json.dumps(report))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--source',required=True);parser.add_argument('--landmark-report',required=True);parser.add_argument('--out',required=True);parser.add_argument('--brow-carrier',required=True)
    args=parser.parse_args();author(args.source,args.landmark_report,args.out,args.brow_carrier)
