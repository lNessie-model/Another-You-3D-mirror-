"""Correct uneven bite edges and dark atlas-dependent enamel; preserve the complete face/cavity."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from head_glb_edit import GlbEdit
from refine_head_dentition import normals
from refine_head_oral_detail import oriented
from refine_jaw_close import lip_loops


def tooth(center,width,height,depth,tangent,forward,kind,lower):
    center=np.asarray(center);tangent=np.asarray(tangent);forward=np.asarray(forward)
    points=[];triangles=[];segments=12;levels=4;sign=-1 if lower else 1
    profiles={'incisor':(.98,1,.985,.80),'lateral':(.955,1,.98,.79),
              'canine':(.92,1,.985,.80),'premolar':(.98,1,.99,.84),'molar':(.985,1,.99,.88)}
    ylevels=[-.49,-.24,.17,.43]
    for row,(y,extent) in enumerate(zip(ylevels,profiles[kind])):
        for column in range(segments):
            theta=2*np.pi*column/segments;c,s=np.cos(theta),np.sin(theta)
            x=np.sign(c)*abs(c)**.43*width*.5*extent
            z=np.sign(s)*abs(s)**.70*depth*.5*(.85+.15*row/(levels-1))
            edge_rounding=.00010*abs(c)**4 if row==0 else 0
            points.append(center+tangent*x+forward*z+[0,sign*(y*height+edge_rounding),0])
    bite=len(points);points.append(center+[0,sign*-.5*height,0])
    root=len(points);points.append(center+[0,sign*.5*height,0])
    for column in range(segments):
        nxt=(column+1)%segments
        triangles.extend([[bite,column,nxt],[root,(levels-1)*segments+nxt,(levels-1)*segments+column]])
        for row in range(levels-1):
            a=row*segments+column;b=row*segments+nxt;c=a+segments;d=b+segments
            triangles.extend([[a,b,c],[b,d,c]])
    return oriented(points,triangles,center)


def arch(cx,span,lip_z,seam_y,lower):
    points=[];triangles=[];rgb=[];crowns=[]
    widths=np.array([4.9,4.8,5.0,5.7,6.5,6.5,5.7,5.0,4.8,4.9]);widths*=span/widths.sum()
    edges=cx-span/2+np.r_[0,np.cumsum(widths)]
    kinds=['molar','premolar','canine','lateral','incisor','incisor','lateral','canine','premolar','molar']
    def curve(x):return lip_z-.0126-.008*((x-cx)/(span/2))**2-(.0008 if lower else 0)
    for i,(width,kind) in enumerate(zip(widths,kinds)):
        x=(edges[i]+edges[i+1])/2;q=(x-cx)/(span/2);slope=-.016*(x-cx)/(span/2)**2
        tangent=np.array([1,0,slope]);tangent/=np.linalg.norm(tangent)
        forward=np.array([-slope,0,1]);forward/=np.linalg.norm(forward)
        height={'incisor':.0057,'lateral':.0054,'canine':.0058,'premolar':.0052,'molar':.0050}[kind]*(.88 if lower else 1)
        # Earlier geometry fixed every tooth's neck, creating a 2.6 mm sawtooth bite.
        # This generator fixes the biting edge and puts height differences at the root.
        bite=seam_y(cx)+(-.0082 if lower else -.0063)+.00012*q*q
        if kind=='canine':bite+=.00006 if lower else -.00006
        if not lower:
            # Keep the crown root under the original proven gum/closed-lip line.
            # A raised right-canine root otherwise shows through the neutral seam.
            height=min(height,seam_y(x)-.00075-bite)
            assert height>.0042
        y=bite-height*.5 if lower else bite+height*.5
        effective_width=width*.985/min(1,max(.90,tangent[0]))
        p,t=tooth([x,y,curve(x)],effective_width,height,.0030,tangent,forward,kind,lower)
        start=len(points);points.extend(p);triangles.extend(t+start)
        for vertex in p:
            root_fraction=max(0,min(1,((vertex[1]-y)/height+.5) if not lower else .5-(vertex[1]-y)/height))
            # Linear warm white enamel, with a subtle warmer cervical region.
            colour=np.array([.76,.725,.660])*(1-.025*root_fraction)
            colour[2]-=.012*root_fraction;rgb.append(colour)
        crowns.append({'kind':kind,'start':start,'count':len(p),'height':height,'width':effective_width,
                       'biteY':bite,'centerX':x})
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


def author(source,landmark_report,out):
    source=Path(source);out=Path(out);assert not out.exists()
    spec=json.loads(Path(landmark_report).read_text('utf-8'));cx,cy=spec['mouth_center'];half=spec['half_width']
    edit=GlbEdit(source/'character.glb');g=edit.source;doc=edit.doc;pivot=np.array(doc['nodes'][1]['translation'])
    face=g.doc['meshes'][0];names=face['extras']['targetNames'];ring=face['primitives'][1]
    rp=g.read(ring['attributes']['POSITION']).astype(float)+pivot
    inner,_,_,_=lip_loops(rp,g.read(ring['indices']).reshape(-1,3),cy)
    jaw=g.read(ring['targets'][names.index('jawOpen')]['POSITION']);close=g.read(ring['targets'][names.index('mouthClose')]['POSITION'])
    upper=inner[jaw[inner,1]>=-1e-7];upper=upper[np.argsort(rp[upper,0])]
    def seam_y(x):return float(np.interp(x,rp[upper,0],rp[upper,1]+close[upper,1]))
    oral=face['primitives'][2];op=g.read(oral['attributes']['POSITION']).astype(float)+pivot
    assert len(op)==361,'Expected the preserved V4 oral cavity'
    lip_z=float(op[288,2]+.029)
    hinge=pivot+np.array(next(n for n in doc['nodes'] if n['name']=='JawAttachments')['translation'])
    arches=[];material=None;kinds={'POSITION':'VEC3','NORMAL':'VEC3','COLOR_0':'VEC4','TEXCOORD_0':'VEC2'}
    for lower,name in ((False,'UpperDentition'),(True,'LowerDentition')):
        node=next(n for n in doc['nodes'] if n['name']==name);mi=node['mesh'];old=g.doc['meshes'][mi]['primitives'][0]
        if material is None:material=old['material']
        assert old['material']==material
        p,t,colours,crowns=arch(cx,half*(1.52 if lower else 1.60),lip_z,seam_y,lower)
        uv=g.read(old['attributes']['TEXCOORD_0'])[0]
        attributes={'POSITION':p-(hinge if lower else pivot),'NORMAL':normals(p,t),
                    'TEXCOORD_0':np.tile(uv,(len(p),1)),'COLOR_0':np.c_[colours,np.ones(len(p))]}
        doc['meshes'][mi]['primitives'][0]={'mode':4,'material':material,'attributes':{k:edit.add(v,kinds[k]) for k,v in attributes.items()},'indices':edit.add(t.reshape(-1),'SCALAR')}
        arches.append({'node':name,'vertices':len(p),'triangles':len(t),'crowns':crowns})
    # Isolate enamel from the head's dark atlas texel: no new texture or render pass.
    enamel=doc['materials'][material];enamel['name']='WarmWhiteDentition'
    pbr=enamel['pbrMetallicRoughness'];pbr.pop('baseColorTexture',None);pbr['baseColorFactor']=[1,1,1,1]
    pbr['roughnessFactor']=.28;pbr['metallicFactor']=0
    out.mkdir(parents=True);target=out/'character.glb';edit.write(target)
    manifest=json.loads((source/'avatar.json').read_text('utf-8'));manifest['id']+='-dentition-appearance'
    manifest['displayName']='杰洛特方向 · 平顺咬合与暖白牙齿候选';manifest['modelSha256']=hashlib.sha256(target.read_bytes()).hexdigest()
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    report={'sourceDirectory':str(source),'sourceModelSha256':hashlib.sha256(g.raw).hexdigest(),'modelSha256':manifest['modelSha256'],
            'arches':arches,'dentitionMaterialIndex':material,'enamelLinearBaseRange':[.63,.76],'roughness':.28,
            'oralFaceEyesNodesPbrImagesPreserved':True,'oralMaterialPreserved':True,'textureIndependentDentalMaterial':True,
            'artistAccepted':False,'tripoCreditsSpent':0,'scope':'Only rigid dental meshes and their shared material changed; complete oral tissue and all facial shapes preserved. Blender preview is not device shader qualification.'}
    (out/'dentition-appearance-authoring.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(report,ensure_ascii=False))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--landmark-report',required=True);p.add_argument('--out',required=True)
    a=p.parse_args();author(a.source,a.landmark_report,a.out)
