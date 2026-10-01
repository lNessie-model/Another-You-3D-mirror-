"""Check real NPU activity and predictions from the ordinary application UID."""
import argparse,json,re,time
from pathlib import Path
from device_profile import adb,sample,summarize
parser=argparse.ArgumentParser(); parser.add_argument('--output-dir',type=Path,required=True); args=parser.parse_args()
args.output_dir.mkdir(parents=True,exist_ok=True)
adb('shell','am force-stop com.mirror.bench')
adb('shell','run-as com.mirror.bench mkdir -p files/npu-smoke')
adb('shell','run-as com.mirror.bench cp /data/local/tmp/mobilenet_v1-rk356x-sdk130.rknn files/npu-smoke/mobilenet.rknn')
adb('shell','run-as com.mirror.bench cp /data/local/tmp/dog_224x224.rgb files/npu-smoke/dog.rgb')
adb('shell','run-as com.mirror.bench rm -f files/npu-app-probe.json')
package_path=adb('shell','pm path com.mirror.bench').split(':',1)[1].strip()
apk_sha=adb('shell','sha256sum '+package_path).split()[0]
adb('shell','am start -n com.mirror.bench/.NpuSmokeActivity')
points=[]; result=None; deadline=time.monotonic()+30
while time.monotonic()<deadline:
    point=sample()
    text=adb('shell','cat /sys/kernel/debug/rknpu/load',check=False)
    match=re.search(r'(\d+)%',text)
    if match: point['npu_busy_percent']=int(match[1])
    points.append(point)
    try:
        result=json.loads(adb('shell','run-as com.mirror.bench cat files/npu-app-probe.json',check=False)); break
    except json.JSONDecodeError: time.sleep(.5)
if result is None: result=dict(status='timeout')
report=dict(result=result,apk_sha256=apk_sha,samples=points,system=summarize(points),
            npu_busy_percent_peak=max((x.get('npu_busy_percent',0) for x in points),default=0),
            scope='App UID MobileNet classification only; startup included in resources; not face capture')
(args.output_dir/'npu-app-probe.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
(args.output_dir/'npu-app-probe-logcat.txt').write_text(adb('logcat','-d','-t','300'),encoding='utf-8')
adb('shell','am force-stop com.mirror.bench')
print(json.dumps({k:v for k,v in report.items() if k!='samples'}))
if result['status']!='success' or report['npu_busy_percent_peak']==0 or result['top_class']!=156 or result['score']<.9:
    raise SystemExit(1)
