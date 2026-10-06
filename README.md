# Another-You-3D-mirror-

裸眼 3D 互动魔镜工程：USB 摄像头面捕、RK3566 NPU 推理、角色表情驱动、OpenGL ES 多视点渲染与交织输出。当前实验设备为 YS-L6 / RK3566 / Android 11 / 2 GB，屏幕竖向 1200×1920。

此仓库保存当前源码与固定版本的生产运行依赖。原仓库 `LICENSE` 与初始提交保留；本地实验工程的旧 Git 历史、个人录像和设备私有配置没有导入。

## 当前版本

当前版本为 **V42 / 0.2.11，最终候选 v2**（versionCode 42），源工程冻结点 `83d7123d0824589e37d2d8ad757d21f14ca306c4`。新增**默认关闭、仅调试会话启用**的空白区域交织采样候选；普通配置仍为16视点 / 400×640，完整PBR与全部角色素材保持。

- 候选不减少真实视图数量：仍渲染16个不同相机矩阵的完整角色。只在当前实际上传几何的保守屏幕包围矩形外跳过RGB三次视图数组采样；背景无条件按原方法计算。每次成功VBO revision更新扫描6float布局，以已显示world/fit和全部实际VP投影；无效输入或near/W不确定时完整采样，生命周期边界撤销旧bounds。
- 实际Mali上三个角色各95项，共 **285项最终1200×1920 RGBA逐字节零差**，实际program/uniform及非平凡生效守卫通过。每fixture共享同一次prepare和同一16层array；69姿态/组合、12背景、8变换、6光学参数组合。中性与all-controls另核全部16层边界外清零。范围限已测角色/参数/设备，不是所有极端导入资产或GPU的通用证明，也不是角色艺术/光学校准全部完成。
- 同APK A-B-A请求各60秒，完整Geralt（19,907顶点 / 19,157三角形）、原PBR、NV21录像回放、RGA/RKNN478、CPU规范化与混合52、异步变形和持久OVR4的确认INTERACTIVE呈现为 **8.920 / 8.957 / 8.932 FPS**。完整采集门均true，但**预先固定35秒窗口的三个完整门均false**（GRACE/短末段），原失败和首组原始采集保留，没有换窗口或重采reference挑结果。录像不含USB采集或视频解码，未知约4.8秒尾段排除；不是长测或同温因果结论。
- CPU整机约56.27% / 59.28% / 55.96%，GPU仍约98%，NPU约49%；**未证明有用FPS收益，候选继续默认关闭，30FPS仍未达到**。最后动态快照16.49%几何空白与2.34倍中性fixture整屏边界不同，不能当平均带宽节省；约0.992ms边界projection均值不含上传扫描。NPU没有接管PBR/光栅工作。
- 1,025项CPU几何、76项实际Activity/输入边界检查及358项独立像素/APK证据检查通过。最终普通入口候选关闭、新相机会话的角色渲染正常；原Camera2流配置错误仍在，**未恢复真人USB采集**。自然两次Back先收菜单再回首页，没有GL/pose/runtime线程；四份原配置逐字节保持。
- **78 assets、9 native entries与V41逐字节一致**；原签名、15项固定依赖、LICENSE/NOTICE及V35–V41历史保留。本轮没有新增或逐项校正角色。私人录像/数组/配置、APK、截图/QA/output/build和未选模型流水线WIP不发布。

详见 [V42候选与失败边界](docs/production-app-v42-20261006.md)、[V41校准历史](docs/production-app-v41-20261006.md)、[V39比例实验](docs/production-app-v39-20261006.md)、[正式App规格](docs/production-app-spec-20261005.md)和[同步历史](docs/repository-sync-20261005.md)。完整模型校正、三维背景、实时采集与16+视点30FPS总目标继续未完成。

## 目录

| 路径 | 内容 |
| --- | --- |
| `app/` | 面捕、NPU、角色管理、渲染、交织与校准 Android 应用 |
| `app/src/main/assets/avatars/catalog/` | 运行角色轻量目录、杰洛特与艾达模型、manifest、真实缩略图 |
| `app/src/main/assets/library/` | 27 项源资产、认证静态预览与就绪状态目录 |
| `launcher/` | 轻量设备桌面 |
| `native/` | RGA / RKNN / 多视点 JNI 源码及保留原声明的 SDK 头文件 |
| `scripts/` | 构建、检查、资产处理与设备测试脚本 |
| `tests/` | JVM、C、Python 和 Blender 检查入口 |
| `runtime-assets/geralt/stage13-candidate/` | 先前候选快照与作者检查入口，历史状态保留；当前 APK 使用 catalog 目录 |
| `docs/` | 设计、测试条件、历史结果和当前状态说明 |
| `third-party-notices/` | 实际依赖随附声明和来源说明 |

默认包内目录角色为杰洛特；原创 CC0 向导 `builtin-guide` 仍保留为旧版参考（29,482 三角面，在现有 loader 30k 上限内，超后续新头部 20k 目标）。首次升级时隐式默认不覆盖既有导入选择；用户显式选择包内角色才改变优先级。设备上的私有选择与光学校准不上传。精确资源与发布范围见 `product-release-manifest.json`、`source-sync-manifest.json` 和 `production-dependency-manifest.json`。

## 构建

要求 Java 17、Gradle 8.5、Android SDK platform 35 / build-tools 35.0.1，AGP 8.2.2；当前 ABI 为 `arm64-v8a`，minSdk 24 / targetSdk 30。仓库没有 Gradle wrapper，使用本机固定版本的 Gradle。

在仓库根目录新建本机的 `local.properties`，例如：

```properties
sdk.dir=C:/Android/Sdk
```

运行：

```powershell
& 'C:/Tools/gradle-8.5/bin/gradle.bat' --no-daemon :app:assembleDebug :launcher:assembleDebug
```

Gradle 路径和 SDK 路径应按本机安装位置替换。首次构建需要下载 Maven 依赖；依赖缓存和 Android SDK 未上传。当前设备构建使用既有固定缓存，全新机器的无缓存构建尚未独立验证。输出位于 `app/build/outputs/apk/debug/` 与 `launcher/build/outputs/apk/debug/`。

生产 Google/RKNN 模型和匹配的 `jniLibs/arm64-v8a` 均按稳定 v30 APK 的解压字节冻结，哈希见生产依赖清单。它们可直接参加构建，无需更换设备 runtime、驱动或刷机。重新编译原生部分需要 Android NDK r25c 和固定版本 RGA，见 [原生依赖](native/DEPENDENCIES.md)。重新获取或转换模型的脚本仍保留，见 `scripts/prepare_assets.py`、`scripts/convert_face_models.py` 与 `scripts/prepare_npu_assets.py`；不要用新版本依赖覆盖固定生产资产。

签名私钥没有上传。在另一台电脑生成的新 debug 签名可能与当前设备应用不同；构建成功不代表能够直接覆盖设备上已安装的 APK。

## 离线检查

表情映射检查不需要设备或个人录像：

```powershell
./tests/run_face_playback_tests.ps1 -JavaHome 'C:/Program Files/Java/jdk-17'
```

GLB / worker 检查使用真实 JSON-java 20240303。按 [loader 检查说明](tests/avatar-loader.md) 准备固定哈希的 JAR，再运行：

```powershell
./tests/run_avatar_tests.ps1 -JavaHome 'C:/Program Files/Java/jdk-17' -AssetPath './runtime-assets/geralt/stage13-candidate/character.glb'
./tests/run_avatar_worker_tests.ps1 -JavaHome 'C:/Program Files/Java/jdk-17' -AssetPath './runtime-assets/geralt/stage13-candidate/character.glb'
```

新增角色目录、选择失败事务与两实际包内模型校准检查：

```powershell
./tests/run_bundled_avatar_tests.ps1 -CatalogPath './app/src/main/assets/avatars/catalog/catalog.json'
./tests/run_camera_preview_bundled_tests.ps1
```

V32 素材库与真实最终 APK 检查：

```powershell
./tests/run_asset_library_tests.ps1 -AssetsRoot './app/src/main/assets'
./tests/run_asset_library_tests.ps1 -Apk 'PATH-TO-YOUR-BUILT-APK'
./tests/run_asset_library_lifecycle_tests.ps1
```

目录/实际 APK 源与完整 PNG 解码共 187 项检查通过；真实 Activity 选择边界并发检查 22 项通过。生命周期 runner 使用实际 Android SDK 和已编译 app 依赖，默认路径可通过参数替换，不会自行启动 Gradle。补充已知资产核对可运行 `python scripts/verify_library_assets.py --assets app/src/main/assets`；使用未优化的 Python，不以该脚本代替严格 Java 目录/GPU/艺术门槛。

V31 实际通过 Catalog/Selection 97 项、UI/StartupGate 51 项、两个真实 GLB 校准加载 16 项及相关 SDK/管理/renderer 回归。主机统计与成功当前 EGL 首帧是不同门槛；主机检查不能证明物理相机或 30 FPS。五个 `asset_pipeline` profile/base/segmentation 文件仅为已知本地输入实验，配套四项测试不证明通用模型身份校验、完整面部绑定或 Ada 的艺术验收；其余管线和实验模型输出未上传。

牙齿检查器的 3 项异常变更回归需要本地 stage12-safe、完整 stage13 作者报告及实际 pose fixtures。公开候选目录只带 GLB/清单，不包含这些前序作者输入；全新 checkout 运行 `tests/test_dentition_appearance_gate.py` 时会明确 skip，不能把 skip 当作回归通过。原实验环境中这 3 项回归以及实际 19 姿态/17520 闭唇采样已通过。

Blender 作者检查使用 Blender 4.5.12 LTS，Python 几何检查通常需要 NumPy / Pillow。检查日志与完整工程资产并非所有测试都已上传；各历史文档中的本地 `E:/tripo/output/...` 仅是原实验记录位置，不是仓库内存在的文件。

## 私有测试数据

没有上传个人视频、NV21 录像、捕获到的人脸截图、真实面部坐标与表情数组、设备私有 preferences、签名密钥或 API 凭据。

`app/src/main/assets/expression-validation/*.f32` 中的固定 92 组 fixture 含真实人脸样本，因此从 Git 和产品 APK 打包排除，源本地原件保留。`ExpressionAppCheckActivity` 的这组哈希固定验证需要本地原 fixture；缺失必须明确报错，不能用不同数据替换后声称原验证仍通过。使用自己的本地视频执行 replay 测试，需要另行记录测试数据来源与条件。

原始实验说明保存在 [device-lab 历史说明](docs/device-lab-history.md)，当前部署状态和限制应以本次同步说明为准。

## 许可与来源

项目原有 MIT `LICENSE` 保留。内置原创角色的 CC0 声明、SDK 文件原声明、NDK 声明及生产模型/二进制来源分别保留，见 `third-party-notices/`。项目 MIT 不改变第三方文件的原声明；RKNN 文件按其实际附带声明记录，不能因为它随项目上传而重新标为 MIT 或 Apache。
