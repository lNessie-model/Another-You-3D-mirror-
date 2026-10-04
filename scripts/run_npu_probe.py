"""Verify real NPU activity without altering firmware, drivers, governors or services."""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path
from device_profile import ADB, SERIAL, adb, sample, summarize

parser=argparse.ArgumentParser()
parser.add_argument('--output-dir',type=Path,required=True)
args=parser.parse_args()
args.output_dir.mkdir(parents=True,exist_ok=True)
command='/data/local/tmp/rknn_probe /data/local/tmp/mobilenet_v1-rk356x-sdk130.rknn /data/local/tmp/dog_224x224.rgb 3000'
process=subprocess.Popen([ADB,'-s',SERIAL,'shell',command],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8')
points=[]
while process.poll() is None:
    point=sample()
    load=adb('shell','cat /sys/kernel/debug/rknpu/load')
    point['npu_load_percent']=int(re.search(r'(\d+)%',load)[1])
    points.append(point)
    time.sleep(1)
stdout,stderr=process.communicate(timeout=10)
(args.output_dir/'npu-probe-log.txt').write_text(stdout+'\n'+stderr,encoding='utf-8')
matches=[line for line in stdout.splitlines() if line.startswith('{')]
result=json.loads(matches[-1]) if matches else {'status':'error','stderr':stderr}
payload={'result':result,'samples':points,'system':summarize(points) if points else {},
         'npu_busy_percent_peak':max((p['npu_load_percent'] for p in points),default=0),
         'scope':'Native MobileNet validation only; not MediaPipe face performance'}
(args.output_dir/'npu-probe.json').write_text(json.dumps(payload,indent=2),encoding='utf-8')
print(json.dumps({key:value for key,value in payload.items() if key!='samples'}),flush=True)
if process.returncode or result.get('status')!='success' or payload['npu_busy_percent_peak']==0:
    raise SystemExit(1)
