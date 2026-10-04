"""Collect private, local-only recorded-face accuracy and exact-tensor fixtures."""
import json,time,argparse,shlex
from pathlib import Path
from device_profile import adb
parser=argparse.ArgumentParser()
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args(); args.output.mkdir(parents=True,exist_ok=True)
adb('shell','am force-stop com.mirror.bench')
adb('shell','run-as com.mirror.bench rm -f files/npu-quality/quality.json',check=False)
adb('shell','am start -n com.mirror.bench/.NpuQualityActivity')
deadline=time.monotonic()+240
while time.monotonic()<deadline:
    data=adb('shell','run-as com.mirror.bench cat files/npu-quality/quality.json',check=False)
    if data.strip().startswith('{'): break
    time.sleep(2)
else: raise TimeoutError('Quality activity did not complete')
result=json.loads(data); (args.output/'quality.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
if result['status']!='success': raise RuntimeError(result)
for stamp in (0,12000,24000):
    dest=args.output/f'frame-{stamp}'; dest.mkdir(exist_ok=True)
    for model in ('face_detector','face_landmarks_detector'):
        for suffix in ('-input.f32','-outputs.json'):
            name=model+suffix
            # Binary subprocess avoids PowerShell text redirection corrupting float tensors.
            import subprocess
            from device_profile import ADB,SERIAL
            raw=subprocess.run([str(ADB),'-s',SERIAL,'exec-out','run-as','com.mirror.bench','cat',
                                f'files/npu-quality/frame-{stamp}/{name}'],capture_output=True,check=True,timeout=35).stdout
            (dest/name).write_bytes(raw)
rows=result['samples']
summary=dict(status=result['status'],samples=len(rows),complete=result['complete_samples'],
             xy_rmse_pixels_mean=sum(x['xy_rmse_pixels'] for x in rows)/len(rows),
             xy_rmse_pixels_max=max(x['xy_rmse_pixels'] for x in rows),
             blendshape_mae_mean=sum(x['blendshape_mae'] for x in rows)/len(rows),
             blendshape_mae_max=max(x['blendshape_mae'] for x in rows),
             blendshape_max_error=max(x['blendshape_max_error'] for x in rows))
print(json.dumps(summary,indent=2))
adb('shell','am force-stop com.mirror.bench')
