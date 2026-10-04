"""Audit precision, real NPU activity, same-rate comparisons and sustained UI load."""
import argparse,json,csv
from pathlib import Path
parser=argparse.ArgumentParser(); parser.add_argument('--root',type=Path,required=True); args=parser.parse_args()
checks=[]; cases={}; output=[]
def check(name,condition): checks.append(dict(name=name,passed=bool(condition)))
names=['gpu-v20-paced-3min','npu-v20-paced-3min','npu-v20-ui-5min','npu-v20-capacity-1min']
for name in names:
    data=json.loads((args.root/(name+'.json')).read_text(encoding='utf-8'))
    result=data['result']; render=result.get('render',{}); system=data['system']; present=data['presentation']; cases[name]=data
    check(name+': success',result.get('status')=='success' and not render.get('error'))
    check(name+': identical 20-view image budget',render.get('views')==20 and (render.get('view_width'),render.get('view_height'))==(240,720)
          and (render.get('output_width'),render.get('output_height'))==(1200,1920) and render.get('triangles_per_view')==20480)
    check(name+': full face outputs',result.get('landmarks')==478 and result.get('blendshapes')==52
          and result.get('face_fraction',0)>=.98 and result.get('effective_fps',0)>=9)
    check(name+': actual presentation >=30 FPS',present.get('presented_fps',0)>=30)
    check(name+': complete presentation history',present.get('covered_duration_s',0)>=result.get('elapsed_s',999)-.5
          and present.get('history_gap_windows',999)==0 and not present.get('rate_is_lower_bound',True))
    check(name+': real USB capture',result['camera']['capture_fps']>=24 and result['camera']['capture_failures']==0)
    check(name+': both conversions included',result['conversion']['count']==result['frames']
          and result['reference_conversion']['count']==result['frames'] and result['face_reference']['clip_frames']==853)
    start=result['measurement_start_monotonic_ns']; end=result['measurement_end_monotonic_ns']
    stamps=sorted({stamp for point in data['samples'] for stamp in point.get('presented_timestamps_ns',[]) if start<=stamp<=end})
    minutes=[]
    for minute in range(int(result['elapsed_s']//60)):
        window=[stamp for stamp in stamps if start+minute*60e9<=stamp<start+(minute+1)*60e9]
        minutes.append((len(window)-1)*1e9/(window[-1]-window[0]) if len(window)>2 else 0)
    check(name+': every minute >=30 FPS',bool(minutes) and min(minutes)>=30)
    if data['arguments']['delegate']=='RKNN':
        check(name+': actual NPU dispatch',system.get('npu_busy_percent_mean',0)>0 and system.get('npu_busy_percent_peak',0)>0
              and data.get('accelerator_verification',{}).get('hardware_activity_observed',False))
        check(name+': every accepted face receives CPU expressions/pose',result['npu_pipeline']['post_calls']==result['face_frames'])
        check(name+': factory runtime and video smoothing',result['npu_pipeline']['mesh']['driver']=='0.7.2'
              and result['npu_pipeline']['mesh']['runtime'].startswith('1.3.0') and result['npu_pipeline']['temporal_smoothing'].startswith('MediaPipe OneEuro'))
    output.append(dict(run=name,seconds=result['elapsed_s'],render_fps=present['presented_fps'],face_fps=result['effective_fps'],
                       face_fraction=result['face_fraction'],camera_fps=result['camera']['capture_fps'],
                       cpu_percent=system['cpu_percent_whole_machine'],gpu_percent=system['gpu_busy_percent_mean'],npu_percent=system['npu_busy_percent_mean'],
                       pss_peak_mib=system['app_pss_mib_peak'],mem_available_min_mib=system['mem_available_mib_min'],
                       inference_mean_ms=result['inference']['mean_ms'],temperature_max_c=system['temperature_c_max'],
                       minute_fps=minutes,ui_draw_fps=result.get('ui_load',{}).get('draw_fps',0),apk_sha256=data['apk_sha256']))
check('Same APK used across comparisons',len({d['apk_sha256'] for d in cases.values()})==1)
gpu=cases[names[0]]['system']; npu=cases[names[1]]['system']
check('NPU frees GPU at the same frame budget',gpu['gpu_busy_percent_mean']-npu['gpu_busy_percent_mean']>=5)
ui=cases[names[2]]['result']
check('UI steady run is >=5 minutes',ui['elapsed_s']>=299.9 and ui.get('ui_load',{}).get('draws',0)>=2000)
quality=json.loads((args.root/'quality-final/quality.json').read_text(encoding='utf-8'))
rows=quality['samples']
check('All recorded reference samples have complete face output',quality['status']=='success' and quality['complete_samples']==36 and len(rows)==36)
check('No-face and recovery regressions',quality['blank_rejected'] and quality['tracked_blank_rejected'] and quality['recovered_after_blank'])
check('Landmark error below half a source pixel per frame',max(row['xy_rmse_pixels'] for row in rows)<.5)
check('All expression comparisons retain low error',max(row['blendshape_mae'] for row in rows)<.01 and max(row['blendshape_max_error'] for row in rows)<.05)
tensors=json.loads((args.root/'quality-final/tensor-comparison.json').read_text())
meshes=[row for row in tensors['samples'] if row['model']=='face_landmarks_detector']
check('Exact-input RKNN FP16 landmarks match TFLite',len(meshes)==3 and all(out['rmse']<.05 and out['max_absolute_error']<.15
      for row in meshes for out in row['outputs'] if out['name']=='Identity'))
report=dict(passed=all(row['passed'] for row in checks),checks=checks,comparison=output,
            precision=dict(samples=36,xy_rmse_pixels_mean=sum(r['xy_rmse_pixels'] for r in rows)/36,
                           xy_rmse_pixels_max=max(r['xy_rmse_pixels'] for r in rows),
                           blendshape_mae_mean=sum(r['blendshape_mae'] for r in rows)/36,
                           blendshape_max_error=max(r['blendshape_max_error'] for r in rows)),
            gpu_freed_percentage_points=gpu['gpu_busy_percent_mean']-npu['gpu_busy_percent_mean'],
            limitations=['240x720 per view; diagnostic mesh, production avatar not measured.',
                         'USB capture and native MJPEG decode run in parallel with recorded moving-face input and its extra conversion.',
                         'NV21 test clip maps 374.85 MiB; PSS includes resident test pages, not production memory requirements.',
                         'NPU runs detector and 478-point image network; CPU retains 52 blendshapes, pose and smoothing.',
                         'UI fixture is a native panel, text and animated bars; not a measurement of a future WebView.',
                         'Camera calibration and optical alignment are unverified.'])
(args.root/'npu-validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
with (args.root/'npu-performance-comparison.csv').open('w',encoding='utf-8-sig',newline='') as file:
    writer=csv.DictWriter(file,fieldnames=list(output[0])); writer.writeheader(); writer.writerows(output)
print(json.dumps(dict(passed=report['passed'],checks=len(checks),failed=[r['name'] for r in checks if not r['passed']],comparison=output),ensure_ascii=False))
if not report['passed']: raise SystemExit(1)
