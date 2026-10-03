# 本地当前状态导出

`scripts/export_runtime_bundle.py` 绑定 `device_profile.py` 中的 ADB 和设备
`6L32552009566714`，只读取 Android 11 的 `com.mirror.bench`。先在设备上打开
MirrorActivity，使状态文件保持更新，再在 PowerShell 执行：

```powershell
$benchPython='C:\Users\lNessie\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $benchPython -X utf8 'E:\tripo\device-lab\scripts\export_runtime_bundle.py' --out 'E:\tripo\output\mirror-current-20261003.zip'
# 需要保存当前已安装 APK 时，换一个新文件名并显式添加此开关。
& $benchPython -X utf8 'E:\tripo\device-lab\scripts\export_runtime_bundle.py' --out 'E:\tripo\output\mirror-rollback-20261003.zip' --include-apk
```

目标父目录必须存在，目标 ZIP 必须是新文件。缺状态、数据损坏、超过限制、读取失败或
写入中断会删除本次未完成的 ZIP；已有 ZIP 始终拒绝覆盖。程序只读设备，不启动/停止
应用，不改 HOME、ADB、权限或配置，不刷机，也没有恢复写入工具。

ZIP 的路径白名单为 `manifest.json`、`status-before.json`、`status-after.json`、
`settings.json`、`avatar-selection.json`；显式包含 APK 时增加 `apk/installed.apk`。
配置和角色选择文件未曾保存时，相应摘要条目缺席，manifest 的 `sources` 明确记录
`exists=false`，不会编造默认配置。角色摘要只含 current/previous/candidate 的包 UUID，
不导出角色模型、图片、录像、原始人脸坐标、表情系数、事件或自由文本错误。
配置只导出白名单中的运行参数、光学参数、相机绑定和旋转/镜像设置。状态只保留会话、
时间、状态枚举、错误是否存在和有限数值摘要；未知字段被过滤，已知字段类型不合法则失败。
APK 开关导出的程序包包含 APK 原有的程序资源，不读取应用数据或外部模型目录。

manifest 保存设备身份、包版本、已安装 APK 的大小/SHA-256、摘要条目的大小/SHA-256、
每项读取区间和前后元数据。APK 身份在采集前后复核；包含 APK 时，额外核对本地字节哈希。
仅支持单个 `base.apk`，遇到 split APK 明确失败。manifest 自身不提供自引用哈希。

状态采集前后各读一次，每次用设备 `/proc/uptime` 与 `updated_elapsed_ns` 比较，并保守
加入 10 毫秒时钟精度余量；默认状态年龄上界为 15 秒，可用
`--max-status-age-seconds 30` 调整（仅允许 1–60 秒）。持久化配置没有过期时间，其
文件修改时间单独记录，不把长期未改动的配置误称为实时性能数据。
这是**非原子的当前状态快照**：manifest 记录完整采集起止时间、两次会话 ID 是否变化和
读取期间文件元数据变化。状态计数器各自属于输入会话或渲染窗口，不代表这段导出期间
的连续测量；不是完整长时报告或刷机恢复镜像。

限制为每条 ADB 命令 15 秒、整体采集及写 ZIP 120 秒、远端输出总量和 ZIP 条目总量
各 512 MiB；状态 256 KiB、配置 16 KiB、角色选择 4 KiB、APK 256 MiB。输出流受限，
错误仅使用本地固定描述，不回显远端 stdout/stderr。私有文件依赖 `run-as` 权限，
当前调试包可用；不支持 `run-as` 的发布包会明确失败。

包含 APK 的 ZIP 可供人工重新安装同包、同签名 APK。使用 `adb install -r` 重新安装时
保留设备现有应用数据和配置；不要先卸载应用或清除数据。这个 ZIP 的 JSON 是诊断摘要，
不能回写为完整配置备份。APK 回滚范围
仅是应用程序版本，Android 可能拒绝版本降级；不保证跨版本所有配置兼容，也不恢复
系统、HOME、原厂应用启用状态或外部导入角色。保持原有系统恢复日志和 ADB 通道。

主机验证（仅 fake ADB 子进程，不接触设备）：

```powershell
& $benchPython -X utf8 -m unittest discover -s 'E:\tripo\device-lab\tests' -p test_export_runtime_bundle.py -v
```
