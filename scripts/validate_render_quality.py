"""Completion audit of the exact render-quality goal, real simultaneous load and recovery."""
import argparse,csv,json
from pathlib import Path
from PIL import Image
parser=argparse.ArgumentParser(); parser.add_argument('--root',type=Path,required=True)
parser.add_argument('--name',choices=['quality-native17-depth-5min','quality-native20-depth-5min'],default='quality-native17-depth-5min')
args=parser.parse_args()
name=args.name; data=json.loads((args.root/(name+'.json')).read_text(encoding='utf-8'))
result=data['result']; render=result['render']; system=data['system']; present=data['presentation']; checks=[]
def check(label,condition): checks.append(dict(name=label,passed=bool(condition)))
check('Completed benchmark and no GL errors',result['status']=='success' and not render['error'])
check('Exact 1920x1200 interlaced output',(render['output_width'],render['output_height'])==(1920,1200))
check('Exact 720x400 per-view targets',(render['view_width'],render['view_height'])==(720,400))
check('20 views satisfy at least 16 viewpoints',render['views']==20)
check('Unchanged mesh, materials and morph workload',render['triangles_per_view']==20480 and render['draw_calls_per_view']==8 and render['morph_targets']==4)
check('Every frame still submits all views',render['submitted_scene_draw_calls_per_frame']==40 and render['view_submission'].startswith('OVR_multiview2'))
check('Native RGBA8 array storage',render['view_storage']=='RGBA8 texture array')
check('Per-view pixel budget increases from 240x720 by two thirds',render['view_width']*render['view_height']==240*720*5//3)
check('Unused window depth removed',render['window_depth_bits']==0)
check('Completed offscreen depth writes discarded',render['depth_store_discarded'])
for key in ('combined_verification','depth_discard_verification'):
    proof=render.get(key,{})
    check(key+': full image equivalence at five poses',proof.get('fixtures')==5 and proof.get('views')==20
          and proof.get('rgb_byte_mismatches')==0 and proof.get('alpha_mismatches')==0 and proof.get('max_rgb_error')==0)
check('Unhelpful shared-phase experiment excluded',not render['shared_rgb_phase_index'])
check('Actual presented average >=30 FPS',present['presented_fps']>=30)
check('Five minute simultaneous load',result['elapsed_s']>=299.9)
check('Complete actual presentation coverage',present['covered_duration_s']>=result['elapsed_s']-.5
      and present['history_gap_windows']==0 and not present['rate_is_lower_bound'])
start=result['measurement_start_monotonic_ns']; end=result['measurement_end_monotonic_ns']
stamps=sorted({t for point in data['samples'] for t in point.get('presented_timestamps_ns',[]) if start<=t<=end})
minutes=[]
for minute in range(5):
    values=[t for t in stamps if start+minute*60e9<=t<start+(minute+1)*60e9]
    rate=(len(values)-1)*1e9/(values[-1]-values[0]) if len(values)>2 else 0
    minutes.append(rate); check('Minute '+str(minute+1)+' actual display >=30 FPS',rate>=30)
check('Full 478-point/52-expression face output',result['landmarks']==478 and result['blendshapes']==52 and result['face_fraction']>=.98)
check('Complete face throughput retains >=16 FPS',result['face_frames']/result['elapsed_s']>=16)
check('Every accepted face has original CPU postprocessing',result['npu_pipeline']['post_calls']==result['face_frames'])
check('Actual NPU hardware dispatch',data['accelerator_verification']['hardware_activity_observed'] and system['npu_busy_percent_mean']>0)
check('Native runtime and factory driver preserved',result['npu_pipeline']['mesh']['runtime'].startswith('1.3.0') and result['npu_pipeline']['mesh']['driver']=='0.7.2')
check('Bounded GPU and CPU stages',render['max_frames_in_flight']==2 and result['npu_pipeline']['max_pending_cpu_jobs']==1)
check('Real USB camera >=24 FPS with no capture failures',result['camera']['capture_fps']>=24 and result['camera']['capture_failures']==0)
check('Both live USB and recorded face conversions included',result['conversion']['count']==result['frames'] and result['reference_conversion']['count']==result['frames'] and result['face_reference']['clip_frames']==853)
check('Dynamic native UI present throughout',result['ui_load']['draws']>=2000 and result['ui_load']['draw_fps']>=7)
check('At least 850 MiB memory remains available',system['mem_available_mib_min']>=850)
with Image.open(args.root/(name+'.png')) as screenshot: check('Actual captured screen is 1920x1200',screenshot.size==(1920,1200))
rotation=json.loads((args.root/(name+'-rotation.json')).read_text(encoding='utf-8'))
check('Native display rotated for exact goal dimensions',rotation['requested_rotation']==1 and 'cur=1920x1200' in rotation['active_display'])
check('Original rotation preferences restored',rotation['restored'] and rotation['final_preferences']==rotation['original'])
regression=json.loads((args.root/'npu-pipeline-check.json').read_text(encoding='utf-8'))
check('Ordered 478 points, 52 expressions and 16 pose values unchanged',regression['status']=='success' and regression['frames']==24 and regression['complete']==22
      and regression['ordered_callbacks'] and regression['blank_and_recovery']
      and all(regression[key]==0 for key in ('landmark_max_absolute_error','blendshape_max_absolute_error','pose_max_absolute_error')))
health=json.loads((args.root/'post-test-health.json').read_text(encoding='utf-8'))
check('ADB, launcher, settings, firmware, vendor library and recording healthy',health['passed'])
check('Installed APK is the exact measured version',health['installed_apk_sha256']==data['apk_sha256'])
row=dict(run=name,seconds=result['elapsed_s'],output='1920x1200',per_view='720x400',views=20,display_fps=present['presented_fps'],
         minute_fps=minutes,face_fps=result['effective_fps'],face_fraction=result['face_fraction'],camera_fps=result['camera']['capture_fps'],
         cpu_percent=system['cpu_percent_whole_machine'],gpu_percent=system['gpu_busy_percent_mean'],npu_percent=system['npu_busy_percent_mean'],
         memory_available_min_mib=system['mem_available_mib_min'],pss_peak_mib=system['app_pss_mib_peak'],temperature_max_c=system['temperature_c_max'],
         ui_draw_fps=result['ui_load']['draw_fps'],apk_sha256=data['apk_sha256'])
row['complete_face_fps']=result['face_frames']/result['elapsed_s']
row['face_latency_mean_ms']=result['npu_pipeline']['completed_face_latency_mean_ms']
row['npu_mesh_mean_ms']=result['npu_pipeline']['mesh_mean_ms']
row['cpu_post_mean_ms']=result['npu_pipeline']['post_mean_ms']
report=dict(passed=all(x['passed'] for x in checks),checks=checks,result=row,
            requirement_interpretation='Width x height: 1920x1200 output and 720x400 per viewpoint; >=16 views and >=30 FPS retained from prior user request.',
            limitations=['Diagnostic deforming mesh; production character is not measured.',
                         'Live USB capture/decode plus local moving-face replay and its extra conversion; no MP4 decode in performance load.',
                         'Native text/96-bar UI fixture at ~8.5 Hz; complex future pages remain unmeasured.',
                         '375 MiB NV21 reference mapping contributes recyclable resident PSS.',
                         'Five-minute validation; optical alignment and long-term enclosed thermal behavior are not certified.'])
report_text=json.dumps(report,indent=2)
(args.root/(name+'-validation.json')).write_text(report_text,encoding='utf-8')
(args.root/'render-quality-validation.json').write_text(report_text,encoding='utf-8')
with (args.root/'render-quality-performance.csv').open('w',encoding='utf-8-sig',newline='') as file:
    writer=csv.DictWriter(file,fieldnames=list(row)); writer.writeheader(); writer.writerow(row)
print(json.dumps(dict(passed=report['passed'],checks=len(checks),failed=[x['name'] for x in checks if not x['passed']],result=row)))
if not report['passed']: raise SystemExit(1)
