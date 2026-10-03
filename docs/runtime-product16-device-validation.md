# 产品16视点：构建与设备验收记录

2026-10-03。**v18 调试 APK 构建、维护页草稿取消/明确保存、重启读取及校准页16视点传播已取得设备证据。** 本轮运行状态均为无脸 WAITING，只证明下面列出的配置、界面和输入/GL恢复范围。本记录对应 [16/20持久配置](runtime-persistent-view-count.md)，不把 v17 的像素或性能结果作为本次新 UI/schema4 的设备通过证据。

## v18 构建及冻结产物

设备负责人完成离线 Gradle `:app:assembleDebug`，job 46227 终态 exit0并关闭。原始日志实际包含 `compileDebugJavaWithJavac`、DEX合并、`packageDebug` 和 `assembleDebug`，最终 `BUILD SUCCESSFUL in 33s`，33任务中4执行/29 up-to-date。日志的 SDK XML版本警告与 deprecated API注记不构成构建失败。未生成 release APK，也未将主机替身测试写成实际 Android UI/GL通过。

证据目录：`E:/tripo/output/mirror-program/20261003/400x640/`。

| 文件 | 大小/范围 | SHA-256 |
| --- | --- | --- |
| `AvatarRuntime-v18-400640.apk` | 34,692,069 bytes，调试包 | `c4cf831de43a80e85d59fe0382e14ef439b803219140c66b2580786d1cc04d84` |
| `AvatarRuntime-v18-sources.zip` | 100项源码/构建配置/资源 | `2189bb1442cf3db43d42f7fac79a7e709d0b218223269d83ba70d0ffcaa71812` |
| `avatar-v18-build-manifest.json` | 构建前后source摘要、APK及基线、19项payload | `1caa0fa9321847ad47590bf5c1d425adf1c8bd9a53efbe9cea9d9f505d99efdd` |
| `v18-gradle-build.log` | 实际构建日志 | `ec9a0800d6763ac550eba850ddffaf2d432dd1be1fe2a5324251eb2e550c6097` |

独立只读复核直接读取这些文件与 ZIP/APK 条目，不重新构建：

- source ZIP恰好100个唯一条目，逐项 SHA 与 `avatar-v18-sources-before.json`、manifest及当时工作树相同；构建归档脚本也在构建后和发布归档前后复核源文件未变化。APK实际字节大小和SHA与manifest一致。
- 对冻结 v17 source ZIP，只有 `MirrorSettings`、`RuntimeViewCount`、`MirrorActivity`、`CalibrationActivity`、`PanelPreviewActivity` 五类变化，正好匹配产品16授权范围；其余95项逐字节相同，包括渲染算法、像素诊断、资产、原NPU/native代码及构建配置。
- `app/build/runtime-product16-freeze-v1.json` SHA `2676a68f17126ad4c91b4debfae8ccb906b52c398f1bab43322a4d5befff6b29` 中16个代码/脚本/测试/文档SHA全部匹配；SDK35及host验收范围见持久配置文档，未扩张为设备验收。
- APK `assets/` 与 `lib/` 恰好保留 v17 的19项文件集合，每项字节和manifest SHA均相同，包含完整角色GLB、原TFLite/landmark资产、RKNN detector/mesh、MediaPipe/native库。基线 v17 APK SHA `43ec48bc82a996c4e576021c6a7c1043e7468f88765ddcd772123ed0b2cdfbe9` 也重新核对。all8 LN诊断候选未进入应用。

上述构建来源链证明归档源文件与构建产物绑定、指定资源未改变；安装和新流程执行另以下面的设备 raw 为依据。v18源码由负责人保存为commit `3ec9d33`；提交ID不替代上述实际文件哈希。

## 设备 UI 与运行传播

设备负责人执行实际 Android UI 操作并归档；本节独立复算已有 raw，没有再操作设备、构建或安装。固定 serial `6L32552009566714`、package `com.mirror.bench`。`v18-product-ui-install-commands.json` 记录 `install -r` exit0/Success及安装前后 APK hash，安装后确为上述 v18 `c4cf…4d84`；没有卸载、清数据或替换偏好。安装前 `mirror-runtime.xml` 已不存在，因此本轮是空配置默认值路径，**没有设备验证旧schema1–3迁移**。

| 入口证据 | SHA-256 |
| --- | --- |
| `v18-product-ui-baseline.json` | `a406a6f21d02891b831da92e5b1792738477ec949cee91586525e296e8968919` |
| `v18-product-ui-install-commands.json` | `f3e2b940a5a35de74f4d94c9c11a346b08e1cfdf9ec99377c0c9146dcae50ada` |
| `v18-product-ui-actions.jsonl` | `cf2f8b55d3da405b7ec6751945ececf88dd0b8e103b3b40f17e5488d0d1cf712` |

actions 有116条命令，host时间严格递增；37份单独归档的 XML/偏好/状态文本逐份与对应命令 stdout 匹配。只有 index29/33 的偏好存在性检查 exit1且无输出，符合尚未保存；其余命令均exit0。三次主启动 index5/64/92 均为 `MirrorActivity --es runtime_input camera`，没有 `test_view_count`、`test_view_preset`或GPU调试覆盖。状态中的 `debug_render_overrides.view_count` 是入口解析后的实际值，不能据字段名推断传了debug extra。

按 action 顺序核对的结果：

1. 空配置运行和维护页显示16/400×640/17 FPS。先把草稿依次改为20、320×576、10 FPS，`draft-all-changed-status.json` 仍运行16/400×640/17；点“返回”后偏好仍不存在，重开维护页恢复原默认草稿。
2. 明确保存20/320×576/10，偏好为schema4，应用重建为新 Activity并运行相同档位。随后force-stop、清旧status、普通启动，另一个新 Activity仍读取20/320×576/10。
3. “恢复运行默认草稿”仅把草稿改为16/400×640/17；未点保存时偏好仍是20档。返回再打开仍显示20/320×576/10，偏好字节hash未变。再次恢复默认草稿并明确保存后，偏好及实际运行变为16/400×640/17；force-stop/普通重启后保持。
4. 从实际维护页进入光学校准，XML显示“预览16视点测试图”；进入普通PanelPreview显示“16视点：01–16独立数字/颜色；光学未确认”。返回校准再取消回主，主Activity实例未变，输入session/epoch更新，偏好字节与最终16档完全相同。

六份偏好 XML 都是973 bytes，含19个唯一键；逐项校验 Android XML类型与值，没有把字符串或Boolean强制转换为数字。20与16两组只改变 `active_fps`、`view_preset`、`view_count`；其余16项一致。

| 键/类型 | 核对值 |
| --- | --- |
| `schema_version` int；`view_count` int；`view_preset` string；`active_fps` int | schema4；20/320x576/10 或16/400x640/17 |
| `panel_pitch` / `panel_tan` / `panel_phase` float | 10.0 / 0.2777777 / 0.0 |
| `panel_units` / `panel_order` / `panel_origin` string；`panel_reverse` boolean | SUBPIXELS / RGB / BOTTOM；false |
| `camera_id` / `camera_fingerprint` string | 两项均空，未绑定相机配置 |
| `camera_width` / `camera_height` / `camera_rotation_degrees` int | 640 / 480 / 0 |
| `camera_reflect_input` / `camera_mirror_interaction` boolean；`camera_revision` long | false / false；0 |

`saved20-preferences.xml`、`default-draft-preferences.xml`、`default-after-cancel-preferences.xml` 三份均为 SHA `1fd515efbfcef6f42ed9b741c3f212962dc9f2a7b59df61e0bebdcc179fa95f7`；`final16-preferences.xml`、`final16-restarted-preferences.xml`、`preview-return-preferences.xml` 均为 SHA `43bd19b1d31c03648a81fb7e18ecd778c47f4d34b3879e3041f2e0f815056b37`。本轮保留的是这些默认光学/未绑定相机值，不能扩张为任意安装配置或角色选择变更的恢复测试。

| 状态文件（均 `v18-ui-` 前缀） | 实际 count/尺寸/FPS | USB收到 / 分析完成 / GL帧 | 实例关系 |
| --- | --- | --- | --- |
| `fresh-default-status.json` | 16 / 400×640 / 17 | 363 / 46 / 164 | 初始实例 `a176cbe3…` |
| `draft-all-changed-status.json` | 16 / 400×640 / 17 | 1127 / 139 / 474 | 同实例/同session，sequence5→11 |
| `saved20-applied-status.json` | 20 / 320×576 / 10 | 998 / 123 / 417 | 保存后新实例 `f21c4035…` |
| `restarted20-status.json` | 20 / 320×576 / 10 | 1124 / 139 / 474 | 强停后新实例 `2ec3c6f7…` |
| `final16-applied-status.json` | 16 / 400×640 / 17 | 1487 / 183 / 617 | 保存后新实例 `d4296ca7…` |
| `restarted16-status.json` | 16 / 400×640 / 17 | 630 / 78 / 270 | 强停后新实例 `30a80408…` |
| `preview-return-status.json` | 16 / 400×640 / 17 | 499 / 62 / 599 | 同 `30a80408…`，新session/epoch2 |

七份状态的 configured/main renderer count和每视点尺寸一致，实际输出1200×1920，GL ready且context_generation=frame_generation=1、错误字段为空、capture_failures=0。fresh→draft同session的序号、单调时间和输入/分析/GL计数均增长。预览返回主实例完整ID为 `30a80408-3476-44d4-bd18-5c44e3f3731b`，新输入session `6c769e54-bd7f-4f48-92e1-948cc0ce2fd0`；新session的499计数不能与上一session的630比较增长。它提供一次返回后的新session快照，未冒充两份连续恢复采样或真实EGL context-loss验收。

状态 capture/updated 单调时钟相差17–31微秒；强停重启前清了旧status，实例身份改变，返回后session/epoch及单调时间更新。该UI动作日志没有独立device-uptime读取夹界，**不能由host读取时间界定绝对status文件年龄**。七份均为 WAITING、face_present=false、face_frames/landmarks/blendshapes=0、stored_face_images=false；不能称为完整478/52人脸或性能通过。

普通预览的源码 `PanelPreviewActivity.createRenderer()` 使用每视点**320×576**和calibrationPattern；本轮仅确认16视点UI传播与该源码合同，不称预览也采用400×640。没有触发verify入口、没有生成新400640像素报告，未确认光学安装；没有检查截图像素或导出脸图。本轮为调试APK的真实产品配置路径，未验证release APK、commit失败持久回滚、任意重建耐久或长期性能。相关边界依 [持久配置说明](runtime-persistent-view-count.md)。
