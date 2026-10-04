"""Audit useful NPU throughput, bounded scheduling and sustained display/UI performance."""
import argparse,csv,json
from pathlib import Path

parser=argparse.ArgumentParser(); parser.add_argument('--root',type=Path,required=True); args=parser.parse_args()
checks=[]; output=[]; cases={}
def check(name,condition): checks.append(dict(name=name,passed=bool(condition)))
names=['npu-ui-reference10-60s','npu-ui-serial20-60s','npu-ui-pipeline15-180s','npu-ui-pipeline20-300s',
       'npu-ui-pipeline17-300s','npu-ui-pipeline-capacity-60s']
if (args.root/'npu-ui-pipeline15-300s.json').exists(): names.append('npu-ui-pipeline15-300s')
for name in names:
    data=json.loads((args.root/(name+'.json')).read_text(encoding='utf-8')); cases[name]=data
    result=data['result']; render=result['render']; system=data['system']; present=data['presentation']; pipeline=result['npu_pipeline']
    check(name+': success',result.get('status')=='success' and not render.get('error'))
    check(name+': same 20-view image budget',render['views']==20 and (render['view_width'],render['view_height'])==(240,720)
          and (render['output_width'],render['output_height'])==(1200,1920) and render['triangles_per_view']==20480)
    check(name+': complete face load',result['face_fraction']>=.98 and result['landmarks']==478 and result['blendshapes']==52
          and pipeline['post_calls']==result['face_frames'])
    check(name+': actual NPU hardware activity',system.get('npu_busy_percent_mean',0)>0
          and data['accelerator_verification']['hardware_activity_observed'])
    check(name+': real USB capture',result['camera']['capture_fps']>=24 and result['camera']['capture_failures']==0)
    check(name+': both frame conversions included',result['conversion']['count']==result['frames']
          and result['reference_conversion']['count']==result['frames'])
    check(name+': complete display history',present['covered_duration_s']>=result['elapsed_s']-.5
          and present['history_gap_windows']==0 and not present['rate_is_lower_bound'])
    check(name+': bounded CPU postprocessing',pipeline['max_pending_cpu_jobs']==int(data['arguments']['npu_pipeline']))
    check(name+': dynamic UI present',result['ui_load']['draws']>300 and result['ui_load']['draw_fps']>=7)
    check(name+': memory headroom >=750 MiB',system['mem_available_mib_min']>=750)
    start=result['measurement_start_monotonic_ns']; end=result['measurement_end_monotonic_ns']
    stamps=sorted({stamp for point in data['samples'] for stamp in point.get('presented_timestamps_ns',[]) if start<=stamp<=end})
    minutes=[]
    for minute in range(int(result['elapsed_s']//60)):
        window=[stamp for stamp in stamps if start+minute*60e9<=stamp<start+(minute+1)*60e9]
        minutes.append((len(window)-1)*1e9/(window[-1]-window[0]) if len(window)>2 else 0)
    qualifies=present['presented_fps']>=30 and bool(minutes) and min(minutes)>=30
    if name in names[:3]: check(name+': display target',qualifies)
    output.append(dict(run=name,seconds=result['elapsed_s'],requested_face_fps=result['analysis_target_fps'],
                       pipelined=result['npu_pipeline_enabled'],display_fps=present['presented_fps'],
                       face_fps=result['effective_fps'],full_face_result_fps=result['face_frames']/result['elapsed_s'],
                       cpu_percent=system['cpu_percent_whole_machine'],gpu_percent=system['gpu_busy_percent_mean'],
                       npu_percent=system['npu_busy_percent_mean'],memory_available_min_mib=system['mem_available_mib_min'],
                       pss_peak_mib=system['app_pss_mib_peak'],temperature_max_c=system['temperature_c_max'],
                       npu_mesh_mean_ms=pipeline['mesh_mean_ms'],cpu_post_mean_ms=pipeline['post_mean_ms'],
                       face_completion_mean_ms=pipeline['completed_face_latency_mean_ms'],minute_display_fps=minutes,
                       meets_display_target=qualifies,
                       apk_sha256=data['apk_sha256']))
check('Same APK across final comparisons',len({d['apk_sha256'] for d in cases.values()})==1)
baseline=cases[names[0]]; high=cases[names[3]]; serial=cases[names[1]]; balanced=cases[names[2]]
check('Boundary test demonstrates useful face throughput >=1.7x baseline',high['result']['effective_fps']>=baseline['result']['effective_fps']*1.7)
check('Boundary test increases NPU duty >=10 percentage points',high['system']['npu_busy_percent_mean']>=baseline['system']['npu_busy_percent_mean']+10)
check('Pipeline improves throughput >=1.2x same requested rate',high['result']['effective_fps']>=serial['result']['effective_fps']*1.2)
check('Balanced profile >=14 face FPS',balanced['result']['effective_fps']>=14)
sustained=[row for row in output if row['pipelined'] and row['seconds']>=299.9 and row['meets_display_target']]
selected=max(sustained,key=lambda row:row['full_face_result_fps']) if sustained else None
check('A higher-rate profile keeps every minute >=30 FPS for five minutes',selected is not None)
check('Selected profile improves useful throughput >=1.4x baseline',selected is not None and selected['full_face_result_fps']>=baseline['result']['effective_fps']*1.4)
check('Selected profile increases NPU duty >=5 percentage points',selected is not None and selected['npu_percent']>=baseline['system']['npu_busy_percent_mean']+5)
regression=json.loads((args.root/'npu-pipeline-check.json').read_text(encoding='utf-8'))
check('Ordered temporal outputs and no-face recovery',regression['status']=='success' and regression['frames']==24
      and regression['complete']==22 and regression['ordered_callbacks'] and regression['blank_and_recovery'])
check('478 landmarks, 52 shapes and pose numerically unchanged',all(regression[key]<=1e-5 for key in
      ['landmark_max_absolute_error','blendshape_max_absolute_error','pose_max_absolute_error']))
quality=json.loads((args.root/'quality/quality.json').read_text(encoding='utf-8'))
check('Original CPU model reference remains accurate',quality['status']=='success' and quality['complete_samples']==36
      and quality['blank_rejected'] and quality['tracked_blank_rejected'] and quality['recovered_after_blank']
      and max(x['xy_rmse_pixels'] for x in quality['samples'])<.5
      and max(x['blendshape_max_error'] for x in quality['samples'])<.05)
report=dict(passed=all(x['passed'] for x in checks),checks=checks,comparison=output,
            selected_profile=selected,
            display_target_rejections=[row['run'] for row in output if not row['meets_display_target']],
            limitations=['Same diagnostic 20-view 240x720 scene; production avatar and complex UI unmeasured.',
                         'Live USB acquisition/decode plus separately converted local recorded face input.',
                         'Higher face rate increases CPU postprocessing; NPU is not a replacement for GPU rendering.',
                         'PSS includes recyclable NV21 reference mapping pages.',
                         'Sequential tests have different thermal starting points.'])
(args.root/'headroom-validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
with (args.root/'headroom-comparison.csv').open('w',encoding='utf-8-sig',newline='') as file:
    writer=csv.DictWriter(file,fieldnames=list(output[0])); writer.writeheader(); writer.writerows(output)
print(json.dumps(dict(passed=report['passed'],checks=len(checks),failed=[x['name'] for x in checks if not x['passed']],comparison=output)))
if not report['passed']: raise SystemExit(1)
