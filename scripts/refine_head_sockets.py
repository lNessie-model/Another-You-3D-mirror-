"""Offline eye socket topology candidate. Preserve the source atlas and morph attributes.

Local surface surgery replaces sparse painted-eye triangles with an annular lid
surface and two rigid eyeballs. This is an artwork candidate, requiring previews.
No raster image is modified and no remote generation is performed.
"""
from pathlib import Path
import argparse, hashlib, json, struct
import numpy as np
from tripo_head_adapter import Glb, write_glb
from head_surface_topology import FrontSurface, cut_convex_hole


def append_eyeballs(path, centers, radius, white_uv, white):
    g=Glb(path);doc=g.doc;binary=bytearray(g.raw[g.start:])
    def add(values,kind):
        values=np.asarray(values,dtype='<u2' if kind=='SCALAR' else '<f4')
        while len(binary)%4:binary.append(0)
        view=len(doc['bufferViews']);doc['bufferViews'].append({'buffer':0,'byteOffset':len(binary),'byteLength':values.nbytes})
        binary.extend(values.tobytes());a={'bufferView':view,'componentType':5123 if kind=='SCALAR' else 5126,'count':len(values),'type':kind}
        if kind=='VEC3':a.update(min=values.min(axis=0).tolist(),max=values.max(axis=0).tolist())
        index=len(doc['accessors']);doc['accessors'].append(a);return index
    positions=[];normals=[];colors=[];faces=[];columns=32
    phis=np.array([0,.04,.08,.12,.16,.20,.24,.27,.29,.31,.34,.38,.45,.55,.72,1.,1.3,1.6,2.,2.4,2.8,np.pi])
    for row,phi in enumerate(phis):
        for column in range(columns):
            theta=column*2*np.pi/columns;n=np.array([np.sin(phi)*np.cos(theta),np.sin(phi)*np.sin(theta),np.cos(phi)])
            positions.append(n*radius);normals.append(n)
            # Muted sclera, amber iris and a small dark pupil; colors are linear.
            if phi<.12:rgb=np.array([.007,.006,.004])
            elif phi<.29:
                streak=.75+.25*np.sin(theta*17+phi*21)**2
                rgb=np.array([.16,.095,.025])*streak
            elif phi<.34:rgb=np.array([.012,.009,.005])
            else:rgb=np.array([.48,.44,.40])
            colors.append([*np.clip(rgb/white,0,1),1])
            if row<len(phis)-1:
                aa=row*columns+column;bb=row*columns+(column+1)%columns;cc=aa+columns;dd=bb+columns
                faces.extend([[aa,cc,bb],[bb,cc,dd]])
    p=np.asarray(positions);faces=np.asarray(faces);cross=np.cross(p[faces[:,1]]-p[faces[:,0]],p[faces[:,2]]-p[faces[:,0]])
    faces=faces[np.linalg.norm(cross,axis=1)>1e-12]
    cross=np.cross(p[faces[:,1]]-p[faces[:,0]],p[faces[:,2]]-p[faces[:,0]])
    wrong=(cross*p[faces].mean(axis=1)).sum(axis=1)<0;faces[wrong]=faces[wrong][:,[0,2,1]]
    primitive={'attributes':{'POSITION':add(p,'VEC3'),'NORMAL':add(normals,'VEC3'),'COLOR_0':add(colors,'VEC4'),
                              'TEXCOORD_0':add(np.tile(white_uv,(len(p),1)),'VEC2')},
               'indices':add(faces.reshape(-1),'SCALAR'),'material':0,'mode':4}
    mesh_index=len(doc['meshes']);doc['meshes'].append({'name':'Eyeball','primitives':[primitive]})
    pivot=np.array(doc['nodes'][1]['translation'])
    for side,center in centers:
        index=len(doc['nodes']);doc['nodes'].append({'name':side+'Eye','mesh':mesh_index,'translation':(center-pivot).tolist()})
        doc['nodes'][1]['children'].append(index)
    doc['buffers'][0]['byteLength']=len(binary)
    encoded=json.dumps(doc,separators=(',',':')).encode();encoded+=b' '*((-len(encoded))%4)
    path.write_bytes(struct.pack('<III',0x46546c67,2,28+len(encoded)+len(binary))+struct.pack('<II',len(encoded),0x4e4f534a)+encoded+struct.pack('<II',len(binary),0x004e4942)+binary)
    return {'unique_vertices':len(p),'triangles_per_eye':len(faces),'radius_m':radius,'nodes':2}


def author(source,landmarks,projection,out):
    source=Path(source);out=Path(out);assert not out.exists(),'Preserve previous candidates'
    g=Glb(source/'character.glb');mesh=g.doc['meshes'][0];pr=mesh['primitives'][0];pivot=np.array(g.doc['nodes'][1]['translation'])
    names=mesh['extras']['targetNames'];p=g.read(pr['attributes']['POSITION']).astype(float)+pivot
    attrs=np.c_[p,g.read(pr['attributes']['NORMAL']),g.read(pr['attributes']['TEXCOORD_0']),g.read(pr['attributes']['COLOR_0']),
                *[g.read(t['POSITION']) for t in pr['targets']]]
    ids=g.read(pr['indices']).reshape(-1,3);surface=FrontSurface(attrs,ids);source_vertices=len(attrs)
    pts=np.array(json.loads(Path(landmarks).read_text('utf-8'))['landmarks'])[:,:2]
    cam=json.loads(Path(projection).read_text('utf-8'));scale=cam['orthographic_scale'];center=cam['views'][0]['center']
    pts[:,0]=(pts[:,0]-.5)*scale+center[0];pts[:,1]=(.5-pts[:,1])*scale+center[2]
    image=g.image(0);pixel=np.unravel_index(image.min(axis=2).argmax(),image.shape[:2]);white_uv=np.array([(pixel[1]+.5)/image.shape[1],(pixel[0]+.5)/image.shape[0]])
    white=g.sample_base_colour(white_uv[None,:],pr['material'])[0,:3];assert white.min()>.5
    details=[];centers=[];new_targets={};radius=.0115
    specs=[('Left',[263,387,386,385,384,398,362],[263,373,374,380,381,382,362],473),
           ('Right',[33,160,159,158,157,173,133],[33,144,145,153,154,155,133],468)]
    for side,upper_ids,lower_ids,iris in specs:
        upper=pts[upper_ids];lower=pts[lower_ids];upper=upper[np.argsort(upper[:,0])];lower=lower[np.argsort(lower[:,0])]
        xmin=max(upper[:,0].min(),lower[:,0].min());xmax=min(upper[:,0].max(),lower[:,0].max())
        cx=(xmin+xmax)/2;cy=(np.interp(cx,upper[:,0],upper[:,1])+np.interp(cx,lower[:,0],lower[:,1]))/2
        height=np.interp(cx,upper[:,0],upper[:,1])-np.interp(cx,lower[:,0],lower[:,1]);half=(xmax-xmin)/2
        columns=64;rows=8;theta=np.arange(columns)*2*np.pi/columns
        outer=np.c_[cx+half*1.38*np.cos(theta),cy+height*1.9*np.sin(theta)]
        attrs,ids,cut=cut_convex_hole(attrs,ids,outer,front_z=.035)
        offset=len(attrs);base=[];blink=[];wide=[];ring_faces=[]
        iris_xy=pts[iris];iris_z=surface.sample(*iris_xy)[2];eye_center=np.array([*iris_xy,iris_z-radius]);centers.append((side,eye_center))
        def z_at(x,y,inner_factor):
            original=surface.sample(x,y)[2];r2=radius**2-(x-eye_center[0])**2-(y-eye_center[1])**2
            sphere=eye_center[2]+np.sqrt(max(0,r2))+.0003
            return max(original,sphere) if inner_factor>0 else original
        for row in range(rows):
            t=row/(rows-1)
            for j,angle in enumerate(theta):
                x=cx+half*np.cos(angle);top=np.interp(x,upper[:,0],upper[:,1]);bottom=np.interp(x,lower[:,0],lower[:,1])
                inner_y=top if np.sin(angle)>=0 else bottom;seam_y=bottom+(top-bottom)*.12
                xy=outer[j]*(1-t)+np.array([x,inner_y])*t
                closed_xy=outer[j]*(1-t)+np.array([x,seam_y])*t
                opened_xy=xy+np.array([0,height*.3*np.sin(angle)*t])
                value=surface.sample(*xy);value[:3]=[*xy,z_at(*xy,t)]
                # A new ring can cross several source UV islands. Sample the source
                # albedo into vertex colors rather than interpolating unrelated UVs.
                # Move the inner albedo samples just outside the photographed eye;
                # otherwise painted sclera creates a white seam even when geometry closes.
                skin_xy=xy+np.array([0,(.0011 if np.sin(angle)>=0 else -.0018)*t*t])
                skin=surface.sample(*skin_xy)
                rgb=g.sample_base_colour(skin[6:8][None,:],pr['material'])[0,:3]*skin[8:11]
                value[6:8]=white_uv;value[8:11]=np.clip(rgb/white,0,1)
                closed=np.array([*closed_xy,z_at(*closed_xy,t)+.00012*t]);opened=np.array([*opened_xy,z_at(*opened_xy,t)])
                # Keep all non-eye expression attributes interpolated from the source.
                base.append(value);blink.append(closed-value[:3]);wide.append(opened-value[:3])
        attrs=np.r_[attrs,base]
        for row in range(rows-1):
            for j in range(columns):
                aa=offset+row*columns+j;bb=offset+row*columns+(j+1)%columns;cc=aa+columns;dd=bb+columns
                # CCW annulus with outer row before inner row.
                ring_faces.extend([[aa,bb,cc],[bb,dd,cc]])
        ids=np.r_[ids,ring_faces]
        for name in ('eyeBlink'+side,'eyeSquint'+side,'eyeWide'+side):
            column=12+names.index(name)*3;attrs[:,column:column+3]=0
        blink=np.asarray(blink);wide=np.asarray(wide)
        for name,delta in [('eyeBlink'+side,blink),('eyeSquint'+side,blink*.52),('eyeWide'+side,wide)]:
            column=12+names.index(name)*3;attrs[offset:,column:column+3]=delta
        # Full blink remains closed under squint/wide combinations.
        new_targets['correctiveBlinkSquint'+side]=(offset,-blink*.52)
        new_targets['correctiveBlinkWide'+side]=(offset,-wide)
        details.append({'side':side,'center':eye_center.tolist(),'ring_vertices':len(base),'ring_triangles':len(ring_faces),'cut':cut,'neutral_height_m':height})
    for name in names:
        if name.startswith('eyeLook'):attrs[:,12+names.index(name)*3:15+names.index(name)*3]=0
    shapes={name:attrs[:,12+i*3:15+i*3].copy() for i,name in enumerate(names)}
    for name,(offset,delta) in new_targets.items():
        target=np.zeros((len(attrs),3));target[offset:offset+len(delta)]=delta;shapes[name]=target
    # Discard dead source/intersection vertices, keeping every surviving linear attribute.
    used=np.unique(ids);remap=np.full(len(attrs),-1);remap[used]=np.arange(len(used));ids=remap[ids];attrs=attrs[used]
    shapes={name:values[used] for name,values in shapes.items()}
    normals=attrs[:,3:6];normals/=np.maximum(np.linalg.norm(normals,axis=1)[:,None],1e-12)
    view=g.doc['bufferViews'][g.doc['images'][0]['bufferView']];start=g.start+view.get('byteOffset',0);atlas=g.raw[start:start+view['byteLength']]
    out.mkdir();target=out/'character.glb';write_glb(target,attrs[:,:3]-pivot,normals,attrs[:,8:12],ids,head_pivot=pivot,morphs=shapes,uv=attrs[:,6:8],atlas_png=atlas)
    eye_report=append_eyeballs(target,centers,radius,white_uv,white)
    manifest=json.loads((source/'avatar.json').read_text('utf-8'));manifest['id']+='-socket-eyes';manifest['displayName']='杰洛特方向 · 独立眼球眼睑候选'
    manifest['modelSha256']=hashlib.sha256(target.read_bytes()).hexdigest();manifest['bindings']=[b for b in manifest['bindings'] if not b['source'].startswith('eyeLook')]
    manifest['rig'].update(gazeMode='joint',leftEyeNode='LeftEye',rightEyeNode='RightEye',gazeYawDegrees=22,gazePitchDegrees=15)
    for side in ('Left','Right'):
        for source_name in ('Squint','Wide'):
            manifest['derivedBindings'].append({'operation':'product','sources':['eyeBlink'+side,'eye'+source_name+side], 'mesh':'Face','target':'correctiveBlink'+source_name+side,'gain':1})
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    report={'source':str(source),'source_sha256':hashlib.sha256(g.raw).hexdigest(),'model_sha256':manifest['modelSha256'],'face_vertices':len(attrs),
            'face_triangles':len(ids),'source_vertices':source_vertices,'eyelids':details,'eyeballs':eye_report,'artistAccepted':False,
            'scope':'True local eye holes and annular upper/lower lids with rigid gaze. Original atlas retained. Mouth remains the prior prototype. Require front/oblique and actual production-rig combination previews.'}
    (out/'socket-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--source',required=True);parser.add_argument('--landmarks',required=True);parser.add_argument('--projection',required=True);parser.add_argument('--out',required=True)
    a=parser.parse_args();author(a.source,a.landmarks,a.projection,a.out)
