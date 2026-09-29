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
