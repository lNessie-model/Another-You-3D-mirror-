"""Compare full geometry/morph correspondence across Boolean vertex renumbering.

Usage: python scripts/compare_builtin_avatar.py old.glb rebuilt.glb --report report.json
No rendered image similarity, tolerance, or vertex reordering can hide changed
positions, colors, morph deltas, winding, nodes, materials, or target names.
Normals are recomputed from topology and independently checked by the asset inspector.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import struct

import numpy as np


def read(path):
    raw=path.read_bytes()
    assert struct.unpack_from('<III',raw)==(0x46546c67,2,len(raw))
    length,kind=struct.unpack_from('<II',raw,12);assert kind==0x4e4f534a
    doc=json.loads(raw[20:20+length]);binary=28+length
    def array(index):
        a=doc['accessors'][index];view=doc['bufferViews'][a['bufferView']]
        dtype=np.dtype({5121:'<u1',5123:'<u2',5125:'<u4',5126:'<f4'}[a['componentType']])
        width={'SCALAR':1,'VEC3':3,'VEC4':4}[a['type']]
        return np.ndarray((a['count'],width),dtype=dtype,buffer=raw,
            offset=binary+view.get('byteOffset',0)+a.get('byteOffset',0),
            strides=(view.get('byteStride',width*dtype.itemsize),dtype.itemsize)).copy()
    results={}
    for mesh in doc['meshes']:
        assert len(mesh['primitives'])==1,'builtin profile has one primitive per mesh'
        p=mesh['primitives'][0]
        arrays=[array(p['attributes']['POSITION']),array(p['attributes']['COLOR_0'])]
        arrays.extend(array(t['POSITION']) for t in p.get('targets',[]))
        payload=np.concatenate(arrays,axis=1).astype('<f4')
        rows=[row.tobytes() for row in payload]
        hashes=[hashlib.sha256(row).digest() for row in rows]
        # A cyclic rotation keeps winding; reversing a triangle does not.
        faces=Counter(min(tuple(hashes[int(t[(i+j)%3])] for j in range(3)) for i in range(3))
                      for t in array(p['indices']).reshape(-1,3))
        results[mesh['name']]={
            'names':mesh.get('extras',{}).get('targetNames',[]),
            'vertices':Counter(rows),'triangles':faces,'material':p['material'],
            'mode':p.get('mode',4)}
    return raw,doc,results


def main():
    parser=argparse.ArgumentParser();parser.add_argument('reference',type=Path);parser.add_argument('rebuilt',type=Path)
    parser.add_argument('--report',type=Path,required=True);args=parser.parse_args()
    ar,ad,a=read(args.reference);br,bd,b=read(args.rebuilt)
    assert a.keys()==b.keys()
    for key in ('nodes','scenes','scene','materials'):
        assert ad.get(key)==bd.get(key),(key,'semantic metadata differs')
    report={'passed':True,'referenceSha256':hashlib.sha256(ar).hexdigest(),
        'rebuiltSha256':hashlib.sha256(br).hexdigest(),'fileByteIdentical':ar==br,
        'comparison':'exact float32 position/color/all morph row multiset and corresponding oriented triangle multiset; nodes/materials/target names unchanged',
        'meshes':{}}
    for name in a:
        for key in ('names','vertices','triangles','material','mode'):
            assert a[name][key]==b[name][key],(name,key,'geometry differs')
        report['meshes'][name]={'positionColorMorphRowsEqual':True,'orientedCorrespondingTrianglesEqual':True}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))


if __name__=='__main__':main()
