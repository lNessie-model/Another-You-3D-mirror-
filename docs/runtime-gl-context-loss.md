# 主运行界面 EGL 上下文释放/恢复诊断

这是默认关闭的 debug 生命周期入口。主机 RED→GREEN 与 SDK 链接检查之后，**v17 已在 16×400×640、legacy FBO / per-frame VP、camera_replay 条件下完成真实主界面 HOME/context 重建功能验证**；同 Activity 的 context 1→2、新 session、完整面捕与 USB 进展均已独立核对，详见 [v17 设备记录](runtime-400640-device-validation.md#主界面-home--egl-重建功能验证)。默认20对照、组合候选恢复及其他生命周期条件仍未由这轮验收。最初实现期间的冻结 v16 30 分钟负载没有包含此切片。

## 触发方式及默认兼容

`test_release_gl_on_pause` 缺省 false；仅 debug APK 接受显式 Java Boolean true/false。release 包提供此键的任何值、非 Boolean、显式 null 均拒绝，经过现有配置错误路径报告 ERROR。检查使用 `Bundle.containsKey`，不把 null 当成缺省。

`MirrorActivity` 在设置 renderer 前调用 `surface.setPreserveEGLContextOnPause(!flag)`。默认仍为原 true；启用后为 false。HOME 仍走真实 `onPause()`→`GLSurfaceView.onPause()`，恢复走已有 `onResume()`，没有手动删除 GL 对象、破坏驱动、改系统参数或另建 GL owner。

Android 文档规定 false 时暂停释放并在恢复时重新创建 EGL；true 只表示允许保留，并不保证绝不会丢失。[GLSurfaceView 官方接口](https://developer.android.com/reference/android/opengl/GLSurfaceView#setPreserveEGLContextOnPause(boolean))。`onSurfaceCreated` 在渲染线程首次启动及 context 丢失后的重建调用，旧 context 资源由 EGL 释放。[Renderer 官方接口](https://developer.android.com/reference/android/opengl/GLSurfaceView.Renderer#onSurfaceCreated(javax.microedition.khronos.opengles.GL10,%20javax.microedition.khronos.egl.EGLConfig))。独立审查也核对了 [AOSP Android 11 源码](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/opengl/java/android/opengl/GLSurfaceView.java)：暂停且不 preserve 时进入 stopEglContextLocked/EglHelper.finish，创建 EGL context 后才调用 onSurfaceCreated。

这个 flag 作用于此主 GLSurfaceView 的所有 pause，包括原有校准预览暂停；正式测试只执行 HOME。它不控制校准页内部的小预览 Surface。view count、尺寸、光学、pose、材质/法线、渲染数学、性能 target 及默认候选开关都不变，不保存偏好。

## 可观察字段和晚帧门

- 顶层 `activity_instance_id` 在 Java Activity 实例构造时生成一次 UUID。HOME 恢复应相同；Activity 重建或进程重启为新值，不能冒充同实例 context 恢复。
- `gl_context_policy.release_on_pause_requested`、`preserve_on_pause_requested` 和 `preserve_on_pause_actual` 分别记录请求与 `getPreserveEGLContextOnPause()` 返回的实际配置。actual 表示 getter 配置，不是已经发生释放的证明；scope 字段明确区分。
- renderer 的 `context_generation` 只在真实 `onSurfaceCreated` 增加。首次为 1；此前为 0。进入新 context 的首条操作即撤销 ready，再沿原有分配和旧 CPU worker 停止路径重建。
- `frame_generation=0` 表示当前许可尚无成功 GL frame；成功提交且 `checkGl()` 通过后才标记为本 context generation。`runtime_gl_frame_ready` 还要求 renderer error 为空。它不表示实际呈现，呈现仍以 SurfaceFlinger 为准。
- pause、resume、尺寸变化都会撤销 ready，并推进许可 epoch；每个 frame 入场捕获 epoch，完成时只能发布相同 epoch。一个在 HOME 或 resize 前进入的旧 frame 即使较晚成功，也不能把新阶段标 ready。正常已 ready 的连续帧不分配 Snapshot、不增加锁。

context 与 frame generation 来自一个 volatile 不可变快照，状态读取不会把新 context 和旧 frame 拼在一起。EGL 释放的实际证据是框架合同、真实 HOME 动作、同 Activity 的新 `onSurfaceCreated` 世代及新成功 frame；没有插入原生 `eglDestroyContext`/`eglCreateContext` 调用跟踪，不能声称捕获了这些原生函数事件。

## 设备执行与恢复门

由设备负责人构建并绑定 APK/hash；已完成 v17 上述单一条件，未来默认配置对照和其它后端仍须分别采集。对应 v17 条件的命令示例：

```powershell
python scripts/run_runtime_check.py --name avatar-context-release-16-90s --input camera_replay --seconds 90 --output-dir E:/tripo/output/mirror-program/20261003 --view-count 16 --view-preset 400x640 --active-target-fps 31 --pause-at 25 --pause-seconds 8 --release-gl-on-pause
```

初启和 HOME 恢复复用同一 extras 字符串，显式 true 各出现一次。缺省不发键。采样器仅有原先的初次/最终 force-stop；中间 HOME 后使用原 Activity 重排到前台，不用 force-stop 伪造 context 恢复。

新增独立 `gl_context_recovery` 功能证据门；默认 `requested=false, verified=false`。请求但没有完整 HOME/resume 或采集整体不完整时不会通过。通过需满足：

1. 单次 HOME/resume 的设备前后时钟完整且有序，暂停前存在有效 Activity/session/model/view/context/frame 身份。
2. 恢复后同 Activity、新 runtime session/epoch，preserve 配置明确 false；新 context generation 大于原值，ready 的 frame generation 与新 context 完全相同。
3. 恢复后至少两份同 session、同 context 的 INTERACTIVE 有脸状态，年龄 0…500 ms、478 landmarks/52 blendshapes；GL frame、推理完成/有脸、NPU mesh/post 计数继续增长，有相机输入时 USB received images 也继续增长。
4. 模型 SHA、输入方式、视点数/尺寸、FBO/VP 模式相同，全部捕获到的恢复后错误为空。缺字段、旧格式、无脸、旧 frame、上下文未变、Activity 重建、计数停止均不通过。

`waiting_for_new_frame_observed` 独立记录是否捕获到新 session 的 WAITING/ready=false。初始状态可能在轮询前已更新；缺少这一瞬时记录不会被编造为观察到。即使未采到 WAITING，功能门仍要求新 context 的明确成功 frame 和后续输入进展。此门不证明像素等价或 30 FPS，不能扩张成 16 persistent/cache 组合的验收。CLI 退出码继续表示采集完成与否；必须读取该门的 `verified/reason`，不能仅以退出 0 判功能成功。

## 主机证据

先 RED：新增 CLI/输出门缺失、生命周期类/API 缺失；再验证缺初始模型/策略/尺寸、坏新 session/epoch 原本可误过，五种输入均先失败，再补完整身份门。独立审查发现中间 ERROR 状态即使错误详情为空也应拒绝，新增此反例先 RED，再补状态门 GREEN。SDK jar 首次沙箱读取被拒，随后获准读取已有 SDK；没有下载依赖或执行 Gradle。

```powershell
.\tests\run_runtime_gl_lifecycle_tests.ps1
.\tests\run_runtime_view_count_tests.ps1
python -m unittest discover -s tests -p test_runtime_metrics.py
python -m unittest discover -s tests -p test_gl_context_recovery.py
```

已通过 lifecycle 34 checks、collector 40 tests、恢复证据门 8 tests（多个拒绝子情形），原视点配置/矩阵/校准传播 12,895 checks、主渲染与预览姿态 3,000 项逐位对照也通过。生产三类先链接真实 SDK 35，再用已有 Bundle/SystemClock 值容器替身运行 host 测试；测试不模拟真正 Android View 或 EGL。旧 SDK runner 仅补新依赖源文件，避免使用上次 APK 的旧类。v17 的16视点单一条件实际恢复已另行绑定 APK 和完整 raw；该 HOME 轮性能门为 false，不能用于宣称30 FPS。默认20兼容对照和 persistent/cache组合恢复仍须单独记录；三组离屏像素等价通过也不能代替组合生命周期验收。

`GLSurfaceView.onPause()` 自身仍可能等待平台 GL 线程；本切片没有承诺硬中断卡死驱动，也没有改变原硬件 owner 的异步取消/重开顺序。
