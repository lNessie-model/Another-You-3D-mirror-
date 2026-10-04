# Tripo 头模的受限纹理支持（设备快速检查通过）

需求：只含头部和短颈的成熟 IP 风格模型；MediaPipe / NPU 面捕；16 视点、每视点 400×640，目标 30 FPS。保留已验收 v20，新增代码与资产先独立检查，失败回退。

观察：Tripo 原件具有 baseColor / ORM / normal 三张纹理，原始身体自动绑定对纯头部返回 riggable=false。离线顶点色采样虽通过加载器和数据检查，仍丢失眼睛细节；增加细分也未达到外观要求。因此需要受限的 albedo atlas 支持，不能把顶点色版本称最终外观。

## 新资产契约

GLB root extras 声明 `mirrorAlbedoAtlas=1` 时允许恰好一个内嵌 PNG atlas，恰好一个 texture，UV 为 TEXCOORD_0。PNG 限 8-bit RGB、1..2048 像素、4 MiB 编码长度；检查 chunk 长度和 CRC，拒绝 URI、PNG 动画、未知纹理扩展或 sampler 配置。所有图像仍来自嵌入 BIN；不联网读取。

允许 opaque、metallicFactor=0 的既有材质额外引用 baseColorTexture.index=0 / texCoord=0。不引用 texture 的材质仍按既有因子 / 顶点色渲染。拒绝 normal / ORM / occlusion / emissive 纹理和 KHR_texture_transform。

默认不声明此 profile 的 GLB 继续执行既有 factor-only 规则；已有角色、渲染 shader 的数学顺序和测试契约保留。

CPU 资产增加不可变 atlas 编码和尺寸、材质 texture 标志，计入 decoded budget：编码字节 + 一份 RGBA 解码尺寸。GPU 创建场景时仅解码并上传一次为 sRGB texture，然后释放 Bitmap。纹理归创建该场景的 EGL 上下文所有，dispose 与上下文丢失遵守已有 VBO 生命周期。

新增 shader 只用于 atlas 资产，纹理采样由 sRGB 格式转换为线性 RGB，再进入原有光照和输出流程。批渲染共享一个 atlas，在静态材质参数第三分量区分是否使用纹理；UV 静态上传，不增加逐帧 CPU 打包。

## 验证

先以小 PNG 的合法与非法 fixtures 验证生产 loader：缺失声明、外部 URI、多 atlas、过大尺寸、非法 CRC、非 RGB、缺失 UV 等明确拒绝。验证旧角色与 shader 完整回归。新增 shader 在实际 Mali 上编译和对比像素；随后再用新头模完整人脸回放验证联合 FPS / 资源。

北京时间 2026-10-04：实际杰洛特局部校准头模在 Mali 上完成 10 个状态截图。新私有头模检查单独使用 9 个冻结姿态、每姿态 16 层：serial vs OVR4 的 144 次比较严格 RGBA 一致；individual vs batch 的 144 次比较通过原有 RGB≤1、RMSE≤0.1 标准，分别耗时 36.629 / 36.683 秒。未降低像素标准，旧 69 姿态回归不变。完整 69 姿态头模运行曾超出 300 秒，失败证据保留；9 姿态结果不等于完整回归或 FPS。

有效设备证据：`app/build/v22-albedo-device-9b016392-34ce-481c-a2dc-4e865b27fc8b`。该次回退同时核验 v20 APK、偏好、实际 `files/avatars/state.json` 字节哈希与两次推进的人脸回放。首次旧检查误用 selection.json 与过短回放等待的失败另存，未覆盖。

杰洛特 stage1 仅11个校准形变，尚有40个有效源未绑定，不能作为完整面捕角色。下列宿傩 stage2 是另一份实验资产；软件覆盖与外观验收分别记录。新 PNG/UV shader、实际角色生产加载及既有缓存/场景 host 检查通过；host 检查不代表设备 GLES 运行。

后续宿傩 stage2-v4 已覆盖51个有效源，另含 jawOpen×mouthClose 产品修正；15179顶点/12827三角形，生产解码31208033字节。当地切开嘴缝并加入简易口腔，修复了露面与桥接的两版失败。覆盖完整是软件接口结论，artistAccepted 仍为 false（闭眼折痕、口型和组合碰撞尚未通过）。Tripo 自动绑定检查返回 riggable=false；这些面部目标是本地实验制作。

实际90秒联合回放：头模变形、NPU 478点/52系数、16视点400×640交织同时运行。确认INTERACTIVE实际呈现30.7220266 FPS；面捕约15 FPS。整段系统CPU73.1077%、GPU87.5393%、NPU43.1124%；应用PSS峰447.4287 MiB；温度69.375..76.25°C。SurfaceFlinger确认区间79.6534秒，未知尾段3.9614秒排除；不等于持续热稳定或实时USB相机通过。APK/配置/选择状态与2次推进的v20回放回退检查通过。

证据：`app/build/v22-albedo-device-032b20c8-0bbb-48c2-9dc1-b116844943d1`。新增 debug-only `test_private_head` 从固定app私有目录加载，SHA/生产profile不合格直接失败；不读写存储角色选择，不落盘修改偏好。默认路径维持既有角色。背景合并保留头模BIN前缀/atlas/morph字节，背景节点是Root子节点，51次输入后背景revision不变；总19597顶点/15702三角形、31490089解码字节。

## 背景联合性能与优化限制

以下第一张表均为90秒已录制人脸联合回放、16视点400×640、真正NPU478点/52系数、动态头部形变及交织同时运行。后续16／20视点候选另表列出。实际FPS由确认INTERACTIVE区间SurfaceFlinger呈现时间计算，不用绘制回调频率代替；未知尾段排除。每次结案均核验v20 APK、偏好、角色状态和两次推进的回放；这不等于USB相机或长期热稳定验收。

| 资产/后端 | 实际FPS | 证据目录末尾 UUID |
|---|---:|---|
| 宿傩头部/individual | 30.7220 | 032b20c8-0bbb-48c2-9dc1-b116844943d1 |
| 原门廊/individual | 27.2011 | c930675e-4aeb-40c3-8333-827e317a418a |
| 原门廊/batched | 23.2369 | fceb741b-139a-44cb-be26-231c8f5caafa |
| 517面门廊/individual、上限31 | 29.1314 | c96f4690-d940-4d56-b1e9-6f1cb2fd53b1 |
| 同517面门廊、上限35 | 28.8926 | e5cb12b8-2d81-4a35-b899-65b1b3a1cd91 |
| 原门廊可见面/individual | 27.7821 | 4cc9e3e4-578b-45da-8077-bba3e9dc0623 |
| 同可见面、直接颜色/individual | 29.0623 | a626d3d1-aaae-48e1-a952-5e9640606a49 |

完整证据目录为 `app/build/v22-albedo-device-UUID`。以上宿傩带背景组均未达到30FPS，未提高阈值或用平均回调冒充呈现。517面减面版门廊出现明显变形，外观不接受。真正交互期间资源与整段（含启动）资源分开记录在运行JSON；直接颜色一组交互期间CPU整机79.43%、GPU95.84%、NPU46.82%、PSS峰440.34MiB；未知尾段3.797秒。以前整段GPU约89%不能描述成交互期间仍有大量GPU余量。

`static_view_mesh.py` 只移除既定相机两端及所有中间视点始终朝后的静态面。保留所有原AABB极值点，生产 `AvatarGeometryBounds` 的 `.625` 宽高比fit矩阵与原组合逐位一致。相机距离3、眼睛X范围±.2、Root背景固定是该候选的约束；不适用于任意相机或可旋转道具。背景4418顶点/2875三角形降至2333/1417，头部字节保持相同。几何行为测试3项通过，实际固定构图和51输入背景revision不变通过。

受限atlas shader对既有unlit材质提前输出base颜色，保持原输出gamma；原factor-only shader与lit数学顺序不改。独立直接颜色候选的GLB BIN、UV、atlas、全部形变字节与来源相同，只改材质扩展；此版本在Mali上通过9姿态16层严格serial/OVR及既有batch容差，实际中性预览已保存。材质风格和表情外观仍未艺术验收。

第一次直接颜色候选错误选择authored法线，而资产没有NORMAL形变，生产变形器明确拒绝，证据631e7389-4eda-429a-a440-8885fff8be3a（回退通过）保留。主机实际资产检查原来固定重算法线，现已修正为遵循清单的策略，错误候选会在主机提前拒绝。后续候选保留recompute-deformed，未放宽生产验证规则。有效测试APK SHA256为 `3f3a79e96310f6572bd50768a00207d12814440bb7768c0340d0ebae22146c39`。

## 新轻量头部与不同视点数

游戏、动漫、影视三套带背景的16视点候选均在90秒联合短测超过30FPS。实际输出1200×1920，使用pitch=10 SUBPIXELS、tan=0.2777777、phase=0、RGB、BOTTOM；光学对齐尚未验收。它们不是最终外观合格的成品角色。

| 资产 | 视点 | 实际FPS | 证据目录末尾 UUID |
|---|---:|---:|---|
| 杰洛特轻量＋哥特背景、直接颜色 | 16 | 30.72247 | 3d38313a-1e3f-440c-8260-09d354315535 |
| 同杰洛特场景 | 20 | 26.88672 | 44df3443-5a99-4753-bd51-5d9f27e3db26 |
| 裁剪玛奇玛＋赛博背景、直接颜色 | 20 | 27.74970 | db830e0b-e249-41dc-a44a-935141d64e61 |
| 同玛奇玛场景 | 16 | 30.70458 | 6068dee3-828e-481c-bdd7-426b5cbcd2b9 |
| 摩尔方向＋哥特背景、直接颜色 | 16 | 30.66311 | 7ba0024f-0d18-4c00-b0bf-6b8cfb341e04 |
| 同摩尔场景 | 20 | 26.99921 | fbe2ad1f-f23b-4d73-b9d1-84ae8281c100 |
| 摩尔仅头部、直接颜色（无背景／自适应构图） | 20 | 30.43329 | 40df4c0d-6580-4638-8a61-d3060db167aa |
| 摩尔保留原构图、关闭背景绘制的诊断基线 | 20 | 30.41378 | c72dde37-54c7-4676-bb36-b161a65e2066 |

以上均为90秒联合回放、每视点400×640，全部已结案记录回退v20通过。16视点带背景已达到短测30目标；三套带背景20视点均未达到30；无背景20视点结果另列。不同候选的driver smoke仍固定9姿态×16视点，不将20视点运行数据当作20视点像素门验收。新包装器记录runtime_view_count与driver_smoke_view_count，输出文件名对应实际运行视点数；旧20视点记录虽然文件名含head16，但JSON参数与实际状态明确为20，旧证据不重命名。

杰洛特组合13409顶点/9142三角形，28694542解码字节，交互期CPU72.40%、GPU91.94%、NPU46.23%、PSS峰451.89MiB，面捕约15.45FPS；确认79.669s、未知尾段4.778s。玛奇玛肩部原件未满足头部范围，另存裁剪产物并重新定位，组合9863/7204、25774039解码字节。摩尔组合10294/7086、26562688解码字节，交互CPU69.93%、GPU92.54%、NPU45.87%、PSS峰460.16MiB；确认84.712s、未知尾段0.712s。CPU是整机比例；面捕分析FPS和呈现FPS不同。

摩尔原RGB纹理2048²，普通PNG编码4350745字节超过4MiB，改为无损优化编码4096576字节；实际解码RGB像素逐个相等。生产尺寸、格式、编码大小和decoded预算没有放宽，原失败产物保留。本地表情作者也使用无损优化编码，原付费模型和既有候选不覆盖。

三套角色均包含51直接形变＋1组合修正，艺术验收仍为false。通道覆盖、加载、动作更新与性能通过不能替代闭眼／口腔／牙齿／相似度及多表情碰撞验收。保存的中性Blender源与实际动作预览在资产目录；完整12候选及计费见 `E:\tripo\assets\mirror-models-20261003\模型预览与进展.md`，当前预算4000、确认535、剩余3465，账户9230/frozen0核对一致。

六个平铺角色ZIP已通过当前生产AvatarPackageStore导入器的隔离主机检查（各含character.glb、avatar.json、README.md、thumbnail.png）：三套scene与三套head-only。核验模型SHA、完整有效源覆盖、候选发布、重读创建新的可变rig以及导入不切换当前角色。证据 `app/build/tripo-import-current/run-20261004-six-packages/report.json`；ZIP目录 `E:\tripo\output\mirror-assets\20261004\import-packages`。这是实际文件经过生产导入器的结果，不是Android SAF/选择UI端到端验收。

三份交付Blender源在实际Blender4.5.12读取后核验52目标全为中性权重、原贴图已packed；证据 `E:\tripo\output\mirror-assets\20261004\editable-workfile-check.json`。最后只读设备核验确认v20 SHA、原偏好与角色状态SHA及两次推进的478／52回放，证据 `E:\tripo\assets\mirror-models-20261003\final-device-state.json`。联合回放是预录真人NV21帧，不含实时USB采集与视频解码开销。

摩尔独立头部20视点短测达到30.43329FPS，但没有背景且自动fit不同；另一个保留原静态位置／全部头部和atlas/morph BIN前缀、只将背景索引变为一个零面积三角形的诊断基线达到30.41378FPS。实际生产fit矩阵逐位一致、主机51输入与实际Mali16视点快检通过。对照支持继续研究静态背景缓存；不同时间的短测不是严格热状态随机对照，关闭背景绘制也不是已实现的背景优化。诊断资产不作为交付角色。

独立头部20视点资产SHA `e1336893a9b1c10b6147d80a20b8628b66429eb18c97b962da865a75ddc8b2d8`，新增 `maul-head-only-unlit20.zip` 经过同一生产导入器主机检查，证据 `app/build/tripo-import-current/run-20261004-head-only20/report.json`；加上原六个ZIP共七个。新atlas需要v22实验实现；已恢复的v20不含该profile，不能用它直接导入新模型并宣称UI已验收。

接口设计采用 api-and-interface-design 技能的附加字段方式，保留原构造调用；不改 NPU 的输出通道顺序。所有源模型及修正产物另存，不覆盖原件。

截止：北京时间 2026-10-04 06:30 起收尾，07:00 停电。未完成资产或检查必须在交接中标记未完成。
