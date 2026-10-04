# 主运行界面 EGL 上下文释放/恢复诊断

这是默认关闭的 debug 生命周期入口。主机 RED→GREEN 与 SDK 链接检查之后，**v17 的 16×400×640、legacy FBO / per-frame VP，以及 v18 的持久化 16×400×640、persistent FBO / per-frame VP，均已在 camera_replay 条件下完成一次真实主界面 HOME/context 重建功能验证**；同 Activity 的 context 1→2、新 session、完整面捕与 USB 进展均已独立核对。旧结果见 [v17 设备记录](runtime-400640-device-validation.md#主界面-home--egl-重建功能验证)，v18 组合见下文。默认20对照、cached VP 组合恢复及其他生命周期条件仍未验收。最初实现期间的冻结 v16 30 分钟负载没有包含此切片。

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

由设备负责人构建并绑定 APK/hash；已完成 v17 和下文 v18 的指定条件，未来默认配置对照和其它组合仍须分别采集。对应 v17 条件的命令示例：

```powershell
python scripts/run_runtime_check.py --name avatar-context-release-16-90s --input camera_replay --seconds 90 --output-dir E:/tripo/output/mirror-program/20261003 --view-count 16 --view-preset 400x640 --active-target-fps 31 --pause-at 25 --pause-seconds 8 --release-gl-on-pause
```

初启和 HOME 恢复复用同一 extras 字符串，显式 true 各出现一次。缺省不发键。采样器仅有原先的初次/最终 force-stop；中间 HOME 后使用原 Activity 重排到前台，不用 force-stop 伪造 context 恢复。

新增独立 `gl_context_recovery` 功能证据门；默认 `requested=false, verified=false`。请求但没有完整 HOME/resume 或采集整体不完整时不会通过。通过需满足：

1. 单次 HOME/resume 的设备前后时钟完整且有序，暂停前存在有效 Activity/session/model/view/context/frame 身份。
2. 恢复后同 Activity、新 runtime session/epoch，preserve 配置明确 false；新 context generation 大于原值，ready 的 frame generation 与新 context 完全相同。
3. 恢复后至少两份同 session、同 context 的 INTERACTIVE 有脸状态，年龄 0…500 ms、478 landmarks/52 blendshapes；GL frame、推理完成/有脸、NPU mesh/post 计数继续增长，有相机输入时 USB received images 也继续增长。
4. 模型 SHA、输入方式、视点数/尺寸、FBO/VP 模式相同，全部捕获到的恢复后错误为空。缺字段、旧格式、无脸、旧 frame、上下文未变、Activity 重建、计数停止均不通过。

`waiting_for_new_frame_observed` 独立记录是否捕获到新 session 的 WAITING/ready=false。初始状态可能在轮询前已更新；缺少这一瞬时记录不会被编造为观察到。即使未采到 WAITING，功能门仍要求新 context 的明确成功 frame 和后续输入进展。此门不证明像素等价或 30 FPS，也不能扩张成未实际采集的后端组合验收。CLI 退出码继续表示采集完成与否；必须读取该门的 `verified/reason`，不能仅以退出 0 判功能成功。

## v18：持久化16视点与 persistent FBO 组合恢复

2026-10-03，设备负责人完成 90 秒采集，进程终态 exit0、`collection_status=completed`、`collection_errors=[]`，最后执行 `final_force_stop`。独立复核只读取已完成文件，没有使用正在进行的30分钟测试。目录为 `E:/tripo/output/mirror-program/20261003/400x640/`：

| 证据 | SHA-256 |
| --- | --- |
| `avatar-v18-persistent-context-release16-400640-90s.json` | `6bff7263d9ee43fab3667bc8dff932ce2c7a03edf9fdabe2354546626f748318` |
| `AvatarRuntime-v18-400640.apk`，与 raw 记录的设备 APK 哈希相同 | `c4cf831de43a80e85d59fe0382e14ef439b803219140c66b2580786d1cc04d84` |
| `collector-v18/run_runtime_check.py` | `8a0f55cfca36e8ef101f2d302382b7825d2001ac2cb0f63024097f2acfa2c195` |
| `collector-v18/device_profile.py` | `56fb783df3cee3bf9a6a183807667a3abb6a5691ea10854fdba521ace96eba32` |

复核先验证 collector manifest 的两个文件 SHA/大小，再仅调用其纯分析函数：`analyze_gl_context_recovery(statuses, host_actions, True)` 整个结果字典与 raw 完全相同；`analyze_run` 的 presentation、system、interactive_system 也全部相同。46 次原始状态读取中保留的18份状态，在移除主机观察时间后都能逐对象对应原始 JSON。设备序列号为 `6L32552009566714`。

该次命令只请求 `runtime_input=camera_replay`、persistent FBO 和 release-on-pause；初启/恢复均没有 `test_view_count`、`test_view_preset` 或 target FPS 覆盖。GUI 流程留下的 `v18-ui-final16-restarted-preferences.xml` 为 schema4 / 16 / 400x640 / 17，全部状态的 configured count 与实际 count 均为16。这是已保存产品配置的恢复，`debug_render_overrides.view_count=16` 在状态中记录解析后的值，不代表实际命令带了覆盖。配置保存流程的单独证据见 [产品16验收](runtime-product16-device-validation.md)。

- Activity 一直是 `ffb7d600-81d9-4b6e-8ea2-feb51f054f85`；session 从 `95a715c8-068b-4909-85ab-f4b380cfee07` 变为 `a1d62349-ca14-4132-baa8-babcb54f020d`，epoch 1→2。
- 捕获了恢复后的 `WAITING_FOR_GL`：旧 context=1，但 frame_generation=0、ready=false。随后真正 `onSurfaceCreated` 的 context=2、成功 frame_generation=2、ready=true；`waiting_for_new_frame_observed=true`。
- 恢复后10份 INTERACTIVE 状态全部有脸、478 landmarks / 52 blendshapes，结果年龄116–170 ms。USB received images 125→1231、NPU mesh calls 75→812、CPU post calls 75→811、completed frames 75→812、有脸完成75→811；GL frames 799→2185。
- 角色 `builtin-guide` 的 GLB SHA 始终为 `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407`，15,085顶点 / 29,482三角形 / 7 primitives，完整源映射、bounded CPU pose worker、individual draw。恢复后 morph updates 155→1472、uploaded bytes 22,046,640→210,446,688、applied pose input ID 161→1547，确认重新创建的实际角色继续变形与上传。
- 全部14份 INTERACTIVE（恢复前4份、恢复后10份）实际都是16×400×640，输出1200×1920、aspect=.625；FBO requested/actual 均为 `persistent_groups`，4个固定 FBO，VP requested/actual 均为 `per_frame`。面捕后端保持 RKNN detector/478 mesh + MediaPipe CPU52及pose，没有换入实验52参数模型。
- 控制配置保持默认：rotation=0、input reflection=false、interaction mirror=false、无 session neutral/personal baseline、无控制错误；未绑定 camera ID/descriptor 的设置没有被本轮宣称为已校准。相机源仍为 Camera2 ID 0，capture_failures=0。捕获状态中的运行/GL/source/control 错误均为空，恢复次数为0。

因此 `gl_context_recovery.requested=true, verified=true`，仅证明上述指定组合的一次功能恢复。实际 USB 在采集，但 `camera_replay` 的推理人脸来自录像；这不是实时真人方向或中性校准验收。EGL 证据仍是框架合同与真实 `onSurfaceCreated` 世代变化，未记录原生 EGL 函数调用，也未在这轮重新进行像素核对。

**这轮不能作为完整性能通过记录。** 三段已确认互动区间合计69.624755371 s，呈现30.555116748 FPS，各段30.391945 / 30.600240 / 30.664945；HOME 附近存在 `state_event_ring_gap`（179395304598475→179404917774607 ns），末尾另有2.443582838 s未确认。尽管 SurfaceFlinger 历史本身无缺口，`complete_active_evidence=false`、`meets_30_fps=false`。不能把这些局部区间扩展为完整90秒或热态长时达标；[v17无HOME短时性能](runtime-400640-performance.md) 也不替代此组合的长期测试。默认20兼容、cached VP 组合恢复、异常驱动及更广泛生命周期仍待分别验证。

## 主机证据

先 RED：新增 CLI/输出门缺失、生命周期类/API 缺失；再验证缺初始模型/策略/尺寸、坏新 session/epoch 原本可误过，五种输入均先失败，再补完整身份门。独立审查发现中间 ERROR 状态即使错误详情为空也应拒绝，新增此反例先 RED，再补状态门 GREEN。SDK jar 首次沙箱读取被拒，随后获准读取已有 SDK；没有下载依赖或执行 Gradle。

```powershell
.\tests\run_runtime_gl_lifecycle_tests.ps1
.\tests\run_runtime_view_count_tests.ps1
python -m unittest discover -s tests -p test_runtime_metrics.py
python -m unittest discover -s tests -p test_gl_context_recovery.py
```

已通过 lifecycle 34 checks、collector 40 tests、恢复证据门 8 tests（多个拒绝子情形），原视点配置/矩阵/校准传播 12,895 checks、主渲染与预览姿态 3,000 项逐位对照也通过。生产三类先链接真实 SDK 35，再用已有 Bundle/SystemClock 值容器替身运行 host 测试；测试不模拟真正 Android View 或 EGL。旧 SDK runner 仅补新依赖源文件，避免使用上次 APK 的旧类。v17 legacy 与 v18 persistent/per-frame 的指定16视点恢复已分别绑定 APK 和完整 raw；两次 HOME 轮性能门都为 false，不能用于宣称30 FPS。默认20兼容对照和 cached VP 组合恢复仍须单独记录；三组离屏像素等价通过也不能代替组合生命周期验收。

`GLSurfaceView.onPause()` 自身仍可能等待平台 GL 线程；本切片没有承诺硬中断卡死驱动，也没有改变原硬件 owner 的异步取消/重开顺序。
