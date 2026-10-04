# 可替换角色与完整面捕接入设计

状态（2026-10-03）：标准 GLB 内置角色、受限 loader、完整 52 项快照、schema v2 rig、CPU 形变及真实 GLES 绘制已接入主程序；生产原型资产 SHA-256 为 `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407`。v9 的 `AvatarPackageStore`、`AvatarManagementActivity` 及主程序角色管理入口已完成两包 SAF 导入、预览后显式启用、无效 ZIP 拒绝、上一版回退、重启持久选择和恢复内置的设备验证，具体证据及未测生命周期见第 9 节与[角色管理验收记录](avatar-management-validation.md)。v8 合并绘制的 1,380 层 GPU 比较在预设容差内通过，但 90 秒呈现为原路径 29.3158 FPS、合并路径 27.8562 FPS，默认继续使用原逐 primitive 路径；v9 shader 的独立像素核对亦通过，但反向顺序合并/原路径仍只有 27.0074/29.3386 FPS，未达持续30 FPS。角色仍是美术原型；完整 texture/skin、导出、全部组合美术验收、真实角色持续 30 FPS 及 30 分钟/2 小时耐久尚未完成。以下同时保留完整交付要求和未完成约束，不把局部通过等同于项目完成。

## 1. 交付目标与当前差距

项目提案要求一个完整虚拟角色，能跟随转头、独立眨眼、视线和张嘴；支持角色/主题替换；完整经过待机、唤醒、互动、离开。用户硬指标是“16 视点以上、30 帧”。原画质基线保留为 20 个独立视点、每视点 400×720、原生竖屏输出 1200×1920；当前主程序默认档位及 v3 同 APK 对照为 20×320×576，须单列结果，不能当成相同画质基线的性能提升。20 视点不是用户不可调整的硬约束；其它视点数或尺寸必须单独声明并重新验收。正式资产、长时稳定和实屏光学效果分别验证。

目前已实现与仍存在的差距：

- `BlendshapeSchema` 和 `FacePostGraph` 校验完整标签；`FaceFrame` 与 `InteractionController.Snapshot.blendshapes52()` 保留左右独立的 52 项、姿态、序号及接收/完成时间。旧 `renderWeights()` 四维出口只为 benchmark/诊断兼容保留。
- 内置 `builtin-guide/character.glb` 已走 `AvatarGlbLoader`、`AvatarRig`、`AvatarDeformer` 和 `AvatarGpuScene` 的正式读取/绘制路径。当前为 15,085 顶点、29,482 三角形、7 个真实 primitive；具有头/颈肩、独立眼球眼睑和嘴唇口腔牙舌。52 个几何目标由 43 个直接目标和 9 个组合 corrective 构成，另有 8 个刚性视线动作；不是“52 个表情全部美术完成”。
- 正式角色每个动画快照只变形一次并重算法线，各视点共享结果；同步 GL 线程与有界 CPU worker 均有同 APK 对照入口。真实 primitive 按各自材质/节点绘制。旧椭球、固定四属性和等分索引路径仍是诊断基线，不用于宣称真实角色验收。
- 纹理数组、四视点一批的 `OVR_multiview2`、独立投影与最终交织继续使用；`AvatarFraming`/`AvatarGeometryBounds` 根据已验证资产包络处理竖屏取景。v4 的 69 姿态×20 图层核对通过，具体范围见第 9 节。
- 本地 ZIP 的有界导入事务、真实单视点预览、精确包激活/上一版回退/内置回退及主程序重新读取选择已实现；v9 已实测两份不同 GLB SHA 的测试包通过 SAF 导入、预览和切换，以及无效包保留当前、回退及重启持久化。尚未实测实际阻塞提供方、IO/提交期间 HOME 或同一管理页 EGL 恢复，也不是原场景内无缝热切换。具体入口与生命周期见[角色管理说明](avatar-management.md)。
- v3 GLES 截图已人工检查独立闭眼及张颌闭唇，但组合美术仍为 prototype。texture/skin、导出、相机权限拒绝实机分支、真实角色 30 分钟/2 小时完整长时验收及最终光学校准仍未完成。

## 2. 首个内置角色：完整的风格化头部半身像

选择一个中性、略卡通的“镜中向导”：清晰的鼻梁/鼻翼、脸颊、下巴和耳朵，短发或头饰，完整后脑勺、颈部和简洁肩部；较大的独立眼球帮助用户看清眨眼和视线。皮肤、头发、虹膜、唇色至少可区分。它是可替换的 GLB 角色，不是交织调试图。

必须具备以下几何结构：

| 部件 | 实际结构与验收要求 |
| --- | --- |
| 头部皮肤 | 前脸有鼻、颧骨和下颌轮廓，后脑与颈部闭合。眼窝和嘴部具有真实开口边界，不能把瞳孔/嘴画在椭球表面代替结构。 |
| 左右眼 | 两个完整或可遮挡的眼球，独立虹膜与瞳孔区；各自有绕眼球中心的 gaze pivot。眼球处于眼睑后方，转头和不同视点不会穿出皮肤。 |
| 上下眼睑 | 左右分开，上下缘都有可形变的 edge loop。`eyeBlinkLeft=1` 时左眼闭合，右眼仍睁开；闭合过程中覆盖眼球，不靠缩扁眼球。 |
| 嘴唇/口腔 | 上下唇及唇角有 edge loop，口腔内壁用深色实体，具有上/下牙和简化舌头。张嘴可见口腔，不能露出背景或后脑。 |
| 下颌 | 下颌、下牙和舌头具有共同驱动关系；下唇、下巴和脸颊平滑过渡，不能让整张头皮一起下降。 |
| 眉毛 | 独立左右眉条/厚曲面，支持内侧抬眉、外侧抬眉、下压。可与皮肤 morph 组合，不能只整体上移一条横线。 |
| 头骨与肩部 | 头部围绕颈部上端转动；肩膀保持稳定。不能直接旋转整个半身像。 |

### 2.1 生成方式与产物

已使用本机 Blender 4.5.12 LTS 和 `scripts/build_builtin_avatar.py` 离线构建。脚本、许可证、清单和导出资产同版维护；`scripts/inspect_builtin_avatar.py` 检查导出字节并生成预览，`scripts/compare_builtin_avatar.py` 验证复建的几何、全部 morph 对应关系和三角形绕序。Boolean 可能改变顶点/面编号，故不宣称 GLB 逐字节可复现；清单始终记录实际文件 SHA。设备只加载导出 GLB；可编辑 `.blend` 及检查图保存在 `app/build/builtin-guide-review/`，不是 APK 内的额外角色路径。

生成顺序是：有眼口边界的前脸拓扑 → 鼻/颊/下颌塑形 → 连接头壳与颈肩 → 眼睑/嘴唇 → 眼球/口腔/牙舌/眉毛 → 刚性头/颌附件/眼节点 → 逐个命名 shape key → 配色和基础材质 → GLB 导出。首里程碑不需要 skin：脸部下颌由 morph 形变，下牙/舌头用独立刚性节点同步，头部与肩部分开；颈部过渡通过衣领/头部下缘的重叠结构保证转头不露缝。完整混合蒙皮是后续明确的扩展，不阻塞第一套真实角色。可以使用官方 canonical face OBJ 作前脸点位/拓扑参考，但它不是完整头部，更没有现成口腔、眼球或完整表情资产；若实际采用其数据，保存来源版本、SHA 和许可证。

当前源文件/资产位置如下。小型合法/非法 fixture 及用户导入事务 fixture 已由主机测试构建：真实 ZIP/文件系统/GLB 检查 139 项、管理生命周期门控 24 项、截止时间 42 项、坏摘要适配 6 项通过。生产源码先用真实 Android SDK 35 编译，主机测试另以唯一 `StatFs` 边界替身运行实际 Store/parser；这些测试不替代系统文件选择器和 GPU 的设备行为：

```text
app/src/main/assets/avatars/builtin-guide/
  avatar.json
  character.glb
  thumbnail.png
  LICENSE.txt
  README.md
scripts/
  build_builtin_avatar.py
  inspect_builtin_avatar.py
  compare_builtin_avatar.py
tests/
  AvatarGlbLoaderTest.java
  AvatarDeformerTest.java
  AvatarRigV2Test.java
  AvatarPackageStoreTest.java
  AvatarManagementGateTest.java
  AvatarManagementDeadlineTest.java
  AvatarManagementCatalogTest.java
```

先做可辨识几何和最关键表情的内置 GLB，立刻经正式 loader 显示；随后在同一个资产里补全表情，不能先写长期存在的第二套程序网格绕过 loader。美术上没有验收的自动生成 shape key 不能标记“52 表情已完成”。

## 3. 面捕数据契约：保留 52 项，按名称映射

已引入固定 `BlendshapeSchema`，记录当前 MediaPipe 标签顺序和 schema 版本。`FacePostGraph` 已验证 `ClassificationList` 的标签/索引，与 schema 不符即明确报错，不只凭数组长度接受顺序。`FaceFrame` 存不可变 52 维结果。

`InteractionController.Snapshot` 已提供完整 `blendshapes52()`、序号、接收/完成时间、姿态与有效状态，按单个不可变快照发布；当前没有另建 `AvatarFrame` 类。控制器分别平滑全部系数并在失效时回中，旧 `renderWeights()` 只服务旧 benchmark/诊断路径。镜像/相机方向/中性校准仍须按下面坐标约定明确实施与实屏验证，不能因 52 维快照已接通便称这些校准完成。

MediaPipe 当前 52 项中，索引 0 是 `_neutral`；它不作为额外面部形变累加。因此这里的“52 输入”是 51 个动作系数加一个中性输出，不等同于所有其它平台声称的 52 个动作；例如不能凭空得到舌头伸出系数。官方标签定义见 [MediaPipe face_blendshapes_graph.cc](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/cc/vision/face_landmarker/face_blendshapes_graph.cc)。

以下是内置资产的完整映射要求。带 `Left/Right` 的目标始终分开，不做平均：

| MediaPipe 索引/名称 | 内置驱动 |
| --- | --- |
| 0 `_neutral` | 忽略数值形变；所有动作归零时即资产的中性形态。 |
| 1–5 `browDownLeft`, `browDownRight`, `browInnerUp`, `browOuterUpLeft`, `browOuterUpRight` | 同名皮肤/眉毛目标，左右眉有独立可见位移。 |
| 6–8 `cheekPuff`, `cheekSquintLeft`, `cheekSquintRight` | 同名颊部目标，不通过嘴角位移冒充脸颊鼓起。 |
| 9–10 `eyeBlinkLeft`, `eyeBlinkRight` | 同名眼睑闭合目标。 |
| 11–18 `eyeLookDownLeft`, `eyeLookDownRight`, `eyeLookInLeft`, `eyeLookInRight`, `eyeLookOutLeft`, `eyeLookOutRight`, `eyeLookUpLeft`, `eyeLookUpRight` | 内置资产驱动两只眼球节点旋转；若导入角色提供同名 morph，则由清单选择 morph 路径，不能重复驱动。 |
| 19–22 `eyeSquintLeft`, `eyeSquintRight`, `eyeWideLeft`, `eyeWideRight` | 同名眼睑目标；组合闭眼/睁大测试必须避免眼睑翻转。 |
| 23–26 `jawForward`, `jawLeft`, `jawOpen`, `jawRight` | 首版内置脸部采用同名完整 morph，刚性下牙/舌头按相同控制量移动。后续 joint 模式用颌骨蒙皮及软组织 corrective，不能与完整 morph 重复驱动同一组面部顶点。 |
| 27–31 `mouthClose`, `mouthDimpleLeft`, `mouthDimpleRight`, `mouthFrownLeft`, `mouthFrownRight` | 同名唇与唇角目标。`mouthClose` 收拢嘴唇，不直接把 jawOpen 的颌骨旋转归零。 |
| 32–39 `mouthFunnel`, `mouthLeft`, `mouthLowerDownLeft`, `mouthLowerDownRight`, `mouthPressLeft`, `mouthPressRight`, `mouthPucker`, `mouthRight` | 同名目标；漏斗口型和噘嘴的前伸/轮廓应可区分。 |
| 40–45 `mouthRollLower`, `mouthRollUpper`, `mouthShrugLower`, `mouthShrugUpper`, `mouthSmileLeft`, `mouthSmileRight` | 同名上下唇及左右唇角目标。 |
| 46–49 `mouthStretchLeft`, `mouthStretchRight`, `mouthUpperUpLeft`, `mouthUpperUpRight` | 同名嘴角拉伸、上唇提升目标。 |
| 50–51 `noseSneerLeft`, `noseSneerRight` | 同名鼻翼/上唇侧目标。 |

### 3.1 坐标、眼球、头骨和下颌

清单必须声明资产坐标。内置角色定义：右手系、+Y 向上、脸朝 +Z、+X 为角色自身左侧，摄像机默认在 +Z 侧。左右含义是被观察者的解剖侧，镜像交互只在一个明确的映射层执行；禁止摄像头预处理、mapping 和模型 root 三处各翻一次。

- 眼球初始朝 +Z。在此约定下，左眼绕 +Y 的角度可用 `18° × (eyeLookOutLeft-eyeLookInLeft)`，右眼用 `18° × (eyeLookInRight-eyeLookOutRight)`；绕 +X 的角度为 `15° × (eyeLookDown-eyeLookUp)`。这是首版可调增益，不是眼动仪标定结果；每眼限幅，预留中性偏移。
- 眼睑采用独立命名 morph；blink、wide、squint 在 rig 中做组合限幅/已制作的纠正形变，不能让负开口高度导致上下眼睑交叉。高置信闭眼时应完整遮住虹膜。
- 头姿继续使用已确认的 MediaPipe 列主序矩阵，去尺度/平移，再应用一次坐标基变换与中性校准。头骨 pivot 位于颈部上端，肩部不跟随。现有姿态限幅可以先沿用，再经角色动作验收调整。
- `jawMode` 必须为 `morph` 或 `joint`。首版采用 `morph`：脸部顶点只接受完整 jaw morph；清单中的 `jawAttachmentNode` 仅移动下牙/舌头等未包含在脸部 morph 中的刚性附件，不再次变换脸皮。附件起始最大张角建议 22°，具体轴/正负方向写在清单，上牙固定于头骨。后续 `joint` 模式用蒙皮和 corrective 补形，不同时叠加完整 jawOpen morph 与完整颌骨张角。
- `mouthClose` 与 `jawOpen` 可同时存在，测试组合必须保留“颌骨打开而嘴唇收拢”的能力。

## 4. 资产入口及 JSON 清单契约

存储事务及正式用户入口现已实现，v9 已完成第 9 节所列设备 SAF/切换检查；尚不等于全部异常和生命周期验收。Android 11+ 使用系统文件选择器的 `content://` 临时授权读取一个本地 ZIP，复制到应用私有 `files/avatars` staging；不从任意网络 URL 拉取资源，也不请求全部存储权限。包仅允许平级 `character.glb`、`avatar.json` 及可选 README/LICENSE/thumbnail，具体上限见 [Store 合同与检查](../tests/avatar-package-store.md)。这是对早期分别选择文件/拒绝 ZIP 草案的有界扩展，不允许任意 ZIP 内容或外部引用。严格 JSON、摘要、真实 GLB/rig/形变验证后只发布候选；精确包预览成功并由用户点击“使用此角色”才原子更新 current，同时保留 previous。主程序从管理页任何返回都重新读取 current，以处理提交已发生但成功回调被 HOME 打断的情况；该提交期间 HOME 分支尚未实测。源文件、清单摘要和导入报告导出仍待实现。

下面保留向后兼容的 schema 1 结构示例；不是完整绑定表。当前内置实际使用 schema 2，包含全部 direct 映射和第 9 节的 `requiredRigFeatures`/`derivedBindings`，不能直接用本示例替换生产清单：

```json
{
  "schemaVersion": 1,
  "id": "builtin-guide",
  "displayName": "镜中向导",
  "model": "character.glb",
  "modelSha256": "64位实际SHA256",
  "inputSchema": "mediapipe-face-blendshapes-v1",
  "coordinates": {
    "up": "+Y", "forward": "+Z", "subjectLeft": "+X", "units": "meters",
    "rootScale": 1.0, "headPivot": [0.0, 0.18, 0.0]
  },
  "normalPolicy": "recompute-deformed",
  "materialProfile": "mirror-lit-v1",
  "rig": {
    "headNode": "Head", "jawMode": "morph", "jawAttachmentNode": "JawAttachments",
    "jawAxis": [1.0, 0.0, 0.0], "jawOpenDegrees": 22.0,
    "leftEyeNode": "EyeLeft", "rightEyeNode": "EyeRight",
    "gazeMode": "joint", "gazeYawDegrees": 18.0, "gazePitchDegrees": 15.0
  },
  "bindings": [
    {"source": "eyeBlinkLeft", "mesh": "Face", "target": "eyeBlinkLeft", "gain": 1.0},
    {"source": "eyeBlinkRight", "mesh": "Face", "target": "eyeBlinkRight", "gain": 1.0},
    {"source": "mouthSmileLeft", "mesh": "Face", "target": "mouthSmileLeft", "gain": 1.0},
    {"source": "mouthSmileRight", "mesh": "Face", "target": "mouthSmileRight", "gain": 1.0},
    {"source": "jawOpen", "mesh": "Face", "target": "jawOpen", "gain": 1.0}
  ],
  "ignoredSources": ["_neutral"],
  "license": {"name": "实际资产许可证", "file": "LICENSE.txt"}
}
```

绑定解析后得到每个 primitive 的固定 target index，不在每帧按字符串检索。GLB 的目标名使用 `mesh.extras.targetNames` 时，必须校验长度与 target 数量一致；它是通用工具约定，不是 glTF 核心必须字段。没有名称的 GLB 可由清单显式提供 `targetIndex`，不能自动把第 0 个 shape 猜作眨眼。重复名字、不存在的节点/目标、不同覆盖关系和双重 gaze/jaw 驱动都拒绝。标准 morph 语义与名称约定见 [glTF 2.0 morph targets](https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html#morph-targets)。

绑定项可显式提供 `gain`、`bias`、`deadZone`、`gamma`、`min`、`max`，所有数值需有限，输出权重做范围校验。多个 source 合成一个目标必须显式声明求和与限幅；不能因为导入资产少几个目标就悄悄把左右动作平均。导入报告列出“完整驱动 / 明确忽略 / 缺失”，缺少独立眨眼、视线、张口或头姿的包不能被标记为完整互动角色。

## 5. GLB v1 loader 的明确支持范围

当前 `AvatarGlbLoader` 已实现 morph-only GLB、刚性节点和不使用贴图的基础材质解析；APK 内置角色和导入包均复用此路径。文件选择、包级校验报告、候选/当前/上一版事务及管理 UI 已实现，v9 两份测试包和一份无效包的设备导入/切换/回退已有证据；正式里程碑 v1a 仍需补齐真实第三方资产与授权/取消等异常验收，不能由这两份测试包推断任意 GLB 均可导入。下面区分当前子集与原定扩展要求；蒙皮和更复杂材质属于随后 v1b。有限支持范围要向用户说明，导入失败保留当前角色并给出具体原因。

| 范围 | 当前实现 / 后续处理要求 |
| --- | --- |
| 文件 | 当前为 GLB 2.0、JSON+BIN chunk、一个内嵌 buffer；拒绝外部 URI 和图片/纹理/sampler。本地包入口仅接收固定文件白名单的有界 ZIP，拒绝路径穿越、额外条目及下载地址。后续 PNG/JPEG 图片必须内嵌，不能绕过包授权。 |
| 顶点 | 已支持 TRIANGLES；POSITION/NORMAL 为有限 float VEC3，TEXCOORD_0 可读 float VEC2，但尚无贴图采样；COLOR_0 支持 float 或规范化 unsigned byte/short。索引支持 unsigned byte/short/int，也支持无索引三角形；统一为内部索引格式。 |
| buffer/accessor | 支持有效 byteOffset、byteStride、bufferView 与 accessor offset 组合；支持 sparse morph accessor，在一次性导入阶段展开。每个 offset/count/stride 用 long 做边界检查，先检查再分配内存。 |
| 节点 | 一个被选中的 scene、有限且无环的节点树、TRS 或 matrix（二选一）。支持正的非均匀 scale，并正确变换法线；负尺度/反射先拒绝，要求离线烘焙，防止背面剔除方向错误。 |
| morph | POSITION 和可选 NORMAL delta，目标数量上限 64；同 mesh 的各 primitive 目标数和顺序一致。基础数值、delta、权重都验证；变形再做蒙皮/节点变换。 |
| skin | v1a 明确拒绝 skin，支持无蒙皮 morph 网格及刚性 Head/Eye/JawAttachments 节点。v1b 支持一个头部骨架、上限 32 joints，JOINTS_0 与 WEIGHTS_0，每点最多 4 个 influence、inverse bind matrices；测试覆盖 Head/Jaw/EyeLeft/EyeRight/Neck。不会要求纯 morph 用户资产凭空新增骨架，也不会把含 skin 的模型悄悄当作静态 mesh。 |
| 材质 | 当前仅支持显式材质的 baseColorFactor、vertex color、OPAQUE 和 `KHR_materials_unlit`；lit 材料要求 metallicFactor=0，使用方向光、环境项和简化粗糙度高光，不承诺完整 glTF PBR 外观。原定 baseColorTexture/单一 UV 采样及 sRGB/线性光照转换仍未实现，不能写成已支持；v1b 再扩展有实际资产需求的金属度/粗糙度实现。 |
| 暂不支持的材质 | 当前包括全部贴图、alpha BLEND/MASK、doubleSided、非零 emissive factor、纹理变换、透射等；明确拒绝。后续基础贴图完成后，normal/occlusion/metallicRoughness/emissive 贴图仍需独立实现与验收。可由离线资产整理工具按用户确认的画质方案烘焙/转换，不加载后静默丢弃特性。 |
| 扩展/动画 | 未实现的 `extensionsRequired` 一律拒绝；Draco、meshopt、KTX2 等压缩包不能直接上传设备。v1 对带 glTF animation 的模型要求离线移除/烘焙明确的基础姿态，不能与实时 facial rig 暗中抢同一节点。 |

这是本项目的资源约束配置，不声称是通用 glTF 查看器。完整文件结构可先用 [Khronos glTF Validator](https://github.com/KhronosGroup/glTF-Validator) 检查，再通过本项目更严格的性能/功能检查。若所选真实用户资产依赖上述未支持特性，必须落实离线转换或扩展 loader，并保存转换前后报告；不能以“只支持内置模型”结束正式导入任务。

当前 loader 硬限制为文件 32 MiB、JSON 1 MiB、解码几何预算 32 MiB、总顶点 20,000、三角形 30,000、8 个 render primitive、64 morph，scene 实例另按相同几何上限核算。原定纹理解码总量含 mip 24 MiB、v1b 上限 32 joint 仍为后续约束，不是已支持能力。纹理实现须在分配前读取图片尺寸并核算资源，不凭压缩文件小就允许任意尺寸。正式导入仍须核算唯一 accessor/bufferView 及实际展开后的 geometry/morph/邻接/工作缓冲字节，避免共享引用被反复复制。更高规格以显式新 profile 开放，不能自动挤占面捕/系统保留内存。

## 6. CPU preblend、动态法线和多视图绘制

当前实现采用独立 CPU `AvatarDeformer`，每次动画快照只变形一次，将结果共享给 20 个视点；不得把 52 次形变放入每个视图的顶点着色器，也不能扩展成 52 个 vertex attribute。同步 GL 线程和 `AvatarPoseWorker` 有界异步路径都已接入，实际性能证据见第 7 节。

处理顺序：

1. 读取一个不可变 `InteractionController.Snapshot`，映射到 morph 权重和头/颌/眼节点状态。
2. 从中性几何复制到可复用工作缓冲；按实际非零 morph 累加位置。导入时建立每目标受影响顶点列表，避免对大量零 delta 做 52 次全网格扫描。被忽略的极小值必须有明确可测试阈值，不能硬丢“只取最大四个表情”。
3. v1a 的眼球/下颌附件/头部使用每个 primitive 的刚体矩阵，脸部 jaw morph 只计算一次；头姿变化不必重算独立头部网格。v1b 再支持局部 skin，标准 morph 在 skin 前执行。若用户模型把头、颈、肩混合蒙皮到同一 mesh，就不能对它整体追加头姿矩阵；完整 skin 必须包含头姿，并将头姿变化视为变形更新。只有经验证可分离的刚体头部才能使用 GPU 总姿态捷径，避免双重变换或肩膀一起转动。
4. `normalPolicy=authored` 时累加 NORMAL delta、正确变换并归一化；缺失必要 NORMAL 时明确报告。内置默认 `recompute-deformed`：按共享顶点索引对最终三角形做面积加权法线累加，不跨重复位置自动焊接。当前没有导出额外平滑组清单；glTF 也没有核心平滑组字段。外部资产的 UV seam、原法线和节点/形变绑定关系仍需检查并报告，必要时离线修正拓扑或实现明确的受限平滑组；不能盲目焊合。UV seam 不应引入意外硬折，头发/牙齿硬边不能被无差别焊平，不能只变形位置而沿用中性法线。
5. 输出 position+normal 动态 VBO；UV/颜色/索引和材质保持静态。使用预分配 primitive 数组和 direct buffer，循环不创建大型数组。
6. GL 线程在 frame boundary 接收最新完成的变形结果、上传一次；旧结果可持续画到新结果完成。队列采用固定三个 buffer 状态（GL 正在读、已完成等待、worker 正在写），已完成结果可被替换，不能改写 GL 正在上传的内存。记录序号和变形年龄。
7. 每个四视点 batch 按真实 primitive/material/节点变换范围绘制，GLB 为 CCW；诊断 mesh 的 CW 规则留在旧路径。合并同材质且变换兼容的 primitive，而不是把 indexCount 等分 8 份。透明材质在 v1 拒绝，避免排序与 overdraw 掩盖性能。

UI 30 Hz 的平滑会使形变更新高于原始面捕帧率，因此统计中分别记录 inference、rig update、deformation、VBO upload 和 actual presentation 频率。初版 Java CPU 实现先作为可测正确性基准；若 p95 超预算，保持同样 `AvatarDeformer` 输入/输出，移至 C++/NEON 或恢复一次性 GPU transform-feedback/纹理 morph 后端，使用同一组位移/法线参考数值比较。不能为了保持“Java 实现”而静默降低视点数、取消完整系数或隐藏性能失败；视点数变化必须作为独立配置重新验收。

### 6.1 热切换与失败回退

当前 v9 已实现后台 Store staging 和包级 schema、边界、覆盖、预算检查，复用 `AvatarGlbLoader`/`AvatarRig`，再由管理页 GL 线程创建候选 scene。v8 在 Android 11 上的 `Files.getFileStore` 权限异常已改为 `StatFs.getAvailableBytes()`，维持 74 MiB 空间门槛并保留查询错误原因；v9 SAF 已实际进入并通过合法包验证。当前准入检查是成功提交一次绘制、`glFlush` 后无 GL 错误，并保持选中包/owner/context 身份一致；不声称已证明 SurfaceFlinger 呈现或美术效果。用户明确激活后原子替换选择指针，返回主程序时重建并加载已选角色，尚不是原活动 scene 内无缝替换。导入失败保留原选择；主程序读取已选包失败时保留坏包并明确提示暂用 APK 内置 GLB。候选 GPU 初始化或 debug 合并绘制失败直接报错，不静默换成成功的内置控制组；诊断椭球只在明确的诊断模式出现。

原定无缝热切换要求仍保留：完成新资产上传及首帧检查后，在帧边界替换活动 scene，失败继续显示原角色；当前管理页预览和主程序重建路径已有第 9 节的局部设备验收。文件读取/排队 30 秒、加载完成后的 GL 预览 20 秒、明确切换确认 15 秒分别限时，超时撤销 owner 和预览资格，不替换被阻塞的单一 IO 线程。原子指针提交已开始时取消不能撤销，切换超时只报告结果未确认，任何管理页返回都重读 current。回退 B 后 force-stop/重启继续选择 B 已有执行记录和新 session 证据；实际 IO/提交中 HOME、同页 EGL 恢复、断电或存储损坏恢复尚未实测，文件强制写出也不等同于目录 fsync 的断电保证。

切换附带 generation id。旧加载任务、旧形变结果和重建前的 GL 资源不得重新发布。旧 GPU buffers/textures/program 引用等最后一个已提交 fence 完成后释放；上下文丢失时丢弃 GL handle，用当前已验证 CPU 资产重建。不能让无界候选包或每次切换都残留 direct buffer。

staging 最多一个候选包。切换峰值超出已测内存预算时先卸载当前可重新加载的 GPU 资源并显示轻量过渡状态，再上传；该路径也必须可回退，不能假设 2 GB 设备可同时容纳任意两个角色。

## 7. 性能预算与事实边界

当前已落盘的 `output/mirror-program/20261003/portrait-program-baseline.json` 是诊断椭球、20 视点、每视点 400×720、输出 1200×1920 的基线：实际呈现约 29.45 FPS、GPU 平均约 94.87%、全机 CPU 约 71.67%。它说明此负载余量不足，不能拿更小视点或旧横屏结果来保证新角色 30 FPS。`runtime-initial-blackout-status.json` 的 renderer callback FPS 也不能替代 SurfaceFlinger 呈现证据。

正式角色 v3 已在同一 APK（SHA-256 `44d0a133f4ef7b9c66e06d57f6dc541b991019d19f9111f1daf52103e711e746`）做 90 秒同步/异步对照：20 视点、每视点 **320×576**、输出 1200×1920，`camera_replay` 为真实 USB 采集＋已录人像回放的完整面捕＋UI，不代表现场实时表情准确度。[同步原始报告](E:/tripo/output/mirror-program/20261003/avatar-v3-sync-a-90s.json) 的已确认互动呈现约 24.3941 FPS，[异步原始报告](E:/tripo/output/mirror-program/20261003/avatar-v3-async-b-90s.json) 约 28.1340 FPS。两组分别仍有 1.6877/1.4037 秒历史缺口、1.3866/1.4474 秒未确认尾段，`complete_active_evidence=false`、`meets_30fps_target=false`，均未通过完整 30 FPS 验收。它们不能替代 400×720、长时热态或真实 camera 的结果。

v7 同 APK 对照仍为同一生产模型、20×320×576、完整面捕与 UI：[同步](E:/tripo/output/mirror-program/20261003/avatar-v7-sync-a-90s.json)和[异步](E:/tripo/output/mirror-program/20261003/avatar-v7-async-b-90s.json)的 90 秒确认互动呈现分别为 28.9378 / 29.2387 FPS，确认互动历史完整，未确认尾段分别 0.3029 / 0.5291 秒，仍未达持续 30 FPS。构造期源数组缓存保持数值和画质不变，双 Deformer 新增约 3.126 MiB；形变均值为 7.2843 / 10.0344 ms。完整资源、温度、SHA、v6 HOME 恢复及 v7 quicken 另测的事实边界见[设备验证记录](avatar-runtime-validation.md)。这些短测不替代真实 GLB 的 30 分钟/2 小时或 400×720 联合验收。

v8 APK `AvatarRuntime-v8-import-batch.apk` 的 SHA-256 为 `5f38fe6cb60f14f4249cae1122f96a1c8217950ee82821358edb540789a65af9`。debug-only 合并绘制把此模型每 20 视点的 35 次 scene draw 合为 5 次；[GPU 原始报告](E:/tripo/output/mirror-program/20261003/avatar-v8-batch.json) 的 69 姿态×20 图层在 400×720 下通过预设 `max RGB≤1`、`RMSE≤0.1` 容差：总计仅 1 个 RGB 字节不同、最大差值 1、最大 RMSE 0.00107583，不能称逐字节相同。其 90 秒同 APK、20×320×576 联合负载对照为[原逐 primitive 路径](E:/tripo/output/mirror-program/20261003/avatar-v8-individual-a-90s.json) 29.3158 FPS、[合并路径](E:/tripo/output/mirror-program/20261003/avatar-v8-batched-b-90s.json) 27.8562 FPS；确认互动历史完整，无状态错误，未确认尾段分别 0.6307/0.2869 秒，两者均未达 30 FPS。减少 draw 次数在这轮实测中没有提速，默认保留原路径。

v9 APK `AvatarRuntime-v9-import-fix-flat-material.apk` 的 SHA-256 为 `8ab4674ca965c199697a1e91ff058b2df247e5f472f090be825a1c8cb157a24e`。该版修复角色包空间检查并加入管理页有限截止时间；功能证据见第 9 节。另一个 debug-only shader 候选把动态材质选择移至顶点阶段，再以 `flat highp` 传入片元阶段；其独立设备 GPU 核对69×20通过预设容差，仅一个RGB字节差1，全部对应图层哈希与v8不变；同APK反向顺序合并/原路径为27.0074/29.3386 FPS，没有性能改善证据。原始报告及统计范围见[运行验收](avatar-runtime-validation.md)。生产默认仍为原逐 primitive 路径。

以下保留最初工程目标，未测即不算完成。当前正式资产为 15,085 顶点、29,482 三角形，已经超过表中约 6,000/12,000 的初始规划，虽仍在 loader 硬上限内，也不能宣称达到原网格/上传预算；应按真实资产量测再优化，不能悄悄重定义目标：

| 项目 | 内置目标 / 门槛 |
| --- | --- |
| 网格 | 约 6,000 顶点、12,000 三角形、最多 8 batch；五次四视点提交约 40 scene draw/frame。与旧 20,480 三角形诊断网格比较时记录不同场景复杂度。 |
| 形变与法线 | 每次更新 CPU 总 p95 ≤3 ms，争取均值 ≤1.5 ms；这些不是对 Cortex-A55 的已验证成绩。超限先做稀疏 delta/邻接优化，再测 native 后端。 |
| 上传 | position+normal 6,000×24≈144 KB/update；30 Hz 约 4.32 MB/s，不含驱动额外复制。上传/获取缓冲 p95 ≤1 ms。 |
| CPU 表情工作集 | 52×6,000×6 个 float×4 字节≈7.14 MiB 的 dense float32 上界；稀疏打包应更低。不能把 52 份完整 Java 对象网格常驻。 |
| 纹理 | 内置优先一张 1024² base color 或 vertex color；RGBA8 加 mip 约 5.33 MiB。避免独立高分辨率眼、牙、嘴、皮肤重复贴图。 |
| 视图缓冲 | 400×720×20 RGBA8≈21.97 MiB；DEPTH16 20 层约 10.99 MiB，另有窗口和其它缓冲。角色预算不能混入视图尺寸变化来比较。 |
| 新角色内存增量 | 正常驻留比对应诊断场景增加 ≤32 MiB，切换临时增量 ≤64 MiB；必须看 PSS/direct/GPU 的实机趋势，而非仅算数组字节。 |
| 实际呈现 | 本轮以 20 视点、相同每视点尺寸、真实 USB 采集+完整 NPU/CPU 面捕+UI 的 INTERACTIVE 区间 ≥30 FPS 验收，原始历史完整；逐 60 秒分段报告，不拿 idle 混入平均。用户硬指标仍是“16 视点以上、30 帧”；其它配置单独报告，不改写 20 视点结果。 |
| 新鲜度/稳定 | 显示形变年龄 p95 目标 ≤200 ms；30 分钟及 2 小时无持续内存斜率、反复重建/切换泄漏和错误。热稳定、摄像头姿态与最终光学另验。 |

320×576 可作为明确标注的对照配置，400×720 的质量基线保持单独结果。NPU继续执行检测与关键点，不为提升占用率增加无关任务；普通 GLB morph/skin 不假定能直接移到 RKNN NPU。

## 8. 最小代码分解与实现顺序

这些是连续的交付切片，表中验收要求并非全部完成。A/C/D 已有实现和局部设备证据，B 的 Store 及 E 的管理入口已通过主机检查、整体 SDK 构建及 v9 两测试包的 SAF/切换/回退检查；实际第三方资产、异常生命周期、全部动作美术和 F 的完整性能/耐久仍有缺口。正式用户资产流程不等待所有美术微调结束。

| 切片 | 文件/边界 | 必须形成的可审查结果 |
| --- | --- | --- |
| A：完整控制量 | 已有 `BlendshapeSchema.java`、`FaceFrame.java`、`InteractionController.Snapshot`；`FacePostGraph` 校验标签 | 52 维保留、左右不合并、镜像只处理一次、失效回中；旧 benchmark 四维出口继续兼容。镜像/中性校准仍待独立验收。 |
| B：资产容器与真实导入 v1a | 已有 `AvatarGlbLoader.java`、`AvatarAsset.java`、`AvatarRig.java`、`AvatarPackageStore.java` 与离线 `scripts/inspect_builtin_avatar.py`；真实文件系统/ZIP 事务主机检查 139 项通过，v9 两测试包 SAF 导入及无效 ZIP 拒绝已实测 | 同时支持 APK assets 和导入文件；合法/损坏/超预算样例；实际读出 morph、刚性节点、primitive、基础材质及报告，明确拒绝 skin。现有测试包不能替代真实第三方资产和全部异常验收。 |
| C：内置完整角色第一版 | `build_builtin_avatar.py`、内置 GLB/清单/源文件 | 真实完整头、眼/睑/眉、口腔/牙/颌/颈肩，至少左右闭眼、左右视线、张嘴、左右笑与头姿可辨；立即通过 B 加载。 |
| D：CPU 形变和角色渲染 | `AvatarRig.java`、`AvatarDeformer.java`、`AvatarPoseWorker.java`、`AvatarGpuScene.java`，renderer 已有 scene 委托 | 一次形变共享20视点，动态法线、真实primitive绘制、资源释放与上下文重建。保持旧诊断 benchmark 独立分支；异步发布和重建仍需各自完整设备验收。 |
| E：补全所有输入及导入 UI | 已有内置51动作覆盖表、`AvatarPackageStore.java`、`AvatarManagementActivity.java`/`AvatarManagementGate.java`、Mirror 维护入口及 renderer 读取；24 项门控、42 项截止时间、6 项坏摘要适配主机检查通过；v9 显式启用 B→A、回退 B、重启持久选择和恢复内置已实测 | 两份不同 GLB SHA 的测试包均经系统文件选择器导入；缺动作包报告 partial，不冒称完整。实际阻塞提供方、IO/提交中 HOME、同页 EGL 恢复仍待验，导出尚未实现。若真实用户资产依赖 skin，紧接 E 实施 v1b 的有界 CPU skin 和对应姿态/法线测试，再对该资产验收；不能仅永久拒绝用户模型。 |
| F：优化与联合验收 | native deformer仅在剖析要求时增加；扩展runtime数值状态和脚本 | 相同像素/输入/角色的前后结果、raw SF history、CPU/GPU/NPU/PSS、形变时延、30min和2h结果、回滚包。 |

v1a 的完成标准是完整可辨识头部、实际 GLB loader、独立眼/口/头动作、文件导入和错误回退；不要求先完成完整 skin 或 PBR 才能交付这个里程碑。v1b 在得到首个需要这些特性的真实资产后按差异报告实现和测试，保持具体资产验收入口。资产风格属于可替换的默认内容选择，不能成为不实现 loader、动作拓扑和资源回收的借口。若确实缺少可执行建模工具，先完成 A/B 和离线解析器/测试，再把具体工具缺口与可交付状态说明，不把功能缩减为原椭球。

## 9. 自动化与视觉验收

### Schema v2 组合修正的已实现合同

`AvatarRig` 继续接受 schema 1，保留其有限 `[0,1]` 输入校验及原 direct/joint 行为；schema 1 出现 v2 功能字段会拒绝，避免悄悄忽略。schema 2 的 `requiredRigFeatures` 数组只认可一次 `product-correctives-v1`，其它名称或重复声明均拒绝。启用此功能必须提供非空 `derivedBindings`，每项只允许 `operation/sources/mesh/meshIndex/target/targetIndex/gain`。mesh 和 target 分别按名称或整数索引二选一。

`operation` 仅为 `product`；`sources` 是 2–3 个不同的原始非 neutral MediaPipe 名称，不能引用几何 target 或另一个 derived 输出。v2 对全部52个有限输入统一 clamp `[0,1]`，direct、product 和刚性 eye/jaw 共用该副本。按所列顺序相乘后再乘 gain，gain 支持 `[0,1]`（默认1），以保持自动 framing 的单位权重包络；未知字段、越界 gain 和 direct/derived 之间的重复目的 mesh/target 均拒绝。产品项是附加 delta，不抑制 jawOpen、blink 或刚性节点；完整源覆盖仍由独立 direct/joint 驱动判定，条件乘积不冒充独立动作支持。

修正版已经晋升至 `app/src/main/assets/avatars/builtin-guide/character.glb`，生产 SHA-256 为 `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407`；生成器、清单、README 和许可证同版保存。原 staging SHA `1b858680a5db0a9da73bcf0b0aadd096b05b97cfee5a6ea7acf9906ae6e99818` 仅是历史检查对象；提升时已验证复建后的节点、材质、全部 morph 顶点对应及有向三角形几何一致，原 v1 APK/资产另有回退副本。

生产 rig/deformer 主机检查覆盖 21 个 jaw+mouthClose 姿态，保留非唇部 jaw 变形和刚性 jaw 旋转；242 个 fully-blink × wide × squint 组合的最大闭眼缝 `1.49e-8 m`。9 个修正不改变原 43 个直接面部 target 及 8 个刚性视线动作。独立 framing 检查 216 个混合姿态×3视图，最坏 NDC `0.8797182`，输出宽高比 `.625` 的固定 scale `3.3153255`。loader/deformer 的 319 个实际几何姿态、旧版 rig/schema/controller 的 441 个断言以及 v2 实际资产测试均通过。可复测 `tests/run_avatar_rig_tests.ps1 -JavaHome 'C:\Program Files\Java\jdk-17' -AssetPath app/src/main/assets/avatars/builtin-guide/character.glb`；framing 和 loader 使用相应已有 runner 传入同一 `-AssetPath`。这些数值/几何检查不替代组合美术或运行性能验收。

### 主机测试

本节保留完整验收矩阵，不能把每一条都视为已完成。v9 的 Store 139 项、管理门控 24 项、截止时间 42 项、坏摘要适配 6 项及既有 loader/rig/deformer/worker 等整套回归通过，整体 Android SDK 构建成功。Store 已用真实 ZIP/GLB 和文件系统覆盖坏包、预算、取消、精确 ticket、提交前后注入失败、残留恢复；新增 StatFs 边界替身覆盖 74 MiB 临界、负/零、超过 2 GiB 及查询异常，生产源码另通过真实 SDK 链接。门控覆盖旧 IO/GL 回调和 context 失效；可控时钟及真实单线程阻塞 fixture 覆盖截止时间，坏摘要适配实际复用 Store。第 9 节以下仅列出的 SAF/切换设备流程已有证据；实际 GPU 上传失败、提供方阻塞、未测生命周期、长期反复切换泄漏和断电/存储损坏恢复仍需补测。复测入口及具体边界见 [Store 检查](../tests/avatar-package-store.md)与[管理入口检查](avatar-management.md)。

- 全部 52 标签的顺序/重排/缺失/重复测试；`_neutral` 不产生 delta；`tongueOut` 等未知来源不能凭索引误绑定。
- 每项系数依次置 0、0.5、1，验证映射目标和左右隔离；对 jawOpen+mouthClose、blink+wide、smile+frown 等组合验证有限值和明确限幅。
- 独立离线 float64 参考变形对 CPU/JNI/GPU候选输出；不是把生产实现抄一份当参考。检查顶点位置、单位法线、面积为零三角形处理、镜像和姿态变换顺序。
- GLB：合法 interleaved/sparse/indexless、offset溢出、截断chunk、越界index、环状节点、错误权重、未知必须扩展、损坏纹理、缺失目标、64以上morph、负scale、资源预算等。
- 导入事务：读失败、校验失败、GL上传失败、取消、切换generation过期、当前资源释放和fallback成功/失败；禁止失败后覆盖可用角色配置。

### 单视图视觉与多视图检查

先在单视图调试模式固定灯光/相机输出截图，再恢复 20 视点：中性、左闭眼、右闭眼、双闭眼、每眼四向视线、最大张嘴、闭唇张颌、左/右笑、皱眉、鼓腮、鼻翼、±头姿。正面和两端视点都要能看见真实遮挡关系，检查眼球穿帮、唇齿交叉、口腔漏背景、鼻/耳轮廓及法线闪烁。图像测试要有实际人工/图像审查，不能仅靠顶点数组非零判定角色可辨识。

对同一可冻结的完整控制快照，逐视图串行参考与 OVR 四视点批次进行像素比较；材质/透明度策略一致，记录容差。形变后法线与中性法线变化也需截图验证。多视图差异不得靠复制同一张图制造“20层”。

当前设备证据分开记录：

- [v3 GLES 报告](E:/tripo/output/mirror-program/20261003/avatar-v3-device/report.json) 已保存 480×768 的中性、左右独立闭眼、张嘴、笑、视线、头姿和张颌闭唇共 10 状态截图（run `45f5227f-bcc7-4854-82b0-347a5ca71ecf`）。root 已视觉检查独立闭眼和 jawOpen×mouthClose；这不是全部 51 动作/极端组合美术验收，报告仍明确 `artwork_validated=false`。
- [v4 多视图原始报告](E:/tripo/output/mirror-program/20261003/avatar-v4-multiview.json) 在 APK SHA-256 `0e8f129ade4001f4fdb3927137267a3ee0c47897f19ee02b23c4bcf9ef7322eb`、同一生产模型下通过：run `8584e865-931c-4393-a7aa-6ae6615eb7ae`，69 姿态×20 独立视图 = 1,380 层对，每层 400×720；最大 RGB 差值 0、总 RGB 字节差异 0。比较的是**同一同步姿态和共享 VBO，交织前的串行单视图与 OVR 四视图图层**。361,225.7 ms 是整项诊断耗时；`performance_evidence=false`、`artwork_validated=false`。此通过不证明异步发布像素一致性、最终交织光学效果或 30 FPS。

### v9 角色管理的局部设备验收

使用第 7 节 SHA `8ab467…7a24e` 的同一 v9 APK，在 Android 11 设备经系统 SAF 选择测试包。完整原始文件位于 `E:/tripo/output/mirror-program/20261003/v9-manager-*`，按选择指针、XML 和返回主程序后的模型 SHA 分别确认；[结构化审计](E:/tripo/output/mirror-program/20261003/avatar-v9-management-evidence.json)及[详细记录](avatar-management-validation.md)保存 36 个采集点、11 项状态核对与原始文件哈希。以下仅覆盖这些具体操作：

| 功能 | 设备证据与结论 |
| --- | --- |
| 有效 B 导入、预览及显式启用 | 05/11 的 XML 显示 B 预览就绪，启用前只发布 candidate；12 明确启用后，[13 主程序状态](E:/tripo/output/mirror-program/20261003/v9-manager-13-main-b-runtime.json)报告 imported B 和其实际模型 SHA。05 的文件名 `invalid` 是误命名，不能作为坏包证据。 |
| 未启用候选的 HOME 路径 | 06 HOME 后经启动器回到主程序，[08 选择状态](E:/tripo/output/mirror-program/20261003/v9-manager-08-main-candidate-uncommitted-selection.json)仍为 current=null、candidate=B，主程序加载内置。只证明候选未自动启用，不证明同一 Manager Activity/EGL 恢复，也不是 IO 或提交中 HOME。 |
| 无效 ZIP 保留当前 | 18 实际选择无效包，19 拒绝启用、20 显示 `ZIP must contain 2 to 5 flat allowed files`；[19 选择状态](E:/tripo/output/mirror-program/20261003/v9-manager-19-invalid-result-selection.json)与错误前的 B 状态一致。这次进入了 ZIP 内容校验，已越过 v8 的 getFileStore 权限失败。 |
| 有效 A 导入、预览及显式启用 | 24 只添加候选 A，预览就绪后 25 明确启用；[26 主程序状态](E:/tripo/output/mirror-program/20261003/v9-manager-26-main-a-runtime.json)加载 imported A，previous=B。A 的 GLB 与内置相同，但 imported 来源和包 ID 能区分它与内置回退；B 则有不同 GLB SHA，不能将这两测试包扩展为任意第三方资产覆盖。 |
| 回退及重启持久选择 | 29 预览 previous B，30 明确回退；[31 新 session](E:/tripo/output/mirror-program/20261003/v9-manager-31-restarted-b-runtime.json)实际加载 B，持久 current=B、previous=A。执行者确认 force-stop 后重启；该采集点 action=null，没有保存 force-stop 命令本身，操作记录与新 session 的直接证据分开解释。 |
| 恢复内置 | 34 预览内置、35 明确启用后，[36 主程序状态](E:/tripo/output/mirror-program/20261003/v9-manager-36-main-builtin-runtime.json)加载 builtin，current=null、previous=B。 |

这些返回主程序后的快照已有约 24.44–24.61 FPS 的 USB 采集、无采集失败或应用错误，但均处于 WAITING、`face_frames=0`、`mesh_calls=0`、`post_calls=0`；不能据此证明完整面捕恢复性能或实际呈现达 30 FPS。管理页期间的 runtime 文件不少是此前主程序快照，XML 与 PNG 也存在先后采集差，不能作为后台持续采集、无缝呈现或无资源泄漏的证据。

实际阻塞的 SAF 提供方、IO/原子提交期间 HOME 或超时、同一 Manager Activity/EGL 恢复仍未实测；真实低空间、权限拒绝、GPU 上传失败、长期反复切换资源趋势、断电/存储损坏恢复也保留为后续验收。主机的 42 项截止时间回归和 Store 故障注入不替代这些设备实验。

### 真机 gate

使用 `scripts/run_runtime_check.py` 的原始 SF actual 时间戳、状态事件和缺口标志，分别测试 replay、camera_replay、真实 camera。先短测试发现资源/形变问题，再 5 分钟联合负载、30 分钟、2 小时；包含 HOME/恢复、USB断连重连、导入失败、反复切换角色、相机权限和 GL 恢复。导出 APK SHA、模型 SHA、映射 SHA、source schema、视点数/像素、材质/三角形数、推理档位和温度。

任何 status error、history gap 或不完整 INTERACTIVE 区间均不得判为完整呈现达标。新角色未达到目标时保留原始证据并继续优化，不用诊断椭球的数字替它验收；光学舒适度、镜头方向和最终角色视觉效果仍需实屏观察。
