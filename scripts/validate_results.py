"""Independently check saved on-device metrics against their raw samples."""
import json
import math
from device_profile import OUT

def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values)*fraction)-1)] if values else 0

checked=[]
for path in sorted(OUT.glob('*.json')):
    payload=json.loads(path.read_text(encoding='utf-8'))
    if 'arguments' not in payload or 'result' not in payload: continue
    result=payload['result']
    assert result['status']=='success', (path.name,result)
    timings=result.get('inference_samples_ms')
    if timings is None:
        assert path.stem in ['mediapipe-cpu-pilot','mediapipe-gpu-pilot'],path.name
    else:
        assert len(timings)==result['frames'], path.name
        assert math.isclose(result['p95_ms'],percentile(timings,.95),rel_tol=1e-8,abs_tol=1e-8),path.name
    assert math.isclose(result['effective_fps'],result['frames']/result['elapsed_s'],rel_tol=1e-8,abs_tol=1e-8),path.name
    if result['delegate']!='NONE':
        assert result['frames']>0 and result['face_frames']==result['frames'],path.name
        assert result['landmarks']==478 and result['blendshapes']==52,path.name
    if 'render' in result:
        render=result['render']
        assert render['error']=='',path.name
        assert (render['output_width'],render['output_height'])==(1200,1920),path.name
        work=render['frame_work_samples_ms']
        interval=render['frame_interval_samples_ms']
        assert len(work)==render['frames'] and len(interval)==len(work)-1,path.name
        assert math.isclose(render['fps'],len(interval)*1000/sum(interval),rel_tol=1e-7),path.name
        assert math.isclose(render['frame_work_p95_ms'],percentile(work,.95),rel_tol=1e-8),path.name
    checked.append(dict(file=path.name,raw_inference_samples_verified=timings is not None))
report=dict(checked_files=checked,count=len(checked),status='passed')
(OUT/'results-validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report),flush=True)
