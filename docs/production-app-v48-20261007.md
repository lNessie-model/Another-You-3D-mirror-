# 正式 App V48：sRGB 视图实验与普通入口复核

V48（0.2.17 / versionCode48）同签名覆盖安装，保留现有主题、角色、完整PBR与四份用户配置。新增默认关闭的sRGB视图实验，将原完整PBR的逐片元颜色编码交给sRGB颜色附件，并以SKIP_DECODE读取已编码视图，最终交织和背景公式保持。原Geralt、16个独立400×640视点、1200×1920输出没有缩减。本轮修复实际启动配置的资格误拒绝，完整新同包ABA为10.5337/11.3145/10.5233实际呈现FPS，候选对两端普通均值+7.4651%。CPU上升、面捕子范围变慢，且原固定画质RMSE门失败；候选继续默认OFF，30FPS没有达到。

## 实现与启动修复

仅确切Geralt/普通draw/原PBR/16层数组允许测试。调试布尔test_srgb_views与collector --srgb-views默认false；sRGB目标和linear-output shader成对启用，检查GL扩展、sRGB FBO编码、SKIP_DECODE参数读回、所有单层及四层组附件、sampler0和EGL上下文代次。写入时恢复进入前的颜色写入状态，初始化的已编码颜色另行处理，失败不隐式切普通候选。

首次实际runtime启动失败：MirrorActivity一直设置legacy explicitLod=true，而候选误把该flag当成不兼容。实际有头像的runtime本来选择runtimeBackgroundProgram，该shader已有textureLod；修复只去掉错误flag排除、验证实际选中非零背景program，并保留初始化首错，避免resize以missing-context覆盖它。普通flag、最终程序选择公式和shader不改。原异常logcat、失败candidate采集、脚本和V1 APK保留，没有将首轮普通结果拼接到V2。

V2唯一新增生产修正为Renderer；其余19个V1路径原字节保持，6个新测试/桩使本轮完整候选共26路径，另有版本字段构成27构建输入。独立actual entry25、配置25、诊断22、Renderer552检查通过；真实Activity flags（含explicitLod/persistent FBO/OVR）被覆盖。默认关闭路径与父11349f7独立编译的shader/draw/matrix/buffer/clear trace同98e8b5149d63b458411528ae2c924c68091c4e919b955000fced2ea9011cb010；这属于主机GL边界验证。Gradle成功，构建输入前后精确冻结，实际manifest/签名核对。78assets/9native对V47逐字节一致。

## 固定画质门：失败，未放宽

V1实际Mali完成69姿态、两后端共2208层pair，以及三表情和显式不绘制、12背景共48个实际默认帧缓冲对比。所有maxRGB差≤1、alpha零差，但128/2208层和12/48最终画面RMSE超过事前0.1门；最大层RMSE0.115238656347，最终0.102604959811。serial和OVR两路整组结果一致；不绘制的透明层及12背景输出零差。四张真实PNG的RGBA散列与GPU读回绑定并由Root/peer独立复核。RGB±1差值有正有负，不能称统一固定偏差。

V2仅纠正runtime资格和错误处理，没有改颜色公式或门槛，也未重新执行整套像素测试。因此保留V1失败，没有声称V2完整像素通过或光学验收；新性能采集明确为diagnostic、productionQualified=false。

## 全新同包短测

V2实际25秒启动检查先达到原Geralt/linear program/16视点/478点/52系数/sRGB附件ready，独立复核4个完整状态及46条journal均无错误。随后事前冻结三组各60秒，普通→候选→普通，只差sRGB flag；同一个最终APK，使用全部确认INTERACTIVE区间且每组≥40秒，未知尾保留并排除。原V1失败运行未拼接。测量期间无截图、JDWP或GPU timer；Root/peer从raw poll journals、SurfaceFlinger时间戳和资源原文重新计算。

原Geralt19907顶点/19157三角形/7节点绘制项/原贴图PBR，ordinary绘制、4持久FBO、逐帧VP、bounded CPU pose worker、目标31。853帧NV21录像face-reference-stable-20261001-01，名义24.369907FPS，无USB采集或视频解码。RGA、RKNN检测与478点、FP32归一化/混合CPU-NPU52后段；没有启用NPU形变或NPU材质计算。各material/分批/白色/复用/ORM/math/空白实验关闭，timer pool0。

|组别|SF呈现FPS|确认秒|未知尾秒|整机CPU%|GPU忙%|NPU忙%|PSS均值/峰值MiB|面捕子范围FPS|received→complete ms|
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
|original-first|10.5337|44.3806|4.7710|57.19|98.07|45.86|301.13/409.31|16.0357|123.17|
|srgb-candidate|11.3145|44.7708|4.9688|61.08|98.00|49.00|294.65/397.67|15.3064|140.59|
|original-repeat|10.5233|44.6363|4.8892|57.34|98.07|46.55|300.60/409.30|16.0015|125.29|

面捕快照子范围分别35.1715/35.2794/35.1843秒，短于SF和资源范围。阶段墙钟重叠，不能相加当GPU或端到端时间。温度依次58.333–63.888、64.444–68.125、68.125–71.111°C；顺序温态、短测、稀疏PSS不证明长期因果、热稳定或显存释放。三组30FPS门均false。本轮不改变正常默认。

## 普通界面、退出与摄像头状态

新普通会话b5a84837-a724-4913-811b-e0d142881216原Geralt完整PBR/ordinary首帧与280次draw通过。sRGB及其它material实验flags均false、view_fragment_output为shader_encoded_reference。真实首页/角色/场景面板取消/设置/关于0.2.17/Back流程通过，最后回实际Home，JDI Java runtime owners=0，四配置前后raw/SHA精确保持。

初次UI脚本错误期待legacy dialog标题“画面、景深与背景”，实际主题页面是“画面、镜像与表情”；保留原断言、UI、失败，修改私有脚本后用同APK重新完整检查，没有改App或重建。真实当前首页/角色/设置截图已提供会话远程查看。3个可选角色、12背景和已打包素材保持；完整历史头部自然52表情、眼睑/材质/口腔及三维背景仍待逐项完成。

USB外置相机仍流配置失败，底层VIDIOC_REQBUFS为EBUSY，不能凭上层Function not implemented认定分辨率不支持。专属external provider一次重启、临时停止时一次有界原生probe均未恢复采集，随后已恢复该服务运行；旧legacy provider/cameraserver/adbd未重启，ADB和原配置保持，没有root/USB reset/刷机/重启设备/清数据。full-speed12Mbps链路及MJPG声明已记录，但未证明它导致EBUSY；持有者未知。录像测试不证明实时USB恢复。

普通App回归通过与sRGB候选画质不通过是两个验收范围。正式完整作品、全部头部校正与16+独立视点30实际呈现FPS仍未完成。

最终APK AvatarRuntime-v48-srgb-view-v2.apk，SHA256 2533afb16002edfd4ff65b2d6c3ac22624a6600bc4a63d8316698cbe43b265f7，原证书64a4af6baa9f10fd9d5bd09003ded7def368211604e3e91181bf015cdfbf6da8。V1/v2 APK、私有人脸录像、vendor诊断副本和原始QA保留本机，不发布；实现、测试和本文纳入本轮限定同步。历史深previous JSON/WIP保持，不增加previous_v47嵌套副本。下一步优先处理GPU主要负载、USB恢复、模型自然度及安全的NPU卸载。
