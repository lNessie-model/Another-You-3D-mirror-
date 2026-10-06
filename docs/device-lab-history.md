# Another You · 另一个你

当前安装版 **V47 / 0.2.16**：组内program/VP/sampler复用仅调试启用，原shader/PBR/模型/UI保持。实际普通→新候选69×16像素门通过，新同包ABA三组12.1453/12.1451/12.1274呈现FPS；候选对常量色两端均值+0.0718%，本轮无普通性能端点，30FPS未完成。初次私有统计错误保留后全新v2重测，默认仍OFF。正常首帧/Back/Java退出、78资产/9库/四配置保持，真实UI截图已供远程查看。USB仍错误，见[本轮说明](docs/production-app-v47-20261007.md)。以下历史原文完整保留。

当前安装版为 **V46 / 0.2.15**：主表面实际13975顶点全白验证后的常量色候选，直接普通→候选69×16层Mali像素门通过。同一V46 APK普通/旧候选/新候选/旧候选/普通五组联合实测10.5471/11.2980/**12.1229**/11.2653/10.5285呈现FPS；新候选相对普通均值+15.04%、相对旧候选+7.46%，CPU与面捕代价另记，默认仍OFF。原78资产/9原生库、模型材质/UI/四配置保持，新普通入口/Back/Java线程退出核验。30FPS及USB实时恢复未完成，见[本轮说明](docs/production-app-v46-20261007.md)。以下保留历史原文。

当前安装版为 **V45 / 0.2.14**：补齐普通INDIVIDUAL与分材质候选69×16的实际Mali像素门；原78资产/9原生库、模型材质、UI和保存配置保持。同V44 APK普通/候选/普通联合短测为10.5459/11.2921/10.5407呈现FPS，相对普通真实参考提升约7.10%，同时CPU增加、面捕延迟上升；候选仍默认关闭，30FPS与USB实时恢复未完成。新V45 APK已通过首帧/Back/Java线程退出与配置保留，跑分APK版本边界分开记录。见[本轮说明](docs/production-app-v45-20261007.md)。下方保留各历史迭代原文。

当前安装版 V44 / 0.2.13 改为一次浏览一个角色，明确区分正在浏览与正在使用，真实选择/重启保留/进入运行/恢复原配置已核验。全部78assets/9native保持。默认关闭的分材质着色器在Mali69姿态×16层比较最大RGB差1/255、alpha零差；同APK完整PBR/录像面捕/NPU/16×400×640合批 A/B/A为8.926/11.278/8.930呈现FPS，候选约提升26%，CPU略增，普通individual默认尚未改变。辅助固定35秒完整门false保留。NPU位移原型有法线精度障碍，眼睑候选仍未完整闭合，均未替换正式角色。详见[V44 UI、实测与恢复](docs/production-app-v44-20261007.md)。USB流、完整历史角色校正和16+视点30FPS仍待完成。

以下保留各轮历史条件及结果。
当前安装版 V43 / 0.2.12 增加独立材质覆盖诊断，原正式界面和全部78assets/9native保持。九姿态×保存/默认两画面×16视图共288层 alpha核对通过；当前画面估计可缓存命中只占可见角色33.19%，不支持直接做整张2048 UV缓存，未实施材质缓存或证明FPS提升。新萨菲罗斯张嘴候选已独立通过设备PBR预览及两组各144层绘制比较，并在仓库提供GLB/绑定/截图，仍待美术校正与正式接入。详见[V43记录](docs/production-app-v43-20261007.md)和[模型候选](review-assets/sephiroth-jaw-v1/README.md)。USB流、完整角色校正和16+视点30FPS仍待完成。

当前安装版 V42 / 0.2.11 增加默认关闭的空白交织采样诊断。三个实际角色共285项最终1200×1920 RGBA逐字节零差；同APK完整Geralt/PBR、录像RGA/RKNN478/混合52、16×400×640 A-B-A短测8.920/8.957/8.932呈现FPS，未证明提升且CPU略增，候选保持关闭。固定前35秒辅助窗口完整门false已原样保留；普通界面与全部78assets/9native、四配置和自然退出沿用并核验。USB流、完整历史角色校正、三维背景与16+视点30FPS仍待完成。详见[V42诊断与实测记录](docs/production-app-v42-20261006.md)。

V41 / 0.2.10 记录 调整校准角色的独立透视取景，三角色实际READY/正确SHA/10FPS上限/0额外pose线程、故障提示/重试/禁用保存、设置返回/运行恢复/最终退出已在设备验证。原四配置和78assets/9native字节保持；主多视图材质与参数保持。摄像头流仍失败，本轮未启用GPU性能候选或证明帧率提升；完整历史角色校正与16+视点30FPS仍未完成。详见 [V41校准迭代](docs/production-app-v41-20261006.md)。

V40 / 0.2.9 记录 统一详细画面与相机校准页，固定保存/取消与错误提示，设置来源关闭后直接回设置、运行来源关闭后恢复画面；三秒全屏预览隐藏菜单，编辑面板不透出背后文字。实际保存/取消/Back、双次冷启动角色预览、主GL恢复和退出线程检查通过，四份配置与78assets/9native字节保持。USB流仍失败、完整角色校正与16+视点30FPS仍待完成；本轮不产生性能提升结论。详见 [V40 UI与返回流程](docs/production-app-v40-20261006.md)。

V39 / 0.2.8 记录 增加240×384、200×320两个仅调试会话的比例视图尺寸，普通默认和保存表仍16视点/400×640。同APK完整杰洛特/PBR及录像RGA/RKNN478/混合52短测：原8.927、240档12.385（末尾短INTERACTIVE完整门未通过）、200档13.361、原复测8.909FPS；统一前25秒辅助分析与CPU/GPU/NPU/PSS另记，最好仍约13.4FPS、GPU约99%，30FPS未完成。画质及16层同姿态严格门尚未验收，候选没有默认启用；四配置和78assets/9native保持，USB采集仍失败。详见 [V39比例视图实验](docs/production-app-v39-20261006.md)。

V38 / 0.2.7 记录 增加独立的椭圆主题设置页：浏览设置/关于不启动相机、面捕或3D渲染，返回键直接回首页；画面、相机校准与高级编辑保持原保存/取消机制并返回设置。已修复相机校准冷启动提前消费请求，两个新会话实际面板与角色预览、原配置、普通魔镜/角色页与退出检查通过。全部78assets/9native字节保持；相机硬件流配置错误、完整角色校正及16+视点30FPS仍未完成。本轮不宣称性能提升，详见 [V38迭代说明](docs/production-app-v38-20261006.md)。

V37 / 0.2.6 记录 保留黑金暗红、椭圆安全区首页/角色/场景/设置。新增默认关闭的完整PBR算术候选及同scene真机对照：九姿态×16视点×两后端288组，最大RGB差1级、共4个颜色字节差，实际shader program计数与36图SHA已核验。同包完整Geralt/NPU录像面捕16×400×640短测呈现8.897/8.817/8.894FPS（原/候选/原），未证明提升，候选关闭，30FPS目标未完成。普通入口/Back、原配置、全部78assets/9native条目保持；相机流错误仍未恢复。完整条件、边界和复现见 [V37迭代说明](docs/production-app-v37-20261006.md)。以下V36及更早记录保留各自实验条件。

当前 App 已新增 Another You 正式入口、椭圆安全区主题、角色/场景/设置与实时菜单。V36 / 0.2.5 增加默认关闭、仅确切杰洛特资产可用的 ORM RG8 上传实验。实际 Mali 的69姿态×16视点×两模式共2208画面对比零RGB/alpha差异，逻辑纹理数据减少10.667MiB；同APK录像面捕/NPU/杰洛特16×400×640短测呈现8.949/8.712/8.889FPS（原/候选/原），未证明帧率提升，未达到30FPS，保持默认关闭。原配置、全部78assets/9native entries、正常Back和主页保持。UI、三个可选角色与27项素材沿用V35；其它IP的模型自然度仍逐项校正。

范围和实际验收见 [V36迭代说明](docs/production-app-v36-20261006.md)，最终交织GPU采样见 [V35记录](docs/production-app-v35-20261006.md)，角色接入见 [V34记录](docs/production-app-v34-20261006.md)，素材库见 [V32记录](docs/production-app-v32-20261005.md)和 [完整目标规格](docs/production-app-spec-20261005.md)。覆盖升级保留原配置、导入角色和ADB；当前为内部安装版。正式入口相机仍有流配置错误、未采集帧，本人实时USB面捕与联合帧率未测。录像测试不含USB采集或解码。完整角色校正、三维背景接入和16+视点30FPS仍未完成，以下历史实验保留各自条件。

在实际的 RK3566 / Android 11 / 2 GB 设备上运行 MediaPipe Face Landmarker 和 OpenGL ES 3 多视图交织。测试不需要刷机。

## 本次设备与输出

- ADB 序列号：`6L32552009566714`。所有脚本绑定此设备，避免改动其他连接的机器。
- 原始数据和恢复记录：`E:\tripo\output\device-benchmark\20260929`。
- 测试 APK：`app/build/outputs/apk/debug/app-debug.apk`，包名 `com.mirror.bench`。
- 轻量桌面 APK：`launcher/build/outputs/apk/debug/launcher-debug.apk`，包名 `com.mirror.launcher`。
- 两个 APK 都没有联网权限、后台服务或开机自启动推理。桌面由 Android 的默认 HOME 机制启动。

## 系统精简与恢复

仅使用 `pm disable-user --user 0` 停用经过审查的预装应用；保留 APK、应用数据和原厂 3D 参数。没有写入 boot、system、vendor、recovery 或 loader 分区。

原厂桌面会启动原厂相册，停用相册时需要先选择可工作的替代桌面。因此部署 `com.mirror.launcher` 后再停用原厂桌面。每个包原来的 enabled 状态、原厂 HOME 和操作历史保存在 `cleanup-journal.json`。

恢复原厂应用和桌面，可以在这台电脑的 PowerShell 执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'E:\tripo\device-lab\scripts\Restore-Factory.ps1'
```

此命令仅为这一次脚本执行设置策略，不改变电脑的永久执行策略。脚本不要求 Python，会核对设备序列号，再恢复日志中的 enabled 状态、选择原厂 HOME 并启动桌面。保留本目录和恢复日志。也可使用下面的 Python 脚本恢复或重新应用精简：

```powershell
$benchPython='C:\Users\lNessie\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $benchPython -X utf8 'E:\tripo\device-lab\scripts\device_profile.py' restore
& $benchPython -X utf8 'E:\tripo\device-lab\scripts\device_profile.py' reapply
```

恢复测试包含原厂 HOME、原厂 3D 相册、设置和重启后的 ADB；精简状态也验证重启后的 HOME、设置、ADB、相机服务和原生屏幕尺寸。备份的原厂 APK 和参数不构成完整固件备份。

## 复现性能测试

测试程序在后台线程创建并运行 MediaPipe；GPU 委托也在同一线程使用。固定单人脸，开启 52 个表情系数和面部变换矩阵。使用 Google 官方人脸照片连续送帧，VIDEO 测稳定跟踪，IMAGE 测每帧检测。没有相机采集、MJPEG 解码、双目融合或实际表情准确率测试。

```powershell
& $benchPython -X utf8 'E:\tripo\device-lab\scripts\run_matrix.py' --group face --seconds 30
& $benchPython -X utf8 'E:\tripo\device-lab\scripts\run_matrix.py' --group render --seconds 30
& $benchPython -X utf8 'E:\tripo\device-lab\scripts\run_matrix.py' --group joint --seconds 30
& $benchPython -X utf8 'E:\tripo\device-lab\scripts\validate_results.py'
```

`run_case.py` 支持 `--delegate CPU/GPU/NONE`、`--render none/interlace/scene`、`--views 2/4/8/16`、`--scale 1/.5`、`--analysis-fps`、`--running-mode VIDEO/IMAGE`。运行结束保存 JSON 后自动停止测试应用。

交织输出保持 1200×1920；scale=.5 表示每个视图用 600×960 离屏缓冲，再合成原生尺寸。纯交织使用预先生成的视图颜色；场景每视图渲染 20,480 个三角形、8 次绘制调用和 4 个 morph 属性，使用简单光照的旋转网格。联合测试把实际 MediaPipe 表情系数传入这 4 个 morph 权重。该网格用于测负载，不能替代最终头部模型的测试。

交织 shader 的 pitch、tilt 是诊断参数。裸眼 3D 光学效果仍需厂商确认视图数、RGB/BGR 排列、光栅 pitch/tilt/center、方向及映射规则。不能用本测试 FPS 判定屏幕已校准。

## 指标口径

### 2026-10-01 真实 USB 摄像头测试

`CameraBenchActivity` 通过系统 Camera2 外置相机 HAL 读取 USB MJPEG 摄像头。HAL 负责 MJPEG 解码，应用读取 YUV_420_888，按实际 row/pixel stride 打包 NV21，再使用此 Android 11 系统的原生 RenderScript YUV→RGBA 转换。RenderScript 已被新 Android 版本弃用，这条路径用于当前设备的性能验证。

MediaPipe 在同一工作线程创建和调用 GPU 委托，使用同步 VIDEO 模式处理最新的已解码帧。相机采集与 GL 渲染分别使用独立线程。ImageReader 使用 acquireLatestImage，并只保留一个可替换的待处理帧；推理慢于摄像头时主动丢弃旧帧。采集 FPS、推理 FPS 与画面 FPS 分开记录。开启 478 个关键点、52 个表情系数和变换矩阵，记录检测到人脸的帧比例。

```powershell
& $benchPython -X utf8 'E:\tripo\device-lab\scripts\run_camera_matrix.py' --group components
& $benchPython -X utf8 'E:\tripo\device-lab\scripts\run_camera_matrix.py' --group joint
& $benchPython -X utf8 'E:\tripo\device-lab\scripts\run_camera_matrix.py' --group hd
& $benchPython -X utf8 'E:\tripo\device-lab\scripts\run_camera_matrix.py' --group steady
```

结果保存到 `E:\tripo\output\camera-benchmark\20261001`。每个结果记录实际已安装 APK 的 SHA-256。CPU 以整机四核合计 100% 归一化，分别采样测试程序、外置相机 provider、cameraserver、SurfaceFlinger，并记录系统总体 CPU、GPU busy、PSS、MemAvailable 和温度。调用线程 CPU 时间不包含推理/转换的其他工作线程，不能作为对应模块的总 CPU 占用。不同组合的资源差值用于估计模块开销，不能直接相加预测联合负载。

渲染阶段计时每 30 帧增加一次 glFinish，分别记录多视图渲染与交织的完成等待时间。这包含提交、GPU 工作和共享 GPU 的等待，属于分段完成耗时，不是独占 GPU 耗时；额外同步也可能影响少量帧。最终帧率仍按实际连续 GL 帧回调测得。模型仍是每视图 20,480 三角形的简化网格，尚未替换成最终角色。

运行结束关闭相机并停止测试进程，没有后台推理或摄像头服务；原厂相机应用保持精简后的停用状态。测试程序新增 CAMERA 权限，不需要网络权限、刷机或更换驱动。

当无法持续保持人脸入镜时，可给矩阵脚本添加 `--face-input portrait`，运行可控完整负载。摄像头仍真正采集并转换每个被消费的图像；面捕使用按当前采集尺寸等比例缩放的官方人脸。结果名称带 `controlled-face`，并记录明确的输入条件。这能测完整关键点/表情推理与其他模块的资源争用，但不包含动态人脸跟踪丢失/重检测，不能称为真实人脸的连续视频测试。无人脸的真实输入结果另存为 `no-face`，不可将仅检测阶段的高 FPS 当作完整面捕 FPS。

- [Android ImageReader 最新帧与资源释放](https://developer.android.com/reference/android/media/ImageReader#acquireLatestImage())
- [Android CameraCaptureSession 重复采集](https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession#setRepeatingRequest(android.hardware.camera2.CaptureRequest,%20android.hardware.camera2.CameraCaptureSession.CaptureCallback,%20android.os.Handler))
- [Android 11 可用的 YUV 转换接口](https://developer.android.com/reference/android/renderscript/ScriptIntrinsicYuvToRGB)

- 推理 FPS：有效调用次数除以设备测量时间；p95 是单次同步推理耗时的第 95 百分位。
- 渲染 FPS：GL 帧回调间隔，包含实际提交与交换节奏。工作耗时使用 glFinish 等待完成，包含 CPU 提交和 GPU 执行，未把它标为纯 GPU 时间。
- 进程 CPU：4 核合计归一化，100% 表示所有核满负荷。
- 系统 CPU：从 `/proc/stat` 差分读取，含合成器、系统工作和采样开销。
- PSS 每 5 秒采样，不包含全部 GPU 共享内存；同时给出理论视图颜色缓冲大小和系统 MemAvailable。
- 系统资源按设备测量窗口统计，初始化和预热排除。脚本记录主机和设备时钟偏差，秒级对齐存在约 1 秒边界误差。
- 温度来自 thermal_zone0；没有改动 CPU/GPU 频率、散热策略或 swap 配置。

## 构建和官方来源

### 录制可重复使用的人脸视频

`CameraRecordActivity` 复用已验证的 Camera2 采集和 YUV 打包路径，默认录制 60 秒、640×480、请求 25 FPS；仅录画面。应用专属外部目录 `recordings/` 保存 NV21 像素、逐帧相机时间戳和 JSON 统计，不进行面捕或渲染，以减少录制干扰。入口参数 `record_id` 只允许字母、数字、下划线和连字符，同名原始录像不会覆盖。

```powershell
adb shell am start -W -n com.mirror.bench/.CameraRecordActivity --es record_id face-reference-20261001-01 --ei seconds 60
adb pull /sdcard/Android/data/com.mirror.bench/files/recordings E:/tripo/output/camera-reference/20261001
```

录制后的 MP4 使用 H.264 / yuv420p / CRF 16，不插帧，以相机时间戳平均速率生成恒定帧率参考片。另用 FFV1 / yuv420p 保存无损 MKV；恢复为 NV21 后的完整字节 MD5 与原始录像相同。逐帧 CSV 保留原始非均匀采集时序，精确时序测试应读取 CSV，而不是把 MP4 当作原始时间戳。

`VideoCheckActivity` 从应用私有目录 `files/recordings/<record_id>.mp4` 读取视频，每秒解码一帧，转换为 MediaPipe 要求的 ARGB_8888，再用现有 GPU Face Landmarker 检查 478 个关键点和 52 个表情系数。输出 `files/recordings/<record_id>-face-check.json`。这只是解码、人脸覆盖和表情变化抽查，不是吞吐率测试，也不证明未经抽查的每帧都有完整人脸。

```powershell
adb push E:/tripo/output/camera-reference/20261001/recordings/face-reference-20261001-01.mp4 /data/local/tmp/
adb shell run-as com.mirror.bench mkdir -p files/recordings
adb shell run-as com.mirror.bench cp /data/local/tmp/face-reference-20261001-01.mp4 files/recordings/
adb shell am start -W -n com.mirror.bench/.VideoCheckActivity --es record_id face-reference-20261001-01
adb shell run-as com.mirror.bench cat files/recordings/face-reference-20261001-01-face-check.json
```

当前 Android 固件禁止通过 ADB 直接写应用外部目录，因此用 `/data/local/tmp` 中转到私有目录。视频可用于离线面捕和渲染对照；离线解码的成本应单列，不能替代真实 USB 采集性能。录制结束自动关闭相机，检查完成后由 ADB 停止测试应用。

### 录制回放与硬件利用优化

`--input replay` 按录制平均速率回放应用私有目录中的 NV21 文件。消费最新帧、跳过旧帧，映射只读且限制 512 MiB；`--preload` 可预读映射页。这条路径不包含 USB 采集或视频解码。其 PSS 包含可回收的录像文件映射页，不能作为产品工作内存的估算。35 秒稳定片仍会在循环边界出现姿态跳变，需查看完整面捕覆盖率。

```powershell
& $benchPython -X utf8 'E:\tripo\device-lab\scripts\run_case.py' replay-gpu4 `
  --input replay --record-id face-reference-stable-20261001-01 `
  --delegate GPU --analysis-fps 10 --render scene --views 4 --scale .5 --pipeline `
  --conversion-backend rga --pitch 10 --tan .2777777 --pitch-units pixels `
  --output-dir E:/tripo/output/hardware-optimization/20261001
```

用户提供 `pitch=10`、`tan=0.2777777`。pitch 单位尚未确认，目前新参数性能组显式假设完整像素；tan 解释为每个垂直像素对应的水平像素偏移。shader 的 x 使用 RGB 子像素，因此完整像素 pitch 乘 3，tan 也乘 3；若 pitch 已是 RGB 子像素，则使用 `--pitch-units subpixels`，只换算 tan。未指定 pitch 的旧对照组继续使用原诊断系数，结果不可混写。视点顺序、RGB/BGR、中心偏移和光学对齐尚未验证。

`--pipeline` 以两个 GPU fence 限制在途帧，去掉逐帧 glFinish，保留真实 EGL 交换约束。异步时工作耗时只表示 CPU 提交和 fence 等待，不表示 GPU 完成。脚本每次资源采样同时采集 SurfaceFlinger 的实际呈现时间戳，在设备 monotonic 测量窗口内计算屏幕呈现 FPS；结果记录覆盖时长。旧 APK 缺少 monotonic 窗口时仅得到尾部历史，不能将其当作整段呈现 FPS。

`--lookup --verify-lookup` 可试验 GPU 生成的整数交织索引纹理（1200×1920 时额外约 8.8 MiB）。校验使用每个视图各通道都不同的固定颜色，与原公式逐字节对照。当前板子上该路径降低帧率，默认关闭。

`--conversion-backend rga` 使用应用本地 Rockchip NDK 库同步 NV21→RGBA（BT601 limited），不更换系统库或驱动。17 个录制/合成色彩样本与 RenderScript 的 RGB 最大差异为 1/255，alpha 一致。`--native-packing` 用原生代码复制真实 Camera2 直接缓冲；共享 NV21 布局通过指针关系确认，其他 row/pixel stride 按通用循环打包，非直接缓冲回退 Java 行读取。已与 24 张实际采集图像逐字节对照，并验证已知平面、共享色度、非零 position、截短缓冲拒绝和堆缓冲回退。

```powershell
& 'E:\tripo\device-lab\scripts\build_native.ps1'
adb shell am start -n com.mirror.bench/.ConversionCheckActivity
adb shell run-as com.mirror.bench cat files/conversion-check.json
adb shell am start -n com.mirror.bench/.PackingCheckActivity
adb shell run-as com.mirror.bench cat files/packing-check.json
```

NPU 独立验证使用 RKNN 1.3.0 官方 MobileNet 模型，3000 次推理平均约 5.64 ms，实际 RKNPU 峰值 87%。这是分类模型性能。本机 MediaPipe 1.0.0 的枚举虽含 NPU，其 Java acceleration 转换只处理 CPU/GPU；请求 NPU 的实际日志显示 XNNPACK CPU 执行，NPU 采样为 0%，不能据此声称面捕已使用 NPU。2026-10-02 新增的 `RKNN` 后端直接调用 NPU，并保留 478 点、52 表情和姿态，详见后文。原生依赖来源、哈希、构建方法见 `native/DEPENDENCIES.md`。

Java 17、Gradle 8.5、AGP 8.2.2、compileSdk 35、build-tools 35.0.1；目标 Android 11，minSdk 24，MediaPipe `tasks-vision:1.0.0`，仅 arm64 原生库。模型和依赖来源及 SHA-256 在 `asset-manifest.json`，`prepare_assets.py` 可下载同版本资产。电脑上已有其余依赖缓存，本次构建已完成；首次在另一台机器上构建需要 Android SDK 与网络。

### 16 视点以上 / 30 FPS 的图像预算与联合测试

2026-10-01 的结果保存在 `E:/tripo/output/multiview-optimization/20261001`。最终屏幕输出始终为 1200×1920，场景仍为每视点 20,480 个三角形、8 个材质批次、4 个诊断形变；面捕输出 478 点和 52 个表情系数。采用减少每视点图像分辨率的取舍，不能把这组结果说成原 600×960 每视点画质下达到 30 FPS，也不能据此保证尚未接入的正式角色达到同样帧率。

原 16 视点 / 600×960 / GPU 完整面捕约 18.01 呈现 FPS。背面剔除约 21.86 FPS；共享形变加显式基础纹理层级约 23.80 FPS。16 视点 / 300×960 短测约 35.12 FPS（呈现统计覆盖 32.44 秒）；20 视点 / 300×960 约 27.11 FPS；24 视点 / 240×960 约 23.19 FPS。20 视点 / 240×720 连续 5 分钟约 35.41 FPS，每分钟均超过 35.34 FPS，面捕约 9.87 FPS、完整人脸覆盖 99.73%。这些数值的 pitch 均按完整像素解释，具体条件及 APK 哈希见各原始 JSON。

- `--cull` 剔除封闭模型的背面，生成网格朝外三角形使用 CW 绕序。
- `--preblend` 在表情更新时通过 GLES transform feedback 计算一次形变，所有视点共享 32 字节顶点记录；不改变网格、形变权重或光照。
- `--multiview` 使用本机支持的 OVR_multiview2，每批 4 个独立投影。20 视点为 5 批，模型仍在所有视点绘制；不是重复两张图来充当多个视点。显式检查最大批量 4 和最大数组层数 256。
- `--explicit-lod` 在只有一个层级的纹理上明确采样基础层级，避免隐式层级计算。
- `--scale` 为纵向比例，未单独指定横向比例时也用于横向；`--width-scale` 单独控制横向。240×720 对应 `.2` / `.375`，最终交织输出不缩小。
- `--verify-combined` 在计时前对比原串行路径与所选优化的完整交织图像，覆盖 5 个角度和中性/最大表情。计时等待 GL 验证结束，避免将初始化遗漏计入成绩。20 视点候选对比为 0 字节差异。

```powershell
& $benchPython -X utf8 E:/tripo/device-lab/scripts/run_case.py v20-live-example `
  --delegate GPU --input camera --face-input replay --render scene `
  --views 20 --scale .375 --width-scale .2 --analysis-fps 10 --seconds 300 `
  --pipeline --cull --preblend --multiview --explicit-lod --verify-combined `
  --conversion-backend rga --native-packing --pitch 10 --tan .2777777 --pitch-units pixels `
  --output-dir E:/tripo/output/multiview-optimization/20261001
```

`--input camera --face-input replay` 实际运行 USB 采集、相机 HAL 的 MJPEG 解码、原生 YUV 打包和 RGA 转换，同时用已录制的动态人脸执行完整面捕。为免需要用户持续站在镜头前，额外转换一份 NV21 人脸参考；其开销在 `reference_conversion` 单列且包含在联合负载中。它不包含 MP4 解码。真实摄像头直接驱动面捕时使用 `--face-input camera`，需要人脸在镜头中。45 秒联合抽查为 35.29 呈现 FPS、9.59 面捕 FPS、24.44 摄像头采集 FPS，完整人脸覆盖 99.77%。

带真实 USB 的最终 5 分钟验证将 pitch 按 RGB 子像素解释，其余保持同一 20 视点 / 240×720 配置：平均实际呈现 34.15 FPS，各分钟为 34.27 / 34.19 / 34.14 / 34.07 / 34.09 FPS。面捕 9.74 FPS、完整覆盖 99.69%，相机采集 24.50 FPS、0 次采集失败；相机打包加转换平均 3.68 ms，额外录像读取加转换 2.60 ms，同步面捕调用 81.02 ms（含共享 GPU 等待，不是独占 GPU 时间）。系统 CPU 60.36%、GPU busy 96.10%、NPU 0%，最低 MemAvailable 940.34 MiB、PSS 峰值 494.88 MiB、最高 thermal_zone0 温度 77.5°C，回调帧间隔 p95 为 38.72 ms。优化组合相对同一图像预算的原方案五姿态比较为 0 字节差异。两种 pitch 单位均有超过 30 FPS 的联合记录，光学对齐仍待实屏标定。

最终 `Multiview30Fps.apk` SHA-256：`7ca5b6071e7f94bcc91faa61f09605f09c1ee178b6a8041e1faa7043b75e04ba`。测试后确认 ADB 开启、设置启动成功、HOME 为 `com.mirror.launcher/.HomeActivity`，录像 SHA-256 保持一致，测试进程已停止；设备固件指纹保持原值。

`scripts/validate_multiview_results.py` 审核两组 5 分钟记录：原始实际呈现时间戳必须覆盖完整窗口、无历史漏采，各分钟必须达到 30 FPS，并检查完整面捕和真实摄像头采集；输出 `goal-validation.json` 与 `performance-comparison.csv`。PSS 含 374.85 MiB 的可回收测试录像映射页，正式产品不必保留这份映射。相机采集、面捕、显示是三个不同帧率，不能混为一个数字。

`--atlas` 直接图集实验未通过边缘像素验证；`--atlas-copy` 精确 GPU 拷贝通过像素对比，但约 16.68 FPS，更慢。这两条实验路径保留用于复现，推荐配置不启用。所有操作在应用内完成，未刷机、改频率或替换系统驱动。

官方接口依据：[OVR_multiview](https://registry.khronos.org/OpenGL/extensions/OVR/OVR_multiview.txt)、[OVR_multiview2](https://registry.khronos.org/OpenGL/extensions/OVR/OVR_multiview2.txt)、[GPU 图像拷贝](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_copy_image.txt)。

2026-10-01 阶段，`scripts/convert_face_models.py` 仅完成与运行时匹配的 RKNN Toolkit2 1.3.0 编译，记录源文件 SHA-256，尚未验证 Android 面捕接入；因此该日上述联合测试的 NPU 负载为 0%。2026-10-02 的实际 NPU 面捕结果见下一节。

- [Google Face Landmarker Android](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android)
- [MediaPipe Android 平台要求](https://ai.google.dev/edge/mediapipe/solutions/setup_android)
- [Android GLSurfaceView](https://developer.android.com/reference/android/opengl/GLSurfaceView)
- [Android GLES30](https://developer.android.com/reference/android/opengl/GLES30)
- [Android ADB](https://developer.android.com/tools/adb)

### 实际 NPU 面捕与 UI 余量（2026-10-02）

`--delegate RKNN` 直接通过持久 JNI 上下文运行原任务包中的人脸检测与 478 点图像网络。RKNN Toolkit2 / runtime 均为 1.3，保留原厂 0.7.2 驱动；只新增应用内库与模型，不刷机，不更换系统库，不调整时钟。模型、运行时和输入归一化的校验值在 `npu-asset-manifest.json`。

52 表情模型在匹配的编译器上报常量折叠形状错误，因此保留官方 CPU FaceBlendshapesGraph；姿态继续用 FaceGeometryFromLandmarksGraph，视频平滑采用原 OneEuro 参数（min cutoff .05、beta 80、derivative cutoff 1）。丢失人脸后重建后处理图以重置滤波状态。这里的 NPU 后端可输出 478 点、52 表情和 16 个姿态矩阵值，渲染器仍使用 4 个诊断形变，正式角色尚未接入。

Tasks AAR 的动态 proto 工厂没有注册 NormalizedLandmarkList；后处理图使用原生 Matrix→Tensor→Landmarks 转换，其 Matrix 输入不做图像归一化。部分 calculator 的 Java options 类也未打包，按官方 proto 写入选项字段，由本机原生图解析。存在度取 `Identity_1` logit，经 sigmoid 后用 .5 阈值；`Identity_2` 辅助输出不作为人脸存在度。

精度对照使用相同解码图像的独立 IMAGE 模式：36 个时间采样产生 30 组不同的参考关键点数据，均有完整输出。478 点 XY 平均 RMSE 为源图约 0.171 像素，最大单帧 RMSE 0.431 像素；52 表情平均绝对误差 0.00147，最大单系数误差 0.0467。这是本地录像上的一致性检查，不是摄像头标定后的真实位置精度。另取 3 组真实裁剪输入逐字节传给原 TFLite 和板上 RKNN，关键点输出 RMSE 为 256×256 裁剪坐标中的 0.022–0.027 像素。无人脸、跟踪时画面变黑和重新捕获三个回归均通过。

资源对照使用同一个 APK、真实 USB 采集/原生 MJPEG 解码/原生打包/RGA，以及额外的录制动态人脸读取和转换；面捕限制 10 FPS。每视点 240×720、20 视点、最终 1200×1920、每视点 20,480 三角形，pitch=10 按子像素解释、tan=.2777777。为保留 UI 余量，`--render-fps 31` 在 GL 提交前以单调时钟节流；实际呈现速率以完整 SurfaceFlinger 时间戳计算。

| 同帧率条件 | 时长 | 实际呈现 FPS | CPU | GPU busy | NPU busy | 最低 MemAvailable |
|---|---:|---:|---:|---:|---:|---:|
| 原 GPU 面捕 | 180 秒 | 30.784 | 55.83% | 87.94% | 0% | 945.93 MiB |
| NPU 图像网络 + CPU 后处理 | 180 秒 | 30.772 | 51.79% | 76.10% | 16.37% | 988.73 MiB |
| NPU + 动态原生 UI | 300 秒 | 30.736 | 55.14% | 78.86% | 18.04% | 973.59 MiB |

同预算迁移 NPU 释放约 11.85 个百分点的 GPU busy，CPU 降约 4.04 个百分点。UI 夹具包含原生半透明面板、文字和 96 根动态柱形，请求每 100 ms 重绘；完整 UI/RenderThread/GPU 成本已计入系统数据。它不能代表未来复杂 WebView 页面。NPU 用于 AI 模型，GL 多视点渲染和交织仍在 GPU 上，UI 的布局与绘制继续使用 CPU/GPU。

UI 联合运行 300.04 秒，各分钟实际呈现为 30.812 / 30.737 / 30.804 / 30.745 / 30.612 FPS；面捕 9.865 FPS、完整输出率 99.73%，USB 采集 24.446 FPS、0 次采集失败。UI 实际重绘 8.528 FPS，PSS 峰值 458.31 MiB，thermal_zone0 峰值 81.111°C。随后不限帧率的 60 秒容量测试为 36.86 FPS、GPU 93.28%、CPU 63.64%；因此后续 UI 功能宜使用有节流的配置。温度是在连续顺序测试中测得，不能用这些组次推断 NPU 本身的温升；正式外壳内的长时散热还未测试。

`validate_npu_results.py` 的 49 项检查全部通过，包括全部分钟达到 30 FPS、完整呈现历史无缺口、相同 APK、相同图像预算、NPU 硬件计数、全套面捕输出、原模型数值和无人脸恢复。测试后 ADB=device、adb_enabled=1，设置和 HOME 均 Status: ok，原厂 fingerprint、驱动、vendor runtime 哈希保持不变；测试进程已停止、NPU busy 归零，录制 NV21 校验值保持不变。健康记录在 `post-test-health.json`。

```powershell
& $benchPython -X utf8 scripts/prepare_npu_assets.py `
  --detector E:/tripo/output/npu-face-optimization/20261002/npu-models/face_detector.rknn `
  --landmarks E:/tripo/output/npu-face-optimization/20261002/npu-models/face_landmarks_detector.rknn
& E:/tripo/device-lab/scripts/build_native.ps1
# 使用原有 Java/Gradle 构建步骤 assembleDebug，并 adb install -r。
& $benchPython -X utf8 scripts/run_npu_comparison.py --output E:/tripo/output/npu-face-optimization/20261002
& $benchPython -X utf8 scripts/validate_npu_results.py --root E:/tripo/output/npu-face-optimization/20261002
```

`--ui-load` 为额外原生 UI 负载；`--render-fps 0` 保留不限帧率测试。实际相机直接驱动面捕用 `--face-input camera`，录像参考联合负载用 `--face-input replay`。APK 为 `E:/tripo/output/npu-face-optimization/20261002/NpuOffload.apk`，SHA-256 `44855aa84d0794a711c0e6792c77d3e969f13ab67b85dcc5a5356ebcca05b889`。原 2026-10-01 GPU APK 保留在原输出目录，可用 `adb install -r` 回退，用户数据和录制文件保留。NPU 版本也保留 `GPU` 后端；不要用旧 `NPU` 枚举冒充本后端。

原始结果、呈现时间戳、采样曲线、精度数据在新输出目录；`npu-performance-comparison.csv` 和 `npu-validation.json` 是审计入口。375 MiB NV21 测试录像的驻留映射页计入 PSS，不能当作正式应用的必要内存。未来模型能否运行 NPU 取决于这套旧编译器支持的算子，需要逐模型转换和验证。

- [Rockchip RKNN 1.3 C API](https://github.com/airockchip/rknpu2/tree/v1.3.0)
- [Google 人脸检测图](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/cc/vision/face_detector/face_detector_graph.cc)
- [Google 关键点、存在度与平滑图](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/cc/vision/face_landmarker/face_landmarks_detector_graph.cc)
- [Google CPU 裁剪坐标](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/calculators/tensor/image_to_tensor_converter_opencv.cc)
- [Google Matrix 转换不执行图像归一化](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/calculators/tensor/tensor_converter_calculator.proto)

### 提高有效 NPU 利用率（2026-10-02）

新增 `--npu-pipeline`，仅用于 `RKNN` 的 camera/replay 测试。NPU 对当前帧裁剪和推理时，单个 CPU worker 完成上一帧的原始 MediaPipe 表情、平滑和姿态图。提交下一个 CPU 任务前等待上一项结束，最多一项待完成任务；CPU 结果完成后立即通过有序回调更新渲染权重，不等待下一帧 NPU。没有扩大图像网络、降低关键点数量或省略表情计算。默认串行路径保留。

输入 Bitmap 只在 NPU 调用期间读取；CPU worker 持有独立关键点数组。丢失人脸后的滤波重置标记随对应帧传递，避免主线程和 worker 共享可变重置状态。计数前、测量结束和关闭原生资源前均等待回调完成，worker 异常会传回测量线程。`inference.mean_ms` 在流水线模式表示生产者耗时，不能当作完整面捕延迟；完整输入推理到表情输出的平均延迟在 `npu_pipeline.completed_face_latency_mean_ms`，不含此前相机排队和 YUV 转换。

`NpuPipelineCheckActivity` 用同样顺序的 24 帧对照串行和流水线的视频平滑结果，其中包含两次黑屏及随后恢复。检查全部 478 点、52 表情、16 个姿态值、回调数量/顺序和丢脸恢复。对应模型、阈值和归一化仍使用前一节已验证的资产。

```powershell
& $benchPython -X utf8 scripts/run_npu_headroom.py --output E:/tripo/output/npu-headroom/20261002
# 单独运行留更多 CPU 余量的 15 FPS 配置；五分钟达标的最高已测档为 --analysis-fps 17。
& $benchPython -X utf8 scripts/run_case.py npu-balanced `
  --delegate RKNN --input camera --face-input replay --analysis-fps 15 --npu-pipeline `
  --render-fps 31 --ui-load --render scene --views 20 --scale .375 --width-scale .2 `
  --pipeline --cull --preblend --multiview --explicit-lod --pitch 10 --tan .2777777 `
  --pitch-units subpixels --conversion-backend rga --native-packing --seconds 180 `
  --output-dir E:/tripo/output/npu-headroom/20261002
```

这是原板上测试程序的可选择调度模式；正式角色、复杂 UI 和光学标定仍未接入。NPU 占用提高来自处理更多有效人脸帧，CPU 的每帧表情和姿态成本也会随之增加。未来增加其他 AI 模型应按模型转换并验证，不应把 NPU 占用率本身当成优化目标；多视点 GL 渲染和 UI 绘制继续使用 CPU/GPU。

线程接口依据：[Android 单 worker Executor](https://developer.android.com/reference/java/util/concurrent/Executors#newSingleThreadExecutor())、[Future 完成与内存可见性](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Future.html)。

同一 APK、20 视点 / 每视点 240×720 / 输出 1200×1920、真实 USB 采集解码转换、独立录制动态人脸参考、动态原生 UI、31 FPS 渲染预算下，最终对照如下：

| 面捕配置 | 时长 | 实际面捕 FPS | 实际呈现 FPS | CPU | GPU busy | NPU busy | 达到显示目标 |
|---|---:|---:|---:|---:|---:|---:|---|
| 10 FPS 串行参考 | 60 秒 | 9.878 | 30.761 | 56.08% | 76.97% | 18.18% | 是 |
| 20 FPS 串行请求 | 60 秒 | 13.893 | 30.812 | 64.06% | 77.81% | 25.91% | 是，短测 |
| 15 FPS 流水线 | 180 秒 | 14.724 | 30.770 | 65.70% | 78.49% | 25.95% | 是，三分钟 |
| 17 FPS 流水线 | 300 秒 | 16.437 | 30.455 | 72.09% | 82.40% | 29.72% | 是，五分钟 |
| 20 FPS 流水线 | 300 秒 | 18.194 | 29.766 | 77.42% | 85.51% | 34.15% | 否，后段掉帧 |
| 不限制面捕的容量短测 | 60 秒 | 19.454 | 30.636 | 77.56% | 77.55% | 41.41% | 是，仅一分钟 |

17 FPS 档各分钟实际显示为 30.745 / 30.662 / 30.395 / 30.387 / 30.103 FPS，完整面捕结果 16.410 FPS、覆盖率 99.84%，最低 MemAvailable 975.54 MiB、PSS 峰值 461.40 MiB、最高温度 81.666°C。相对同 UI 的 10 FPS 参考，有效面捕吞吐率约提高 66%，NPU busy 增加约 11.55 个百分点。CPU 每帧后处理平均 37.18 ms，完整推理到输出平均 80.70 ms；吞吐率提高不表示单帧耗时下降。375 MiB 的 NV21 参考映射驻留页计入 PSS。

20 FPS 流水线各分钟实际呈现为 30.278 / 30.311 / 29.845 / 29.719 / 28.678 FPS，因此排除在达标配置之外。NPU 达到 41.41% 的一分钟容量值也不能用作长时间 UI 联合负载的结论。测试为顺序进行且起始温度不同；温度与时钟采样存在变化，未禁用原厂调频或热保护，不能只凭这些组次给温度变化作因果归因。五分钟验证不覆盖正式外壳内的长时散热。给后续复杂 UI 留更多余量时可采用 15 FPS 档，正式 UI 和角色接入后应重测。

流水线回归的 24 帧、22 次完整人脸输出与串行结果在 478 点、52 表情、16 个姿态值上最大差异均为 0，两次无人脸和恢复通过。原 CPU IMAGE 模型的 36 个参考时间采样复核也通过：XY 平均 RMSE 0.171 源像素、最大单帧 0.431；52 表情平均绝对差 0.00147。参考内容和前一阶段相同，不能作为新数据集上的泛化精度。

`validate_npu_headroom.py` 的 74 项数据完整性、数值与所选档位目标检查通过。审计将 17 FPS 档选为五分钟达标配置，20 FPS 长测保留为 `meets_display_target=false` 和 `display_target_rejections`，未删除失败记录。原始采样、完整实际呈现时间戳、`headroom-comparison.csv`、`headroom-validation.json`、`npu-pipeline-check.json` 和 `quality/` 均在 `E:/tripo/output/npu-headroom/20261002`。

```powershell
# 在安装此次 APK 后执行回归，随后导出 files/npu-pipeline-check.json 到报告目录。
adb shell am start -n com.mirror.bench/.NpuPipelineCheckActivity
adb shell run-as com.mirror.bench cat files/npu-pipeline-check.json
& $benchPython -X utf8 scripts/run_npu_quality.py --output E:/tripo/output/npu-headroom/20261002/quality
& $benchPython -X utf8 scripts/validate_npu_headroom.py --root E:/tripo/output/npu-headroom/20261002
& $benchPython -X utf8 scripts/check_npu_device_health.py --output E:/tripo/output/npu-headroom/20261002/post-test-health.json
```

APK 为 `NpuHeadroom.apk`，SHA-256 `4c54b1b69e29ac47cdb66cbc16341e49cfdd67ec498641ad82ba89adfbde7334`；对应源码 `NpuHeadroomSource.zip`。前一阶段 `NpuOffload.apk` 保留，可用 `adb install -r` 回退；测试应用未清数据。测试后 ADB、桌面、设置、原厂 fingerprint、0.7.2 驱动、vendor runtime 和录制文件哈希均正常，应用已停止，NPU busy 归零。

### 精确输出尺寸与更高的单视点图像预算（2026-10-02）

目标按宽×高解释为交织输出 1920×1200、每视点 720×400；保留至少 16 视点和实际显示 30 FPS 的要求。最终测试采用 20 视点，RGBA8 颜色缓冲约 21.97 MiB。每视点从 240×720 的 172,800 像素增加至 288,000 像素，增加 66.7%。每视点仍绘制 20,480 个三角形、8 个材质批次和 4 个诊断形变，所有 20 个独立投影均参与每帧输出。

新增 `--view-width/--view-height` 显式指定每视点尺寸。设备原生方向为 1200×1920，厂商固件没有按 Activity 的横屏请求旋转屏幕。使用 `run_quality_case.py` 临时锁定系统旋转至 1 后，实际 GL 表面和截屏均为 1920×1200；没有修改 wm size、density 或物理分辨率。包装脚本在操作前写旋转日志，退出时停止测试应用，并恢复原来的 user_rotation 和 accelerometer_rotation。直接调用 `run_case.py --orientation landscape` 不足以保证此固件实际输出横屏尺寸，必须以结果和截屏为准。

新增 `--discard-depth`：交织窗口不分配未使用的深度缓冲，离屏视点的深度只在当前视点/四视点批次内部使用，批次结束后丢弃其存储；颜色保留供交织采样，深度在下一次绘制前清空。实测窗口深度从 24 位降至 0 位。同 APK、20 视点、720×400、完整 NPU 面捕请求 10 FPS、USB 与 UI、不限制渲染的 60 秒比较为 33.003→33.279 实际显示 FPS，增益约 0.84%。这项改动是小幅减少带宽消耗，不能单独解释全部达标结果。

`--shared-phase` 是 pitch=10 RGB 子像素、20 视点条件下共享 RGB 相位计算的实验。逐像素校验没有差异，但横屏短测为 32.983 FPS，低于未启用时的 33.279 FPS，因此最终配置关闭。较早的竖屏 400×720 实验约 29.94 FPS，不能算作本次指定 1920×1200 / 720×400 的达标证据。

稳定参考配置将面捕请求设为 17 FPS，NPU 和上一帧 CPU 后处理重叠，GL 提交预算 31 FPS，真实 USB 与动态原生 UI 同时运行五分钟：

| 指标 | 实测 |
|---|---:|
| 实际屏幕呈现 | 30.751 FPS |
| 每分钟实际屏幕呈现 | 30.745 / 30.762 / 30.770 / 30.762 / 30.712 FPS |
| 面捕调用 / 完整人脸覆盖 | 16.636 FPS / 99.84% |
| USB 摄像头采集 / 失败 | 24.433 FPS / 0 |
| 系统 CPU / GPU / NPU 平均占用 | 66.25% / 88.81% / 30.42% |
| 完整推理到表情输出的平均延迟 | 71.30 ms |
| NPU 关键点调用 / CPU 表情姿态后处理 | 30.95 / 30.83 ms |
| 最低 MemAvailable / PSS 峰值 | 919.25 / 458.60 MiB |
| thermal_zone0 最高温度 | 78.75°C |
| 原生 UI 实际重绘 | 8.518 FPS |

分段调用时间含共享设备上的等待，不能当作独占硬件时间；CPU 四核合计归一化为 100%。PSS 包含约 375 MiB 可回收的 NV21 参考录像映射页。摄像头确实采集、解码、打包和转换；完整面捕使用另外转换的本地动态人脸录像，不包含 MP4 解码。所有成本都包含在联合资源采样中。

五种姿态/表情、所有视点的原路径与组合优化完整交织图比较为 0 RGB 字节差异、0 alpha 差异；单独丢弃深度的检查也为 0 差异。最新 APK 的串行/流水线回归检查 24 帧、22 次完整输出，全部 478 点、52 表情和 16 个姿态值完全一致，黑屏和恢复通过。模型数值精度沿用前述独立 TFLite/RKNN 资产检查。此处的渲染质量提高指单视点图像预算提高和优化没有改变诊断图像，尚未证明正式角色的视觉质量或屏幕光学对齐。

```powershell
& $benchPython -X utf8 scripts/run_quality_case.py quality-native17-depth-5min `
  --orientation landscape --view-width 720 --view-height 400 `
  --delegate RKNN --input camera --face-input replay --analysis-fps 17 --npu-pipeline `
  --seconds 300 --render-fps 31 --ui-load --render scene --views 20 `
  --pipeline --cull --preblend --multiview --explicit-lod --discard-depth `
  --verify-discard-depth --verify-combined --pitch 10 --tan .2777777 `
  --pitch-units subpixels --conversion-backend rga --native-packing --screenshot `
  --output-dir E:/tripo/output/render-quality-optimization/20261002
& $benchPython -X utf8 scripts/check_npu_device_health.py `
  --output E:/tripo/output/render-quality-optimization/20261002/post-test-health.json
& $benchPython -X utf8 scripts/validate_render_quality.py `
  --root E:/tripo/output/render-quality-optimization/20261002
```

此配置为诊断网格与原生文字/96 柱形图 UI 的性能验证；正式角色、复杂页面和实际外壳内的长时散热需要在接入后重新测量。NPU 用于支持的神经网络，GL 渲染、交织及 UI 绘制不能通过提高 NPU 占用率直接迁移。更多有效 AI 任务需逐模型验证这套 RKNN 编译器与驱动支持的算子；不运行无用任务来提高占用数字。

在同一最新 APK、精确尺寸、USB、UI 与渲染预算下，进一步提高面捕请求至 20 FPS：120 秒测试的面捕调用 19.098 FPS、NPU 35.61%、实际显示 30.740 FPS；随后不降温直接执行 300 秒测试，完整结果如下。短测 NPU 值不能当作五分钟平均值。

| 同尺寸面捕档位 | 完整人脸输出 FPS | 实际显示 FPS | CPU | GPU busy | NPU busy | 最高温度 |
|---|---:|---:|---:|---:|---:|---:|
| 17 FPS 请求，300 秒 | 16.610 | 30.751 | 66.25% | 88.81% | 30.42% | 78.750°C |
| 20 FPS 请求，300 秒 | 19.142 | 30.613 | 72.79% | 90.04% | 33.88% | 80.555°C |

高频档的各分钟实际显示为 30.754 / 30.770 / 30.737 / 30.378 / 30.436 FPS，完整人脸覆盖 99.86%，USB 24.498 FPS、0 采集失败；最低 MemAvailable 916.50 MiB、PSS 峰值 463.38 MiB、原生 UI 重绘 8.517 FPS。完整推理到输出的平均延迟 75.23 ms，NPU 关键点调用平均 30.62 ms，CPU 表情与姿态后处理平均 34.06 ms。吞吐率提高也伴随 CPU 负载和单帧延迟增加。两组起始温度不同，温度差不能单独归因于频率调整。

20 FPS 请求是当前尺寸下通过五分钟联合验证的高频档；若需要为复杂 UI 和新功能留更多余量，使用 17 FPS 请求。将上面的命令改为运行名 `quality-native20-depth-5min` 和 `--analysis-fps 20` 即可复现高频档。审计命令加 `--name quality-native20-depth-5min` 选择此记录，默认审计 17 FPS 档。两个档位各自的 37 项检查、原始时间戳、旋转日志与零差异图像证明均保留；此前另一尺寸下 20 FPS 长测掉帧的失败记录仍保留在 `npu-headroom/20261002`，不与本次尺寸混淆。

`E:/tripo/output/render-quality-optimization/20261002` 保存 `RenderQuality.apk`、`RenderQualitySource.zip`、`render-quality-validation.json`、`render-quality-performance.csv` 和 `npu-frequency-comparison.csv`。APK SHA-256 为 `f8bbb2e02add5ccebf11606a0269839f1db7aa8eda9ae0a35d5c6b2950e79ce5`，与测量和设备安装版本一致。源码归档附当前模型及原生库，构建仍需 Java 17、Android SDK 和 Gradle 依赖；个人录像不包含在源码归档中。可用前述 `NpuHeadroom.apk` 或 `NpuOffload.apk` 执行 `adb install -r` 回退，保留应用数据。测试结束后应用已停止、NPU 归零；ADB、HOME、设置、原厂 fingerprint、驱动、vendor runtime 与录像哈希均通过检查，原屏幕旋转设置恢复为 0。

- [Android GLES30 深度附件失效接口](https://developer.android.com/reference/android/opengl/GLES30#glInvalidateFramebuffer(int,%20int,%20int[],%20int))
- [Android Activity 方向请求](https://developer.android.com/reference/android/app/Activity#setRequestedOrientation(int))
