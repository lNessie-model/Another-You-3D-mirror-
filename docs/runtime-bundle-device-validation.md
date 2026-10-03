# 本地状态包：v16 只读真机验证

2026-10-03，设备 `6L32552009566714`，Android 11。使用已独立审查的
`scripts/export_runtime_bundle.py`，SHA256
`5a9abaa7476cf9d06f4646e4eba955444300b0497df5d503bdad4e8b715fb753`。
13 项 fake-ADB 测试通过，独立审查另解析了 v16 三次性能原始记录中的 96 个真实状态。

测试结束后先清除已知旧状态文件，再启动普通实时 USB 入口。新会话
`ae4bebec-c186-41dd-bb63-f58bc744b3a7` 接收 131 帧、采集 24.4488 FPS、
状态年龄 0.145 秒，输入/采集/渲染错误为空，两个渲染候选均未启用。
该恢复证据位于 `E:/tripo/output/mirror-program/20261003/v16-restored-before-export-status.json`。
现场有人脸与否不影响此次只读导出检查；恢复本身不是完整有脸性能验收。

## 实际导出与字节核对

命令为 `export_runtime_bundle.py --out <以下新ZIP> --include-apk`，实际 exit 0。
工具没有启动/停止应用，也没有写设备文件或修改配置。

- ZIP：`E:/tripo/output/mirror-program/20261003/v16-runtime-local-bundle.zip`
- 大小：34,697,315 bytes。
- SHA256：`e2806950092ddf01b0787af184781ff512337ebb4e9f1d33a16f5e309778cbbb`。
- APK：34,687,516 bytes，版本代码 1 / 名称 0.1.0，SHA256
  `728bf3ff354f5cd4ebc499c3e60ffd05afe3d694ac8687a66108e6fa378663e5`，
  与已安装及归档 v16 APK 一致。

离线审计逐条验证 ZIP 无重复名、CRC、条目大小及 SHA256；APK 字节哈希一致。
实际条目为 `manifest.json`、`status-before.json`、`status-after.json`、
`avatar-selection.json`、`apk/installed.apk`。设备没有保存 preferences 文件，
manifest 明确记录 `settings.exists=false`，没有编造或导出默认设置。

两次状态都属于上述恢复后的普通 camera 会话，USB 接收计数大于 30、错误标志为 false。
读取前后元数据均稳定，状态年龄上界分别为 4.0215 / 1.1571 秒，未超过 15 秒门。
采集历时 3.828 秒，`atomic=false`、`session_changed=false`。两状态摘要和角色选择
没有原始人脸点、表情数组、自由文本错误、事件或影像。APK 包含原有程序资产，
导出没有读取设备录像或外部角色模型目录。

再次向同一 ZIP 导出明确 exit 1 / “Destination already exists”，原 ZIP SHA256 保持相同。
该操作在创建目标的保护处退出；未重新采集设备数据。

## 可复核范围

审计脚本为 `E:/tripo/output/mirror-program/20261003/audit_v16_runtime_bundle.py`；
完整核对结果为同目录 `v16-runtime-local-bundle-audit.json`，包含 APK/条目哈希、
完整读取元数据、新鲜度和非原子采集范围。

这完成 CLI 状态导出与保留应用安装包的首个交付切片。JSON 是白名单诊断摘要，
不能回写为完整配置备份；此次未执行 APK 重装/降级或验证刷机恢复。
应用内导出入口、完整配置恢复、外部导入角色备份及实际回滚验收仍需后续实现。
使用说明与限制见 [runtime-bundle-export.md](runtime-bundle-export.md)。
