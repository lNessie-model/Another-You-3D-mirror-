# 持久 OVR4 FBO 设备验收记录

2026-10-03，v14 固定 FBO 候选通过 **69 个真实角色姿态 × 20 个独立视点、每层 400×720 的严格 RGBA 零差异检查**，并完成一次 HOME 恢复短时功能验证。同 APK 20×320×576 联合 A/B/A 中，候选实际呈现 29.995271 FPS，高于两轮 legacy 的 29.067147 / 29.014171；随后候选 target 35 为 29.847574，回到 31 为 28.788098，且温度与频率采样明显改变。**全部五轮都未通过完整已确认区间的 30 FPS 门，尚无稳定收益/达标结论。** 生产默认仍为 legacy、target 31，候选需要显式 debug opt-in；光学效果及长期生命周期未据此验收。

## 证据身份与独立核对

原始证据位于 `E:/tripo/output/mirror-program/20261003`：

| 证据 | SHA-256 |
| --- | --- |
| [v14-persistent-fbo-pixels.json](../../output/mirror-program/20261003/v14-persistent-fbo-pixels.json) | `15eda2569dc1c1970467c8f6c877d9c948271e360007b045034429a50f2299b1` |
| [avatar-v14-build-manifest.json](../../output/mirror-program/20261003/avatar-v14-build-manifest.json) | `809de875b6e425c9c4157ced057ed9663270b4b661c5247fc587d69d509cf11c` |
| `AvatarRuntime-v14-persistent-fbo.apk`，34,677,576 bytes | `5065ab25be68c6da397f473c40bea21ccb9c01b7ef8da212cdc91fb2e3b45257` |
| APK 内 `assets/avatars/builtin-guide/character.glb` | `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407` |

独立审计重新计算了上述文件及 APK 内模型的 SHA；模型 SHA 与报告 `avatar.model_sha256`、构建清单和本地资产一致。构建清单记录 `source_files_stable_through_build=true`，其 96 个 source/asset/build 文件的当前字节 SHA 全部与清单一致。APK 身份来自构建清单及归档 APK 复算，像素 JSON 自身没有 APK SHA 字段，不能仅凭该 JSON 推导安装包身份。

本次设备执行由 root 完成并确认作业 terminal exit 0。审计只读本地完成后的证据，没有重新操作 ADB、编译 APK 或改动生产源码。

## 固定验收门与执行范围

入口为 debug `AvatarPreviewActivity --ez verify_persistent_fbos true`，调用 `AvatarPersistentFboCheck.run`。本次 `run_id=af1e37ef-361b-486f-a188-d1d4eb87ceeb`，`context_generation=1`。设备报告 ARM Mali-G52、OpenGL ES 3.2、`max_ovr_views=4`。

两侧均为真实 `builtin-guide`：15,085 vertices、29,482 triangles、7 primitives，`draw_backend=individual`、`pose_backend=synchronous_gl_thread`、`normal_policy=recompute-deformed`。每姿态只调用一次 `scene.prepare`，两侧共用其 VBO、世界矩阵、shader 及对应的四个视图矩阵。`prepare_calls=69`；`avatar.morph_updates=70` 还包括 Scene 创建时的初始中性态，不代表每侧或每个视点重复变形。

baseline 在每组重新 attach color/depth；candidate 使用 5 个固定 FBO，其 base 为 0/4/8/12/16、每组 4 层。两侧每组都 clear color/depth、draw、invalidate depth；`depth_clear_and_invalidate_both_paths=true`。诊断各自使用独立 color/depth array 存储，避免覆盖彼此；这份额外存储仅用于验证，生产候选共享原有一套纹理存储。

像素按每个 global layer 通过独立单层 read FBO 读取，范围为交织前的所有 20 层，每层 400×720、physical aspect 0.625。没有从 OVR 多层附件直接 ReadPixels。报告中旧字段前缀 `serial_*` 在这里代表 legacy OVR4 reference，实际比较不是单视图 renderer；`comparison_backend=legacy_ovr4_vs_persistent_ovr4` 和 `reference_label` 已明确这一点。

阈值在设备运行前已固定：`max_rgb_error_allowed=0`、`rmse_allowed=0`、`alpha_error_allowed=0`。`AvatarPersistentFboCheck.pixelsMatch` 除沿用非空和全不透明检查，还要求 RGB byte mismatches 与 alpha mismatches 均为 0；没有因设备结果而放宽门槛。实现及主机资源验证见 [tests/persistent-fbo.md](../tests/persistent-fbo.md)。

## 完整结果复算

审计没有只采信顶层 `passed`，而是遍历所有姿态和所有 layer：

| 项目 | 独立核对结果 |
| --- | --- |
| 终态 | `running=false`、`completed=true`、`passed=true`、`cancelled=false`；无 error / cleanup_error |
| 姿态 | expected / completed / 实际数组长度均 69，名称无重复 |
| 单源 | 52 个 source index 恰为 0..51，逐个核对对应 one-hot 52 维权重、零头角 |
| 其它姿态 | 中性态、6 头角、4 视线、3 下颌/闭嘴、3 组合；名称、权重和头角与预设 fixture 一致 |
| 层覆盖 | 1,380 个比较；每姿态 global view 恰为 0..19，base / relative / eye_x 与该 view 一致 |
| RGB | 所有层 max error = 0、RMSE = 0、RGB byte mismatches = 0 |
| Alpha | 所有层 alpha mismatches = 0；两侧 nonopaque pixels 均为 0 |
| 对应图像 SHA | 所有层两侧 SHA-256 相同，且均为有效 64 位十六进制 |
| 非空 | 每层前景 33,796–40,267 pixels，两侧相等；高于非空门 288 pixels |
| 独立视点 | 每个姿态的 20 个视点 SHA 全部不同；两侧首末视点 RGB byte mismatches 均为 67,457–91,725 |
| 姿态变化 | 67 个姿态中心图像与中性不同；只有 `neutral` 和 `source-_neutral` 相同，符合中性源语义 |

每侧比较数据合计 1,589,760,000 RGBA bytes。这个字节数表示逐层累计读取规模，不是同时分配的内存。检查耗时 `diagnostic_elapsed_ms=358907.050562`；Activity 起始至最终发布为 363.791840927 秒，包含额外预览/初始化开销，二者均不是实时渲染 FPS。

## 结论边界与待追加证据

本结果支持：在本次 APK、设备驱动、真实模型、69 个确定性姿态和固定 400×720 × 20 工作量下，持久 FBO 与 legacy OVR4 的交织前 RGBA 输出逐字节一致。全部视点和绝大多数姿态的图像确有变化，排除了本组样例中空画面、重复相机或永久中性态造成的假通过。

本结果没有覆盖异步 pose worker 调度、真实摄像头/NPU 联合负载、交织后输出、面板光学对齐、真人左右动作与美术体验；JSON 保留 `performance_evidence=false` 和 `artwork_validated=false`。也不能将 v12 `attach_clear` 的约 13.95 ms 直接当作候选可节省的 GPU 时间：该值包含附件操作、clear 和可能的隐式 driver stall，是 CPU 墙钟区间。

下节同 APK 性能 A/B/A 使用同样的模型、20 个视点、320×576、async individual、输入/NPU路径、校准和 active target 31，仅切换 `--persistent-fbos`；400×720 保留为独立质量基线。实际 `persistent_groups` / FBO count 5、成功帧、错误状态及 SurfaceFlinger 历史已分别核对，没有用请求参数代替实际执行状态。

HOME 测试应保留相同 Intent extra，暂停中不 force-stop，并检查新输入 session、恢复完成帧、相机/worker 生命周期和真实呈现。`MirrorActivity` 请求保留 EGL context，因此 HOME 恢复成功不能证明强制 context loss；当前状态也没有专门的 GL generation / resize 计数，不能仅凭 frames 增长宣称同 context 任意尺寸重建已实机覆盖。另一次冷启动可以证明全新进程/context 初始化，仍不能替代旧 renderer 丢失 context 的恢复场景。现有资源主机测试及源码审查覆盖部分失败清理、context ownership 和 resize 顺序，这些边界应继续与实机证据分列。

## HOME / 恢复短时功能验证

已完成 [avatar-v14-persistent-home-60s.json](../../output/mirror-program/20261003/avatar-v14-persistent-home-60s.json)，SHA-256 `e44587309f4897f74c31d08224531d20bb568ae320797d3fc90dd4e123983010`。root 作业 terminal exit 0；collector `collection_status=completed`、`collection_errors=[]`，APK SHA 与上表 v14 相同。

参数为 60 秒 `camera_replay`、20 秒时 HOME、8 秒后恢复、20×320×576，`persistent_fbos=true`、synchronous=false、batched=false。`active_target_fps` CLI 未显式覆盖，所有 status 的实际配置均为默认 31。已初始化样本均为 `bounded_cpu_worker` + `individual`，模型 SHA 未变。11 个状态样本中，第一个处于 `uninitialized/count=0/frames=0`；其余 10 个均为 `persistent_groups/count=5`、`runtime_gl_frame_ready=true`，output 为 1200×1920。所有样本的 app/renderer error 为空，render/processing fault 为 false，retained frame samples 为 0。

首启动和恢复的实际 `host_actions.command` 都传入 `--ez test_persistent_fbos true --es test_view_preset 320x576`，恢复另带 `-f 0x20020000`。HOME 命令在同一 shell 中先收尾旧 SurfaceFlinger 历史，再读取 device clock、执行 HOME、再次读 clock。force-stop 仅在整个测试开始和结束各一次；没有写偏好、`settings put` 或 `wm size`。

| 观察点 | status / runtime session | 主 renderer frames | 当前输入 session completed |
| --- | --- | ---: | ---: |
| HOME 前最后状态 | epoch 1 / seq 5 / `bf57ca9f-d4d9-4813-b202-e03d696692c6` | 448 | 222 |
| 恢复后首个已取得状态 | epoch 2 / seq 2 / `562a25ee-ec7a-44ee-baf0-27a6c0af1a31` | 483 | 1 |
| 恢复后最后状态 | epoch 2 / seq 7 / 同上 | 1,221 | 390 |

恢复 session 的 renderer 帧数逐样本增长，completed / face frames 最终均为 390，并重新进入 INTERACTIVE。这证明本轮恢复后新输入和候选绘制继续工作。输入计数已按 session 重置，不将 222 与 390 作跨 session 差分。

两段已确认 INTERACTIVE 区间的 SurfaceFlinger 历史均完整：分别约 14.730 秒 / 429 presented frames、24.894 秒 / 733 presented frames，报告区间 FPS 为 29.185731、29.492896。总体 `interactive_presented_fps=29.378813` 为当前报告的下界；`complete_active_evidence=false`、`meets_30_fps=false`。Surface 历史 gap 为 0，但状态历史有横跨 HOME/新 session 的 9.640745386 秒 gap，结束还有 4.496323061 秒未经最新状态确认的尾部，不能改成“整段完整达标”。这次短功能序列不是独立稳态性能 A/B。

额外的 `v14-home-observation-{activity,camera,threads}.txt` **实际采在恢复后**：activity 显示 MirrorActivity resumed，PID 11396 有 1 个 CameraAcquire、1 个 avatar-pose、1 个 MirrorRuntime，camera 存在该包 active client。它们支持恢复后的单实例观察，不能当作 HOME 当时的相机关闭或线程停止快照。camera 事件历史确有该 PID 于 11:42:00 DISCONNECT、11:42:09 CONNECT，但仍未得到 HOME 窗口内的独立线程快照。

| 恢复后补充证据 | SHA-256 |
| --- | --- |
| `v14-home-observation-activity.txt` | `e9d9fc3b1ba74b42c9963bf539450163f16ca6531d7bb4b4aaa585d0f0c5fe6e` |
| `v14-home-observation-camera.txt` | `208da499144c43c4a33c209e3e5645166a05db3f166f706ce5e4304ce3937165` |
| `v14-home-observation-threads.txt` | `57683639558687cb0ddea93ce00194d2a3b1cf26ee449af542312a2c4b016a4a` |

全收集窗口（含初始化、idle、HOME）的 12 次 PSS 样本均值为 171.30 MiB、峰值 308.70 MiB；这个范围不能解释为纯 INTERACTIVE 稳态，也不构成无泄漏或耐久结论。随后独立 B90 候选性能启动已覆盖另一次冷启动，没有为同样目的额外重复 20 秒测试。

## 同 APK、target 31 的 A / B / A 联合实验

执行顺序为 legacy A → persistent B → legacy A2；三次均 90 秒、collector 正常终结、`collection_errors=[]`。归档 APK 为上表同一 SHA，模型和几何未变。仅 `arguments.persistent_fbos` 不同，三轮均 `camera_replay`（USB 采集与既有录制素材回放面捕共同运行）、async pose worker、individual 绘制、20×320×576、1200×1920 输出、projection aspect 0.625、active target 31。镜头描述指纹、相机控制、pitch=10 SUBPIXELS / tan=0.2777777 / RGB / phase=0 / 正向视点 / BOTTOM 光学校准完全相同。NPU 路径仍为 RKNN detector + 478 landmarks，MediaPipe CPU 52 blendshapes + canonical pose，没有换成未验收的 52 表情 NPU 候选。

| 顺序 / 原始报告 | SHA-256 |
| --- | --- |
| [A：avatar-v14-legacy-a-90s.json](../../output/mirror-program/20261003/avatar-v14-legacy-a-90s.json) | `70ea134b8802757fa1d4cc177da558c06f77a832c88c7280e3b4a5effd1e25ce` |
| [B：avatar-v14-persistent-b-90s.json](../../output/mirror-program/20261003/avatar-v14-persistent-b-90s.json) | `18e9d1985c55e53aacd7ba8206161d7b3d40e4413b26f11afa96a68edf95e922` |
| [A2：avatar-v14-legacy-a2-90s.json](../../output/mirror-program/20261003/avatar-v14-legacy-a2-90s.json) | `81c5bfdc0c0605f95f2fa2dc8a7e0343deb90d2736c03cbe4e1d76e122c78955` |

遍历全部已初始化 status，A/A2 的 actual 均为 legacy/count 0，B 均为 persistent_groups/count 5，成功 GL 帧持续增长且无 app/render/processing error。每轮独立一次输入 session；每个 worker 都保持 3 输出槽、max pending / ready 各 1。首次 uninitialized 状态未被算作已执行候选。B 的独立首末 force-stop / start 也构成另一次全新进程候选初始化的功能证据。

### 实际呈现与已确认区间

独立审计从 `surface_windows` 原始 timestamps 去重，按已确认 INTERACTIVE 区间重新计算每段 `(frames−1)/(last−first)` 及按实际跨度加权的整体 FPS，与报告一致。没有把 startup、WAITING、GRACE 或未确认尾部并入。三轮 `complete_active_evidence=true`、所有区间 `complete_history=true`，state / surface history gaps、status errors、ambiguous layer 均为空；这里的“完整”仅指各自已确认区间。

| 指标 | A legacy | B persistent | A2 legacy |
| --- | ---: | ---: | ---: |
| 已确认 INTERACTIVE 时长（秒） | 84.696826 | 84.793532 | 79.797628 |
| 去重呈现采样跨度（秒） | 84.562822 | 84.680015 | 79.719666 |
| 加权实际呈现 FPS | 29.067147 | **29.995271** | 29.014171 |
| 第 1 区间 FPS | 28.013707 | 29.270926 | 28.117331 |
| 第 2 区间 FPS | 29.792351 | 30.496149 | 29.663201 |
| 第 3 区间 FPS | 29.805374 | 30.492614 | 29.839060 |
| 未确认尾部（秒） | 0.299391 | 0.887606 | 4.990464 |
| 全组 30 FPS 门 | false | **false** | false |

B 相对两轮 legacy 的整体 FPS 分别高 3.193% / 3.381%。A2 回到 legacy 后结果接近 A，为候选改动带来改善提供了顺序对照；仍只有一轮 B，温度、运行次序和输入实时节拍不是完全相同，不宣称所有运行条件下必然提升。B 的两个后段超过 30，但首段 29.270926、整体 29.995271 均不能四舍五入成达标；报告门只允许 0.000001 FPS 数值容差并要求各段达标。

### 资源、面捕与内存范围

下面资源均取 **已确认 INTERACTIVE 内** 的系统样本，CPU 利用率为整机比例。审计按每个独立区间重建 CPU tick pairs（A/B/A2 分别 77/75/73 对），没有跨排除区间相减，并独立复算 GPU/NPU/PSS 均值及峰值。

| 指标 | A | B | A2 |
| --- | ---: | ---: | ---: |
| 资源样本数 | 80 | 78 | 76 |
| CPU 整机均值 | 87.921% | 85.989% | 87.864% |
| GPU busy 均值 | 88.200% | 92.090% | 87.882% |
| NPU busy 均值 | 36.263% | 37.282% | 36.474% |
| 温度范围（℃） | 67.500–75.625 | 71.666–78.125 | 73.888–78.750 |
| App PSS 均值 / 峰值（MiB） | 364.33 / 463.49 | 358.67 / 458.78 | 357.28 / 456.60 |
| PSS 样本数 | 16 | 15 | 15 |
| 面捕 face FPS，末输入 session 累计 | 15.5333 | 15.7387 | 15.5427 |
| inference completion 均值（ms），同上 | 96.027 | 94.588 | 96.947 |

最后两行是输入 session 的在线累计指标，包含采集/idle，分母不同于上面 INTERACTIVE 系统样本。B 没有通过关闭面捕获取 FPS；该轮 face fraction 为 99.852%，A/A2 为 99.850% / 99.841%。

PSS 仍需长期观察：A/B 全窗口分别从约 107.14 / 104.48 MiB（约 5 秒）增至 463.49 / 458.78 MiB（约 89 秒），两条曲线相近，不能把 B 略低当作 FBO 内存优化。末原始 meminfo 中主要增量分类为 Other mmap（A 355,329 KiB / B 350,796 KiB，其中 Private Clean 分别 354,388 / 349,972 KiB），Java Heap 约 40 MiB、Native Heap 约 38–39 MiB。仅据这些分类不能确定具体映射来源或断言无泄漏；也不能将其归因于候选新增纹理，因为候选未新增纹理存储。A2 的全窗口最后 PSS 为 461.49 MiB，表中 456.60 MiB 是已确认 INTERACTIVE 样本峰值，未混用。

### 有界阶段计时：CPU 墙钟，非 GPU 独占时间

以下读取末 status 的 target 31 bucket：它累计自 GL Scene/统计 reset 以来成功的稳定 target callbacks，包括可能的同 target GRACE，**不是严格 INTERACTIVE 窗口**。A/B/A2 分别 2,476 / 2,556 / 2,326 callbacks，12,380 / 12,780 / 11,630 四视图 groups。所有已发布样本的 missing=0，groups=5×callbacks；逐样本验证五个粗段之和等于 callback work、六个视图细段之和等于 view submission，误差小于 0.0000001 ms（JSON double 表示误差）。单位为每 callback 平均 ms，五组已累计在同一 callback 中。

| 阶段 / 辅助指标 | A | B | A2 |
| --- | ---: | ---: | ---: |
| callback work | 29.732680 | **24.167541** | 29.567415 |
| pre views | 0.164598 | 0.177343 | 0.165093 |
| avatar prepare（submit/acquire/upload） | 1.478174 | 1.610633 | 1.646990 |
| view submission | 21.627114 | **15.779727** | 21.342536 |
| └ setup | 0.074715 | 0.103409 | 0.082102 |
| └ attach/bind + clear | 13.893071 | **7.088648** | 13.941400 |
| └ camera matrices | 0.941379 | 0.945156 | 0.951289 |
| └ scene draw | 6.507441 | **7.378901** | 6.147330 |
| └ invalidate | 0.199081 | 0.249608 | 0.207035 |
| └ view tail | 0.011427 | 0.014004 | 0.013379 |
| interlace submission | 5.712301 | 5.770588 | 5.615390 |
| submit tail | 0.750493 | 0.829251 | 0.797404 |
| pacer wait，work 外 | 1.400105 | 4.583711 | 1.558499 |
| callback gap，work 外 | 3.246142 | 4.562906 | 3.321076 |
| fence wait，已包含于 pre views/work | 0.083101 | 0.064221 | 0.068399 |

相对 A，B 的 attach/bind+clear 减少约 6.804 ms，但 scene draw 增加约 0.871 ms，其它阶段也有变化；净 callback work 减少约 5.565 ms，不能把 attach 段的全部减少当作净收益。这些 CPU 墙钟区间包含驱动调用、线程调度及隐式 driver stall；不除以 INTERACTIVE 资源样本时长来推算“GPU 占比”。Fence 是粗段子集，不能再加到 callback work 上。

另外，末 Scene 的 applied-pose 在线累计 rig/morph/copy/upload 是并发 worker 口径，并非以上可相加的 GL 阶段。A/B/A2 的 morph 均值为 10.117 / 10.431 / 10.291 ms，copy 为 0.598 / 0.630 / 0.647 ms，upload 为 0.447 / 0.481 / 0.462 ms，pose age 均值为 33.981 / 33.103 / 33.840 ms。候选没有将 CPU morph 变形消除，也不能将这些并发时间重复计入 GL work。

### 后续 target 31 / 35 单变量对照

B31 的 callback work 已下降，而 pacer wait 从约 1.4 ms 增至 4.58 ms，因此进行了同一候选的显式 `--active-target-fps 35`，再回到 31 的试验。旧 v10 legacy 的 35 无收益不自动适用于这次新路径；同样，减少睡眠不保证吞吐改善，GPU 已约 92% busy，更多提交可能增加排队、面捕竞争或热负载。以下已完成结果不修改默认 target 31。

## 候选 target 35 / 回到 31 的顺序复测

| 原始报告 | SHA-256 |
| --- | --- |
| [avatar-v14-persistent-target35-90s.json](../../output/mirror-program/20261003/avatar-v14-persistent-target35-90s.json) | `5c3a13d263646f54cc9927f720fa206ee57b4b06367ee5d407238b732e2c52b3` |
| [avatar-v14-persistent-target31-repeat-90s.json](../../output/mirror-program/20261003/avatar-v14-persistent-target31-repeat-90s.json) | `346bb6ca5a364c4495909fc93ffaab3de8710115b838ae80ce964304fbe009c0` |

两轮仍为同一 APK/模型、20×320×576、camera_replay、async individual、persistent_groups/count 5、同相机/光学校准和 NPU 路径。所有已初始化状态核对通过，只有 debug active target 分别显式 35 / 31；没有修改默认代码。collector 均正常终结、errors 为空，root 已在各轮结束 force-stop。原始 SF timestamps、CPU 分组及阶段守恒再次独立复算通过。

| 指标 | 较早 B31（便于比较） | 后续 35 | 再回 31 |
| --- | ---: | ---: | ---: |
| 整体已确认实际 FPS | 29.995271 | 29.847574 | 28.788098 |
| 三个区间 FPS | 29.270926 / 30.496149 / 30.492614 | 29.777165 / 30.051214 / 29.543908 | 28.896087 / 28.609500 / 28.949662 |
| 已确认 INTERACTIVE 秒数 | 84.793532 | 84.773864 | 84.599411 |
| 未确认尾部秒数 | 0.887606 | 0.826203 | 0.825706 |
| complete active evidence | true | true | true |
| 整组 30 FPS 门 | false | false | false |
| INTERACTIVE 资源样本数 | 78 | 78 | 80 |
| CPU 整机均值 | 85.989% | 86.922% | 86.219% |
| GPU busy 均值 | 92.090% | 92.077% | 92.475% |
| NPU busy 均值 | 37.282% | 37.372% | 36.288% |
| INTERACTIVE 温度范围（℃） | 71.666–78.125 | 74.444–79.375 | 76.250–80.555 |
| 全收集温度范围（℃） | 69.375–78.125 | 71.666–79.375 | 73.888–80.555 |
| INTERACTIVE PSS 均值 / 峰值（MiB） | 358.67 / 458.78 | 364.61 / 466.77 | 365.43 / 467.38 |
| PSS 样本数 | 15 | 16 | 16 |
| 面捕 face FPS，末输入 session 累计 | 15.7387 | 15.5835 | 15.5646 |
| inference completion 均值 ms，同上 | 94.588 | 96.723 | 96.934 |

上述两轮均无 state/surface history gap、status error 或歧义 layer；完整性只适用于表内已确认区间，不覆盖约 0.826 秒尾部。35 有一个区间略高于 30，但其余两段和整体未达门；回到 31 的三个区间都低于 30。较早 B31 的改善没有在较热的后续 31 重复中保持，因此不能从单个 B31 宣称稳定收益，也不能把 35 相比早期 B31 的下降当成等温条件下 target 35 无用的因果结论。

### 频率采样与条件差异

以下从已确认 INTERACTIVE 的原始系统样本逐项计数，单位 MHz；CPU 列仅是 `cpu0/scaling_cur_freq`，GPU 列来自同一 `gpu/load` 行的 `@...Hz`。它们是约每秒一次的瞬时采样次数，**不是按时长加权的频率驻留或完整调度轨迹**。

| 运行 | CPU0 MHz × 样本数 | GPU MHz × 样本数 |
| --- | --- | --- |
| A31 legacy | 1800×80 | 800×80 |
| B31 persistent | 1608×1，1800×77 | 800×78 |
| A2 legacy | 1608×5，1800×71 | 200×1，700×10，800×65 |
| persistent 35 | 1608×17，1800×61 | 600×5，700×20，800×53 |
| persistent 31 repeat | 1608×41，1800×39 | 400×3，600×10，700×43，800×24 |

后续温度上移与较低频率采样同时发生，足以说明几轮不是相同温度/频率条件；尚未读取完整 thermal cooling 状态和频率 residency，不能单凭这张表断言每次降频的原因。持续约 92% GPU busy 也不代表相同绝对 GPU 吞吐，因为实际频率已不同。

### target bucket 墙钟变化

口径继续是各轮末 status 的稳定 active-target bucket，而非严格 INTERACTIVE 窗口。后续 35 / 31 分别 2,542 / 2,448 callbacks、12,710 / 12,240 groups，细分 missing 均 0，五段/六段守恒。均值单位 ms：

| 指标 | 较早 B31 | 35 | 31 repeat |
| --- | ---: | ---: | ---: |
| callback work | 24.167541 | 25.284144 | 25.492194 |
| avatar prepare | 1.610633 | 2.096041 | 1.902266 |
| view submission | 15.779727 | 16.597823 | 16.610933 |
| bind + clear，view 子段 | 7.088648 | 7.549361 | 7.521968 |
| scene draw，view 子段 | 7.378901 | 7.547443 | 7.723250 |
| interlace submission | 5.770588 | 5.500053 | 5.887878 |
| pacer wait，work 外 | 4.583711 | 0.560736 | 1.742384 |
| callback gap，work 外 | 4.562906 | 7.637681 | 7.477973 |
| fence wait，pre views/work 子集 | 0.064221 | 0.180889 | 0.121640 |
| pose age，Scene applied-pose 累计均值 | 33.103009 | 33.334328 | 34.410145 |

35 的 pacer wait 确实减少，但 callback gap 增长、work 也增加，整体实际呈现没有高于早期 B31；后续 31 的 gap 仍高，不能都归因于 35 的提交策略。gap 包含 publication、EGL swap、框架和 OS 调度，不是已隔离的 swap/GPU 阻塞。两次后续 PSS 曲线仍与前面相似（约 104/105 MiB@5 秒至 466.77/467.38 MiB@89 秒），需保留长期内存观察，不能据五次短跑声称耐久。

当前可执行结论是：固定 FBO 的确定性图像一致性和短时恢复门已通过，计时显示附件重绑相关路径成本降低；联合呈现仍未稳定达到用户要求的“16 视点以上 30 帧”。本轮全部维持 20 独立视点、原几何和画质，没有通过关功能或重复视点达标。保持 legacy/31 默认和可回退 debug 选项，下一项实验须继续保留像素门、真实呈现门和温度/频率范围。
