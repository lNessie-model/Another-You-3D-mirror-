# 真实 GLB 角色：设备验证与性能迭代

本轮将连续运行入口接入实际 GLB，保留完整 52 项面捕输入、独立左右眼、眼球和头部转动、下颌及组合修正。模型仍是原型美术；文件导入/切换、相机方向与中性校准、真实角色长时耐久等仍需完成，不能由本页局部结果宣称整个项目完成。

## 可追溯版本

证据目录：`E:/tripo/output/mirror-program/20261003/`。APK 使用 `adb install -r` 更新，未清空应用数据、改系统分区或强制频率，之前的回退 APK 保留。

| 版本 | APK SHA256 | 作用 |
| --- | --- | --- |
| AvatarRuntime-v3-ab.apk | `44d0a133f4ef7b9c66e06d57f6dc541b991019d19f9111f1daf52103e711e746` | 同 APK 同步/异步首轮对照；异步缓冲复制尚有额外 CPU 成本 |
| AvatarRuntime-v4-check.apk | `0e8f129ade4001f4fdb3927137267a3ee0c47897f19ee02b23c4bcf9ef7322eb` | 加入真实角色逐视点核对入口，渲染/模型与 v3 相同 |
| AvatarRuntime-v6-bulk-copy.apk | `b0c4588f8bfdbaa1616fff1efce463e31f0625ccad36119d0e3692376c9931b9` | 批量槽复制、首帧加载门控、恢复会话状态隔离 |
| AvatarRuntime-v7-heap-cache.apk | `89642634988ba93f68477df90ba3147e1fc822116f6429d7d214b876c6a552f8` | 只增加构造期源数组缓存，模型和画质不变 |
| AvatarRuntime-v8-import-batch.apk | `5f38fe6cb60f14f4249cae1122f96a1c8217950ee82821358edb540789a65af9` | 本地角色管理与调试合并绘制；真机发现空间查询兼容问题，尚不可作为导入交付版 |

生产模型 `character.glb` SHA256：`8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407`。15,085 顶点、29,482 三角形、7 primitive，解码资产 5,890,656 字节。43 个原始形变加 9 个组合目标，8 个视线来源控制刚性眼球；几何目标数不等同于独立动作数。坐标、绑定和限制见包内 README 及 `docs/avatar-runtime-design.md`。

## v3 同 APK 联合负载

串行完成两组 90 秒测试，真实 USB 640×480/25 FPS 采集、已录人脸完整 NPU/CPU 面捕、原生 UI、真实角色 **20 个独立视点、每视点 320×576**，输出物理 1200×1920。面捕目标 17 FPS、渲染节拍目标 31 FPS。面捕使用录像以保证完整人脸负载，因此这不验证当前摄像头画面准确率；PSS 包含录像文件映射。角色取景与场景完全相同，只有调试开关改变形变调度。

| 指标 | 同步：avatar-v3-sync-a-90s.json | 后台：avatar-v3-async-b-90s.json |
| --- | ---: | ---: |
| 全部确认互动区间实际呈现 FPS 下界 | 24.3941 | 28.1340 |
| 后两个完整历史区间 FPS | 24.7935 / 25.1109 | 29.1000 / 29.4203 |
| CPU 全机占用均值 | 81.39% | 93.36% |
| GPU 忙碌均值 | 74.31% | 86.45% |
| NPU 忙碌均值 | 36.52% | 37.98% |
| 完整有效面捕 FPS | 16.3107 | 14.7226 |
| 有脸比例 | 99.865% | 99.851% |
| 形变均值（不含 rig） | 16.7906 ms | 21.0309 ms |
| 后台输出复制均值 | 无 | 11.2590 ms |
| VBO 上传均值 | 0.3159 ms | 0.5036 ms |
| CPU 输出提交到应用的年龄均值 / 峰值 | 同步 | 53.79 / 181.29 ms |
| PSS 峰值 | 444.76 MiB | 456.90 MiB |
| 温度范围 | 60.0–70.0°C | 64.444–74.444°C |

两组均**没有达到 30 FPS**。后台组后运行、温度更高；不是等温控制实验。首段真实呈现历史分别缺 1.6877 / 1.4037 秒，不能把全程下界称为完整历史或删去缺口来过线。尾部未被最后状态确认约 1.39 / 1.45 秒。采集脚本归档在 `v3-collector/`；后续收集器改进不会覆写这些原始文件。

异步队列最多一个待处理输入、一个最新完成结果、三个固定输出槽。这里不是无限排队：过时输入可被更新输入替代，所有视点共享同一完成姿态。实测表明第一版异步的复制成本挤占 CPU，面捕吞吐下降，必须继续优化，不能只凭画面帧率提高就称其完成。

## 图像、交织及恢复验证

- `avatar-v3-device/report.json`，run ID `45f5227f-bcc7-4854-82b0-347a5ca71ecf`：十组真实 GLES 动作图，下载时逐文件核对 SHA。已查看独立闭眼及张颌闭唇截图；生成成功和非空几何不替代全部表情美术验收。
- `avatar-v4-multiview.json`，SHA256 `5ea4fa68af8438632b83efa8edff5c0ddf289deedbfb69be4d6354306f2b516f`，run ID `8584e865-931c-4393-a7aa-6ae6615eb7ae`：69 组姿态，每组只 prepare 一次，在相同 VBO/节点矩阵上分别执行单视图与 OVR4。比较全部 20 个全局图层、每视点 400×720，共 1,380 对；RGB 最大误差和总差异均为 **0**，alpha 一致且不透明，首末视点有实际差异。约 361 秒的同步读回诊断不是性能测试，也不证明异步调度、光学或美术质量。
- PanelPreview 的 HOME/恢复：`panel-v4-before-home.json` 为 context 1 正在校验；`panel-v4-home-cancelled.json` 为同 run、cancelled=true、passed=false；恢复原 Activity 后 `panel-v4-resumed-green.json` 换新 run ID、context 2，重新完成 6 组参数×4 路径×1200×1920 RGB 共 165,888,000 字节、零差异。`optical_alignment_verified=false` 始终保留。

## v6 批量复制与加载/恢复

后台复制改用一次预分配的 float 数组和显式数组批量 get/put，保留槽租借、revision 和关闭协议。55 项主机断言及当前真实模型 64 组持续姿态与同步结果逐浮点一致。同模型、20×320×576、相同 USB/录像面捕与 UI 负载的新版实测如下：

| 指标 | v6 同步 90 秒 | v6 异步 90 秒 |
| --- | ---: | ---: |
| 确认互动区间实际呈现 FPS | 24.4973 | 28.2447 |
| CPU 全机 / GPU / NPU 均值 | 81.38 / 74.42 / 36.16% | 91.23 / 84.48 / 38.26% |
| 完整有效面捕 FPS | 16.2286 | 14.9864 |
| 形变均值 | 16.7579 ms | 22.0447 ms |
| 槽复制均值 | 无 | 0.5590 ms |
| 已应用姿态年龄均值 / 峰值 | 同步 | 40.40 / 144.32 ms |
| PSS 峰值 | 452.11 MiB | 454.28 MiB |

原始报告 `avatar-v6-sync-a-90s.json` SHA256 `d8ccef9574a138ea69f95c47e96347c8e2840ed86b45c36113d3e5d0925ca3f5`；`avatar-v6-async-b-90s.json` SHA256 `8517da96799825e7bd2271ae9f70a6e65cb8e91040e3213c06d365c177f26aa4`。新版收集器覆盖了两组全部确认互动区间，状态/呈现历史无缺口；未确认尾段分别 5.1704 / 0.8412 秒，仍不对尾段作性能声明。两组均未达 30 FPS。v3→v6 的复制均值由 11.2590 降为 0.5590 ms，但版本同时包含加载/报告修复，收集器也改进了；只能把 v6 内同步/异步称为同 APK 对照。复制改善没有自动带来全程 30 FPS，22 ms 的变形和 CPU 竞争继续需要优化。

新增形变进度 watchdog：只有明确 resume 可重置恢复宽限；晚到的 GL 回调不算后台任务有进展。显示首帧成功提交后才开始面捕，加载期显示“正在加载角色”；这与 SurfaceFlinger 已实际呈现仍是两个不同指标。启动状态发布和恢复生命周期另做设备回归。

`avatar-v6-home-resume-60s.json` 完成一次 HOME、暂停 6 秒、恢复原 Activity 的检查，未记录运行错误。两个 session 各自从 seq=1、WAITING/WAITING_FOR_GL、face=false 开始；恢复后约 1.160 秒打开输入，1.576 秒进入 INTERACTIVE，新相机帧 3→735、RKNN mesh/post 1→476。该报告含会话事件环重置产生的 8.155 秒保守状态历史缺口，功能恢复证据不能宣称连续性能达标。恢复期间总 PSS 的增加几乎全部来自 Other mmap / Private Clean：164,669 / 164,736 KiB，Java/native 堆同期基本稳定。`ReplayFrameSource` 只读映射约 375 MiB 录像后逐帧触页与此吻合；不能将其判为堆泄漏，仍需完整循环后的稳定期验证。

## v7 构造期缓存与待测项

`AvatarDeformer.java` 源码 SHA256 `04880d29c7a2fb3a8a809cbae9d8fd61b169761b248e8a8136bb68e4447b040e`。源资产本来就是只读堆缓冲区，改动减少的是热循环 Buffer 调用和索引开销，不是去掉 JNI 读取。当前异步双 Deformer 增加约 3.126 MiB 数组负载；保持目标/分量顺序、double 累加、动态法线及有限值/溢出检查。详细内存预算和独立逐位回归见 `tests/avatar-deformer-cache.md`。先用同 APK、相同 320×576 负载比较同步/异步，再单独检查 400×720 画质基线。

| 指标 | v7 同步 90 秒 | v7 异步 90 秒 |
| --- | ---: | ---: |
| 确认互动区间实际呈现 FPS | 28.9378 | 29.2387 |
| CPU 全机 / GPU / NPU 均值 | 84.11 / 87.80 / 35.46% | 87.42 / 87.90 / 40.15% |
| 完整有效面捕 FPS | 16.0609 | 15.5296 |
| 形变均值 | 7.2843 ms | 10.0344 ms |
| 槽复制均值 | 无 | 0.7293 ms |
| 已应用姿态年龄均值 / 峰值 | 同步 | 33.28 / 117.93 ms |
| PSS 峰值 | 442.87 MiB | 457.46 MiB |
| 温度范围 | 61.666–71.111°C | 67.5–75.0°C |

报告 `avatar-v7-sync-a-90s.json` SHA256 `1834740bb7c8aacba80e16855f674c8e175f64a063697ba4bb465fe5f492d724`；`avatar-v7-async-b-90s.json` SHA256 `e9962308e2d25be49822f4f2eb4e1701ea1d963364f3c1ee53b987e281a7741d`。确认互动历史完整，未确认尾段分别 0.3029 / 0.5291 秒。v6→v7 同步形变约降低 56.5%，异步约降低 54.5%；不同先后/温度的单次测试不是等温统计实验，但数值一致的缓存获得明确设备收益。异步末段 15.17 秒达到 30.2000 FPS，**全组仍为 29.2387，不能宣称持续 30 FPS**。

另测 `avatar-v7-async-speed-c-90s.json`：仅对该应用执行 `cmd package compile -m speed -f com.mirror.bench`，命令返回 Success，但 `dumpsys package` 从 run-from-apk 变成的是 **quicken**。因此文件名 speed 仅记录请求，实际不是 speed/AOT。确认互动为 29.3865 FPS，未达 30；这约 0.15 FPS 差异不足以单次归因。原始状态在 `avatar-v7-dexopt-before.txt` / `avatar-v7-dexopt-after.txt`。Android 11 的 debuggable 应用会把 speed 转成 safe-mode quicken，不能通过成功返回值推断模式；依据 [PackageDexOptimizer](https://android.googlesource.com/platform/frameworks/base/+/android-11.0.0_r1/services/core/java/com/android/server/pm/PackageDexOptimizer.java) 和 [ART compiler filter](https://android.googlesource.com/platform/art/+/android-11.0.0_r1/runtime/compiler_filter.cc)。后续收集器保存完整应用编译状态，避免仅凭 APK hash 混合不同执行模式。

## v8 合并绘制核对与联合性能

`avatar-v8-batch.json` SHA256 `c7eae1b2b1c26b303a6ea8b36f7a3529a312efb5d2b8e27546cafb2700c55293`，run `1ccb18fd-6909-49db-ac1a-3d76bba3e52c`。69 姿态 × 20 个独立全局视点、每视点 400×720，原串行路径与 OVR4 合并路径共 1,380 对全部通过预先规定的容差。1,379 对完全一致；mouthFrownLeft 的第 16 图层有一个 RGB 字节差 1，alpha 差异为 0。结论是容差等价，不是逐位相同；约 360 秒同步诊断读回不是性能测试。

同 v8 APK、同内置模型、同 USB 采集＋录像完整面捕＋原生 UI、异步形变、20×320×576 的 90 秒顺序 A/B：

| 指标（确认互动区间） | 原 individual | 调试 batched |
| --- | ---: | ---: |
| 实际呈现 FPS | 29.3158 | 27.8562 |
| CPU 全机 / GPU / NPU 均值 | 87.529 / 87.481 / 45.886% | 86.767 / 93.772 / 45.848% |
| 完整有效面捕 FPS | 15.0571 | 14.974 |
| VBO 上传均值 | 0.426 ms | 1.092 ms |
| 已应用姿态年龄均值 | 33.74 ms | 36.10 ms |
| 温度范围 | 62.777–71.666°C | 64.444–75.0°C |

两轮确认互动历史完整、无状态/呈现缺口或运行错误；未确认尾段分别 0.6307 / 0.2869 秒。原始文件为 `avatar-v8-individual-a-90s.json` 与 `avatar-v8-batched-b-90s.json`。合并每帧 35→5 次绘制提交没有带来此轮性能收益，实际帧率低约 4.98%，均未达到 30 FPS。B 在后、温度较高；两轮采样 CPU0/GPU 均为 1.8 GHz/800 MHz，但不能排除温度或顺序影响，不能把单次差异直接归因为 shader。默认保持 individual。该轮后的候选把材质选择从片段阶段移到顶点阶段；v9 对照见下，代码与数值契约见 `tests/avatar-batch.md`。

## v9 顶点材质选择与反向顺序对照

两份原始报告均为 `collection_status=completed`。同 APK SHA256 `8ab4674ca965c199697a1e91ff058b2df247e5f472f090be825a1c8cb157a24e`，ART 实际状态均为 `run-from-apk`；本轮顺序是 **B：batched 先测，A：individual 后测**，每次请求 90 秒。实际已加载状态确认 B 的 `draw_backend=batched`、`material_selection_stage=vertex_flat`，A 为 `individual`，不只依赖启动参数判断后端。

两组都是内置模型 SHA `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407`，15,085 顶点、29,482 三角形；`camera_replay` 同时运行 USB 采集、录像完整面捕、原生 UI、`bounded_cpu_worker` 异步角色形变及 **20×320×576** 视图交织，输出 1200×1920。RKNN 执行检测/478 点网络，MediaPipe CPU 执行 52 表情及 canonical pose。本轮不是刚导入的 A/B 角色切换测试；此处 A/B 仅指两种绘制路径。

下表严格使用两份报告的确认 INTERACTIVE 区间：实际呈现来自 SurfaceFlinger，资源来自 `interactive_system`；CPU 为全机占用，不是应用进程百分比。

| 指标（确认互动区间） | B：vertex_flat batched，先测 | A：individual，后测 |
| --- | ---: | ---: |
| 实际呈现 FPS | 27.0074 | 29.3386 |
| 确认互动时长 | 84.8245 s | 84.5468 s |
| CPU 全机 / GPU / NPU 均值 | 87.880 / 93.675 / 38.063% | 87.764 / 89.238 / 37.050% |
| PSS 峰值 | 460.126 MiB | 452.983 MiB |
| 温度范围 | 62.777–72.222°C | 61.666–71.111°C |
| 未确认尾段（不计入上述结论） | 0.8000 s | 0.7650 s |

两组各有 80 个互动区间资源采样和 16 个 PSS 采样，采样时 CPU0/GPU 分别为 1.8 GHz/800 MHz。确认状态历史及实际呈现历史无缺口、无歧义区间，采集和运行错误均为空。FPS 已按各段真实帧间隔独立重算，与原报告一致；启动、ACQUIRING、短暂 GRACE 及未确认尾段均不混入。A 最后约 15.10 秒的确认段为 30.1020 FPS，整轮仍是 29.3386，**两组都未达持续 30 FPS**。

面捕及角色阶段耗时没有逐帧记录可用于重新裁剪到互动区间，以下保留各自原始范围，两列采用相同定义比较：

| 指标 | B：先测 | A：后测 | 原始范围 |
| --- | ---: | ---: | --- |
| 完整有效面捕 FPS | 14.9267 | 15.5904 | 末次输入 session，含 acquisition/idle |
| 有效人脸结果 / 完成推理帧 | 1,279 / 1,281 | 1,334 / 1,336 | 同上，输出 478 点、52 表情 |
| 推理完成延迟均值 | 99.918 ms | 95.715 ms | 同上，含后处理 |
| 接收帧至完成均值 | 127.043 ms | 119.736 ms | 同上，非最终上屏延迟 |
| NPU mesh / CPU post 均值 | 39.712 / 47.502 ms | 37.918 / 45.270 ms | 各输入 session 的调用均值 |
| morph 计算均值 | 12.141 ms | 10.075 ms | 已应用姿态，自 GL 场景创建，含初始中性姿态 |
| 槽复制 / VBO 上传均值 | 0.689 / 1.171 ms | 0.614 / 0.429 ms | 同上 |
| 已应用姿态年龄均值 / 峰值 | 37.093 / 123.512 ms | 33.914 / 126.458 ms | 同上，不能直接当作摄像头至屏幕延迟 |

此轮 B 的实际 FPS 比 A 低约 7.95%，没有显示合并路径的性能收益，默认保持 individual。B 先、A 后且温度不同，各只有一次测量；不能把差异直接归因为材质阶段、合并本身或热状态，也不能用 v8→v9 的单次跨版本变化推导因果。相同瞬时频率不足以消除顺序、温度和调度影响。

原始证据与离线审计：

- [avatar-v9-batched-first-90s.json](E:/tripo/output/mirror-program/20261003/avatar-v9-batched-first-90s.json)，SHA256 `5d640f023e598520f71968fa340b5e9e8c2cab749f2728b562be8fbaf2139f41`。
- [avatar-v9-individual-second-90s.json](E:/tripo/output/mirror-program/20261003/avatar-v9-individual-second-90s.json)，SHA256 `7c5c8b5250c9183fa495f2770b307a9e02f80079b50aefd8e6013e18a2eb06e4`。
- [avatar-v9-performance-audit.json](E:/tripo/output/mirror-program/20261003/avatar-v9-performance-audit.json) 保留实际模型/后端/ART 身份、帧率重算、各指标原始统计范围及报告哈希，不改写原始数据。

## 角色管理与权限验收边界

v8 管理页已在设备显示真实模型，中性/闭眼/张口/头转截图与取消文件选择证据保存在 `manager-*`。系统选择器的 ADB `input tap` 对条目无反应，键盘 DPAD/Enter 能正常选择并返回；没有因此扩大文件权限或修改 MIME 合同。选择 ZIP 后实际失败于 `SecurityException: getFileStore`，在内容验证之前，因此不能把该结果当成“无效包验证成功”；原截图 `manager-28-invalid-detail.png` 保留。v9 已用 StatFs 修复，并完成有效包导入、候选不自动启用、明确启用、真正无效 ZIP 保留当前、上一版回退、重开后读取及恢复内置的局部实机验证，详见 [avatar-management-validation.md](E:/tripo/device-lab/docs/avatar-management-validation.md)。管理验证中的 USB 约 24.4 FPS 且零采集失败，但没有人脸；完整面捕性能只能引用上面的专项报告。实际阻塞 IO/提交与 HOME 竞态、同一 Manager EGL 恢复和切换资源稳定期仍未覆盖。

摄像头拒绝授权分支仍未验收：本机两次 `pm revoke com.mirror.bench android.permission.CAMERA` 返回无错误，但立即查询仍为 `granted=true`，没有制造出真实拒绝状态；没有改系统权限策略。`permission-01-request.xml` 是早期辅助脚本在 uiautomator 无法等到空闲后误读的旧 XML，**不得作为权限证据**。辅助脚本已改为先删除固定临时 XML，且仅在明确 dump 成功后读取，避免旧界面误判。

尚未完成：真实角色持续 30 FPS 的长时证据、400×720 联合运行性能、2 小时耐久、导入失败/角色切换资源回收、摄像头权限拒绝实测、相机方向/镜像/中性校准及最终安装光学/散热验收。保持原目标，继续按实际瓶颈迭代。
