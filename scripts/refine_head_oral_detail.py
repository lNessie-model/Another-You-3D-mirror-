"""Reusable low-budget dental and oral replacement; never changes face/eye targets."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from head_glb_edit import GlbEdit
from refine_head_dentition import normals
from refine_jaw_close import lip_loops


def oriented(points, triangles, center):
    points=np.asarray(points);triangles=np.asarray(triangles)
    cross=np.cross(points[triangles[:,1]]-points[triangles[:,0]],points[triangles[:,2]]-points[triangles[:,0]])
    flip=np.einsum('ij,ij->i',cross,points[triangles].mean(axis=1)-center)<0
    triangles[flip]=triangles[flip][:,[0,2,1]]
    return points,triangles


def tooth(center, width, height, depth, tangent, forward, kind, lower):
    """Five profile levels, broad incisor edges and a distinct canine cusp."""
    center=np.asarray(center);tangent=np.asarray(tangent);forward=np.asarray(forward)
    points=[];triangles=[];segments=8;levels=5
    # In upper teeth the biting edge is the negative Y end; in lower teeth it is +Y.
    sign=-1 if lower else 1
    profile={'incisor':(.97,1,.99,.85,.58),'lateral':(.91,1,.97,.82,.57),
             'canine':(.48,.90,1,.88,.60),'premolar':(.85,1,1,.89,.60),'molar':(.97,1,1,.90,.62)}[kind]
    ylevels=np.array([-.44,-.24,0,.25,.43])*height*sign
    for row,(y,extent) in enumerate(zip(ylevels,profile)):
        for column in range(segments):
            theta=2*np.pi*column/segments;c,s=np.cos(theta),np.sin(theta)
            # A rounded rectangular front/side section, rather than the old corn shape.
            lateral=np.sign(c)*abs(c)**.42*width*.5*extent
            front=np.sign(s)*abs(s)**.62*depth*.5
            if kind in ('incisor','lateral'):front*=.72+.28*row/(levels-1)
            points.append(center+tangent*lateral+forward*front+[0,y,0])
    # Broad cutting-edge cap for incisors, pointed cusp for canines.
    bite=len(points);points.append(center+[0,sign*(-.5 if kind=='canine' else -.46)*height,0])
    root=len(points);points.append(center+[0,sign*.5*height,0])
    for column in range(segments):
        nxt=(column+1)%segments
        triangles.extend([[bite,column,nxt],[root,(levels-1)*segments+nxt,(levels-1)*segments+column]])
        for row in range(levels-1):
            a=row*segments+column;b=row*segments+nxt;c=a+segments;d=b+segments
            triangles.extend([[a,b,c],[b,d,c]])
    return oriented(points,triangles,center)


def dental_arch(cx, span, lip_z, seam_y, lower):
    points=[];triangles=[];rgb=[];crowns=[]
    widths=np.array([4.5,4.2,4.8,5.6,6.5,6.5,5.6,4.8,4.2,4.5]);widths*=span/widths.sum()
    edges=cx-span/2+np.r_[0,np.cumsum(widths)]
    kinds=['molar','premolar','canine','lateral','incisor','incisor','lateral','canine','premolar','molar']
    def curve(x):return lip_z-.0126-.008*((x-cx)/(span/2))**2-(.0008 if lower else 0)
    for i,(width,kind) in enumerate(zip(widths,kinds)):
        x=(edges[i]+edges[i+1])/2;slope=-.016*(x-cx)/(span/2)**2
        tangent=np.array([1,0,slope]);tangent/=np.linalg.norm(tangent)
        forward=np.array([-slope,0,1]);forward/=np.linalg.norm(forward)
        height={'incisor':.0061,'lateral':.0055,'canine':.0068,'premolar':.0048,'molar':.0043}[kind]*(.88 if lower else 1)
        # Retain the proven source bite gap and depth so teeth stay behind the lips.
        y=seam_y(x)-(.0078+height/2 if lower else .0007+height/2)
        p,t=tooth([x,y,curve(x)],width*.965,height,.0033,tangent,forward,kind,lower)
        start=len(points);triangles.extend(t+start);points.extend(p)
        for q in p:
            neck=max(0,min(1,(q[1]-y)/height+.5))
            tone=.94+.07*(1-neck);rgb.append(np.array([.37,.328,.260])*tone)
        crowns.append({'kind':kind,'start':start,'count':len(p),'height':height,'width':width*.965})
    start=len(points);columns=13;sides=6
    for x in np.linspace(cx-span*.515,cx+span*.515,columns):
        center=np.array([x,seam_y(x)+(-.0131 if lower else .0009),curve(x)-.0008])
        for column in range(sides):
            theta=2*np.pi*column/sides;points.append(center+[0,.0019*np.cos(theta),.0023*np.sin(theta)])
            rgb.append([.068,.020,.029])
    for row in range(columns-1):
        for column in range(sides):
            a=start+row*sides+column;b=start+row*sides+(column+1)%sides;c=a+sides;d=b+sides
            triangles.extend([[a,b,c],[b,d,c]])
    for row in (0,columns-1):
        cap=len(points);points.append(np.mean(points[start+row*sides:start+(row+1)*sides],axis=0));rgb.append([.068,.020,.029])
        for column in range(sides):
            a=start+row*sides+column;b=start+row*sides+(column+1)%sides
            triangles.append([cap,a,b] if row else [cap,b,a])
    return np.array(points),np.array(triangles),np.array(rgb),crowns


def tongue_mesh(cx,cy,front_z,back_z):
    """Solid tongue: broad rounded tip and a deep descending root inside the floor."""
    points=[];rgb=[];triangles=[];segments=10;rows=7
    for row in range(rows):
        t=row/(rows-1);z=back_z-.003+(front_z-back_z+.003)*t
        y=cy-.015+.007*np.sin(t*np.pi*.70)
        width=.003+.006*np.sin(np.pi*(t*.82+.08))
        height=.0033 if row<3 else .0027
        for column in range(segments):
            theta=2*np.pi*column/segments
            x=cx+width*np.cos(theta);py=y+height*np.sin(theta)
            # A small central groove on the upper surface; no extra vertices or draw.
            if np.sin(theta)>0:py-=.00030*np.exp(-((x-cx)/.0015)**2)
            points.append([x,py,z]);shade=.32+.68*t
            rgb.append(np.array([.033,.0085,.013])*shade)
    for row in range(rows-1):
        for column in range(segments):
            a=row*segments+column;b=row*segments+(column+1)%segments;c=a+segments;d=b+segments
            triangles.extend([[a,b,c],[b,d,c]])
    for row in (0,rows-1):
        cap=len(points);points.append(np.mean(points[row*segments:(row+1)*segments],axis=0));rgb.append([.012,.003,.0045] if row==0 else [.033,.0085,.013])
        for column in range(segments):
            a=row*segments+column;b=row*segments+(column+1)%segments
            triangles.append([cap,a,b] if row==0 else [cap,b,a])
    return oriented(points,triangles,np.mean(points,axis=0))+ (np.asarray(rgb),)


def author(source,landmark_report,out):
    source=Path(source);out=Path(out);assert not out.exists()
    spec=json.loads(Path(landmark_report).read_text('utf-8'));cx,cy=spec['mouth_center'];half=spec['half_width']
    edit=GlbEdit(source/'character.glb');g=edit.source;doc=edit.doc
    face=g.doc['meshes'][0];names=face['extras']['targetNames'];pivot=np.asarray(doc['nodes'][1]['translation'])
    ring=face['primitives'][1];rp=g.read(ring['attributes']['POSITION']).astype(float)+pivot
    rd={name:g.read(t['POSITION']).astype(float) for name,t in zip(names,ring['targets'])}
    inner,_,_,_=lip_loops(rp,g.read(ring['indices']).reshape(-1,3),cy)
    opened=rp+rd['jawOpen'];theta=np.mod(np.arctan2((opened[inner,1]-cy+.0095)/.010,(opened[inner,0]-cx)/half),2*np.pi)
    order=np.argsort(theta);inner=inner[order];theta=theta[order]
    upper=inner[rd['jawOpen'][inner,1]>=-1e-7];upper=upper[np.argsort(rp[upper,0])]
    def seam_y(x):return float(np.interp(x,rp[upper,0],rp[upper,1]+rd['mouthClose'][upper,1]))
    upper_node=next(n for n in doc['nodes'] if n['name']=='UpperDentition')
    old_upper=g.doc['meshes'][upper_node['mesh']]['primitives'][0]
    old_up=g.read(old_upper['attributes']['POSITION']).astype(float)+pivot
    # Source crown forward extremity = lip reference - 12.3 mm + 1.7 mm.
    lip_z=float(old_up[:,2].max()+.0106);back_z=lip_z-.029
    front=rp[inner].copy();back=np.c_[cx+half*.74*np.cos(theta),cy-.002+.013*np.sin(theta),np.full(96,back_z)]
    middle=front*.45+back*.55;center=np.array([cx,cy-.004,back_z])
    points=np.r_[front,middle,back,center[None,:]];ids=[]
    for row in range(2):
        for column in range(96):
            nxt=(column+1)%96;a=row*96+column;b=row*96+nxt;c=a+96;d=b+96
            ids.extend([[a,b,c],[b,d,c]])
    for column in range(96):ids.append([288,192+column,192+(column+1)%96])
    tongue,tt,trgb=tongue_mesh(cx,cy,lip_z-.0135,back_z)
    tongue_start=len(points);points=np.r_[points,tongue];ids=np.r_[np.array(ids),tt+tongue_start]
    tongue_floor_weight=np.r_[np.repeat(np.linspace(1,.35,7),10),1,.35]
    # The root penetrates the rear wall and the bottom floor; it is not a thin floating disk.
    hinge=pivot+np.array(next(n for n in doc['nodes'] if n['name']=='JawAttachments')['translation'])
    angle=np.deg2rad(15);rotation=np.array([[1,0,0],[0,np.cos(angle),-np.sin(angle)],[0,np.sin(angle),np.cos(angle)]])
    floor_weight=(1-np.sin(theta))*.5
    shapes=[]
    for name in names:
        r=rd[name][inner];rear=np.zeros_like(back);td=np.zeros_like(tongue);cd=np.zeros((1,3))
        if name=='jawOpen':
            rear=((back-hinge)@rotation.T-(back-hinge))*floor_weight[:,None]
            td=((tongue-hinge)@rotation.T-(tongue-hinge))*tongue_floor_weight[:,None];cd=((center-hinge)@rotation.T-(center-hinge))[None,:]*.5
        elif name in ('jawLeft','jawRight','jawForward'):
            v=np.array([.004 if name=='jawLeft' else -.004 if name=='jawRight' else 0,0,.005 if name=='jawForward' else 0])
            rear=np.tile(v,(96,1))*floor_weight[:,None];td=np.tile(v,(len(tongue),1))*tongue_floor_weight[:,None];cd=v[None,:]*.5
        shapes.append(np.r_[r,r*.45+rear*.55,rear,cd,td])
    oldoral=face['primitives'][2];uv=g.read(oldoral['attributes']['TEXCOORD_0'])[0]
    white=g.sample_base_colour(uv[None,:],oldoral['material'])[0,:3]
    desired=np.r_[np.tile([.007,.0018,.0026],(96,1)),np.tile([.0045,.0012,.0018],(96,1)),np.tile([.002,.00055,.0008],(96,1)),[[.0015,.0004,.0006]],trgb]
    attrs={'POSITION':points-pivot,'NORMAL':normals(points,ids),'TEXCOORD_0':np.tile(uv,(len(points),1)),
           'COLOR_0':np.c_[desired/white,np.ones(len(points))]}
    kinds={'POSITION':'VEC3','NORMAL':'VEC3','COLOR_0':'VEC4','TEXCOORD_0':'VEC2'}
    assert (attrs['COLOR_0']>=0).all() and (attrs['COLOR_0']<=1).all()
    doc['meshes'][0]['primitives'][2]={'mode':4,'material':oldoral['material'],
        'attributes':{k:edit.add(v,kinds[k]) for k,v in attrs.items()},'indices':edit.add(ids.reshape(-1),'SCALAR'),
        'targets':[{'POSITION':edit.add(delta,'VEC3')} for delta in shapes]}
    dental=[]
    for lower,name in ((False,'UpperDentition'),(True,'LowerDentition')):
        node=next(n for n in doc['nodes'] if n['name']==name);mi=node['mesh'];old=g.doc['meshes'][mi]['primitives'][0]
        p,t,rgb,crowns=dental_arch(cx,half*(1.52 if lower else 1.60),lip_z,seam_y,lower)
        dentalwhite=g.sample_base_colour(uv[None,:],old['material'])[0,:3]
        attrs={'POSITION':p-(hinge if lower else pivot),'NORMAL':normals(p,t),'TEXCOORD_0':np.tile(uv,(len(p),1)),
               'COLOR_0':np.c_[rgb/dentalwhite,np.ones(len(p))]}
        assert (attrs['COLOR_0']>=0).all() and (attrs['COLOR_0']<=1).all()
        doc['meshes'][mi]['primitives'][0]={'mode':4,'material':old['material'],'attributes':{k:edit.add(v,kinds[k]) for k,v in attrs.items()},'indices':edit.add(t.reshape(-1),'SCALAR')}
        dental.append({'node':name,'vertices':len(p),'triangles':len(t),'crowns':crowns})
    out.mkdir(parents=True);target=out/'character.glb';edit.write(target)
    manifest=json.loads((source/'avatar.json').read_text('utf-8'));manifest['id']+='-oral-detail';manifest['displayName']='杰洛特方向 · 成人牙型与深口腔候选';manifest['modelSha256']=hashlib.sha256(target.read_bytes()).hexdigest()
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    report={'source':str(source),'sourceModelSha256':hashlib.sha256(g.raw).hexdigest(),'modelSha256':manifest['modelSha256'],
            'arches':dental,'oralVertices':len(points),'tongueVertices':len(tongue),'lipAttachmentRingIndices':inner.tolist(),
            'mouthCenter':[cx,cy],'lipReferenceDepth':lip_z,'backWallDepth':back_z,'tongueRootDepth':float(tongue[:10,2].mean()),
            'sourceFaceEyeLipGeometryTargetsNodesMaterialsMapsUnchanged':True,'artistAccepted':False,
            'localProceduralTripoCreditsSpent':0,'tripoAttemptActualCredits':None,'successfulTripoTasks':0,
            'scope':'Procedural display dentition and recessed solid tongue, not a medically qualified dental model; Tripo reference request failed before a task ID was returned'}
    (out/'oral-detail-authoring.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(report,ensure_ascii=False))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--landmark-report',required=True);p.add_argument('--out',required=True)
    a=p.parse_args();author(a.source,a.landmark_report,a.out)
