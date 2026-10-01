"""Package pinned RKNN models and exact CPU postprocessing assets from the task ZIP."""
import argparse,hashlib,shutil,zipfile,json
from pathlib import Path
parser=argparse.ArgumentParser()
parser.add_argument('--detector',type=Path,required=True)
parser.add_argument('--landmarks',type=Path,required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]; assets=root/'app/src/main/assets'
report={}
expected={'face_detector.rknn':'ae6adedcb63bc8100acde16db9d1044297687e569fcd6ac4a0c4e6c0c82d58a2',
          'face_landmarks_detector.rknn':'c7005608b4c2398d48f023d4acc03089dbffe5a81686a4adfa210c0be73f6495',
          'face_blendshapes.tflite':'4f36dded049db18d76048567439b2a7f58f1daabc00d78bfe8f3ad396a2d2082',
          'geometry_pipeline_metadata_landmarks.binarypb':'bdbcda96dfcb7da883da124aaa2c55dee49770d934f0fcc71747f8c21bdc75b4'}
for name,source in [('face_detector.rknn',args.detector),('face_landmarks_detector.rknn',args.landmarks)]:
    if not source.is_file(): raise FileNotFoundError(source)
    if hashlib.sha256(source.read_bytes()).hexdigest()!=expected[name]: raise ValueError('Unverified RKNN model '+name)
    shutil.copyfile(source,assets/name)
    report[name]=hashlib.sha256((assets/name).read_bytes()).hexdigest()
with zipfile.ZipFile(assets/'face_landmarker.task') as archive:
    for name in ('face_blendshapes.tflite','geometry_pipeline_metadata_landmarks.binarypb'):
        data=archive.read(name)
        if hashlib.sha256(data).hexdigest()!=expected[name]: raise ValueError('Unexpected task asset '+name)
        (assets/name).write_bytes(data); report[name]=hashlib.sha256(data).hexdigest()
print(json.dumps(report,indent=2))
