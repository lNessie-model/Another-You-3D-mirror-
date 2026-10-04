# 相机设置与控制映射接线合同

2026-10-03：本切片完成 `CameraControlSettings`、`MirrorSettings` v3、`InteractionController` 面控映射、`FacePoseMatrix` 和 `FacePostGraph` 元数据入口接线。独立 javac 使用实际 SDK 35、tasks-core/tasks-vision 1.0.0 和 protobuf-javalite 4.26.1 编译通过；主机验证通过。本文不把同期正在实现的校准面板、相机转换、真人方向或性能测试标记为已验收。

完整产品范围仍见[相机与动作校准计划](camera-controls-plan.md)。数学定义见[映射模块](face-control-mapping.md)，采样与确认合同见[中性采集器](neutral-calibration-collector.md)。

## 不可变安装设置与 v3 迁移

```java
new CameraControlSettings(cameraId, fingerprint, width, height,
    rotationDegrees, reflectInput, mirrorInteraction, revision);
CameraControlSettings.DEFAULT;
settings.withInput(id, fingerprint, width, height, rotation, reflect, nextRevision);
settings.withMirror(mirror, nextRevision);
settings.matches(actualId, actualFingerprint, actualWidth, actualHeight);

MirrorSettings current = MirrorSettings.load(context);
MirrorSettings draft = current.withCamera(controls); // 不写盘
MirrorSettings.saveCamera(context, controls);       // 显式 commit；失败抛错
```

所有设置字段是 final。`cameraId` 与 `fingerprint` 必须同时为空或同时非空，长度各不超过 256，不含控制字符；宽高正数，总像素最多 16,777,216，乘法以 long 验证；方向仅 0/90/180/270，revision 非负 long。DEFAULT 为未绑定、640×480、0°、无输入反射、无动作镜像、revision 0。`matches` 必须已绑定且四项完全相同；默认不能被当成已经确认当前设备。with 方法返回新值，不变更原对象；不强制 revision 数值递增，发布者负责版本策略。

指纹由输入层提供，应基于可重复的 CameraCharacteristics 描述；这是描述兼容性，不是可靠的 USB 唯一序列号。相同描述的两台设备可能匹配，现场仍需文字方向和真人动作确认。设置对象不选择或打开相机。运行入口在实际身份/尺寸不匹配时决定使用默认并提示，不能拿旧绑定假装匹配。

v3 的存储键为 `camera_id/fingerprint/width/height/rotation_degrees/reflect_input/mirror_interaction/revision`（各键均带 `camera_` 前缀）。revision 使用 SharedPreferences long，不转为字符串或 int。只保存安装和互动模式，**没有 neutral pose、个人眼口/视线基准或人脸图像字段**。

版本 0/1 保留原运行 profile 并使用默认光学/相机；版本 2 的原有 pitch/tan/phase/units/order/reverse/origin 全部读取并保留，新增 camera 为默认；版本 3 同时读取。关键修正是光学读取条件使用 `version >= 2`，不再与当前 schema 号相等才读取。`withProfile`、`withPanel` 保留 camera；`withCamera` 保留原 profile 与 panel。

各已知配置分组的非法存储类型/范围有 warning，并只让该分组临时使用默认；读取不写盘，显式保存才覆盖。版本损坏、负版本、未来版本标为不可写，三个 with/save 路径均拒绝覆盖。所有保存仍使用现有的显式 `commit` 结果，不因已修改内存草稿就报告成功；后台保存与 UI 生命周期的提交边界由调用方处理。

## Controller：只映射有效驱动，失败整帧归中

```java
interaction.setCalibration(new FaceControlCalibration(revision, mirror, neutralPoseOrNull, personalOrNull));
FaceControlCalibration current = interaction.calibration();
InteractionController.Snapshot output = interaction.sample(SystemClock::elapsedRealtimeNanos);
output.calibrationRevision();
output.calibrationError();
output.calibrationRejectedFrames();
```

setter/getter 与 `accept/sample/reset` 使用同一个 Controller 锁，配置作为整体不可变值发布。**每次 setCalibration 都清映射缓存**，包括相同 revision 或同一对象；source 和当前平滑值保留，重复 setter 不会把角色动作瞬间清零。缓存身份使用实际 FaceFrame 与实际配置对象，不能用 revision 单独判断相等。

每个时间合法的新 raw present 帧先验证完整 `[0,1]` 系数和 pose 几何；姿态验证沿用 Mapper/renderer 既有容差。这一步不减基准、不镜像，用于防止 ACQUIRING 从无效 pose 累计稳定有脸。未来或回退时间仍沿用既有丢弃规则。新增 `observed` 保存输入时间上界，即使数值不合格也不能让随后更早的结果重新进入。

数值/pose 不合格不会替换最近接受的 `latest`、不会刷新最近有效脸的 receipt 或 age；标记 invalid，INTERACTIVE 转既有 GRACE，ACQUIRING 回 WAITING，并按原 150 ms 时间常数归中。每个新拒绝观察增加一次饱和 long 计数，错误文本最多 180 字符，不在 UI 每 tick 重复计数。下一个时间合法且数值有效的新帧可以按既有恢复规则继续；present 有效时清当前错误文本，计数保留到 input reset。

只有 `drive = INTERACTIVE && fresh-valid-face` 分支执行 `FaceControlMapper.mapActive`，对同一帧/同一配置只算一次。随后每次 sample 仍执行原有平滑，所以缓存不会降低动画更新频率。52 路和 pose 同时使用同一配置；兼容四路渲染权重从映射后的完整 52 路计算。WAITING、ACQUIRING、GRACE、ERROR 的目标保持零系数与 identity，不再次减 R0 或个人基准。

Mapper 的 `IllegalArgumentException` 在 Controller 内受控转为拒绝/归中，不能穿透到 UI tick。正常输入的默认配置保留原 pose 的逐位副本及原有 52 路平滑轨迹；相比旧行为，非法原始系数/姿态现明确拒绝，不再让非法数值被 clamp 后伪装成新鲜有效脸。

`reset()` 清 source、时间、缓存与诊断，**保留已安装配置**。调用方必须在 HOME、换人、新输入 generation、输入方向变更时明确清除 session-only head/personal；不能因为 reset 自带中性权重就以为个人配置也已失效。中性确认需同时匹配采集 Session、当前 input token 和 UI owner，并在同一 UI/同步发布边界设置配置。

## MatrixData：验证布局后才丢弃元数据

`FacePostGraph` 在读取 geometry 时改为 `FacePoseMatrix.copyValidated(matrix)`，随后仍使用现有完整输出事务校验，52/478/pose 不部分发布。检查 rows=4、cols=4、packed count=16、值全部 finite，并保持原 packed float 顺序及位值，不悄悄转置或修正。

官方 [MatrixData proto](https://raw.githubusercontent.com/google-ai-edge/mediapipe/master/mediapipe/framework/formats/matrix_data.proto) 使用 proto2，layout 字段号 4，缺省是 COLUMN_MAJOR；因此未显式携带 layout 的正常输出必须接受。实际 tasks-core 1.0.0 的生成类提供 `hasLayout/getLayout`，但未知枚举在 proto2 unknown fields 中保留，而 getter 返回 COLUMN_MAJOR 默认。实际测试向正常消息追加 layout=99，证实 `hasLayout=false/getLayout=COLUMN_MAJOR`；只检查 getter 会误接受。

本实现先检查 getter 排除显式 ROW_MAJOR，再用 protobuf 自带 CodedInputStream 对该 MatrixData 的序列化内容扫描字段 4：必须 varint 且值为 0，未知值或错误 wire type 明确拒绝。用 `skipField` 跳过其它普通字段，不手写 varint/长度解码；序列化消息上限 4096 字节。此版本 `skipField(group)` 不执行 recursionLimit，真实嵌套 group fixture 首次使边界测试失败；MatrixData 未定义 group，因此最终实现明确拒绝 START/END_GROUP，不递归跳过。普通未知扩展字段仍可跳过。

元数据失败沿用 FacePostGraph 的 `outputError` 与 process 异常，交运行 worker 现有错误/恢复逻辑处理。输入元数据验证不保证 pose 代表真实人脸，也不替代列主序矩阵的几何检查或真人方向验收。官方链接是当前上游，运行行为以测试所用 1.0.0/4.26.1 二进制为证据。

## 回归与尚待设备验证

```powershell
.\tests\run_camera_controls_tests.ps1 -JavaHome 'C:\Program Files\Java\jdk-17'
.\tests\run_avatar_rig_tests.ps1 -JavaHome 'C:\Program Files\Java\jdk-17'
.\scripts\test_java.ps1 -JavaHome 'C:\Program Files\Java\jdk-17'
```

新增 runner 编译生产设置、Controller、真实 FacePostGraph 与 MatrixData helper；不以 protobuf mock 替换真实类，不执行 native graph、APK 或 ADB。新增文件/接口的初始编译测试为 RED，随后真实边界测试暴露未知 group 问题并在修复后 GREEN。

当前结果：camera settings 55，原 settings 17，Controller calibration 2212，原 Controller 149，实际 MatrixData 33 checks。额外通过既有 rig/schema/Controller runner，以及 renderer、pacing/watchdog、YUV、屏幕 CPU oracle 和配置回归。Controller 新测试包含独立固定的旧有效输入平滑轨迹逐位对照，不能仅由“两个都用了新实现”的比较推断默认行为不变。

面板生命周期、确认/取消事务、每个推理新帧都进入 Collector、旋转后图像字节、USB 断开/恢复、实际 52 路/头姿动作方向和新增转换成本仍须由主界面整合与设备验证。面板不得从 UI 的最新帧槽漏过中间不合格推理帧而声称连续中性；输入 token 异步更换后旧 READY 不能被确认到新 session。前述主机通过不等同这些流程已经验收。
