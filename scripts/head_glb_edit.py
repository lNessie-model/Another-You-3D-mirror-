"""Append local asset edits while retaining the source binary and all embedded map bytes."""
import copy
import json
from pathlib import Path
import struct

import numpy as np
from tripo_head_adapter import Glb


class GlbEdit:
    def __init__(self,path):
        self.source=Glb(path);self.doc=copy.deepcopy(self.source.doc)
        length=self.doc['buffers'][0]['byteLength']
        self.binary=bytearray(self.source.raw[self.source.start:self.source.start+length])

    def add(self,values,kind):
        values=np.asarray(values,dtype='<u2' if kind=='SCALAR' else '<f4')
        assert len(values)>0 and np.isfinite(values).all()
        while len(self.binary)%4:self.binary.append(0)
        view=len(self.doc['bufferViews']);self.doc['bufferViews'].append({'buffer':0,'byteOffset':len(self.binary),'byteLength':values.nbytes})
        self.binary.extend(values.tobytes())
        accessor={'bufferView':view,'componentType':5123 if kind=='SCALAR' else 5126,'count':len(values),'type':kind}
        if kind=='VEC3':accessor.update(min=values.min(axis=0).tolist(),max=values.max(axis=0).tolist())
        index=len(self.doc['accessors']);self.doc['accessors'].append(accessor);return index

    def write(self,path):
        path=Path(path);assert not path.exists()
        self.doc['buffers']=[{'byteLength':len(self.binary)}]
        while len(self.binary)%4:self.binary.append(0)
        encoded=json.dumps(self.doc,separators=(',',':')).encode();encoded+=b' '*((-len(encoded))%4)
        path.write_bytes(struct.pack('<III',0x46546c67,2,28+len(encoded)+len(self.binary))+
                struct.pack('<II',len(encoded),0x4e4f534a)+encoded+struct.pack('<II',len(self.binary),0x004e4942)+self.binary)
