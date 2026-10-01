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

Java 17、Gradle 8.5、AGP 8.2.2、compileSdk 35、build-tools 35.0.1；目标 Android 11，minSdk 24，MediaPipe `tasks-vision:1.0.0`，仅 arm64 原生库。模型和依赖来源及 SHA-256 在 `asset-manifest.json`，`prepare_assets.py` 可下载同版本资产。电脑上已有其余依赖缓存，本次构建已完成；首次在另一台机器上构建需要 Android SDK 与网络。

- [Google Face Landmarker Android](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android)
- [MediaPipe Android 平台要求](https://ai.google.dev/edge/mediapipe/solutions/setup_android)
- [Android GLSurfaceView](https://developer.android.com/reference/android/opengl/GLSurfaceView)
- [Android GLES30](https://developer.android.com/reference/android/opengl/GLES30)
- [Android ADB](https://developer.android.com/tools/adb)
