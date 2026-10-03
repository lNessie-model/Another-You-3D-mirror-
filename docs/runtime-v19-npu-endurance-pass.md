# v19 NPU 表情候选：30 分钟联合负载验收

2026-10-03，RK3566 Android 11，设备 `6L32552009566714`。**本轮完整采集与确认互动呈现门均通过：52 个连续互动区间的最低值为 30.353008 FPS，总体 30.568389 FPS。** 这是 16 视点、每视点 400×640、真实 USB 采集与预录人脸推理、GLB/UI 主程序、persistent FBO 和新混合 CPU/NPU 表情后处理同时运行的结果。

确认的互动范围为 **1780.865241 秒**；最后状态之后的 **3.968319 秒仍未知**，没有插值或补算。呈现 30 FPS 不等于面捕 30 FPS：同一互动范围内的快照差分完整人脸更新约 **16.457 FPS**。本报告不覆盖随后进行的两小时测试，也不替代现场脸部效果、光学校准或全部 UI 页面测试。

## 实际配置和证据完整性

本轮 APK SHA `ad8f7e219da380cf46e3dfea55b1c8238024aeab8c7cd8c28664e4a77a35448a`，与此前 v19 CPU→NPU→CPU 短测相同。实际状态确认 `debug_npu_blendshapes=true`、`normalized_rknn_experimental`，保留 CPU OneEuro/canonical pose 和原 FP32 规范化，表情后段模型为 `17b0773a404521a8cf9624fa8147467d9fb3fdb309a641f244244b2910d4831c`。它是混合 CPU/NPU 模型，不是纯 NPU 网络，也不是把 GL 渲染移到 NPU。

- 实际 16×400×640；屏幕输出 1200×1920，active render target 31、analysis target 17。四组 persistent FBO、per-frame camera matrices、有界异步 CPU pose worker、individual draw。
- 内置标准 GLB SHA `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407`；不是诊断椭球。
- `camera_replay` 同时进行真实 USB 640×480 采集/转换与预录人脸推理。355 个 INTERACTIVE 状态快照均为完整 478 点/52 表情；不声称这轮逐帧复验了现场人脸精度。
- 同一 Activity `eea024de-7842-4368-bc65-eb6fb26f664f`、input session `af071b21-d0ec-485f-94e1-e38216f2e59a`，runtime epoch 与 GL context/frame generation 都为 1。无 HOME、GL 重建或恢复操作。

| 完成文件 | SHA-256 |
| --- | --- |
| [原始 JSON](E:/tripo/output/mirror-program/20261003/400x640/avatar-v19-persistent16-400640-npu-30min.json)，77,775,433 B | `4c33fde6f3db2322c5eec6caed962598dc9bee16ee52dee02b66c530f1b6ed42` |
| [逐次 journal](E:/tripo/output/mirror-program/20261003/400x640/avatar-v19-persistent16-400640-npu-30min.polls.jsonl)，124,484,660 B | `3f84a4b76d9d6af33c5756c7c2f9bad53d5ee7c26b2c09a71f60dfec734b0c31` |
| [独立完整审计 JSON](E:/tripo/output/mirror-program/20261003/400x640/runtime-v19-npu-endurance-pass-audit-v1.json) | `a04705b9117129c0974f01699ea7b39826f9cfcdb8699f13ad1a6b36cac49ce3` |

独立合并 2392 条 journal 的八类 delta，与完成报告全部对应数组相等；1784 次 poll、596 次 surface discovery、893 次 status read（358 新状态/535 重复）均重解析原始内容。冻结 `collector-v19-npu-v1` 的分析结果与报告全对象相等。另直接从 SF 原文第二列去重时间戳、state-event 并集和 `/proc/stat` 重建帧率及资源。首尾 metadata、APK/serial、final snapshot 后独立 force-stop 和完成记录闭合；没有采集错误、状态/SF 历史缺口或重叠图层歧义。

本次还核对 v19 冻结源码 ZIP 的 146 个输入与 APK 的 24 个 asset/library entry；所有已采原生完整 metadata 都逐对象等于此前真实 [Android AppCheck](E:/tripo/device-lab/docs/normalized-expression-app-device-validation.md)：SDK 1.3.0、driver 0.7.2、F16 `[1,146,2]`→`[52]`、fmt 3/qnt 2/zp 0/scale 1，提交仍是 F32 1168 B、pass-through 0/want-float 1。候选没有 poisoned/fault/failed-stage。

结束后保存的 preferences 为 973 B、SHA `43bd19b1d31c03648a81fb7e18ecd778c47f4d34b3879e3041f2e0f815056b37`，与此前保存设置相同；独立命令记录再次确认同 APK。原始命令只作为证据读取，本审计没有调用设备。journal 为 flush 持久化，不把它描述为断电 fsync 保证。

## 确认互动范围内的呈现和资源

SF 总体按 `Σ(帧数−1)/Σ(首末呈现时间差)` 计算；52 个连续区间分别完整且都超过 30 FPS。原 34 ms 边界容差及 0.000001 FPS 数值容差未改变。采集实际用时 1801.407 秒；SF 有效估计跨度 1778.863757 秒，不能与确认状态时长互换。

| 指标 | 实测 |
| --- | ---: |
| 互动呈现 FPS / 最低连续区间 FPS | 30.568389 / 30.353008 |
| 确认互动时间 / 未知尾部 | 1780.865241 s / 3.968319 s |
| 整机 CPU 平均 / p95 | 76.039% / 81.473% |
| GPU busy 平均 / NPU busy 平均 | 86.537% / 41.998% |
| PSS 平均 / 峰值 | 484.484 / 494.773 MiB |
| 温度平均 / 峰值 | 77.105 / 79.375 °C |
| CPU0 采样频率范围 / 平均 | 1608–1800 / 1756.547 MHz |
| 系统可用内存平均 / 最低 | 915.115 / 899.438 MiB |
| 已采结果年龄平均 / 峰值 | 150.339 / 252 ms |

资源只取已确认 INTERACTIVE：1763 个系统样本，1711 对同区间 CPU tick 差分，353 个 PSS 样本。CPU 差分不跨 GRACE 或未知范围；设备 busy 不是某个应用阶段的独占时间。最大采集 poll 间隔为 1.672 秒，均值 1.009482 秒；实际 SF 历史仍闭合。

## 五分钟分段

以下从首个系统样本起分段，并与确认互动区间求交。面捕/USB 帧率与 post 墙钟只由同一分段、同一互动区间内的相邻状态计数差分得到；不跨边界，不平均累计均值。各段这类快照对实际覆盖约 236.6–256.7 秒。post 用 `累计均值×累计调用数` 的差重建，属于外层完整 post 墙钟，包含它覆盖的重建等成本，并非逐帧追踪。

| 分钟 | 互动呈现 FPS | CPU % | GPU % | NPU % | 温度均值 °C | PSS 均值 MiB | 完整 face FPS | USB received FPS | post ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0–5 | 30.597218 | 76.585 | 85.564 | 41.740 | 70.789 | 449.581 | 16.319 | 24.396 | 36.033 |
| 5–10 | 30.631928 | 75.406 | 85.946 | 42.173 | 76.510 | 491.157 | 16.513 | 24.440 | 31.891 |
| 10–15 | 30.568832 | 75.504 | 86.265 | 42.034 | 78.284 | 491.062 | 16.484 | 24.335 | 32.078 |
| 15–20 | 30.554383 | 75.893 | 86.595 | 41.986 | 78.849 | 491.685 | 16.492 | 24.431 | 32.119 |
| 20–25 | 30.521698 | 76.551 | 86.876 | 42.141 | 79.033 | 491.582 | 16.473 | 24.498 | 33.144 |
| 25–30 | 30.538468 | 76.309 | 87.966 | 41.908 | 79.035 | 491.242 | 16.449 | 24.466 | 32.650 |

另有 30.000000–30.002833 分钟的微小采样余段，没有确认互动样本，FPS 和资源均为 `null`；未填成 0、30 或通过。未知尾部整体仍是 3.968319 秒，已含在前述排除范围。初期 PSS 上升后，后五段均值约 491 MiB；这组没有显示持续线性增长，但不能证明任意更长运行都无泄漏。

## 面捕、上下文重建与统计窗口

同一确认互动区间内共 302 对状态，覆盖 1520.040168 秒；独立计数差分得到 USB received **24.427644 FPS**、完整 face **16.456802 FPS**。按这些窗口重建的外层 post 平均 **32.953162 ms**。剩余时间没有凭累计均值补成精确互动延迟。

末状态全 input-session 累计：29,408 次 mesh/completed、29,357 次完整 face/post，session face 16.379795 FPS；USB received 43,772、capture results 43,810，capture failures/error/recoveries 均为零。14,364 个 replaceable pending frame 被新帧替换，与既有保留最新帧策略一致，不能称每个摄像头帧都做了推理。

| 末状态的全 session 均值 | ms |
| --- | ---: |
| 实时 USB 转换 / 回放参考转换 | 3.835 / 2.230 |
| Detector API / mesh API | 16.324 / 35.460 |
| 外层完整 post（29,357 次） | **33.263479** |
| Inference completion / received→completed | 77.976 / 101.294 |

内层 `expression_post` 的末对象只有 **115 帧**：geometry 13.042779 ms、FP32 normalization 0.291527 ms、expression API 19.488006 ms、整个对象 post 32.971124 ms。本轮实际观察到 **51 次内层成功计数回落**，外层 session 计数持续增长；源码在丢脸后重建 post 对象，内层 generation/reset 计数随新对象重新开始。快照计数回落是可见证据，不冒充精确逐次 context 生命周期 trace。内层 115 与外层 29,357 的范围不能混用，更不能相减来估计初始化独占耗时。

此前同 APK 90 秒 B1 的 session post 是 **47.184267 ms**、face 15.814076 FPS；该短测保留有效，不能因本轮较长测试下降就删除或修改。新分段说明 47.184 ms 不能代表此后整个长时段，但还没有把差异归因于 ART 暖机、温度、重建或共享 NPU 争用中的某一项。旧 v18 CPU52 的 30 分钟数据来自另一 APK，温态与运行顺序不同，不能据此宣布本模型的同温因果加速。

末 GL target=31 桶覆盖 54,774 次 callback，含仍使用该 target 的 GRACE 时期；不是上述 INTERACTIVE-only 范围。callback work 平均 19.788884 ms：pre-views 0.123195、avatar prepare 1.127981、view submission 11.835252、interlace submission 5.928123、tail 0.774334。每次四组，五段/六项 view 总和与记录闭合；其中 fixed FBO bind+clear 5.035264 ms、scene draw 5.458573 ms。它们均为 CPU 墙钟，包含调度和驱动阻塞，不能当成 GPU 独占耗时或 GPU 工作迁移证据。

## 复算与保留边界

本次 [audit_runtime_v19_npu_endurance.py](E:/tripo/device-lab/app/build/audit_runtime_v19_npu_endurance.py) 只读取固定完成证据，每个文件最多 128 MiB；冻结 collector 的 ADB 入口已替换为拒绝函数。直接执行排他创建新 audit，已有文件不覆盖；纯只读可用 `runpy.run_path(absolute_path, run_name='audit_recheck')['report']` 与已保存 JSON 全字典比较。

没有发现 gate、hash 或 journal 闭合异常。scope 仍限定本次 NPU debug opt-in 联合负载；[旧 v18 CPU30 PASS](E:/tripo/device-lab/docs/runtime-400640-endurance-pass.md)、[v19 ABA/EGL](E:/tripo/device-lab/docs/runtime-v19-expression-aba-validation.md) 保持原哈希和各自结论。旧失败原因未知。本次不宣称已通过两小时、所有 UI 流程、现场脸部准确性或物理光学验收，也不改变默认后处理选择。
