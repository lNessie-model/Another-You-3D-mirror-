# 静态背景缓存实验：源码已接入，未部署、未实测

2026-10-04北京时间07:00以后，仅在电脑上继续处理源码与主机验证。没有ADB操作、APK构建／安装、付费生成或新的设备测量。停电前冻结的c84de9a源码及v22 APK保持原文件；设备最后确认仍为06:24记录的v20，当前供电及USB状态未重新确认。

## 与每视点400×640目标的关系

既有联合测量中，摩尔独立头部20视点达到30.4333FPS；带可见哥特背景的20视点为26.9992FPS。相同fit、仅去掉背景绘制的诊断为30.4138FPS，说明静态背景值得单独优化。本实验保留400×640、完整独立视点、头像动态形变与可见背景；没有把去掉背景的测量当作完成。

缓存移除了命中帧中的静态背景几何／材质绘制，但加入每视点全屏颜色及深度读取。实际速度可能改善，也可能因带宽、额外采样及gl_FragDepth损失早期深度优化而变慢；目前没有新的FPS、GPU占用或提升比例结论。

## 当前实现

- `AvatarBackgroundCache`：GL线程和context generation所有权，完整键命中后无分配／背景重绘，仅在全部视点捕获成功后发布。键包括所属scene实例、模型SHA、固定配置的视点数／尺寸／group、实际physical aspect、fit、所有静态节点实际world和全部视点VP。保存输入副本；非有限值、错误线程／context拒绝。分配、捕获或提交检查失败释放部分资源，原错误保留，不能恢复部分缓存。GPU存储上限64MiB。
- `AvatarBackgroundGl`：独立RGBA8和DEPTH_COMPONENT16数组，覆盖所有16／20层，各serial层或OVR4组固定FBO。背景使用同一AvatarGpuScene、相同VP／fit／世界矩阵及原材质shader生成；缓存不被普通视点清除／invalidate。恢复用texelFetch读取同一像素和全局layer，写gl_FragDepth，LESS测试保留头像遮挡与原来的尾部绘制顺序。空背景深度1丢弃；RGBA8直取不重复gamma，不增加第二次dither。
- `InterlaceRenderer`：只在明确的private-head实验打开。默认关闭；普通路径仍调用ALL。eligible要求静态、unlit、无受控祖先且原本排在动态节点后；不满足时普通绘制。构建失败在本context／尺寸周期内明确fallback，状态包含警告，不逐帧重复分配。resize在所属context释放，新context直接丢弃旧句柄、不在新context删除旧数字名。没有默认启用，也没有生产资格。
- `MirrorActivity`：strict Boolean debug选项`test_static_background_cache`；仅与`test_private_head=true`、individual backend组合。release或错误类型／null／batched组合拒绝。
- `AvatarMultiviewCheck`及`AvatarPreviewActivity`：新增明确的设备验收入口`verify_private_background_cache=true`和`test_private_head=true`，与其他验证互斥；`test_view_count`选择16或20。逐一运行全部69姿态，在相同prepared pose及persistent OVR4目标上比较普通ALL和动态＋缓存；每层独立readback，要求RGB、alpha零差异，16视点完成1104次比较，20视点完成1380次。要求头部姿态改变输出、各视点可见变化及背景在69姿态中只构建一次。清理失败或取消不合格。

额外纹理名义存储：16×400×640×6=23.4375MiB；20层29.296875MiB，不包括FBO／shader、驱动开销和验证临时目标。更正准备文档的旧判断：现有makeMultiviewDepth本来已分配views层，它仍需每帧清除／invalidate，不能直接作为静态背景缓存。

shader的逐视点整数varying使用[OVR_multiview2规范](https://registry.khronos.org/OpenGL/extensions/OVR/OVR_multiview2.txt)允许的ViewID依赖；color/depth附件组规则按[OVR_multiview规范](https://registry.khronos.org/OpenGL/extensions/OVR/OVR_multiview.txt)。读取与深度量化结果仍必须在实际Mali上检查。

## 主机证据与边界

缓存控制321项通过。实际选项／状态／验收profile解析34项通过；SDK35编译了新GL驱动、实际renderer、Activity及诊断代码。三套真实场景Geralt＋Gothic、Makima＋Cyber、Maul＋Gothic，每套全部69姿态的生产key提取检查共222项通过：真实头部发生旋转，背景key不变；actual background world及physical aspect改变会失效。

场景并发状态、watchdog、shader合同、相机、16／20视点、产品设置、preview姿态与GL生命周期的相关主机回归见实验证据目录。Unsafe只绕过测试中的EGL构造；Matrix夹具及Android SDK linkage不执行GPU。无native shader编译、真实EGL／Mali像素、物理呈现、艺术效果或联合FPS资格。

旧PersistentFboConfigTest第一项期待产品默认关闭，但669287b的InputOptions早已默认开启。本轮保持产品默认值，修正旧断言，并补充release及空extras的默认检查。

## 恢复供电后的验收顺序

1. 先重新读取ADB设备、安装APK SHA及配置／角色state，确认与保存的v20记录一致。USB流错误−38仍需用户可操作时重新插拔；性能回放可以继续，但不能宣称实时USB合格。
2. 更新实验回退包装器的实际允许时间窗口后，构建独立的实验APK与源SHA记录。保留原v20 APK、prefs和avatars/state恢复材料；旧截止门不可直接绕过。
3. 对三套带背景角色各做16、20视点完整69姿态的普通OVR4 vs cached OVR4检查。产物`tripo-head-background-cache-16-check.json`／`tripo-head-background-cache-20-check.json`必须来自当前runId、当前context、当前模型SHA，completed、passed、background_cache_qualified全部true，所有层完成，cleanup无错误，background_cache_builds=1。当前只是提供源码入口，尚没有设备结果。验证不运行NPU／交织，不是联合性能证明。
4. 保持旧serial vs OVR及69姿态契约；新恢复shader的serial路径另做相同场景对照。缓存颜色深度的共面边缘、量化、遮挡及context丢失／resize回退必须实际检查。当前严格rgba门若失败，先修复，不能改容差消除失败。
5. 通过像素及生命周期门后，执行90秒真实人脸NV21回放＋NPU478／52＋动态头像＋可见背景＋20×400×640＋交织。与普通路径同条件比较，核对状态actual=cached_unverified（实验实际工作）、bytes／builds、无warning/fallback及完整face率；以确认INTERACTIVE的SurfaceFlinger呈现FPS判断≥30，不用回调速度代替物理输出。额外UI负载及USB实时路径仍单独验收。
6. 验收前不进入默认产品UI。整体未完成：可见背景20视点30FPS、角色相似度／闭眼口型、生产UI配置／Owner停止流程和USB实时采集。

本轮不创建付费任务，预算保持535／4000积分，无待确认计费。
