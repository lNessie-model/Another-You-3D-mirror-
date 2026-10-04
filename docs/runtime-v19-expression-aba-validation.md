# v19 同 APK：CPU → NPU → CPU 联合负载审计

2026-10-03，设备 `6L32552009566714`。三次完整 90 秒采集顺序为 CPU A1、NPU B1、CPU A2，使用同一 APK。**三轮确认的 INTERACTIVE 呈现区间都通过 30 FPS 门；NPU 轮 CPU 占用较低，但面捕后处理墙钟更长、完整人脸更新略慢。** 不能只据 CPU 降幅宣布整个流水线提速，也没有 GPU 工作迁移到 NPU 的证据。

本报告另附同 APK 的 NPU HOME/EGL 功能恢复结果。该恢复测试有状态历史缺口，性能门仍为失败；它不计入 ABA 的三轮比较。正在进行或后续的耐久测试不在本报告内。

## 固定配置和证据链

- APK：`AvatarRuntime-v19-npu-expression.apk`，SHA `ad8f7e219da380cf46e3dfea55b1c8238024aeab8c7cd8c28664e4a77a35448a`。
- 三轮除名称外，唯一启动参数差异为 `npu_blendshapes=false / true / false`。实际状态分别确认 `mediapipe_cpu / normalized_rknn_experimental / mediapipe_cpu`，不是仅检查请求值。
- 16 × 400 × 640，输出 1200 × 1920，实际 active render target 31、analysis target 17；4 个 persistent FBO 组、per-frame camera matrices、异步有界 pose worker、individual draw、内置标准 GLB。
- GLB SHA `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407`；`camera_replay` 为真实 USB 采集/转换 + 预录人脸推理。包含 478 点、52 表情和 pose，不能当成现场人脸准确性测试。
- B1 后处理保持 CPU OneEuro/canonical pose 和原 FP32 规范化，使用**混合 CPU/NPU 表情后段**。模型 SHA `17b0773a404521a8cf9624fa8147467d9fb3fdb309a641f244244b2910d4831c`，runtime SHA `01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de`，SDK 1.3.0 / driver 0.7.2。实际 query 为 FLOAT16 `[1,146,2]` → `[52]`、fmt 3、qnt_type 2/zp 0/scale 1；提交仍是 292 个 FP32、pass-through 0、want_float 1。

独立读取 APK 并核验 manifest 中全部 asset/library entry 的字节数和 SHA，核验 source-v2 freeze 与实际 `NpuFacePipeline`、`NpuExpressionPostGraph` 来源。采集器为 [collector-v19-npu-v1](E:/tripo/output/mirror-program/20261003/400x640/collector-v19-npu-v1/freeze.json)，SHA `7e0790d54515390e4541ee59e63c4fc2e0ff5242298b3ce9938ea64d91f08c98`。

每轮 132 条 journal、89 次 poll、46 次 status read。合并八类原始 delta，派生资源时钟后与报告所有对应列表相等；冻结 collector 重新计算的整个分析对象也相等。另独立解析 shell 原文的资源数据、状态 JSON、SF 第二列时间戳，并从状态事件重建互动区间和帧率。启动 APK/serial、首尾记录与 final snapshot → 独立 force-stop 顺序均一致。三轮均无采集错误、状态错误、surface/state 历史缺口或重叠图层歧义。

| 完整原始报告 | JSON SHA-256 | journal SHA-256 |
| --- | --- | --- |
| [CPU A1](E:/tripo/output/mirror-program/20261003/400x640/avatar-v19-persistent16-400640-cpu-a1-90s.json) | `b03be09f517ab217276821d6052dbb284dff9a4ad2b6d18ef0004c67a97ab760` | `109bec928f89da518084aaae337139f70cfaf106639d2db85b821f01943aff4e` |
| [NPU B1](E:/tripo/output/mirror-program/20261003/400x640/avatar-v19-persistent16-400640-npu-b1-90s.json) | `3afa0c9cb9d0feebfc1303c04a5f766a1adfb588770ceba03714efe1058dde92` | `fcf951bce5b21692492bc975934ba1aaad284e0faaf8e0d4c47bc0830f36934a` |
| [CPU A2](E:/tripo/output/mirror-program/20261003/400x640/avatar-v19-persistent16-400640-cpu-a2-90s.json) | `b9d11f7cdce9c299c53f1997f28ef5a13fc9fc5a829d61b500b62cc294056718` | `184d365f01fc7f2f10e7b8af5c355816d7802a2dbeb2663e2d884111b1d7b4b8` |

## 确认互动范围内的呈现与资源

资源只取各轮已确认 INTERACTIVE 区间；CPU tick 差分不跨 GRACE 或未知尾部。呈现 FPS 为 `Σ(帧数−1)/Σ(首尾时间跨度)`，原 34 ms 边界容差和 0.000001 FPS 数值容差未改。每轮 3 个连续互动区间均独立超过 30 FPS。

| 指标 | CPU A1 | NPU B1 | CPU A2 |
| --- | ---: | ---: | ---: |
| 互动呈现 FPS | 30.602943 | 30.577484 | 30.637987 |
| 最低连续互动区间 FPS | 30.542819 | 30.514090 | 30.586181 |
| 确认互动秒数 | 84.865922 | 79.668170 | 84.933459 |
| SF 估计跨度秒数 | 84.730412 | 79.535649 | 84.796693 |
| 未确认尾部秒数 | 0.225946 | **4.968873** | 0.391422 |
| 资源样本 / CPU 差分数 | 83 / 80 | 78 / 75 | 82 / 79 |
| 整机 CPU 平均 % | 81.244 | **76.903** | 80.386 |
| GPU busy 平均 % | 84.988 | 85.282 | 84.890 |
| NPU busy 平均 % | 40.928 | 42.026 | 36.122 |
| 温度 °C 均值 / 峰值 | 60.716 / 64.444 | 63.366 / 66.250 | 66.042 / 68.750 |
| PSS MiB 均值 / 峰值 | 368.681 / 470.938 | 350.104 / 451.404 | 366.263 / 466.339 |
| PSS 样本数 | 16 | 15 | 16 |

CPU 均值在 B1 比两侧低约 3.48–4.34 个百分点，但只有一轮 NPU，未随机化、未同温。两次 CPU 轮自身 NPU busy 已相差约 4.81 个百分点；这组不能给出固定的 NPU 增益比例或因果置信度。CPU 是整机数据，GPU/NPU 是采样 busy；PSS 含启动后增长阶段，短测且 B1 确认尾部较短，不能由这些均值推出稳定常驻内存节约。三轮 CPU0 采样均为 1800 MHz，也不等同整 SoC 状态相同。

另做只读长度敏感性检查：以各轮首个 INTERACTIVE 事件为零，截断到共同的 **80.104991632 秒**时间跨度，只缩短 A1/A2，不延长 B1 未知尾部。此时 A1/B1/A2 呈现为 **30.594982 / 30.577484 / 30.633642 FPS**，CPU 为 **81.411 / 76.903 / 80.459%**。方向未变，但这仍不是相同温度、相同逐帧人脸轨迹的对照实验。

## 面捕代价与后处理统计边界

下表前两行来自同一互动区间内相邻状态的计数差分，不跨 GRACE；覆盖秒数分别为 60.372、60.342、65.484。余下各行是末尾 current-input-session 累计指标，含采集启动/GRACE；不伪称为逐互动帧延迟，也不平均多份累计均值。

| 指标 | CPU A1 | NPU B1 | CPU A2 |
| --- | ---: | ---: | ---: |
| 互动快照差分完整 face FPS | 16.183 | **15.992** | 16.325 |
| 互动快照差分 USB received FPS | 24.498 | 24.494 | 24.434 |
| Session 完整 face FPS | 16.046 | **15.814** | 16.185 |
| Session mesh / complete post 调用数 | 1376 / 1374 | 1276 / 1274 | 1390 / 1388 |
| 采集转换均值 ms | 4.075 | 3.468 | 4.085 |
| 录像参考转换均值 ms | 2.299 | 1.994 | 2.229 |
| Detector API 墙钟均值 ms | 14.518 | 14.538 | 14.620 |
| Mesh API 墙钟均值 ms | 38.845 | 36.332 | 35.790 |
| **全会话 post 墙钟均值 ms** | **40.431** | **47.184** | **39.255** |
| Inference completion 均值 ms | 88.873 | **97.007** | 84.734 |
| Received → completed 均值 ms | 112.462 | **120.486** | 107.964 |
| 已采互动状态结果年龄峰值 ms | 200 | 245 | 197 |

三轮 camera capture failures、recoveries、运行时错误均为零。B1 全会话 post 墙钟比两侧约高 16.7–20.2%；它不能因 CPU 采样下降而被忽略。API 墙钟包含并发争用、等待与 RKNN CPU fallback，不能当作纯 NPU 计算耗时。检测/mesh、post 与渲染存在重叠，表内数值不能简单相加推导整帧吞吐。

尤其不能把 B1 最末 `expression_post.total_mean_ms=34.177051` 与 CPU 全会话 40.431/39.255 ms 直接对比并声称 post 加速：

- 该字段只覆盖**当前 post 对象的 171 帧**，geometry 13.260527 ms、FP32 normalization 0.358814 ms、expression API 20.420965 ms；validation 均值约 0.112873 ms。
- B1 状态中该对象计数两次回落：sequence 8→9 的 successful 460→2，sequence 15→16 的 494→5；同期 session post 计数继续 460→537、1029→1108。`generation=1/resets=0` 属于新对象，不能表示全会话没有重建。
- 冻结 `NpuFacePipeline.process` 在丢脸恢复时执行 `post.close(); post=createPost()`；外层 `postNs` 从这之前计时，覆盖整个 input session 的 1274 次 post，包含重建成本。内层 `expression_post` 统计仅从当前对象构造后开始，排除 init/reset 成本。

这能解释两个统计量的范围差异，但不能用不同窗口的 47.184−34.177 直接算出重建的独占耗时。下一次优化应保留面捕吞吐和端到端延迟门，并单独量测重建与共享 NPU 等待；本报告未改变算法或默认选择。

## 渲染墙钟：按 target 31 的全 GL 会话桶

以下是最后状态的 target=31 桶，A1/B1/A2 分别 2605/2449/2613 次 callback；桶含 GRACE 等仍沿用该 target 的时期，**不是前表的 INTERACTIVE-only 资源范围**。独立验证每桶五段总和等于 covered callback work，六项 view 明细总和等于 view submission，每 callback 均为 4 组，缺样为零，fence 是 pre_views 子集。wall time 包含 CPU、调度和隐式驱动阻塞，不是 GPU 独占时间。

| 平均 ms / callback | CPU A1 | NPU B1 | CPU A2 |
| --- | ---: | ---: | ---: |
| Callback work | 20.422 | 19.394 | 20.063 |
| Pre-views（含 fence） | 0.119 | 0.114 | 0.108 |
| Avatar prepare | 1.272 | 1.300 | 1.095 |
| View submission | 11.998 | 11.551 | 12.073 |
| Interlace submission | 6.158 | 5.685 | 5.996 |
| Submit tail | 0.875 | 0.745 | 0.791 |
| Pacer wait（不在上述 work 内） | 9.286 | 10.646 | 9.821 |
| Callback gap | 2.966 | 2.637 | 2.742 |

View 内 fixed-FBO bind+clear 墙钟分别 5.313/4.926/5.144 ms、scene draw 5.573/5.333/5.572 ms；完整六项与独立并发 avatar worker 统计保存在 audit。GPU busy 并未在 B1 降低；这些小幅墙钟差异不构成“把 GPU 渲染移到 NPU”的证据。

## 独立 NPU HOME/EGL 功能恢复

[NPU EGL90 原始报告](E:/tripo/output/mirror-program/20261003/400x640/avatar-v19-persistent16-400640-npu-egl90s.json) SHA `9865ff8c0c36b98a4b92d9e13be0aa7f5a1436a585969c533100e2848bb704c9`；journal SHA `2e05a7a066d4b652685e66241366033da3ab86352f50cea8c085ce3dbf94e05e`。同 APK，25 秒时 HOME，8 秒请求暂停，release-on-pause=true。重新合并 journal 和重新执行冻结功能 gate，与报告全等。

同一 Activity `1447fc7c-f273-40c1-befe-f68853c3dd0f`，GL context 1→2，input session `c08f7c74-d1f0-47e8-bc92-4914e8e7beff` → `33a50a02-93c5-4235-87a5-a9fd2438277d`。NPU flag 与 `normalized_rknn_experimental` 后端保留；观察到 WAITING/new-frame 等待后恢复 INTERACTIVE，10 份新 session 完整 478/52 人脸进度样本，首末 camera received 126→1240、完整 face/post 70→781、mesh 70→782，capture failures/recoveries/error 为零。功能恢复门 **verified=true**。

但状态环存在 `186986474133751`→`186995837740191` 缺口；未知尾部 **2.719714806 秒**。所以 `complete_active_evidence=false`、`meets_30_fps=false`，即使局部呈现估计为 30.626451，也不称这轮性能通过。这里不验证物理光学校准或 GPU 图层像素等价。

## 可复算产物

[独立审计 JSON](E:/tripo/output/mirror-program/20261003/400x640/runtime-v19-expression-aba-audit-v1.json)，SHA `409b4dda22b65d938b33f2ac5249f0b7254cd5937cc5a91cb8e19d3c2e8418c3`，包含 18 个原始/来源文件哈希、原完整分析、独立资源/计数/帧率计算及敏感性截断。脚本 [audit_runtime_v19_expression_aba.py](E:/tripo/device-lab/app/build/audit_runtime_v19_expression_aba.py) 只读固定完成文件，每文件 64 MiB 上限；冻结 collector 的 ADB 入口替换成拒绝函数。`runpy.run_path(script, run_name="audit_recheck")` 在内存生成 `report`，可与 audit 全字典比对；直接执行 exclusive-create，拒绝覆盖。

[旧 v18 30min PASS 文档](E:/tripo/device-lab/docs/runtime-400640-endurance-pass.md) SHA `34b382679e6225e03501fafa24f5ba82cea4abf9c4549deb3c5970179512ffb0` 保持不变。旧 CPU52 耐久通过与本轮 NPU 短测是两项证据；不能互相替代。
