import argparse
import json
import re
import shlex
import math
import time
from pathlib import Path
from device_profile import adb, parse_sample, SAMPLE_COMMAND, summarize, OUT as DEFAULT_OUT

parser=argparse.ArgumentParser()
parser.add_argument('name')
parser.add_argument('--delegate',default='CPU',choices=['CPU','GPU','NPU','RKNN','NONE'])
parser.add_argument('--seconds',type=int,default=30)
parser.add_argument('--analysis-fps',type=int,default=0)
parser.add_argument('--render-fps',type=int,default=0)
parser.add_argument('--ui-load',action='store_true')
parser.add_argument('--npu-pipeline',action='store_true')
parser.add_argument('--crop-reference',action='store_true',help='Use the original RKNN crop for same-APK comparison')
parser.add_argument('--orientation',choices=['portrait','landscape'],default='portrait')
parser.add_argument('--view-width',type=int)
parser.add_argument('--view-height',type=int)
parser.add_argument('--discard-depth',action='store_true')
parser.add_argument('--verify-discard-depth',action='store_true')
parser.add_argument('--shared-phase',action='store_true')
parser.add_argument('--verify-shared-phase',action='store_true')
parser.add_argument('--running-mode',default='VIDEO',choices=['VIDEO','IMAGE'])
parser.add_argument('--render',default='none',choices=['none','interlace','scene'])
parser.add_argument('--views',type=int,default=4)
parser.add_argument('--scale',type=float,default=1)
parser.add_argument('--width-scale',type=float)
parser.add_argument('--screenshot',action='store_true')
parser.add_argument('--input',default='portrait',choices=['portrait','camera','replay'])
parser.add_argument('--record-id',default='face-reference-stable-20261001-01')
parser.add_argument('--replay-fps',type=float,default=24.369906558)
parser.add_argument('--preload',action='store_true')
parser.add_argument('--pipeline',action='store_true')
parser.add_argument('--cull',action='store_true')
parser.add_argument('--verify-cull',action='store_true')
parser.add_argument('--atlas',action='store_true')
parser.add_argument('--verify-atlas',action='store_true')
parser.add_argument('--atlas-copy',action='store_true')
parser.add_argument('--multiview',action='store_true')
parser.add_argument('--verify-multiview',action='store_true')
parser.add_argument('--preblend',action='store_true')
parser.add_argument('--verify-preblend',action='store_true')
parser.add_argument('--explicit-lod',action='store_true')
parser.add_argument('--verify-combined',action='store_true')
parser.add_argument('--lookup',action='store_true')
parser.add_argument('--verify-lookup',action='store_true')
parser.add_argument('--pitch',type=float)
parser.add_argument('--tan',type=float,default=.2777777)
parser.add_argument('--pitch-units',choices=['pixels','subpixels'],default='pixels')
parser.add_argument('--camera-width',type=int,default=640)
parser.add_argument('--camera-height',type=int,default=480)
parser.add_argument('--capture-fps',type=int,default=25)
parser.add_argument('--rotation',type=int,default=0)
parser.add_argument('--convert',action='store_true')
parser.add_argument('--conversion-backend',choices=['rs','rga'],default='rs')
parser.add_argument('--native-packing',action='store_true')
parser.add_argument('--profile-stages',action='store_true')
parser.add_argument('--face-input',choices=['camera','portrait','replay'],default='camera')
parser.add_argument('--output-dir',type=Path,default=DEFAULT_OUT)
args=parser.parse_args()
if not (1<=args.seconds<=600 and 1<=args.views<=32 and math.isfinite(args.scale) and 0<args.scale<=1
        and args.analysis_fps>=0 and 0<=args.render_fps<=60 and math.isfinite(args.replay_fps) and 0<args.replay_fps<=60):
    parser.error('Invalid duration, views, scale or frame rate')
if args.width_scale is not None and not (math.isfinite(args.width_scale) and 0<args.width_scale<=1):
    parser.error('Invalid horizontal view scale')
if args.pitch is not None and not (math.isfinite(args.pitch) and args.pitch>0 and math.isfinite(args.tan)):
    parser.error('Pitch must be positive and pitch/tan finite')
if args.view_width is not None or args.view_height is not None:
    if args.input not in ('camera','replay') or args.view_width is None or args.view_height is None or not (1<=args.view_width<=4096 and 1<=args.view_height<=4096):
        parser.error('Explicit view dimensions require both positive camera/replay sizes <=4096')
if args.orientation=='landscape' and args.input not in ('camera','replay'): parser.error('Landscape requires camera/replay activity')
if (args.discard_depth or args.verify_discard_depth) and args.input not in ('camera','replay'): parser.error('Depth discard requires camera/replay activity')
if args.crop_reference and (args.delegate!='RKNN' or args.input not in ('camera','replay')):
    parser.error('Reference crop requires RKNN camera/replay backend')
OUT=args.output_dir
OUT.mkdir(parents=True,exist_ok=True)
if not re.fullmatch('[a-zA-Z0-9_-]+',args.name): raise ValueError('Invalid run name')

process_names=['com.mirror.bench','android.hardware.camera.provider@2.4-external-service','cameraserver','surfaceflinger']
PROCESS_COMMAND='; '.join(f'for p in $(pidof {name}); do echo PROC {name}; cat /proc/$p/stat; done' for name in process_names)
def parse_processes(text):
    rows={}
    for name,stat in re.findall(r'PROC ([^\n]+)\n([^\n]+)',text):
        tail=stat.rsplit(')',1)[1].split()
        rows[name.strip()]=dict(pid=int(stat.split()[0]),ticks=int(tail[11])+int(tail[12]))
    return rows

package_path=adb('shell','pm path com.mirror.bench').splitlines()[0].split(':',1)[1]
apk_sha256=adb('shell','sha256sum '+shlex.quote(package_path)).split()[0]
if not re.fullmatch('[a-f0-9]{64}',apk_sha256): raise RuntimeError('Cannot read installed APK checksum')
adb('shell','am force-stop com.mirror.bench')
adb('shell',f'run-as com.mirror.bench rm -f files/{args.name}.json',check=False)
clock_before=time.time()
device_epoch=int(adb('shell','date +%s'))
device_clock_offset=device_epoch-(clock_before+time.time())/2
print('START',args.name,flush=True)
activity='CameraBenchActivity' if args.input in ('camera','replay') else 'BenchActivity'
camera_extras=(f' --ei camera_width {args.camera_width} --ei camera_height {args.camera_height}'
               f' --ei capture_fps {args.capture_fps} --ei rotation {args.rotation} --ez convert {str(args.convert).lower()}'
               f' --es face_input {args.face_input} --es input {args.input} --es record_id {shlex.quote(args.record_id)}'
               f' --ef replay_fps {args.replay_fps} --ez preload {str(args.preload).lower()}'
               f' --es conversion_backend {args.conversion_backend} --ez native_packing {str(args.native_packing).lower()}') if args.input in ('camera','replay') else ''
panel_extras=f' --ef pitch {args.pitch} --ef tan {args.tan} --es pitch_units {args.pitch_units}' if args.pitch is not None else ''
if args.width_scale is not None: panel_extras+=f' --ef width_scale {args.width_scale}'
if args.input in ('camera','replay'): panel_extras+=f' --ei render_fps {args.render_fps}'
elif args.render_fps: parser.error('Frame pacing currently requires camera/replay activity')
if args.ui_load:
    if args.input not in ('camera','replay') or args.render=='none': parser.error('UI load requires camera/replay plus rendering')
    panel_extras+=' --ez ui_load true'
if args.npu_pipeline:
    if args.delegate!='RKNN' or args.input not in ('camera','replay'): parser.error('NPU pipeline requires RKNN camera/replay backend')
    panel_extras+=' --ez npu_pipeline true'
if args.crop_reference: panel_extras+=' --ez crop_reference true'
if args.input in ('camera','replay'):
    panel_extras+=f' --es orientation {args.orientation} --ez discard_depth {str(args.discard_depth).lower()} --ez verify_discard_depth {str(args.verify_discard_depth).lower()}'
    panel_extras+=f' --ez shared_phase {str(args.shared_phase).lower()} --ez verify_shared_phase {str(args.verify_shared_phase).lower()}'
    if args.view_width is not None: panel_extras+=f' --ei view_width {args.view_width} --ei view_height {args.view_height}'
adb('shell',f'am start -n com.mirror.bench/.{activity} --es run_id {args.name} '
    f'--es delegate {args.delegate} --ei seconds {args.seconds} --ei analysis_fps {args.analysis_fps} '
    f'--es running_mode {args.running_mode} '
    f'--es render {args.render} --ei views {args.views} --ef scale {args.scale} '
    f'--ez profile_stages {str(args.profile_stages).lower()} --ez pipeline {str(args.pipeline).lower()} '
    f'--ez cull {str(args.cull).lower()} --ez verify_cull {str(args.verify_cull).lower()} '
    f'--ez atlas {str(args.atlas).lower()} --ez verify_atlas {str(args.verify_atlas).lower()} '
    f'--ez atlas_copy {str(args.atlas_copy).lower()} '
    f'--ez multiview {str(args.multiview).lower()} --ez verify_multiview {str(args.verify_multiview).lower()} '
    f'--ez preblend {str(args.preblend).lower()} --ez verify_preblend {str(args.verify_preblend).lower()} '
    f'--ez explicit_lod {str(args.explicit_lod).lower()} '
    f'--ez verify_combined {str(args.verify_combined).lower()} '
    f'--ez lookup {str(args.lookup).lower()} --ez verify_lookup {str(args.verify_lookup).lower()}'+camera_extras+panel_extras)
series=[]
deadline=time.monotonic()+args.seconds+120
result=None
last_memory_sample=0
surface_layer=''
presentation_stamps=set()
while time.monotonic()<deadline:
    memory_due=time.monotonic()-last_memory_sample>=5
    commands=[SAMPLE_COMMAND,
              "printf '\\n__MIRROR_PROC__\\n'",PROCESS_COMMAND,
              "printf '\\n__MIRROR_NPU__\\n'",'cat /sys/kernel/debug/rknpu/load']
    if memory_due:
        commands += ["printf '\\n__MIRROR_MEM__\\n'",'dumpsys meminfo com.mirror.bench',
                     "printf '\\n__MIRROR_PROVMEM__\\n'",
                     'for p in $(pidof android.hardware.camera.provider@2.4-external-service); do dumpsys meminfo $p; done']
    if args.render!='none':
        commands += ["printf '\\n__MIRROR_SURFACE__\\n'",
                     'dumpsys SurfaceFlinger --latency '+shlex.quote(surface_layer) if surface_layer else 'dumpsys SurfaceFlinger --list']
    commands += ["printf '\\n__MIRROR_RESULT__\\n'",f'run-as com.mirror.bench cat files/{args.name}.json']
    raw=adb('shell','; '.join(commands),check=False)
    sections=re.split(r'\n__MIRROR_(\w+)__\n',raw)
    parts=dict(zip(sections[1::2],sections[2::2]))
    point=parse_sample(sections[0])
    point['processes']=parse_processes(parts.get('PROC',''))
    npu_text=parts.get('NPU','')
    npu_match=re.search(r'(\d+)%',npu_text)
    if npu_match: point['npu_busy_percent']=int(npu_match[1])
    if memory_due:
        memory=parts.get('MEM','')
        match=re.search(r'TOTAL PSS:\s+(\d+)',memory)
        if match: point['app_pss_kib']=int(match.group(1))
        provider_memory=parts.get('PROVMEM','')
        match=re.search(r'TOTAL PSS:\s+(\d+)',provider_memory)
        if match: point['camera_provider_pss_kib']=int(match.group(1))
        last_memory_sample=time.monotonic()
    series.append(point)
    if args.render!='none':
        if not surface_layer:
            layers=parts.get('SURFACE','').splitlines()
            surface_layer=next((x for x in layers if x.startswith('SurfaceView') and 'com.mirror.bench' in x),'')
        else:
            trace=parts.get('SURFACE','')
            values=[]
            for line in trace.splitlines()[1:]:
                columns=line.split()
                if len(columns)==3 and all(v.isdigit() for v in columns):
                    actual=int(columns[1])
                    if 0<actual<2**63-1: values.append(actual)
            presentation_stamps.update(values)
            point['presented_timestamps_ns']=values
    text=parts.get('RESULT','')
    try:
        result=json.loads(text)
        break
    except json.JSONDecodeError: pass
    time.sleep(1)
if result is None:
    result=dict(status='timeout',run_id=args.name)
    (OUT/f'{args.name}-logcat.txt').write_text(adb('logcat','-d','-s','MirrorBench','AndroidRuntime'),encoding='utf-8')
start=result.get('measurement_start_epoch_ms',0)/1000
end=result.get('measurement_end_epoch_ms',float('inf'))/1000
measured=[x for x in series if start<=x['time']+device_clock_offset<=end]
has_measured_interval=start>0 and len(measured)>=2
if not has_measured_interval: measured=series
metrics=summarize(measured)
metrics['scope']='measured interval; startup and warmup excluded' if has_measured_interval else 'whole run including startup'
metrics['device_clock_offset_s']=device_clock_offset
pss=[x['app_pss_kib']/1024 for x in measured if 'app_pss_kib' in x]
if pss:
    metrics['app_pss_mib_peak']=max(pss)
    metrics['app_pss_mib_mean']=sum(pss)/len(pss)
provider_pss=[x['camera_provider_pss_kib']/1024 for x in measured if 'camera_provider_pss_kib' in x]
if provider_pss: metrics['camera_provider_pss_mib_peak']=max(provider_pss)
gpu=[int(re.match(r'(\d+)@',x['gpu_load'])[1]) for x in measured if re.match(r'(\d+)@',x['gpu_load'])]
if gpu: metrics['gpu_busy_percent_mean']=sum(gpu)/len(gpu)
npu=[x['npu_busy_percent'] for x in measured if 'npu_busy_percent' in x]
if npu:
    metrics['npu_busy_percent_mean']=sum(npu)/len(npu)
    metrics['npu_busy_percent_peak']=max(npu)
attribution={}
for name in process_names:
    ratios=[]
    for left,right in zip(measured,measured[1:]):
        a,b=left['processes'].get(name),right['processes'].get(name)
        total=sum(right['cpu_ticks'])-sum(left['cpu_ticks'])
        if a and b and a['pid']==b['pid'] and total>0: ratios.append((b['ticks']-a['ticks'])*100/total)
    if ratios: attribution[name]=sum(ratios)/len(ratios)
metrics['process_cpu_percent_4cores']=attribution
arguments=vars(args).copy(); arguments['output_dir']=str(arguments['output_dir'])
presentation={}
mono_start=result.get('measurement_start_monotonic_ns')
mono_end=result.get('measurement_end_monotonic_ns')
if mono_start and mono_end:
    stamps=sorted(x for x in presentation_stamps if mono_start<=x<=mono_end)
    if len(stamps)>=3:
        presentation=dict(layer=surface_layer,presented_fps=(len(stamps)-1)*1e9/(stamps[-1]-stamps[0]),
                          samples=len(stamps),scope='SurfaceFlinger actual presentation timestamps within benchmark',
                          covered_duration_s=(stamps[-1]-stamps[0])/1e9)
        previous_end=0; history_gaps=[]
        for point in series:
            window=point.get('presented_timestamps_ns',[])
            if not window: continue
            if previous_end and min(window)>previous_end+34_000_000 and mono_start<=min(window)<=mono_end:
                history_gaps.append((min(window)-previous_end)/1e9)
            previous_end=max(previous_end,max(window))
        presentation['history_gap_windows']=len(history_gaps)
        presentation['history_gap_seconds']=sum(history_gaps)
        presentation['rate_is_lower_bound']=bool(history_gaps)
elif args.render!='none':
    layers=adb('shell','dumpsys SurfaceFlinger --list',check=False).splitlines()
    layers=[x for x in layers if 'SurfaceView' in x and 'com.mirror.bench' in x]
    for layer in layers:
        trace=adb('shell','dumpsys SurfaceFlinger --latency '+shlex.quote(layer),check=False)
        stamps=[]
        for line in trace.splitlines()[1:]:
            values=line.split()
            if len(values)==3 and all(v.isdigit() for v in values):
                actual=int(values[1])
                if 0<actual<2**63-1: stamps.append(actual)
        stamps=sorted(set(stamps))
        if len(stamps)>=3:
            presentation=dict(layer=layer,presented_fps=(len(stamps)-1)*1e9/(stamps[-1]-stamps[0]),
                              samples=len(stamps),scope='SurfaceFlinger recent presentation history; includes tail after benchmark')
            break
    presentation['layers']=layers
payload=dict(arguments=arguments,apk_sha256=apk_sha256,result=result,system=metrics,samples=series,presentation=presentation)
if args.delegate in ('NPU','RKNN'):
    runtime_log=adb('logcat','-d','-s','tflite','MirrorBench','AndroidRuntime')
    pids={x['processes']['com.mirror.bench']['pid'] for x in series if 'com.mirror.bench' in x['processes']}
    runtime_log='\n'.join(line for line in runtime_log.splitlines() if
            (match:=re.match(r'\S+\s+\S+\s+(\d+)\s+',line)) and int(match[1]) in pids)
    (OUT/f'{args.name}-runtime-log.txt').write_text(runtime_log,encoding='utf-8')
    payload['accelerator_verification']={'requested':'NPU','busy_samples':len(npu),
        'hardware_activity_observed':bool(npu and max(npu)>0),
        'cpu_delegate_observed':'TfLiteXNNPackDelegate' in runtime_log,
        'note':('Direct RKNN image-model execution; XNNPACK is expected for CPU blendshapes/pose postprocessing.'
                if args.delegate=='RKNN' else 'Selecting the enum does not prove hardware dispatch; inspect runtime log and NPU counter.')}
(OUT/f'{args.name}.json').write_text(json.dumps(payload,indent=2),encoding='utf-8')
compact=json.loads(json.dumps(result))
compact.pop('inference_samples_ms',None)
compact.pop('conversion_samples_ms',None)
compact.pop('reference_conversion_samples_ms',None)
if 'render' in compact:
    compact['render'].pop('frame_work_samples_ms',None)
    compact['render'].pop('frame_interval_samples_ms',None)
if args.screenshot:
    adb('shell','screencap -p /data/local/tmp/mirror-bench-shot.png')
    adb('pull','/data/local/tmp/mirror-bench-shot.png',str(OUT/f'{args.name}.png'))
    adb('shell','rm -f /data/local/tmp/mirror-bench-shot.png')
adb('shell','am force-stop com.mirror.bench')
print(json.dumps(dict(run=args.name,result=compact,system=metrics,presentation=presentation)),flush=True)
if result['status'] not in ['success','interrupted']: raise SystemExit(1)
