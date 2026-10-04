# 魔镜性能版本交接清单

2026-10-03，RK3566／Android 11，固定设备 `6L32552009566714`、主包 `com.mirror.bench`。当前交接已验证的v20 debug APK，release未实测；整个目标尚未完成，不预告尚未构建或验收的v21。

## 可用文件

- [v20 APK](E:/tripo/output/mirror-program/20261003/400x640/AvatarRuntime-v20-production-npu.apk)：SHA `21ee8a3d11e94c0ac399740364f4ba235ceea8b54d177d9bfcda44b56b01b3da`。已保存16视点、每视点400×640、分析目标17 FPS；普通入口默认混合CPU/NPU表情后段与四组persistent FBO。
- [本地运行包](E:/tripo/output/mirror-program/20261003/v20-runtime-local-bundle.zip)：SHA `37670332cad7f9d82b9009754ebc70d0b8e6cce23530b767158527abe0f43d2c`。包含APK和导出时的非原子白名单摘要，不能作为完整配置／角色恢复或刷机镜像。[使用及回退说明](E:/tripo/device-lab/docs/runtime-v20-delivery.md) 保留ADB、HOME和同包APK回退；本次未实际执行回退。
- [v20源ZIP](E:/tripo/output/mirror-program/20261003/400x640/v20-source-v1.zip)：SHA `ce5b30c6d6dd8ae3492f59880be0f43207f17b95c523b73950a7f953e3940682`；[逐项冻结清单](E:/tripo/output/mirror-program/20261003/400x640/v20-source-v1-freeze.json)。后续候选不能冒充这份已验APK。

## 已通过与保留范围

- [v19真实30分钟联合负载](E:/tripo/device-lab/docs/runtime-v19-npu-endurance-pass.md)：USB实际采集＋预录人脸推理、完整478／52、GLB/UI、混合CPU/NPU及16×400×640共同运行。52个连续互动区间全部超过30 FPS，整体30.568389、最低30.353008；未知尾部3.968319秒单列。资格属于冻结v19 APK，不能改名为v20长测。[原始报告](E:/tripo/output/mirror-program/20261003/400x640/avatar-v19-persistent16-400640-npu-30min.json) 保留。
- [v20使用与90秒短测](E:/tripo/device-lab/docs/runtime-v20-default-npu-validation.md)：两次普通启动与完整90秒联合短测已验，互动呈现30.663533 FPS、三段全部超过30；未知尾部4.947895秒。短测只传camera_replay输入，没有NPU／FBO／视点覆盖。权限拒绝分支未验成，现场真实脸、全部UI、光学及新的v20长测不在通过范围。
- 两小时按用户要求 [SKIPPED_BY_USER](E:/tripo/output/mirror-program/20261003/v19-endurance-user-skip-v1/user-skip.json)，`completed=false, passed=false`；没有将跳过写成通过或重启两小时任务。

## 当前运行与未完成项

用户当前不便插拔USB。摄像头流配置仍为 `CAMERA_ERROR / endConfigure / -38`，错误在配置存储v2窗口之前已出现，USB恢复待独立确认。目前以临时Intent运行本地NV21回放：[继续运行证据](E:/tripo/device-lab/docs/runtime-v20-replay-continuation.md) 的两份状态同Activity／session且计数增长，478／52、NPU、四FBO和16×400×640正常。回放不含USB采集或视频解码负载，不是摄像头修复或新的FPS验收；APK及保存偏好不变。

[隔离配置存储短检](E:/tripo/device-lab/docs/config-storage-device-validation.md) 已通过原期限内的四正常模式与八进程中断点，但普通USB恢复窗口仍为false。codec、事务、Store、owner和备份目录候选尚未完成生产所有入口、真实资源资格及维护页全配置备份／恢复UI接线；当前不开放半成品恢复入口。角色缓存等后续候选须另有源冻结、构建与必要短检，不能从主机通过推断已经部署。

模型阶段随后继续：Tripo生成须提供可查看的GLB模型预览，并提供rig／骨骼及面部动作预览供确认。body rig只能证明身体骨骼和动作能力，不能代替52个脸部blendshape及名称／映射、表情驱动和实际渲染检查。骨骼校正与背景目标仍待完成；本清单未发起付费生成。
