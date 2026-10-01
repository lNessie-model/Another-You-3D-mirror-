"""Check recorded frame counts, timing distributions and queue accounting independently."""
import argparse
import json
import math
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--output-dir',type=Path,default=Path(r'E:\tripo\output\camera-benchmark\20261001'))
args=parser.parse_args()
checks=[]
for path in sorted(args.output_dir.glob('*.json')):
    data=json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(data,dict) or 'result' not in data or 'arguments' not in data: continue
    result=data['result']; metrics=data['system']; name=path.stem
    assert result['status']=='success',(name,result.get('error'))
    elapsed=result['elapsed_s']
    assert elapsed>0
    assert len(data['apk_sha256'])==64
    assert math.isclose(result['effective_fps'],result['frames']/elapsed,rel_tol=1e-9,abs_tol=1e-9),name
    raw=result['inference_samples_ms']
    assert len(raw)==result['frames'],name
    if raw:
        reported=result.get('inference',result)
        assert math.isclose(reported['mean_ms'],sum(raw)/len(raw),rel_tol=1e-9),name
        assert math.isclose(reported['p95_ms'],sorted(raw)[math.ceil(len(raw)*.95)-1],rel_tol=1e-9),name
    assert 0<=result['face_frames']<=result['frames'],name
    if result['face_frames']:
        assert result['landmarks']==478 and result['blendshapes']==52,name
    if 'camera' in result:
        camera=result['camera']
        assert camera['capture_failures']==0 and camera['error']=='',name
        assert 0<camera['capture_fps']<=result['requested_capture_fps']+1,name
        assert math.isclose(camera['capture_fps'],camera['capture_results']/elapsed,rel_tol=1e-9),name
        assert abs(result['consumed_images']+camera['pending_frames_replaced']-camera['received_images'])<=4,name
        converted=result['conversion_samples_ms']
        assert len(converted)==(result['consumed_images'] if result['conversion_enabled'] else 0),name
        if converted:
            assert math.isclose(result['conversion']['mean_ms'],sum(converted)/len(converted),rel_tol=1e-9),name
    if 'render' in result:
        render=result['render']; intervals=render['frame_interval_samples_ms']
        assert render['error']=='' and render['frames']>0,name
        assert render['output_width']==1200 and render['output_height']==1920,name
        assert len(render['frame_work_samples_ms'])==render['frames'],name
        assert len(intervals)==render['frames']-1,name
        assert math.isclose(render['fps'],1000*len(intervals)/sum(intervals),rel_tol=1e-9),name
    assert 0<=metrics['cpu_percent_whole_machine']<=100,name
    checks.append({'case':name,'status':'passed','face_fraction':result.get('face_fraction'),
                   'duration_s':elapsed,'apk_sha256':data['apk_sha256']})
output={'validated_cases':len(checks),'cases':checks}
(args.output_dir/'results-validation.json').write_text(json.dumps(output,indent=2),encoding='utf-8')
print(json.dumps(output))
