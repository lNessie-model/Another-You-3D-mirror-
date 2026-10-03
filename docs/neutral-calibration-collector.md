# 中性采集器：原始帧、连续窗口与会话合同

2026-10-03。本切片新增纯 Java `NeutralCalibrationCollector` 和独立主机回归。它没有接入 Controller、相机/NPU、设置、维护页或角色预览；不改变默认实时路径，也没有进行真人中性、方向或性能验收。完整范围继续按[相机与动作校准计划](camera-controls-plan.md)实施，数学映射合同见[面控校准数学模块](face-control-mapping.md)。

## API 与确认边界

```java
NeutralCalibrationCollector collector = new NeutralCalibrationCollector(false);
NeutralCalibrationCollector.Session session = collector.begin(System.nanoTime());

// 必须传规范输入推理所得的原始 FaceFrame，不能传 Controller 的合成/平滑快照。
NeutralCalibrationCollector.Update update = collector.offer(session, rawFrame, System::nanoTime);
NeutralCalibrationCollector.Update progress = collector.poll(session, System::nanoTime);

// READY 只是待确认草稿；此处不自动更新配置或写盘。
NeutralCalibrationCollector.Result draft = update.result();
if (collector.isCurrentReady(session, draft)) {
    // 在同一 UI/会话所有者中，用户明确确认后才构建一个新的不可变配置。
    FaceControlCalibration next = new FaceControlCalibration(
        nextRevision, mirrorInteraction, draft.neutralPose(), draft.personalBaseline());
    // 调用者负责整体原子发布 next，并拒绝已经退出/改变配置的 UI 所有者。
}

// 离页、HOME、换人、更换输入方向/相机/推理会话时必须撤销。
collector.cancel(session);
```

以上 `nextRevision`、`mirrorInteraction` 和最终配置发布是未来 UI 的职责，示例不是已经接好的用户流程。采集器只读原始输入，故不持有映射配置 revision；任何影响原始坐标的配置变更必须取消旧 Session，重建输入并开启新 Session。仅给新原始帧改 revision 标签，不能使旧采集继续有效。

`Session` 是私有构造的不可变身份令牌。所有状态方法同步，先验证令牌再读取时间/帧；旧令牌的 `offer`、`poll`、`cancel` 仅返回 `STALE_SESSION`，不能延长/缩短新会话截止时间或撤销新结果。`LongSupplier` 重载在锁内采样时间，避免并发调用提前取时后按相反顺序入锁产生假回退；也保留显式 `long nowNs` 重载供确定性测试。帧 receipt、completion、begin、poll 必须同为单调纳秒时钟，不能混用 wall clock 或 elapsedRealtime 与 nanoTime。

`Update` 和 `Result` 不可变。`neutralPose()` 返回防御副本；`PersonalBaseline` 是已有不可变标量对象。采集器不改写 FaceFrame，不持有其数组、图像或整个帧列表，只保留固定长度统计量、两个 quaternion 向量和有限标量。诊断 `detail` 最多 180 字符，reset 计数饱和，不累积事件日志。

`Update` 提供 `status/reason/detail/samples/resets/stableMillis/remainingMillis/result`。`Result` 提供旋转矩阵、可选个人基准、统计样本数、首末统计样本序号与 receipt 时间、相对首样本的最大角距离、七个选定系数的最大范围。序号首末间可能有只检查质量而未计入均值的帧，不能把这个区间标成每帧都参与均值。

## 状态与失败处理

`begin` 开始 `COLLECTING` 并撤销旧结果；`READY`、`CANCELLED`、`TIMED_OUT` 是终态，不会被晚帧变回采集中。`STALE_SESSION` 是旧调用的返回结果，不是当前采集器的新状态。`IDLE` 表示尚未 begin 的内部初态；没有有效令牌的调用仍返回 `STALE_SESSION`。

**READY 保留为冻结的待确认草稿。** 之后的 absent、poll 或超过原采集截止时间不会默默修改这个草稿。只有 `cancel` 或再次 `begin` 撤销它；`isCurrentReady(session, exactDraft)` 对已撤销结果返回 false。UI 必须在退出/HOME 时 cancel，并在显式确认前再次检查自身会话和 exactDraft；如产品要求 READY 后失脸立即失效，由 UI 明确 cancel。采集器不替 UI 跨异步调用保证“检查后永久有效”，也不把 Result 的存在当作保存授权。

帧无效、无脸、过期、眼口不放松或视线不居中时，丢弃整个连续窗口，返回明确原因，不输出部分基准。有效但间隔过大、头部运动或选定系数范围过大时，丢弃旧窗口，以当前有效帧作为新窗口的第一帧，保留该次重置原因。后续有效帧可继续收集；所有重置共用原 20 秒总截止时间。

正常不合格观察返回诊断而非抛出异常。`FaceFrame` 自身仍在构造时拒绝非法长度、非有限值、负序号或 completion 早于 receipt；采集器再检查 `[0,1]`、pose 几何与时间合同。错误的 API 参数（begin 为负时间，当前会话传 null clock）抛 `IllegalArgumentException`。调用者提供的 clock 若自身抛错不会被伪装成采集失败。

## 工程候选阈值

下列值用于先建立可测试合同，**未经真人/具体摄像头标定验证**，不代表医学、眼动精度或“确实中性”的保证。首次界面应展示“保持放松，采集后还需动作检查”，不显示未经验证的准确率。

| 项目 | 当前规则 |
| --- | --- |
| 连续统计时长 | 首末统计样本 receipt 至少 2 秒 |
| 样本数量 | 至少 20 个；固定容量 64；每 50 ms 最多计入一个 |
| 总等待 | begin 后 20 秒；达到截止时间即超时；重置不续期 |
| 新鲜度 | now − received ≤ 250 ms，received/completed 均不得晚于 now |
| 连续性 | 相邻有效新帧 received 间隔 ≤ 250 ms；poll 发现最后新帧超过 250 ms 也重置 |
| 姿态稳定 | 与窗口首统计样本的最短旋转角 ≤ 3°（数值容差 0.00001°） |
| 系数稳定 | 七个基准量各自 min/max 范围 ≤ 0.08（数值容差 1e-7） |
| 睁眼 | 左右 eyeBlink ≤ 0.25 |
| 放松嘴部 | jawOpen ≤ 0.20；mouthFunnel、mouthPucker、左右 mouthSmile ≤ 0.30 |
| 视线居中 | 每个 eyeLook 来源 ≤ 0.50，四个 per-eye 有符号视线绝对值 ≤ 0.35 |

这不是完整的人类情绪/肌肉放松分类器：其它有效 `[0,1]` 来源未作为“中性”拒绝条件，输入仍完整保留。每个新序号都检查上述质量，即使尚未到统计采样间隔；中间发生眨眼或运动不能靠下采样漏掉。头姿阈值是到 anchor 的范围，不是所有样本两两角差 ≤ 3°。

新序号必须严格增加，receipt 和 completion 各自不得回退；相等时间可以被验证，但不会制造额外时间间隔或统计样本。相同/旧序号不会累计；重复回调持续到超过新鲜输入间隔时也会重置。新序号带回退时间明确拒绝，不能刷新“最后有效观察”；未来帧的序号已消耗，不能稍后重复该帧补采。早于 begin 收到的在途结果不能播种新窗口。调用 now 回退会清空窗口并保留上一次有效 now，总截止时间不后移。

当前待机探测 3 FPS 的约 333 ms 间隔不能满足这个合同；后续校准页采集中应在已有硬件所有权下使用正常 17/20 FPS 分析，不应为使待机数据通过而默默放宽连续性。推理延迟超过 250 ms 时会得到过期诊断，而不是使用积压数据声称当前已校准。

## 旋转与个人基准

pose 使用 `FaceControlMapper.rotationPose` 的现有输入容差：有限列主序 mat4，允许现有 renderer 已接受的正列尺度和微小非正交误差；明显剪切、反射、退化明确拒绝。该方法去平移/尺度并正交化。采集器不检查平移稳定度，也无置信度、身份识别或真实相机内参；这些缺口不能由旋转稳定推断为已满足。MatrixData rows/cols/layout 以及 52 来源标签仍须在上游元数据入口验证，单独一个 float 数组不足以辨认行主序。

旋转转为单位 quaternion `q_i=(w,x,y,z)`，以首样本 `q_0` 为符号参考：`dot(q_0,q_i)<0` 时使用 `-q_i`，最后归一化向量和并转回正交矩阵。`q/-q` 同义不会相消，±180° 附近也不会平均成零度。角距离为 `2*acos(clamp(abs(dot(q_0,q_i)),0,1))`。在 3° 小窗口内采用等权 chordal 均值是明确工程选择；不是对 16 个矩阵元素取平均，也不宣称一般大角度全局最优估计。

默认 `new NeutralCalibrationCollector()` 不产生个人基准，仍检查睁眼、闭口和正视。显式构造 `true` 后，对七个统计量取等权均值：左/右 blink、jawOpen、左/右 `out-in`、左/右 `up-down`。先在原始解剖坐标收集，再由 Mapper 扣基准后镜像；不能用已经镜像/校准/平滑的控制量反向采样。

个人基准只属当前会话；换人/新会话必须清除，不持久化为安装设置。数值通过与生成 `PersonalBaseline` 对象不等于真人中性验收，READY 后仍须做左右眨眼、张嘴、视线和转头检查。头基准未来如需持久化，必须另行定义安装作用域和重置条件；本模块不写盘。

## 主机验证与后续接线

```powershell
.\tests\run_neutral_calibration_tests.ps1 -JavaHome 'C:\Program Files\Java\jdk-17'
```

runner 只编译真实 `FaceFrame`、`FaceControlCalibration`、`FaceControlMapper`、采集器和两个测试类，`--release 17`；没有 Android mock、SDK、APK、ADB、相机或 NPU 执行。先建立缺少生产类的编译 RED，再实现；本轮回归目前为 `NeutralCalibrationCollectorTest` 2316 assertions、`NeutralRotationMeanTest` 16 assertions。

另在 `app/build` 的隔离源码副本运行两项故障注入，不改生产类：去掉 quaternion 符号对齐会在 q/-q fixture 以退化 quaternion 失败；去掉新序号的 receipt/completion 回退检查会在明确的时间回退断言失败。两份故障源码均先成功编译再得到测试 RED；真实源码随后重新运行保持上述 GREEN。独立只读审查覆盖会话、时间、旋转四个转换分支和无外部生产调用，未发现可复现 P1/P2。

测试覆盖连续 2 秒与最低样本数、密集调用有界、同帧/等时间重复、失脸/间隔/质量重置、receipt/completion 各自回退、未来帧不得稍后补采、截止精确边界、旧会话 clock 不被调用、取消和旧 READY 撤销、原始数组所有权。旋转使用 q/-q、+179°/−179°、三个 converter 分支、复合不交换旋转局部扰动及独立正交/行列式检查，避免只复写实现公式当 oracle。

下一切片仍需完成相机/NPU 所有权共用、规范图像缓冲、设置草稿/迁移、Controller 仅 drive 分支映射、上游 pose 元数据验证、校准页确认/取消、真人方向与表情验收以及同工作量性能回归。Mapper 的 invalid-pose 异常必须在集成处转为整帧拒绝/可理解错误，不能从 UI tick 的 sample 直接抛出导致页面崩溃。GRACE/WAITING 合成零系数与 identity 不经过个人/头基准再次校正。
