# 面控校准数学模块与接入合同

2026-10-03：新增 `FaceControlCalibration`、`FaceControlMapper` 及纯 Java 测试。此切片尚未接入 Controller、设置或 UI，没有运行 APK/ADB，也没有真人方向、中性采集或性能实测。完整后续范围仍按[相机与动作校准计划](camera-controls-plan.md)执行：图像安装方向、原始样本采集器、硬件所有权、设置迁移/草稿、真人左右验收及联合性能，均不能由本模块代替。

## 1. 原子配置与 API

```java
FaceControlCalibration config = new FaceControlCalibration(
    revision, mirrorInteraction, neutralColumnMajorPoseOrNull, sessionBaselineOrNull);
FaceControlCalibration defaults = FaceControlCalibration.defaults();

FaceControlMapper.Mapped output = FaceControlMapper.mapActive(raw52, rawColumnMajorPose, config);
long revisionUsed = output.revision();
float[] mapped52 = output.blendshapes52();
float[] mappedPose = output.pose();

// 非 drive 状态：已经位于映射后坐标，绝不再次减中性基准。
FaceControlMapper.Mapped neutral = FaceControlMapper.neutral(config);

// 后续采集器可用：验证输入并提取旋转，不修改输入。
float[] referenceRotation = FaceControlMapper.rotationPose(rawColumnMajorPose);
```

配置中的 revision 必须非负；默认 revision=0、无头基准、非镜像、个人偏置关闭。输入头基准及所有输出数组均复制；配置和 `Mapped` 没有修改接口，getter 不泄露内部数组。`PersonalBaseline` 只有私有 final 标量，可安全作为不可变值共享。调用者以一个 `AtomicReference<FaceControlCalibration>` 或等价同步机制发布完整配置，每次映射只获取一次，不能分别读取头姿和表情设置。`Mapped.revision()` 标明本次同时使用的配置版本。

输入系数必须恰好 52 项且有限、位于 `[0,1]`，标签顺序由上游 `BlendshapeSchema`/`FacePostGraph` 负责验证；Mapper 不根据长度猜测其它 schema。姿态必须是有限的 16 项列主序矩阵。无效输入抛 `IllegalArgumentException`，没有部分发布、内部可变输出缓存或静默回退。调用者要记录/拒绝该帧，不能把校准失败标成成功。

## 2. 姿态验证、默认兼容性及旋转顺序

默认头姿行为刻意保留当前 renderer 的有效输入范围：底行 `pose[3/7/11]` 接近零、`pose[15]` 接近一，容差均为 0.001；三列长度各在 `[1e-6,1e6]`；列归一化后两两点积绝对值不超过 0.05，行列式不低于 0.9。退化、非有限、过大/过小尺度、明显剪切和反射明确拒绝。正的非统一列尺度沿用现有 renderer 行为，可去除；两个轴同时翻号可以是合法的 180° 旋转，不能误当反射。不能仅由纯旋转的 16 个数识别行/列主序，实际 `MatrixData` 的 rows/cols/layout 必须在上游元数据层另行检查。

无头基准且不镜像时，输出是**已验证原姿态的逐位副本**，包括平移和尺度；让现有 renderer 按原路径提取角度，避免本次新类改变默认数值。默认 52 项同样逐位保留，包括合法负零。仅启用个人表情偏置也不改变这个头姿默认行为。

启用头基准或镜像，以及调用 `rotationPose` 时，先去平移和正列尺度，再做稳定的 Gram–Schmidt：保留第一单位轴，从第二轴去掉第一轴分量并归一化，以二者叉积得到第三轴。该步骤修复当前窗口允许的微小非正交误差；不会接收窗口之外的剪切。输出为无平移、底行 `[0,0,0,1]` 的右手旋转矩阵。头基准在构造配置时按同样方法处理。

采用列向量和中性头部局部坐标增量：

```text
Rraw = R0 · Δ
Δ = transpose(R0) · Rraw
S = diag(-1, 1, 1)
Rout = mirror ? S · Δ · S : Δ
```

没有头基准时省略 `transpose(R0)`。不减 Euler、不使用右乘的 `Rraw·transpose(R0)`，也不在模型 root 加负尺度。镜像保留纯 pitch，反转纯 yaw/roll；复合旋转按矩阵定义处理。Mapper 不做额外滤波、Euler 提取或角度限幅，现有 renderer 继续负责这些步骤及 pitch/yaw/roll 的 ±45°/±65°/±40° 限幅。

当前资产坐标和规范推理坐标继续采用计划中的同一基底；本模块没有偷偷增加未验证的 C 基变换。相机安装旋转/输入反射在像素规范化层处理，屏幕 phase/reverseViews 不参与面控镜像。

## 3. 明确的 52 项左右置换

Mapper 内置完整 52 项整数置换，并通过 `mirrorPermutation()` 返回副本。采用计划中逐项列出的 20 对：browDown、browOuterUp、cheekSquint、eyeBlink、eyeLookDown、eyeLookIn、eyeLookOut、eyeLookUp、eyeSquint、eyeWide、jawLeft/Right、mouthDimple、mouthFrown、mouthLeft/Right、mouthLowerDown、mouthPress、mouthSmile、mouthStretch、mouthUpperUp、noseSneer。其余 12 项各留原位，包括 `_neutral`。

`eyeLookIn/Out` 仅交换眼睛，不能把 In 与 Out 对调。当前 rig 的左眼 yaw 使用 `outLeft-inLeft`，右眼使用 `inRight-outRight`，交换眼睛后世界 yaw 自然反号；上下方向保持。置换是一一对应且自反的，完整执行两次会逐位恢复原 52 项。这里的两次还原只指镜像操作，不是把带个人偏置的完整校准反复执行两次。

## 4. 个人偏置：显式、仅当前会话

```java
new FaceControlCalibration.PersonalBaseline(
    blinkLeft, blinkRight, jawOpen,
    leftOut, rightOut, leftUp, rightUp);
```

这是后续采集器已经接受的数值结果入口，不是采集器本身。它只验证数学安全，不知道样本是否新鲜、稳定、睁眼闭口，不能用任意一帧创建对象就宣称中性采集完成。**七个参数只属当前会话；换人/开始新会话必须清除，不得作为安装设置持久保存。** 默认传 null 完全关闭，不自动学习、不自适应漂移。

单向输入只校正左右 blink（9/10）和 jawOpen（25）：

```text
out = clamp((w-b)/(1-b), 0, 1)
```

基准要求 `[0,0.5]`，使最大增益不超过 2；这个 0.5 是数值安全界，不是已验证的真人中性阈值。`w=b` 输出零、`w=1` 仍输出一。mouthClose 和所有其它嘴部系数保留，因此 jawOpen×mouthClose 的组合不会被“闭嘴即清零下颌”破坏。它不承诺消除所有嘴部形态偏差；更多个人通道需另定合同及资产/真人验证。

视线在各解剖眼内部定义两个有符号差值：水平 `d=Out-In`，垂直 `d=Up-Down`，分别输入四个有限基准，范围 `[-0.5,0.5]`。使用两侧端点保持的线性映射：

```text
d' = (d-b)/(1-b),  d >= b
d' = (d-b)/(1+b),  d < b
d' = clamp(d', -1, 1)
```

因此个人正视方向回零，正负最大视线仍可达 ±1，原 rig 的最大角度不被偷偷缩小。为保留同时激活的两源值，重建时设 `c=min(positive,negative,1-abs(d'))`，输出 `positive'=c+max(d',0)`、`negative'=c+max(-d',0)`。只有超出 `[0,1]` 容纳能力时才减少共同分量；基准为零时直接逐位保留该对原始值，不走重建。这里归零的是**视线差值**；对于把 In/Out 当两个 morph 的导入资产，共同分量仍可能造成形变，不能称其眼部几何完全中性。

顺序固定为规范解剖坐标中扣除各自个人基准，然后进行 20 对镜像置换。不能先交换眼睛、却继续使用另一侧的偏置。

## 5. Controller 接线约束与后续工作

- `FaceFrame` 继续保存未经个人校正/动作镜像的推理结果，供原始诊断和后续中性采样使用。
- Controller 仅在 `drive=true` 的有效 raw 帧分支调用 `mapActive`；再将映射后完整 52 项送入现有平滑，并从同一数组产生旧四维诊断权重。pose 和系数必须来自同一个 `Mapped`。
- 当前 `FaceFrame` 只检查姿态长度/有限值，renderer 对反射、剪切等无效姿态会静默使用中性角度；Mapper 则明确抛异常。因此接线时必须在 Controller 的受控边界捕获，实施并记录“拒绝整帧并回中性”或明确 ERROR 的产品策略，不能让异常逃出 UI 的 `sample()` 调用造成 Activity 崩溃，也不能未经说明保留一半校准结果。这一失效策略尚未由本切片修改现有控制器。
- ACQUIRING、GRACE、WAITING、ERROR 或过期结果走零系数/identity 的现有衰减目标，或使用 `neutral(config)`。禁止对合成 identity 再执行 `R0ᵀ`，否则丢脸会倒转到反向中性角度。
- 配置变化由调用者决定 revision/重置行为。安装方向改变必须新建输入会话、清空 ROI/post 平滑/pending 并拒绝旧结果；本模块不负责跨会话硬件所有权，也不隐藏 UI/线程。
- 头部基准的 quaternion 稳定采样、q/-q 处理、重复序号/新鲜度/晃动拒绝、样本数量和超时仍待独立采集器实现。个人偏置的真人有效阈值、校准预览/确认、设置 v2 光学配置保留及录像方向隔离亦未完成。

## 6. 主机验证与局限

```powershell
.\tests\run_face_control_mapping_tests.ps1 -JavaHome 'C:\Program Files\Java\jdk-17'
```

独立 Java 17 runner 无 Android SDK/JSON/native 依赖。测试先在不存在数学模块时失败，再验证实现；还用隔离的错误乘法/禁用偏置副本检查关键断言能捕获退化。矩阵参考使用手算四分之一圈与独立的 Rz·Ry·Rx 展开，包含错误乘法确实不同的反例，以及 100 个确定性复合旋转。完整 52 one-hot 置换、自反性、非交换基准与镜像组合、所有权、无效系数/pose/尺度/剪切/反射、默认兼容窗口和非 drive 中性均有覆盖。

个人偏置测试覆盖独立双眼、完整闭眼/张口端点、jawOpen×mouthClose、16 个视线方向/基准组合的每组 101 个输入点、接近奇异基准拒绝、高共同分量重建、基准前后镜像顺序及原子 revision 并发发布。主机数学通过不证明 MediaPipe 在真人左右动作上的方向、校准有效性、最终美术或实机性能；仍需按完整计划接线并验收。
