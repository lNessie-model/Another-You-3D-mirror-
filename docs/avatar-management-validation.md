# v9 本地角色管理实机证据

2026-10-03，Android 11 目标设备已走通本地 ZIP 导入、单视点预览后明确启用、上一版回退和恢复内置的局部流程。无效 ZIP 在内容校验阶段被拒绝，已有当前角色保留。本记录不构成完整导入、生命周期、性能或美术验收。

本次仅离线审计已有 `v9-manager-*` 原始文件，没有操作设备或修改生产源码。结构化审计见 [avatar-v9-management-evidence.json](E:/tripo/output/mirror-program/20261003/avatar-v9-management-evidence.json)：36 个操作采集点、11 项具体状态核对、173 个原始文件/构建与 ZIP 文件的 SHA256。报告 SHA256 为 `d9695b79b9a8c61f0f7d0846cd44092e053b638d5a3eec62ec7e7f8fedc75bdf`。

## 版本与角色身份

实测 APK 为 [AvatarRuntime-v9-import-fix-flat-material.apk](E:/tripo/output/mirror-program/20261003/AvatarRuntime-v9-import-fix-flat-material.apk)，离线重算 SHA256：`8ab4674ca965c199697a1e91ff058b2df247e5f472f090be825a1c8cb157a24e`。对应源码清单为 [avatar-v9-build-sources.json](E:/tripo/output/mirror-program/20261003/avatar-v9-build-sources.json)，其中 Store SHA256 为 `171ab6c9f4a69745fdb427e5cac35ea2449708a797e815b5c06802d1551026dd`，已包含 StatFs 空间检查修复。

| 标识 | 私有包 ID | 实际 GLB SHA256 |
| --- | --- | --- |
| A：镜中向导组合修正版 | `ba51cf60-bf01-49d8-96ba-d5173957a26d` | `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407` |
| B：导入回退测试 B | `6bee3fcf-920c-4161-afbb-9166c88f3541` | `cb5e83314fca0e080880cf9839b45088cba2b6465f2b4db8c750bad4585d12a1` |

两个有效 ZIP 内的 GLB 重新读取并计算了 SHA，与主程序实际报告一致；各自包含五个允许文件。A 的模型与 APK 内置相同，但启用 A 时 `avatar_source=imported` 且包 ID 为 A，因此能区分“导入角色 A”与“加载内置”。无效 ZIP 只有一个条目，对应此次明确的数量校验失败。ZIP 本体、manifest 和 GLB 的哈希均在结构化报告中。

## 实际流程

下表编号对应 `E:/tripo/output/mirror-program/20261003/v9-manager-<编号>-*`。选择状态、XML、运行报告与截图分别核对，不把它们当成同一时刻的原子快照。

| 采集点 | 可验证的结果 |
| --- | --- |
| 05 `invalid`、11 `candidate` | **05 文件名有误导性，实际是有效 B 导入。** `candidate=B`，`current=null`。XML 显示 B、预览已渲染、使用按钮启用。不是无效 ZIP 拒绝证据。 |
| 06 HOME → 07 启动器 → 08 主程序 | HOME 后从启动器重新打开主程序，候选 B 仍在，`current=null`，主程序明确加载 builtin。导入没有自动激活。此流程不证明原 Manager Activity 的 EGL 恢复。 |
| 12 启用 → 13 主程序 | 当前成为 B，候选清空；主程序 `avatar_source=imported`、包 ID B、模型 SHA `cb5e…12a1`，无加载警告或运行错误。 |
| 18 选无效包 → 19 失败 → 20 详情 | 19 使用按钮禁用；20 明确为 `IOException: ZIP must contain 2 to 5 flat allowed files`。15 与 19 的 selection 文件字节哈希完全相同，19/20 当前仍是 B。此次到达 ZIP 内容校验，区别于 v8 的 `SecurityException: getFileStore`。 |
| 24 导入 A | `current=B`、`candidate=A`；XML 报告预览已渲染，截图也能看到真实角色。候选不覆盖当前。 |
| 25 启用 → 26 主程序 | `current=A`、`previous=B`、候选清空；主程序实际加载 imported A，模型 SHA `8349…e407`。 |
| 29 预览上一版 → 30 回退 | XML 显示预览 B，并启用“回退到预览的上一版”；30 持久状态变为 `current=B`、`previous=A`、候选清空。30 的运行报告尚为加载初始态，不用于证明首帧。 |
| 31 重开主程序 | 新运行 session 读取并渲染 B，当前/上一版状态保持。执行者记录为 force-stop 后重启；该采集点的 action 字段为 null，本组文件本身没有保存 force-stop 命令，故动作来源与新 session 的直接证据分开记录。 |
| 34 预览内置 → 35 启用 → 36 主程序 | `current=null`、`previous=B`，主程序明确加载 builtin，模型 SHA `8349…e407`。恢复内置由明确操作触发。 |

已查看 05、13、20、24、26、29、34 的 PNG：24 的管理页显示真实角色，13/26 主程序显示交织角色，20 显示准确校验错误。05/29/34 的 PNG 仍处于加载中，而稍后 XML 已就绪；不将这些早期截图当成成功预览画面，也不将这一采集时间差判成渲染失败。XML 中的动作按钮存在和“52 项输入已映射”文字，不代表本轮已逐项检验动作或完成美术验收。

## 相机恢复与证据限制

主程序的五个独立返回/切换后快照均有有效 USB 采集，`capture_failures=0`，应用错误和角色加载警告为空：

| 采集点 | 采集 FPS | 收到图像数 | 运行 session |
| --- | ---: | ---: | --- |
| 08：候选未激活 | 24.4863 | 1,250 | `94e75516-51b8-4b30-8583-0c1d8791f361` |
| 13：B | 24.4739 | 858 | `e2e28469-ef82-4dda-b57e-d77ca0fdc476` |
| 26：A | 24.5556 | 754 | `a424ef3a-8f6e-4b08-b330-9d911818393e` |
| 31：重开后 B | 24.6128 | 764 | `8ea2264f-97fd-485d-b42a-411f8869e42d` |
| 36：内置 | 24.4446 | 490 | `f28d072e-5956-48bf-9748-e257f491668a` |

以上证明主程序恢复采集，不是实际呈现 FPS。五组 `face_frames=0`、`mesh_calls=0`、`post_calls=0`，处于等待人脸和低频分析状态；没有执行完整 478 点、52 表情及头姿流水线，不能用于证明导入角色下完整面捕性能或 30 FPS。20 个 320×576 视点的配置也不能代替全负载实际呈现测量；性能范围仍以 [avatar-runtime-validation.md](E:/tripo/device-lab/docs/avatar-runtime-validation.md) 的专项报告为准。

原始证据还有三项边界：

- 01–04 的 `selection.json` 内容实际是 `cat: files/avatars/state.json: No such file or directory`，不是合法 JSON，也不能解析成全部 null 的状态。05 起才有真实 catalog。
- 管理页、文件选择器期间许多 `runtime.json` 与上次主程序快照哈希相同，是最后已知状态；不能据此声称后台相机持续运行、无缝呈现或没有资源泄漏。
- 07 只有 action 和 PNG，没有 XML、runtime 或 selection。12/25/30/35 切换后的即时 runtime 仍为 pending；使用随后稳定主程序快照确认实际模型。

## 尚未完成

实际阻塞的 SAF IO 中断、提交与 HOME/超时竞态、同一 Manager Activity/EGL 恢复、摄像头或文件提供方权限拒绝、真实低空间、断电持久性与所有恶意包类别尚未设备验收。主机门控和 Store 回归不能替代这些实验。

本轮也不覆盖导入角色下完整面捕、持续 30 FPS、30 分钟/2 小时耐久及切换资源占用稳定期、通用贴图/蒙皮导入或导出、最终角色美术和实体屏光学校准。生产角色仍是程序生成的原型；本记录只确认上表所列局部功能路径。
