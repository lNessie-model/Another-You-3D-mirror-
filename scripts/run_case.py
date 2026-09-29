import argparse
import json
import re
import time
from device_profile import adb, sample, summarize, OUT

parser=argparse.ArgumentParser()
parser.add_argument('name')
parser.add_argument('--delegate',default='CPU',choices=['CPU','GPU','NONE'])
parser.add_argument('--seconds',type=int,default=30)
parser.add_argument('--analysis-fps',type=int,default=0)
parser.add_argument('--render',default='none',choices=['none','interlace','scene'])
parser.add_argument('--views',type=int,default=4)
parser.add_argument('--scale',type=float,default=1)
args=parser.parse_args()
if not re.fullmatch('[a-zA-Z0-9_-]+',args.name): raise ValueError('Invalid run name')
adb('shell','am force-stop com.mirror.bench')
adb('shell',f'run-as com.mirror.bench rm -f files/{args.name}.json',check=False)
print('START',args.name,flush=True)
adb('shell',f'am start -n com.mirror.bench/.BenchActivity --es run_id {args.name} '
    f'--es delegate {args.delegate} --ei seconds {args.seconds} --ei analysis_fps {args.analysis_fps} '
    f'--es render {args.render} --ei views {args.views} --ef scale {args.scale}')
series=[]
deadline=time.monotonic()+args.seconds+120
result=None
while time.monotonic()<deadline:
    point=sample()
    memory=adb('shell','dumpsys meminfo com.mirror.bench',check=False)
    match=re.search(r'TOTAL PSS:\s+(\d+)',memory)
    if match: point['app_pss_kib']=int(match.group(1))
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
metrics=summarize(series)
pss=[x['app_pss_kib']/1024 for x in series if 'app_pss_kib' in x]
if pss:
    metrics['app_pss_mib_peak']=max(pss)
    metrics['app_pss_mib_mean']=sum(pss)/len(pss)
payload=dict(arguments=vars(args),result=result,system=metrics,samples=series)
(OUT/f'{args.name}.json').write_text(json.dumps(payload,indent=2),encoding='utf-8')
print(json.dumps(dict(run=args.name,result=result,system=metrics)),flush=True)
if result['status'] not in ['success','interrupted']: raise SystemExit(1)
