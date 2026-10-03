# v20 本地交付与使用

当前设备为 Android 11／RK3566，固定 serial `6L32552009566714`，包 `com.mirror.bench`。普通打开魔镜即使用已保存的 **16 视点、每视点 400×640**，默认四组 persistent FBO 和混合 CPU/NPU 表情后端。维护页改运行参数后须明确保存；取消不保存。USB 安装方向已由用户确认；新模型、骨骼校正和背景目标另行处理。

本地交付 [v20-runtime-local-bundle.zip](E:/tripo/output/mirror-program/20261003/v20-runtime-local-bundle.zip) 为 39,877,785 B，SHA `37670332cad7f9d82b9009754ebc70d0b8e6cce23530b767158527abe0f43d2c`。精确六项：manifest、前后状态摘要、设置摘要、角色选择 UUID 摘要及 `apk/installed.apk`。全部五项 manifest size/SHA 已逐字节核同；manifest 自身的外部 SHA 见 [独立审计](E:/tripo/device-lab/app/build/v20-delivery-audit-v1.json)。内含 APK SHA `21ee8a3d11e94c0ac399740364f4ba235ceea8b54d177d9bfcda44b56b01b3da`，与冻结 v20 程序包完全相同。

导出耗时 4.281 秒；session `3bbe8d67-409b-4a2c-ab84-d6947613e0fc` 未变化。两次摘要字节相同、序号均为 27，状态 WAITING／现场无脸；状态年龄上界分别 2.368／5.268 秒，均在 15 秒门内。384 个已完成分析帧、3127 个 USB 图像是该输入会话累计值，**不是导出期间的新增量或性能验收**。

这是**非原子诊断摘要＋完整 APK**，不能回写成完整配置备份、恢复已删除的外部角色，或恢复 Android／HOME／ADB。设置摘要记录 schema4、16／400×640／分析目标 17 FPS 与光学、相机参数；角色 current=null 表示 APK 内置角色，previous 只有 UUID，没有该导入角色的模型。配置备份恢复 UI 和全部入口／真实 camera-GL 事务资格仍未接线。

外层摘要没有原始私有文件、设备采集图片、录像、运行时脸部坐标／表情数组或自由文本错误。**APK 原有程序资源完整保留**：其中仍有随包 `portrait.jpg`、模型和 expression-validation 数值 fixtures；不能称整个 ZIP 任意位置都没有图片／人脸测试资源。导出未额外读取设备录像、相册或角色模型目录，也没有上传。

需要重新安装时，可将 ZIP 解到一个新目录，使用其中 `apk/installed.apk`；同包、同签名 `adb install -r -t` 保留设备现有应用数据。不要先卸载、清数据或更改系统服务。保留 ADB、HOME 和原系统回滚记录；APK 回装只改变程序，Android 可拒绝版本降级，也不保证任意版本配置兼容。

当前 debug v20 可临时选择 CPU52／legacy FBO，不保存该覆盖；以下仅为人工操作说明，本审计没有执行：

```powershell
$mirrorAdb='C:\Users\lNessie\platform-tools-latest-windows\platform-tools\adb.exe'
& $mirrorAdb -s 6L32552009566714 shell am force-stop com.mirror.bench
& $mirrorAdb -s 6L32552009566714 shell am start -n com.mirror.bench/.MirrorActivity --ez test_npu_blendshapes false --ez test_persistent_fbos false
```

也保留原 [v19 APK](E:/tripo/output/mirror-program/20261003/400x640/AvatarRuntime-v19-npu-expression.apk)，SHA `ad8f7e21…35448a`：用同包安装覆盖后，普通无 test extras 启动默认就是 CPU52／legacy FBO，仍读取原保存的 16／400×640 配置，无需卸载主包。显式 debug false 同样可用。恢复 v20 默认时重新安装 v20，并 force-stop 后无 extras 打开；不要拿前一个 Activity 的临时覆盖当成已保存配置。此次没有实机执行 APK 回退。

[v20 普通启动与 90 秒资格](E:/tripo/device-lab/docs/runtime-v20-default-npu-validation.md) 保持原冻结结论；原 v19 30 分钟资格只属于 v19。权限拒绝仍是“设备未能施加拒绝”，两小时按用户要求跳过，均不能改写为通过。导出／快照边界详见 [导出说明](E:/tripo/device-lab/docs/runtime-bundle-export.md)，[本次审计器](E:/tripo/device-lab/app/build/audit_v20_delivery.py) 支持只读 `audit()` 重算。
