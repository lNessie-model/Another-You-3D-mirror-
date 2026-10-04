# v12 多视图提交细分实测

2026-10-03，90 秒联合负载完成。确认 INTERACTIVE 区间的实际显示为 **29.076620 FPS，未达到 30 FPS**。目标 31 的 GL 回调桶中，视图提交平均 21.4692 ms；其中附件绑定与清屏合计 13.9547 ms，占该段 65.00%，是本轮最值得继续定位的提交位置。它是 CPU 线程墙钟时间，包含驱动隐式等待及系统调度，不能解释成 GPU 清屏耗时。

## 证据与运行身份

原始报告：`E:/tripo/output/mirror-program/20261003/avatar-v12-view-stages-90s.json`。

| 身份 | SHA256 |
| --- | --- |
| 原始 JSON | `b8f24fc0d2a01c8ad61d22a789d7a531f43ff89d3240352abc899b9b109dc408` |
| 运行 APK | `aec31137c9b69014e0d6f582060c0792416462558ce525e54faccbcad8411fc3` |
| 内置 GLB | `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407` |

`collection_status=completed`，无收集错误。输入是 `camera_replay`：真实 USB 采集加录制人脸面捕，当前仍为 RKNN 1.3 检测/478 点加 MediaPipe CPU 52 表情/姿态；新编译的 52 表情 RKNN 候选没有进入本轮负载。

实际 renderer 为 **20 视点，每视点 320×576，输出 1200×1920，投影宽高比 0.625**。内置原型角色 15,085 顶点、29,482 三角形、7 个 primitive，`individual` 绘制、`bounded_cpu_worker` 异步形变；一个动画快照的 VBO 供全部视点共用。多视图每组 4 层，共 5 组；`individual` 指角色 primitive 绘制方式，不代表只渲染一个视点。

调试请求与执行目标为 31 FPS，初始空闲目标为 10。相机控制 revision 0，旋转 0、不反射、不镜像互动，未开启个人/中性头姿基线。光学参数为 pitch 10 subpixels、tan 约 0.2777777、phase 0、RGB、视点不反转、Y 原点 BOTTOM，仍 `optical_alignment_verified=false`。

本版同时接入 controller/camera 功能变化，**不是相对 v10/v11 只增加计时的单变量对照**。本报告用于定位 v12 当前负载，不能将跨版本帧率差异归因于计时、某项控制功能或单个算子。

## 呈现完整性与资源：只取确认互动区间

从原始状态事件重建 3 个 INTERACTIVE 区间，再对 SurfaceFlinger 时间戳去重、按区间筛选，复算结果如下。两次 GRACE 均排除。

| 区间 | 确认时长（秒） | 呈现采样时长（秒） | 呈现帧数 | FPS |
| --- | ---: | ---: | ---: | ---: |
| 第 1 段 | 34.653287 | 34.590124 | 965 | 27.869226 |
| 第 2 段 | 34.837360 | 34.790786 | 1042 | 29.921715 |
| 第 3 段 | 10.318573 | 10.305107 | 313 | 30.276251 |
| 合计 | 79.809220 | 79.686017 | 2320 | 29.076620 |

合计 FPS 按 `sum(帧数−1)/sum(采样时长)` 计算，不平均各段 FPS。确认区间内无 SurfaceFlinger 历史缺口、状态历史缺口、状态错误或多 surface 歧义，`complete_active_evidence=true`。

**实际收集 90.671 秒，不等于上述完整确认时长。** 最后状态 seq 18 确认至 device monotonic `153420267798961 ns`，收集观察至 `153425140005834 ns`，有 **4.872206873 秒未确认尾段**。外层停止前确实执行 `pre_stop` 状态读取，但得到旧 seq 18，结果为 `duplicate`；不能由读操作发生就推断状态已更新。该尾段保留并排除，`complete_active_evidence` 只描述已确认区间，不能声称完整 90 秒全程通过。

| 同一确认互动窗口的资源 | 结果 |
| --- | ---: |
| 全机 CPU 均值 | 87.9927% |
| GPU busy 均值 / 采样峰值 | 88.2895% / 99% |
| NPU busy 均值 / 采样峰值 | 35.2632% / 40% |
| 应用 PSS 采样均值 / 峰值 | 348.040 / 446.076 MiB |
| 可用内存采样均值 / 最低 | 883.109 / 875.102 MiB |
| 温度范围 | 70.555–76.875 °C |
| 资源样本 / PSS 样本 | 76 / 15 |

3 段资源样本数为 33、33、10；CPU 使用 73 对区间内部相邻原始 tick 差分，没有跨 GRACE 拼接。CPU/GPU/NPU 均值与报告独立复算一致。采到 CPU0 频率均为 1.8 GHz，GPU 800 MHz；这些离散样本不能排除未采到的瞬时频率变化，也不能从 busy 百分比断定唯一瓶颈。

## GL 目标桶：五段和六项细分

下表来自最终 `frame_pacing` 的目标 31 桶：**2,330 次成功稳定目标回调**，包含相同目标下的 GRACE，未按 INTERACTIVE 时间过滤。不能与上一节 FPS/资源共享分母。

| 回调五大段 | 每回调均值（ms） | 占 callback work |
| --- | ---: | ---: |
| pre_views：脸部状态更新及旧 fence 等待 | 0.14350 | 0.48% |
| avatar_prepare：提交/获取形变结果及上传 | 1.59896 | 5.40% |
| view_submission：全部 20 视点提交 | 21.46917 | 72.46% |
| interlace_submission：交织输出提交 | 5.60273 | 18.91% |
| submit_tail：新 fence/flush/错误检查 | 0.81410 | 2.75% |
| callback work 合计 | 29.62845 | 100% |

其中 view_submission 的细分如下。循环项是 **5 组累计后除以回调数**，不是每组或每视点的独立耗时。

| 视图提交细分 | 每回调均值（ms） | 占 view_submission | 回调采样最大值（ms） |
| --- | ---: | ---: | ---: |
| setup | 0.06908 | 0.32% | 3.78263 |
| attach_clear | 13.95471 | 65.00% | 87.25268 |
| camera_matrices | 0.94593 | 4.41% | 14.44829 |
| scene_draw | 6.28704 | 29.28% | 76.80576 |
| invalidate | 0.19950 | 0.93% | 8.07683 |
| tail | 0.01291 | 0.06% | 2.73963 |
| 合计 | 21.46917 | 100% | 98.71022 |

`attach_clear` 围住每组 color/depth 的 `MultiviewGl.attach` 和清色/清深度调用；`camera_matrices` 包括 20 个视点的 lookAt/frustum/multiply/copy；`scene_draw` 围住完整 `AvatarGpuScene.draw` 调用。`invalidate` 是每组深度失效提示，`tail` 是循环退出和返回等剩余工作。各项包含其范围内计时/控制开销。各列最大值可能发生在不同回调，不能相加或据此生成 P95/P99。

对全部 18 个原始状态进行了审计：17 个 GL-ready 状态的实际角色/后端/尺寸/目标身份一致，33 个非空桶快照的以下约束均通过：

- 五段 count 等于桶帧数，missing 0；纳秒总和等于 `covered_callback_work` 及 `callback_work`。
- 六项 count 等于 detail 帧数，missing 0；纳秒总和等于 `covered_view_submission` 及外层 `view_submission`。
- 每个快照 `groups=frames×5`。最终目标 31 为 2,330 帧、11,650 组；目标 10 为 18 帧、90 组。
- 最终总回调 2,349 = 2,330 + 18 + 1 个 epoch 过渡回调。过渡单独记账；各桶一个边界回调不进入 gap/interval/deadline debt。
- 所有 total/count 与 mean 一致；fence wait 已包含在 pre_views 中，未被重复相加。初始 GL 未建立时 generation 0，GL-ready 后 generation 1，未把初始化状态当一次已运行 context。

目标 31 桶的 pacer wait 均值 1.50665 ms，fence wait 0.05783 ms，callback gap 3.22825 ms，callback interval 34.38920 ms。gap 包含状态发布、EGL/framework 和调度，不能称为单独的 swap。背景形变 worker 的 morph 均值 10.73895 ms、rig 0.16164 ms、copy 0.69438 ms 使用已应用姿态的另一组累计分母并与 GL 并行，不能加进 callback work。

## 面捕与下一步

最后输入会话累计完成 1,219 次处理、1,217 次有脸结果，保留 478 点和 52 项表情，有效面捕 15.0919 FPS。推理完成耗时均值 97.2827 ms，收帧至完成 124.6917 ms；mesh 均值 37.8027 ms，CPU post 46.5459 ms。USB 收帧 1,970、24.4298 FPS，采集失败 0。此组是输入会话累计值，含获取/空闲阶段，不是按确认互动区间筛出的耗时统计。

优先将 `attach_clear` 再拆为 color attachment、depth attachment、clear，明确 13.95 ms 主要停在哪个调用；无需增加 `glFinish` 或阻塞式 GPU query，否则会改变原负载。当前合并段只定位到 CPU 提交区间，不能证明附件重绑或清屏中的某一项是原因。

可随后做一个受控候选：为 5 个固定 4-view 组预建/验证 FBO，比较每帧只 bind/clear 与当前反复重绑 color/depth 的方案。必须保留原有 20 层、相同颜色/深度清理、深度失效、输出交织及资源销毁/context 恢复，先做逐层像素一致性，再在同 APK 中 A/B 检查呈现、分段和资源。它是待验证方向；驱动可能仍在 bind/clear 中同步，不能预先承诺节省 13.95 ms。

第二优先是核查交织提交的 5.60 ms 中是否有隐式同步；第三是预计算静态视点矩阵，当前该段总计约 0.95 ms，收益上限明显小于附件/清屏段。所有候选都应先保持视点数与输入一致，避免以减少视图或形变功能换取不可比的数字。

前序帧节拍范围见 [v10 帧节拍验证](frame-pacing-validation.md)。52 表情网络的另一路 [ONNX/RKNN 编译实验](blendshape-onnx-experiment.md) 已有离线产物，但尚未计入本轮，也不能把 CPU post 全段当成该网络可被转移的收益。
