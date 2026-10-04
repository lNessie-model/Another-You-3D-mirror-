"""Local surface clipping for an experimental mouth seam, preserving UV interpolation.

This is a geometry experiment, not a watertight print mesh or artist-approved rig.
"""
import numpy as np

def clip(poly, values, positive):
    out=[]
    for i,b in enumerate(poly):
        a=poly[i-1];da=values[i-1];db=values[i]
        ina=da>=-1e-12 if positive else da<=1e-12
        inb=db>=-1e-12 if positive else db<=1e-12
        if ina!=inb:
            t=da/(da-db);out.append(a+(b-a)*t)
        if inb:out.append(b)
    return out

def split_mouth(p,n,uv,triangles,*,left,right,line_y,front_z,knots=()):
    attrs=np.c_[p,n,uv];vertices=list(attrs);flags=[0]*len(p);faces=[];split_count=0
    def emit(poly,flag):
        if len(poly)<3:return
        ids=[]
        for v in poly:ids.append(len(vertices));vertices.append(v);flags.append(flag)
        for i in range(1,len(ids)-1):
            a,b,c=(vertices[j][:3] for j in [ids[0],ids[i],ids[i+1]])
            if np.linalg.norm(np.cross(b-a,c-a))>1e-12:faces.append([ids[0],ids[i],ids[i+1]])
    for ids in triangles:
        points=attrs[ids];xyz=points[:,:3]
        signed=xyz[:,1]-line_y(xyz[:,0])
        relevant=(xyz[:,0].max()>left and xyz[:,0].min()<right and xyz[:,2].min()>front_z
                  and signed.min()<-1e-8 and signed.max()>1e-8)
        if not relevant:faces.append(list(ids));continue
        split_count+=1
        emit(clip(list(points),list(xyz[:,0]-left),False),0)
        center=clip(list(points),list(xyz[:,0]-left),True)
        if not center:continue
        emit(clip(center,[v[0]-right for v in center],True),0)
        center=clip(center,[v[0]-right for v in center],False)
        if not center:continue
        sections=[center]
        # Curve knots divide the seam into linear segments before interpolating edges.
        for knot in knots:
            pieces=[]
            for section in sections:
                values=[v[0]-knot for v in section]
                for positive in (False,True):
                    part=clip(section,values,positive)
                    if len(part)>=3:pieces.append(part)
            sections=pieces
        for section in sections:
            distances=[v[1]-float(line_y(v[0])) for v in section]
            emit(clip(section,distances,True),1);emit(clip(section,distances,False),-1)
    result=np.array(vertices);norms=result[:,3:6];length=np.linalg.norm(norms,axis=1)
    assert (length>1e-8).all();norms/=length[:,None]
    return result[:,:3],norms,result[:,6:8],np.array(faces,dtype=np.int32),np.array(flags),split_count
