"""Rounded mouth seam and oral-arch artwork candidate, preserving the original atlas.

The source artwork and eyeballs are NOT artist validated. Channel coverage is a
software property, not a claim of accurate, collision-free expressions.
"""
from pathlib import Path
import argparse,hashlib,json,re
import numpy as np
from mouth_topology import split_mouth
from tripo_head_adapter import Glb,write_glb
from head_surface_topology import FrontSurface

def smoothstep(a,b,value):
    q=np.clip((value-a)/(b-a),0,1);return q*q*(3-2*q)

def author(source,landmarks,projection,out,avatar_id,display_name,taper_mouth=False):
    source=Path(source);out=Path(out);out.mkdir(exist_ok=False)
    g=Glb(source);pr=g.doc['meshes'][0]['primitives'][0];pivot=np.array(g.doc['nodes'][1]['translation'])
    p=g.read(pr['attributes']['POSITION']).astype(float)+pivot;n=g.read(pr['attributes']['NORMAL']).astype(float)
    uv=g.read(pr['attributes']['TEXCOORD_0']).astype(float);tri=g.read(pr['indices']).reshape(-1,3)
    lm=json.loads(Path(landmarks).read_text('utf-8'));assert lm['faces']==1 and len(lm['landmarks'])==478
    camera=json.loads(Path(projection).read_text('utf-8'));assert camera['views'][0]['name']=='front'
    scale=camera['orthographic_scale'];center=camera['views'][0]['center']
    pts=np.array(lm['landmarks'])[:,:2];pts[:,0]=(pts[:,0]-.5)*scale+center[0];pts[:,1]=(.5-pts[:,1])*scale+center[2]
    mouth=(pts[13]+pts[14])/2;corners=pts[[61,291]];corners=corners[np.argsort(corners[:,0])]
    left,right=corners[:,0];width=right-left;assert .02<width<.10
    def line_y(x):return np.interp(x,[left,mouth[0],right],[corners[0,1],mouth[1],corners[1,1]])
    nearby=(abs(p[:,0]-mouth[0])<.012)&(abs(p[:,1]-mouth[1])<.008)
    assert nearby.sum()>10
    lip_z=float(p[nearby,2].max());threshold=.020
    # Dense seam segments let the corner profile form a rounded lip opening.
    knots=np.linspace(left,right,27)[1:-1] if taper_mouth else [mouth[0]]
    p,n,uv,tri,side,split_count=split_mouth(p,n,uv,tri,left=left,right=right,line_y=line_y,front_z=threshold,knots=knots)
    assert split_count>0,'No frontal lip triangles split; inspect landmarks before accepting'
    source_count=len(p);colours=np.ones((len(p),4));interiors=[];oral_attachment=[]
    source_surface=FrontSurface(np.c_[p,n,uv],tri)
    image=g.image(0);white=np.unravel_index(np.argmax(image.min(axis=2)),image.shape[:2]);white_uv=np.array([(white[1]+.5)/image.shape[1],(white[0]+.5)/image.shape[0]])
    def surface(points,faces,colour,kind,moving=False):
        nonlocal p,n,uv,tri,colours,side
        offset=len(p);points=np.array(points);p=np.concatenate([p,points]);n=np.concatenate([n,np.tile([0,0,1],(len(points),1))])
        uv=np.concatenate([uv,np.tile(white_uv,(len(points),1))]);colours=np.concatenate([colours,np.tile([*colour,1],(len(points),1))])
        tri=np.concatenate([tri,np.array(faces)+offset]);side=np.r_[side,np.zeros(len(points),int)]
        oral_attachment.extend([int(moving)]*len(points));interiors.append({'kind':kind,'vertices':len(points),'jawAttachment':moving})
    # A recessed dark oral surface, hidden by the closed neutral lip seam.
    oral_z=lip_z-.016;ring=48;points=[[mouth[0],mouth[1]-.010,oral_z-.008]]
    for i in range(ring):
        a=2*np.pi*i/ring;x0=mouth[0]+width*.47*np.cos(a);y0=mouth[1]-.010+.020*np.sin(a)
        z0=min(oral_z,source_surface.sample(x0,y0)[2]-.005);points.append([x0,y0,z0])
    surface(points,[[0,i+1,(i+1)%ring+1] for i in range(ring)],[.022,.006,.009],'recessed-oral-surface')
    def rectangle(cx,cy,z,w,h,colour,kind):
        surface([[cx-w/2,cy-h/2,z],[cx+w/2,cy-h/2,z],[cx+w/2,cy+h/2,z],[cx-w/2,cy+h/2,z]],[[0,1,2],[0,2,3]],colour,kind)
    # Ten restrained tooth segments follow a shallow 3D arch, behind the lip surface.
    for kind,cy,span,h,moving in [('upper-teeth',mouth[1]-.002,width*.67,.0034,False),('lower-teeth',mouth[1]-.012,width*.60,.0025,True)]:
        edges=np.linspace(mouth[0]-span/2,mouth[0]+span/2,11)
        for j in range(10):
            xx=np.array([edges[j]+.00010,edges[j+1]-.00010]);ys=cy+.0012*((xx-mouth[0])/(span/2))**2
            zs=np.array([min(lip_z-.006,source_surface.sample(float(x0),float(y0))[2]-.005) for x0,y0 in zip(xx,ys)])
            surface([[xx[0],ys[0]-h/2,zs[0]],[xx[1],ys[1]-h/2,zs[1]],[xx[1],ys[1]+h/2,zs[1]],[xx[0],ys[0]+h/2,zs[0]]],[[0,1,2],[0,2,3]],[.45,.42,.36],kind,moving)
    tongue=[[mouth[0],mouth[1]-.017,oral_z+.003]]
    for j in range(24):
        angle=j*2*np.pi/24;tongue.append([mouth[0]+width*.25*np.cos(angle),mouth[1]-.017+.0035*np.sin(angle),oral_z+.002])
    surface(tongue,[[0,j+1,(j+1)%24+1] for j in range(24)],[.16,.035,.05],'tongue',True)
    front=smoothstep(.006,.035,p[:,2]);x,y,z=p.T
    def region(c,rx,ry):
        r=((x-c[0])/rx)**2+((y-c[1])/ry)**2
        result=(1-smoothstep(.12,1,r))*front
        result[source_count:]=0
        return result
    def translation(mask,dx=0,dy=0,dz=0):return mask[:,None]*np.array([dx,dy,dz])
    shapes={};eyes={}
    for name,outer,inner,upper,lower,iris in [('Left',263,362,386,374,473),('Right',33,133,159,145,468)]:
        c=(pts[inner]+pts[outer])/2;ew=abs(pts[inner,0]-pts[outer,0]);eh=abs(pts[upper,1]-pts[lower,1]);mid=(pts[upper,1]+pts[lower,1])/2
        assert ew>.01 and eh>.001
        mask=region([c[0],mid],ew*.64,max(eh*1.6,.004));blink=np.zeros_like(p);blink[:,1]=-(y-mid)*.96*mask
        shapes['eyeBlink'+name]=blink;shapes['eyeSquint'+name]=blink*.52;shapes['eyeWide'+name]=-blink*.40
        gaze=region(pts[iris],ew*.56,max(eh*1.1,.0035));distance=min(.0025,ew*.12)
        for direction,axis,sign in [('Down',1,-1),('Up',1,1),('In',0,-1 if name=='Left' else 1),('Out',0,1 if name=='Left' else -1)]:
            d=np.zeros_like(p);d[:,axis]=sign*distance*gaze;shapes['eyeLook'+direction+name]=d
        brow=(pts[336]+pts[300])/2 if name=='Left' else (pts[107]+pts[70])/2
        bm=region(brow,.029,.015);shapes['browDown'+name]=translation(bm,dy=-.004)
        outer_brow=pts[300] if name=='Left' else pts[70]
        shapes['browOuterUp'+name]=translation(region(outer_brow,.020,.018),dy=.005)
        cheek=[c[0],mid-.025];shapes['cheekSquint'+name]=translation(region(cheek,.027,.021),dy=.003,dz=.002)
        eyes[name]={'center':c.tolist(),'width':float(ew),'height':float(eh)}
    brow_center=(pts[107]+pts[336])/2
    shapes['browInnerUp']=translation(region(brow_center,.026,.015),dy=.006)
    cheeks=np.maximum(region([eyes['Left']['center'][0],mouth[1]+.014],.030,.026),region([eyes['Right']['center'][0],mouth[1]+.014],.030,.026))
    shapes['cheekPuff']=translation(cheeks,dz=.006)
    signed=y-line_y(x);in_mouth=1-smoothstep(width*.42,width*.62,abs(x-mouth[0]))
    # Lower beard/neck triangles span several depth layers. A depth-dependent
    # carrier folds one triangle while its neighbor moves rigidly. Use one Y
    # carrier across those layers; the lip seam has its own local corner weight.
    jaw=smoothstep(mouth[1]+.005,mouth[1]-.015,y)
    seam_zone=(1-smoothstep(.003,.007,abs(signed)))*in_mouth*front
    below=(signed<0).astype(float);below[(side==1)&(abs(signed)<1e-5)]=0;below[(side==-1)&(abs(signed)<1e-5)]=1
    taper=np.sqrt(np.maximum(0,1-((x-mouth[0])/(width*.5))**2)) if taper_mouth else np.ones(len(p))
    jaw=jaw*(1-seam_zone)+below*seam_zone*taper
    # A split polygon includes vertices far below the lip. Restrict the special
    # corner profile to the seam itself; overriding the whole polygon tears beards.
    on_seam=abs(signed)<1e-6
    jaw[(side==-1)&on_seam]=taper[(side==-1)&on_seam];jaw[(side==1)&on_seam]=0
    # Oral backing stays fixed; lower teeth/tongue move with the jaw.
    jaw[source_count:]=oral_attachment
    hinge=np.array([mouth[0],mouth[1]+.015,.012]);q=p-hinge;c=np.cos(np.deg2rad(15));s=np.sin(np.deg2rad(15))
    rotated=q.copy();rotated[:,1]=c*q[:,1]-s*q[:,2];rotated[:,2]=s*q[:,1]+c*q[:,2]
    shapes['jawOpen']=(rotated-q)*jaw[:,None]
    shapes['jawLeft']=translation(jaw,dx=.004);shapes['jawRight']=translation(jaw,dx=-.004);shapes['jawForward']=translation(jaw,dz=.005)
    lips=region(mouth,width*.70,.014);upper=lips*smoothstep(-.002,.003,signed);lower=lips*(1-smoothstep(-.003,.002,signed))
    closed=np.zeros_like(p);closed[:,1]=-signed*lips*.65;shapes['mouthClose']=closed
    for name,corner,sign in [('Left',corners[1],1),('Right',corners[0],-1)]:
        corner_mask=region(corner,.019,.020);half=smoothstep(-.004,.010,sign*(x-mouth[0]))
        shapes['mouthSmile'+name]=translation(corner_mask,dx=.002*sign,dy=.003)
        shapes['mouthFrown'+name]=translation(corner_mask,dy=-.005)
        shapes['mouthDimple'+name]=translation(corner_mask,dx=.002*sign,dz=-.002)
        shapes['mouthStretch'+name]=translation(corner_mask,dx=.005*sign)
        shapes['mouthLowerDown'+name]=translation(lower*half,dy=-.004)
        shapes['mouthUpperUp'+name]=translation(upper*half,dy=.004)
        shapes['mouthPress'+name]=closed*half[:,None]*.8+translation(lips*half,dz=-.001)
        nose=pts[327] if name=='Left' else pts[98]
        shapes['noseSneer'+name]=translation(region(nose,.016,.020),dy=.003,dz=.001)
    shapes['mouthLeft']=translation(lips,dx=.004);shapes['mouthRight']=translation(lips,dx=-.004)
    pucker=np.zeros_like(p);pucker[:,0]=-(x-mouth[0])*.25*lips;pucker[:,2]=.008*lips;shapes['mouthPucker']=pucker
    funnel=pucker*.55;funnel[:,1]=signed*.45*lips;shapes['mouthFunnel']=funnel
    shapes['mouthRollLower']=translation(lower,dy=.002,dz=-.003)
    shapes['mouthRollUpper']=translation(upper,dy=-.002,dz=-.003)
    shapes['mouthShrugLower']=translation(lower,dy=.004,dz=.002)
    shapes['mouthShrugUpper']=translation(upper,dy=.003,dz=.001)
    schema=(Path(__file__).resolve().parents[1]/'app/src/main/java/com/mirror/bench/BlendshapeSchema.java').read_text('utf-8')
    section=schema.split('private static final String[] NAMES = {')[1].split('};')[0]
    names=re.findall(r'"([^"]+)"',section)[1:];assert set(shapes)==set(names),(set(names)-set(shapes),set(shapes)-set(names))
    shapes={name:shapes[name] for name in names};shapes['correctiveJawOpenMouthClose']=-shapes['jawOpen']
    for name,d in shapes.items():
        assert np.isfinite(d).all() and np.linalg.norm(d,axis=1).max()>1e-5,name
    view=g.doc['bufferViews'][g.doc['images'][0]['bufferView']];off=g.start+view.get('byteOffset',0);atlas=g.raw[off:off+view['byteLength']]
    target=out/'character.glb';write_glb(target,p-pivot,n,colours,tri,head_pivot=pivot,morphs=shapes,uv=uv,atlas_png=atlas)
    manifest={'schemaVersion':2,'requiredRigFeatures':['product-correctives-v1'],'id':avatar_id,'displayName':display_name,'model':'character.glb','modelSha256':hashlib.sha256(target.read_bytes()).hexdigest(),'inputSchema':'mediapipe-face-blendshapes-v1',
      'coordinates':{'up':'+Y','forward':'+Z','subjectLeft':'+X','units':'meters','rootScale':1,'headPivot':pivot.tolist()},'normalPolicy':'recompute-deformed','materialProfile':'mirror-lit-v1',
      'rig':{'headNode':'Head','jawMode':'morph','jawAttachmentNode':'JawAttachments','jawAxis':[1,0,0],'jawOpenDegrees':15,'jawLateralMeters':.004,'jawForwardMeters':.005,'gazeMode':'morph'},
      'bindings':[{'source':name,'mesh':'Face','target':name,'gain':1} for name in names],
      'derivedBindings':[{'operation':'product','sources':['jawOpen','mouthClose'],'mesh':'Face','target':'correctiveJawOpenMouthClose','gain':1}]}
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    report={'source':str(source),'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'modelSha256':manifest['modelSha256'],'vertices':len(p),'triangles':len(tri),'morphs':list(shapes),'directSources':len(names),'splitMouthTriangles':split_count,'taperedMouthCorners':taper_mouth,'mouthSurfaceDepth':lip_z,'interiors':interiors,'eyes':eyes,'sourceCoverageComplete':True,'artistAccepted':False,
      'shapeMaxDisplacementMeters':{k:float(np.linalg.norm(v,axis=1).max()) for k,v in shapes.items()},
      'scope':'Experimental landmark-guided 51 direct controls plus jawOpen*mouthClose corrective, split mouth seam and simple oral surfaces. Not Tripo automatic facial rig, artist-approved likeness, anatomically correct eyeballs, or collision-free combination proof.'}
    (out/'authoring-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    np.savez_compressed(out/'authoring-geometry.npz',positions=p,indices=tri,seam_side=side,mouth_xy=mouth,mouth_width=width)
    print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('--source',required=True);a.add_argument('--landmarks',required=True);a.add_argument('--projection',required=True);a.add_argument('--out',required=True);a.add_argument('--id',required=True);a.add_argument('--name',required=True);a.add_argument('--taper-mouth',action='store_true');v=a.parse_args();author(v.source,v.landmarks,v.projection,v.out,v.id,v.name,v.taper_mouth)
