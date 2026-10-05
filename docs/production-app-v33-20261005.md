# Another You V33 / 0.2.2（2026-10-05）

本版仅改善杰洛特在角色页和素材库中的头像可读性。此前预览有大量黑色余白，头像在卡片中太小；新版使用同一最终模型的完整正面中性渲染，保留白发、头部和短颈，不改变模型、材质、表情绑定或卡片尺寸。最终 APK 已安装，角色页与素材库的真实静态界面已确认头像更清晰、没有裁掉头部；本版验收范围限于静态界面、资源与退出行为。

## 缩略图来源和范围

源/包内杰洛特模型 SHA256 均为 `9381f452c53098314f97e1a1799ec55d2be37878e66bf205c7a26958afcef531`，模型和 `avatar.json` 字节保持不变。预览来自该模型现有的 `dental-preview/neutral-front.png`，源图 800×800，SHA256 `6b419cdce6e7f7e1ec4d433e1bc8706ad6a9d330b67a77c8ca52a6ab02d30d57`。对应渲染报告与生产 AvatarRig 姿态记录都绑定相同模型；中性输入为空、58 个形态键权重全为零。

官方 Blender GLB 导入器保留模型自身 PBR 材质和纹理，按真实生产节点矩阵离线渲染。本次只对现有整张 PNG 做 Lanczos 缩放，未裁剪、调色或用 AI 重新绘制。Blender 灯光与 AgX 颜色管理不同于设备渲染；这张静态预览不证明 Android 材质一致、裸眼光学效果、实时表情美术或帧率通过。

两处新图都是 512×512、305,556 bytes，SHA256 `afa5fd271997149f559fefad198cec248e2424197fd24ed4c6ac58bc09fef6a1`：

- `app/src/main/assets/avatars/catalog/geralt/thumbnail.png`
- `app/src/main/assets/library/head-geralt/preview.png`

素材库仅更新杰洛特已有的 `previewSha256`；角色目录协议与字节保持不变，精确缩略图哈希由发布记录追踪。参考向导的完整头部和胸像预览保持原样。按平坦背景像素差估算，杰洛特主体占图高从约 30.6% 提高到 87.7%，约为原来的 2.87 倍；这是构图估计，实机可读性以设备截图为准。

## 验证与实机结果

- applicationId `com.mirror.bench`，versionCode 33，versionName 0.2.2，仍使用既有内部安装证书，未设置商店发行签名。
- 最终 APK `AvatarRuntime-v33-role-thumbnails.apk`，125,102,232 bytes，SHA256 `b797f3a708eee30373fe7519b73f55f700aa8fa9dfa238682007385ba649209d`。离线构建、验签和覆盖安装通过；证书 SHA256 保持 `64a4af6baa9f10fd9d5bd09003ded7def368211604e3e91181bf015cdfbf6da8`。
- 最终 APK 中相较 V32 唯一的资产差异是两张杰洛特 PNG 与素材库 `catalog.json`；其它资源，包括模型、manifest、向导和九个原生库，都与 V32 逐字节一致。权限仍只有 CAMERA，未打包私人人脸验证向量、录像或凭据。
- 实际最终 APK 的 27 条目录、原件/预览哈希及两项实时角色引用，通过现有严格 Java 校验器的 187 项检查。缩略图编辑结束时，169 个保护文件二次 SHA 核对不变；版本号的明确更新单独纳入发布范围。
- 真机按首页→角色页→素材库→返回角色页→返回首页验证。`roles.png` 与 `library.png` 显示杰洛特完整头像更清晰，UI 控件边界与 V32 保持一致；四份配置 SHA 全部保持验收前原值。
- 最终首页按实际 `/proc/<PID>/task/<TID>/comm` 检查，无素材预览、AvatarPose 或 GLThread 工作线程。该 Android 的 `ps NAME` 只给进程名称，未采用其假阴性结果；Android/Mali 系统线程仍保留，不把工作线程退出等同于 GPU 内存归零。
- 本版没有打开实时魔镜，没有新的摄像头、GPU 首帧、表情或联合性能验收；这些结果没有沿用 V32 数据冒充本版测量。
- V33 同步计划限定六项源路径：两张 PNG、素材库 `catalog.json`、`app/build.gradle`、`README.md` 和本文。源码同步不纳入模型校正中的脚本、候选模型、输出、APK、个人影像、关键点或设备私有配置。

本地缩略图来源、旧图备份、冻结文件 SHA 和主机日志位于 `E:/tripo/output/mirror-assets/20261005/production-app-release-audit/role-thumbnail-refresh-v1/`。最终构建证据是 `E:/tripo/output/mirror-program/20261005/v33-thumbnail-build/build-provenance-v2.json`；真实设备证据位于同日期的 `v33-device-qa-v1/`，包含 `receipt.json`、`home.png`、`roles.png`、`library.png`、`home-final.png` 和 `home-final-thread-comms.json`。实际最终 APK 校验日志在 `app/build/asset-library-tests/runs/e676cc13-a830-4bc2-91fe-0520b3b56b7d/`。这些本地输出与安装包不上传公共源码仓；源码在最终六路径冻结后按原流程同步并核对远端提交。

## 保留的内容和未完成目标

素材库仍为 27 项、55 个文件，因预览图片变大，当前共 79,536,006 bytes；可用于实时魔镜的角色仍只有杰洛特和镜中向导。十个 IP 身份中的其余九个头部仍在逐模型校正，完整面部质量验收没有完成；本版不提升这些条目的可用状态。四件三维场景仍是原件预览/档案，尚未接入实时背景；现有四种图片和八种程序背景保留。

最近 V32 运行尝试没有摄像头采集帧，并报 `CAMERA_ERROR (3)` 流配置错误；本版未重测摄像头，不宣称该问题已解决。完整实时面捕、完整模型校正、三维背景接入和 16 视点以上 30 FPS 仍未完成，缩略图清晰度改善不能代替这些门槛。已验收的素材库行为和历史性能条件分别见 [V32 记录](production-app-v32-20261005.md)与[完整目标规格](production-app-spec-20261005.md)。
