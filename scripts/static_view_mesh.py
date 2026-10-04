"""Cull only triangles facing away from every eye of a fixed parallel camera.

This is an offline candidate optimization, valid only with the verified fit and
camera. Keep all AABB extrema so production framing can remain bit-identical.
"""
import numpy as np


def visible_subset(positions,normals,colours,indices,fit_matrix):
    p=np.asarray(positions);n=np.asarray(normals);c=np.asarray(colours)
    ids=np.asarray(indices)
    if p.ndim!=2 or p.shape[1]!=3 or not len(p) or n.shape!=p.shape or c.shape!=(len(p),4):
        raise ValueError('Matching finite position, normal and colour arrays required')
    if not all(np.isfinite(a).all() for a in [p,n,c,fit_matrix]):
        raise ValueError('Nonfinite static geometry or fit')
    if ids.dtype.kind not in 'iu' or ids.size==0 or ids.size%3 or ids.min()<0 or ids.max()>=len(p):
        raise ValueError('Triangle indices are outside the source vertex array')
    triangles=ids.reshape(-1,3)
    f=np.asarray(fit_matrix,dtype=float).reshape(4,4).T
    expected=np.eye(4);expected[:3,:3]*=f[0,0];expected[:3,3]=f[:3,3]
    if not np.array_equal(f,expected) or f[0,0]<=0:
        raise ValueError('Camera proof requires a positive uniform affine fit')
    fitted=p*f[0,0]+f[:3,3]
    a,b,d=(fitted[triangles[:,i]] for i in range(3))
    cross=np.cross(b-a,d-a);centre=(a+b+d)/3
    dots=np.stack([np.einsum('ij,ij->i',cross,np.array([eye,0,3])-centre) for eye in [-.2,.2]])
    # Preserve nearly edge-on and degenerate faces instead of relying on raster precision.
    reserve=np.linalg.norm(cross,axis=1)*1e-5
    keep=dots.max(axis=0)>=-reserve
    retained=triangles[keep]
    if not len(retained):raise ValueError('No camera-facing triangles')
    extrema=np.r_[p.argmin(axis=0),p.argmax(axis=0)]
    used=np.unique(np.r_[retained.reshape(-1),extrema])
    remap=np.full(len(p),-1,dtype=np.int32);remap[used]=np.arange(len(used))
    compact=p[used]
    assert np.array_equal(compact.min(axis=0),p.min(axis=0))
    assert np.array_equal(compact.max(axis=0),p.max(axis=0))
    assert (dots[:,~keep]<-reserve[~keep]).all()
    report={'original_vertices':len(p),'retained_vertices':len(used),
            'original_triangles':len(triangles),'retained_triangles':len(retained),
            'removed_triangles':int((~keep).sum()),'bounds_identical':True,
            'fit_matrix':list(map(float,fit_matrix)), 'camera_distance':3,'eye_range':[-.2,.2],
            'scope':'Only static Root-sibling geometry; every removed face is back-facing for both extreme eyes and all interpolated eyes. Requires unchanged production fit at display aspect .625.'}
    return compact,n[used],c[used],remap[retained].reshape(-1),report
