import argparse
import json
import re
import shlex
import time
from pathlib import Path
from device_profile import adb, sample, summarize, OUT as DEFAULT_OUT

parser=argparse.ArgumentParser()
parser.add_argument('name')
parser.add_argument('--delegate',default='CPU',choices=['CPU','GPU','NONE'])
parser.add_argument('--seconds',type=int,default=30)
parser.add_argument('--analysis-fps',type=int,default=0)
parser.add_argument('--running-mode',default='VIDEO',choices=['VIDEO','IMAGE'])
parser.add_argument('--render',default='none',choices=['none','interlace','scene'])
parser.add_argument('--views',type=int,default=4)
parser.add_argument('--scale',type=float,default=1)
parser.add_argument('--screenshot',action='store_true')
parser.add_argument('--input',default='portrait',choices=['portrait','camera'])
parser.add_argument('--camera-width',type=int,default=640)
parser.add_argument('--camera-height',type=int,default=480)
parser.add_argument('--capture-fps',type=int,default=25)
parser.add_argument('--rotation',type=int,default=0)
parser.add_argument('--convert',action='store_true')
parser.add_argument('--profile-stages',action='store_true')
parser.add_argument('--output-dir',type=Path,default=DEFAULT_OUT)
args=parser.parse_args()
OUT=args.output_dir
OUT.mkdir(parents=True,exist_ok=True)
if not re.fullmatch('[a-zA-Z0-9_-]+',args.name): raise ValueError('Invalid run name')

process_names=['com.mirror.bench','android.hardware.camera.provider@2.4-external-service','cameraserver','surfaceflinger']
def processes():
    command='; '.join(f'for p in $(pidof {name}); do echo PROC {name}; cat /proc/$p/stat; done' for name in process_names)
    text=adb('shell',command,check=False)
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
activity='CameraBenchActivity' if args.input=='camera' else 'BenchActivity'
camera_extras=(f' --ei camera_width {args.camera_width} --ei camera_height {args.camera_height}'
               f' --ei capture_fps {args.capture_fps} --ei rotation {args.rotation} --ez convert {str(args.convert).lower()}') if args.input=='camera' else ''
adb('shell',f'am start -n com.mirror.bench/.{activity} --es run_id {args.name} '
    f'--es delegate {args.delegate} --ei seconds {args.seconds} --ei analysis_fps {args.analysis_fps} '
    f'--es running_mode {args.running_mode} '
    f'--es render {args.render} --ei views {args.views} --ef scale {args.scale} '
    f'--ez profile_stages {str(args.profile_stages).lower()}'+camera_extras)
series=[]
deadline=time.monotonic()+args.seconds+120
result=None
last_memory_sample=0
while time.monotonic()<deadline:
    point=sample()
    point['processes']=processes()
    if time.monotonic()-last_memory_sample>=5:
        memory=adb('shell','dumpsys meminfo com.mirror.bench',check=False)
        match=re.search(r'TOTAL PSS:\s+(\d+)',memory)
        if match: point['app_pss_kib']=int(match.group(1))
        provider=point['processes'].get('android.hardware.camera.provider@2.4-external-service')
        provider_memory=adb('shell','dumpsys meminfo '+str(provider['pid']),check=False) if provider else ''
        match=re.search(r'TOTAL PSS:\s+(\d+)',provider_memory)
        if match: point['camera_provider_pss_kib']=int(match.group(1))
        last_memory_sample=time.monotonic()
    series.append(point)
    text=adb('shell',f'run-as com.mirror.bench cat files/{args.name}.json',check=False)
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
payload=dict(arguments=arguments,apk_sha256=apk_sha256,result=result,system=metrics,samples=series)
(OUT/f'{args.name}.json').write_text(json.dumps(payload,indent=2),encoding='utf-8')
compact=json.loads(json.dumps(result))
compact.pop('inference_samples_ms',None)
compact.pop('conversion_samples_ms',None)
if 'render' in compact:
    compact['render'].pop('frame_work_samples_ms',None)
    compact['render'].pop('frame_interval_samples_ms',None)
if args.screenshot:
    adb('shell','screencap -p /data/local/tmp/mirror-bench-shot.png')
    adb('pull','/data/local/tmp/mirror-bench-shot.png',str(OUT/f'{args.name}.png'))
    adb('shell','rm -f /data/local/tmp/mirror-bench-shot.png')
adb('shell','am force-stop com.mirror.bench')
print(json.dumps(dict(run=args.name,result=compact,system=metrics)),flush=True)
if result['status'] not in ['success','interrupted']: raise SystemExit(1)
