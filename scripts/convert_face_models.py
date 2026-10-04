"""Compile the exact MediaPipe models with RKNN 1.3; conversion is not pipeline validation."""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--model',choices=['face_detector','face_landmarks_detector','face_blendshapes'],required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
args.output.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(root/'app/src/main/assets/face_landmarker.task') as archive:
    data=archive.read(args.model+'.tflite')
source=args.output/(args.model+'.tflite')
source.write_bytes(data)
from rknn.api import RKNN
compiler=RKNN(verbose=True,verbose_file=str(args.output/(args.model+'-compile.log')))
report=dict(model=args.model,source_sha256=hashlib.sha256(data).hexdigest(),
            target='rk3566',toolkit='1.3.0_11912b58',quantization=False,
            scope='Compilation only; normalization, outputs, accuracy and Android face pipeline unverified')
try:
    for step,call in [('config',lambda:compiler.config(target_platform='rk3566',float_dtype='float16')),
                      ('load',lambda:compiler.load_tflite(model=str(source))),
                      ('build',lambda:compiler.build(do_quantization=False)),
                      ('export',lambda:compiler.export_rknn(str(args.output/(args.model+'.rknn'))))]:
        code=call()
        report[step]=code
        if code: raise RuntimeError(f'{step} returned {code}')
    report['status']='compiled'
except Exception as error:
    report['status']='error'; report['error']=str(error)
    raise
finally:
    compiler.release()
    (args.output/(args.model+'-conversion.json')).write_text(json.dumps(report,indent=2),encoding='utf-8')
