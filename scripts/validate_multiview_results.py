"""Audit >=16-view / >=30 presented FPS under complete face load, with explicit image budgets."""
import argparse
import csv
import json
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--root',type=Path,default=Path('E:/tripo/output/multiview-optimization/20261001'))
parser.add_argument('--steady',default='v20-steady-5min')
parser.add_argument('--live',default='v20-live-steady-5min')
args=parser.parse_args()
rows=[]
for path in sorted(args.root.glob('*.json')):
    data=json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(data,dict) or 'result' not in data or 'arguments' not in data: continue
    result=data['result']; render=result.get('render',{}); system=data.get('system',{}); present=data.get('presentation',{})
    rows.append(dict(run=path.stem,status=result['status'],input=data['arguments']['input'],
                     face_input=data['arguments'].get('face_input'),pitch_units=data['arguments'].get('pitch_units'),
                     views=render.get('views'),view_width=render.get('view_width'),view_height=render.get('view_height'),
                     output_width=render.get('output_width'),output_height=render.get('output_height'),
                     duration_s=result.get('elapsed_s'),presented_fps=present.get('presented_fps'),
                     presented_coverage_s=present.get('covered_duration_s'),history_gap_windows=present.get('history_gap_windows'),
                     presented_rate_is_lower_bound=present.get('rate_is_lower_bound'),
                     callback_fps=render.get('fps'),face_fps=result.get('effective_fps'),face_fraction=result.get('face_fraction'),
                     cpu_percent=system.get('cpu_percent_whole_machine'),gpu_percent=system.get('gpu_busy_percent_mean'),
                     npu_percent=system.get('npu_busy_percent_mean'),mem_available_mib_min=system.get('mem_available_mib_min'),
                     pss_mib_peak=system.get('app_pss_mib_peak'),temperature_c_max=system.get('temperature_c_max'),
                     frame_interval_p95_ms=render.get('frame_interval_p95_ms'),error=result.get('error','')))
if not rows: raise ValueError('No benchmark records found')
with (args.root/'performance-comparison.csv').open('w',encoding='utf-8-sig',newline='') as stream:
    writer=csv.DictWriter(stream,fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)

checks=[]; case_reports={}
def check(name,condition):
    checks.append(dict(name=name,passed=bool(condition)))

for kind,name in [('replay_steady',args.steady),('live_usb_steady',args.live)]:
    data=json.loads((args.root/(name+'.json')).read_text(encoding='utf-8'))
    result=data['result']; render=result.get('render',{}); present=data.get('presentation',{})
    check(name+': success and rendered frames',result['status']=='success' and not render.get('error') and render.get('frames',0)>0)
    check(name+': >=16 distinct rendered views',render.get('views',0)>=16 and render.get('triangles_per_view')==20480)
    check(name+': native final output',(render.get('output_width'),render.get('output_height'))==(1200,1920))
    check(name+': complete face outputs',result.get('landmarks')==478 and result.get('blendshapes')==52
          and result.get('face_fraction',0)>=.98 and result.get('effective_fps',0)>=9)
    check(name+': >=30 actual presented FPS',present.get('presented_fps',0)>=30)
    check(name+': full measurement coverage',present.get('covered_duration_s',0)>=result.get('elapsed_s',0)-.5
          and present.get('scope')=='SurfaceFlinger actual presentation timestamps within benchmark'
          and present.get('history_gap_windows',1)==0 and not present.get('rate_is_lower_bound',True))
    check(name+': callback and presentation agree',abs(render.get('fps',0)-present.get('presented_fps',0))<.5)
    check(name+': five minute duration',result.get('elapsed_s',0)>=299.9)
    validation=render.get('combined_verification',{})
    check(name+': all selected optimizations compared against original',validation.get('fixtures')==5
          and validation.get('views')==render.get('views') and validation.get('max_rgb_error',999)<=1
          and validation.get('alpha_mismatches',999)==0 and validation.get('rmse',999)<=.1)
    start=result['measurement_start_monotonic_ns']; end=result['measurement_end_monotonic_ns']
    stamps=sorted({stamp for point in data['samples'] for stamp in point.get('presented_timestamps_ns',[]) if start<=stamp<=end})
    minutes=[]
    for minute in range(5):
        window=[stamp for stamp in stamps if start+minute*60e9<=stamp<start+(minute+1)*60e9]
        fps=(len(window)-1)*1e9/(window[-1]-window[0]) if len(window)>2 else 0
        minutes.append(fps)
    check(name+': every one minute window >=30 FPS',min(minutes)>=30)
    if kind=='live_usb_steady':
        camera=result.get('camera',{})
        check(name+': real USB camera at >=24 FPS',data['arguments']['input']=='camera' and camera.get('capture_fps',0)>=24
              and camera.get('capture_failures',999)==0 and not camera.get('error'))
        check(name+': recorded moving face plus real USB conversion',data['arguments']['face_input']=='replay'
              and result.get('face_reference',{}).get('clip_frames')==853
              and result.get('reference_conversion',{}).get('count')==result.get('frames')
              and result.get('conversion',{}).get('count')==result.get('frames'))
    case_reports[kind]=dict(run=name,apk_sha256=data['apk_sha256'],minute_presented_fps=minutes,
                           arguments=data['arguments'],presentation=present,system=data['system'],
                           face_fps=result['effective_fps'],face_fraction=result['face_fraction'],
                           frame_interval_p95_ms=render['frame_interval_p95_ms'],
                           camera=result.get('camera'),conversion=result.get('conversion'),
                           reference_conversion=result.get('reference_conversion'),pixel_comparison=validation)

report=dict(passed=all(item['passed'] for item in checks),checks=checks,cases=case_reports,
            limitations=['Per-view image budget is explicitly reduced; final screen resolution is retained.',
                         'A diagnostic 20480-triangle/four-morph mesh is tested; the production avatar remains unmeasured.',
                         'Recorded NV21 replay is additional test load, not product working memory or MP4 decoding.',
                         'Pitch units and optical alignment remain unconfirmed; each case records its interpretation.',
                         'NPU is not used by this complete face pipeline.'])
(args.root/'goal-validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(dict(passed=report['passed'],checks=len(checks),failed=[item['name'] for item in checks if not item['passed']],
                     cases={key:dict(run=value['run'],minute_fps=value['minute_presented_fps']) for key,value in case_reports.items()})))
if not report['passed']: raise SystemExit(1)
