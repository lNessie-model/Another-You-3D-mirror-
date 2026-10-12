# 另一个你裸眼3D魔镜工程交接文档

交接日期：2026-10-12。交接对象：后续软件、算法、3D资产与整机工程接手者。运行基准：V60 / 0.2.29。

本文汇总当前已部署工程、可复用资产、构建依赖、设备恢复方式、验证证据与未完成工作。接手时先恢复同一运行基准，再分开推进模型自然度、联合性能与外壳装配。当前版本可用于继续开发与演示，不能据此认定整机已经完成商业发布验收。

当前已具备离线原生应用界面、单人面捕链路、NPU分担、真实GLB头部驱动、多视点渲染与交织、角色和素材管理。主要未完成目标是完整角色的16视点以上30FPS、最终光学校准、更多美术合格的实时角色，以及实体外壳后的现场验证。V60主要完成UI动效，未重新验证真实USB面捕和联合帧率。

## 1 接手先读

### 1.1 唯一运行基准

| 项目 | 当前记录 |
| --- | --- |
| 应用名称 | Another You / 另一个你 |
| 包名 | com.mirror.bench |
| 应用版本 | versionCode 60，versionName 0.2.29 |
| 本机源码提交 | 651c2e61d76e1470fc735a65a365647f0a2255dd |
| 公开代码基准提交 | 80c52d57b8258758dce2531bc6c63d04f0346337 |
| 仓库 | https://github.com/lNessie-model/Another-You-3D-mirror- |
| 已安装APK | AvatarRuntime-v60-crafted-motion-v3.apk |
| APK大小 | 242,180,601字节 |
| APK SHA256 | 1c06a4aea581489f0ee2711616af5ef8a535e0cbeb8b246d74a53a6f87a617a6 |
| 签名证书 SHA256 | 64a4af6baa9f10fd9d5bd09003ded7def368211604e3e91181bf015cdfbf6da8 |
| 最后关机前记录 | 2026-10-12：GitHub提交核对、实验代码备份、设备sync及安装版本检查通过 |

上述提交是本交接的实现基准；后续交接文档本身的提交不改变应用版本。仓库README包含完整历史，多处历史“当前版本”只是当时状态，应优先看最上方V60说明与本文。

### 1.2 第一次接手的顺序

1. 阅读本文状态、构建、安装与恢复章节；核对交接包清单和APK SHA256。
2. 保留现有设备应用与配置。设备开机后连接原OTG调试口，用ADB确认指定序列号。
3. 先启动已部署V60首页，检查角色、素材、设置和返回。首次复接不必重装，也不必刷机。
4. 再独立检查USB采集、人脸覆盖和完整478/52输出；最后检查实际角色与交织联合负载。
5. 每次只修改一项，留存前版本APK、资产哈希、配置及实机预览；验收后再提交和同步代码仓。

### 1.3 交接包的内容边界

全套本地交接包包含当前公开工程的固定提交、全部已打包生产资产和原生库、必要本地MediaPipe Maven文件、固定JSON测试库、V60 APK、交接文档、完整资产目录、未合并实验代码备份、三个研究模型候选、正确规格书和原项目提案、系统恢复日志及关键验证收据。

Java、Android SDK、Gradle、NDK、Blender等大型开发工具需要接手电脑自行安装。完整历史性能原始采样和全部实验中间产物仍在原电脑的output、assets、native-tools目录，本文给出入口；并未把整个E盘复制到交接包。个人面部录像、真实脸部fixture、设备私有preferences、签名私钥与Tripo/API凭据不放入工程压缩包。研究模型与实验代码不自动成为正式角色。

## 2 产品定义和实现状态

产品是单人互动的裸眼3D魔镜：用户进入范围，角色从等待状态唤醒，跟随头姿、视线和面部动作；用户离开或检测丢失后平滑回中与待机。用户后续明确只需要头部，不以全身骨骼角色作为当前制作目标。

屏幕竖向，比例10:16。外壳内外轮廓均为椭圆，允许遮住屏幕四角。背景与装饰要向中间收紧，用户要求在既有风格上增加约30%的铺陈；这属于美术方向，不能当成已量化完成的装配验收。

| 子系统 | 可确认的当前状态 | 尚需完成的边界 |
| --- | --- | --- |
| 原生应用与UI | 首页、角色、场景入口、设置、素材库、本地角色管理、V60动效已部署 | 完整正式release签名、诊断入口收敛和整机发布验收 |
| 单摄面捕 | Camera2外置相机输入、录像回放、478关键点、52系数和头姿链路已有验证 | V60未重新测试本人USB面捕；历史USB流配置故障需复接确认 |
| NPU分担 | RKNN检测、关键点及混合CPU/NPU表情后处理已接入运行链 | 不能用Delegate.NPU枚举或MobileNet成绩代替完整面捕证据 |
| 角色驱动 | 四个可选角色；支持morph、头/颌/眼节点与组合修正 | 部分角色仍是试用或旧版参考，完整自然度未全部验收 |
| 多视图与交织 | 16/20视点配置、独立投影、普通/OVR路径和像素检查已有证据 | 当前完整PBR头部的16视点30FPS未达标；最终实屏光学校准未完成 |
| 资产与背景 | 素材库36项，正式源件、静态预览和档案均保留 | 静态预览不等于可实时面捕；场景原件不一定已接入实时背景 |
| 外壳与整机 | 已确定椭圆框及哥特等视觉方向 | CAD/STL、实体尺寸、固定件、散热和打印装配尚无最终验收 |

双摄提升面捕准确率属于后续研究方向；当前工程是单路面捕，没有双目时间同步、内外参校准或多相机融合实现。不要把物理USB接口数量等同于可同时处理的人脸相机数量。

## 3 工程位置和目录

### 3.1 原电脑位置

| 路径 | 用途 |
| --- | --- |
| E:/tripo/device-lab | 主要开发工程；包含180项未合并工作文件 |
| E:/tripo/repo-sync-20261005-65bac8d9 | 与GitHub同步的公开工程，供正式基准交付 |
| E:/tripo/output/mirror-program | 按日期保留的APK、版本验证与发布证据 |
| E:/tripo/output/mirror-program/20261010/v60-crafted-motion-v1 | V60构建、安装、UI检查、原录屏、最终review-v3与发布收据 |
| E:/tripo/output/mirror-program/20261012/poweroff-checkpoint-001 | 关机前说明、180项实验文件ZIP、差异patch与哈希 |
| E:/tripo/assets/mirror-models-20261005 | 多角色生成原件及未晋升的校正研究 |
| E:/tripo/output/model-quality-audit | 模型技术检查、美术未通过说明和候选检查点 |
| E:/tripo/output/mirror-assets | 早期模型绑定、背景、Blender手调等产物 |
| E:/tripo/output/device-benchmark/20260929 | 系统精简记录、原厂恢复日志和设备资料 |
| E:/tripo/output/camera-benchmark/20261001 | 真实USB采集与分项/联合测试 |
| E:/tripo/output/npu-face-optimization/20261002 | NPU转换、推理与数值/性能历史证据 |
| E:/tripo/native-tools | 固定NDK、RGA及旧RKNN编译环境等本机工具 |

### 3.2 正式工程的结构

| 工程内路径 | 接手用途 |
| --- | --- |
| app/src/main/java/com/mirror/bench | Android UI、输入、面捕、控制、GLB、GPU与交织实现 |
| app/src/main/res | 原生样式、品牌绘制配套资源、窗口和弹窗动画 |
| app/src/main/assets/avatars | 四个运行角色及其GLB、avatar.json和缩略图 |
| app/src/main/assets/library | 36项素材源件、真实预览及就绪状态目录 |
| app/src/main/assets/library/image-* | 背景源图与椭圆版，运行引用以源码和目录为准 |
| app/src/main/jniLibs/arm64-v8a | 九个固定原生库；不要随意替换 |
| native | RGA/RKNN/多视图JNI源代码与原SDK声明 |
| launcher | 独立轻量桌面com.mirror.launcher |
| scripts | 构建、运行、采集与资产检查脚本 |
| tests | Java/Python/C/Blender及SDK边界测试入口 |
| docs | 历次版本、数据合同和验证范围 |
| review-assets | 已发布的实际预览与来源说明 |
| third-party-notices | 代码、SDK、模型和测试依赖声明 |
| production-dependency-manifest.json | 15个固定生产依赖的字节与来源 |
| source-sync-manifest.json | 源码与公开复制路径的映射及哈希 |
| product-release-manifest.json | 历史产品发布冻结记录；其版本可能早于V60 |

素材、生产依赖和源同步清单承担不同职责，不能因为某个清单仍记录V30依赖基准就认为当前APK是V30。当前版本最终以V60 APK门、安装收据和公开提交为准。

## 4 硬件和物理连接

### 4.1 正确规格书与实际设备

用户后续确认的正确规格书是YS-L6承认书V3.3，24页，生效日期2023-10-18。原先提供的YS-E735资料不作为本工程硬件依据。规格书与实际设备记录应分别使用，厂商的可选配置不代表这台设备全部具备。

| 项目 | 交接记录 |
| --- | --- |
| 主板 | YS-L6方向；实际ADB设备为RK3566 Android板 |
| 处理器 | 规格书：四核64位Cortex-A55，最高1.8GHz |
| GPU | ARM G52 2EE；项目实机Mali-G52 / GLES3.2记录 |
| NPU | 规格书标称1T；本工程以RKNN运行结果验证使用情况，不据此承诺模型吞吐 |
| 设备内存 | 实验配置约2GB；规格书默认1GB、最高2GB，不以默认档代替实测配置 |
| 系统和ABI | Android11 / API30，arm64-v8a |
| 屏幕 | 竖向1200×1920，10:16；历史UI记录density320 |
| ADB设备序列号 | 6L32552009566714 |
| USB与调试口 | 规格书三个USB2.0 Host、一个Type-C USB_OTG；调试使用原OTG口 |
| CSI接口 | 规格书MIPI-CSI 30PIN BTB；当前摄像头方案使用USB而非CSI |
| 供电 | 规格书DC输入推荐12V/2A；实际接线与适配器以整机确认结果为准 |

规格书第4页是参数汇总，第6页列出三个Host端子和电源接口。Host端子为4PIN、1.25mm间距，规格书标注USB限流1.5A。端子针序、供电和线序必须依原图核对，不能仅凭“四根线”认定相机线可直插；本文不替代电气接线图。

### 4.2 摄像头的已用路径

选用的摄像头方向为USB MJPEG模块，商品图有4PIN 1.25mm端子和多分辨率MJPG宣传。工程中的实际使用基准是640×480，系统Camera2外置相机HAL接收，HAL负责MJPEG解码，应用消费YUV_420_888并按真实row/pixel stride打包。

这条路径无需ESP32，也不通过ESP32专属并口接入。更高分辨率、多个相机同时打开或CSI替代均没有本次验收。曾经实际识别相机但出现CAMERA_ERROR(3)和endConfigure stream configuration failed；重新插拔与驱动恢复需要在设备旁确认，不能凭ADB枚举成功推断采集已恢复。

## 5 应用操作和界面动效

首页入口为MirrorHomeActivity，提供进入魔镜、角色、场景和设置。MirrorRolesActivity负责包内角色浏览与显式选择；AssetLibraryActivity区分可实时使用、静态预览和档案；AvatarManagementActivity管理本地导入、上一版与回退。运行输入和硬件由MirrorActivity持有，首页及普通元数据页面不启动相机/NPU推理。

设置分为持久配置、运行草稿和仅当前会话校准。只有明确保存才写对应配置。个人中性基准不作为任意设备和人的永久校准结果。运行页进入维护/校准、HOME、恢复、取消等流程应按已有状态合同处理。

V60采用暗酒红、旧金、椭圆轮廓和衬线品牌文字：2.1秒有限启动揭幕，金线双向展开，文字分段出现；镜框向首页轮廓展开，标题对齐实际TextView尺寸和基线后交接。可见内容行以8dp轻抬和460ms淡入，35ms错开、最多175ms。角色图片过渡420ms，按钮110/220ms，弹窗340/220ms。窗口和转场实际时间仍受系统倍率影响。

用户首次触摸会立即移除揭幕并继续派发同一次事件。暂停、移除和销毁取消回调与动画，静置不循环重绘；关闭系统动画后仍可操作。UI安全矩形为屏宽64%、屏高62%，居中保留在椭圆内部，不等于实物开孔尺寸。

实际交织GL窗口的Activity/任务转场仍只改变alpha。不要为效果给交织输出层增加整体缩放、位移或显示分辨率覆盖；那会改变光栅对应的原生像素位置。

实际UI预览见工程review-assets/v60-crafted-motion-v1；录屏与GIF采样不是面捕或渲染FPS证据。

## 6 运行架构和数据合同

### 6.1 实际数据链

```text
USB Camera2 或 NV21回放
  → 最新帧消费与stride打包
  → RGA / Bitmap规范输入
  → RKNN人脸检测及478点网络
  → CPU几何与规范化 + 混合CPU/NPU52表情后处理
  → 完整FaceFrame及头姿
  → FaceControlMapper / InteractionController
  → GLB绑定与有界变形worker
  → 真实primitive与PBR材质绘制
  → 16或20个独立投影视点
  → 原生1200×1920子像素交织
  → SurfaceFlinger实际呈现
```

采集、推理、控制、CPU变形和GL绘制是不同阶段。最新帧槽允许丢弃过时输入，避免持续排队加大延迟。状态输出保留会话、序号、时间戳、错误、脸部覆盖及完整结果，不因某一数组可用就把不完整帧发布为成功。

### 6.2 文件职责

| 文件或模块 | 核心职责 |
| --- | --- |
| MirrorActivity | 运行生命周期、输入、校准与renderer所有权 |
| CameraFrameSource | Camera2输入、真实缓冲与相机描述 |
| CameraBitmapNormalizer / CameraInputTransform | 顺时针0/90/180/270度后可选水平反射，复用输出 |
| NpuFacePipeline / RknnModel | RKNN检测、ROI跟踪和478点网络 |
| NpuExpressionPostGraph / RknnExpression | 规范输入、CPU几何、混合表情模型和完整事务验证 |
| BlendshapeSchema / FaceFrame | 固定标签顺序、不可变478/52/pose数据 |
| FaceControlMapper / InteractionController | 镜像、中性/个人基准、平滑、丢失回中和新鲜度 |
| AvatarGlbLoader / AvatarRig | GLB、纹理、节点、morph、资源预算与清单绑定合同 |
| AvatarRig / AvatarDeformer / AvatarPoseWorker | 按名称驱动和组合修正，姿态快照只变形一次 |
| AvatarGpuScene / InterlaceRenderer | primitive/PBR、多视图、OVR与交织 |
| PanelCalibration / CalibrationActivity | 光学参数、软件oracle与测试图 |
| AvatarPackageStore / BundledAvatarCatalog | 有界导入事务、选择、哈希、包内目录和回退 |

### 6.3 面捕合同

必须保留478关键点、恰好52项有限表情系数、4×4列主序头姿矩阵，以及接收/完成时间和会话标识。固定schema为mediapipe-face-blendshapes-v1；检查标签和索引，不能只按数组长度猜顺序。当前52项包含_neutral，实际为51个动作输出加中性项，不能凭空得到舌头伸出控制。

相机安装旋转、规范输入反射、角色动作镜像、左右表情置换及屏幕视点反转是不同开关。动作镜像通过明确的左右置换和姿态矩阵规则实现，不给模型root施加负尺度。输入变换是先旋转、后反射；90/270度交换规范尺寸，ROI和后处理也必须使用交换后的尺寸。

个人眉部/眼口基准只对新鲜、完整、合格的人脸样本采集，并绑定输入会话与revision；旧READY在换输入后不能继续确认。无人脸时拒绝基准是已验证路径，不等于所有真人成功校准分支已经验收。

### 6.4 NPU的实际使用方式

本工程直接调用RKNN，已将人脸检测和478点图像网络放到板载NPU。当前运行Options默认启用规范输入表情后处理，经CPU几何/规范化与混合CPU/NPU模型输出52系数；并不是把整套MediaPipe图直接放上NPU。部分早期类注释和文档仍描述未接入实验，应以MirrorActivity实际默认值及运行状态backend为准。

RknnExpression接收292个native-order float32规范值，共1168字节，验证模型和librknnrt哈希；输出恰好52项有限的[0,1]概率。CPU仍承担几何、规范化、部分表情运算、帧管理和资产变形。错误不被伪造的中性结果或静默回退掩盖。

本机使用固定RKNN Toolkit2 1.3.0、vendor runtime 1.3.0、驱动0.7.2及匹配模型。RGA NDK库1.10.6_[3]匹配既有驱动1.2.25，以应用内库使用，不替换vendor分区。原生重建使用NDK r25c。旧MediaPipe Delegate.NPU试验实际选择CPU；MobileNet约88%的峰值只证明分类网络和应用访问，不是面捕整链性能。

NPU不能承担现有PBR光栅化和屏幕交织着色。当前完整角色测试GPU接近满载，不能仅通过提高NPU占用、堆内存或增加无效推理循环推断画面会提速。

## 7 角色绑定和自然度维护

### 7.1 当前实现不是全身骨骼套件

主要驱动由GLB morph targets、Head节点、JawAttachments节点、左右眼节点和组合修正组成。当前杰洛特清单采用jawMode=morph、gazeMode=joint；下颌软组织与刚性下牙/口腔附件需要协调，不能同一组顶点既做完整张颌morph又重复完整颌骨旋转。

角色坐标合同为+Y向上、+Z向前、+X为角色自己的左侧，单位米、rootScale=1。正面看角色Left通常在观察者画面右侧；不要在资产阶段与运行镜像阶段重复翻转。

Tripo原件或自动body rig不会自动满足52项面部形状、眼睑闭合、嘴部开孔或材质预算。工程离线生成资产后，运行时无需Tripo在线服务。云端CLI版本、可用性与剩余积分本次未重新查询，也未进行新生成。

### 7.2 现有改良经验

眉毛不能只统一放大：需要分别处理browDown、browInnerUp、browOuterUp及眉部静止偏置。微笑应同时检查唇角、上唇、颊部和闭口形状。眼睛必须检查0/0.5/1端点、左右独立闭眼，以及blink×wide×squint组合，避免过度眨眼或完全闭不住。

杰洛特沿用V56之后较小张嘴范围：jawOpen与闭嘴补偿相关贡献0.8，下颌附件12度。暖白牙色和粗糙度0.42保留；V58只改善牙冠前肩和侧缘造成的主要暗槽，并非物理焊接无缝牙列，仍有局部残余和有限角度限制。口腔是简化结构，不能标为完整解剖真实性。

V55通过PBR主补光和连续亮部映射减轻塑料/蜡像感，改善温和，没有完整皮肤SSS。脸部、白发共享材质以及自身UV/法线限制仍在；不要全材质统一加粗糙度、压暗画面或直接套用另一角色贴图来掩盖源资产问题。

### 7.3 修改资产的操作边界

优先使用当前运行目录下的character.glb和avatar.json为修改基准，并先记模型SHA。保持Basis、顶点对应、UV、材质、target名称和左右定义；改变拓扑后必须重新建立全部形状与法线，不允许只替换中性网格。GPU光照与CPU重算法线的政策要匹配实际变形。

历史Blender手调入口在E:/tripo/output/mirror-assets/20261004/manual-face-ui-v1及manual-eye-ui-v2，配有打开手调界面.cmd和打开左眼手调.cmd。面板支持预览数值与编辑shape key；拖数值只是预览，改变顶点才改变形状。该历史.blend不等于V60最终GLB，不能未经核对直接覆盖当前模型。

新模型应依次通过：完整loader和manifest → 端点与混合姿态几何 → 自身纹理与法线 → 普通PBR实机效果 → 串行/OVR及individual/batch一致性 → 实际联合负载。技术数值通过不替代美术接受。

## 8 资产清单和研究候选

### 8.1 四个可选角色

| 角色 | 当前状态 | 顶点 | 三角面 | draw | 逻辑解码字节 |
| --- | --- | ---: | ---: | ---: | ---: |
| 杰洛特 | device_verified | 19,915 | 19,157 | 7 | 73,558,574 |
| 镜中向导 | legacy_reference | 15,085 | 29,482 | 7 | 5,890,656 |
| 艾达王 | corrected_trial | 19,105 | 19,639 | 8 | 41,708,894 |
| 宿傩 | corrected_trial | 17,303 | 19,515 | 8 | 44,094,586 |

资产位置、完整SHA和清单以avatars/catalog/catalog.json与各avatar.json为准。目录默认杰洛特，显式选择包内角色改变优先级；未明确选择时，既有本地导入选择不应被升级覆盖。

当前生产loader上限：文件32MiB、JSON1MiB、逻辑解码80MiB、20,000顶点、30,000三角面、8个primitive；morph与纹理还需满足实际解析限制。旧loader文档中32MiB解码或“不支持纹理”的初期描述不适用于当前完整路径。新角色希望控制在20k三角以内，镜中向导29,482三角是保留的旧参考例外。

逻辑decodedBytes不是PSS、GPU驻留或全部峰值内存；素材全部打包也不意味着启动时全部加载到RAM。

### 8.2 36项素材目录

素材状态为4项runtime_ready、18项static_preview、14项archive。素材库中的runtime_ready包括保留的旧向导，不代表所有角色美术质量相同。源件和运行校正件是不同文件：例如library/head-geralt/source.glb保留原件，真正运行的character.glb位于avatars/catalog/geralt。

完整列表与SHA另见工程资产清单.csv；以下由当前目录自动填充。

| 名称 | 类型 | 状态 | 源文件 |
| --- | --- | --- | --- |
| 杰洛特 | head | runtime_ready | library/head-geralt/source.glb |
| 杰洛特 · 初版 | head | archive | library/head-geralt-early/source.glb |
| 玛奇玛 | head | static_preview | library/head-makima/source.glb |
| 玛奇玛 · 初版 | head | archive | library/head-makima-early/source.glb |
| 宿傩 | head | runtime_ready | library/head-sukuna/source.glb |
| 达斯·摩尔 | head | static_preview | library/head-maul/source.glb |
| 萨菲罗斯 | head | static_preview | library/head-sephiroth/source.glb |
| 玛琳菲森 | head | static_preview | library/head-maleficent/source.glb |
| 五条悟 | head | static_preview | library/head-gojo/source.glb |
| 艾达王 | head | runtime_ready | library/head-ada-wong/source.glb |
| 小丑 | head | static_preview | library/head-joker/source.glb |
| 斯内普 | head | static_preview | library/head-snape/source.glb |
| 镜中向导 | head | runtime_ready | library/head-builtin-guide/source.glb |
| 月影向导 · 原件 | head | archive | library/moon-guide-early/source.glb |
| 月影向导 · 早期绑定 | head | archive | library/moon-guide-rig-early/source.glb |
| 赛博场景 · 初版 | scene | archive | library/cyberpunk-alcove-v1/source.glb |
| 哥特场景 · 初版 | scene | archive | library/gothic-alcove-v1/source.glb |
| 赛博门架 | scene | static_preview | library/cyberpunk-portal-v2/source.glb |
| 哥特石拱 | scene | static_preview | library/gothic-portal-v2/source.glb |
| 冷色雾 · 原版 | image | archive | library/image-cold-mist-original/source.png |
| 冷色雾 · 椭圆版 | image | static_preview | library/image-cold-mist-oval/source.png |
| 暗红雾 · 原版 | image | archive | library/image-crimson-mist-original/source.png |
| 暗红雾 · 椭圆版 | image | static_preview | library/image-crimson-mist-oval/source.png |
| 古典暗纹 · 原版 | image | archive | library/image-ancient-dark-pattern-original/source.png |
| 古典暗纹 · 椭圆版 | image | static_preview | library/image-ancient-dark-pattern-oval/source.png |
| 紫色星尘 · 原版 | image | archive | library/image-violet-stardust-original/source.png |
| 紫色星尘 · 椭圆版 | image | static_preview | library/image-violet-stardust-oval/source.png |
| 哥特女巫 | head | static_preview | library/head-gothic-sorceress/source.glb |
| 哥特女巫 · 首版 | head | archive | library/head-gothic-sorceress-early/source.glb |
| 银发精灵 · 首版 | head | archive | library/head-silver-elf/source.glb |
| 仿生人 · 首版 | head | archive | library/head-android/source.glb |
| 蒂法风格·新原件 | head | static_preview | library/head-tifa-premium/source.glb |
| 里昂风格·新原件 | head | static_preview | library/head-leon-premium/source.glb |
| 暴风女风格·新原件 | head | static_preview | library/head-storm-premium/source.glb |
| 阿斯代伦风格·新原件 | head | static_preview | library/head-astarion-premium/source.glb |
| 亚玟风格·研究原件 | head | archive | library/head-arwen-premium/source.glb |

### 8.3 不得误当正式角色的候选

交接包experiments/model-candidates带三个现有检查点的candidate原件和checkpoint.json：玛奇玛native-eye-final、摩尔converged-final、宿傩converged-final。其检查点均记录artistAccepted=false、productionAccepted=false、deviceTested=false；玛奇玛技术门记录technicalPassed，另两项记录technicalGatePassed。它们未替换V60运行目录。

候选模型SHA分别为ae500931a05611134de8abed42ede0ea42ebd5cce1f7df04014034204d078f76、f825460a419a9556403fef27781a3402d1f9f18698dc73eaa6b3b6cfc3738c02、ece17f1c3c1375a0ea8fe5b565d83f04268bf436d23d195a226f1bf82093d59d。检查点内的绝对路径指原电脑；包内只带指定候选与摘要，完整runtime姿态和作者历史仍在model-quality-audit原目录。

Sephiroth嘴部、Geralt法线、里昂眉部、背景门架等还有未晋升研究。不要按文件修改时间挑一个GLB直接提升；以源SHA、绑定清单、技术门、美术状态和实际渲染路径判断。

## 9 光学校准和保存配置

### 9.1 已保存参数与原点

截至关机前保存核对，运行视点16、单视点400×640、分析档17，摄像头请求640×480，安装旋转0，规范输入反射关闭。光学字段为pitch=10、tan=0.2777777、SUBPIXELS、phase=0、RGB、reverse=false、BOTTOM。这是软件当前解释，不是厂商确认的最终光学参数。

场景配置另有mirror_motion=true、expression_gain=2.5、scale=2.34、camera_span=0.4、zero_plane约0.66、暗红雾背景。运行相机校准字段camera_mirror_interaction=false与场景镜像字段不是同一开关；不要只看一项XML就推断用户最终看到的左右关系。本次只记录非个人标量，私有XML不放交接包。

### 9.2 子像素交织定义

```text
fx = x + 0.5
fy = y + 0.5
Y  = BOTTOM ? fy : height - fy
P  = SUBPIXELS ? pitch : pitch * 3
T  = tan * 3
O  = RGB ? channel : 2 - channel
q  = (fx * 3 + Y * T + O) / P + phase
index = min(count - 1, floor(fract(q) * count))
view  = reverse ? count - 1 - index : index
```

channel仍代表输出R/G/B，BGR只改变物理位置。实现还约束float顺序、负数fract、phase归一化和边界，实际合同以PanelCalibration和panel-calibration-design.md为准。校准路径使用GLES3.20 precise约束运算，不以随机CPU对照代替真实GPU像素检查。

光学对齐仍为UNVERIFIED。必须在原生1200×1920屏幕上用测试图确认pitch单位、视点顺序、RGB/BGR、phase、Y原点及串扰，再结合真实观察位置。手机视频、缩小截图或软件零差不能证明裸眼3D已对齐。

### 9.3 四份配置的归属

| 应用私有路径 | 保存内容 |
| --- | --- |
| shared_prefs/mirror-runtime.xml | 分析档、视点预算、光学与相机安装配置 |
| shared_prefs/mirror-scene-view.xml | 模型取景、背景、场景镜像和表情强度 |
| shared_prefs/bundled_avatar_selection_v1.xml | 显式包内角色选择 |
| files/avatars/state.json | 本地导入角色的current/previous状态 |

V60安装与操作检查后四份原字节保持。不要用清数据、卸载或覆盖未知XML作为日常调试方法；读取旧版本/损坏配置时沿用现有迁移与草稿合同，只有显式保存才覆盖。个人中性和眉眼口基准为会话状态，不伪称在新session仍有效。

## 10 构建环境和依赖恢复

### 10.1 工具版本

| 工具 | 当前使用 |
| --- | --- |
| JDK | Java17，原机C:/Program Files/Java/jdk-17 |
| Gradle | 8.5；工程没有gradlew wrapper |
| Android Gradle Plugin | 8.2.2 |
| Android SDK | platform35、build-tools35.0.1 |
| App配置 | compileSdk35、minSdk24、targetSdk30、arm64-v8a |
| MediaPipe | tasks-vision1.0.0 / tasks-core1.0.0，本地Maven |
| 原生重建 | NDK r25c；应用使用既有九库可直接打APK |
| 模型作者环境 | Blender4.5.12 LTS，Python常用NumPy/Pillow |

原Gradle位置为C:/Users/lNessie/.gradle/wrapper/dists/gradle-8.5-bin/5t9huq95ubn472n8rpzujfbqh/gradle-8.5/bin/gradle.bat。该本机路径不作为其它电脑的安装位置。

### 10.2 必须补齐的本地依赖

settings.gradle优先查工程maven目录；原机保存tasks-core与tasks-vision1.0.0的AAR和POM，共四个文件、约23MB。此前GitHub工程未上传该目录，本交接包补入project/maven并记录哈希。不要在换机时只拷源码而漏掉它们，也不要擅自改成新MediaPipe版本来消除依赖错误。

测试JSON库为org.json:json:20240303，SHA256为3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed，位于交接包tooling-libraries。保留上游Public Domain来源说明。该JAR是JVM测试依赖，不打进APK。

从project根目录准备默认测试路径：

```powershell
New-Item -ItemType Directory -Force './app/build/avatar-tests' | Out-Null
Copy-Item -LiteralPath '../tooling-libraries/json-20240303.jar' `
  -Destination './app/build/avatar-tests/json-20240303.jar'
```

其余Gradle/Google/Maven依赖仍需已有缓存或首次联网获取；本交接未在全新无缓存电脑重建，不能称整个工具链完全离线可复现。生产15项模型/二进制依赖已经按固定字节打包，清单保留原基准与声明。

### 10.3 先构建Java应用

从解压工程根目录执行，路径按接手电脑修改。

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
$mirrorGradle='C:/Tools/gradle-8.5/bin/gradle.bat'
Set-Content -LiteralPath './local.properties' `
  -Value 'sdk.dir=C:/Android/Sdk' -Encoding ascii
& $mirrorGradle --no-daemon :app:assembleDebug
& $mirrorGradle --no-daemon :launcher:assembleDebug
```

已有固定依赖缓存的原机可加--offline；新电脑不应在缓存未准备时强行离线。输出为app/build/outputs/apk/debug/app-debug.apk与launcher/build/outputs/apk/debug/launcher-debug.apk。交接APK是已部署debug基准，不是最终商业release签名构建。

### 10.4 原生与RKNN重建

普通接手先保留app/src/main/jniLibs中的匹配库，不必重建。确需改JNI时，在独立工作分支使用scripts/build_native.ps1，显式指定NdkRoot与RgaLibrary；该脚本只写应用输出目录，不替换设备vendor库。核对native/DEPENDENCIES.md、RGA哈希、libc++与librknnrt，完整回归后再安装。

RKNN转换依赖旧Toolkit2 1.3.0及Python3.8环境，原机隔离环境保存在native-tools。新生成模型先单独检查算子、输入布局、输出数值、ROI和runtime兼容，再接入；不能把同名不同版本的RKNN文件替换固定生产模型。

## 11 安装调试和恢复

### 11.1 接回设备

设备此前已允许用户关机，本交接整理不启动或连接设备。下次开机后用原Type-C OTG调试口连接，保留摄像头在Host口。

```powershell
$mirrorAdb='C:/Users/lNessie/platform-tools-latest-windows/platform-tools/adb.exe'
$mirrorSerial='6L32552009566714'
& $mirrorAdb devices -l
& $mirrorAdb -s $mirrorSerial shell pm path com.mirror.bench
& $mirrorAdb -s $mirrorSerial shell dumpsys package com.mirror.bench
& $mirrorAdb -s $mirrorSerial shell am start -W `
  -n com.mirror.bench/.MirrorHomeActivity
```

先核对devices为device而非offline/unauthorized，包版本60/0.2.29，屏幕原生尺寸。若设备亮屏却无ADB设备，检查OTG口、可传数据的线、USB调试授权与Windows驱动；普通应用调试不应刷loader或更换固件来尝试恢复。

### 11.2 更新与应用回退

不需要重装时保留设备现有V60。需要安装同一证书APK时才执行：

```powershell
& $mirrorAdb -s $mirrorSerial install -r `
  './release/AvatarRuntime-v60-crafted-motion-v3.apk'
```

先检查APK SHA和签名证书；另一台电脑自动生成的debug证书通常不同。遇到INSTALL_FAILED_UPDATE_INCOMPATIBLE应取得原证书或保留现有安装，不能为了覆盖而直接卸载。原签名密钥未上传，也不装入本交接包，应由拥有者安全保管。

应用级回退只针对保留的同签名、兼容旧配置APK；debug包的允许降级可用install -r -d，先做配置备份并确认迁移兼容，不能默认回退任意历史包。最直接的恢复基准是本包V60，不需要刷固件。

### 11.3 系统精简与原厂恢复

早期精简采用pm disable-user --user 0停用经审查预装包，保留原APK、数据及3D参数。默认HOME切到com.mirror.launcher后才停用原桌面。没有写boot/system/vendor/recovery/loader分区；备份的APK与参数不是完整固件备份。

交接包device-recovery/cleanup-journal.json保存原enabled状态和原HOME。需要恢复原厂桌面时，从交接包根目录执行，按新电脑调整ADB路径：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File './project/scripts/Restore-Factory.ps1' `
  -AdbPath 'C:/Tools/platform-tools/adb.exe' `
  -JournalPath './device-recovery/cleanup-journal.json'
```

脚本核对设备序列号与日志，恢复原com.softwinner.launcher及记录的包状态。不要在没有原日志的其它板子上直接套用；也不要为了恢复HOME停用设置、ADB、相机provider或SurfaceFlinger。

### 11.4 数据备份与关机

运行相机和面捕不应通过隐式后台服务继续工作；正常返回首页、停止测试并检查状态。保存明确需要保留的设置，使用现有配置备份入口或保持四份私有文件的精确副本；二进制文件不要用旧PowerShell文本重定向复制。

关机前可执行指定设备的adb shell sync，再正常关机。2026-10-12已做一次该检查，原工程、APK、实验文件与恢复说明保存在电脑。原机保存了180项实验工作文件ZIP和working-diff.patch；不使用stash、reset或清理来隐藏这些工作内容。

## 12 验证证据和真实性能

### 12.1 关键结果

| 证据 | 条件与结果 | 能证明的范围 |
| --- | --- | --- |
| 早期20视点诊断网格 | 240×720/视点、真实USB采集+录像参考完整面捕，5分钟约34.15呈现FPS | 简化诊断网格特定负载，不是当前PBR角色成绩 |
| V52宿傩联合回放 | 16个400×640视点，主要确认34.614秒，331次呈现，9.559FPS | 真实PBR角色+NPU链；不是本人USB；完整活跃门false |
| V55最终杰洛特短测 | 16个400×640视点，确认14.731秒，152次呈现，10.332FPS | 该版本已确认互动窗口，不是30秒连续达标或V60测量 |
| V58资产绘制 | 317项检查；serial/OVR零差，individual/batch在既定容差内 | 候选固定姿态绘制路径一致性，不是新旧模型相同或FPS |
| V59 UI短测 | 正常动画p50 18ms、p90 61ms，仍有慢帧 | UI单独短操作；不能换算持续60FPS |
| V60 UI与资源门 | 真机导航/跳过/暂停恢复/关闭动画通过，静置帧计数稳定；99资产与9库保持 | UI动效与部署保持，不覆盖面捕/NPU/3D并发 |

V52主要区间系统CPU约51.17%、GPU98.18%、NPU39.97%，PSS平均266.85MiB、最高385.34MiB，温度62.22-65.63摄氏度；面捕会话约16.20FPS、99.83%有脸，但会话与互动资源不是同一原子窗口。

V55最终确认区间GPU98.29%、整机CPU62.64%、NPU44%；PSS190.87MiB仅两个互动样本。未确认尾段约4.855秒排除，不能把30秒请求等同于30秒有效互动。不同输入阶段、时长、温度和材质条件限制跨版本比较，不能称这些数字证明内存下降或性能提升。

16个400×640 RGBA8视点的一套颜色面约15.625MiB；双套约31.25MiB，尚不含深度、纹理、资产解码、驱动及额外FBO。PSS含可回收录像mmap页面，不等于独占分配；更大RAM不能直接解决GPU接近满载的问题。

### 12.2 测量规则

相机采集FPS、有效完整推理FPS与实际呈现FPS必须分开。真实相机无脸时推理负载降低，不能把无脸高FPS当完整478/52成绩。replay不包含USB采集或MJPEG解码；camera_replay才同时采集相机并用录像供面捕，其额外转换应计入。

最终呈现使用SurfaceFlinger latency实际时间戳在确认的INTERACTIVE区间统计，检查覆盖、缺口、尾段和完整人脸输出；GL回调、CPU提交、glFinish等待或GIF20fps都不能替代该值。记录APK/模型/schema SHA、视点数与尺寸、输入来源、NPUbackend、材质路径、温度和时间窗口。

两小时测试已按用户指示跳过；不把跳过记为通过，也不在交接后自动要求重做两小时。长期稳定性和外壳热环境仍是未完成事项，后续测试时长由实际需要再安排。

### 12.3 可复用检查入口

完成Java构建并提供固定JSON库后，先从工程根目录运行已有离线检查：

```powershell
./tests/run_face_playback_tests.ps1
./tests/run_bundled_avatar_tests.ps1
./tests/run_asset_library_tests.ps1 -AssetsRoot './app/src/main/assets'
./tests/run_avatar_tests.ps1 `
  -AssetPath './app/src/main/assets/avatars/catalog/geralt/character.glb' `
  -JsonJar '../tooling-libraries/json-20240303.jar'
./tests/run_camera_controls_tests.ps1
```

runner默认JDK/SDK路径按参数调整。部分检查依赖已编译classes、历史作者产物或本地真实脸fixture；文件缺失/skip必须如实报告，不能更换输入后仍宣称原哈希固定检查通过。

设备恢复采集并有人脸后，短测真实输入；以下只提供后续复现命令，本次未执行：

```powershell
$mirrorPython='C:/Tools/Python/python.exe'
& $mirrorPython -X utf8 './scripts/run_runtime_check.py' `
  --name handover-camera-short --input camera --seconds 30 `
  --view-count 16 --view-preset 400x640 `
  --output-dir '../device-evidence/handover-camera-short'
```

脚本绑定设备序列号，实验参数不写用户preferences。replay需要自行准备合法的应用私有NV21记录及元数据；个人录像未交付，不能假定在新设备已存在。不要第一次就叠加多个debug材质或渲染开关；先复现原PBR基准，再做同包、同输入的单项对照。

## 13 未合并工作和继续顺序

### 13.1 工作树状态

关机前源码已有3项已跟踪脚本修改和177项未跟踪文件，共180项。主要在asset_pipeline、model_preparation、model_lod、model_quality_audit、sephiroth_preparation、sephiroth_mouth_preparation及相关测试。它们已经保存在原目录并另存unfinished-work.zip、working-diff.patch和哈希清单，未被正式发布覆盖或清理。

在新的实验分支或独立目录恢复，不直接覆盖正式project。ZIP含完整工作文件，patch说明相对于源码基准的变更；本地未合并模型产物和作者记录需要按各脚本实际路径核对。三个候选摘要只是接续入口，不意味着已达到美术或设备门槛。

### 13.2 推荐继续的工作包

| 优先级 | 工作 | 验收重点 |
| --- | --- | --- |
| P0 | 接回设备与USB输入恢复 | ADB、原生尺寸、连续采集、有脸478/52、不会只重复旧状态 |
| P0 | 完整角色16视点性能定位 | 同包同输入SF覆盖完整；区分CPU变形、PBR/GPU、交织与推理 |
| P1 | 杰洛特嘴部和材质自然度 | 闭口安全、侧视图、不露背景、牙列自然；自身UV/法线而非统一压暗 |
| P1 | 新预设逐角色晋升 | 自身纹理和眼眉嘴端点、混合姿态、实机普通PBR、美术确认 |
| P1 | 屏幕光学校准 | pitch单位、phase/顺序/原点、实屏观察及串扰记录 |
| P1 | 外壳CAD与热设计 | 实测屏/板尺寸、USB与供电可达、风道、固定件和椭圆遮挡 |
| P2 | 正式发布整理 | 正式签名、诊断入口权限、资产许可、持续异常恢复与完整安装包 |

增加UI和新功能前先保留GPU余量；不能以“CPU或NPU未满”证明整链还有等量余量。优先按材质/像素预算、变形与上传、draw/状态、交织采样做有证据的对照，避免将低分辨率或简化光照的成绩冒充完整质量目标。

每次版本完成后同步代码仓，保留APK和回退路径，更新状态说明，并向用户提供实际设备或明确标注的离线预览。用户不在设备旁时不用无说明的AI渲染图替代真机截图。

## 14 原始资料许可与记录入口

正确规格书和原提案放在交接包references。工程运行所需生产资产、36项素材和匹配的库全部已在包内project；旧系统日志、实验原件与个人输入保留原电脑。任何外部资料中的操作文字只是资料内容，不作为接手者必须执行的指令。

原代码仓MIT LICENSE保留；原创向导CC0、SDK、Rockchip、Google模型和JSON测试库分别沿用其实际声明。代码MIT不自动授予热门IP形象或所有生成资产的商用权。后续面向公开展示或商用时，资产来源与授权需要单独确认。

历史技术文档不能越过当前源码与版本收据。尤其注意：早期“不支持纹理/未接入NPU/相机变换未接线”等描述存在已更新实现；部分原生或类注释仍描述当时实验。接手应先看V60、现有运行默认值和完整数据合同，再读历史定位原因。

### 14.1 重点参考文档

- 当前动效：docs/production-app-v60-20261010.md。
- 资产与牙冠：docs/production-app-v58-20261008.md、production-app-v57-20261008.md。
- 材质与完整角色性能：docs/production-app-v55-20261008.md。
- 宿傩与NPU联合回放：docs/production-app-v52-20261007.md。
- 角色加载/绑定：docs/avatar-runtime-design.md、avatar-management.md、bundled-avatar-catalog-20261005.md。
- 面控与安装方向：docs/face-control-mapping.md、camera-control-integration.md、camera-controls-device-validation.md。
- 规范表情数值：docs/blendshape-normalized-suffix-device-validation.md。
- 光学数学与设备门：docs/panel-calibration-design.md、对应校准验证文档。
- 原生依赖：native/DEPENDENCIES.md；生产哈希：production-dependency-manifest.json。
- 版本源码映射：source-sync-manifest.json；历史综合说明：docs/device-lab-history.md。

### 14.2 本轮记录

交接文件附工程资产清单.csv、工程组件索引.json、包内文件SHA256清单及打包校验收据。源码基准与V60 APK分开记录；文档整理不改变版本号、不改变模型、不运行设备测试。全部打包内容按已存在文件读取并校验，原始工作树保留。
