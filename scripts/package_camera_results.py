"""Export labeled metrics, exact APKs and raw recordings without extra prose reports."""
import csv
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUT=Path(r'E:\tripo\output\camera-benchmark\20261001')
rows=[]
for path in sorted(OUT.glob('*.json')):
    data=json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(data,dict) or 'arguments' not in data or 'result' not in data: continue
    r=data['result']; s=data['system']; render=r.get('render',{}); camera=r.get('camera',{})
    if 'smoke' in path.stem: continue
    condition='固定人脸完整推理＋真实摄像头采集转换' if 'controlled-face' in path.stem else (
        '真实摄像头无人脸，仅检测负载' if path.stem.endswith('no-face') else '真实摄像头' if 'camera' in r else '无摄像头')
    rows.append({'测试':path.stem,'输入条件':condition,'时长秒':r['elapsed_s'],
                 '摄像头宽':r.get('camera_width',0),'摄像头高':r.get('camera_height',0),
                 '采集FPS':camera.get('capture_fps',0),'推理FPS':r['effective_fps'],'有人脸比例':r.get('face_fraction',0),
                 '视图数':render.get('views',0),'单视图宽':render.get('view_width',0),'单视图高':render.get('view_height',0),
                 '输出FPS':render.get('fps',0),'整机CPU百分比':s['cpu_percent_whole_machine'],
                 '测试应用CPU百分比':s['process_cpu_percent_4cores'].get('com.mirror.bench',0),
                 '相机ProviderCPU百分比':s['process_cpu_percent_4cores'].get('android.hardware.camera.provider@2.4-external-service',0),
                 'CameraServerCPU百分比':s['process_cpu_percent_4cores'].get('cameraserver',0),
                 'SurfaceFlingerCPU百分比':s['process_cpu_percent_4cores'].get('surfaceflinger',0),
                 'GPU平均Busy百分比':s.get('gpu_busy_percent_mean',0),'测试应用PSS峰值MiB':s.get('app_pss_mib_peak',0),
                 '相机ProviderPSS峰值MiB':s.get('camera_provider_pss_mib_peak',0),'系统可用内存最低MiB':s['mem_available_mib_min'],
                 '最高温度摄氏度':s['temperature_c_max'],'转换平均毫秒':r.get('conversion',{}).get('mean_ms',0),
                 '推理平均毫秒':r.get('inference',{}).get('mean_ms',0),'推理P95毫秒':r.get('inference',{}).get('p95_ms',0),
                 '多视图渲染完成平均毫秒':render.get('scene_completion_mean_ms',0),
                 '交织完成平均毫秒':render.get('interlace_completion_mean_ms',0),'APK_SHA256':data['apk_sha256']})
with (OUT/'性能汇总.csv').open('w',encoding='utf-8-sig',newline='') as stream:
    writer=csv.DictWriter(stream,fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
source=OUT/'测试程序源码.zip'
subprocess.run(['git','-C',str(ROOT),'archive','--format=zip','--output',str(source),'HEAD'],check=True)
revision=subprocess.check_output(['git','-C',str(ROOT),'rev-parse','HEAD'],text=True).strip()
manifest={'git_revision':revision,'date':'2026-10-01','device_serial':'6L32552009566714',
          'cpu_percent_scope':'100% means all four CPU cores occupied; GPU busy is device-wide, not per-module',
          'memory_scope':'PSS sampling excludes some GPU/shared allocations; also inspect system MemAvailable',
          'render_scope':'20,480 triangles/view, four morph attributes, diagnostic optical interlace mapping',
          'controlled_face_scope':'Real USB capture and YUV conversion remain active; inference uses a fixed face fitted to camera dimensions',
          'apks':[{'file':p.name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size} for p in (OUT/'apks').glob('*.apk')]}
(OUT/'delivery-manifest.json').write_text(json.dumps(manifest,indent=2,ensure_ascii=False),encoding='utf-8')
archive=OUT/'摄像头联合性能测试-完整数据.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as delivery:
    for path in sorted(OUT.rglob('*')):
        if path.is_file() and path!=archive: delivery.write(path,str(path.relative_to(OUT)))
with zipfile.ZipFile(archive) as delivery:
    assert delivery.testzip() is None
print(json.dumps({'cases':len(rows),'archive':str(archive),'bytes':archive.stat().st_size,'git_revision':revision},ensure_ascii=False))
