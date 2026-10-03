# v16：16 视点实机像素与短时联合负载验收

2026-10-03，YS-L6，设备序列号 `6L32552009566714`。本记录由独立审查者离线核对原始 JSON、命令回执、APK、构建输入和 SurfaceFlinger 原始列；没有重新操作设备。

**16 视点的两项软件像素门通过。16 层 legacy OVR4 / per-frame VP 在 320×576 和 400×720 两个档位，各一次 90 秒联合采集，以及 400×720 一次 5 分钟采集中的完整、已确认 INTERACTIVE 区间均超过 30 FPS。** 30 分钟稳定性仍待验证；这也不是 20 视点或相同工作量的性能改善。默认仍为 20；本次仅用 debug 参数选 16，不写入偏好。

## APK、源码及原始证据

所有文件位于 `E:/tripo/output/mirror-program/20261003/`。下表为独立重算的 SHA-256。

| 文件 | SHA-256 |
|---|---|
| `AvatarRuntime-v16-view-count.apk`（34,687,516 bytes） | `728bf3ff354f5cd4ebc499c3e60ffd05afe3d694ac8687a66108e6fa378663e5` |
| `avatar-v16-build-manifest.json` | `e1d0e5c0bf16765f0dd946d9394c12938ce91b884e3718ed971b3b058027445c` |
| `avatar-v16-sources-before.json` | `3bbfea850119a7a3c6d6350e8d09135f14f7c93b439c506bf2406879d53047be` |
| `v16-avatar16-pixels.json` | `c45c2a84045162b2176a1c170d295ab9578582d9e2e0d3a876708c67765850dd` |
| `v16-panel16-pixels.json` | `8d740507ec089e79bb8ad15b65a95eced65753849172c3cdffeb487a4af67ab6` |
| `avatar-v16-legacy16-320576-90s.json` | `8bebbebe71dbb0c88901a04c00653cc73fcddb4701b1461945ce4309a190a373` |
| `avatar-v16-legacy16-400720-90s.json` | `8207a7b3c7c6a3ad9b5211208d6c2d1834aca58c245857f0a2233e4f52624fc7` |
| `avatar-v16-legacy16-400720-300s.json` | `a19659b90dfdd572409735d6a51af2d2122cac610c374c544216d25ccd46e0cf` |

构建清单的 99 个输入均与 before 清单一致，审查时逐文件重新计算也全部相同；清单记录构建前后稳定。两份像素采集命令记录均包含实际设备 APK SHA，与归档一致，最后一次读取的完整 JSON 与归档逐字段相同。角色与交织各自的新 run ID 为 `459b8a42-21c1-4d27-a9ec-c6cb193a56da`、`b5319a97-790b-4a14-adc8-b5c6fd007414`，context generation 均为 1。

独立打开 APK ZIP，内置 GLB SHA 为 `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407`，与像素报告及两次联合测试实际场景相同：15,085 顶点、29,482 三角形、7 primitives、52 输入完整映射。未替换实验 RKNN 表情模型：APK 仍包含原 CPU 表情 TFLite，SHA `4f36dded049db18d76048567439b2a7f58f1daabc00d78bfe8f3ad396a2d2082`。这是归档/构建输入一致性检查，不是可复现构建或设备远程证明。

## 角色层：69 姿态 × 16 个独立视点

设备 `Mali-G52` / OpenGL ES 3.2、OVR 最大单组 4 视点；入口 `AvatarPreviewActivity --ez verify_multiview_16 true`。报告的 profile 为 `avatar_16views_strict`，比较后端 `individual_serial_vs_individual_ovr4`，每层 400×720，物理投影比例 1200/1920=0.625。

- 69 个唯一 fixture 全完成，含 0…51 全部 52 个孤立 source（逐项检查 one-hot），每姿态只 prepare 一次。共 1,104 次层比较。
- 每姿态 global view 均完整为 0…15，base 为 0/4/8/12，relative 为 0…3；报告 eyeX 与原 float32 均分表达式逐位一致，完整覆盖约 -0.2…+0.2。
- 真机矩阵门对实际送入 shader 的 256 个 float 与独立原 renderer 表达式比较，0 bit mismatch、16 个不同矩阵、投影顺序严格单调。原始 JSON 保留门结果，没有保存全部 256 个数值；离线复核检查了门和实现，未声称从 JSON 再计算这些未存矩阵。
- 每个姿态两侧各有 16 个不同图像 SHA，对应层 SHA 全部相同；首末视点图像真实不同，67 个非中性姿态的中心图像相对中性发生变化。
- 每层 RGB byte mismatch、最大 RGB 误差、RMSE、alpha mismatch 均为 0；全不透明。每层前景像素 33,797…40,267，远高于非空门 288。
- 总计 **1,271,808,000 RGBA bytes**（其中 RGB 953,856,000），严格零差异。completed/passed 为 true，running/cancelled 为 false，无 error 或 cleanup_error。

参考使用独立的单视图 shader/2D FBO；候选使用 legacy OVR4/array FBO，再独立绑定读 FBO 读取每个 layer。两侧共享一次 prepare 的 VBO/world pose，因此此门隔离视图投影与提交，不验证异步姿态调度、相机或 NPU。完整 RGBA 图像没有全部归档；证据为绑定源码的设备比较计数和每层摘要。此门也不覆盖 **16 层 persistent FBO + cached VP** 的组合。

## 全屏交织：6 组参数 × 4 路径

入口 `PanelPreviewActivity --ei test_view_count 16 --ez verify true`。实际输出 1200×1920，16 层。24 行唯一 case/path 全齐，每行比较 **6,912,000 RGB bytes**，总 **165,888,000 bytes**，全部零差异。

四路径为 array、array explicit LOD、atlas、lookup；六组为默认、phase=.137、BGR、视点反转、TOP 原点、pitch=3.5 PIXELS/tan=-.125/phase=.375/BGR/reverse/TOP 组合。每行独立对照 CPU float oracle，所有通道采用可区分视点的编码；每行 16 项 `oracle_view_component_counts` 均大于 0，总和均为 6,912,000。各 case 中单视点分量计数最小值为 384,154 / 345,600 / 384,154 / 384,154 / 384,154 / 411,427，没有仅检查部分视点。

顶层和全部行 strict zero/full coverage 均通过，无取消或错误。`optical_alignment_verified=false`：这证明给定参数的软件映射，不能证明 pitch=10、tan=.2777777 与实际柱镜屏已完成光学校准。本次直接启动预览 Activity，不能据此代替维护→校准→预览的完整人工点击验收。

## 同时运行：两次 90 秒短测

两次 APK 一致；请求和实际状态均为 16 视点、legacy FBO、per-frame VP、individual draw、异步 bounded CPU pose worker、重算法线、target31。输出均 1200×1920。只改变每视点尺寸 320×576 / 400×720；未启用 batched/persistent/cache，也没有关闭 UI、USB 或面捕。原始文件的 collection completed、无 collection error，未执行 HOME。

审查从每次 SF 原始三列重新取第 2 列，去除 0/未完成值并全局去重，与存储 timestamps 全部一致。独立合并状态事件，按每段首末真实呈现时间使用 `(帧数-1)/(末时间-首时间)`；再复算采样器全部 presentation/system/interactive_system 字段，结果一致。窗口连续，无 SF/state gap 或多 Surface 歧义；边界保留原 34 ms 容差，FPS 判定仅允许 0.000001 数值舍入。

| 项目 | 16×320×576 | 16×400×720 |
|---|---:|---:|
| 实际采集秒数 | 90.797 | 90.547 |
| 已确认 INTERACTIVE 秒数 | 79.790965 | 79.776608 |
| SF 首末采样总秒数 | 79.652838 | 79.669066 |
| 完整段 1：帧数 / FPS | 1,047 / 30.268917 | 1,042 / 30.095248 |
| 完整段 2：帧数 / FPS | 1,065 / 30.597525 | 1,066 / 30.626487 |
| 完整段 3：帧数 / FPS | 317 / 30.614574 | 317 / 30.664529 |
| 完整段合并 SF FPS | **30.457170** | **30.400758** |
| 最后 status 后未确认尾段 | **5.064395 s** | **4.773887 s** |

两次三段之间均有短暂 GRACE；它们与启动、ACQUIRING、最后未确认尾段一起排除，不能称“完整 90 秒均在互动状态且持续 30”。本门约束完整区间平均值，不意味着每个 1 秒窗口或每帧期限都达到 30。两次温度/顺序不同，也不是随机顺序多次性能对照。

### 面捕及输入确实同时工作

`camera_replay` 仍实际开启 USB Camera2 并转换采集帧，同时以固定有人脸录制输入做推理；这能重复完整面捕负载，不代表当场真人面捕效果。每次唯一 runtime session，最后状态的完整计数如下（统计范围是当前输入 session，含 acquisition/idle，不能混称上述 SF 区间专用均值）：

| 项目 | 320×576 | 400×720 |
|---|---:|---:|
| 输入 session 秒数 | 80.613274 | 80.659341 |
| 完成推理 / 有脸帧 | 1,280 / 1,278 | 1,291 / 1,289 |
| 有脸比率 | 99.8438% | 99.8451% |
| 推理 / 有脸 FPS | 15.8783 / 15.8535 | 16.0056 / 15.9808 |
| USB 接收帧 / FPS | 1,978 / 24.5369 | 1,964 / 24.3493 |
| 检测 / mesh / CPU post 次数 | 3 / 1,280 / 1,278 | 3 / 1,291 / 1,289 |
| USB 转换均值 ms | 3.9190 | 4.1192 |
| NPU mesh 均值 ms | 41.1714 | 37.6355 |
| CPU 表情/姿态 post 均值 ms | 40.2261 | 40.9983 |
| 推理完成 / 接收到完成均值 ms | 91.6490 / 114.8514 | 89.1124 / 112.8382 |
| 抽样 INTERACTIVE 结果年龄范围 ms | 102…191 | 104…210 |

每次所有已就绪场景状态均保持同一内置模型 SHA、16 层和目标尺寸。状态确认完整 478 landmarks、52 blendshapes，RKNN 1.3 NPU detector+mesh 与 MediaPipe CPU 表情/姿态；runtime 1.3.0、driver 0.7.2，最多一个待处理 CPU post。控制拒帧数、恢复数、camera capture failures 均为 0；全部已保存状态的 input/render/control/runtime error 与 fault 均为空/false。

### 资源范围与计时边界

| 项目 | 320×576：互动 / 全采集 | 400×720：互动 / 全采集 |
|---|---:|---:|
| 资源样本数 | 77 / 88 | 76 / 87 |
| 全机 CPU 忙碌均值 % | 82.929 / 80.620 | 83.839 / 81.423 |
| GPU busy 均值 % | 78.987 / 74.318 | 85.382 / 80.632 |
| NPU busy 均值 % | 43.779 / 40.818 | 38.158 / 35.540 |
| 温度范围 °C | 59.444…68.125 / 57.222…68.750 | 65.000…72.777 / 62.222…73.333 |
| PSS 样本数 | 15 / 18 | 15 / 18 |
| PSS 均值 MiB | 355.961 / 329.841 | 359.876 / 333.008 |
| PSS 峰值 MiB | 455.245 / 459.554 | 461.668 / 464.926 |
| 最低 MemAvailable MiB | 891.086 / 890.582 | 889.063 / 888.902 |

互动 CPU 差分不跨 GRACE/未归属区间；CPU 是全机忙碌百分比，GPU/NPU 是设备计数器采样，不是本进程独占资源。全部 cpu0 频率采样均为 1.8 GHz，只能说明这些采样时刻；尚未到此前长测的高热稳定状态。

两次 PSS 都继续上升：最后约 10 秒分别 451.40→459.55、456.02→464.93 MiB。对应 Other mmap PSS 分别 342,348→350,604、348,352→356,416 KiB，Native Heap 基本稳定，支持文件映射逐步驻留是主要增量；这仍不足以在 90 秒内证明长期无泄漏或最终平台。回放文件映射与真实摄像头长期工作集不能等同。

最后状态 target31 桶分别有 2,440 / 2,438 个成功 callback，全部细分计数齐全；callback work 均值 24.5115 / 24.9093 ms，view submission 16.7417 / 17.3306 ms，interlace submission 5.7980 / 5.4413 ms，prepare 1.1077 / 1.2911 ms。五段总和与 callback work 守恒，view detail 的四组计数为 9,760 / 9,752。它们按 target 桶而非 INTERACTIVE 分段，包含 CPU、调度、driver 隐式等待；后台 morph 均值约 9.31 ms 与这些阶段并行，不能相加当成 GPU 独占耗时。

## 追加：400×720 的 5 分钟热态筛查

同 v16 APK、16 层、legacy/per-frame、camera_replay、target31，实际采集 300.906 秒。独立再次解析所有 SF 原始第 2 列并重算状态区间，以及采样器 presentation/system/interactive_system 全部字段，结果完全一致。没有 SF/state history gap、多 Surface 歧义、采集错误或状态错误；无 HOME 动作。

- 确认 INTERACTIVE 共 **289.910194 秒**，SF 首末采样共 289.531479 秒，合计 **30.466463 FPS**。
- 9 个完整区间 FPS 依次为 **30.196297、30.598366、30.642427、30.709771、30.598139、30.583588、30.310166、30.137387、30.327269**。每段均通过原 30 FPS 门，没有只用总体均值掩盖低于 30 的完整区间。
- 最后 status 后 **3.945310 秒**仍未确认；启动与短 GRACE 同样不混入。结果不能推导每秒/每帧都满足目标。
- 输入 session 291.831957 秒中完成 4,615 次、4,607 次有脸，15.8139 / 15.7865 FPS，99.8267% 有脸；保持 478/52 输出。USB 接收 7,111 帧，24.3668 FPS，无 capture failure。NPU mesh/CPU post 均值 41.0350 / 46.1195 ms；推理完成/接收至完成均值 98.3048 / 121.7714 ms。互动状态抽样结果年龄 118…216 ms；恢复和控制拒帧均为 0。

| 资源 | 互动区间 | 全采集 |
|---|---:|---:|
| 样本数 | 283 | 292 |
| 全机 CPU busy 均值 % | 83.618 | 82.972 |
| GPU busy 均值 % | 88.184 | 86.688 |
| NPU busy 均值 % | 43.212 | 42.301 |
| 温度范围 °C | 65.625…80.555 | 62.222…80.555 |
| PSS 样本数 | 56 | 59 |
| PSS 均值 / 峰值 MiB | 453.152 / 494.192 | 440.801 / 494.192 |
| 最低 MemAvailable MiB | 890.516 | 890.516 |

cpu0 频率采样 227 次为 1.8 GHz、64 次 1.608 GHz、1 次 1.416 GHz，已覆盖实际降频时刻。末段完整区间降至约 30.14 FPS，余量有限，仍应继续 30 分钟。约 180 秒后 PSS 接近 492 MiB，末 6 次为 492.99…494.19 MiB；Other mmap 末段约 377 MiB，Native Heap 约 38 MiB。相较 90 秒持续上涨，已观察到工作集接近平台，但这些短时样本不能排除缓慢泄漏或更长运行的内存变化。

## 尚未覆盖的验收

- 16 档最终 30 分钟稳定性、较长 PSS 平台和每段实际呈现；5 分钟筛查已通过上述限定范围。
- 16 档 HOME 恢复及强制主 EGL context 丢失；本次两轮没有这些动作。
- 16 档 persistent/cache 组合独立像素及性能；本次只证明 legacy/per-frame。
- 实屏光学对齐、角色美术和最终交互效果。软件像素正确不改变 `optical_alignment_verified=false`、`artwork_validated=false`。

复现命令及主机门见 [runtime-view-count.md](../tests/runtime-view-count.md)。下一步保留相同 APK/模型/flags 做热态长测；任何新优化需单独绑定版本及像素/性能证据。
