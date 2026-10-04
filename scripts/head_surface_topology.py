"""Attribute-preserving local surface sampling and convex-hole clipping for head retopology."""
import numpy as np

class FrontSurface:
    def __init__(self,attributes,triangles):
        self.attributes=np.asarray(attributes,dtype=float);self.triangles=np.asarray(triangles,dtype=int)
        self.v=self.attributes[self.triangles];self.a=self.v[:,0,:2]
        self.b=self.v[:,1,:2]-self.a;self.c=self.v[:,2,:2]-self.a
        self.det=self.b[:,0]*self.c[:,1]-self.b[:,1]*self.c[:,0];self.valid=abs(self.det)>1e-12
    def sample(self,x,y):
        q=np.array([x,y])-self.a;s=np.zeros(len(self.v));t=s.copy();good=self.valid
        s[good]=(q[good,0]*self.c[good,1]-q[good,1]*self.c[good,0])/self.det[good]
        t[good]=(self.b[good,0]*q[good,1]-self.b[good,1]*q[good,0])/self.det[good]
        hit=good&(s>=-1e-7)&(t>=-1e-7)&(s+t<=1.0000001)
        candidates=np.flatnonzero(hit)
        if len(candidates)==0:raise ValueError('No projected surface at '+str((x,y)))
        weights=np.c_[1-s-t,s,t];z=(weights*self.v[:,:,2]).sum(axis=1)
        face=candidates[np.argmax(z[candidates])]
        return weights[face]@self.v[face]

def clip_polygon(poly,distances,inside):
    out=[]
    for i,current in enumerate(poly):
        previous=poly[i-1];a=distances[i-1];b=distances[i]
        was=a>=0 if inside else a<=0;now=b>=0 if inside else b<=0
        if was!=now:out.append(previous+(current-previous)*(a/(a-b)))
        if now:out.append(current)
    return out

def cut_convex_hole(attributes,triangles,outline,front_z=.02):
    """Remove only the front surface inside a CCW polygon, interpolating every attribute.

    Outside triangles retain their indices. Intersections retain winding and exact
    linear attributes. Dead source vertices remain until a later optional compaction.
    """
    attrs=np.asarray(attributes,dtype=float);ids=np.asarray(triangles,dtype=int);outline=np.asarray(outline,dtype=float)
    area=np.sum(outline[:,0]*np.roll(outline[:,1],-1)-outline[:,1]*np.roll(outline[:,0],-1))*.5
    if not area>0:raise ValueError('Hole outline must be CCW')
    low=outline.min(axis=0);high=outline.max(axis=0);vertices=list(attrs);faces=[];changed=removed=0
    def emit(poly):
        if len(poly)<3:return
        for i in range(1,len(poly)-1):
            face=np.array([poly[0],poly[i],poly[i+1]])
            if np.linalg.norm(np.cross(face[1,:3]-face[0,:3],face[2,:3]-face[0,:3]))<=1e-12:continue
            start=len(vertices);vertices.extend(face);faces.append([start,start+1,start+2])
    for face in ids:
        data=attrs[face];p=data[:,:3]
        if p[:,2].max()<=front_z or (p[:,:2].max(axis=0)<low).any() or (p[:,:2].min(axis=0)>high).any():faces.append(face.tolist());continue
        # Clip partial front/back triangles as well. Skipping a triangle because
        # one corner is behind the threshold leaves foreground hair inside holes.
        distances=data[:,2]-front_z
        front=clip_polygon(list(data),distances,True)
        back=clip_polygon(list(data),distances,False) if p[:,2].min()<front_z else []
        interior=front;outside=[]
        for i,end in enumerate(outline):
            start=outline[i-1];edge=end-start
            distance=[edge[0]*(v[1]-start[1])-edge[1]*(v[0]-start[0]) for v in interior]
            outside.append(clip_polygon(interior,distance,False));interior=clip_polygon(interior,distance,True)
            if len(interior)<3:break
        if len(interior)<3:faces.append(face.tolist());continue
        changed+=1
        for poly in outside:emit(poly)
        emit(back)
        removed+=1
    return np.asarray(vertices),np.asarray(faces,dtype=int),{'intersected_source_triangles':changed,'removed_interior_polygons':removed}
