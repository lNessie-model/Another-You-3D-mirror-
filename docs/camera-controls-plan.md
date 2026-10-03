# 相机方向、镜像互动与中性校准：下一切片计划

状态：**基础数学已实现，端到端接线与设备验收未完成**。2026-10-03 已增加独立的 [CameraInputTransform](camera-input-transform.md) 和 [FaceControlMapper/Calibration](face-control-mapping.md)，通过主机数学与所有权测试；尚未接入输入、Controller 或校准 UI。下文原始调查与完整验收要求保持。已有标签、数学和 GPU 合成姿态测试不能证明真实人的左右动作、摄像头安装方向或中性标定正确，也不代表 30 FPS 性能目标已经完成。

用户硬指标仍是“16 视点以上、30 帧”。本切片沿用当前 20 个独立视点及真实角色、完整 52 输入，不通过关闭视线/表情、固定姿态、复制视图或减少工作量获得达标数字。当前 20×320×576 与 20×400×720 画质基线分别报告，不能互换。

## 1. 现有链路与证据边界

| 环节 | 源码证据 | 当前行为与缺项 |
| --- | --- | --- |
| 相机选择 | [CameraFrameSource.java](E:/tripo/device-lab/app/src/main/java/com/mirror/bench/CameraFrameSource.java:48) | 优先 external，否则取首 ID；没有读取或应用 SENSOR_ORIENTATION/LENS_FACING，也没有安装方向配置。 |
| 图像转换 | [YuvConverter.java](E:/tripo/device-lab/app/src/main/java/com/mirror/bench/YuvConverter.java)、[rga_convert.c](E:/tripo/device-lab/native/rga_convert.c:33) | 将 YUV/NV21 转 RGBA；现有 RGA 调用仅颜色转换，无旋转或镜像。 |
| 主程序输入 | [MirrorActivity.java](E:/tripo/device-lab/app/src/main/java/com/mirror/bench/MirrorActivity.java:482) | 固定采集 640×480；转换 Bitmap 直接交给 NPU。camera_replay 实际推理输入来自录像，其相机采集用于保留联合负载。 |
| 检测与裁剪 | [NpuFacePipeline.java](E:/tripo/device-lab/app/src/main/java/com/mirror/bench/NpuFacePipeline.java:95) | ROI、landmark 归一化、Z 尺度及 FacePostGraph 的 IMAGE_SIZE 均依赖输入尺寸。ROI 旋转是人脸裁剪变换，不是摄像头安装方向配置。 |
| 输出事务 | [FacePostGraph.java](E:/tripo/device-lab/app/src/main/java/com/mirror/bench/FacePostGraph.java:69)、[BlendshapeSchema.java](E:/tripo/device-lab/app/src/main/java/com/mirror/bench/BlendshapeSchema.java) | 验证 52 项标签/顺序和有限值，复制 pose 的 packedData；尚未显式检查 MatrixData 的 rows、cols、layout。 |
| 控制与失效 | [InteractionController.java](E:/tripo/device-lab/app/src/main/java/com/mirror/bench/InteractionController.java:118) | 平滑全部系数并在失效时衰减；有效时传原 pose，GRACE/WAITING 等非驱动状态传 identity。 |
| 头姿 | [InterlaceRenderer.java](E:/tripo/device-lab/app/src/main/java/com/mirror/bench/InterlaceRenderer.java:473) | 列主序读取，去平移/尺度，拒绝退化、反射和明显剪切，按 Rz·Ry·Rx 提取 Euler；限幅 pitch ±45°、yaw ±65°、roll ±40°。没有 R0 中性基准或互动镜像。 |
| 角色坐标与左右 | [AvatarRig.java](E:/tripo/device-lab/app/src/main/java/com/mirror/bench/AvatarRig.java:61)、[内置清单](E:/tripo/device-lab/app/src/main/assets/avatars/builtin-guide/avatar.json) | 明确 +Y 向上、+Z 朝前、+X 为角色自身左侧、单位米；左右眼/下颌已有独立驱动。清单拒绝未知 mirror 操作，不能悄悄在资产内部再翻一次。 |
| 设置 | [MirrorSettings.java](E:/tripo/device-lab/app/src/main/java/com/mirror/bench/MirrorSettings.java:10) | schema v2，只有运行档位和屏幕光学参数，没有相机或个人中性配置。 |

当前合成 fixtures 能证明“指定 source slot 驱动指定几何、串行与多视图相符”，不能证明真人闭左眼时模型恰好收到正确的解剖侧，亦不能排除两个错误镜像互相抵消。

## 2. 坐标与操作必须分别命名

定义以下四层，状态报告也应分别记录，禁止一个含混的 `mirror=true` 同时控制它们：

1. **原始采集图像**：相机交付的像素顺序及尺寸。
2. **推理规范图像**：经过一次安装方向旋转、必要时校正硬件自带左右颠倒后，正立、未镜像、左上为原点的图像。所有检测、ROI、landmark 和几何求解使用这一尺寸和坐标。
3. **角色动作映射**：将规范的解剖左右、头姿和个人中性偏置映射到资产控制量。用户选择镜像互动时，只在本层进行动作反射。
4. **屏幕视图选择**：光学校准的 phase/RGB/BGR/reverseViews 等，仅决定子像素采样哪张独立视图。它不是相机方向或动作镜像开关。

原始相机本身已经镜像时，输入归一化中的反射是纠正数据方向；角色动作镜像是用户选择的交互方式。两者用途不同，必须分别显示和记录，不能依据最终“看起来正常”便认定每一层正确。相机预览不能另行隐藏一次翻转；调试页必须让用户看清当前展示的是原始还是规范图像。

SENSOR_ORIENTATION、LENS_FACING 和显示方向可用于建议值与诊断，不能把 external 相机或板子的声明自动当作安装事实。采用可读字母和方向标记确认最终方向。[Android Camera2 预览方向文档](https://developer.android.com/media/camera/camera2/camera-preview)解释了相机传感器、显示与预览方向之间需要明确变换。

## 3. 最小完整用户流程

从维护页增加“相机与动作校准”，完成以下操作后才允许保存草稿：

1. 显示实际 camera ID、采集尺寸以及原始/规范图像标识。提供安装旋转 0/90/180/270° 和“校正摄像头自带左右颠倒”。让用户用正立的字母 F 或带文字的方向卡确认画面方向；禁止只用左右对称的人脸做此确认。
2. 显示规范相机画面、左右动作监视值和真实角色预览。提供解剖同侧映射/镜像互动选择；同时用屏幕位置及动作说明展示区别，不能仅显示“左/右”两个抽象名称。
3. 引导用户正视、放松、睁眼、闭口，点击采集中性。收集约 2 秒连续、新鲜、有脸结果；验证样本数、姿态稳定度和眼口状态后展示成功或可理解的重试原因。具体阈值是待验证参数，不能凭计划中的秒数就声称标定可靠。
4. 用户依次测试左眨眼、右眨眼、张嘴、左右看和转头，再保存。保存、取消、恢复默认都是草稿事务；失败或取消不得覆盖原配置。无脸时仍能设置图像安装方向，但不能伪造“中性采集成功”。

相机安装方向和互动模式是安装设置，可以持久化；个人眼口/视线偏置首版明确为当前会话，换人或开始新会话不沿用。若要将头部正视基准作为安装设置持久化，必须明确其作用域、采集条件、失效条件及重置入口，不能混同于永久记住某个人的表情。不得偷偷自动把用户正在做的表情学习为中性。

校准页复用现有真实 AvatarGpuScene 单视图预览和固定物理比例。页面的相机/NPU工作必须与主程序共享进程级硬件所有权：先请求旧工作停止，后台等待所有权；不能在 UI join，不能另开一套相机来抢占。离页/HOME 发出停止，晚回调按 session/配置 revision 拒绝。预览图像用有界、明确所有权的缓冲，不能让 UI 读取推理线程正在写的 Bitmap。

## 4. 控制数学与放置位置

### 4.1 旋转基准

提取并验证原 pose 的正交旋转 R(t)，去掉平移和统一尺度。中性采样给出有效旋转 R0。固定列向量约定：

```text
R(t) = R0 · Δ
Δ = transpose(R0) · R(t)
```

这定义的是中性头部局部坐标中的增量。不要逐项减 Euler，也不要无说明地改成 `R(t) · transpose(R0)`；复合旋转下两者不同。中性旋转估计使用归一化 quaternion/rotation 的统计方法，并处理 quaternion 正负同义；不能逐元素平均矩阵后直接当旋转使用。

若源坐标与资产坐标需要基变换 C，在统一映射层显式执行 `C · Δ · inverse(C)` 并记录契约。当前资产约定与预期 canonical 几何方向相符，但真人动作方向仍待验收；中性基准不能用来掩盖错误的坐标基或源图像镜像。

镜像互动使用：

```text
S = diag(-1, 1, 1)
Rout = S · Rmapped · S
```

结果仍是旋转，不在模型 root 上设置负尺度，避免绕序、法线和自动取景合同被破坏。随后进入现有 Euler 提取、角度限幅和头姿平滑。不要在图像规范化之后又按 SENSOR_ORIENTATION 重复旋转头姿。

### 4.2 左右表情置换

采用经过语义核对的显式置换表，不在运行时凭字符串猜测未知输入。当前 schema 的 20 对为：

| 左 | 右 |
| --- | --- |
| browDownLeft | browDownRight |
| browOuterUpLeft | browOuterUpRight |
| cheekSquintLeft | cheekSquintRight |
| eyeBlinkLeft | eyeBlinkRight |
| eyeLookDownLeft | eyeLookDownRight |
| eyeLookInLeft | eyeLookInRight |
| eyeLookOutLeft | eyeLookOutRight |
| eyeLookUpLeft | eyeLookUpRight |
| eyeSquintLeft | eyeSquintRight |
| eyeWideLeft | eyeWideRight |
| jawLeft | jawRight |
| mouthDimpleLeft | mouthDimpleRight |
| mouthFrownLeft | mouthFrownRight |
| mouthLeft | mouthRight |
| mouthLowerDownLeft | mouthLowerDownRight |
| mouthPressLeft | mouthPressRight |
| mouthSmileLeft | mouthSmileRight |
| mouthStretchLeft | mouthStretchRight |
| mouthUpperUpLeft | mouthUpperUpRight |
| noseSneerLeft | noseSneerRight |

12 个非左右成对输入保持所属语义，包括 `_neutral`；其数值不作为额外几何形变。`eyeLookIn/Out` 只交换所属眼，不能再把 In/Out 对调。置换应覆盖完整 52 索引、无重复、是 involution（执行两次恢复原值）。这张表说明实现合同；真人单侧动作仍要独立验证，不因名称匹配就跳过。

### 4.3 个人眼口与视线中性

使用原始、新鲜、独立序号的样本收集中性，不重复采样同一个 Controller 快照凑样本数。眼口系数候选归一化为 `clamp((w-b)/(1-b),0,1)`；只有通过中性有效性检查的有限 b 才能使用，接近 1 的基准必须拒绝，不能制造除零或过大增益。此公式属于候选，必须检查闭眼/张口仍能达到完整动作，且不破坏 jawOpen×mouthClose 等组合。

视线应按每眼有符号水平/垂直控制量处理基准，保留 In/Out、Up/Down 的方向和原有最大角度；不能把左右眼合并成一个值。基准扣除与动作镜像的顺序固定为“规范解剖坐标中扣除个人基准，再镜像置换”。暂不加入未标定的自动增益、自适应漂移或额外滤波；原有 Controller/renderer 已分别承担系数和头姿平滑。

### 4.4 失效中性不能再次校准

**关键接线约束**：Controller 非 drive 状态已经产生零系数和 identity。如果在 renderer 对每个 Snapshot 无条件执行 `transpose(R0) · pose`，丢脸时会变成 `transpose(R0)`，导致角色不能回正。

建议新增纯 Java `FaceControlMapper`，只在 Controller 的 `drive=true` 路径，将当前有效 raw52/rawpose 映射为目标控制量，再使用现有平滑。非 drive 直接使用映射后的中性：零系数、identity。FaceFrame 继续保留原始推理结果，Snapshot 文档明确其 pose/系数是映射后的控制量。中性采集器从原始 FaceFrame 读样本，避免校准自己的已校准输出。

配置作为不可变快照原子发布。变更安装旋转或输入反射时重建输入会话并重置 ROI、FacePostGraph 平滑和 pending 输出；不能在旧尺寸 ROI 上直接切新图像。镜像、基准及表情必须采用同一个 revision，不得头姿与表情各读取一次可能变化的设置。

## 5. 最小代码分解与实施顺序

前两项纯数学类现已存在且默认运行未接线；其余采集器、设置与界面仍待实现：

1. `CameraInputTransform`：纯数学的四种旋转及反射合同、尺寸变化、正反坐标变换和像素参考；默认恒等路径原样保留。实际图像实现复用现有 RGA/native 能力或预分配缓冲，必须先做像素对照。避免首版顺带重写已验证的裁剪采样数学。
2. `FaceControlCalibration` / `FaceControlMapper`：不可变配置、有效旋转提取、中性相对旋转、显式左右置换；带确定性测试后接入 Controller 的有效输入分支。
3. `NeutralCalibrationCollector`：有界采样、单调时间/序号、稳定性与拒绝原因、取消及超时；先完成纯 Java 时间序列测试，再接实际推理。
4. 版本化设置与校准页面：共用硬件所有权、草稿事务、预览、错误/重试和后台释放；通过端到端方向验收后才标记校准成功。
5. 原始数值状态、确定性回放及真机性能回归：记录源/设置身份和完整工作量，保留当前默认路径作为对照。

输入归一化的 90/270° 变换需要同时更新 detector、mesh ROI、normalized landmark 的宽高/Z 尺度和 FacePostGraph IMAGE_SIZE。采用完整规范 Bitmap 时，上述流程自然使用其尺寸；采用融合裁剪优化则必须证明全部步骤使用虚拟规范尺寸。不能只旋转预览，或只修改最终 pose 来补偿倒置的人脸检测输入。

640×480 RGBA 的一份缓存约 1.172 MiB；需要多少份由预览/推理所有权确定，必须固定上限。额外全图变换会增加实际带宽与时间，因此量化转换耗时；不能把“使用 RGA”当作零成本。首版不为性能省略方向校正，后续如融合进现有 crop 则独立做字节和数值回归。

## 6. 设置、录像与迁移

- 持久设置至少携带 schema、camera ID、采集尺寸、可取得的设备特征、旋转、输入反射、互动模式及 revision。Camera2 ID 不保证是稳定的 USB 物理身份；特征不匹配或多相机歧义时应提示重新确认，不能自动套用旧标定。
- **现有迁移陷阱**：MirrorSettings.decode 仅在 `version == SCHEMA_VERSION` 时读 panel。直接把版本 2 改为 3 会使旧 v2 的自定义 panel 参数落为默认值。必须显式迁移并测试 pitch/tan/phase/units/RGB-BGR/reverse/origin 原值保留；未来版本仍不覆盖未知配置。
- `replay`/`camera_replay` 的推理来自录像，方向属于录像元数据，不能使用当前 USB 的安装配置。旧录像无元数据时保留明确的 legacy 恒等约定并在报告标识，不能根据现场相机状态猜测。
- 设置保存或切换必须是完整事务。失败保留旧配置，取消不写盘；恢复默认先改草稿，不暗中删除用户校准。角色切换不应重置硬件安装设置；个人基准的作用域应可见。
- 默认仅保存数值校准和验证报告。若沿用已授权的测试录像，标记来源及 clip/profile hash；新的真人动作验收影像按任务授权保存，不将屏幕展示自动变成后台录像。

建议状态字段包括实际相机/规范输入尺寸、安装变换、输入方向来源、互动模式、校准 revision/作用域/有效性、采样拒绝原因、原始与映射后头姿/选定左右动作数值。高频完整数组仅用于有界诊断，日常报告保留摘要，不能无限积累帧。

## 7. 完整验收计划

### 7.1 图像规范化

- 非对称四色角点、可读字母 F、方向箭头、非方形尺寸和边界像素覆盖全部 4×2 组合；验证输出尺寸、正反映射、边界与像素位置。
- YUV stride/pixel stride、NV21 色度顺序、RGBA 通道及90/270°宽高交换有独立 fixture；不能用纯灰、对称脸或正方形图掩盖错误。
- 每个组合变换后与规范参考图比较，默认恒等路径保持现有转换和推理结果。加速实现对参考实现做字节对照，不仅检查检测到脸。
- 方向切换后确认新 NPU ROI/post 图会话建立，旧结果不能进入新 revision。

### 7.2 纯控制数学与时间序列

- 检查 pose rows=cols=4、COLUMN_MAJOR、有限值及旋转有效性；错误布局/长度、反射、剪切、退化、非有限值必须可诊断，不能随机产生头姿。
- 用非交换复合旋转构造 `Rraw=R0·Δ`，例如 R0=(-8°,17°,11°)、Δ=(12°,-23°,7°)，校正应恢复 Δ；同时验证错误乘法顺序确实会被测试识别。
- 中性映射为 identity；镜像使用 S·R·S，保持正交且 determinant≈1，镜像两次恢复原输入。各单轴和混合姿态不得改变已有限幅合同。
- 52 索引置换无遗漏/重复，12 非成对项保留，左右眨眼和两眼四向视线独立；极值、零值、组合 corrective 和完整动作范围都要验证。
- 采样重复序号不能累计；样本不足、丢脸、间隔过长、晃头、闭眼/张嘴、过期结果、取消和超时不能成功提交基准。quaternion q/-q 混合不能相消为退化旋转。
- 非 drive 状态绝不校正合成 identity；GRACE、WAITING、ERROR、过期输入均回到真正中性，重新获取不继承陈旧姿态或半次采集。

### 7.3 真人端到端方向

固定光照与摄像头位置，使用明确的真人解剖侧标记，例如被摄者自身左侧贴色标，并让字母 F 保持可读。依次采集左眨眼、右眨眼、双眼、左右看、左右转头/倾头、张嘴、向左右移动下颌。每段同时对应：

1. 原始采集图像与安装标记；
2. 规范输入图像、原始 source 系数和原始 pose；
3. 映射后的动作与当前 revision；
4. 真实角色中心和两端视点。

验收依据是具体身体动作和图像位置，不是 source 名称、测试 fixture 名字或“看着像”。尤其要验证镜像互动开关只反射一次、In/Out 不乱换、头姿与单侧眨眼方向一致。对无法独立眨眼的测试者应换可辨识的单侧动作/既有带标注录像，不能把缺乏区分度的数据标为通过。既有 GPU 单视图/OVR 逐层一致性仍需保留，但它不能替代本项。

### 7.4 用户流程、生命周期与配置

- 保存、取消、恢复默认、保存失败及未来 schema；特别覆盖 v2 自定义屏幕校准的无损迁移。
- 采集中 HOME、返回、USB断开、权限失败、相机初始化超时、旧结果晚到和快速改方向；不能同时打开两套输入、UI等待join或永久忙碌。
- 校准页预览缓冲不能被并发改写；关闭后停止相机/NPU，新会话只有自己的结果可更新 UI 或角色。
- replay 与 camera_replay 按各自录制方向解释，现场旋转设置不会污染历史性能对照。
- 换人/新会话清除个人偏置，硬件设置保留；错误基准可以明确重置，不自动静默漂移。

### 7.5 工作量与性能

保持相同 APK、实际 backend、模型和映射 hash、独立视点数、每视点尺寸、输出尺寸、完整表情/头姿/视线及面捕输入。分别测试恒等输入与实际旋转路径，报告转换时间、face FPS、morph/upload/pose age、CPU/GPU/NPU、PSS和温度。

仍使用确认 INTERACTIVE 区间内的 SurfaceFlinger actual 时间戳判定呈现 FPS，记录采样缺口和未确认尾段；不能混入待机、仅报 renderer callback 或将 29.x 四舍五入为 30。方向/中性验收通过不构成光学校准、美术或持续30 FPS通过，亦不替代长时测试。

## 8. 官方来源与版本边界

以下官方资料于 2026-10-03 查阅，帮助明确合同，不能替代当前 tasks-vision 二进制和实机行为验证：

- [MatrixData proto](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/framework/formats/matrix_data.proto)：默认列主序，也存在 ROW_MAJOR 枚举；生产入口应显式确认布局，不能只信数组长度。
- [FaceGeometry proto](https://raw.githubusercontent.com/google-ai-edge/mediapipe/master/mediapipe/tasks/cc/vision/face_geometry/proto/face_geometry.proto)：pose 映射 canonical 模型到 runtime face，描述旋转/平移/统一尺度及最后一行合同。
- [Face geometry graph](https://raw.githubusercontent.com/google-ai-edge/mediapipe/master/mediapipe/tasks/cc/vision/face_geometry/face_geometry_from_landmarks_graph.cc)：默认环境为 TOP_LEFT、虚拟相机及实际 IMAGE_SIZE；这不是用户 USB 相机内参完成标定的证据。
- [Geometry pipeline](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/cc/vision/face_geometry/libs/geometry_pipeline.cc)：屏幕到 metric 坐标过程中处理原点与手性，并通过 MatrixDataProtoFromMatrix 打包姿态；不能在下游凭“图像Y向下”再重复翻转。
- [Face blendshape graph](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/cc/vision/face_landmarker/face_blendshapes_graph.cc)：标签 schema 的官方来源；标签表不构成真人左右验证。
- [Android Camera2 preview](https://developer.android.com/media/camera/camera2/camera-preview)：传感器、显示和预览方向的关系。external 模组仍需安装现场确认。

当前 app/build.gradle 声明 `com.google.mediapipe:tasks-vision:1.0.0`；上述 master 链接会变化。实施时记录实际模型/metadata/依赖版本与 hash，优先对运行输出验证 rows/cols/layout 等合同。所有本节所述方向、个人中性和用户流程仍是待实施验收项。
