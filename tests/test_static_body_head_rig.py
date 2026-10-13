"""Deterministic geometry contract for the fixed-body/head-neck candidate."""
from pathlib import Path
import json, struct, sys
import numpy as np

def read_glb(path):
    raw=Path(path).read_bytes(); magic,version,total=struct.unpack_from('<III',raw)
    assert (magic,version,total)==(0x46546c67,2,len(raw)); n=struct.unpack_from('<I',raw,12)[0]
    doc=json.loads(raw[20:20+n]); start=28+n
    def read(index):
        a=doc['accessors'][index]; v=doc['bufferViews'][a['bufferView']]
        width={'SCALAR':1,'VEC2':2,'VEC3':3}[a['type']]; dtype=np.dtype({5123:'<u2',5125:'<u4',5126:'<f4'}[a['componentType']])
        stride=v.get('byteStride',width*dtype.itemsize); off=start+v.get('byteOffset',0)+a.get('byteOffset',0)
        return np.ndarray((a['count'],width),dtype=dtype,buffer=raw,offset=off,strides=(stride,dtype.itemsize)).copy()
    return doc,read

def rot(axis,degrees):
    a=np.deg2rad(degrees); c,s=np.cos(a),np.sin(a); r=np.eye(3)
    if axis=='pitch': r=np.array([[1,0,0],[0,c,-s],[0,s,c]])
    elif axis=='yaw': r=np.array([[c,0,s],[0,1,0],[-s,0,c]])
    else: r=np.array([[c,-s,0],[s,c,0],[0,0,1]])
    return r

def main(path):
    path=Path(path); doc,read=read_glb(path); manifest=json.loads(path.with_name('avatar.json').read_text('utf-8'))
    cut=float((path.parent/'binding-report.json').exists() and json.loads((path.parent/'binding-report.json').read_text('utf-8'))['cutY'])
    pivot=np.asarray(manifest['coordinates']['headPivot'],dtype=float)
    body=read(doc['meshes'][0]['primitives'][0]['attributes']['POSITION']).astype(float)
    head=read(doc['meshes'][1]['primitives'][0]['attributes']['POSITION']).astype(float)
    morphs=[read(t['POSITION']).astype(float) for t in doc['meshes'][1]['primitives'][0]['targets']]
    names=doc['meshes'][1]['extras']['targetNames']; assert len(names)==len(morphs)==9
    boundary=np.where(np.abs(head[:,1]+pivot[1]-cut)<1e-5)[0]; assert len(boundary)>0
    limits={row['axis']:float(row['maxDegrees']) for row in manifest['headPoseCorrections'] if row['sign']==1}
    errors=[]
    for row in manifest['headPoseCorrections']:
        axis,sign=row['axis'],int(row['sign']); limit=float(row['maxDegrees']); target=names.index(row['target'])
        R=rot(axis,sign*limit); weight=1.0
        world=pivot+(head+weight*morphs[target])@R.T
        # For a single-axis correction, the cut ring must remain on the static body.
        errors.append({'axis':axis,'sign':sign,'maxBoundaryError':float(np.max(np.linalg.norm(world[boundary]-(pivot+head[boundary]),axis=1)))})
    report={'bodyFixed':True,'bodyVertices':len(body),'headVertices':len(head),'boundaryVertices':len(boundary),'axisChecks':errors,
            'maxBoundaryError':max(row['maxBoundaryError'] for row in errors),'scope':'Numeric ring continuity and fixed-body contract; no visual/artist acceptance.'}
    assert report['maxBoundaryError']<2e-6,report
    (path.parent/'pose-geometry-check.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n','utf-8')
    print(json.dumps(report,ensure_ascii=False))

if __name__=='__main__': main(sys.argv[1])
