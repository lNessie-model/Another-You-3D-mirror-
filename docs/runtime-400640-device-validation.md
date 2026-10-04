# 每视点 400×640：v17 构建与设备验证证据

2026-10-03，YS-L6，设备序列号 `6L32552009566714`。本记录由独立审查者读取冻结文件核对；审查者没有使用 ADB、构建或安装 APK。

**v17 的 16 视点、每视点 400×640 三组设备像素门通过，并逐姿态、逐层连接了三份报告的图像 SHA 链。** 这是完整角色渲染输出一致性证据，不是联合负载帧率、光学校准或真人效果通过证明。

## 冻结的 v17 构建

证据目录：`E:/tripo/output/mirror-program/20261003/400x640/`。

| 文件 | SHA-256 |
|---|---|
| `AvatarRuntime-v17-400640.apk`（34,690,770 bytes） | `43ec48bc82a996c4e576021c6a7c1043e7468f88765ddcd772123ed0b2cdfbe9` |
| `avatar-v17-build-manifest.json` | `78ec8faaa6fc78a889df7c9053f89991c7ce39b8e2c9247687ffea771d33d89f` |
| `avatar-v17-sources-before.json` | `bc1f2f6e877cb55b62e94156f3466da78a70fe6de1a65c00c2f8b49c0d7a7aa1` |
| `AvatarRuntime-v17-sources.zip` | `ed2d83196a7137a02fae1fe7ac6e69130505da1ae52603d8a17d20c6db9b9ecb` |
| `v17-gradle-build.log` | `2a6be5f5570e7bd182eb4520cefef98c0f22aea0d99124c7b7c635f551b2862a` |

独立核对结果：

- manifest 的 100 个输入名称和 SHA 与 before 清单完全相同。源码 ZIP 也恰好有 100 个不重复 entry；只将清单的 Windows 路径分隔符转换为 ZIP 的 `/` 后，名称集合完全相同，全部内容 SHA 匹配。没有以正在开发的后续版本工作树代替 v17。
- 对提交 `4b84e77cf3a19642f36aead676341051e6eeeeda` 再次比较：81 个文件逐字节相同，6 个文件仅 CRLF/LF 不同，无其他已跟踪内容差异。另 13 个模型、元数据、图片或原生库没有纳入该 Git 提交，但全部存在冻结 ZIP，并由上述清单绑定。
- 6 个换行差异文件为内置角色的 `avatar.json`、`LICENSE.txt`、`README.md`，以及 `AvatarPackageStore.java`、`InterlaceRenderer.java`、`MirrorSettings.java`。
- 日志实际包含 `:app:compileDebugJavaWithJavac`、dex/package/assemble 完成和 `BUILD SUCCESSFUL in 19s`，共 63 项任务，5 项执行、58 项 up-to-date。设备操作者另报告 job 76087 已终态 exit 0。日志中 SDK XML 版本与 deprecated API 警告不等于构建失败。

该快照覆盖列出的 `app/src` 与 Gradle 配置输入；它不是完整工具链、依赖缓存或所有模块源码的可复现构建证明。清单记录构建前后输入稳定，归档 APK 与清单 SHA 一致；仅凭这些记录不能独立证明编译器执行过程。安装成功由设备操作者报告，后续设备验收还须检查安装包 SHA。

## 模型、角色和原生库没有替换

独立打开 v17 与冻结 v16 APK（v16 SHA `728bf3ff354f5cd4ebc499c3e60ffd05afe3d694ac8687a66108e6fa378663e5`），两者 `assets/`、`lib/` 文件集合完全相同：11 个资产、8 个原生库，合计 19 项逐字节相同，并全部匹配 manifest 中的哈希。

| 关键 APK 内容 | SHA-256 |
|---|---|
| `assets/avatars/builtin-guide/character.glb` | `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407` |
| `assets/face_blendshapes.tflite` | `4f36dded049db18d76048567439b2a7f58f1daabc00d78bfe8f3ad396a2d2082` |
| `assets/face_detector.rknn` | `ae6adedcb63bc8100acde16db9d1044297687e569fcd6ac4a0c4e6c0c82d58a2` |
| `assets/face_landmarks_detector.rknn` | `c7005608b4c2398d48f023d4acc03089dbffe5a81686a4adfa210c0be73f6495` |
| `lib/arm64-v8a/librknnrt.so` | `01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de` |
| `lib/arm64-v8a/libmirror_npu.so` | `82ec0534b200a7da54a576ea1818e025cfad8a5694fbc672739ffa8d23b0e43e` |
| `lib/arm64-v8a/libmirror_rknn_face.so` | `ab46f37cb59c67f793092ec3e80e995077c0e7e20b89838d2b0d344a72ae0035` |

冻结 `NpuFacePipeline.java` 明确加载两份 RKNN 图像网络；`FacePostGraph.java` 使用 `Delegate.CPU` 执行原 52 表情网络及姿态求解。额外的 MediaPipe、RGA、GL、C++ 共享库也包含在 19 项相同比较中。因此本轮没有把隔离实验的表情 RKNN 候选接入主程序，亦不能把隔离候选的推理耗时作为本 APK 的加速成绩。

## 三组设备像素证据

v17 支持 400×640 档位；此时产品配置仍使用 schema 3，默认视点数仍为 20。16 视点像素入口由明确 debug 参数选择，不把后续 schema 4 产品默认 16 的开发结果倒填为本 APK 行为。

设备操作者确认三项操作均已终态成功，最后 cached job 67687 exit 0；本次审查只在三份完整原始报告都落盘后进行。三份报告均 `completed=true`、`running=false`、`passed=true`、`cancelled=false`，无 error/cleanup error，并明确 `artwork_validated=false`、`performance_evidence=false`。

下列文件仍位于同一 `400x640/` 证据目录，SHA 均独立重算。

| 文件 | SHA-256 |
|---|---|
| `v17-serial16-400640-pixels.json` | `95c0bcda69c60db7d7fe9baa61160ffd871a258f5561d531af3cd2df38d57204` |
| `v17-persistent16-400640-pixels.json` | `9f69d1b63e37c5268e567c5f1fa6647842f65720876d737d457425884e6d272c` |
| `v17-cached16-400640-pixels.json` | `d1a2b719501a4d4a8b0c7178e6d0b1348a2ec7b06603be83d02117eb6bbaf961` |
| `v17-serial16-400640-pixels-commands.json` | `49af356cbc5a6bcf2d6699750c1ee7f39b4a067a076179430af428f8faec4dc8` |
| `v17-persistent16-400640-pixels-commands.json` | `730098186594010d3043c412268566d5edb93a5eb232c879bdd414f0c39fd567` |
| `v17-cached16-400640-pixels-commands.json` | `5da7f42a62c671784e929b69dc855486b991565200ab9e6cdef7ace37bb41230` |

三个独立 run ID 分别为 `71aa575a-df82-4004-b9b3-eacdb790cb03`、`80c72b26-f966-4aa7-b777-6bc3482ee011`、`40498c8a-d595-422c-a3ce-76959a94d73c`，context generation 都为 1。每轮命令记录均先核设备序列号与实际安装 APK SHA，清自己的固定结果文件及独立配置错误文件，再以明确 `test_view_count=16`、`test_view_preset=400x640` 启动相应入口。运行前后 APK SHA 都与归档 v17 相同。

离线核对了每轮最后读回的完整 JSON 与归档对象相等；开始/更新时间都位于该轮设备 uptime 窗口内，最终读回距更新时间分别约 3.579、1.025、1.193 秒，均小于预定 10 秒门。没有读取到有效的配置错误 JSON。注意早期 `cat` 缺失文件的信息虽然命令退出为 0，也只视为尚无报告，未当作结果。每轮最后均有 force-stop 成功记录，没有通过重启同轮来隐藏失败。

### 姿态、视点和像素全量核对

三组实际后端依次为 `individual_serial_vs_individual_ovr4`、`legacy_ovr4_vs_persistent_ovr4`、`per_group_camera_vp_vs_cached_camera_vp`。GPU 报告为 ARM Mali-G52 / OpenGL ES 3.2，OVR 最大单组 4。三组均为真实内置 GLB，模型 SHA 与 APK 一致，15,085 顶点、29,482 三角形、7 primitives、52 输入完整映射。角色姿态采用 `synchronous_gl_thread`，每个输入只 prepare 一次，双方共享同一变形 VBO；这不是实时摄像头/NPU 联合运行测试。

- 每组恰好 69 个唯一姿态、69 次 prepare、1,104 层比较；三组合计 3,312 层对。实际和 requested 尺寸均为 400×640、视点数均为 16，物理投影比例为 1200/1920=0.625。
- 不是只核对姿态数量：从冻结 ZIP 的 `AvatarPoseFixtures.java`、`BlendshapeSchema.java` 独立列出所有输入，核对每个名称、source index、完整 52 权重及三项 head angles。包括中性、0…51 全部 one-hot（头姿为零）、6 个独立头转、4 个视线组合、左右张口前移、闭嘴修正、微笑闭眼倾斜、头部范围组合和全控制组合，共 69 项全部相符。
- 每姿态的 global view 完整为 0…15，OVR base 为 `floor(view/4)*4`、relative 为 `view%4`；eyeX 与原逐步 float32 均分表达式相同，覆盖约 -0.2…+0.2。每一侧每姿态都有 16 个不同的图像 SHA，未以重复中间视点凑数。
- 全部层对的 RGB byte mismatch、最大 RGB 误差、RMSE、alpha mismatch 均为零，两侧图像 SHA 相等、非不透明像素数为零。每层前景为 30,037…35,779 像素，高于非空门。按实际中心层 SHA 再数，三组都为 67 个姿态相对中性产生变化；中性及 `source-_neutral` 不变符合预期。
- strict serial 门：实际送入诊断路径的 256 个矩阵 float 与独立原 renderer 表达式比对，0 bit mismatch、16 个不同矩阵、投影顺序单调。报告没有保存完整矩阵数组；离线审查核对实现及报告门，不声称重新计算了 JSON 未存的 256 个值。
- cached 门：25 个精确 key（视点 1/4/16/20/32 × 五种物理尺寸）共 7,200 float 比对，0 bit mismatch，25 次 invalidate/rebuild 检查；逐 key 的视点、尺寸、float32 aspect 和零差异都重新检查。persistent 与 cached 实际均为 4 个固定 OVR4 FBO 组，cached 参考侧也为 4 组，两条路径都执行相同深度清理/失效操作。

### 跨报告的像素链

按完全相同的姿态输入和 global view 逐项比较，得到：

| 连接 | 核对项数 | SHA 不同项 |
|---|---:|---:|
| serial 报告的 `ovr_sha256` → persistent 报告的 `serial_sha256` | 1,104 | 0 |
| persistent 报告的 `ovr_sha256` → cached 报告的 `serial_sha256` | 1,104 | 0 |

这里后两份报告沿用 `serial_*` 字段命名，其含义分别是 legacy OVR4 参考侧和 per-group Matrix/persistent OVR4 参考侧，并非都执行单视点渲染。结合每份报告内部的零误差门，这 2,208 个连接支持该固定 69×16 场景下从单视点参考、legacy OVR4、persistent OVR4 到 persistent+cached 的输出等价链；没有仅凭三份 `passed=true` 推导组合通过。

## 主界面 HOME / EGL 重建功能验证

随后设备操作者在同一 v17 APK 上完成 90 秒 HOME 功能采集（job 98389，终态 exit 0），原始文件 `avatar-v17-context-release16-400640-90s.json` SHA 为 `e28a799efa1cda1885e96c62074fa8f5f385ceb739525fd1be06e48f3c7fcac9`。本轮为 `camera_replay`，真实 USB 采集持续运行，推理图像来自已录制的人脸；16×400×640、原生 1200×1920、legacy FBO、per-frame VP，target 31，明确开启 debug `release_gl_on_pause`，约第 25 秒 HOME、8 秒后恢复。

该采集使用 `collector-v17/` 冻结代码，manifest SHA `bf87c7c4d5281e95765e91a8b38aac8435f0db89f08882745f1153a4af534e18`。两份脚本均逐 SHA 匹配清单，并与 `4b84e77` 内容一致（仅规范化 CRLF/LF）；没有用后续 schema4 工作树的采集器重新解释来源。独立抽取冻结的纯功能门，重算结果与报告 `gl_context_recovery` 整个对象相同，且逐条核对 18 个解析状态确实出自保存的原始状态读取。

- 同一 Activity UUID `147b1fbb-bb02-4f37-abce-2d1565907a89`，真实 `onSurfaceCreated` 的 context generation 从 1 增至 2；新的成功 frame generation 为 2，ready 为 true。没有通过重建 Activity 冒充同实例恢复。
- runtime session 从 `5f512896-2d48-4928-b456-67112a53ae11` / epoch 1，变为 `b4453c2b-a51e-463b-9ef7-1ad1ecffdac7` / epoch 2。确实采到新 session 的 WAITING、ready=false、frame generation=0，之后新 context 就绪，再进入 INTERACTIVE。
- requested release=true，requested preserve=false，GLSurfaceView getter actual preserve=false；HOME 和 resume 的设备前后时钟完整有序。结合 Android GLSurfaceView 合同及实际回调世代，这支持本轮经历了 context 重建；没有采集原生 `eglDestroyContext` / `eglCreateContext` 函数跟踪。
- 恢复后 10 份完整 INTERACTIVE 样本保持同新 session/context，478 landmarks、52 blendshapes、face present，结果年龄 125…184 ms；完整角色模型 SHA、16×400×640、1200×1920 及 legacy/per-frame 后端身份保持。全部已采状态中没有 ERROR 或报告的 GL/source/processing/control 错误。
- 同新 session 首末互动样本，GL frames 792→2,170，完成推理 74→798，有脸完成 74→797，NPU mesh 74→798、CPU post 74→797，USB received images 127→1,235，capture failures 为 0。证明恢复后继续采集、完整推理并成功提交新世代 GL 帧；不只是停留在一张旧脸或旧画面。

因此本轮独立功能门为 `requested=true, verified=true`，仅覆盖以上 v17、16×400×640、legacy/per-frame、camera_replay 条件。没有据此验收默认20对照、persistent/cache组合恢复、release APK 或全部生命周期情形。完整机制与限制见 [EGL 诊断说明](runtime-gl-context-loss.md)。

**此 HOME 采集不作为性能基线。** 第一完整互动区间为 29.834650 FPS；HOME 附近存在 state-event-ring gap，`complete_active_evidence=false`、`meets_30_fps=false`。合计值 30.281404 FPS 不能覆盖这些限制，未确认尾段为 1.856966 秒；也不能以功能恢复通过推导长期30 FPS达标。

## 仍未由本轮证明的内容

比较对象是交织前的每个 array layer，不是新一轮最终屏幕交织、光学对位或肉眼体验验收。固定动作覆盖也不代表所有任意动作、任意导入角色或驱动组合已穷尽。长时间像素 readback 的诊断耗时不能转换成主程序 FPS。

除上述已明确覆盖的主 EGL/HOME 功能恢复外，16×400×640 无 HOME 联合负载及持续热态 30 FPS仍需独立性能证据。旧 16×400×720 的结果见 [v16 验证记录](runtime-view-count-device-validation.md)：其 30 分钟确认区间为 28.364437 FPS，不可用本轮像素或恢复通过覆盖该限制，也不能当成新尺寸性能结果。后续 schema 4 的产品默认 16、偏好迁移和保存流程不属于本次 v17 的构建或设备验收。
