# v12 相机校准设备验证记录

2026-10-03。此次验证完成了真实 Android Bitmap 的方向/反射数值检查，以及相机设置界面的草稿、取消、保存重启和 HOME 恢复流程。**未完成真人动作方向、中性采集成功、角色动作可见预览的验收。** 当时相机没有检测到人脸；勾选“已核对”属于保存流程测试，不能代替人工观察左右眨眼、视线、张口和转头。

本文只复核已归档文件，没有重新操作设备。实现合同见[相机控制接线](camera-control-integration.md)、[输入方向转换](camera-input-transform.md)、[中性采集器](neutral-calibration-collector.md)。同期 90 秒渲染分段报告的性能口径独立分析，不用本页 UI 快照证明 30 FPS。

## 被测版本与证据身份

- APK：[AvatarRuntime-v12-camera-controls-and-view-stages.apk](../../output/mirror-program/20261003/AvatarRuntime-v12-camera-controls-and-view-stages.apk)。SHA-256：`aec31137c9b69014e0d6f582060c0792416462558ce525e54faccbcad8411fc3`。本次离线复核重新计算文件哈希，与构建清单相符。
- [构建清单](../../output/mirror-program/20261003/avatar-v12-build-manifest.json)记录 87 个源码/资产/构建文件，`source_files_stable_through_build=true`。清单 SHA-256：`46fa5efc8eb05b150a71652fcb1e6c451cb6e4cb26607884fdb1cd0d60eb5a90`。
- 相机为 Camera2 ID `0`，输入 640×480，描述指纹 `2296dda0c5bdcdd452d69934d9e1d3329a601e1aa68670f23c0333cd95085cd1`。这是描述兼容性的指纹，**不等同 USB 唯一序列号**；相同描述的不同摄像头仍可能匹配。
- 校准界面之前及本页所列状态均为实时 `camera` 输入、无有效人脸。场景配置保持 20 视点、每视点 320×576，pitch=10 SUBPIXELS、tan=0.2777777；这些字段不表示实体光学已经校准完成。
- 后续新增的 `CameraCalibrationAvatarPreview` 单视图角色预览 helper 不在这份 APK 的构建清单内，不能把其主机测试算作 v12 设备结果。

## 真实 Bitmap：8 组、344 项断言通过

[设备数值报告](../../output/mirror-program/20261003/avatar-v12-camera-transform-check.json)的 run ID 为 `a6887839-2bee-426e-8735-c6ebc583f0f3`，状态 `completed`、`passed=true`、`failure_count=0`。报告 SHA-256：`45173f28b88557eeb4594f260c076c7f33348a4878a5fe1d6cc72cda7c29c286`。

测试在真实 Android `ARGB_8888` Bitmap 上运行，使用两套完全不透明、非灰度的 3×2 六色图。预期值由显式源像素索引表给出；没有通过重复调用被测变换计算预期。覆盖顺时针 0°/90°/180°/270°，分别关闭和开启“旋转后左右反射”，共 8 组，每组 43 项断言。离线重新核对了全部 8 组的 96 个结果像素、宽高、身份与回收字段，均与报告预期一致。

| 检查 | 实机结果 |
| --- | --- |
| 0°、不反射 | 返回原 source 对象；关闭 normalizer 后借入 source 仍可读取、没有被 recycle |
| 其余 7 种变换 | 输出不别名 source；第二帧复用同一输出；两套图的逐像素 ARGB 都一致 |
| 宽高 | 0°/180°输出 3×2；90°/270°输出 2×3 |
| 所有权与关闭 | 所有 source 均未被回收；非恒等情况下自有输出在 close 后被回收 |

这是 Bitmap 变换与复用所有权的设备证据。它不覆盖 USB 摄像头固件的实际朝向、NPU 关键点方向、角色左右命名或真人镜像互动。

## 界面与运行配置流程

界面入口由[维护页 XML](../../output/mirror-program/20261003/v12-ui-maintenance.xml)及[校准面板截图](../../output/mirror-program/20261003/v12-camera-panel.png)保留；维护页中的相机校准入口可用。面板显示源尺寸、旋转/反射选项、动作镜像、采集中性、取消及显式保存按钮。下表的“三项配置”按顺序表示：顺时针角度 / 规范输入反射 / 角色动作镜像。`T/F` 为开启/关闭。

| 操作后的归档状态 | 面板 | 三项配置 | 可确认的行为 |
| --- | --- | --- | --- |
| [进入维护前](../../output/mirror-program/20261003/v12-live-before-ui.json) | 关 | 0 / F / F | 初始实时相机身份与默认配置 |
| [打开校准](../../output/mirror-program/20261003/v12-panel-open-status.json) | 开 | 0 / F / F | 面板 owner 已发布，绑定当前相机描述 |
| [旋转 90°](../../output/mirror-program/20261003/v12-rotation90-status.json) | 开 | 90 / F / F | 方向草稿进入运行输入；记录新的 `input_open`，已有 1 帧转换和推理完成 |
| [再启用输入反射](../../output/mirror-program/20261003/v12-rotation90-reflect-status.json) | 开 | 90 / T / F | 反射与动作镜像分别配置；输入重新打开 |
| [再启用动作镜像](../../output/mirror-program/20261003/v12-rotation90-reflect-mirror-status.json) | 开 | 90 / T / T | 动作开关进入 controller 配置；当前输入会话已完成 97 帧、仍无有效人脸 |
| [取消草稿](../../output/mirror-program/20261003/v12-cancel-restored-status.json) | 关 | 0 / F / F | 三项回到原配置，面板关闭；没有把试验中的 90°/反射留在运行设置 |
| [保存后重启](../../output/mirror-program/20261003/v12-saved-restart-status.json) | 关 | 0 / F / T | 已保存的动作镜像在新运行 session 恢复；不是只修改内存草稿 |
| [打开默认草稿](../../output/mirror-program/20261003/v12-default-draft-status.json) | 开 | 0 / F / F | 默认草稿临时关闭动作镜像，尚未保存 |
| [HOME 后恢复](../../output/mirror-program/20261003/v12-home-resume-status.json) | 关 | 0 / F / T | 丢弃未保存默认草稿，恢复已保存镜像；新 epoch/session，面板未继续占有旧输入 |

保存重启前的 runtime session 为 `a041efbd-a0f2-4edd-9b2b-650ba43d116a`；保存重启后为 `1aad43a2-3a25-41fa-b68e-16236024954c`；HOME 恢复后为 `baecab60-1605-45e8-aa03-9dcac8f11c3f`、epoch=2。HOME 快照包含新的 `input_open` 和 `completed_frames=1`，能说明恢复后至少一帧处理完成；其 source 计数尚为 0，不能从这份初始快照断言稳定采集帧率，也不能证明整个后台期间 HAL 资源释放情况。

上述快照的当前 `error`、`render_error`、`control_error`、`source.error` 为空，`processing_fault=false`。但同一早期 session 的事件历史保留了 `152512239704267` ns 的 ERROR 和 `152516096238362` ns 的 WAITING 恢复，不能概括为“全程无错误”。这些归档快照没有给出该历史错误的完整原因，因此不在此归因。

## 无脸中性采集：只验收拒绝路径

[超时截图](../../output/mirror-program/20261003/v12-neutral-no-face-timeout.png)显示 `TIMED_OUT · 0 个样本 / 0 ms`、“采集超时，可重新采集”，确认中性按钮不可用。[对应运行状态](../../output/mirror-program/20261003/v12-neutral-no-face-status.json)仍为 WAITING，`face_frames=0`，`neutral_head_session_only=false`、`personal_baseline_session_only=false`。

因此可以确认：没有人脸时不会生成可确认的中性结果。不能据此证明合格真人的采集会达到 READY、确认后的 R0/个人眼口视线基准正确、旧 READY 在换输入时被撤销，或基准能改善动作。这些成功/切换路径仍需真人设备验证。HOME 后的两个 session-only 标志仍为 false，但本轮从未建立过有效基准，故也不能声称已实测“清除了已确认的真人基准”。

## 保存内容与恢复原始“文件不存在”状态

[保存后的 XML](../../output/mirror-program/20261003/v12-saved-settings.xml)及[重启后的 prefs 读取](../../output/mirror-program/20261003/v12-ui-maintenance3-prefs.json)包含 schema=3、相机 ID/指纹/640×480、rotation=0、reflect=false、mirror=true、revision=2。原 profile `active_fps=17`、`view_preset=320x576` 与原光学字段仍保留。XML 没有中性头姿或个人基准字段。

两份 XML 在 CRLF→LF 归一化后逐字符完全一致，归一化 UTF-8 的 SHA-256 为：

```text
27302c594cb915fa3f82eee76ab31300d0cb3e654fa801143c23c57268d53c69
```

本地 CRLF 文件的原始 SHA 为 `68a709d2da1acc5599efd86df8065d935327fbe61926bae6d1ce9be52ebde5a7`；不能把换行差异误报成保存内容变动。

初始状态以[补充核实记录](../../output/mirror-program/20261003/avatar-v12-settings-before-verified.json)为准：`exists=false`，`shell_test_f_exit=1`，草稿尚未写入 preferences。较早的 `avatar-v12-settings-before.json`、`v12-ui-maintenance-prefs.json` 和 `v12-ui-maintenance2-prefs.json` 虽写有 `exists=true`，其 `xml` 实际内容却是 `cat: ... No such file or directory`。这是 `exec-out cat` 返回 0 导致的采集误判，**不作为存在旧配置文件的证据**；原始错误记录保留。

[恢复记录](../../output/mirror-program/20261003/v12-settings-restored.json)明确 `original_absence_restored=true`，只清理测试生成的 `shared_prefs/mirror-runtime.xml`。其 `removed_test_xml_sha256` 与上述归一化 XML 哈希一致。本轮恢复的是原始“文件不存在”状态，而非恢复某份此前存在的 XML。恢复记录 SHA-256：`5af9233d837e74ab51abd4120b5a29c5984edc49a22877b31df7323f72f2f91b`。

## 未通过完整用户体验验收的部分

[v12 校准面板截图](../../output/mirror-program/20261003/v12-camera-panel.png)中的不透明 modal 覆盖了背景角色，面板上虽提示“背景角色用于检查动作”，实际没有提供可见的角色动作预览。图中的输入预览也没有用于判向的文字或真人。故本轮不能声称用户可以在该面板内完成所有方向/动作确认。

后续设备验收仍需要：可见的真实角色单视图预览；带字母 F 或文字的相机方向核对；分别测试左/右眨眼、左右/上下视线、张口及三轴转头；动作镜像开关对照；真人中性采集成功、显式确认及取消/换输入/HOME 撤销。UI 复选框测试和数学/主机回归不能替代这些观察。

本轮也没有用这些短暂 UI 快照量化旋转/反射的稳态资源增量、预览额外内存、相机拔插恢复、后台长期资源释放或 16 视点以上 30 FPS 达标。尤其输入重开后只有 1 帧的 FPS/均值统计，不具备稳态性能代表性。
