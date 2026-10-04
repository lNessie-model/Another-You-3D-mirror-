# 静态相机 VP 缓存：v15 设备验证

2026-10-03。本轮在实际 Mali-G52 上完成原逐组相机矩阵计算与静态缓存的严格数值、图像对照。**7,200 个 float 值位级一致；69 个姿态的完整 20 视点、1,380 层 RGBA 零差异。HOME/恢复通过；同 APK A/B/A2 为29.911/30.078/29.794 FPS，三轮均未通过全部完整互动区间30 FPS的门。** 缓存明确降低矩阵段成本，但本轮未建立持续30 FPS或等温下净帧工作减少的结论。默认仍为原逐帧计算；本报告没有改变默认设置或应用代码。

## 产物与独立核对

原始目录为 `E:/tripo/output/mirror-program/20261003`。

| 证据 | SHA-256 |
| --- | --- |
| [v15-camera-vp-pixels.json](../../output/mirror-program/20261003/v15-camera-vp-pixels.json) | `4e087f17e170c660514a81f015fff7ff3313de593b90a55aef7a1dea81ee49b3` |
| [avatar-v15-build-manifest.json](../../output/mirror-program/20261003/avatar-v15-build-manifest.json) | `e816684b41e04462322f876484a8ca72e0fc5e4515224a00d85611d2796a715e` |
| `AvatarRuntime-v15-camera-vp-cache.apk`，34,683,560 bytes | `3b3b197094943d531128f7d40bf702386cd034d1260a359949a03ce19a96714a` |
| APK 内 `avatars/builtin-guide/character.glb`，5,501,220 bytes | `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407` |
| APK 内 `avatars/builtin-guide/avatar.json` | `ddb819fc4f23477f6d7c18efbba82a9356d0eee46145bfb240de3491e51f710b` |

独立读取完整原始报告，逐层检查计数、视点编号、误差、哈希与非空条件，而非只读取顶层 `passed`。再次计算 APK 哈希，98 项源文件与清单全部匹配，清单记录 `source_files_stable_through_build=true`。解开 APK 读取的 11 项受清单追踪资产均与源哈希一致，包括正式角色、映射、原始表情 TFLite、检测/关键点 RKNN 及 MediaPipe task；未混入尚未通过精度门的离线 NPU 候选。

最终报告 `run_id=f878e427-bf96-48cb-9f6b-7ab0acbae440`，`context_generation=1`；`running=false`、`completed=true`、`passed=true`、`cancelled=false`，没有 `error` 或 `cleanup_error`。设备操作者确认作业 terminal exit 0。本次文档审计仅使用本地证据，没有执行 ADB、构建或改写应用。

## 比较的真实路径

入口为 debug `AvatarPreviewActivity --ez verify_cached_camera_vp true`。比较后端是 `per_group_camera_vp_vs_cached_camera_vp`：

- reference 每个四视图组重新执行原 `Matrix.setLookAtM`、`frustumM`、`multiplyMM` 表达式。
- candidate 使用生产 `AvatarCameraProjectionCache` 预计算并复制对应组。
- 两侧均采用 persistent OVR4；各自独立 color/depth array 和 5 个 FBO，base 为 0/4/8/12/16。双方 clear color/depth、draw、invalidate depth 的操作保持一致。
- 每个姿态只 `prepare` 一次，同一 Scene、VBO、世界矩阵与 shader 先后供双方使用。每个 global layer 由单层 read FBO 读 RGBA8；没有从 multiview attachment 直接读像素。

`serial_*` 是沿用的 reference 字段名，本轮 reference 实际也使用 OVR4，并非串行 single-view。此检查只比较相机矩阵计算/缓存；legacy attachment 与 persistent FBO 的差异另见 [v14 验证](persistent-fbo-device-validation.md)。诊断为了防止双方互相覆盖使用额外纹理数组，不代表生产缓存增加了此项显存。

设备为 ARM / Mali-G52，`OpenGL ES 3.2 v1.g2p0-01eac0.6cdb9e4846b564c40e2e87e3f0467d61`，`max_ovr_views=4`。正式 GLB 含 15,085 顶点、29,482 三角形、7 primitive，解码资产 5,890,656 bytes；`normal_policy=recompute-deformed`、`complete_source_mapping=true`。诊断是同步 CPU 变形、individual draw，故不能用其计时推断主程序异步 worker 的联合性能。

## 真实 Android Matrix 位级门

这是运行设备上 `android.opengl.Matrix` 的结果，不是 SDK stub 或主机 Matrix fixture 的推断。

| 项目 | 完整报告核对 |
| --- | --- |
| 视点集合 | 1、4、16、20、32 |
| 每种视点数的 physical 尺寸 | 1200×1920、1920×1200、1200×1600、2400×3840、1×1 |
| 矩阵 case | 25 个，笛卡尔组合全部存在且无重复 |
| raw float bit 比较 | 7,200 个，差异 0 |
| invalidate 后拒绝读取 | 25 次；随后重新 prepare 并核对首组 |
| 每个 case 的 bit mismatches | 全部 0 |

覆盖横竖比改变、同一比例但不同 physical 尺寸和全部视图顺序。这里的 invalidate/rebuild 是缓存数学对象测试；不能把它改写为发生了 25 次实际 Android surface resize 或 EGL context loss。当前像素检查的 physical 尺寸仍固定如下。

## 完整角色逐层像素门

预设允许值 `max_rgb_error_allowed=0`、`rmse_allowed=0`、`alpha_error_allowed=0`，没有在看到结果后放宽。

| 项目 | 独立复算结果 |
| --- | --- |
| 视点 / 每层大小 / physical 输出 | 20 / 400×720 / 1200×1920，aspect 0.625 |
| 姿态、prepare、层对 | 69 / 69 / 1,380 |
| 单源 | 52 个，source index 0–51 完整且每项为对应 one-hot |
| 每姿态 global view | 0–19 完整，OVR base / relative 与全局编号一致 |
| 最大 RGB 差 / RGB 不同字节 / RMSE | 全部 0 |
| alpha 不同字节 / 非不透明像素 | 全部 0 |
| 每层两侧 SHA-256 | 全部相同 |
| 非背景像素数 | 每层 33,796–40,267；双方一致 |
| 每姿态不同视点 SHA | 每个姿态都有 20 个不同哈希 |
| 首末视点 RGB 不同字节数 | 67,457–91,725；双方一致且全部大于 0 |
| 相对 neutral 中心画面变化 | 67 个；仅 `neutral`、`source-_neutral` 不变 |

除 52 单源外，还包括初始中性、pitch/yaw/roll 双向极值、四向 gaze、jaw-open-left/right-forward、jaw-mouth-close-corrective、smile-blink-tilt、head-envelope-combination 和 all-controls。此处的 fixture 名称不构成真人左右方向或光学验收。

Scene `morph_updates=70` 包含构造时的初始中性更新，与 `prepare_calls=69` 不矛盾。报告诊断区间 370.391 秒；Activity 记录从 started 到 updated 为 375.266 秒。两者含有初始化、同步读回、哈希和 JSON 等诊断工作，均不能换算成主程序呈现 FPS。

## 已确认范围与未确认边界

本证据确认这一 APK、资产、Android Matrix 实现和 GPU 驱动下，指定矩阵集合及 69 个角色姿态的所有 20 层像素保持一致。零差异来自非空、不同视点和变化的表情，排除了两侧都画空白或重复同一相机的简单假通过。

本轮未覆盖摄像头/NPU/异步 CPU worker 与主程序一起运行，没有 SurfaceFlinger 呈现统计；不证明美术、真人动作方向、裸眼屏光学对齐、完整交织 framebuffer、400×720 联合性能或长期稳定性。报告自身保留 `performance_evidence=false`、`artwork_validated=false`。任意第三方资产、不同驱动及未枚举连续 pose 也不由有限 fixture 穷尽证明。

主机固定容量/错误 key/复制所有权等测试与 SDK 检查见 [camera-vp-cache.md](../tests/camera-vp-cache.md)。该缓存只有 2,240 bytes 的固定 float payload，无 GL ID；这种设计并不自动证明整个 Activity/renderer 的恢复路径已经设备验证。

## HOME / 恢复功能证据

[avatar-v15-cached-vp-home-60s.json](../../output/mirror-program/20261003/avatar-v15-cached-vp-home-60s.json)，SHA-256 `35b2c0247c1a9eb016d3a8b357ce7bc5ac802e5e077e3a513ceb54acf93a383e`，同一 v15 APK。配置 camera_replay、20×320×576、persistent/cache 同时开启、target31，约20秒 HOME、8秒后恢复。collector completed、collection_errors=[]；设备操作者确认 terminal exit 0。

独立检查实际 `host_actions.start` 与 `resume` 命令均带 `--ez test_persistent_fbos true --ez test_cached_camera_vp true --es test_view_preset 320x576 --ei test_active_target_fps 31`。初启前与测试结束有 force-stop，HOME 到恢复之间没有 force-stop；HOME 前还在同一 shell 先获取最后一份 SF 历史。恢复命令为同 MirrorActivity 的 `-f 0x20020000`。所有落盘状态请求一致，成功 GL 帧的实际模式均为 cached / persistent_groups / 5，错误与恢复计数为0。

| 观察 | HOME 前最后状态 | 恢复后首次有处理结果 | 恢复后末状态 |
| --- | ---: | ---: | ---: |
| input session | `7131d457-fefd-408a-9275-cb995e7461c9` | `f89215ac-3684-4130-a8eb-626dd72cf912` | 同恢复 session |
| epoch / sequence | 1 / 5 | 2 / 2 | 2 / 7 |
| state | INTERACTIVE | WAITING | INTERACTIVE |
| renderer frames | 453 | 479 | 1,224 |
| 当前 session completed / face frames | 229 / 229 | 1 / 1 | 395 / 395 |
| 当前 session USB received / failures | 372 / 0 | 2 / 0 | 611 / 0 |
| landmarks / blendshapes | 478 / 52 | 478 / 52 | 478 / 52 |

新 session 的首个输入结果不意味着已经进入 INTERACTIVE，之后状态明确完成获取并继续渲染。没有把两个 session 的在线计数相减或累加成连续推理率；这也不是截图旧 sequence 的误判。

两段确认互动 SF 历史分别为14.423924秒/430帧、24.774694秒/741帧，区间FPS为29.832181、29.936091；各段 `complete_history=true`、surface history gaps为空。报告总体29.897874为当前确认范围的下界，`complete_active_evidence=false`、`meets_30_fps=false`：HOME附近有9.423431696秒状态历史缺口，末尾4.40922403秒未经最新状态确认。保留这些缺口，不能把此60秒功能序列作为稳态性能达标、主context实际销毁、暂停时相机/线程数量或长期无泄漏证据。

## 同 APK A/B/A2 联合性能

三轮作业均已 terminal exit 0，collector completed且collection_errors=[]。原始证据：

| 运行顺序 | SHA-256 |
| --- | --- |
| [A：avatar-v15-per-frame-vp-a-90s.json](../../output/mirror-program/20261003/avatar-v15-per-frame-vp-a-90s.json) | `0cde84fa988eea9e315ceaabca6bb98d4566df93ab8fe9a13881d9f7da2ebefa` |
| [B：avatar-v15-cached-vp-b-90s.json](../../output/mirror-program/20261003/avatar-v15-cached-vp-b-90s.json) | `f05ac1bf6f7501babd0f8bd0589093320156a928f7f5a7fe85fc6c6aa5f4e78b` |
| [A2：avatar-v15-per-frame-vp-a2-90s.json](../../output/mirror-program/20261003/avatar-v15-per-frame-vp-a2-90s.json) | `3800abb05627e728c0451b96e2167dcd3f851bc9a503cf448b2b176422b5df03` |

三轮同一APK、正式GLB及映射、20独立视点、320×576单视点、1200×1920输出/0.625 aspect，完整RKNN检测+478点/CPU52表情与姿态、17 FPS面捕请求、bounded_cpu_worker、individual draw、persistent5组。光学参数均为pitch10 SUBPIXELS、tan0.2777777、phase0/RGB/forward/BOTTOM。原始请求和实际后端均核对，只有B的矩阵模式为cached，其余per_frame。共同命令参数为：

```text
--input camera_replay --seconds 90 --view-preset 320x576 --persistent-fbos --active-target-fps 31
```

只在B增加`--cached-camera-vp`。没有HOME、blackout、batch或同步变形混入。每次开始及结束均force-stop，不改偏好。所有状态均无runtime/render/control/processing错误、control拒绝或自动恢复；模型身份、实际后端、尺寸和校准逐状态核对。400×720仍只有上面的像素证据，不是联合性能结果。

独立审计从每份`surface_windows.raw`的实际呈现列重新提取并去重，与保存的timestamp数组核对；从原`state_events`推导三个INTERACTIVE区间。总FPS按各区间`(帧数-1)`之和除以各段首末实际帧间隔之和，未对区间FPS简单取平均。资源重新选取同一区间内的原samples，CPU只计算同段相邻ticks（排除idle/GRACE连接）；所有原statuses的target桶计数、total/count均值、五段与六子段守恒均核对通过。没有只抄collector摘要。

| 呈现范围 | A per-frame | B cached | A2 per-frame |
| --- | ---: | ---: | ---: |
| 已确认互动实际呈现FPS | 29.911178 | 30.077834 | 29.794374 |
| 三个完整区间FPS | 29.272109 /30.395519 /30.424384 | 29.474104 /30.525523 /30.424272 | 29.314509 /30.195288 /29.968754 |
| 确认互动秒数 | 79.836855 | 84.799680 | 84.753188 |
| 实际首末帧采样秒数之和 | 79.736077 | 84.746795 | 84.646852 |
| 实际帧间隔数之和 | 2,385 | 2,549 | 2,522 |
| 未确认尾段秒数 | 4.967775 | 0.804071 | 0.814469 |
| 确认活动历史完整 / 30FPS门 | true / false | true / false | true / false |

三轮state/SF history gaps和模糊surface区间都为空；`rate_is_lower_bound=false`只针对已确认范围，不覆盖表中尾段。B平均超过30，但第一个34.627秒完整区间仅29.474，故不能称持续达标。A较长的未确认尾段也使三轮确认时长不同，不能隐藏这一差异。

| 已确认INTERACTIVE资源 | A | B | A2 |
| --- | ---: | ---: | ---: |
| 资源样本 / 同段CPU差分对 | 75 /72 | 78 /75 | 80 /77 |
| 整机四核合计CPU均值 | 86.385% | 86.426% | 86.647% |
| GPU busy / NPU busy均值 | 91.307% /37.187% | 91.962% /37.449% | 90.175% /36.463% |
| 温度范围 °C | 66.250–73.333 | 70.555–76.875 | 72.222–78.750 |
| 温度样本均值 °C | 70.310 | 74.028 | 76.954 |
| 全采集首个温度 °C | 62.777 | 67.500 | 71.666 |
| PSS均值 /峰值 MiB | 359.03 /458.41 | 367.69 /467.71 | 346.38 /463.29 |
| PSS样本数 | 15 | 16 | 16 |
| MemAvailable最低 MiB | 882.01 | 874.37 | 887.13 |

A的75个、B的78个互动样本均采到CPU0 1800MHz/GPU800MHz；A2的CPU0为1800MHz×72、1608MHz×8，GPU为800MHz×65、700MHz×14、200MHz×1。频率是采样瞬间，并非完整驻留分布；A/B频率样本相同也不证明等温。温度随顺序上升、A2出现降频，故B的小幅FPS优势不能直接推成稳定或纯缓存因果增益；PSS亦受回放驻留和窗口影响，不能据这三组认定内存改善或泄漏。

| target31成功callback墙钟均值，ms | A | B | A2 |
| --- | ---: | ---: | ---: |
| callback / OVR groups计数 | 2,398 /11,990 | 2,563 /12,815 | 2,539 /12,695 |
| callback work | 24.667562 | 24.649698 | 25.106520 |
| pre views（含fence） | 0.197419 | 0.178909 | 0.206698 |
| avatar prepare | 1.656279 | 1.762593 | 1.747438 |
| view submission | 16.222321 | 15.969089 | 16.546411 |
| └ setup | 0.088383 | 0.115655 | 0.113750 |
| └ attach + clear | 7.147778 | 7.228587 | 7.341808 |
| └ camera matrices / cached copy | 1.004279 | 0.171774 | 0.993314 |
| └ scene draw | 7.714386 | 8.186295 | 7.856111 |
| └ invalidate | 0.249893 | 0.254738 | 0.230955 |
| └ tail | 0.017602 | 0.012041 | 0.010472 |
| interlace submission | 5.766475 | 5.890329 | 5.788726 |
| submit tail | 0.825069 | 0.848778 | 0.817247 |
| pacer wait（work之外） | 3.662539 | 4.275868 | 2.819343 |
| callback gap（work之外） | 5.072383 | 4.293152 | 5.617630 |
| fence wait（pre views子集） | 0.097545 | 0.053986 | 0.099248 |

矩阵段A→B减少0.832505ms（约82.9%），A2恢复到0.993314ms，说明被移除的重复Matrix工作确实落在所测分段。但A→B总callback work只减少0.017864ms；scene draw增加0.471909ms，prepare/interlace等也变化。不能把局部0.83ms当作整帧净收益，也不能将driver等待迁移或调度差异当GPU独占时间。

FPS 使用已确认 INTERACTIVE 的实际 SF 历史，保留分段、缺口和未确认尾段，不用 callback FPS 替代呈现。资源按同一 INTERACTIVE 交集统计；记录起始温度、温度范围、CPU0/GPU 频率分布与运行顺序。A/B/A2 有助观察顺序影响，但温度分布不一致时仍不是等温因果实验。

`camera_matrices` 是每个成功 callback 内五组的总 CPU 墙钟；缓存路径对应 group copy，不能解释成 GPU 时间。其 scope 是自统计 reset 后的 target 桶，包含该 target 的非 INTERACTIVE 工作；与 SF/资源窗口分开报告。核对五段及视图六子段守恒、样本数和 epoch/missing，不用不同窗口相除计算 GPU 开销比例。

面捕 `face_fps`、`face_fraction`、`inference_completion_mean_ms`、`received_to_completed_mean_ms` 属当前 input session；`result_age_ms` 是应用收到输入后、含推理的结果年龄，只有稀疏状态观察。Scene `applied_pose_age` 从 worker.submit 到上传完成，是 GL scene 的在线统计，不是相机曝光到实际呈现端到端延迟，也不能与上述年龄直接相加。还需核对有效脸/478点/52系数、control rejection、pose input 递增和 worker 有界计数。

| 完整面捕及角色更新，按上述各自scope | A | B | A2 |
| --- | ---: | ---: | ---: |
| 末input session face FPS | 15.689178 | 15.684988 | 15.629315 |
| completed / face frames | 1,267 /1,265 | 1,345 /1,343 | 1,341 /1,339 |
| face fraction | 99.8421% | 99.8513% | 99.8509% |
| inference completion均值 ms | 95.572 | 95.924 | 96.296 |
| received→completed均值 ms | 119.433 | 119.986 | 120.237 |
| USB capture FPS / received images | 24.408 /1,966 | 24.374 /2,083 | 24.360 /2,085 |
| INTERACTIVE状态年龄样本数 | 16 | 17 | 17 |
| 状态result_age均值 /范围 ms | 153.44 /129–188 | 157.06 /113–227 | 161.76 /114–245 |
| Scene morph均值 ms | 10.432 | 10.641 | 10.498 |
| Scene pose copy /upload均值 ms | 0.586 /0.489 | 0.600 /0.486 | 0.745 /0.555 |
| Scene applied pose age均值 /最大 ms | 33.092 /135.407 | 32.783 /122.017 | 32.832 /121.616 |
| worker丢弃待处理input /替换ready | 0 /150 | 1 /184 | 0 /197 |

每轮只有一个input session；所有有推理的状态保持478点/52系数，USB capture failures均0，NPU detector/mesh公开runtime与driver、crop实现、单一CPU在途任务保持相同。末applied input分别为2416/2581/2558，持续消费有更新；worker固定3槽、pending/ready最大均1。表情帧率和覆盖没有被关闭或明显降载。上述采样年龄不是延迟分布的p95，也不证明端到端显示延迟；Scene形变等后台均值不可加到GL callback work。

## 后续实验的优先顺序

先完成本缓存的单变量联合实测，再决定是否保留，不同时叠加其它渲染改动。此前的负面证据应继续保留：

| 路径 | 现有证据与决策 |
| --- | --- |
| 合并 7 primitive draw | [v8/v9](../tests/avatar-batch.md)：v8 individual/batched 为 29.316/27.856 FPS；v9 反序为 27.007 batched /29.339 individual。温度有混杂，但没有支持默认开启的实测收益；不直接重试同方案。 |
| RGB shared phase | [旧诊断原始数据](../../output/render-quality-optimization/20261002/quality-phase-landscape-shared-60s.json)零像素差，32.983 FPS；同轮 depth 基线 33.279。旧诊断场景不是正式角色验收，亦没有现成收益理由。 |
| 整数 lookup 交织纹理 | [历史记录](../../output/render-quality-optimization/20261002/README.md)已经降低帧率且增加约 8.8 MiB；不能仅因“减少公式”再默认启用。 |
| 直接 atlas / atlas-copy | [直接 atlas](../../output/multiview-optimization/20261001/v16-atlas-gpu.json)有 697 个 RGB 字节差；[精确拷贝](../../output/multiview-optimization/20261001/v16-atlas-copy-gpu.json)零差但仅 16.678 FPS。不能用错误画面或重复拷贝换取表面数字。 |
| 提高 target 到 35 | [v10](frame-pacing-validation.md)与[v14](persistent-fbo-device-validation.md)均未建立稳定改善；v14 热态31复测变差且降频，不能得出等温35无效的结论，也不应反复只调目标值。 |

如果 v15 实测仍显示 CPU 提交值得优化，下一最小候选是 **同一已显示 pose 的节点变换每帧准备一次**。当前 `AvatarGpuScene.draw` 每个 OVR 组重复 `framing.copyFitMatrix`、逐可绘制 node 的 `Matrix.multiplyMM`、`invertM` 和 inverse-transpose 提取；本模型 7 个可绘制节点、5 组，即每帧 35 次世界矩阵乘法与逆矩阵。可在保持所有 GL uniforms/draw 次序、shader 和 float 运算不变的情况下，降低到每个 applied world snapshot 的 7 次，再复用结果。此项还未实现或验证，`scene_draw` 约数毫秒含 driver 隐式等待，绝不能全当可消除数学成本。

该候选必须按 **实际 applied world snapshot** 和 physical aspect 失效；仅看 morph revision 会遗漏纯头转/眼球/下颌变化。固定有界数组、不按 view 分配，资产/scene替换及 context 初始化不得复用错误状态。先独立量化原节点数学成本，再用相同 prepared VBO 的原节点数学/缓存节点数学做严格逐层门，最后同 APK 性能对照；尚无数据前不承诺收益。

若 20 视点热态仍不足，用户要求的 16 视点可作为明确的新性能档位。**当前 v15 主程序和 collector 没有该入口**：`MirrorActivity` 创建 renderer 和 status 都固定 20，`view_preset` 只改分辨率；旧 `run_case.py --views 16` 使用诊断网格，不能替代正式 GLB。最小接线应为 debug 16/20（默认20）并在 HOME 恢复、requested/actual 状态和测试图一致传播。底层 renderer/cache/4-view FBO 支持16，但正式16层像素、交织及光学映射尚待验收。16个视点须均匀覆盖原±0.2端点，而非删除20层中的4层；物理 pitch/tan/phase 不按20/16缩放。它减少20%的视图工作量，属于档位选择，不能称相同20视点负载优化或20视点达标。

## 恢复与长期验证建议

候选 HOME 功能的独立60秒结果见上节。主 Surface 请求保留 EGL context，故成功恢复不保证发生 context loss。

已有无需改全局设置的诊断补充：`AvatarPreviewActivity` 没有设置 preserve，Android [GLSurfaceView 官方合同](https://developer.android.com/reference/android/opengl/GLSurfaceView#setPreserveEGLContextOnPause(boolean))规定默认 false，暂停释放、恢复重建 context。可在本次完整报告归档后 HOME，再恢复同一 Activity 对象，记录相同 PID/Activity、新的 run_id 和 `context_generation` 从1递增到2及新的完整通过报告。若 Activity 被重建且 generation仍1，只能证明冷建，不能冒称同一对象恢复。此操作尚未执行。

上述诊断每次重新创建 cache/FBO helper，只补充 Preview 及 helper 在新 context 的初始化/像素证据，不能替代主 `InterlaceRenderer.onSurfaceCreated` 自身旧引用失效测试，因此本轮不重复执行这个约6分钟诊断。主路径目前无显式调试的 context-release extra，确定性覆盖需后续最小 debug 控制，不能靠全局“不保留活动”、强制旋转或清数据冒充。

选择一个短测无回归配置后，先5分钟确认热稳态，再30分钟及2小时；保留逐分钟呈现/face覆盖与年龄、温度和频率、PSS/Java/native/mmap分类、MemAvailable、相机/worker数量和错误/恢复事件。回放映射页驻留增长应与匿名堆增长区分；不能只凭90秒PSS曲线证明泄漏或无泄漏。HOME/USB重连/校准预览开关可作独立功能段，不能混进稳态FPS均值。30FPS判定仍需完整已确认历史，达到局部区间或热稳态前30并不代替长期目标。
