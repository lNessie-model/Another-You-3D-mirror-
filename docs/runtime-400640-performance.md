# v17：16视点400×640联合负载性能

2026-10-03。**四轮90秒采集的已确认互动区间均通过原30 FPS呈现门；persistent FBO的回调工作时间较两次legacy对照下降。** 这是有界短轮观察，不是持续热态30 FPS或同温度随机实验，尚不能据此自动更改生产后端默认值。

## 固定条件与证据复算

四轮均由设备负责人执行并以采集CLI exit0结束、关闭任务；`collection_status=completed`、`collection_errors=[]`。序列为legacy A、persistent、persistent+cached、legacy A2。每轮仅初始force-stop/清旧status/启动/最终force-stop，没有HOME、黑屏注入或中途重建。CLI exit0表示采集完成，性能判定另读下表的完整呈现门。

全部绑定设备 `6L32552009566714`、同一 v17 APK SHA `43ec48bc82a996c4e576021c6a7c1043e7468f88765ddcd772123ed0b2cdfbe9`。主界面真实运行GLB，非离屏像素fixture/程序化替代场景：16视点、每视点实际400×640，输出1200×1920、物理投影aspect0.625。完整内置角色SHA `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407`，15,085顶点、29,482三角形、7 primitives、52输入完整映射；`bounded_cpu_worker`、individual draw、全部独立视点共享同一变形VBO。未开启同步pose或batch开关。

输入为 `camera_replay`：真实USB Camera2采集与转换继续工作，**推理图像来自原录制人脸**，不是四轮新拍的现场动作。RKNN1.3 NPU detector+478 landmarks，原MediaPipe CPU52 blendshapes与canonical pose；没有改用未达原TF门的all8候选。分析目标仍17 FPS，render target显式31；所有互动状态完整478/52、有脸、新鲜度101…235ms，主界面calibration panel关闭。不能将约30.6呈现FPS称为30.6次新推理。

冻结采集器 `collector-v17/manifest.json` SHA `bf87c7c4d5281e95765e91a8b38aac8435f0db89f08882745f1153a4af534e18`，source_commit `4b84e77`；`run_runtime_check.py` SHA `16c67763b82080bab67ed879bb63a53e07e0a0e7fae332a2dc44523090480352`，`device_profile.py` SHA `56fb783df3cee3bf9a6a183807667a3abb6a5691ea10854fdba521ace96eba32`。只导入冻结纯计算函数重算四轮，`presentation`、`interactive_system`及`system`整个对象均与保存报告相同。另从181次status读取中的原JSON核对全部74份解析状态，剔除唯一host附加时间后逐对象相等；66份互动样本的实际尺寸/后端/模型/完整脸保持。

呈现采用SurfaceFlinger latency**第二列实际呈现时间**，只计状态事件确认的INTERACTIVE区间，去重/排除获取、idle和GRACE；所有各自三段互动区间均≥30，覆盖完整、无state/surface-history gap、无ambiguous surface及状态错误。34ms边界余量和原门不变。每轮最后status之后的未确认尾段单列，不计作达标时间。

## 呈现与回调工作

| 条件 | 已确认互动/采样跨度 秒 | 实际呈现 FPS | 各段最低 FPS | 未确认尾段 秒 | target31回调工作均值 ms / 样本数 |
| --- | ---: | ---: | ---: | ---: | ---: |
| legacy A：legacy/per_frame | 79.755805 / 79.652569 | 30.369391 | 30.066250 | 4.855005 | 24.953637 / 2,436 |
| persistent/per_frame | 84.873947 / 84.763076 | 30.650138 | 30.616609 | 0.689215 | 20.090853 / 2,611 |
| persistent/cached | 79.873052 / 79.702839 | 30.576075 | 30.471390 | 4.873776 | 20.440291 / 2,452 |
| legacy A2：legacy/per_frame | 84.781507 / 84.663331 | 30.520888 | 30.398008 | 0.330333 | 24.787456 / 2,599 |

四份 `collection_complete`、`complete_active_evidence`、`meets_30_fps`均为true。persistent两条真实后端都是 `persistent_groups`，固定4个OVR4 FBO组；A/A2为legacy、FBO count0；VP的requested/actual分别按表为per_frame或cached，无静默fallback。每份当前context/frame generation均为1且ready，此处没有测试contextloss恢复。

回调工作来自最后状态的target31聚合bucket：成功、稳定target回调，自GL context创建以来统计；**按target分桶，不等于INTERACTIVE资源筛选区间**。五连续阶段total逐项求和等于callback_work，view细分六段total也等于view_submission；没有缺失stage frame、每回调4组。它包括CPU调用、调度及隐式驱动等待，不是GPU独占执行时间，也不包含另一线程并行pose工作或全部EGL swap成本。

| target31每回调平均 ms | legacy A | persistent | persistent+cached | legacy A2 |
| --- | ---: | ---: | ---: | ---: |
| view_submission整体 | 17.078079 | 12.015796 | 12.223383 | 17.199625 |
| attach_clear | 10.897033 | 5.216399 | 5.114843 | 10.726109 |
| camera_matrices | 0.817618 | 0.788469 | 0.137078 | 0.803609 |
| scene_draw | 5.110969 | 5.741747 | 6.712132 | 5.422471 |
| interlace_submission | 5.792845 | 6.017942 | 5.982795 | 5.566340 |

persistent回调工作较A少4.863ms、约19.5%，较A2少4.697ms、约18.9%；组合较两个A少约17.5…18.1%。attach_clear差异与固定FBO切换相符，A2回到约25ms也支持收益不只是一次legacy异常。cached矩阵段明显下降，但其整体回调工作20.440ms、呈现30.576FPS均未优于persistent这一轮，scene_draw还较高；因此只记录局部时间变化，不声称cache带来额外端到端收益或精确因果分解。31帧上限使这些FPS不能用于推断无上限渲染能力。

## 互动资源与完整负载

以下资源仍只取确认INTERACTIVE中的样本，CPU delta不跨排除区间；CPU为整机使用率，不是应用进程独占比例。PSS是间歇抽样峰值，温度是采到的范围，不表示实验统一起始芯片温度。

| 条件 | 资源样本 / PSS样本 | 整机 CPU % | GPU busy均值 % | NPU busy均值 % | PSS峰值 MiB | 温度范围 °C |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| legacy A | 75 / 15 | 83.881441 | 83.866667 | 38.480000 | 461.478516 | 60.555…68.125 |
| persistent | 83 / 16 | 80.725381 | 85.060241 | 35.421687 | 462.537109 | 64.444…70.000 |
| persistent+cached | 77 / 15 | 81.292860 | 85.077922 | 35.415584 | 464.292969 | 62.222…69.375 |
| legacy A2 | 82 / 16 | 83.584305 | 86.353659 | 35.207317 | 457.631836 | 60.555…68.125 |

同会话首末互动样本分别记录USB received images：A126→1977、persistent126→2105、cached125→1977、A2 124→2077；mesh/post/完成推理/有脸完成/GL frames均继续增长。全部捕获状态无ERROR、GL/source/processing/control错误，capture_failures0、stored_face_images0。末状态会话平均analysis FPS依次15.9838/16.2256/16.1832/16.0743，有脸完成占比约99.84…99.86%；这些包含初始获取/idle的会话均值，不冒充独立互动推理吞吐测试。

## 冻结报告与限制

目录为 `E:/tripo/output/mirror-program/20261003/400x640/`，四份原始文件未被改写：

| 报告 | SHA-256 |
| --- | --- |
| `avatar-v17-legacy16-400640-90s.json` | `5fe7f74855f1a82a2d35f86a5b334865abb60b8d36f3cc36eaa94eac5f8a9115` |
| `avatar-v17-persistent16-400640-90s.json` | `4a9e5d50a6da10d22c413ae3771a2a487aa367fabea089c45a5fd63a902e222d` |
| `avatar-v17-cached16-400640-90s.json` | `3719a0beaac837e0912af7beff469f1e13149201243077a18680f20df97b353b` |
| `avatar-v17-legacyA2-16-400640-90s.json` | `c8bb59de195d4e3a1efd8910d5f9413001383a275c9180e7927084533bb1b557` |

相关69×16层像素链另见 [v17尺寸/像素/功能记录](runtime-400640-device-validation.md)，它支持固定场景输出等价，不能由本性能数字重新推导所有动作或光学通过。已有HOME轮的功能门与性能门也应分别阅读，不并入这里四份无HOME测量。

这四个独立进程有A2返回对照，但轮次未随机、温度/调度条件未配平、没有逐帧GPU计时；每轮有有限确认区间与未确认尾段。不能声称长期热稳定、每秒/每帧都30、default20或release APK、全部角色、纯现场相机推理或persistent/cache的HOME恢复已通过。v18新的产品16 UI/schema4保存流程需要其自己的设备记录；本页不覆盖它，也不能用新尺寸短轮推翻旧400×720三十分钟约28.36FPS的限制。
