"""Curved eyelid surface candidate, retaining the actual source texture and all controls.

This changes mesh geometry/vertex colours, not raster imagery. Painted source eyes
are preserved and covered on blink. Visual inspection is required before acceptance.
"""
from pathlib import Path
import argparse,hashlib,json
import numpy as np
from tripo_head_adapter import Glb,write_glb

def author(source,landmarks,projection,out):
    source=Path(source);out=Path(out);assert not out.exists()
    g=Glb(source/'character.glb');mesh=g.doc['meshes'][0];pr=mesh['primitives'][0];pivot=np.array(g.doc['nodes'][1]['translation'])
    p=g.read(pr['attributes']['POSITION']).astype(float)+pivot;n=g.read(pr['attributes']['NORMAL']).astype(float)
    uv=g.read(pr['attributes']['TEXCOORD_0']).astype(float);colour=g.read(pr['attributes']['COLOR_0']).astype(float)
    ids=g.read(pr['indices']).reshape(-1,3);original=len(p)
    shapes={name:g.read(target['POSITION']).astype(float) for name,target in zip(mesh['extras']['targetNames'],pr['targets'])}
    lm=json.loads(Path(landmarks).read_text('utf-8'));cam=json.loads(Path(projection).read_text('utf-8'))
    pts=np.array(lm['landmarks'])[:,:2];scale=cam['orthographic_scale'];center=cam['views'][0]['center']
    pts[:,0]=(pts[:,0]-.5)*scale+center[0];pts[:,1]=(.5-pts[:,1])*scale+center[2]
    # Front-most barycentric ray hit: lids follow the observed curved surface, not a flat rectangle.
    v=p[ids];a=v[:,0,:2];b=v[:,1,:2]-a;c=v[:,2,:2]-a;det=b[:,0]*c[:,1]-b[:,1]*c[:,0]
    good=abs(det)>1e-12
    def sample(x,y):
        q=np.array([x,y])-a;s=np.zeros(len(v));t=s.copy()
        s[good]=(q[good,0]*c[good,1]-q[good,1]*c[good,0])/det[good]
        t[good]=(b[good,0]*q[good,1]-b[good,1]*q[good,0])/det[good]
        hit=good&(s>=-1e-6)&(t>=-1e-6)&(s+t<=1.000001)
        candidates=np.flatnonzero(hit);assert len(candidates)>0,(x,y)
        z=v[:,0,2]*(1-s-t)+v[:,1,2]*s+v[:,2,2]*t
        face=candidates[np.argmax(z[candidates])];weights=np.array([1-s[face]-t[face],s[face],t[face]])
        return float(z[face]),weights@uv[ids[face]]
    image=g.image(0);pixel=np.unravel_index(image.min(axis=2).argmax(),image.shape[:2])
    white_uv=np.array([(pixel[1]+.5)/image.shape[1],(pixel[0]+.5)/image.shape[0]])
    white=g.sample_base_colour(white_uv[None,:],pr['material'])[0,:3];assert white.min()>.5
    source_rgb=g.sample_base_colour(uv,pr['material'])[:,:3]
    details=[]
    specs=[('Left',[263,387,386,385,384,398,362],[263,373,374,380,381,382,362]),('Right',[33,160,159,158,157,173,133],[33,144,145,153,154,155,133])]
    for side,upper_ids,lower_ids in specs:
        upper=pts[upper_ids];lower=pts[lower_ids];upper=upper[np.argsort(upper[:,0])];lower=lower[np.argsort(lower[:,0])]
        left=max(upper[:,0].min(),lower[:,0].min())-.0007;right=min(upper[:,0].max(),lower[:,0].max())+.0007
        xs=np.linspace(left,right,33);u=(xs-left)/(right-left);taper=np.sin(np.pi*u)
        top=np.interp(xs,upper[:,0],upper[:,1])+.0007*taper
        bottom=np.interp(xs,lower[:,0],lower[:,1])-.0007*taper
        # Closed seam retains the almond contour and lies slightly below the central open-eye line.
        seam=bottom+(top-bottom)*.35;rows=3;newp=[];newc=[];delta=[];newfaces=[]
        for lid in [0,1]:
            for row in range(rows):
                fraction=row/(rows-1)
                for i,x in enumerate(xs):
                    edge=top[i] if lid==0 else bottom[i];destination=edge+(seam[i]-edge)*fraction
                    rest=edge+( -.00002 if lid==0 else .00002)*fraction*taper[i]
                    rest_z,_=sample(x,rest);dest_z,_=sample(x,destination)
                    # Source skin texels sampled outside the eye opening; store colours so UV islands cannot stripe the patch.
                    eye_mid=(top[i]+bottom[i])*.5
                    skin_mask=(abs(p[:original,0]-x)<.014)&(p[:original,1]<eye_mid-.006)&(p[:original,1]>eye_mid-.025)&(p[:original,2]>.035)
                    skin_mask&=(source_rgb[:original].min(axis=1)>.10)&(source_rgb[:original,0]>source_rgb[:original,1]*1.03)&(source_rgb[:original,1]>source_rgb[:original,2]*.98)
                    skin_ids=np.flatnonzero(skin_mask);assert len(skin_ids)>0,'No observed cheek skin colour'
                    distance=(p[skin_ids,0]-x)**2+(p[skin_ids,1]-eye_mid+.012)**2
                    nearest=skin_ids[np.argsort(distance)[:8]];skin=source_rgb[nearest].mean(axis=0)
                    # Clearance covers the convex painted eye between ray samples; it vanishes at rest.
                    newp.append([x,rest,rest_z+.00035]);delta.append([0,destination-rest,dest_z-rest_z+.0015*fraction*taper[i]])
                    newc.append([*np.clip(skin/white,0,1),1])
            start=len(p)+lid*rows*len(xs)
            for row in range(rows-1):
                for i in range(len(xs)-1):
                    aa=start+row*len(xs)+i;bb=aa+1;cc=aa+len(xs);dd=cc+1
                    newfaces.extend([[aa,cc,bb],[bb,cc,dd]] if lid==0 else [[aa,bb,cc],[bb,dd,cc]])
        # A narrow eyelid crease distinguishes closed skin from a pale open eyeball.
        # It starts with zero area along the upper contour and opens only with blink.
        crease_start=len(p)+len(newp)
        for row in range(2):
            for i,x in enumerate(xs):
                rest=top[i];destination=seam[i]+(-1 if row==0 else 1)*.00013*taper[i]
                rest_z,_=sample(x,rest);dest_z,_=sample(x,destination)
                newp.append([x,rest,rest_z+.00035]);delta.append([0,destination-rest,dest_z-rest_z+.002*taper[i]])
                newc.append([*(np.array([.022,.014,.010])/white),1])
        for i in range(len(xs)-1):
            aa=crease_start+i;bb=aa+1;cc=aa+len(xs);dd=cc+1;newfaces.extend([[aa,bb,cc],[bb,dd,cc]])
        newp=np.array(newp);delta=np.array(delta);offset=len(p);added=len(newp)
        p=np.r_[p,newp];n=np.r_[n,np.tile([0,0,1],(added,1))];uv=np.r_[uv,np.tile(white_uv,(added,1))]
        colour=np.r_[colour,newc];ids=np.r_[ids,newfaces]
        for name in shapes:shapes[name]=np.r_[shapes[name],np.zeros((added,3))]
        # Stop collapsing the painted source eye triangles; the new curved lid covers them instead.
        shapes['eyeBlink'+side][:original]=0;shapes['eyeSquint'+side][:original]=0
        shapes['eyeBlink'+side][offset:]=delta;shapes['eyeSquint'+side][offset:]=delta*.52
        details.append({'side':side,'vertices':added,'triangles':len(newfaces),'left':float(left),'right':float(right),'rows':rows,'columns':len(xs)})
    out.mkdir();view=g.doc['bufferViews'][g.doc['images'][0]['bufferView']];start=g.start+view.get('byteOffset',0)
    atlas=g.raw[start:start+view['byteLength']]
    target=out/'character.glb';write_glb(target,p-pivot,n,colour,ids,head_pivot=pivot,morphs=shapes,uv=uv,atlas_png=atlas)
    manifest=json.loads((source/'avatar.json').read_text('utf-8'));manifest['id']+='-curved-lids';manifest['displayName']='杰洛特方向 · 曲面眼睑候选';manifest['modelSha256']=hashlib.sha256(target.read_bytes()).hexdigest()
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    report={'source':str(source),'source_sha256':hashlib.sha256(g.raw).hexdigest(),'model_sha256':manifest['modelSha256'],'vertices':len(p),'triangles':len(ids),'eyelids':details,'artistAccepted':False,'scope':'Curved source-surface covers; painted eyes remain, independent eyeballs and anatomical retopology not provided. Static and combination previews required.'}
    (out/'eyelid-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--source',required=True);parser.add_argument('--landmarks',required=True);parser.add_argument('--projection',required=True);parser.add_argument('--out',required=True)
    a=parser.parse_args();author(a.source,a.landmarks,a.projection,a.out)
