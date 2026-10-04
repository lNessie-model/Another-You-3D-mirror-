# NPU后处理执行器停止与关闭错误保留

2026-10-04，电脑端源码切片。实际 `NpuFacePipeline` 关闭顺序已修复，主机检查通过；没有新APK、设备操作、模型生成或扣费。未宣称 Android/RKNN/MediaPipe native 关闭或全应用硬件停止通过。

## 修复的实际问题

原 `close()` 先 drain 当前Future，再调用 `postWorker.shutdown()`，随即清空executor并关闭 post/mesh/detector。Future完成不代表executor终止；Future取消也不代表正在执行的任务结束。`shutdown()` 抛错时还会跳过全部native关闭。嵌套finally会用后面的关闭错误覆盖前面的关闭/任务失败。

现在先禁止新的 detect/submit/measurement/summary 调用，再 drain。随后尝试shutdown并实际等待 `ExecutorService.awaitTermination`，同时核对 `isTerminated()`。等待只在输入资源所属线程进行，保留中断标志；没有shutdownNow强杀、UI join、固定sleep推测或自动重建executor。

executor无法确认停止时，保留 post/mesh/detector 和executor引用，抛出并保留首个错误。这样不会在任务仍可能使用时释放native图；主程序已有 `RuntimeInputStop` 失败门会阻止同进程盲目重新创建输入资源。此分支需要真正强制停止应用后打开新进程，不能靠重复close洗掉失败。

executor已终止后，仍尝试关闭全部三类图/模型，复用 `ResourceCleanup` 保留首个错误及其他suppressed错误。CPU任务失败不跳过安全关闭。成功重复close不再调用native；失败重复close重新抛出原错误；异常退出留下的“已经要求关闭但尚未完成”状态也不会返回成功。

`CloseStatus` 是不可变的调用层诊断：requested、sequenceCompleted、postExecutorTerminated、failed、首个failureType（最多96字符）。sequenceCompleted指关闭调用序列已走完，失败时也可能true；必须单独看failed。匿名异常的simpleName可能为空，因此不能把空failureType当作没有错误。这里的executor终止是其真实API状态，未额外观察内部Thread.State或native图内部线程。

正常推理、478点、52表情、FP32归一化、姿态、NPU调度、相机和400×640／16或20视点均未改变；新开门检查仅保护停止后的再调用。这不是新增FPS或NPU占用率测量。

## RED/GREEN与检查

初始检查使用实际 `NpuFacePipeline` 字节码，真实JDK单线程executor在Future已完成后仍被afterExecute门阻挡；原close提前返回，检查失败。还先验证“要求关闭但未完成”原实现会把失败洗成成功；随后增加明确failed字段的接口检查，再实现。

`tests/run_npu_face_pipeline_stop_tests.ps1`：71项通过。包括真实executor终止等待、保留中断、Future已取消而任务仍live时禁止关闭图、shutdown执行前／执行后抛错、CPU任务失败及多步关闭Error、重复close保留原错误、关闭后的再调用拒绝、匿名异常，以及NPU失败进入真实输入失败门。SDK编译使用实际RknnModel；执行时只替换JNI入口／post图，不运行推理或native模型。

相关回归：实际主程序输入桥接48、FaceGeometry真实protobuf配置32、NpuExpressionPostGraph真实组合386通过。分别仍使用明确的生命周期／native外壳，不是Android平台资格。日志和源码输入哈希放在 `app/build/npu-pipeline-stop-20261004/host-checks-final.json`。

## 仍缺失的证据与下步

在本切片92ae07f时实查 `native/rknn_face.c`：cleanup调用 `m->destroy(m->context)` **没有核对返回码**。后续已在实际JNI补返回码和失败隔离，并保留Java关闭失败及构造器主异常；源码／主机／交叉编译证据见[rknn-native-cleanup.md](rknn-native-cleanup.md)。尚未安装到设备，不能把主机通过当作厂商驱动释放已验证。

构造器抛错前的部分资源关闭及失败传递，也仍要单独核对；本切片没有把它们宣称为成功。相机HAL onClosed／Image leases、GL owning context内清理、CPU pose实际Thread终止与所有预览Owner回执仍缺失。`RuntimeInputStop` 的 `hardware_qualified` 保持false；完整配置Owner／备份恢复UI不得据此放开恢复门。

继续保留背景缓存默认关闭和原设备v20。确认稳定供电后才走可恢复实验流程，完成16/20视点像素门及90秒NPU＋可见背景＋交织联合验证；20视点带背景30FPS、USB实时和角色外观仍未验收。
