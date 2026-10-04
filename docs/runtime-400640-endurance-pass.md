# v18：16 × 400 × 640，30 分钟采集的确认互动区间通过

2026-10-03，设备 `6L32552009566714`。本次低暂停采集完整结束，确认的 INTERACTIVE 区间达到 **30.552976 FPS**；52 个区间分别都超过 30 FPS，最低 **30.281458 FPS**。独立重建原始 journal、状态、SurfaceFlinger 历史和资源统计，未发现哈希或 gate 不一致。

这里的 PASS 严格沿用原门：只覆盖有完整状态与呈现证据的互动区间。总采集 **1801.281 秒**，确认互动 **1782.481551 秒**，用于帧间隔估计的跨度 **1780.579425 秒**。最后 **4.025454077 秒**的状态仍未知，未插值，也未计入互动通过结论。不能把它描述为每一秒、每个状态都已证明达到 30 FPS。

## 实际执行配置

| 项目 | 本轮实际状态 |
| --- | --- |
| APK SHA-256 | `c4cf831de43a80e85d59fe0382e14ef439b803219140c66b2580786d1cc04d84` |
| 视图 / 输出 | 16 视点，每视点 400 × 640；交织输出 1200 × 1920，projection aspect 0.625 |
| 帧率目标 | 所有已采到的 INTERACTIVE 状态，renderer 实际目标为 31 FPS；分析目标 17 FPS。启动参数未额外指定 active target |
| 输入 | `camera_replay`：真实 USB 相机采集与转换，同时用预录人脸执行推理；不是本轮现场人脸的跟踪准确性测试 |
| 人脸流水线 | RKNN 1.3 检测 + 478 点；**MediaPipe CPU 52 表情 + canonical pose**，仍是旧 v18 后处理 |
| 新表达模型 | 本轮**未部署** CPU 规范化前段 + 新 RKNN 表情后段，不能用本轮作为该新链性能或生命周期验收 |
| 渲染 | 内置标准 GLB `builtin-guide`；异步有界 CPU pose worker，individual draw；4 个持久 FBO 组，每帧相机矩阵 |
| GLB SHA-256 | `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407` |
| 会话 | 一个 Activity、一个 input session、runtime epoch 1、GL context generation 1；无本轮 HOME/resume 测试 |

界面与角色在该负载下运行；本轮没有逐项操作导入、切换或校准等全部 UI 页面。光学参数仍为 pitch 10 SUBPIXELS、tan 0.2777777016、RGB、BOTTOM，`optical_alignment_verified=false`。软件呈现通过不等于裸眼光学效果验收，也不证明 20 视点或两小时耐久通过。

## 原始证据重建

- [原始完整报告](E:/tripo/output/mirror-program/20261003/400x640/avatar-v18-persistent16-400640-lowpause-30min.json)：72,987,632 B，SHA `f0425d8a2e43916820a9b8af4bc8bea4e98ee4c94a7ad585acfa9a8b77271150`。
- [追加 journal](E:/tripo/output/mirror-program/20261003/400x640/avatar-v18-persistent16-400640-lowpause-30min.polls.jsonl)：116,770,655 B，SHA `87db4ede350af8eb5a8ac4e1d55ebfccbbfd6c1f7914ef67adda45800b2366e2`。
- [独立审计 JSON](E:/tripo/output/mirror-program/20261003/400x640/runtime-400640-endurance-pass-audit-v1.json)：SHA `89dc3456022b8f8c2b938dccbd8614e7a4a7b36be2085496741b0af542562b24`。

2,377 条有界 journal 记录包含 1,773 次 poll、592 次 surface discovery 和独立末尾快照。合并八类 delta 后，原始列表全部相等；资源 elapsed→monotonic 派生映射后样本也全等。冻结采集器重新分析得到的 presentation、system、interactive_system 等整个对象与报告相等。

此外独立解析了 shell 原文中的 CPU ticks、内存、温度、GPU/NPU、PSS、所有状态 JSON 与 SF 第二列实际呈现时间戳；重新合并状态事件并对每段计数。FPS 使用 `Σ(帧数−1)/Σ(首尾呈现时间跨度)`，不以 GL callback 频率替代。不跨 GRACE 拼接帧间隔；沿用 34 ms 边界容差和仅 0.000001 FPS 的数值容差。

`collection_complete`、`complete_active_evidence`、`meets_30_fps` 均为 true。采集错误、状态错误、surface 历史缺口、状态环缺口、重叠图层歧义均为零；358 个唯一状态序号 1–358 连续，另有 530 次重复读取。最后快照在独立 force-stop 之前完成，结束记录明确在 stop 后、完整报告写入前。journal 为 flush，不是断电 fsync 持久性保证。

主机相邻 poll 起点间隔平均 1.015660 秒、最大 1.594 秒；没有本次观测标记警告。状态事件累计 WAITING 4.517835 秒、ACQUIRING 0.267797 秒、GRACE 9.195029 秒，这些不纳入互动 FPS。最后状态边界为 device monotonic `185311184548839`，最后观察边界 `185315210002916`，二者差即上述未知尾部。

## 同一互动范围内的资源与分段结果

以下分段起点为首个资源样本，末段实际到 29.998667 分钟；表内 FPS、资源都只取与确认 INTERACTIVE 区间的交集。CPU 差分不跨区间或分桶边界。它比原 summary 的“资源含首桶启动期”范围更窄，两种范围在 audit 中分别保留。

| 分钟 | 互动 FPS | CPU 整机 % | GPU % | NPU % | PSS MiB 均值 / 峰值 | 温度 °C 均值 / 峰值 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 0–5 | 30.643 | 79.67 | 85.37 | 35.56 | 454.95 / 494.98 | 69.44 / 74.44 |
| 5–10 | 30.651 | 78.59 | 85.81 | 35.54 | 494.32 / 495.28 | 76.87 / 78.75 |
| 10–15 | 30.548 | 79.03 | 87.79 | 35.36 | 494.33 / 494.95 | 78.70 / 79.38 |
| 15–20 | 30.525 | 79.62 | 88.99 | 35.38 | 494.52 / 495.25 | 79.20 / 80.00 |
| 20–25 | 30.469 | 79.66 | 90.82 | 35.28 | 494.57 / 496.30 | 79.54 / 80.56 |
| 25–30 | 30.483 | 79.43 | 89.31 | 35.20 | 494.65 / 495.45 | 79.40 / 80.00 |

全程确认互动包含 1,755 个资源样本、1,703 个 CPU 差分、352 个 PSS 样本：整机 CPU 平均 **79.333%**、GPU busy **88.023%**、NPU busy **35.386%**；GPU/NPU 采样峰值分别 100%/39%。PSS 均值 **488.077 MiB**、峰值 **496.299 MiB**；系统 MemAvailable 均值 **915.749 MiB**、最低 **904.133 MiB**。温度均值 **77.215°C**、峰值 **80.555°C**。CPU0 频率均值 1720.79 MHz、最低 1416 MHz，仅是 CPU0 采样，不能据此单独认定温控根因。

这些是采样的整机/设备 busy 指标；CPU 不是应用独占占用，GPU/NPU 不是某阶段执行时间。首桶 PSS 包含仍在增长的已互动进程，不能把后续平台期和首次分配混成泄漏结论。高 GPU busy 也不支持“还有大量渲染余量”的判断。

## 相机、面捕与延迟的范围

同一确认互动区间内相邻状态差分，共 303 对、1525.197191 秒：USB received **24.474868 FPS**、capture results **24.495193 FPS**、分析 completed **16.412960 FPS**、完整 face **16.412304 FPS**。这是状态快照之间的计数估计，覆盖少于全部互动时长，不能当成逐推理帧追踪。

末尾 current-session 原始累计指标仍单独保留，不平均各次累计均值：29,363 次完成分析、29,312 次完整人脸，analysis **16.384371 FPS**、face **16.355913 FPS**；USB received 43,834、capture results 43,868，capture failures **0**、recoveries **0**。相机用 acquireLatestImage + 一个可替换 pending frame，累计替换 14,471 帧是排队策略计数，不是硬件采集失败。

该 session 的 conversion 平均 3.740 ms、录像 reference conversion 2.177 ms、mesh API 墙钟 35.217 ms、CPU post 墙钟 **42.983 ms**；inference completion 平均 **88.017 ms**，received→completed **111.262 ms**。这些累计在线时序含启动/GRACE、重叠执行和等待，不能相加成串行帧时长或解释成纯 NPU/GPU 时间。356 个已采 INTERACTIVE 状态的结果年龄峰值 280 ms；严格 `[start,end)` 资源区间选择包含其中 355 个，平均 153.741 ms、P95 212 ms。新 NPU 表情链的性能需要另轮同配置验证。

## 复核与边界

审计脚本 [audit_runtime_400640_endurance_pass.py](E:/tripo/device-lab/app/build/audit_runtime_400640_endurance_pass.py) 固定输入 SHA、每文件 128 MiB 上限、journal 行数/行长上限，禁止 ADB，输出 exclusive-create。冻结 collector 位于 [collector-low-pause-v1](E:/tripo/output/mirror-program/20261003/400x640/collector-low-pause-v1/freeze.json)，采集器 SHA `0fea6fcf7a4721326a6e879dd0ab4bacddff3fade9dbb37a73303925af8b1aca`；summary SHA `74074533f706a78881e6d01a6f2914ea0496455a1eeccb26820768170ce464a7`。

可用 `runpy.run_path(script, run_name="audit_recheck")` 只在内存重算 `report` 并与 audit JSON 全字典比较；直接执行脚本会拒绝覆盖已有审计输出。本次所有检查只读设备已归档结果，没有 ADB、Gradle、模型编译或生产代码改动。

[旧失败采集](E:/tripo/device-lab/docs/runtime-400640-endurance-failed.md)仍保留失败结论及原因未知边界。此次 PASS 证明这次固定配置的确认互动段，不构成“旧失败一定由 checkpoint 引起”的因果证明，也不抹去旧末段失去前台与证据不足的问题。
