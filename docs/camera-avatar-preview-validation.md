# v13 校准角色预览设备验证

2026-10-03。本轮设备证据支持：校准面板内已能看到真实内置角色；预览提交帧持续增长时，主多视图渲染保持暂停，相机继续采集；取消、再次打开和 HOME 恢复完成了短流程验证。**真人动作方向、中性确认成功、细微眼口动作是否易于辨认以及长期资源稳定性仍未验收。** 所有归档状态都没有有效人脸，不能把可见的中性角色当作动作映射成功。

本文仅离线复核现有归档，没有重新操作设备或修改应用。实现与主机验证见[预览 helper 合同](../tests/camera-calibration-avatar-preview.md)；上一轮方向 Bitmap、设置保存及恢复证据见[v12 相机验证](camera-controls-device-validation.md)。

## 被测版本和采集边界

- [APK](../../output/mirror-program/20261003/AvatarRuntime-v13-calibration-avatar-preview.apk) SHA-256：`b6b593d86bd301c55ae30ef63dc61ae5bc01e9575df3862e7eca3e8fd69c8f52`。离线重新计算与[构建清单](../../output/mirror-program/20261003/avatar-v13-build-manifest.json)一致，文件 34,673,134 字节。
- 清单包含 93 个源码/资产/构建文件，`source_files_stable_through_build=true`；清单 SHA-256：`356faead37db56e7b58fca7e0ca97af5aa70cdd48bd0bbabc29b58837029b735`。
- 进程 PID 为 9291，实时 Camera2 ID `0`，640×480；描述指纹 `2296dda0c5bdcdd452d69934d9e1d3329a601e1aa68670f23c0333cd95085cd1`。此指纹不是 USB 唯一序列号。
- 主画面保持 20 视点、每视点 320×576、输出 1200×1920、投影宽高比 0.625，`individual` 绘制和原有 `bounded_cpu_worker`。本轮无脸，主画面采用 10 FPS 空闲目标，不是 30 FPS 联合性能验收。
- 面板预览与主画面的模型 SHA 都为 `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407`，来源 `builtin`，解码资产 5,890,656 字节。证明了本次内置角色一致；尚未设备验证导入的当前用户资产及读取失败路径。

[采集脚本](../../output/mirror-program/20261003/v13_ui_probe.py)先读取已落盘状态，再依次截屏、读取 meminfo/线程和检查配置文件存在性。因此同标签下的状态和截图不是同一原子时刻；应按状态自身的 session、sequence、时间戳确认变化，不能按文件名推断动作已完成。

## 状态序号与恢复证据

下表“主帧”是主 renderer 成功 callback 累计值；“预览帧”是预览成功 GL 提交计数，均不是 SurfaceFlinger 实际呈现帧。前七行属于 session `0a8d5978-f988-4195-bb08-e5e9ca6d3f73`、epoch=1。

| 归档状态 | sequence | 面板/预览 | 主帧 | 预览帧 | 当前输入 completed |
| --- | ---: | --- | ---: | ---: | ---: |
| [main](../../output/mirror-program/20261003/v13-main-status.json) | 12 | 关闭 | 531 | — | 156 |
| [panel-first](../../output/mirror-program/20261003/v13-panel-first-status.json) | 18 | **旧状态仍为关闭** | 841 | — | 249 |
| [panel-ready](../../output/mirror-program/20261003/v13-panel-ready-status.json) | 25 | 打开，READY | 1093 | 266 | 599 |
| [panel-stable](../../output/mirror-program/20261003/v13-panel-stable-status.json) | 35 | 打开，READY | 1093 | 736 | 1598 |
| [cancel](../../output/mirror-program/20261003/v13-cancel-status.json) | 35 | **仍是取消前旧状态** | 1093 | 736 | 1598 |
| [cancel-confirmed](../../output/mirror-program/20261003/v13-cancel-confirmed-status.json) | 43 | 关闭，预览字段已移除 | 1463 | — | 109 |
| [reopened](../../output/mirror-program/20261003/v13-reopened-status.json) | 45 | 再次打开，READY | 2001 | 38 | 101 |
| [home-resumed](../../output/mirror-program/20261003/v13-home-resumed-status.json) | 2，新 session | 关闭，预览字段已移除 | 2011 | — | 1 |

`panel-first` 截图已出现加载面板，但 sequence 18 的状态还是此前落盘的数据，不能解释成“面板打开失败”。同样，`cancel-status.json` 与 `panel-stable-status.json` **字节完全一致**，SHA-256 均为 `1bc4dad928bede3632489210fca7e7ac9bed7a538e658ac8c9c550eb14d3eb6e`；不能用这个旧 sequence 35 声称取消未释放。实际取消确认采用新 sequence 43，主帧恢复增长，截图回到主角色画面。

首次 READY→stable 的状态捕获间隔为 **50.253 秒**：

- 预览帧从 266→736；按两次 `last_frame_elapsed_ns` 的差计算约 **9.35 次成功 GL 提交/秒**，符合 `maximum_fps=10` 上限。没有采集预览 SurfaceFlinger 历史，不称实际呈现 FPS。
- 主 renderer 的整个 JSON 快照不变，包括 frames=1093、主 pose worker 的 submitted/processed/published=1093、pending=0、writing=0。结合 `main_multiview_paused=true`，支持主多视图确实暂停，没有在面板期间继续消耗渲染工作。
- 相机 capture_results 从 735→1963，增加 1228；received_images 从 736→1964；当前同一输入会话的 completed 从 599→1598，增加 999。相机与处理继续运行，处理在线均值约 19.83→19.86 FPS，但 face_frames 始终 0，未执行完整有效人脸动作负载。

取消后及重开后输入会话有新的 `input_open`，completed 重置，不能拿 1598 与 109/101 作累计差。HOME 恢复属于新 session `5efd2557-b6a4-4470-83af-add82254fc4c`、epoch=2；记录新的 `input_open` 和 completed=1，足以确认恢复首帧完成。其 source 计数仍为 0、输入均值分母仅约 22 ms，不能用 44.66 FPS 的瞬时分析均值证明稳态速度。主 renderer 帧数跨此次保留的 GL context 延续，不应与新的输入 session 计数混算。

所有列出的当前状态为 WAITING，当前运行/渲染/控制/source 错误为空，recoveries=0。旧 session 事件历史包含进入维护期间的 ERROR→WAITING，不能用当前空错误概括“全程没有 ERROR 状态”。

## 角色可见性与当前限制

[首次加载截图](../../output/mirror-program/20261003/v13-panel-first.png)显示加载状态与不可用保存按钮；[READY 截图](../../output/mirror-program/20261003/v13-panel-ready.png)、[稳定截图](../../output/mirror-program/20261003/v13-panel-stable.png)和[再次打开截图](../../output/mirror-program/20261003/v13-reopened.png)均可看到面板右侧的真实角色、名称及模型 SHA 前缀。左侧相机规范输入与右侧角色分开显示，解决了 v12 不透明弹窗挡住唯一背景角色的问题。GL viewport 为 **225×360（10:16）**，未把宽矩形面板直接拉伸为角色投影。

不过，角色在截图中仍偏小，眼睑、瞳孔与嘴唇变化是否足够清楚尚待真人验证。相机图像没有可判向的文字或人脸，左眨眼/右眨眼/张口读数均为 0；这组图只能证明模型被真正绘制，不能证明输入驱动了正确动作，更不能替代左右视线、镜像、头姿和中性基准的验收。此次也没有验证已导入角色的模型身份匹配。

## 线程与 HOME 相机释放

独立解析 `ps -A -T`，只统计 **PID=9291** 的行；不能把全系统线程合计当成本应用线程。源码中的 CPU pose worker 线程名是 `avatar-pose`，并非 Java 类名 `AvatarPoseWorker`。设备记录如下：

| 线程快照 | 本进程线程总数 | avatar-pose | CameraAcquire | MirrorRuntime |
| --- | ---: | ---: | ---: | ---: |
| [main](../../output/mirror-program/20261003/v13-main-threads.txt) | 32 | 1 | 1 | 1 |
| [panel-first](../../output/mirror-program/20261003/v13-panel-first-threads.txt) | 36 | 1 | 1 | 1 |
| [panel-ready](../../output/mirror-program/20261003/v13-panel-ready-threads.txt) | 34 | 1 | 1 | 1 |
| [panel-stable](../../output/mirror-program/20261003/v13-panel-stable-threads.txt) | 34 | 1 | 1 | 1 |
| [cancel](../../output/mirror-program/20261003/v13-cancel-threads.txt) | 33 | 1 | 1 | 1 |
| [cancel-confirmed](../../output/mirror-program/20261003/v13-cancel-confirmed-threads.txt) | 33 | 1 | 1 | 1 |
| [reopened](../../output/mirror-program/20261003/v13-reopened-threads.txt) | 35 | 1 | 1 | 1 |
| [HOME 后台](../../output/mirror-program/20261003/v13-home-threads.txt) | 31 | 1 | 0 | 0 |
| [home-resumed](../../output/mirror-program/20261003/v13-home-resumed-threads.txt) | 35 | 1 | 1 | 1 |

所有快照只有原有一个 `avatar-pose`（TID=9335），与预览状态的 `additional_pose_workers=0` 一致。首次面板的额外 GL 线程 TID=9442 在取消确认中消失；重开后是新的 TID=9650，HOME 快照中也已消失。一个有界加载器线程 `CameraAvatarLoa`（系统显示的截断名称，TID=9443）在关闭后仍保留，符合共享 executor 的设计，不能描述为“预览所有线程均已退出”。线程名重复的主 `GLThread 1057` 行也不能直接解释成多个正在渲染的主场景。

[HOME 相机服务记录](../../output/mirror-program/20261003/v13-home-camera.txt)显示 `Active Camera Clients: []`、`Device 0 is closed, no client instance`，事件中有本应用 PID=9291 的 DISCONNECT；同次 HOME 线程快照没有 CameraAcquire/MirrorRuntime。这是后台采集点的实际释放证据，强于仅检查 UI 面板关闭。它不覆盖整个后台时间轴或长时间反复切换；线程“存在”本身也不能证明是否正在占用 CPU。

## 内存与设置副作用

下列数值来自各文件的 `TOTAL PSS`，单位 KiB（MiB=KiB/1024）。它们是按顺序采集的应用整体快照，不是纯预览增量、峰值采样或 GPU 显存完整账单。

| meminfo 快照 | PSS KiB | MiB |
| --- | ---: | ---: |
| [main](../../output/mirror-program/20261003/v13-main-memory.txt) | 107430 | 104.91 |
| [panel-first](../../output/mirror-program/20261003/v13-panel-first-memory.txt) | 138808 | 135.55 |
| [panel-ready](../../output/mirror-program/20261003/v13-panel-ready-memory.txt) | 135056 | 131.89 |
| [panel-stable](../../output/mirror-program/20261003/v13-panel-stable-memory.txt) | 155044 | 151.41 |
| [cancel](../../output/mirror-program/20261003/v13-cancel-memory.txt) | 135104 | 131.94 |
| [cancel-confirmed](../../output/mirror-program/20261003/v13-cancel-confirmed-memory.txt) | 132504 | 129.40 |
| [reopened](../../output/mirror-program/20261003/v13-reopened-memory.txt) | 158916 | 155.19 |
| [home-resumed](../../output/mirror-program/20261003/v13-home-resumed-memory.txt) | 115923 | 113.21 |

本组最大观测值是 155.19 MiB，比初始快照高约 50.28 MiB；关闭确认后下降到 129.40 MiB，HOME 恢复后为 113.21 MiB。内存没有在关闭时立刻回到初始值，也有 READY→stable 的增长，不能据此声称零泄漏、长期稳定或完整额外预算已经验证。meminfo 的 Graphics=0 也不意味着预览没有 GPU 资源。需要更长的真实人脸负载、重复开关与平台 GPU 内存观测才能判断耐久性。

8 份 `v13-*-prefs-presence.json` 均为 `exists=false`，包括[初始](../../output/mirror-program/20261003/v13-main-prefs-presence.json)、[预览稳定](../../output/mirror-program/20261003/v13-panel-stable-prefs-presence.json)、[取消确认](../../output/mirror-program/20261003/v13-cancel-confirmed-prefs-presence.json)和[HOME 恢复](../../output/mirror-program/20261003/v13-home-resumed-prefs-presence.json)。采集脚本使用 `test -f` 的 0/1 返回值，避开了 v12 曾发生的 `cat` 错误文本误报存在。本轮只预览/取消，没有显式保存，原先不存在的安装配置文件仍未创建。

## 摄像头权限拒绝尝试：未实际进入拒绝分支

操作者记录了两次 `pm revoke` 返回 0，第二次显式指定 `--user 0`；但返回码不能证明授权已撤销。[撤销前 package 记录](../../output/mirror-program/20261003/v13-permission-before-package.txt)与[撤销后、尚未重新启动应用的记录](../../output/mirror-program/20261003/v13-permission-after-revoke-package.txt)均显示 `android.permission.CAMERA: granted=true`，flags 同为 `USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED`。后者的 User 0 状态为 `stopped=true`，与 force-stop 后尚未重新启动的采集顺序一致。[尝试摘要](../../output/mirror-program/20261003/v13-permission-attempt.json)据此明确标为 `not_exercised`；[revoke 输出文件](../../output/mirror-program/20261003/v13-permission-revoke-result.txt)是空文本，不能单独证明权限状态或弹窗行为。这些证据只说明本次操作未改变查询到的授权状态，不足以归因于厂商的某种具体权限策略。

离线查看[名为 prompt 的截图](../../output/mirror-program/20261003/v13-permission-prompt.png)，实际是主角色画面及“等待人脸”，没有权限对话框；同名 [prompt.xml](../../output/mirror-program/20261003/v13-permission-prompt.xml)实际只有 `cat: /data/local/tmp/mirror-permission.xml: No such file or directory` 错误文本，不是有效 UI XML。因此不能将文件名或命令成功当作权限提示、拒绝后错误提示、再次授权恢复已经通过。

[恢复运行状态](../../output/mirror-program/20261003/v13-permission-attempt-restored-status.json)属于新 session `7c294ba5-182e-49a0-bcfb-c435782e21c8`、sequence=2，输入 `camera`、Camera2 ID `0`，相机已有 capture/received 各 1 帧；当前 WAITING，运行、渲染、控制和 source 错误为空，面板关闭。它支持原授权状态下应用已重新打开相机，不能替代拒绝分支验证，也不是稳态性能样本。撤销前/后 package 文件 SHA-256 分别为 `a56c512de148da218f30239cfe9af87606c7ed6bef90f00b86f487e4c17af4bd` / `156162c396f96c22fb8b5c016d6325519f569e0647ecb3e2ba16c667920e15c2`；尝试摘要 SHA-256 为 `6b56267eba7b602cb6c87268d6ef7d0e8e2675aa5cf2c26a668e9e6980c2b93f`。**真正的拒绝、重试和重新授权流程仍待实测。**

## 验收结论的范围

本轮通过的是短流程的可见预览、暂停主渲染而保持相机、取消恢复、再次打开以及 HOME 采集点释放/首帧恢复。没有发现这组记录中的额外 pose worker 或重复 CameraAcquire。预览提交计数和可见截图共同支持实际渲染，而不是只展示一个静态占位图，但本轮无脸意味着角色始终可处于中性状态。

仍需真人核对角色左右眼、视线、张口、头姿及镜像/中性基准；需要改进或确认当前偏小预览的可读性；需要验证用户导入资产、加载失败/超时、启动极早打开、快速反复 HOME/重开及长期内存稳定性。不能将本次约 50 秒 READY 对照、两次打开和一轮 HOME 流程称为耐久测试，也不能用校准单视图的约 9.35 次提交/秒代替“16 视点以上 30 FPS”主场景目标。
