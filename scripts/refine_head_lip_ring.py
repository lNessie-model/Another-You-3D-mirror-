"""Local lip topology candidate; retain source atlas bytes and named facial channels."""
from pathlib import Path
import argparse,hashlib,json
import numpy as np
from tripo_head_adapter import Glb,write_glb
from head_surface_topology import FrontSurface,cut_convex_hole


def smoothstep(a,b,value):
    q=np.clip((value-a)/(b-a),0,1);return q*q*(3-2*q)


def compact_exact(attributes,triangles):
    """Merge only bit-identical serialized attributes, preserving UV and morph seams."""
    used=np.unique(triangles);mapping=np.full(len(attributes),-1);mapping[used]=np.arange(len(used))
    data=np.ascontiguousarray(attributes[used],dtype='<f4')
    keys=data.view(np.dtype((np.void,data.shape[1]*data.dtype.itemsize))).reshape(-1)
    _,first,inverse=np.unique(keys,return_index=True,return_inverse=True)
    return data[first].astype(float),inverse[mapping[triangles]]


def author(source,landmarks,projection,out):
    source=Path(source);out=Path(out);assert not out.exists()
    g=Glb(source/'character.glb');mesh=g.doc['meshes'][0];pr=mesh['primitives'][0];pivot=np.array(g.doc['nodes'][1]['translation'])
    names=mesh['extras']['targetNames'];p=g.read(pr['attributes']['POSITION']).astype(float)+pivot
    attrs=np.c_[p,g.read(pr['attributes']['NORMAL']),g.read(pr['attributes']['TEXCOORD_0']),g.read(pr['attributes']['COLOR_0']),
                *[g.read(t['POSITION']) for t in pr['targets']]]
    ids=g.read(pr['indices']).reshape(-1,3)
    # The earlier prototype's dark backing and tooth cards are replaced below.
    skin=np.all(abs(attrs[:,8:11]-1)<1e-6,axis=1);ids=ids[skin[ids].all(axis=1)]
    surface=FrontSurface(attrs,ids)
    pts=np.array(json.loads(Path(landmarks).read_text('utf-8'))['landmarks'])[:,:2];cam=json.loads(Path(projection).read_text('utf-8'))
    scale=cam['orthographic_scale'];center=cam['views'][0]['center'];pts[:,0]=(pts[:,0]-.5)*scale+center[0];pts[:,1]=(.5-pts[:,1])*scale+center[2]
    corners=pts[[61,291]];corners=corners[np.argsort(corners[:,0])];xmin,xmax=corners[:,0];cx=(xmin+xmax)/2;half=(xmax-xmin)/2
    cy=(pts[13,1]+pts[14,1])/2
    def line_y(x):return np.interp(x,[xmin,cx,xmax],[corners[0,1],cy,corners[1,1]])
    image=g.image(0);pixel=np.unravel_index(image.min(axis=2).argmax(),image.shape[:2]);white_uv=np.array([(pixel[1]+.5)/image.shape[1],(pixel[0]+.5)/image.shape[0]])
    white=g.sample_base_colour(white_uv[None,:],pr['material'])[0,:3]
    columns=96;rows=11;angles=np.arange(columns)*2*np.pi/columns
    outer=np.c_[cx+half*1.45*np.cos(angles),cy+.014*np.sin(angles)]
    attrs,ids,cut=cut_convex_hole(attrs,ids,outer,front_z=.035)
    def carrier(points):
        # Front mandible moves; lower neck and rear hair have a smooth stationary boundary.
        y,z=points[:,1],points[:,2]
        return smoothstep(cy+.010,cy-.018,y)*smoothstep(-.004,.040,z)*smoothstep(-.12,-.095,y)
    hinge=np.array([cx,cy+.015,.010]);angle=np.deg2rad(15)
    def rotation_delta(points):
        q=points-hinge;rot=q.copy();rot[:,1]=np.cos(angle)*q[:,1]-np.sin(angle)*q[:,2];rot[:,2]=np.sin(angle)*q[:,1]+np.cos(angle)*q[:,2]
        return rot-q
    def set_shape(values,name,delta):values[:,12+names.index(name)*3:15+names.index(name)*3]=delta
    jaw=rotation_delta(attrs[:,:3])*carrier(attrs[:,:3])[:,None]
    set_shape(attrs,'jawOpen',jaw);set_shape(attrs,'correctiveJawOpenMouthClose',-jaw)
    for name,direction in [('jawLeft',[.004,0,0]),('jawRight',[-.004,0,0]),('jawForward',[0,0,.005])]:set_shape(attrs,name,carrier(attrs[:,:3])[:,None]*direction)
    new=[];ring_faces=[];inner_values=[];opening=[]
    for j,theta in enumerate(angles):
        x=cx+half*np.cos(theta);s=np.sin(theta);xy=np.array([x,line_y(x)+.00018*s]);value=surface.sample(*xy);value[:2]=xy
        lower=1 if s<0 else 0
        delta=rotation_delta(value[None,:3])[0]*(abs(s)*lower)
        if not lower:delta[1]=.0008*s*s
        inner_values.append(value);opening.append(delta)
    inner_values=np.array(inner_values);opening=np.array(opening)
    offset=len(attrs)
    for row in range(rows):
        t=row/(rows-1);blend=smoothstep(0,1,t)
        for j,theta in enumerate(angles):
            xy=outer[j]*(1-t)+inner_values[j,:2]*t;value=surface.sample(*xy);value[:2]=xy
            rgb=g.sample_base_colour(value[6:8][None,:],pr['material'])[0,:3]*value[8:11]
            value[6:8]=white_uv;value[8:11]=np.clip(rgb/white,0,1)
            outer_sample=surface.sample(*outer[j]);outer_delta=rotation_delta(outer_sample[None,:3])[0]*carrier(outer_sample[None,:3])[0]
            delta=outer_delta*(1-blend)+opening[j]*blend
            for name,d in [('jawOpen',delta),('correctiveJawOpenMouthClose',-delta)]:value[12+names.index(name)*3:15+names.index(name)*3]=d
            # Neutral mouthClose brings the two lip boundaries onto the same curved seam.
            closed=np.array([0,line_y(value[0])-value[1],0])*t**3
            value[12+names.index('mouthClose')*3:15+names.index('mouthClose')*3]=closed
            new.append(value)
    attrs=np.r_[attrs,new]
    for row in range(rows-1):
        for j in range(columns):
            aa=offset+row*columns+j;bb=offset+row*columns+(j+1)%columns;cc=aa+columns;dd=bb+columns
            ring_faces.extend([[aa,bb,cc],[bb,dd,cc]])
    ids=np.r_[ids,ring_faces]
    # A real inner wall follows the lip at its front and a recessed oral bowl at its back.
    oral=[];oral_faces=[];lip_z=surface.sample(cx,cy)[2];inner_ring=np.asarray(new)[-columns:]
    def oral_vertex(point,rgb,delta):
        value=np.zeros(attrs.shape[1]);value[:3]=point;value[3:6]=[0,0,1];value[6:8]=white_uv;value[8:11]=np.clip(np.array(rgb)/white,0,1)
        value[11]=.875 # Temporary exact marker; split_oral_material removes it before preview/deployment.
        for name,d in [('jawOpen',delta),('correctiveJawOpenMouthClose',-delta)]:value[12+names.index(name)*3:15+names.index(name)*3]=d
        return value
    for row in range(3):
        t=row/2
        for j,theta in enumerate(angles):
            bx=cx+half*.95*np.cos(theta);by=line_y(bx)+.00013*np.sin(theta)
            back=np.array([bx,by,surface.sample(bx,by)[2]-.016])
            point=inner_ring[j,:3]*(1-t)+back*t
            delta=opening[j]*(1-t)+rotation_delta(back[None,:])[0]*(np.sin(theta)<0)*abs(np.sin(theta))*t
            value=oral_vertex(point,np.array([.048,.012,.016])*(1-t)+np.array([.008,.0018,.0025])*t,delta)
            # Every facial source moves the wall's front boundary with the lip.
            value[12:]=inner_ring[j,12:]*(1-t)
            for name,d in [('jawOpen',delta),('correctiveJawOpenMouthClose',-delta)]:value[12+names.index(name)*3:15+names.index(name)*3]=d
            oral.append(value)
    for row in range(2):
        for j in range(columns):
            aa=row*columns+j;bb=row*columns+(j+1)%columns;cc=aa+columns;dd=bb+columns;oral_faces.extend([[aa,bb,cc],[bb,dd,cc]])
    center_index=len(oral);back_center=np.array([cx,cy-.00008,lip_z-.030]);oral.append(oral_vertex(back_center,[.006,.001,.0015],rotation_delta(back_center[None,:])[0]*.45))
    for j in range(columns):oral_faces.append([center_index,2*columns+j,2*columns+(j+1)%columns])
    # Separate tooth faces are curved in X/Z, and sheltered by the closed neutral lips.
    for lower in [False,True]:
        span=half*(1.28 if not lower else 1.16);edges=np.linspace(cx-span/2,cx+span/2,11)
        y0=cy+(-.002 if not lower else -.011);height=.0032 if not lower else .0024
        for tooth in range(10):
            xx=[edges[tooth]+.00010,edges[tooth+1]-.00010];start=len(oral)
            for x,yoff in [(xx[0],-height/2),(xx[1],-height/2),(xx[1],height/2),(xx[0],height/2)]:
                point=np.array([x,y0+yoff+.001*((x-cx)/(span/2))**2,lip_z-.007-.003*((x-cx)/(span/2))**2])
                delta=rotation_delta(point[None,:])[0]*lower;oral.append(oral_vertex(point,[.37,.34,.29],delta))
            oral_faces.extend([[start,start+1,start+2],[start,start+2,start+3]])
    oral_offset=len(attrs);attrs=np.r_[attrs,oral];ids=np.r_[ids,np.array(oral_faces)+oral_offset]
    attrs,ids=compact_exact(attrs,ids)
    normals=attrs[:,3:6];normals/=np.maximum(np.linalg.norm(normals,axis=1)[:,None],1e-12)
    shapes={name:attrs[:,12+i*3:15+i*3] for i,name in enumerate(names)}
    out.mkdir();view=g.doc['bufferViews'][g.doc['images'][0]['bufferView']];off=g.start+view.get('byteOffset',0);atlas=g.raw[off:off+view['byteLength']]
    target=out/'character.glb';write_glb(target,attrs[:,:3]-pivot,normals,attrs[:,8:12],ids,head_pivot=pivot,morphs=shapes,uv=attrs[:,6:8],atlas_png=atlas)
    manifest=json.loads((source/'avatar.json').read_text('utf-8'));manifest['id']+='-lip-ring';manifest['displayName']='杰洛特方向 · 唇圈重拓扑候选';manifest['modelSha256']=hashlib.sha256(target.read_bytes()).hexdigest()
    (out/'avatar.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    report={'model_sha256':manifest['modelSha256'],'source':str(source),'vertices':len(attrs),'triangles':len(ids),'cut':cut,'ring_vertices':rows*columns,
            'oral_vertices_before_compaction':len(oral),'temporary_oral_alpha_marker':.875,'mouth_center':[cx,cy],'half_width':half,'artistAccepted':False,
            'scope':'Local lip annulus, continuous front mandible carrier, inner oral wall and recessed bowl; run sockets and split_oral_material before previews. No anatomical or collision qualification.'}
    (out/'lip-ring-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(report,ensure_ascii=False))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--source',required=True);parser.add_argument('--landmarks',required=True);parser.add_argument('--projection',required=True);parser.add_argument('--out',required=True)
    a=parser.parse_args();author(a.source,a.landmarks,a.projection,a.out)
