"""Export comparable case metrics and a compact, scope-explicit result summary."""
import csv,json
from pathlib import Path
root=Path('E:/tripo/output/hardware-optimization/20261001')
rows=[]
for path in sorted(root.glob('*.json')):
    data=json.loads(path.read_text(encoding='utf-8-sig'))
    if 'arguments' not in data or 'result' not in data: continue
    result=data['result']; metrics=data['system']; render=result.get('render',{}); presentation=data.get('presentation',{})
    rows.append(dict(case=path.stem,input=result.get('input'),condition=result.get('condition'),delegate=result.get('delegate'),
        seconds=result.get('elapsed_s'),face_fps=result.get('effective_fps'),face_coverage=result.get('face_fraction'),
        conversion_ms=result.get('conversion',{}).get('mean_ms'),inference_ms=result.get('inference',{}).get('mean_ms'),
        render_callback_fps=render.get('fps'),observed_present_fps=presentation.get('presented_fps'),
        presentation_scope=presentation.get('scope'),history_gap_windows=presentation.get('history_gap_windows'),
        views=render.get('views'),output_width=render.get('output_width'),output_height=render.get('output_height'),
        view_width=render.get('view_width'),view_height=render.get('view_height'),
        calibration=render.get('calibration'),sync=render.get('sync_mode'),conversion_backend=result.get('conversion_backend'),
        packing_backend=result.get('packing_backend'),cpu_whole_machine_percent=metrics.get('cpu_percent_whole_machine'),
        gpu_busy_percent=metrics.get('gpu_busy_percent_mean'),npu_busy_percent=metrics.get('npu_busy_percent_mean'),
        app_pss_mib_peak=metrics.get('app_pss_mib_peak'),available_mib_min=metrics.get('mem_available_mib_min'),
        temperature_c_max=metrics.get('temperature_c_max'),apk_sha256=data.get('apk_sha256')))
with (root/'case-summary.csv').open('w',newline='',encoding='utf-8-sig') as stream:
    writer=csv.DictWriter(stream,fieldnames=rows[0].keys()); writer.writeheader(); writer.writerows(rows)
steady=json.loads((root/'panel10-replay-gpu4-steady.json').read_text())
result=steady['result']; begin=result['measurement_start_monotonic_ns']; end=result['measurement_end_monotonic_ns']
stamps=sorted(set(t for point in steady['samples'] for t in point.get('presented_timestamps_ns',[]) if begin<=t<=end))
def period_fps(left,right):
    values=[t for t in stamps if left<=t<right]
    return (len(values)-1)*1e9/(values[-1]-values[0]) if len(values)>1 else 0
summary=dict(status='measured',pitch=10,tan=.2777777,pitch_units='pixels assumed; awaiting confirmation',
    workload='1200x1920 output; 600x960 per view; 20480 triangles/8 draws/4 morph attributes per view; full 478/52 face task',
    recording='60 seconds original, 35 seconds stable trim; locally retained without audio',
    steady=dict(seconds=result['elapsed_s'],face_fps=result['effective_fps'],coverage=result['face_fraction'],
                present_fps=steady['presentation']['presented_fps'],first_minute_fps=period_fps(begin,begin+60_000_000_000),
                last_minute_fps=period_fps(end-60_000_000_000,end),system=steady['system'],presentation=steady['presentation']),
    scope_notes=['Replay excludes USB acquisition and decode; its PSS includes up to 374.85 MiB of reclaimable recording pages.',
                 'Live joint control uses real USB acquisition/conversion and a fixed official portrait for inference.',
                 'NPU classifier validation is not full Face Landmarker acceleration.',
                 'Final character materials/mesh and optical panel alignment remain untested.'])
(root/'optimization-summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
print(json.dumps(summary))
