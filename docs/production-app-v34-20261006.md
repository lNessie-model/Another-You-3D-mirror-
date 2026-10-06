# Another You V34 / 0.2.3（2026-10-06）

本版新增艾达王为“已校正 · 可试用”角色，可选择身份从杰洛特、参考向导两个增加为三个，素材库仍为 27 项。最终 APK 已覆盖安装，角色选择、素材库显式使用和包内艾达的实际首帧通过；退出后四份配置全部恢复验收前原值，默认杰洛特与暗红雾保留。本人面捕和联合性能未测，艾达仍为试用状态。

## 艾达模型和试用状态

接入的模型 SHA256 为 `9c5246e343ee52c474470c763ea83801921fed235dbaed1a83a107a709431128`，16,220,864 bytes。实际生产 loader 统计为 19,105 个实例顶点、19,639 个三角形、8 次绘制、41,708,894 decoded bytes。它在本轮角色目录中标为 `corrected_trial`，卡片文案为“已校正 · 可试用”；这个状态不表示本人实时面捕、整体美术或联合性能已最终通过。

新 `avatar.json` 只将身份规范为 `ada-wong`、显示名称“艾达王”，SHA256 `efad7da01f421b0915c7b6b8635fbd5d46eeb1dba0e7a9e87723a519bc3a6dd0`。接入前的真实生产 loader/rig 核对证明其它绑定字段语义保持，19/55 组输入、权重与节点矩阵与冻结候选一致；GLB 的实际几何、材质、纹理和控制绑定字节不改。

角色卡片和素材库预览均来自该 SHA 模型的现有 800×800 真实中性 PBR 渲染，生产 deformer 的 POSITION/NORMAL 驱动离线 Blender 预览。整幅 Lanczos 缩为 512×512，两 PNG 都为 276,002 bytes，SHA256 `d58750879a946e0ebfe4d02da7010b44b5eac745f3fbff565b917ce6f9365196`，没有裁切或 AI 重画。离线灯光可能把黑发显示得较灰亮；实际 Mali 已查看为黑发，没有改贴图补偿灯光，也不宣称两种渲染颜色完全一致。

素材库仍保留艾达原始档案 `library/head-ada-wong/source.glb`，SHA256 `6a00740664acb97edb54b540304d5013042f5c24734d9ec6aeef23fbed299263`。原件身份不被校正版替换。已有条目改为 `runtime_ready`，通过 `liveRoleId=ada-wong` 与校正版 SHA 引用当前试用角色；这里的 ready 仅表示可以明确选用，个人面捕仍待确认。其余 26 项素材与原两个角色保持原值。

口角极小的白色反光点和细闭睑线仍可见，已经披露；不能称为完美匹配或无条件最终美术通过。十个 IP 身份中，杰洛特与艾达已达到各自接入状态，其余八个仍在独立校正；参考向导继续作为旧版参考。

## 已完成的模型验证

同一 GLB 在正式 App V33 上执行实际 Mali fresh run `9d4dc6a4-d0d1-44c2-ae8a-a1b239395856`，生产 loader/rig 的 10 组姿态、serial-versus-OVR 以及 individual-versus-batch gate 通过。完成后原杰洛特模型与四份配置逐字节恢复，并返回首页。该报告明确 `artistAccepted=false`、`liveFacePassed=false`、`jointPerformanceMeasured=false`；这是具体模型的 GPU 检查，不是 V34 安装验收。

接入草稿另外保留实际生产 19/55 姿态、眼与闭牙遮挡、刚性牙弓、口周连接和完整 51 来源覆盖的主机证据。这些技术门槛不能代替本人映射和实际画面质量；接受为试用角色来自对同模型图像的逐项查看。原历史候选的“待 Mali”记录保持原时态，后续同 SHA 的新证据单独保存。

当前源码对试用状态的接受与候选拒绝已增加回归：只有与当前角色目录身份、模型 SHA、manifest SHA 相符的 `corrected_trial` 才可经单角色字节校验选用；普通 `candidate` 仍拒绝。目录浏览继续只读元数据，不同时解码三个模型。实际 source assets 和最终 APK 分别通过严格 Java 校验器的 190 项检查，验证真实 27 条目录、原件/预览哈希及三个可用身份的当前模型/manifest 引用。

## 构建、设备结果和发布范围

applicationId 为 `com.mirror.bench`，versionCode 34、versionName 0.2.3。最终 APK `AvatarRuntime-v34-ada-trial.apk`，130,572,199 bytes，SHA256 `df642530804d7976f0c8f85da454f2c2944768d24da3092dc28e411382d211ae`。离线构建与覆盖安装通过，内部 debug 证书保持 `64a4af6baa9f10fd9d5bd09003ded7def368211604e3e91181bf015cdfbf6da8`，未设置商店发布签名。

相较 V33，包内资产仅有下表中的六件明确变更；其它 assets 和九个原生库逐字节保持。素材库仍是 55 文件、27 条目，未打包私人人脸视频或验证向量。

实机角色卡片准确显示试用状态，选择持久化通过。以原杰洛特选择浏览素材库艾达预览时，四份配置保持原 SHA；实际明确点击“使用角色”后才从杰洛特切为艾达并返回角色页。这次验证包含真实选择变化，没有用已经选中艾达的重复操作代替切换检查。实际产品入口 fresh session `823caf59-2908-4131-a349-5f3fc7a2c97a` 加载 `bundled/ada-wong` 与同一 `9c5246…1128` 模型，`glFrameReady=true`。这次证明本版包内角色能进入实际渲染，不将首帧通过解释为本人面捕或联合性能通过。

正常 Back 先关闭实时菜单，再返回首页。最终真实 `/proc/<PID>/task/<TID>/comm` 清单没有 GLThread、AvatarPose 或 AssetLibraryMetadata 工作线程；Android/Mali 系统线程仍保留，不把它当作 GPU 内存归零证据。最终回执确认四份运行/场景/选择/导入配置恢复原 SHA，默认角色回到杰洛特，原暗红雾配置保留。

本版 13 项源文件如下，最终冻结时逐项核 SHA，不按目录加入其它工作：

| 范围 | 文件 |
| --- | --- |
| 新试用角色 | `app/src/main/assets/avatars/catalog/ada-wong/character.glb`、`avatar.json`、`thumbnail.png` |
| 目录和预览 | `app/src/main/assets/avatars/catalog/catalog.json`、`app/src/main/assets/library/catalog.json`、`app/src/main/assets/library/head-ada-wong/preview.png` |
| 三处接入逻辑 | `AssetLibraryCatalog.java`、`MirrorHomeActivity.java`、`MirrorRolesActivity.java`（位于 `app/src/main/java/com/mirror/bench/`） |
| 真实目录回归 | `tests/AssetLibraryCatalogTest.java` |
| 版本和文档 | `app/build.gradle`、`README.md`、本文 |

最终构建/资产/验签证据位于 `E:/tripo/output/mirror-program/20261006/v34-ada-build/`，采用绑定当前设备回执 SHA 的 `build-provenance-v3.json`；真实设备截图、runtime status、退出线程与最终 `receipt.json` 位于同日期的 `v34-device-qa-v1/`。初次回执另存历史记录，最终结论采用完成真实选择变化检查后的当前回执。实际角色页和素材库截图为 `roles-scroll-1.png`、`library-ada-actions.png`，另保留 `home.png`、`ada-runtime.png` 和 `home-final.png`。实际 Java source 与 APK 校验日志分别位于 `app/build/asset-library-tests/runs/a4e88fc2-98c1-4fd9-8ac7-503e4451571b/` 与 `46a2ecf9-1743-4bd6-94d9-52de38e69bd4/`。这些是 V34 新证据，不沿用 V33 模型 gate 或 V32/V33 的界面证据冒充新验证。

预审草稿与六件 payload 位于 `E:/tripo/output/mirror-assets/20261006/ada-app-v34-integration-draft/`；实际 V33 Mali 模型 gate 位于 `E:/tripo/output/mirror-program/20261005/ada-v32-device-gpu-on-app-v33-v1/`。这些输出、主机中间文件、配置备份和 APK 不进入公共源码仓。

## 未完成的完整目标

本版没有接入新的三维背景。四件三维场景仍为静态预览/档案，现有四种图片与八种程序背景保留；隔离的 Portal 原型和未验收背景源码不在此版范围。

V34 新会话相机仍报 `CAMERA_ERROR (3)`，`endConfigure` 流配置失败，没有成功采集帧。本次没有测得本人面捕或联合 FPS；自然组合表情、个人映射确认和其它八个 IP 的完整校正仍待完成，16 视点以上 30 FPS 也未达标。增加试用角色不代表完整目标完成；在最终 13 路径冻结获准后按既有流程精确提交、普通推送并核对远端。历史条件见 [V33 记录](production-app-v33-20261005.md)、[V32 素材库记录](production-app-v32-20261005.md)和[完整目标规格](production-app-spec-20261005.md)。
