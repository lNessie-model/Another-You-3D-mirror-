# 持久 OVR4 FBO 调试候选

2026-10-03：主机资源/配置/数值门及 SDK 35 编译通过；v14 已完成严格设备像素门（69×20 层 RGBA 零差异）、HOME 恢复、同 APK 20×320×576 的 A/B/A 及候选 target 35/31 顺序复测。详细原始证据、统计范围及限制见 [设备验收记录](../docs/persistent-fbo-device-validation.md)。候选 B31 为 29.995271 FPS，后续 35/31 为 29.847574/28.788098，伴随温度/频率变化；五轮均未过全部区间 30 FPS 门，不宣称稳定提升，生产默认仍为 legacy/31。v12 `attach_clear` 约 13.95 ms 是附件操作与 clear 合计的 CPU 墙钟时间，可能包含隐式 driver stall，不能假定整个数值都能消除。

## 变更与边界

`MirrorActivity` 的 debug Boolean extra `test_persistent_fbos=true` 选择候选；省略或 false 保留旧路径。非 debug APK 或非 Boolean 类型的显式覆盖拒绝。它只改变 runtime avatar 的 OVR array FBO 绑定，模型、20 个独立视点、视图分辨率、投影/交织、材质、shader、pose worker、目标帧率不变。其它绘制实验不应同时改变，以便归因。

20 层原 color/depth texture array 仍各一份。候选创建 5 个 FBO，固定 attachment 的 base 为 0/4/8/12/16、numViews=4、mip=0；每帧每组 bind 对应 FBO，代替两次 `MultiviewGl.attach`。**每组 color/depth clear、所有 draw、depth invalidate 的内容和顺序保留**，不增加 GL finish/flush/fence。多出来的是相对于 legacy 单 FBO 的 4 个 framebuffer 对象，生产路径没有额外颜色/深度存储。原有初始化阶段 `glFinish` 未改变。

规则依据 Khronos [OVR_multiview](https://registry.khronos.org/OpenGL/extensions/OVR/OVR_multiview.txt) 的 attachment 状态、baseViewIndex/numViews、完整性和 Clear/Draw 广播定义。每个 FBO 初始化时检查 GL error、FRAMEBUFFER_COMPLETE，再查询两附件的实际 object type/name、mip、base 和 numViews。Shader 沿用已有 [OVR_multiview2](https://registry.khronos.org/OpenGL/extensions/OVR/OVR_multiview2.txt) 路径。

`PersistentMultiviewFbos` 只拥有最多 8 组 FBO names，不拥有纹理；创建过程中任一组失败会删除所有已经分配的 names。尺寸变化先删除当前 context 的旧组，再由 renderer 替换纹理并重建。每次 `onSurfaceCreated` 增加 generation，并丢弃旧 context 的 Java 引用，禁止在新 context 删除旧数字 ID。bind 验证 GL owner thread、generation、base 范围；暂停时 EGL context 是否保留沿用平台现有行为。候选失败硬报 renderer error，不回退 legacy 冒充候选运行。

运行状态：

- `debug_render_overrides.persistent_fbos`：请求值。
- `renderer.multiview_fbo_requested`：`legacy` 或 `persistent_groups`。
- `renderer.multiview_fbo_actual`：初始化前/重建中 `uninitialized`，对应 FBO 初始化成功后 `legacy` 或 `persistent_groups`，候选发生渲染故障后 `failed`。
- `renderer.persistent_fbo_count`：成功候选为 5，未建/清理后为 0。

`actual` 表示附件后端初始化成功，不是 SurfaceFlinger 已实际呈现的证明；同时核查 runtime_gl_frame_ready、error 和真实呈现数据。已有分段计时保持相同边界；候选的 `attach_clear` 现在包括固定 FBO bind 与原 clear，JSON 定义已明确两种口径，仍然不是独立 GPU 耗时。

## 最小设备像素入口

现有 debug `AvatarPreviewActivity` 增加 `verify_persistent_fbos=true`，与 `verify_multiview` / `verify_batch` 互斥。root 在设备独占阶段可执行：

```text
adb -s <serial> shell am start -n com.mirror.bench/.AvatarPreviewActivity --ez verify_persistent_fbos true
adb -s <serial> exec-out run-as com.mirror.bench cat files/avatar-persistent-fbo-check.json
```

调用 `AvatarPersistentFboCheck.run`，复用现有 69 个真实资产姿态（包括 52 单源和头/眼/下颌/组合）。每姿态只 `prepare` 一次、同一个同步 Scene、同一 VBO/世界矩阵/OVR shader；两侧均绘制完整 20 层，每层 400×720、physical aspect=.625。baseline 每组执行 legacy 两次 attach；candidate 使用与生产相同的 persistent helper。两侧都 clear color/depth、draw、invalidate depth。诊断使用两套独立 array 存储避免互相覆盖；这项额外内存仅在 readback 验证中存在。

每个 global layer 使用单层 read FBO 读 RGBA8；不从 multiview attachment 直接 ReadPixels。**预先固定 max RGB=0、RMSE=0、alpha mismatch=0**，任何一个字节差异都失败，不能事后放宽。保留每层两侧 SHA、非空/不透明检查、首末视点差异和中心跨姿态差异，防止两侧同画一个空层/重复相机/永远中性而假通过。报告沿用 `serial_*` 字段时 reference 实际是 legacy OVR4，`reference_label` 与 `comparison_backend=legacy_ovr4_vs_persistent_ovr4` 明确说明。

必须等待本次 run_id 的 running=false、completed=true、passed=true、completed_fixtures=69、prepare_calls=69、layer_comparisons=1380、所有层严格一致，并确认没有取消或 cleanup_error。该报告不是帧率证据，十张普通预览截图也不能替代上述像素门。默认旧 multiview/batch 入口和原阈值没有改变。

## 主机验证

```powershell
.\tests\run_persistent_fbo_tests.ps1
.\tests\run_persistent_fbo_sdk_tests.ps1
.\tests\run_avatar_multiview_fixture_tests.ps1
.\scripts\test_java.ps1 -JavaHome 'C:\Program Files\Java\jdk-17'
.\tests\run_camera_preview_sdk_tests.ps1
```

新增资源测试先 RED（缺少 helper 导致编译失败），随后 GREEN 63 项：固定层基址、200 帧绑定不再 attach、所有已分配 FBO 部分失败清理、resize 替换、线程/generation/越界/关闭检查。实际 SDK 35 编译全部变更生产类；15 项配置/像素门检查使用实际 Activity 选项解析和 renderer 状态，证明默认 legacy、严格 debug Boolean、未初始化不声称 actual，以及原宽松门允许的单字节差异会被新严格门拒绝。只对 Bundle/SystemClock 使用主机输入/时钟 stub，JSON 是真实 JSON-java，不模拟 GL。

既有 69 姿态 fixture 4048 checks、renderer 92 checks（含 100 万次 runtime 有界样本）、pacing 22、相机头姿逐位 3000 checks及其它原 host suite 通过。没有安装 APK、操作 ADB 或运行 Gradle；资源 fake driver 不能证明 GPU 驱动实际 attachment 状态，必须经过上面的设备门。

独立只读 review 未发现可复现 P1/P2，复跑资源测试 63 项通过；核对了两侧独立 array、同一 prepared pose/VBO、global base、单层读取和 context/resize/部分失败清理。本结论仍不替代设备像素及吞吐测试。

## 后续同 APK 性能门

先完成严格像素门和取消/重开/尺寸重建的设备检查。之后在同 APK、同模型 SHA、同 CPU/NPU 输入、同 20 视点和同分辨率下只切换 `test_persistent_fbos`。先沿用 320×576 的联合负载 A/B；400×720 单独作为质量基线，不混算。每次包括进入 INTERACTIVE、足够稳态区间和可靠 SurfaceFlinger 历史，记录 requested/actual/count、CPU/GPU/NPU、温度、PSS、face completion 与各段 CPU wall。

### Collector 开关与恢复

`scripts/run_runtime_check.py --persistent-fbos` 是显式 debug opt-in；省略时默认 legacy。首次 `am start -n` 和 HOME 后带 `-f 0x20020000` 的恢复命令使用同一个 extra 集合，启用时都传 `--ez test_persistent_fbos true`。没有写入偏好文件。原始报告 `arguments.persistent_fbos` 始终记录 Boolean 请求值，`host_actions` 的 `start` / `resume` 保留实际完整命令；请求值不能代替上述 renderer `actual` 和成功帧检查。

下面是由设备持有者在像素门通过后执行的候选采样示例；legacy 对照只移除 `--persistent-fbos` 并改成独立报告名，其余参数保持相同：

```text
python scripts/run_runtime_check.py --name persistent-fbo-b-90s --input camera_replay --seconds 90 --output-dir ../output/mirror-program/20261003 --view-preset 320x576 --persistent-fbos
```

HOME 生命周期独立复测可再加 `--pause-at 30 --pause-seconds 5`；主机 fixture 已实际执行 collector 的两个 launch 分支并检查 extra，默认两次命令均不带该选项，同时核对 JSON 请求值及已记录命令。新增测试先 RED（parser 缺少字段、实际 launch 漏传），实现后 `python -m unittest discover -s tests -p test_runtime_metrics.py` 共 33 项 GREEN。此验证只 mock ADB/时钟，不运行设备，不能替代设备恢复与 FBO actual 检查。

收益可能来自避免驱动重建附件状态，也可能因每组 FBO bind、tile 处理或原本由 clear 承担的 stall 而没有收益。若像素不一致或没有同负载真实呈现改善，保留 legacy 默认；不减少视点、不删深度、不降低几何/画质来冒充此次候选收益。用户硬指标仍为“16 视点以上 30 帧”，本次实验保持现有 20 视点。
