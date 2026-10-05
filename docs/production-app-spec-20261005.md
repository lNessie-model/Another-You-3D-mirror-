# Another You 正式 App 规格（2026-10-05）

## 用户目标与完整范围
用户要求把现有demo改成正式Android App：符合魔镜主题的UI、合理操作逻辑、此前全部不同角色和场景资产纳入产品、逐模型完成类似Geralt的面捕/材质/眼口校正，并且每次验证通过的迭代同步GitHub。此前16视点以上30FPS与NPU分担推理目标仍保留，不能把当前约10.62FPS报告或低面数预算当作达标。

## 明确假设
产品名暂用Another You / 另一个你；沿用已选择的椭圆哥特蝠翼玫瑰外壳，暗金属/酒红/少量暗金主题（已向用户询问偏好，收到新偏好即更新）。竖屏10:16；关键操作始终在椭圆可见范围内。全部资产指此前独立角色及背景的最新可运行校正版，不在APK重复打包同一角色十几个研发中间版；原始文件、旧迭代和审核证据保留归档。图标打开首页，一次点击进入实时魔镜；保留现有设备launcher的调试能力，本轮不改固件/开机配置。

## 技术与目录
Android native Java17 / Gradle8.5 / SDK35 build-tools35.0.1 / targetSdk30 / arm64-v8a，保持com.mirror.bench applicationId和原debug证书以覆盖升级、保留用户配置；不新增联网权限、框架或native依赖。正式唯一launcher为MirrorHomeActivity；MirrorActivity保留原推理和交织内核及debug extras。公开仓库main当前c966355，源码基线30e4。

app/src/main/java/com/mirror/bench/: 原生主题、产品页面、角色元数据、原渲染器/面捕。
app/src/main/assets/avatars/catalog/: 按角色slug存放校正版character.glb/avatar.json/真实缩略图；仅选择角色时解码。
app/src/main/assets/scene-backgrounds/: 四个已做背景及椭圆安全构图版本；现有程序背景仍可选。
docs/: 本规格、发布与模型校正说明。tasks/plan.md、tasks/todo.md: 活动阶段和验收证据。
scripts/与tests/: 包生成、结构/rig校验、主机回归与设备检查；output与私有相机素材不入公共仓库。

## V31：正式交互入口和主题（完整纵向迭代）
- 一个Another You启动图标与产品主题，首页显示当前角色/场景，清晰的进入魔镜、角色、场景、设置入口。
- 实时界面去掉常驻debug横栏；中央椭圆安全区的可收起操作菜单。触摸可重新显示；角色/场景/校准/设置/返回首页操作可达。内部debug工具通过维护入口保留，不再第二launcher。
- 状态分清初始化、寻找人脸、跟随中、相机不可用、权限未授权；使用易懂文案与正确动作，不把推理等待当硬件错误。不能将无摄像头或未检测到脸标为实时追踪成功。
- 相机权限、被拒后系统设置入口与流失败重试/重插提示，动作由用户显式触发，不清除数据。
- 复用原SceneViewPanel/CameraCalibrationPanel/PanelCalibrationActivity原功能和保存/取消事务；镜像、部位响应、pitch10/tan.2777777/原view_count及用户当前景深值保留。
- 设置和菜单内关键控件触摸目标至少48dp、有文字和contentDescription；不靠颜色单独表示状态，按Android返回键可预测返回上一层。
- 本版允许角色库仍以真实已有角色为范围逐步接入，未校正模型不能伪标通过；V31不等同完整目标完成。

## V32+：完整资产库与校正
- 加入至少现存10不同IP头部、内置向导及既有场景资产，实际完整范围由只读清单补全；每件asset有slug、displayName、准确SHA、rig/校正状态、文件/解码/贴图资源统计和真实预览。
- 独立BundledAvatarCatalog管理APK多角色，绝不把全库塞入原三槽AvatarPackageStore；其current/previous/candidate导入恢复机制保持。
- 选择状态以稳定slug和资产版本存储，包内资源缺失/损坏有明确错误、保留选择信息并安全降级；不能悄悄以其他角色当作选择成功。
- 列表只读轻量metadata+小thumbnail，打开角色才后台加载GLB；仅当前/上一角色必要缓存，不全量解码。角色切换关闭旧GL/VBO/纹理及CPU worker。
- 每模型使用自身的眼口/眉拓扑定位、头部尺度和profile；不直接复制Geralt坐标或声称Tripo自动提供完整52表情。统一Face58/52映射、眉抬/眉压竞争、端点闭眼/睁眼、自然smile、非尖下颌、亮暖白等长牙列与暗口腔、PBR材质与中性形态。
- 生成基础约8k-10k三角面，运行成品目标<=20k三角面/20k实例顶点/8 draws/32MiB文件/80MiB解码；任何超标有真实统计与处理，不静默丢部件。2K颜色+1K法线+1K ORM优先，不把8K原图直接上传设备；原图原件归档。
- 背景和关键图案在椭圆遮挡范围内构图；12现有背景先完整保留，新增3D背景须另做导入/性能验证才能声称实现。

## 构建与验证命令
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME='C:\Users\lNessie\AppData\Local\Android\Sdk'
& 'C:\Users\lNessie\.gradle\wrapper\dists\gradle-8.5-bin\5t9huq95ubn472n8rpzujfbqh\gradle-8.5\bin\gradle.bat' --offline --no-daemon assembleDebug
& .\scripts\test_java.ps1 -JavaHome 'C:\Program Files\Java\jdk-17' -AndroidSdk 'C:\Users\lNessie\AppData\Local\Android\Sdk'
新纯逻辑主机测试按对应runner编译真实生产类；app装机由root统一操作，先核实证书、备份APK/模型/配置、保留回滚路径。

## 代码风格与接口边界
小型原生View组件，数据读取/解码不在UI线程；metadata不绑定GL owner。复用现有JSONObject/AtomicFile与生产loader，不引入新全局EventBus或笼统重构。
示例：`void showCameraUnavailable(String reason) { statusView.setText(reason); retryButton.setEnabled(!retryInProgress); }`
允许新增正式导航、catalog和逐模型profile；保持现有安全导入/退出清理、FacePlayback语义和native依赖。禁止删除失败测试、造假成功状态、提交个人录像/人脸样本/凭据、关闭TLS验证、强推、清除用户数据、刷机。

## 每版验收与同步
版本Code/Name真实递增（此前一直1/0.1.0不能当作30次发布）；V31拟versionCode31/versionName0.2.0，后续递增。每版必须：实际构建通过、相关真实逻辑回归、manifest唯一产品launcher与权限检查、APK签名匹配、资产SHA/预算/完整清单核对、设备截图与真实导航/设置持久化/资源退出检查。相机可用时另测采集计数与面捕，短录制联合测试记录实际presented FPS/CPU/GPU/NPU/PSS，区分无脸与完整脸推理。
仅通过的迭代由repo_sync冻结、审查、普通push至现有仓库并独立核对远端SHA；保留原历史，使用Windows既有代理仅进程/命令作用域。失败保持上一APK与完整状态记录，不能把暂未验收版本称为最终正式完成。

## 待用户反馈与已知状态
主题与预算范围已发异步文本问题；不影响现有资产校正和正式App工作。Tripo经正规进程代理已doctor通过，余额9230/冻结0，原项目授权余3465，新增收费仍0。当前设备本次开机曾在新会话采集约24.5FPS，stage13短时部署与GPU门槛通过；之后同会话再次发生CAMERA_ERROR流配置失败，持续稳定性未通过。旧跨开机错误文件与这次新错误分开留档。用户个人表情验收、完整实时联合负载和30FPS仍待验证。正式界面必须如实提示当前硬件错误。
