# Another-You-3D-mirror-

裸眼 3D 互动魔镜工程：USB 摄像头面捕、RK3566 NPU 推理、角色表情驱动、OpenGL ES 多视点渲染与交织输出。当前实验设备为 YS-L6 / RK3566 / Android 11 / 2 GB，屏幕竖向 1200×1920。

此仓库保存当前源码与固定版本的生产运行依赖。原仓库 `LICENSE` 与初始提交保留；本地实验工程的旧 Git 历史、个人录像和设备私有配置没有导入。

## 当前版本

V57 / 0.2.26 已覆盖安装。仅改良 Geralt 牙齿：缩短两端长牙、收紧间距、平顺牙弓；暖白色保持，高光更柔和。上一版较小嘴部范围 0.8 / 0.8 / 12° 保留。真实设备317项检查通过，口腔和牙齿仍为轻量简化结构；本版未重测帧率，未完成本人USB面捕或16+视点30FPS目标。

本版验证范围为牙齿姿态与实际静态预览；本版未重测帧率，不宣称性能提升或30FPS达标。

详见[V57说明](docs/production-app-v57-20261008.md)。

实际嘴部预览与验收范围见本版发布说明。

### 上一轮 V56（历史记录）

V56 / 0.2.25 已覆盖安装。Geralt 的下颌贡献缩小 20%，三处同步为 0.8 / 0.8 / 12°；保留本轮口腔 PBR 显色和 U 形下唇改良，微笑与眉眼保持。真实设备 317 项检查与 32 组 Java 权重/节点矩阵等效检查通过。口腔仍为简化结构；最终缩幅版未重测帧率，未完成本人 USB 面捕或 16+视点 30FPS 目标。

本版验证范围为嘴部姿态与实际静态预览；最终缩幅版未重测帧率，不宣称性能提升或30FPS达标。

详见[V56说明](docs/production-app-v56-20261008.md)。

实际嘴部预览与验收范围见本版发布说明。

### 上一轮 V55（历史记录）

V55 / 0.2.24 已覆盖安装。本轮调整人像 PBR 光照与连续亮部过渡，改善幅度温和；精简高光函数保留十姿态实机像素结果。99 项 APK 资产、九项原生库和原签名保持，素材库 36 项、四个既有实时角色不变。

最终 Mali 多视图验证 317 项通过，两组各 144 层 RGBA 零差，18 个诊断/配置槽位恢复。这里的零差是新版本跨绘制路径比较，并不表示 V54 与 V55 画面相同。最终录像整链短测在约 14.731 秒确认互动窗口内为 10.332 FPS，未确认尾段排除；热状态与不同窗口限制比较，16 视点 30FPS 和本人 USB 面捕仍未完成。

原曲线三组与等价精简曲线一次录像整链短测的结果和限制见发布说明；不宣称性能提升或30FPS达标。

详见[V55说明](docs/production-app-v55-20261008.md)。

[查看实机前后对比](review-assets/v55-natural-portrait-v1/actual-device-before-after.png)。

### 上一轮 V54（历史记录）

V54 / 0.2.23 已覆盖安装。新增阿斯代伦风格头部原件及实际 GLB 材质预览，等待面部动作校正；亚玟发顶/灰白条/颈边缺陷明确归档。素材库36项：4可用、18静态预览、14档案，四个既有实时角色保留。

实际 APK 素材库检查218项通过。设备首页、角色入口和两份新原件PNG预览、返回首页及四配置字节保持已核对。旧94个非catalog素材和九个原生库字节保持。本轮Tripo实际240积分，两款各一次生成。

里昂眉部仅五个控制/13有限姿态，眼嘴研究候选未接入。没有本轮USB、GPU或联合性能测量，30FPS未完成。详见[V54说明](docs/production-app-v54-20261008.md)。

### 上一轮 V53（历史记录）


V53 / 0.2.22 已覆盖安装。新增蒂法、里昂、暴风女三款成人头部原件与真实 GLB 材质预览，素材库共34项；四个既有实时角色保留。三个新头像仍为静态预览，面部绑定尚未完成。

实际 APK 素材库检查212项通过，设备首页、角色入口及三张新素材预览已核对，浏览后设置字节一致。全部其它V52素材与九个原生库保留。本轮 Tripo 实际使用360积分。

自然度研究已推进眼睑、微笑与嘴边连续性，但候选仍有厚眼环和下颌动作问题，未替换正式角色。本轮没有重测USB面捕或联合性能；最近V52的16视点完整PBR联合实测约9.56FPS，30FPS目标尚未完成。详见[V53说明](docs/production-app-v53-20261008.md)。

### 上一轮 V52（历史记录）

V52 / 0.2.21 已覆盖安装。新增本次运行的个人眉部静止偏置校准，减少持续压眉，保留真实皱眉端点。新增宿傩试用预设，共四个可选角色；新哥特女巫、银发精灵、仿生人及首版原件已打包，共31项素材。

宿傩实际普通PBR首帧、选择/恢复、校准故障取消、素材预览、版本/Back/Java退出与四配置核验通过。45秒录像NPU面捕与16独立400×640视点同时运行，主要交互区间9.559呈现FPS；末尾极短区间无帧，完整门false，尚未达到30FPS。没有同包性能对照，不作提升或回归结论。

哥特女巫贴图已修复，绑定美术尚未通过，只有静态预览；其它新首版有发顶缺损，保留档案。宿傩嘴部/牙齿及完整历史角色仍待校正。USB流配置错误仍存在，未作本人实时面捕验收。本轮Tripo实际使用140积分。详见[V52说明](docs/production-app-v52-20261007.md)，APK与原始QA保留本地。

## 目录

| 路径 | 内容 |
| --- | --- |
| `app/` | 面捕、NPU、角色管理、渲染、交织与校准 Android 应用 |
| `app/src/main/assets/avatars/catalog/` | 运行角色轻量目录、杰洛特/艾达/宿傩模型、manifest、真实缩略图 |
| `app/src/main/assets/library/` | 36 项源资产、认证静态预览与就绪状态目录 |
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
