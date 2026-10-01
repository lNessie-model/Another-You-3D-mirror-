"""Run the original TFLite models on identical real-face RKNN input tensors (WSL TF2.2)."""
import argparse,json
from pathlib import Path
import numpy as np
import tensorflow as tf
parser=argparse.ArgumentParser()
parser.add_argument('--fixtures',type=Path,required=True)
parser.add_argument('--models',type=Path,required=True)
args=parser.parse_args()
rows=[]
for folder in sorted(args.fixtures.glob('frame-*')):
    for model in ('face_detector','face_landmarks_detector'):
        fixture=folder/(model+'-outputs.json')
        if not fixture.exists(): continue
        actual=json.loads(fixture.read_text())
        interpreter=tf.lite.Interpreter(model_path=str(args.models/(model+'.tflite')))
        interpreter.allocate_tensors()
        input_info=interpreter.get_input_details()[0]
        data=np.fromfile(folder/(model+'-input.f32'),dtype='<f4').reshape(input_info['shape'])
        interpreter.set_tensor(input_info['index'],data); interpreter.invoke()
        expected={v['name']:interpreter.get_tensor(v['index']).reshape(-1) for v in interpreter.get_output_details()}
        outputs=[]
        for meta,values in zip(actual['info']['outputs'],actual['values']):
            reference=expected[meta['name']]; candidate=np.asarray(values,dtype=np.float32)
            if reference.shape!=candidate.shape or not np.all(np.isfinite(candidate)): raise ValueError('Invalid output')
            delta=candidate-reference
            outputs.append(dict(name=meta['name'],elements=len(candidate),rmse=float(np.sqrt(np.mean(delta**2))),
                                mean_absolute_error=float(np.mean(np.abs(delta))),max_absolute_error=float(np.max(np.abs(delta))),
                                reference_min=float(reference.min()),reference_max=float(reference.max())))
        rows.append(dict(frame=folder.name,model=model,outputs=outputs))
report=dict(scope='Original TFLite vs device RKNN FP16 on byte-identical real-face input tensors',samples=rows)
(args.fixtures/'tensor-comparison.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
