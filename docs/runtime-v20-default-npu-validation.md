# v20 默认 NPU／persistent FBO：使用与 90 秒验收

2026-10-03，RK3566 Android 11，设备 `6L32552009566714`。**v20 两次普通无 extras 启动使用原有 16×400×640 配置，并启用了新的默认混合 CPU/NPU 表情后端和四组 persistent FBO。另一次完整 90 秒联合短测通过：互动呈现 30.663533 FPS，三个连续区间均超过 30 FPS。** 摄像头权限拒绝分支没有验成；这不是新的 v20 长测通过或全项目完成声明。

## 构建与默认启动

APK 为 [AvatarRuntime-v20-production-npu.apk](E:/tripo/output/mirror-program/20261003/400x640/AvatarRuntime-v20-production-npu.apk)，39,866,952 B，SHA `21ee8a3d11e94c0ac399740364f4ba235ceea8b54d177d9bfcda44b56b01b3da`。源码 ZIP SHA `ce5b30c6d6dd8ae3492f59880be0f43207f17b95c523b73950a7f953e3940682`；冻结清单 SHA `299bebea4d8e9d11bec26985ab4091f5e83ad4eeb2a0ee1eb3090ca33afcdf97`。

独立核对两个源码 ZIP 的精确 146 项集合及逐项字节/hash：相对 v19 只改变 `MirrorActivity.java`，与已审默认候选全文相同（SHA `344a7a2e8dbf1422ed34f25db20aaaef4883c6eaa7b915a560656e13a8e7e539`）；其余 145 项相同。两个 APK 全部 24 个 assets/library 条目集合和字节均相同。修改是 Mirror 的默认 NPU／persistent 两个选择及其已有 11 个 test 字段的严格 presence/type 校验；原数学、模型、渲染算法和保存设置不变。debug 可显式选择旧后端；release test 字段拒绝等合同来自候选 259 项主机检查，**本次设备包为 debug APK，没有运行 release APK**。

root 记录离线 Gradle 工具会话 `76326` 为 `BUILD SUCCESSFUL`、39 秒。没有另存独立构建日志；本审计验证了 APK、源码和资源链，没有伪造或声称独立重看 Gradle 日志。

两次 [普通启动 v1](E:/tripo/output/mirror-program/20261003/v20-default-device-v1/ordinary-start.json)／[v2](E:/tripo/output/mirror-program/20261003/v20-default-device-v2/ordinary-start.json) 的命令均为 `am start -n com.mirror.bench/.MirrorActivity`，之前 force-stop 并清除精确旧 status。返回的不同 Activity／input session 与命令 stdout 字节闭合；16／17 个已完成分析帧、124／132 个真实 USB 图像，source error 为空，实际 persistent 4、NPU 模型 metadata 与原 Android AppCheck 完整相同。

现场无脸，状态均为 WAITING、完整 face/post 调用为 0、idle render target 10。这里证明普通入口、采集进度和后端初始化，**没有证明现场 478/52 精度或无脸状态的互动 30 FPS**。状态没有同期 `/proc/uptime`，不从字段伪造独立绝对设备年龄。

## 权限拒绝仍待验

两个组合探针保留 `completed=true, passed=false`：第一轮短暂等待后 Back，没有取得拒绝状态；第二轮等不到实际 resumed 权限对话框，未发 Back。两次最终 grant 命令成功，但命令成功本身不是权限实际改变的证明。

独立 [permission-state 命令证据](E:/tripo/output/mirror-program/20261003/v20-permission-state-v1/commands.json) 显示 `pm revoke` rc 0 后、以及随后启动后的两次 `dumpsys package` 仍为 `CAMERA: granted=true`（UID 10116，package targetSdk 30）。因此分类为 **设备未能施加拒绝**，不能把应用未走到拒绝分支写成通过，也不猜唯一系统原因。90 秒结束后的独立 package 读取确认当前 granted=true。

## 新的 90 秒联合短测

冻结 v19 collector 沿用原分析／SF 严格门。该次启动只传 `runtime_input=camera_replay` 和两个黑场值 0，**没有传 NPU、persistent 或视点覆盖参数**。采集参数中的 Boolean false 是 CLI 未请求该覆盖；实际 status 确认默认 NPU 和 persistent=true，不能把该参数解释成运行了旧后端。

| 指标 | 实测 |
| --- | ---: |
| 实际采集时间 | 91.562 s |
| 互动呈现 FPS／最低区间 | 30.663533／30.630134 |
| 确认互动时间／未知尾部 | 79.857645／4.947895 s |
| 三段 FPS | 30.630134／30.641216／30.849053 |
| 互动整机 CPU／GPU busy／NPU busy 均值 | 76.436%／84.732%／39.676% |
| PSS 均值／峰值 | 346.799／462.737 MiB |
| 温度均值／峰值 | 63.413／66.250 °C |
| 同区间快照差分完整 face／USB received | 15.991574／24.565569 FPS |
| 同区间快照差分外层 post 墙钟 | 45.676647 ms |

独立合并 120 条 journal 的八类 delta，80 次 poll、42 次 status read →18 个新状态、28 次 surface discovery，重新执行冻结 collector 后 presentation/system/interactive 等完整对象相等。另从 SF 原文第二列去重时间戳及状态事件并集直接复算三段 `Σ(帧数−1)/Σ(首末时间差)`；无 collection error、状态/SF 缺口或图层歧义，final snapshot→独立 force-stop→end 闭合，原 34 ms／0.000001 FPS 容差未改变。

16 个 INTERACTIVE 快照全部为完整 478 点／52 表情、真实 USB 640×480 采集与预录人脸推理、内置完整 GLB（SHA `8349c9b7…f1e407`）、1200×1920 输出、16×400×640、analysis target 17／active render target 31、persistent 4、per-frame camera matrices、individual draw 和有界 CPU pose worker。GL context/frame generation 均为 1，实际原生 metadata 全对象等于原 AppCheck：CPU OneEuro/canonical pose＋FP32 规范化＋固定模型 `17b0773a…831c` 的混合 CPU/NPU 后段。不是纯 NPU 网络，也没有将 GL 移到 NPU。

资源仅来自确认 INTERACTIVE 的 71 个系统样本、68 对 CPU tick、15 个 PSS；整机 CPU/busy 不等于某阶段耗时。face/USB/post 差分只用同区间 12 对状态，覆盖 60.531877 s、968 次 post，不跨 GRACE。最后整个输入会话为 1282 completed／1280 face/post，post 均值 45.523876 ms；最后一个 expression 对象只有 175 帧、API 均值 17.770115 ms，期间两次可见计数回落。对象计数与完整会话必须区分，不能拿 API 均值替代整个 post，也不能据此跨温度、时长、APK作因果性能比较。GL timing bucket 另属整个 context，包含 GRACE；五阶段和六个 view 子阶段墙钟已核闭合，不是逐帧 GPU 时间。

原始 [JSON](E:/tripo/output/mirror-program/20261003/400x640/avatar-v20-default16-400640-npu-90s.json) SHA `ee1d0b69bc6ac8bcda06c2dd54ce50a30f8a2e77397322efb43acb02ff8b0de8`；[journal](E:/tripo/output/mirror-program/20261003/400x640/avatar-v20-default16-400640-npu-90s.polls.jsonl) SHA `343dde1fba0bfb41ce0a24be3f4e21c3f43c6f073e2e9e78e2317000dc4126fc`。[结束身份证据](E:/tripo/output/mirror-program/20261003/v20-after-90s-v1/audit.json) 核同 APK 和全部 19 项原型偏好：973 B、SHA `43bd19b1…56b37`，未改保存配置。此身份命令没有时间戳，不单凭该文件推导严格跨目录时序。

## 保留资格与未完成范围

原 [v19 NPU 30 分钟资格](E:/tripo/device-lab/docs/runtime-v19-npu-endurance-pass.md) 保留，只适用于其冻结 v19 APK，不能改名为 v20 长测通过。两小时已按用户要求 [SKIPPED_BY_USER](E:/tripo/output/mirror-program/20261003/v19-endurance-user-skip-v1/user-skip.json)，`completed=false, passed=false`；本审计没有读取或执行两小时实际审计。

本次没有重跑 92／24 精度、像素对比、HOME/EGL、光学校准或全部 UI，现场脸部效果仍需相应验收。配置 codec／事务／Store／owner／目录候选的主机证据不能替代维护页备份恢复 UI、全部入口配置门和真实 camera/GL 资格接线；这些仍未完成。模型、骨骼校正和背景的新目标也不因这份短测文档完成。

独立 [审计器](E:/tripo/device-lab/app/build/audit_v20_defaults.py)／[审计 JSON v2](E:/tripo/output/mirror-program/20261003/400x640/runtime-v20-defaults-device-audit-v2.json)。`runpy.run_path(..., run_name='audit_recheck')['audit']()` 只重算返回对象，不写 output；直接运行须非 `-O`，最终输出独占新建且拒覆盖。第一份 v1 审计及当时源码已保留；v2 只补显式未接线边界，数值不变。本审计没有调用 ADB、Gradle、设备或修改应用源码。
