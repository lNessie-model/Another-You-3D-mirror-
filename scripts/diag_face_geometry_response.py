"""Independent projected aperture and carrier-continuity metrics for feedback heads."""
import argparse,hashlib,json,sys
from pathlib import Path
import numpy as np
from tripo_head_adapter import Glb
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tests'))
from check_head_ocular import visible_eye

def ease(a,b,x):
    t=np.clip((x-a)/(b-a),0,1);return t*t*(3-2*t)

def diagnose(root,carrier,out,center_y=.037,rx=.032,ry=.030,fade_low=.026,fade_high=.034):
    root=Path(root);g=Glb(root/'character.glb');original=Glb(Path(carrier)/'character.glb')
    data=json.loads((root/'feedback-poses.json').read_text('utf-8'));assert data['modelSha256']==hashlib.sha256(g.raw).hexdigest()
    mesh=g.doc['meshes'][0];names=mesh['extras']['targetNames'];pr=mesh['primitives'][1]
    positions=g.read(pr['attributes']['POSITION']).astype(float)
    pivot=np.array(next(n['translation'] for n in g.doc['nodes'] if n['name']=='Head'))
    q=positions+pivot;prefix=2080;groups={}
    for side,sign in [('Left',1),('Right',-1)]:
        group=np.flatnonzero((q[:prefix,1]>.01)&(sign*q[:prefix,0]>.004));assert len(group)==512
        groups[side]=group.reshape(8,64)
    poses=[]
    for pose in data['poses']:
        if pose['name'] not in ('neutral','wide','brow-up','blink-brow-up'):continue
        p=positions.copy()
        for weight,target in zip(pose['meshes'][0]['weights'],pr['targets']):
            if weight:p+=weight*g.read(target['POSITION'])
        rows=[]
        for side,group in groups.items():
            rim=p[group[-1]];r0=positions[group[-1]]
            area=abs(np.sum(rim[:,0]*np.roll(rim[:,1],-1)-rim[:,1]*np.roll(rim[:,0],-1)))*.5
            upper=(r0[:,1]>np.median(r0[:,1]));lower=~upper
            rows.append({'side':side,'innerProjectedAreaSquareMm':area*1e6,'innerWidthMm':np.ptp(rim[:,0])*1000,
                         'innerHeightMm':np.ptp(rim[:,1])*1000,'upperInnerMeanMoveMm':float((rim[upper,1]-r0[upper,1]).mean()*1000),
                         'lowerInnerMeanMoveMm':float((rim[lower,1]-r0[lower,1]).mean()*1000)})
        row={'pose':pose['name'],'eyes':rows}
        if pose['name'] in ('neutral','wide'):row['frontVisibleEyeProbes']=visible_eye(g,pose,[0,0,1])
        poses.append(row)
    # Independent 2-D barycentric reconstruction of pre-socket carrier fields,
    # rather than the author's nearest-3-D-surface routine.
    cp=original.doc['meshes'][0]['primitives'][0];v=original.read(cp['attributes']['POSITION']).astype(float)+pivot
    ids=original.read(cp['indices']).reshape(-1,3);tri=v[ids];a=tri[:,0,:2];ab=tri[:,1,:2]-a;ac=tri[:,2,:2]-a
    det=ab[:,0]*ac[:,1]-ab[:,1]*ac[:,0];valid=abs(det)>1e-14
    mouth=json.loads((root/'expression-authoring.json').read_text('utf-8'));cx=mouth['mouthCenter'][0]
    # The independent mask declaration is explicit in this report rather than
    # silently borrowing an author's already written deltas.
    r=((v[:,0]-cx)/rx)**2+((v[:,1]-center_y)/ry)**2
    field=.008*(1-ease(.12,1,r))*ease(fade_low,fade_high,v[:,1])*ease(.025,.05,v[:,2])
    skin=mesh['primitives'][0];p=g.read(skin['attributes']['POSITION']).astype(float)+pivot
    delta=g.read(skin['targets'][names.index('browInnerUp')]['POSITION']).astype(float)
    # Track the actual textured eyebrow points identified in the neutral render.
    skin_ids=g.read(skin['indices']).reshape(-1,3);skin_v=p[skin_ids]
    qa=skin_v[:,0,:2];qab=skin_v[:,1,:2]-qa;qac=skin_v[:,2,:2]-qa
    qdet=qab[:,0]*qac[:,1]-qab[:,1]*qac[:,0];qvalid=abs(qdet)>1e-14
    brow_points=[]
    for label,x,y in [('left inner top',-.004831330385059118,.03163500130176544),('left inner bottom',-.003463330678641796,.02889900654554367),
                      ('left brow body',-.00893533043563366,.033003002405166626),('right inner top',.008848669938743114,.031293004751205444),
                      ('right inner bottom',.007480669766664505,.02821500599384308),('right brow body',.014320670627057552,.033003002405166626)]:
        qq=np.array([x,y])-qa;qu=np.zeros(len(qa));qw=qu.copy()
        qu[qvalid]=(qq[qvalid,0]*qac[qvalid,1]-qq[qvalid,1]*qac[qvalid,0])/qdet[qvalid]
        qw[qvalid]=(qab[qvalid,0]*qq[qvalid,1]-qab[qvalid,1]*qq[qvalid,0])/qdet[qvalid]
        hit=qvalid&(qu>=-1e-7)&(qw>=-1e-7)&(qu+qw<=1.0000001)
        qz=skin_v[:,0,2]+qu*(skin_v[:,1,2]-skin_v[:,0,2])+qw*(skin_v[:,2,2]-skin_v[:,0,2]);qz[~hit]=-np.inf
        index=qz.argmax();bary=np.array([1-qu[index]-qw[index],qu[index],qw[index]])
        neutral=np.array([x,y,qz[index]]);authored=bary@delta[skin_ids[index]];pose_points=[]
        for pose in data['poses']:
            if pose['name'] not in ('neutral','brow-up','brow-mixed','blink-brow-up'):continue
            moved=np.zeros(3)
            for weight,target in zip(pose['meshes'][0]['weights'],skin['targets']):
                if weight:moved+=weight*(bary@g.read(target['POSITION'])[skin_ids[index]])
            pose_points.append({'pose':pose['name'],'modelWorld':(neutral+moved).tolist(),'movementMm':(moved*1000).tolist()})
        brow_points.append({'label':label,'neutralWorld':neutral.tolist(),'authoredBrowInnerUpMm':(authored*1000).tolist(),'poses':pose_points})
    candidates=np.flatnonzero((delta[:,1]>0)&(p[:,2]>.06))
    errors=[];depths=[];unmatched=[]
    for begin in range(0,len(candidates),64):
        selected=candidates[begin:begin+64];d=p[selected,None,:2]-a
        u=np.zeros(d.shape[:2]);w=u.copy()
        u[:,valid]=(d[:,valid,0]*ac[valid,1]-d[:,valid,1]*ac[valid,0])/det[valid]
        w[:,valid]=(ab[valid,0]*d[:,valid,1]-ab[valid,1]*d[:,valid,0])/det[valid]
        inside=valid&(u>=-2e-6)&(w>=-2e-6)&(u+w<=1.000002)
        z=tri[:,0,2]+u*(tri[:,1,2]-tri[:,0,2])+w*(tri[:,2,2]-tri[:,0,2])
        diff=abs(z-p[selected,None,2]);diff[~inside]=np.inf;choice=diff.argmin(1);ri=np.arange(len(selected))
        finite=np.isfinite(diff[ri,choice]);unmatched.extend(selected[~finite].tolist())
        use=choice[finite];u2=u[ri[finite],use];w2=w[ri[finite],use]
        predicted=field[ids[use,0]]*(1-u2-w2)+field[ids[use,1]]*u2+field[ids[use,2]]*w2
        errors.extend(abs(predicted-delta[selected[finite],1]));depths.extend(diff[ri[finite],use])
    result={'modelSha256':data['modelSha256'],'poses':poses,'trackedEyebrowPoints':brow_points,'carrierCrossCheck':{'scope':'Declared mask on original carrier, independently matched by XY barycentric coordinates and neutral depth',
            'independentMaskDeclaration':{'centerY':center_y,'rx':rx,'ry':ry,'fadeY':[fade_low,fade_high],'peak':.008},
            'samples':len(errors),'unmatched':unmatched,'maxDepthReconstructionErrorMeters':max(depths),'maxDeltaReconstructionErrorMeters':max(errors)},
            'scope':'Projected eyelid polygon and sampled front visibility; no live detector calibration or artistic acceptance.'}
    output=Path(out);assert not output.exists();output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,indent=2),'utf-8');print(json.dumps(result))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--model',required=True);p.add_argument('--carrier',required=True);p.add_argument('--out',required=True)
    p.add_argument('--center-y',type=float,default=.037);p.add_argument('--rx',type=float,default=.032);p.add_argument('--ry',type=float,default=.030)
    p.add_argument('--fade-low',type=float,default=.026);p.add_argument('--fade-high',type=float,default=.034)
    a=p.parse_args();diagnose(a.model,a.carrier,a.out,a.center_y,a.rx,a.ry,a.fade_low,a.fade_high)
