"""Read exact input normalization from TFLite M001 metadata, using its official schema."""
import sys, zipfile, struct, json
from pathlib import Path
sys.path.insert(0,'E:/tripo/native-tools/python-model-tools')
import tflite
from flatbuffers.table import Table

def vector_table(t, field, index=0):
    o=t.Offset(4+2*field)
    if not o or index>=t.VectorLen(o): return None
    return Table(t.Bytes,t.Indirect(t.Vector(o)+index*4))

def normalization(data):
    model=tflite.Model.GetRootAsModel(data,0)
    for i in range(model.MetadataLength()):
        md=model.Metadata(i)
        if md.Name()!=b'TFLITE_METADATA': continue
        raw=bytes(model.Buffers(md.Buffer()).DataAsNumpy())
        if raw[4:8]!=b'M001': raise ValueError('Unknown metadata schema')
        root=Table(raw,struct.unpack_from('<I',raw)[0])
        sg=vector_table(root,3); tensor=vector_table(sg,2)
        units=tensor.Offset(12) # TensorMetadata.process_units is field 4.
        for j in range(tensor.VectorLen(units) if units else 0):
            unit=vector_table(tensor,4,j)
            kind=unit.Offset(4)
            if not kind or raw[unit.Pos+kind]!=1: continue
            opts=Table(raw,unit.Indirect(unit.Pos+unit.Offset(6)))
            values=[]
            for field in (0,1):
                o=opts.Offset(4+field*2)
                values.append(list(struct.unpack_from('<'+'f'*opts.VectorLen(o),raw,opts.Vector(o))))
            return dict(mean=values[0],std=values[1])
    return None

if __name__=='__main__':
    task=Path(__file__).resolve().parents[1]/'app/src/main/assets/face_landmarker.task'
    with zipfile.ZipFile(task) as archive:
        print(json.dumps({n:normalization(archive.read(n)) for n in archive.namelist() if n.endswith('.tflite')},indent=2))
