# 16 视点调试档与独立验收入口

主机验证及 SDK 35 javac 检查已完成。**v16 真机 69×16 角色层严格 RGBA 零差异、6×4 全屏交织严格 RGB 零差异均通过；legacy/per-frame 的 320×576 和 400×720 两次 90 秒联合测试，完整已确认互动区间分别为 30.457170 / 30.400758 FPS。热态长期验收待执行。** 原始证据、APK/hash、完整计数和尾段限制见 [实机验收](../docs/runtime-view-count-device-validation.md)。默认仍为 20 视点；这是用户要求“至少 16 视点”的另一工作量档位，16 层比 20 层少 20% 视点工作，不是相同工作量优化，也不能证明 20 视点达到 30 FPS。没有降低模型、关闭面捕、重复中间相机、修改光学参数或把视点数写入用户偏好。

## 运行配置与校准传播

`RuntimeViewCount` 统一读取 `test_view_count`：未提供时为 20；显式覆盖只接受 debug APK 中的 Java `Integer` 16 或 20。String、Long、Float、Boolean、显式 null、其他整数和 release 中的任意显式覆盖均拒绝。`MirrorActivity.InputOptions` 的值直接用于创建 renderer，并写入顶层 `view_count`、`debug_render_overrides.view_count`；`renderer.view_count` 来自实际渲染器。

维护页 → `CalibrationActivity` → `PanelPreviewActivity` 两个真实 Intent factory 转发相同档位，两处接收者再次执行严格校验。debug 可显式传 16/20；release 默认 20 且不发 override。维护按钮、校准说明、预览按钮、预览状态、01–16 / 01–20 数字颜色图以及实际 renderer 层数一致。校准保存仍只保存原有 panel 参数；view count 不进入 `MirrorSettings`。pitch、tan、phase、RGB/BGR、上下原点和反转方向的意义不变，光学确认标志仍为 false。

16 个相机仍覆盖完整端点 `eyeX=-0.2…+0.2`，由原表达式 `(view/(float)(views-1)-0.5f)*0.4f` 均分，OVR4 分组 base 为 0、4、8、12。不是取 20 个相机中的前 16 个。现有 renderer 用实际 `views` 分配层数并向交织 shader 传 `uCount`；持久 FBO、VP 缓存原有的 count/key 校验仍适用。此切片没有修改正常渲染数学、shader、clear/depth/invalidate、同步、默认 target 31 或默认候选开关。

采样器新增 `--view-count {16,20}`；缺省不传 extra，应用继续 20。初次启动和 HOME 恢复复用同一个 extras 字符串，原始 JSON `arguments.view_count` 记录请求。不会通过共享偏好改变档位。

## 主机证据

```powershell
.\tests\run_runtime_view_count_tests.ps1
.\tests\run_camera_vp_sdk_tests.ps1
.\tests\run_persistent_fbo_sdk_tests.ps1
python -m unittest discover -s tests -p test_runtime_metrics.py
```

- 先 RED：新 view-count CLI/启动 extra 缺失；新严格像素/profile helper 缺失。补校准传播也先 RED，真实 factory/helper API 未实现导致新用例编译失败，然后实现后 GREEN。
- `RuntimeViewCountTest` **12,895 checks**：默认/严格类型/debug-release 边界、两级真实 Intent factory、两种实际 panel renderer 配置/报告文件、参数透传、16 个均分矩阵与缓存组的 raw bits、首末端点与 20 档相同、中间相机并非截取、重复中间矩阵/1 ULP/NaN 拒绝、严格 RGB/alpha 门，以及六种光学配置均可选中全部视点、每视点卡片与每通道编码独立。
- 采样器 **38 tests**：实际 `run()` 的初启及 HOME 恢复命令均包含一次相同 16/20 extra；默认两次均不发；CLI 非法值拒绝；JSON 保留请求且不写偏好。测试使用离线 fake ADB，不执行设备命令。
- SDK 35 直接 javac 编译变更 Android 源码与依赖；原 VP 配置 **19 checks**、持久 FBO 配置 **15 checks**通过。原多视图 fixture **4,048 checks / 69 姿态**通过。

`run_runtime_view_count_tests.ps1` 将所有本切片变更 Android 类从源码编译，旧 Gradle 输出仅链接未变依赖；真实 JSON-java 20240303 依赖沿用 SHA 校验。输出在 ignored `app/build/runtime-view-count-tests`。没有 Gradle、APK 安装或 ADB 操作。

独立只读 fresh review 已完成，无未解决 P1/P2。审查者独立运行 12,895 项配置/矩阵/传播检查与 38 项采样器测试，核对真实单视图 reference、16 个唯一矩阵/每姿态图像、严格零差异，以及校准传播/覆盖门。SDK 首次读取曾受沙箱限制，获准读取现有 SDK 后通过；没有下载依赖或改动设备。

主机 `Matrix` 为测试算术替身，`Intent`/`Bundle` 为值容器替身。它们不模拟 Activity 生命周期、GL、Android native `Matrix` 或 UI 点击，不能替代设备像素证据。测试中的 `Unsafe` 只实例化未调用 Android 生命周期的对象以读取配置/文件名，不进入生产代码。

## 真机门 1：真实角色 69×16 层，严格 RGBA 零差异

以下命令已由设备负责人在 v16 APK 执行，独立审查者离线核验完整原始结果通过。复测仍须绑定正确 serial 和本次 APK/hash，保留新 `run_id`，不要读取上次结果作为本次通过证据。

```powershell
adb -s <serial> shell am start -n com.mirror.bench/.AvatarPreviewActivity --ez verify_multiview_16 true
adb -s <serial> exec-out run-as com.mirror.bench cat files/avatar-multiview16-check.json
```

新入口与其他 verify flags 互斥，独立文件不覆盖 20 层报告。固定每层 **400×720**、物理 aspect **1200/1920=0.625**，相同正式 builtin GLB、52 输入、头/眼/下颌映射和法线策略。每姿态只 `prepare` 一次，两侧共用这份 VBO/world pose；参考使用真实单视图 shader 和独立 2D framebuffer，候选使用原 legacy OVR4 shader/array framebuffer。通过独立 read framebuffer 读取每个实际 array layer，不能从多视图 attachment 直接 readback。

必须核对完整结果：

- `verification_profile=avatar_16views_strict`、`comparison_backend=individual_serial_vs_individual_ovr4`，`views=16`、400×720、physical aspect 0.625；模型 SHA 与本次 APK 内实际 GLB 一致。
- `running=false`、`completed=true`、`passed=true`、非 cancelled，且无 error/cleanup_error；69 个 fixture 全部完成、`prepare_calls=69`、`layer_comparisons=1104`。核对 52 个孤立 source 全覆盖，不能只看总数。
- `matrix_gate` 对实际送入 shader 的 256 个 float 与独立原 renderer 表达式比较，`bit_mismatches=0`、`distinct_actual_matrices=16`、投影顺序严格单调。报告的 eye_min/max 本身不构成证明，位级 reference 检查才约束完整均分端点。此门实际运行平台 Android `Matrix`。
- 每姿态覆盖 global_view 0…15，base/relative 为 0/4/8/12 与 0…3；两侧各有 **16 个不同图像 SHA**，首末视图存在非零 RGB 差异。矩阵唯一性和图像唯一性均参与总 passed，避免两侧共享错误中间相机而同错通过。
- 每层非空、全不透明；`rgb_byte_mismatches=0`、`max_rgb_error=0`、RMSE 0、`alpha_mismatches=0`。允许值预设为 0，1 byte 不同也失败，不根据结果事后放宽。姿态变化必须真实改变输出。

旧 20 层入口、文件名和原允许误差语义保留；新 16 层门更严格。该门在交织前比较角色层，**不验证最终光学相位、异步调度或 FPS**。候选明确是 legacy OVR4；此结果不能单独宣称 16 层 persistent/cache 组合的 GPU 像素也通过。此前 v15 的 20 层严格缓存像素结果及包含 16 档的实际 Matrix 检查是相关证据，不等于本次组合设备门。

## 真机门 2：16 视点全屏 CPU/GPU 交织 oracle

已完成 1200×1920 真机检查：24 行、每行 6,912,000 RGB 分量、全部 16 视点覆盖，总 165,888,000 RGB bytes 严格零差异。下面保留复测命令。

```powershell
adb -s <serial> shell am start -n com.mirror.bench/.PanelPreviewActivity --ei test_view_count 16 --ez verify true
adb -s <serial> exec-out run-as com.mirror.bench cat files/panel-pixel16-check.json
```

实际 `createRenderer` 使用 16 层；`verifyPanelPixels` 使用 renderer 的 `views`，向每层填充各通道唯一的无损编码。原有六组参数（默认、phase、BGR、反转、TOP、单位/负 slope 组合）× 四条 GPU 路径（array、explicit LOD、atlas、lookup），每条独立对照 `PanelCalibration.viewIndex` 的 CPU float oracle，遍历物理输出全部 RGB 分量，仍要求零错误。

新增每行 `oracle_view_component_counts` 长度必须 16、每项 >0、总和等于 `width*height*3`，`oracle_all_views_reached=true`。总 passed 同时要求所有 24 行均到达全部视点且所有 RGB byte 完全一致，不能只测某个子集而通过。结果每次写入均带实际 `view_count=16`，运行阶段/错误也带 count。20 档仍用 `panel-pixel-check.json`。检查新 run_id/context generation、实际物理宽高、running false、无取消/错误和全部 24 行，不把旧文件或中间阶段当结果。

另需沿真实 UI 走维护→校准→预览，确认数字/颜色为 01–16，并在取消返回后主 runtime 仍是 16；本次直接启动预览的结果不代替该点击流程。保存/默认仍只影响原有校准参数，不产生 view-count 偏好。软件像素门检查的是给定参数的实现，**不会自动证明 pitch/tan/phase 与实屏光学对齐**。

## 联合负载性能与功能边界

例（由设备负责人执行，所有档位比较必须使用同 APK 与相同其他 flags）：

```powershell
python scripts/run_runtime_check.py --name avatar-v16-16views-90s --input camera_replay --seconds 90 --output-dir E:/tripo/output/mirror-program/20261003 --view-count 16 --view-preset 320x576 --active-target-fps 31
```

以上为已测 legacy/per-frame 配置。20 档对照只把 `--view-count` 改为 20；400×720 质量基线只把 `--view-preset` 改为 `400x720`，单独标注，不能混作 320×576 同工作量 FPS。`--persistent-fbos --cached-camera-vp` 是另行候选，其 16 档组合像素/性能不能沿用本次通过结论。HOME 功能检查可追加 `--pause-at 20 --pause-seconds 8`，必须核实恢复命令/新输入 session、实际 count=16、请求与实际模式、帧计数及面捕继续增长；若明确另测 persistent/cache，再核 groups=4、缓存 key。恢复不是强制主 EGL context 丢失证据，本轮没有新增 context-loss 开关。

报告真实 SurfaceFlinger 呈现，以完整确认的 INTERACTIVE 区间为准；缺状态历史、SF gap 或未确认尾段不得冒称整段持续达标。仍保留完整 USB + 回放面捕、NPU 推理/CPU 表情、52 输入/真实 GLB、相同材质法线与 UI 状态。分别检查 CPU/GPU/NPU、温度/频率、PSS、面捕 fps/新鲜度和所有 errors。target31 桶的 callback 墙钟/细分统计不等同 INTERACTIVE 时间窗，更不是 GPU 独占计时。

90 秒结果只用于筛选。追加同 APK 的 16×400×720 / legacy/per-frame / 完整 camera_replay 5 分钟筛查已完成：确认互动 289.910194 秒、30.466463 FPS，9 段全部 ≥30（最低 30.137387），最高温 80.555°C、已有 CPU 降频；3.945310 秒尾段未确认。完整证据见实机验收文档。30 分钟稳定性仍待验证。此前 20 档 v15 均值偶尔超过 30，但首个完整互动区间仍低于 30，且热状态/顺序有影响，不能作为 16 档长期通过证据。
