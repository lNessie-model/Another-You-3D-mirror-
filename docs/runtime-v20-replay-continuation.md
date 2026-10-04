# v20 临时离线回放继续运行

2026-10-03，RK3566／Android 11。用户当前不便重新插拔USB，root将现有v20临时切到 `runtime_input=replay`，继续本地预录制人脸的推理与角色渲染。普通USB摄像头恢复仍未通过；本记录不修改此前交付、存储或性能验收结论。

## 摄像头边界

[当前相机错误证据](E:/tripo/device-lab/app/build/v20-camera-stream-error-v1/audit.json) 记录camera状态ERROR、renderer ready且无render fault、相机权限granted。对应 [错误日志](E:/tripo/device-lab/app/build/v20-camera-stream-error-v1/camera-errors.txt) 为Camera 0 `endConfigure` 流配置失败：`Function not implemented (-38)`。video40节点及USB路径存在不代表流可以成功配置，也不足以确定唯一驱动或硬件根因。

[配置检查窗口-v1](E:/tripo/output/mirror-program/20261003/config-device-check-v2-window-v1/commands.json) 的首个相机状态已报相同错误，发生在该窗口启动隔离存储v2之前；重开后新的主Activity又报相同流配置错误。因此不能将这次摄像头故障归因于Store缓存或配置事务。该窗口仍为 `ordinary_restored=false, passed=false`，隔离存储通过是另一个有限结论。

## 回放核验

[首次回放记录](E:/tripo/device-lab/app/build/v20-replay-restored-v1/audit.json) 保留 `completed=true, passed=false, AssertionError`。实际21条 [命令记录](E:/tripo/device-lab/app/build/v20-replay-restored-v1/commands.json) 只有 `force-stop` 后以 `--es runtime_input replay` 启动；没有NPU、FBO或view调试覆盖，也没有偏好写入。最后两次cat（零起始索引19、20）返回完全相同的status字节：sequence=3、capture_elapsed_ns=200134963259452、completed_frames=77。root首次工具使用固定2秒等待，但commands没有host时钟字段，无法从磁盘记录独立重建这两秒。重复状态与状态更新节奏不匹配相容；不将其失败抹去，也不将它断言为应用推理停滞。

随后root的 [只读核验helper](E:/tripo/device-lab/app/build/verify_replay_runtime.py) 不重启应用，按0.5秒重读、在15秒等待窗口内寻找相同Activity／session的新序号、设备capture时钟及完成／人脸计数增长。[核验-v2](E:/tripo/device-lab/app/build/v20-replay-verified-v2/audit.json) 为 `completed=true, passed=true, camera_live_recovered=false`，13条只读命令全部返回0且stderr为空。前九次状态读取仍为sequence109，第十次取得sequence110；两份保存状态分别与对应cat原始stdout逐字节一致。

| 同一回放会话 | 第一份状态 | 第二份状态 |
| --- | ---: | ---: |
| status_sequence | 109 | 110 |
| status_capture_elapsed_ns | 200669387313689 | 200674438485613 |
| completed_frames | 9050 | 9135 |
| face_frames | 9035 | 9120 |
| state／face_present | INTERACTIVE／true | INTERACTIVE／true |

Activity为 `56fee722-1aaa-447b-915b-8295ca72fc3c`，session为 `b46df757-7056-4764-a1dc-0e56488d68c0`。两份状态的device capture相隔5.051171924秒，updated时钟也递增；均无运行、处理或渲染错误，478个landmarks／52个blendshapes，表达后段 `normalized_rknn_experimental` 且failed_frames=0，persistent FBO实际4个、GL frame ready，实际16×400×640。这是有进度的两份回放快照；没有设备当前uptime读数来给出绝对状态年龄上下界，也没有SurfaceFlinger呈现时间戳，不能当作FPS、联合USB负载、摄像头修复或长时耐久通过。

source明确为 `memory-mapped NV21 recording; no USB capture or video decoder`；主程序 [源选择](E:/tripo/device-lab/app/src/main/java/com/mirror/bench/MirrorActivity.java:590) 和 [ReplayFrameSource](E:/tripo/device-lab/app/src/main/java/com/mirror/bench/ReplayFrameSource.java:11) 与此一致。离线回放仍做图像转换及模型推理，但不含USB采集或视频解码负载，不能将本会话与此前 `camera_replay` 的USB采集＋回放推理性能直接互换。

核验后APK仍为 `21ee8a3d11e94c0ac399740364f4ba235ceea8b54d177d9bfcda44b56b01b3da`，偏好仍为 `43bd19b1d31c03648a81fb7e18ecd778c47f4d34b3879e3041f2e0f815056b37`。回放选择只在当前Intent／会话，保存配置不变；之后普通无extras启动仍选择camera，须另验USB成功采集。没有改动生产源码、系统、驱动或冻结交付包，也没有重新启动用户已跳过的两小时测试。

事实文件SHA256：首次audit `773dc497d017e4f6c2af3253131933774821ceb600f9a58d9ae961ce1f956705`、首次commands `8894805ce6e168417098f4e68e85bfc874443e4d945604a1fc4f369db988c11d`；v2 audit `4eaba66616cc5f67bbff1f90aef3a2db0ef6e536f50f2baa462ac27269a8d7c4`、commands `9c4530a47ba056ac3af614bb80994375958aa4ec54f8967f98a6fd0858dfd734`；[第一份状态](E:/tripo/device-lab/app/build/v20-replay-verified-v2/status-first.json) `8d28d37eee8bad6a548c7f8ab4ad8044b1b68937d0890b86eb56f1952350d57c`、[第二份状态](E:/tripo/device-lab/app/build/v20-replay-verified-v2/status-second.json) `398912dc81eaa62919d77889b55f061d514f49c19b995595d2e3428b55c23c18`。本文作者仅作host只读核对和新增文档，未执行helper或设备命令。
