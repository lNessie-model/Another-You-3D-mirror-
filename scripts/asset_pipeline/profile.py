"""Own-model coordinate evidence and calibrated square-camera projection."""
import re
import numpy as np

def validate_profile(spec):
    required={'schemaVersion','slug','sourceSha256','observedFrontAxis','heightMeters',
              'neckPivotFraction','frontEvidence','landmarkApproval'}
    if not required<=spec.keys() or spec['schemaVersion']!=1:
        raise ValueError('A complete model-specific profile is required')
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{1,63}',spec['slug']):raise ValueError('Unsafe slug')
    if not re.fullmatch('[0-9a-f]{64}',spec['sourceSha256']):raise ValueError('Missing original SHA256')
    if spec['observedFrontAxis'] not in ('+X','-X','+Z','-Z') or not spec['frontEvidence']:
        raise ValueError('Observed face orientation and evidence required')
    if not .18<=float(spec['heightMeters'])<=.32 or not 0<=float(spec['neckPivotFraction'])<=.3:
        raise ValueError('Invalid own head scale/pivot')
    if spec['landmarkApproval'] not in ('pending','accepted','rejected'):
        raise ValueError('Unknown landmark acceptance status')
    return spec

def normalize_geometry(points,normals,spec):
    validate_profile(spec)
    points=np.asarray(points,dtype=float);normals=np.asarray(normals,dtype=float)
    if points.ndim!=2 or points.shape!=normals.shape or points.shape[1]!=3 or not np.isfinite(points).all() or not np.isfinite(normals).all():
        raise ValueError('Invalid geometry')
    rotations={'+X':[[0,0,1],[0,1,0],[-1,0,0]],'-X':[[0,0,-1],[0,1,0],[1,0,0]],
               '+Z':np.eye(3),'-Z':[[-1,0,0],[0,1,0],[0,0,-1]]}
    r=np.asarray(rotations[spec['observedFrontAxis']],dtype=float)
    if not np.allclose(r.T@r,np.eye(3)) or not np.isclose(np.linalg.det(r),1):raise ValueError('Improper orientation')
    p=points@r;n=normals@r;height=np.ptp(p[:,1])
    if height<=1e-8:raise ValueError('Degenerate head height')
    lengths=np.linalg.norm(n,axis=1)
    if np.any(lengths<=1e-8):raise ValueError('Degenerate normals')
    scale=float(spec['heightMeters'])/height;p*=scale;n/=lengths[:,None]
    pivot=np.array([0,float(p[:,1].min())+float(spec['neckPivotFraction'])*float(spec['heightMeters']),0])
    return p-pivot,n,pivot,scale

def project_landmarks(report,camera):
    points=np.asarray(report.get('landmarks',[]),dtype=float)
    if report.get('faces')!=1 or points.shape!=(478,3) or not np.isfinite(points).all():
        raise ValueError('Exactly one own-model 478 point detection required')
    if not camera.get('views') or camera['views'][0].get('name')!='front':
        raise ValueError('Canonical +Z frontal square projection required')
    scale=float(camera['orthographic_scale']);center=camera['views'][0]['center']
    if not np.isfinite(scale) or scale<=0 or len(center)!=3:raise ValueError('Invalid render projection')
    return np.c_[(points[:,0]-.5)*scale+center[0],(.5-points[:,1])*scale+center[2]]
