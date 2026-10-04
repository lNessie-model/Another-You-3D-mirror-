"""Verify comparable workloads, output coverage and actual presentation evidence."""
import json,math
from pathlib import Path
root=Path('E:/tripo/output/hardware-optimization/20261001')
checked=[]
for path in sorted(root.glob('*.json')):
    data=json.loads(path.read_text(encoding='utf-8-sig'))
    if 'arguments' not in data or 'result' not in data: continue
    result=data['result']; arguments=data['arguments']
    assert result['status']=='success',path.name
    assert len(data['apk_sha256'])==64,path.name
    assert data['system']['samples']>=3,path.name
    assert 0<=data['system']['cpu_percent_whole_machine']<=100,path.name
    if arguments['delegate']!='NONE':
        assert result['landmarks']==478 and result['blendshapes']==52,path.name
        assert result['face_fraction']>=.98,path.name
    if arguments['render']!='none':
        render=result['render']
        assert (render['output_width'],render['output_height'])==(1200,1920),path.name
        assert (render['view_width'],render['view_height'])==(600,960),path.name
        assert render['views']==arguments['views'] and render['error']=='',path.name
        assert render['triangles_per_view']==20480 and render['draw_calls_per_view']==8,path.name
        if arguments.get('verify_lookup'):
            assert render['lookup_verification_byte_mismatches']==0,path.name
        presentation=data.get('presentation',{})
        if result.get('measurement_start_monotonic_ns'):
            assert 'within benchmark' in presentation['scope'],path.name
            assert presentation['covered_duration_s']>=result['elapsed_s']-2,path.name
            # A busy CPU can delay old sampler snapshots beyond the 127-frame
            # history. Do not assert callback counts equal the observed display
            # samples; display rate can also be lower due to compositor drops.
            assert 0<presentation['presented_fps']<=render['fps']*1.03,path.name
    assert all(math.isfinite(value) for value in result.get('conversion_samples_ms',[])),path.name
    checked.append(path.name)
colors=json.loads((root/'conversion-check.json').read_text(encoding='utf-8-sig'))
assert colors['status']=='success' and len(colors['frames'])==17
assert all(row['max_rgb_error']<=1 and row['alpha_mismatches']==0 for row in colors['frames'])
packing=json.loads((root/'packing-check.json').read_text(encoding='utf-8-sig'))
assert packing['status']=='success' and packing['live_frames_checked']==24 and packing['nv21_byte_mismatches']==0
npu=json.loads((root/'npu-probe.json').read_text(encoding='utf-8-sig'))
assert npu['result']['status']=='success' and npu['npu_busy_percent_peak']>0
fallback=json.loads((root/'replay-npu-verified.json').read_text(encoding='utf-8-sig'))
assert fallback['system']['npu_busy_percent_peak']==0
assert not fallback['accelerator_verification']['hardware_activity_observed']
app_npu=json.loads((root/'npu-app-probe.json').read_text(encoding='utf-8-sig'))
assert app_npu['result']['status']=='success' and app_npu['result']['uid']>=10000
assert app_npu['npu_busy_percent_peak']>0 and app_npu['result']['top_class']==156 and app_npu['result']['score']>.9
report=dict(status='passed',cases=checked,color_check='max RGB error 1/255; alpha identical',
            packing_check='24 live frames byte-identical; known fixtures and bounds checks passed',
            npu_check='RKNN classifier hardware activity observed from shell and ordinary app; MediaPipe NPU enum has no NPU activity')
(root/'validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report))
