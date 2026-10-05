# V31 工程增量同步（2026-10-05）

目标仓库：`https://github.com/lNessie-model/Another-You-3D-mirror-.git`。

保留目标仓库原 `main` 初始提交 `56cb76ff36d3ad6a5faf096a152d5b079b6e7346`、第一次快照 `c966355fa866baa4f1d3fb4bcb66b95eab3aa996` 与原 `LICENSE`。V31 源工程冻结于本地 `device-lab` 提交 `8dcacdfb68eb305365f6bbac04e9f016abf765e4`；上一源提交 `30e4fe7b3506c474753610578b0435f7d47bfe33` 保存此前眉眼/牙齿及验证器。目标继续采用独立源码快照，本地旧 Git 历史没有导入目标仓库。

本轮只同步 50 个明确冻结文件：正式 UI、原生安全弹窗与触控尺寸、单 launcher、APK 人脸 fixture 排除、轻量 catalog/选择、两角色加载与校准桥接、对应回归与真实 JSON/生命周期 runner、规格/发布/tasks 文档，以及五个明确标为实验的离线工具。其余 retopo/atlas/眉眼口作者管线、model_quality_audit、endpoint 未审实验都留在本地，没有删除或重置。

最终 Code31/Name0.2.0 APK SHA-256 为 `2093ac779d8ed6e1391345937d050f73182b2a305ca928b24a5ad6faf4375767`，原签名与 109 个 APK 源文件核对通过。实际设备验收只覆盖 UI、资源、导航、配置保留和退出；完整实时面捕仍受 CAMERA_ERROR 流配置影响，个人表情美术及联合 30 FPS 不通过。详见 [V31 发布说明](production-app-v31-20261005.md) 与 `product-release-manifest.json`。APK 和原始设备证据没有上传。

同步了应用、轻量桌面、native 源码、脚本、测试、设计及历史验证文档、内置原创 CC0 角色、四张场景背景。按固定清单加入 15 个现有生产模型/原生库，共 21,894,016 字节；它们与稳定 v30 APK 的对应 ZIP 条目逐字节一致。附带 Google / NDK 原 NOTICE、vendor 头文件原声明和来源说明，不重新指定第三方许可。

旧候选 `runtime-assets/geralt/stage13-candidate/` 保留原始快照，其当时的 GPU_VERIFIED_CANDIDATE / LIVE_CAMERA_PENDING 状态不覆盖本版设备记录。V31 实际包内模型位于 `app/src/main/assets/avatars/catalog/geralt/`，模型 SHA-256 同为 `9381f452c53098314f97e1a1799ec55d2be37878e66bf205c7a26958afcef531`；参考向导保留原目录，SHA-256 `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407`。两者真实 GPU 首帧和角色切换通过，但不能以模型加载通过替代 live-camera/艺术/性能验收。

私有数据排除范围：

- 原录像、NV21、个人摄像头截图与原始设备采集输出。
- `expression-validation` 的三个 `.f32` 文件，固定 92 组 fixture 中含真实人脸样本。
- 设备私有 preferences、备份配置、签名私钥、API 凭据。
- 生成 APK、Blender 中间文件、失败模型和全部 `output/` 目录。
- 未接受的 `scripts/refine_brow_local_mesh.py` 局部加密实验。

源码复制前后均核对 SHA-256。唯一有意修改的源复制文件是目标仓库 `.gitignore`：增加私有数据排除及固定生产资产白名单。源 README 原文保存于 `docs/device-lab-history.md`，目标 README 保留原项目标题并给出当前状态和构建入口。`.gitattributes` 禁用换行自动转换，保持冻结字节。逐文件哈希见 `source-sync-manifest.json` 和 `production-dependency-manifest.json`。

全新构建需要 Java 17、Gradle 8.5、SDK 35 / build-tools 35.0.1 与 Maven 依赖。本机 SDK、依赖缓存、个人数据和签名密钥不在仓库内。原生库已按稳定版冻结，可直接打包；重新编译的版本应另行验证，不替换既有设备驱动。fixture 专项验证需要本地私有数据，不能把普通构建或离线检查当成原 92 组专项测试通过。

牙齿验证器额外要求 primitive binding/mode、数量与字段集合保持，并要求中立和张颌闭唇姿态均实际采样。主 agent 已运行实际 19 姿态及 17,520 个闭唇样本，3 项异常变更回归通过。回归依赖未随仓库上传的 stage12-safe 与完整 stage13 作者报告/pose 文件，全新 checkout 会明确 skip 这 3 项；skip 不能写成验证通过。

本轮 repo sync 不调用 ADB、不修改设备、不更换远端、网络或认证设置；普通提交与 push 保留远端历史，未使用 force。连接使用 Windows 既有代理 `127.0.0.1:33210`，仅 Git `-c http.proxy=...` 命令作用域，不改变全局 Git 或 TLS 校验。完成状态以推送后独立 `ls-remote` 返回的完整 SHA 与本地 HEAD 比较为准；操作回执保留本地。
