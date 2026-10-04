# 当前工程仓库同步（2026-10-05）

目标仓库：`https://github.com/lNessie-model/Another-You-3D-mirror-.git`。

保留目标仓库原 `main` 初始提交 `56cb76ff36d3ad6a5faf096a152d5b079b6e7346` 与原 `LICENSE`。源工程冻结于本地 `device-lab` 提交 `30e4fe7b3506c474753610578b0435f7d47bfe33`；其中 `367a3c3` 保存眉眼/牙齿修正，`30e4fe7` 独立补强验证器及异常变更回归。目标采用独立源码快照，本地旧 Git 历史没有导入目标仓库。

同步了应用、轻量桌面、native 源码、脚本、测试、设计及历史验证文档、内置原创 CC0 角色、四张场景背景。按固定清单加入 15 个现有生产模型/原生库，共 21,894,016 字节；它们与稳定 v30 APK 的对应 ZIP 条目逐字节一致。附带 Google / NDK 原 NOTICE、vendor 头文件原声明和来源说明，不重新指定第三方许可。

牙齿候选保存于 `runtime-assets/geralt/stage13-candidate/`。其模型 SHA-256 为 `9381f452c53098314f97e1a1799ec55d2be37878e66bf205c7a26958afcef531`，状态为 **GPU_VERIFIED_CANDIDATE / LIVE_CAMERA_PENDING**。GPU 验证通过与现场部署验收分别记录；相机故障后设备已回退至 stage11，APK 仍为 v30。本候选没有新的帧率实测，16 视点 30 FPS 仍未达标。

私有数据排除范围：

- 原录像、NV21、个人摄像头截图与原始设备采集输出。
- `expression-validation` 的三个 `.f32` 文件，固定 92 组 fixture 中含真实人脸样本。
- 设备私有 preferences、备份配置、签名私钥、API 凭据。
- 生成 APK、Blender 中间文件、失败模型和全部 `output/` 目录。
- 未接受的 `scripts/refine_brow_local_mesh.py` 局部加密实验。

源码复制前后均核对 SHA-256。唯一有意修改的源复制文件是目标仓库 `.gitignore`：增加私有数据排除及固定生产资产白名单。源 README 原文保存于 `docs/device-lab-history.md`，目标 README 保留原项目标题并给出当前状态和构建入口。`.gitattributes` 禁用换行自动转换，保持冻结字节。逐文件哈希见 `source-sync-manifest.json` 和 `production-dependency-manifest.json`。

全新构建需要 Java 17、Gradle 8.5、SDK 35 / build-tools 35.0.1 与 Maven 依赖。本机 SDK、依赖缓存、个人数据和签名密钥不在仓库内。原生库已按稳定版冻结，可直接打包；重新编译的版本应另行验证，不替换既有设备驱动。fixture 专项验证需要本地私有数据，不能把普通构建或离线检查当成原 92 组专项测试通过。

牙齿验证器额外要求 primitive binding/mode、数量与字段集合保持，并要求中立和张颌闭唇姿态均实际采样。主 agent 已运行实际 19 姿态及 17,520 个闭唇样本，3 项异常变更回归通过。回归依赖未随仓库上传的 stage12-safe 与完整 stage13 作者报告/pose 文件，全新 checkout 会明确 skip 这 3 项；skip 不能写成验证通过。

本轮 repo sync 不调用 ADB、不修改设备、不更换远端、网络或认证设置；普通提交与 push 保留远端历史，未使用 force。
