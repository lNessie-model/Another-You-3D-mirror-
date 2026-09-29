"""Render a report and compact CSV from the saved measurements, not estimates."""
import csv
import json
from device_profile import OUT

def read(name):
    return json.loads((OUT/(name+'.json')).read_text(encoding='utf-8'))

def table(headers,rows):
    return '\n'.join(['| '+' | '.join(headers)+' |','| '+' | '.join(['---']*len(headers))+' |',
                      *['| '+' | '.join(map(str,row))+' |' for row in rows]])

def n(value): return f'{value:.1f}'

before=read('restored-idle')['summary']
after=read('clean-idle')['summary']
parts=['# YS-L6 实机精简与 MediaPipe／裸眼 3D 交织性能报告',
'测试日期：2026-09-29。设备序列号：6L32552009566714。',
'本次通过 ADB 停用应用、安装测试 APK 和切换桌面完成；没有刷机或修改固件分区。原厂应用与桌面的恢复、以及精简后的重启调试均已实测。',
'## 设备配置',
table(['项目','ADB／运行时确认结果'],[
['平台','RK3566，4× Cortex-A55，CPU 最高 1.8 GHz'],
['GPU','Mali-G52，最高 800 MHz；OpenGL ES 3.2'],
['系统','Android 11／API 30，arm64；Linux 4.19.232'],
['内存','MemTotal 2,004,600 KiB，约 1.91 GiB（2 GB 档）'],
['存储','32 GB 档 eMMC；/data 约 22.77 GiB，可用约 22.48 GiB'],
['显示','物理输出 1200×1920'],
['相机','本次未连接相机；CameraService 枚举 0 台，内部／外部 provider 均运行']]),
'## 精简结果与恢复',
'停用 26 个包，包括原厂相册／自动相册、出厂测试、OTA 应用、浏览器、音乐、打印、壁纸和系统相机界面；未删除 APK 或应用数据。原厂桌面依赖相册应用，因此先选择新的轻量桌面，再停用原厂桌面。保留系统框架、ADB、设置、输入、网络、媒体、相机 HAL 和显示驱动。',
table(['20 秒空闲采样','恢复原厂应用后','精简并重启后'],[
['MemAvailable 均值 / MiB',n(before['mem_available_mib_mean']),n(after['mem_available_mib_mean'])],
['MemAvailable 最小值 / MiB',n(before['mem_available_mib_min']),n(after['mem_available_mib_min'])],
['整机 CPU / %',n(before['cpu_percent_whole_machine']),n(after['cpu_percent_whole_machine'])]]),
f"这组基线增加约 {n(after['mem_available_mib_mean']-before['mem_available_mib_mean'])} MiB 可用内存。早期应用停用测试增加约 297 MiB，运行状态和缓存会影响数值。存储原本只用了约 1%，空间充足；用户级停用不会释放只读系统分区里的 APK 空间。",
'已验证：恢复原厂 HOME、原厂 3D 相册能启动；两种状态下重启后 ADB 均能连接，adb_enabled=1，设置和桌面可启动，显示与相机服务存在。固件 fingerprint 保持不变。',
'恢复复测发现原厂相册会主动跳转 Wi-Fi 设置；日志确认启动请求来自相册的 UID，应用进程仍在。这是原厂联网引导，不作为相册崩溃处理。重启验收等待用户解锁和实际 HOME 就绪，避免把启动过渡界面误判为失败。[Android Direct Boot 说明](https://developer.android.com/privacy-and-security/direct-boot)。',
'恢复脚本还覆盖了“设备已经执行停用，但电脑没有收到 ADB 回复”的中断情形：调用前先写恢复记录，防止漏恢复。两项恢复回归检查已通过。',
'## MediaPipe 单独运行',
'使用 tasks-vision 1.0.0、官方 float16 Face Landmarker 模型、单人脸，并开启表情系数和面部变换矩阵。输入是官方 820×1024 人脸照片，预先解码为 Bitmap 后连续送入；本测试没有相机、MJPEG 解码或实际动作准确率验证。每组稳定测量 30 秒，预热与模型初始化另计。所有人脸测试都检出 478 个关键点和 52 个表情系数。']
face_rows=[]
for name,label in [('face-cpu','CPU / VIDEO'),('face-gpu','GPU / VIDEO'),('face-cpu-image','CPU / IMAGE'),('face-gpu-image','GPU / IMAGE')]:
    item=read(name); result=item['result']; system=item['system']
    face_rows.append([label,n(result['effective_fps']),n(result['p95_ms']),n(system['app_pss_mib_peak']),n(result['process_cpu_percent_4cores']),n(result['initialization_ms']/1000)])
parts += [table(['模式','推理 FPS','推理 p95 / ms','PSS 峰值 / MiB','进程 CPU / %','初始化 / s'],face_rows),
'VIDEO 利用跟踪减少重复检测；IMAGE 每帧运行检测，作为跟踪丢失时负载的参照。GPU 委托在本设备可用，但仍消耗 CPU，并未调用 RKNN／NPU。这个结果不能等同于接入摄像头后的总帧率。[Google 官方运行模式说明](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android)。',
'## 多视图渲染与交织',
'最终输出固定为 1200×1920。纯交织从预先生成的视图纹理采样 RGB 子像素；动态场景每个视图实际渲染 20,480 个三角形、8 次绘制调用和 4 个 morph 属性，再交织。模型是简化的旋转网格，使用简单光照，没有最终角色的 PBR、透明头发、阴影或完整 52 个 morph 负载。']
render_rows=[]
for views in [2,4,8,16]:
    pure=read(f'interlace-{views}-full')['result']['render']
    scene=read(f'scene-{views}-full')['result']['render']
    render_rows.append([views,'1200×1920',n(pure['fps']),n(scene['fps']),n(scene['view_color_buffer_mib'])])
for views in [8,16]:
    scene=read(f'scene-{views}-half')['result']['render']
    render_rows.append([views,'600×960','—',n(scene['fps']),n(scene['view_color_buffer_mib'])])
parts += [table(['视图数','每视图分辨率','纯交织 FPS','场景＋交织 FPS','视图颜色缓冲 / MiB'],render_rows),
'颜色缓冲大小仅计算所有视图的 RGBA8 纹理，不含深度缓冲、窗口交换缓冲、驱动与推理内存。PSS 无法完整计入 GPU 共享内存，不能只看 PSS 判断余量。',
'glFinish 用于等待一帧工作完成，工作耗时含 CPU 提交和 GPU 执行；FPS 按实际 GL 帧间隔统计。该同步测试路径用于可重复测量，最终渲染器还需验证自己的帧节奏。',
'## 联合测试',
'把 MediaPipe 输出中的张嘴、眨眼、微笑和眉毛权重送入 4 个 morph 属性。面捕目标设为 10 FPS，每组测 30 秒；达不到目标时不积压等待处理的输入。']
joint_rows=[]
for name in ['joint-cpu-4-full','joint-gpu-4-full','joint-cpu-8-half','joint-gpu-8-half','joint-cpu-4-half','joint-gpu-4-half','joint-gpu-8-small']:
    if not (OUT/(name+'.json')).exists(): continue
    item=read(name); r=item['result']; s=item['system']; gl=r['render']
    joint_rows.append([r['delegate'],gl['views'],f"{gl['view_width']}×{gl['view_height']}",n(r['effective_fps']),n(gl['fps']),n(gl['frame_interval_p95_ms']),n(s['cpu_percent_whole_machine']),n(s['app_pss_mib_peak']),n(s['mem_available_mib_min'])])
parts += [table(['委托','视图','每视图','面捕 FPS','渲染 FPS','帧间隔 p95 / ms','整机 CPU / %','PSS 峰值 / MiB','可用内存最小 / MiB'],joint_rows)]
stress=read('joint-steady-300s')
r=stress['result']; s=stress['system']; gl=r['render']
parts += ['## 连续运行验证',
f"连续测量 {n(r['elapsed_s'])} 秒：{r['delegate']} 面捕，{gl['views']} 视图，每视图 {gl['view_width']}×{gl['view_height']}，最终 1200×1920。",
table(['指标','结果'],[
['面捕 FPS',n(r['effective_fps'])],['渲染 FPS',n(gl['fps'])],['推理 p95 / ms',n(r['p95_ms'])],
['渲染帧间隔 p95 / ms',n(gl['frame_interval_p95_ms'])],['整机 CPU / %',n(s['cpu_percent_whole_machine'])],
['进程 CPU / %',n(r['process_cpu_percent_4cores'])],['PSS 峰值 / MiB',n(s['app_pss_mib_peak'])],
['MemAvailable 最小 / MiB',n(s['mem_available_mib_min'])],['SoC 温度范围 / °C',f"{n(s['temperature_c_min'])}–{n(s['temperature_c_max'])}"]]),
'该运行完成且没有测试程序记录的 GL 错误或推理失败。5 分钟测试不能替代整机封装后的长时间稳定性验收。温度读取 soc-thermal；系统的被动散热阈值为 75°C 和 85°C，本次未改变散热／频率配置。',
'PSS 前 5 次与后 5 次采样均值约为 121.5 和 123.7 MiB；首／末约 1 分钟渲染率约 42.9／43.0 FPS。CPU0 采样频率一直为 1.8 GHz，没有使用 swap。这组数据没有出现明显帧率衰减，不能据此排除更长时间的内存增长。',
'最终 APK 的渲染错误字段补充了跨线程可见性；模型、着色器和计算负载未改变。更新 APK 并重启后再次联合测量 10 秒，得到约 9.9 FPS 面捕和 42.8 FPS 渲染，关键点／表情数量与 GL 状态均通过检查。矩阵及 5 分钟测试的原始 APK 也另存于 apks/MirrorDeviceLab-measured.apk，构建 SHA-256 记录在 build-provenance.json。',
'## 使用建议与待验证项目',
'按目前负载，原型可先采用较低的每视图渲染分辨率、单人脸跟踪、约 10 FPS 面捕，渲染线程独立运行并插值表情。全分辨率 8／16 视图动态角色开销明显；4 视图半分辨率留出的渲染余量更大。正式视图数要服从实际光学屏的映射规则，不能仅按性能任意选择。',
'后续必须实测：摄像头 UVC 枚举、USB 供电和针脚、采集／JPEG 解码、输入旋转与坐标；最终角色材质、表情 morph 和头部姿态；以及厂商提供的光栅 pitch、tilt、center、RGB/BGR、屏幕方向和视图映射。当前交织参数仅用于诊断，尚未验证裸眼 3D 光学效果。',
'当前保留精简状态和测试 APK，测试进程已停止。需要原厂功能时，在 PowerShell 执行：',
"```powershell\npowershell.exe -NoProfile -ExecutionPolicy Bypass -File 'E:\\tripo\\device-lab\\scripts\\Restore-Factory.ps1'\n```",
'本次不需要刷固件。若以后确实需要刷机，先取得与这块板及屏幕匹配的原厂完整固件和经过验证的恢复方法；当前的应用备份和恢复脚本只覆盖本次应用级改动。',
'## 数据与复现',
'源码和复现说明：`E:\\tripo\\device-lab\\README.md`。原始数据、逐帧耗时、系统采样、恢复记录和 APK 位于本报告目录。`results-validation.json` 记录了从原始采样重新核对 FPS／p95、关键点／表情数、显示尺寸和 GL 状态的结果。主机与设备时钟经过偏差对齐，系统采样窗口约有 1 秒的边界精度。']
(OUT/'YS-L6实机测试报告.md').write_text('\n\n'.join(parts)+'\n',encoding='utf-8')
rows=[]
for path in sorted(OUT.glob('*.json')):
    item=json.loads(path.read_text(encoding='utf-8'))
    if 'arguments' not in item or 'result' not in item: continue
    r=item['result']; s=item['system']; gl=r.get('render',{})
    rows.append(dict(case=path.stem,delegate=r['delegate'],mode=r.get('running_mode','VIDEO'),views=gl.get('views',''),
                     inference_fps=r['effective_fps'],inference_p95_ms=r['p95_ms'],render_fps=gl.get('fps',''),
                     render_interval_p95_ms=gl.get('frame_interval_p95_ms',''),cpu_whole_percent=s['cpu_percent_whole_machine'],
                     pss_peak_mib=s.get('app_pss_mib_peak',''),mem_available_min_mib=s['mem_available_mib_min'],temperature_max_c=s['temperature_c_max']))
with (OUT/'measurements.csv').open('w',newline='',encoding='utf-8-sig') as stream:
    writer=csv.DictWriter(stream,fieldnames=rows[0].keys()); writer.writeheader(); writer.writerows(rows)
print(OUT/'YS-L6实机测试报告.md')
