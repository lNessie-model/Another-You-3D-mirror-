# 主程序输入停止记录与失败隔离

2026-10-04，电脑端源码及主机检查完成。未构建新APK、未安装、未操作相机/NPU/GL硬件。是配置Owner接线之前的一项必要修复，完整备份／恢复和全应用硬件停止资格仍未完成。

## 实际行为

`MirrorActivity.RuntimeWorker` 原来的关闭辅助方法只记录 Exception，关闭失败后仍可能重试创建输入资源；异步相机关闭使用独立的 volatile source 与 scheduled 标志，工作线程可能先清空输入并读到“未安排”，随后另一个取消线程才安排关闭。

现在每个实际工作线程持有 `RuntimeInputStop`，保留独立 worker UUID、原始 RuntimeStatusOrder Epoch UUID/序号及 elapsedRealtime 开始／完成时间。实际 SOURCE、REFERENCE、NPU、NORMALIZER、CONVERTER、REFERENCE_CONVERTER 关闭调用都有固定计数；只保留首个异常类型（最多96字符），不把错误消息、Throwable、人脸或资源引用装进不可变记录。

绑定／清空相机与取消线程的关闭 claim 使用同一把短锁。native close 在锁外执行，失败也继续关闭其余资源。异步关闭 claim 在启动 Thread 之前可见；安排失败也保留为失败，后续同步关闭成功不会抹掉。工作线程清空相机后，迟来的取消不能再安排关闭；已有任务由工作线程等待返回，再 join 到真正结束，保留工作线程中断状态。UI只安排关闭，不 join；HAL/native 卡住时不会发布成功或放开输入运行 permit。

一次关闭失败后本工作线程停止重试。最终记录必须先完成全部已安排的关闭并密封；`INPUT_STOP_FAILURES` 随后保留首份失败的不可变记录，最后才放开 `HARDWARE_OWNER`。界面 startIfReady 与工作线程取得 permit 后都检查同一进程门。Activity重建、维护重试、新 Epoch、后来成功的记录均不能解除；需要真正强制停止应用、再打开新进程。这里没有执行强停或重启设备。

每5秒既有状态和独立故障状态增加 `input_stop`：首份进程失败记录、当前Activity最后一个工作线程记录、观察时的真实 Thread.State 及 TERMINATED 布尔值。失败后，迟来的状态写入也被改为 ERROR/face_present=false，不能覆盖失败状态。不同记录的 UUID/Epoch 用于区分旧会话；“最后一个工作线程已结束”不表示当前新工作线程或其他Activity也已停止。

## 记录的严格范围

`close_calls_succeeded` 只表示本记录中关闭调用未报错，且已安排的相机关闭线程结束；没有异步任务时，该项表示无需等待此线程。ASYNC_CAMERA 的 failures 包含线程安排失败，故可能出现 attempts=0、failures=1。

记录不证明 CameraDevice.onClosed 的HAL确认、尚未归还的 Image lease、GL资源／AvatarPoseWorker／其他Activity预览停止，或构造器抛错前的部分资源已释放。后续 [NPU停止切片](npu-pipeline-stop.md) 已在实际pipeline close中等待post executor终止并保留失败，但本输入记录没有嵌入其独立CloseStatus，也没有RKNN驱动destroy返回码的成功证据；不能据此扩大到全硬件资格。零次关闭也不构成硬件资格。因此所有记录固定 `hardware_qualified=false`；不得用作 ConfigurationOwner.HardwareGate 的 `allRuntimeOwnersClosed=true`。

密封发生在工作线程 run 的 finally 中，仍早于该线程返回；单独密封记录不能证明工作线程已经结束。状态中的 TERMINATED 来自稍后观察实际 Thread.State，单独明确区分。静态失败门只持有不可变数据；最后一个 Thread 引用属于Activity实例，最多一份。

本切片不修改设置／角色库、相机朝向、NPU算法、模型、16/20视点、400×640、交织数学或背景缓存默认关闭策略。相机流配置 −38 的实时验收仍待重新插拔；强制重启进程不能保证USB/HAL恢复。

## 检查与结果

先写检查再实现：纯输入停止检查因缺少 RuntimeInputStop 失败；SDK实际链接后，Activity桥接检查因缺少 closeRuntimeInputs 方法失败。随后实现并接入实际 RuntimeWorker。

- `tests/run_runtime_input_stop_tests.ps1`：1478检查通过，包括200次真实 cancel/clear 并发、重复关闭仍在运行、同步关闭进行中、Exception/Error、安排失败／先启动再报错、迟来取消、不可变记录及失败门不能被成功记录清除。
- `tests/run_runtime_input_stop_sdk_tests.ps1`：48检查通过。执行实际Activity关闭顺序与JSON／失败门，实际 startIfReady 和新 RuntimeWorker Thread 在失败门下拒绝、所有输入关闭 attempts=0，并观察到真实线程 TERMINATED。Activity使用宿主生命周期外壳，未执行Android生命周期或native构造器。
- 相关回归通过：产品视点75、GL生命周期逻辑34、视点传播12934、相机VP配置19、持久FBO配置17、背景配置34、三份实际资产69姿态背景键222。对应脚本显式从源码编译 RuntimeInputStop 与 RuntimeStatusOrder，不依赖旧APK产生的新类。

日志及源码哈希放在 `app/build/runtime-input-stop-20261004/host-checks-final.json`。SDK API链接和主机线程证据不等于Android硬件关闭／GPU像素／FPS资格。已有v22 APK保持原SHA；本切片没有新APK，也没有扩大旧APK资格。

## 后续接线

先补相机lease/生命周期确认、NPU post线程、GL线程/上下文及CPU pose worker的真实停止证据；组合主程序、相机校准、Panel与角色预览实际Owner。再接固定后台 ConfigurationOwner、已确认持久设置、角色Store CAS和所有入口；完整事务门形成后才能开放本地备份与恢复UI。候选实现和冻结资料仍在 `app/build/config-integration-v1/HANDOFF.md` 指定位置，不能复制 fake HardwareGate 为通过。
