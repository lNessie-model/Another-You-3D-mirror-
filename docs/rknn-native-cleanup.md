# RKNN 图像模型释放失败隔离

2026-10-04，基于源码92ae07f的电脑端修复。实际JNI源码检查和Android ARM64交叉编译通过；没有APK构建、安装、ADB操作或付费任务。目标设备稳定供电仍待确认，旧APK及设备v20的资格不扩展到本次源码。

## 行为变化

`native/rknn_face.c` 原cleanup忽略rknn_destroy与dlclose返回码，仍释放模型包装器。现在仅在销毁成功后卸载库，所有成功才释放包装器。销毁／卸载失败保留不确定的所有权，记住失败码，返回`NativeCleanupUnconfirmed`，不再重试不确定的销毁或卸载。该模块的原子故障标志不提供复位；后续open/info/run被拒绝，直到应用进程真正结束。健康模型仍可关闭，以继续尽可能释放其他资源。

初始化失败路径也核对cleanup结果；初始化进行期间另一上下文出现故障时，返回前再次检查并关闭新模型。标志是入口与发布前的保护，不是全应用硬件锁；不能取消已经进入驱动的初始化／查询／推理。不同诊断Activity和其他原生模块的完整所有权协调仍未完成。

实际`RknnModel`现在保存close失败，重复close继续抛出同一个失败，不再次调用native。初始化metadata失败会保留原始异常，并将cleanup失败作为suppressed附加，避免用清理错误覆盖原始失败。JNI库改为首次native操作加载；direct buffer分配不加载JNI，正常运行的JNI签名、输入、输出和crop算法不变。package内NativeCalls接口仅替代测试的native边界。

实际`NpuFacePipeline`构造器同样保留主异常及cleanup失败。实际主程序在Exception／LinkageError路径及其他Error重新抛出前，检查cause／suppressed中的释放失败类型。即使构造器未返回可关闭对象，也会计入RuntimeInputStop的NPU失败，close尝试数保持0。记录发生在epoch有效性判断前；旧worker的失败不能因界面换代被忽略。停止回执继续先进入进程失败门再释放Main输入许可，不自动重建输入资源。

异常图检查使用身份集合处理循环，最多访问64个不同异常；超过此规模保守记为未确认释放。它不是native/HAL/GL证据。真正耗尽堆、厂商驱动异常、Android VM异常类加载与进程重启恢复仍需设备验证。

## 验证及限度

- 实际rknn_face.c，模拟VM／加载器／驱动：6个独立进程、191项检查。涵盖正常关闭、destroy失败、初始化失败且清理成功／失败、dlclose失败、初始化期间故障、失败重复关闭和后续入口拒绝。未运行目标驱动。
- 实际RknnModel Java：40项，native边界替代。覆盖关闭失败及重复调用、关闭后运行拒绝、metadata主异常与cleanup suppressed、零句柄、异常cause／suppressed／循环／超界。
- 实际NpuFacePipeline构造器：10项，假assets／模型边界；部分模型初始化失败后原异常保留、cleanup类型传递及资产读取失败。原停止顺序71项回归通过，使用真实JDK executor。
- 实际MirrorActivity接线52、RuntimeInputStop并发／回执1481通过。SDK编译后使用明确平台外壳，不是Android生命周期运行。
- 原渲染配置回归：背景配置34、3份真实资产×69姿态状态键222；投影19、持久FBO17、运行视点12934、产品视点75、GL配置34。未执行EGL、像素、表情外观或FPS。
- NDK r25c对实际JNI源码使用`-std=c11 -O2 -Wall -Wextra -Werror`交叉编译Android API30／ARM64，ELF架构和6个JNI入口通过。候选只放app/build/rknn-face-android-compile，SHA256为ef73c838828b3072c81af0d8c66070c54b95ce181d9a30b8d47b2a01571abb91；没有替换打包jniLibs，也没有新APK。之后正常构建APK必须重编本源文件并明确更新打包库。

新增测试先复现缺失接线、构造器主异常被覆盖、初始化期间故障仍返回模型，再修复。最终日志、源码输入哈希、交叉编译候选和冻结元数据另存交付目录。git diff --check与人工检查通过；检查了重复关闭、故障后所有权保留、错误传播、JNI异常类构造器及编译清单。当前构建没有开启混淆；日后开启混淆须保持JNI引用的类名和String构造器。

## 下步

这组修改只覆盖图像RKNN模块和Main的失败传递。RknnExpression模块、MediaPipe图、输出release失败、Camera2 leases／onClosed、CPU pose真实线程退出与GL owning context内释放、各预览Owner仍需各自确认。hardware_qualified始终false；不能据此放开完整配置备份恢复门。

继续接生产ConfigurationOwner／全部读写门和备份恢复UI；确认设备稳定供电后，先核对原v20及配置，再做可恢复实验。背景缓存保持默认关闭。20×400×640带可见背景30FPS、实时USB（−38）、角色相似度和自然表情仍未验收。已录人脸回放不能代替实时USB通过。

任务预算4000，累计535，剩余3465，本组消耗0。模型继续头部＋短颈、成熟比例；现有12个原件／10种游戏动漫影视参考及3份可编辑表情原型均保留，候选相似度不算成品验收。后续先提高代表角色的造型和闭眼／张嘴质量，再扩充种类，继续给实际3D预览。
