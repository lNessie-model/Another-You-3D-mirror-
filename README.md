# YS-L6 设备性能实验

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

NPU 独立验证使用 RKNN 1.3.0 官方 MobileNet 模型，3000 次推理平均约 5.64 ms，实际 RKNPU 峰值 87%。这是分类模型性能。本机 MediaPipe 1.0.0 的枚举虽含 NPU，其 Java acceleration 转换只处理 CPU/GPU；请求 NPU 的实际日志显示 XNNPACK CPU 执行，NPU 采样为 0%，不能据此声称面捕已使用 NPU。完整面捕的 NPU 路径需要 RKNN 模型转换和完整的裁剪、跟踪、478 点及 52 表情后处理接入。原生依赖来源、哈希、构建方法见 `native/DEPENDENCIES.md`。

Java 17、Gradle 8.5、AGP 8.2.2、compileSdk 35、build-tools 35.0.1；目标 Android 11，minSdk 24，MediaPipe `tasks-vision:1.0.0`，仅 arm64 原生库。模型和依赖来源及 SHA-256 在 `asset-manifest.json`，`prepare_assets.py` 可下载同版本资产。电脑上已有其余依赖缓存，本次构建已完成；首次在另一台机器上构建需要 Android SDK 与网络。

- [Google Face Landmarker Android](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android)
- [MediaPipe Android 平台要求](https://ai.google.dev/edge/mediapipe/solutions/setup_android)
- [Android GLSurfaceView](https://developer.android.com/reference/android/opengl/GLSurfaceView)
- [Android GLES30](https://developer.android.com/reference/android/opengl/GLES30)
- [Android ADB](https://developer.android.com/tools/adb)
