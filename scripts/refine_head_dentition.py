"""Replace tooth cards with volumetric arches, rigid jaw attachments and a recessed tongue."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

import numpy as np
from head_glb_edit import GlbEdit
from refine_jaw_close import lip_loops


def normals(points,triangles):
    p=np.asarray(points);triangles=np.asarray(triangles);out=np.zeros_like(p)
    cross=np.cross(p[triangles[:,1]]-p[triangles[:,0]],p[triangles[:,2]]-p[triangles[:,0]])
    for i in range(3):np.add.at(out,triangles[:,i],cross)
    length=np.linalg.norm(out,axis=1);assert length.min()>1e-12
    return out/length[:,None]


def crown(center,radii,tangent=(1,0,0),forward=(0,0,1),power=.36):
    center=np.asarray(center);tangent=np.asarray(tangent);forward=np.asarray(forward)
    points=[center+np.array([0,radii[1],0])];triangles=[];segments=10;rings=6
    for row in range(1,rings+1):
        phi=np.pi*row/(rings+1);extent=np.sin(phi)**power
        for column in range(segments):
            theta=2*np.pi*column/segments;c,s=np.cos(theta),np.sin(theta)
            points.append(center+tangent*radii[0]*np.sign(c)*abs(c)**power*extent+
                    forward*radii[2]*np.sign(s)*abs(s)**power*extent+np.array([0,radii[1]*np.cos(phi),0]))
    points.append(center-np.array([0,radii[1],0]));south=len(points)-1
    for column in range(segments):
        next_column=(column+1)%segments
        triangles.extend([[0,1+column,1+next_column],[south,1+(rings-1)*segments+next_column,1+(rings-1)*segments+column]])
        for row in range(rings-1):
            a=1+row*segments+column;b=1+row*segments+next_column;c=a+segments;d=b+segments
            triangles.extend([[a,b,c],[b,d,c]])
    p=np.array(points);tri=np.array(triangles)
    cross=np.cross(p[tri[:,1]]-p[tri[:,0]],p[tri[:,2]]-p[tri[:,0]])
    flip=np.einsum('ij,ij->i',cross,p[tri].mean(axis=1)-center)<0
    tri[flip]=tri[flip][:,[0,2,1]]
    return p,tri


def arch(cx,span,lip_z,seam_y,lower):
    points=[];triangles=[];rgb=[]
    widths=np.array([3.4,4.7,5.8,6.7,6.7,5.8,4.7,3.4]);widths*=span/widths.sum()
    edges=cx-span/2+np.r_[0,np.cumsum(widths)]
    def curve(x):return lip_z-.0123-.006*((x-cx)/(span/2))**2-(.0008 if lower else 0)
    for i,width in enumerate(widths):
        x=(edges[i]+edges[i+1])/2;slope=-.012*(x-cx)/(span/2)**2
        tangent=np.array([1,0,slope]);tangent/=np.linalg.norm(tangent)
        forward=np.array([-slope,0,1]);forward/=np.linalg.norm(forward)
        height=([.0044,.0054,.0057,.0058,.0058,.0057,.0054,.0044] if lower else [.0048,.0068,.0065,.0063,.0063,.0065,.0068,.0048])[i]
        y=seam_y(x)-(.0077+height/2 if lower else .0005+height/2)
        p,t=crown([x,y,curve(x)],[width*.465,height/2,.0017],tangent,forward)
        tone=1.025 if i in (3,4) else .97
        triangles.extend(t+len(points));points.extend(p);rgb.extend([np.array([.32,.285,.225])*tone]*len(p))
    # Closed, curved gingiva tube: same rigid transform and surface material as its teeth.
    start=len(points);columns=17;sides=6
    for x in np.linspace(cx-span*.51,cx+span*.51,columns):
        center=np.array([x,seam_y(x)+(-.013 if lower else .0005),curve(x)-.0005])
        for column in range(sides):
            theta=2*np.pi*column/sides;points.append(center+[0,.00165*np.cos(theta),.0022*np.sin(theta)])
            rgb.append([.065,.015,.022])
    for row in range(columns-1):
        for column in range(sides):
            a=start+row*sides+column;b=start+row*sides+(column+1)%sides;c=a+sides;d=b+sides
            triangles.extend([[a,b,c],[b,d,c]])
    for row in (0,columns-1):
        cap=len(points);points.append(np.mean(points[start+row*sides:start+(row+1)*sides],axis=0));rgb.append([.065,.015,.022])
        for column in range(sides):
            a=start+row*sides+column;b=start+row*sides+(column+1)%sides
            triangles.append([cap,a,b] if row else [cap,b,a])
    return np.array(points),np.array(triangles),np.array(rgb)


def author(source,landmark_report,out):
    source=Path(source);out=Path(out);assert not out.exists()
    spec=json.loads(Path(landmark_report).read_text('utf-8'));cx,cy=spec['mouth_center'];half=spec['half_width']
    edit=GlbEdit(source/'character.glb');g=edit.source;doc=edit.doc;pivot=np.array(doc['nodes'][1]['translation'])
    mesh=g.doc['meshes'][0];names=mesh['extras']['targetNames'];oral=mesh['primitives'][2]
    colors=g.read(oral['attributes']['COLOR_0']);ids=g.read(oral['indices']).reshape(-1,3);teeth=colors[:,:3].mean(axis=1)>.3
    assert teeth.sum()==80 and ((teeth[ids].all(axis=1))|(~teeth[ids].any(axis=1))).all()
    ids=ids[~teeth[ids].any(axis=1)];used=np.unique(ids);lookup=np.full(len(colors),-1);lookup[used]=np.arange(len(used));ids=lookup[ids]
    op=g.read(oral['attributes']['POSITION']).astype(float)+pivot
    center=op[np.argmin(colors[:,:3].mean(axis=1))];lip_z=float(center[2]+.030)
    white_uv=g.read(oral['attributes']['TEXCOORD_0'])[0];white=g.sample_base_colour(white_uv[None,:],1)[0,:3]
    ring=mesh['primitives'][1];rp=g.read(ring['attributes']['POSITION']).astype(float)+pivot
    inner,_,_,_=lip_loops(rp,g.read(ring['indices']).reshape(-1,3),cy)
    j=g.read(ring['targets'][names.index('jawOpen')]['POSITION']);m=g.read(ring['targets'][names.index('mouthClose')]['POSITION'])
    upper=inner[j[inner,1]>=-1e-7];upper=upper[np.argsort(rp[upper,0])]
    def seam_y(x):return float(np.interp(x,rp[upper,0],rp[upper,1]+m[upper,1]))
    hinge=np.array([cx,cy+.015,.010]);angle=np.deg2rad(15)
    rotation=np.array([[1,0,0],[0,np.cos(angle),-np.sin(angle)],[0,np.sin(angle),np.cos(angle)]])
    tongue,tongue_ids=crown([cx,cy-.0085,lip_z-.020],[half*.43,.0017,.005],power=1)
    points=np.r_[op[used],tongue];triangles=np.r_[ids,tongue_ids+len(used)]
    oral_attributes={'POSITION':points-pivot,'NORMAL':normals(points,triangles),
            'TEXCOORD_0':np.r_[g.read(oral['attributes']['TEXCOORD_0'])[used],np.tile(white_uv,(len(tongue),1))],
            'COLOR_0':np.r_[colors[used],np.c_[np.tile(np.array([.095,.023,.035])/white,(len(tongue),1)),np.ones(len(tongue))]]}
    oral_targets=[]
    for name,t in zip(names,oral['targets']):
        d=np.zeros_like(tongue)
        if name=='jawOpen':d=((tongue-hinge)@rotation.T-(tongue-hinge))*.30
        elif name=='jawLeft':d[:,0]=.004*.30
        elif name=='jawRight':d[:,0]=-.004*.30
        elif name=='jawForward':d[:,2]=.005*.30
        oral_targets.append({'POSITION':edit.add(np.r_[g.read(t['POSITION'])[used],d],'VEC3')})
    kinds={'POSITION':'VEC3','NORMAL':'VEC3','COLOR_0':'VEC4','TEXCOORD_0':'VEC2'}
    doc['meshes'][0]['primitives'][2]={'mode':4,'material':1,'attributes':{k:edit.add(v,kinds[k]) for k,v in oral_attributes.items()},
            'indices':edit.add(triangles.reshape(-1),'SCALAR'),'targets':oral_targets}
    doc['materials'][1].pop('extensions',None);doc['materials'][1]['name']='OralTissuePBR'
    doc['materials'][1]['pbrMetallicRoughness']['roughnessFactor']=.94
    # The board has no mouth self-shadowing: bake a conservative recess attenuation
    # into tissue diffuse colour while retaining physically lit surface normals.
    doc['materials'][1]['pbrMetallicRoughness']['baseColorFactor']=[.20,.20,.20,1]
    doc['extensionsUsed']=[e for e in doc.get('extensionsUsed',[]) if e!='KHR_materials_unlit']
    enamel=copy.deepcopy(doc['materials'][2]);enamel['name']='DentitionSurface';enamel['pbrMetallicRoughness']['roughnessFactor']=.32
    mat=len(doc['materials']);doc['materials'].append(enamel);jaw=next(i for i,n in enumerate(doc['nodes']) if n['name']=='JawAttachments')
    doc['nodes'][jaw]['translation']=(hinge-pivot).tolist();arch_report=[]
    for lower in (False,True):
        p,t,rgb=arch(cx,half*(1.52 if lower else 1.60),lip_z,seam_y,lower)
        name='LowerDentition' if lower else 'UpperDentition';local=p-(hinge if lower else pivot)
        attrs={'POSITION':local,'NORMAL':normals(p,t),'TEXCOORD_0':np.tile(white_uv,(len(p),1)),
                'COLOR_0':np.c_[rgb/white,np.ones(len(p))]}
        assert (attrs['COLOR_0']>=0).all() and (attrs['COLOR_0']<=1).all()
        pr={'mode':4,'material':mat,'attributes':{k:edit.add(v,kinds[k]) for k,v in attrs.items()},'indices':edit.add(t.reshape(-1),'SCALAR')}
        mesh_index=len(doc['meshes']);doc['meshes'].append({'name':name,'primitives':[pr]})
        node=len(doc['nodes']);doc['nodes'].append({'name':name,'mesh':mesh_index})
        parent=jaw if lower else 1;doc['nodes'][parent].setdefault('children',[]).append(node)
        arch_report.append({'node':name,'vertices':len(p),'triangles':len(t),'teeth':8})
    out.mkdir();target=out/'character.glb';edit.write(target)
    manifest=json.loads((source/'avatar.json').read_text('utf-8'));manifest['id']+='-rigid-dentition'
    manifest['displayName']='杰洛特方向 · 下颌绑定与立体口腔候选';manifest['modelSha256']=hashlib.sha256(target.read_bytes()).hexdigest()
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    report={'modelSha256':manifest['modelSha256'],'sourceModelSha256':hashlib.sha256(g.raw).hexdigest(),
            'jawHingeGlobalMeters':hinge.tolist(),'arches':arch_report,'tongueVertices':len(tongue),
            'sourceSkinEyeLipChannelsAndPbrMapsUnchanged':True,'artistAccepted':False,'tripoCreditsSpent':0,
            'scope':'Procedural crowns/gums, rigid dental arches, recessed tongue and lit tissue; not anatomical anatomy or collision qualification'}
    (out/'dentition-authoring.json').write_text(json.dumps(report,indent=2),'utf-8');print(json.dumps(report))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--landmark-report',required=True);p.add_argument('--out',required=True)
    a=p.parse_args();author(a.source,a.landmark_report,a.out)
