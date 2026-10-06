# Another You V36 / 0.2.5（2026-10-06）

本版增加默认关闭的材质纹理上传优化，用于减少杰洛特模型的 ORM 数据量。主页、角色页、场景、设置和素材库沿用 V35；历史资产保持原样。画面对比通过；联合短测未显示帧率提升，因此保持默认关闭。完整目标仍未完成。

## 实现和适用范围

只接受模型 SHA `9381f452c53098314f97e1a1799ec55d2be37878e66bf205c7a26958afcef531`，再逐项核对五个材质、三张图的尺寸和 SHA。所有材质的 metallicFactor 为零，ORM 的蓝通道不影响原着色结果；R 保留遮蔽，G 保留粗糙度。显式调试 Boolean `test_orm_rg8=true` 才从 RGBA8 改为 RG8。未知或改动过的模型回退 RGBA8，普通入口保持关闭。

保留原 GLB、颜色图、法线图、PBR shader、几何、表情和绘制顺序。RG8 上传在创建纹理时用 64 行缓冲提取 R/G，保留并恢复像素解包设置，沿用原 mip 链和 trilinear 过滤，没有每帧打包。完整 2048×2048 ORM mip 数据从 22,369,620 降为 11,184,810 bytes，三图合计逻辑数据从约 64 降为 53.33 MiB；这些是格式数据量，不是 Mali 驻留测量或内存/FPS收益。

格式依据：[OpenGL ES 3.0.6，纹理章节和表3.13](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf)。实际效果以设备对比报告为准。

## 检查入口

`AvatarPreviewActivity --ez verify_orm_rg8 true` 只在 debug 包接受明确 Boolean，禁止同时运行其它角色检查。设备生成带新 UUID 和上下文代数的 `files/avatar-orm-rg8-check.json`。

使用一个同步角色、相同 pose/VBO/颜色和法线纹理，仅切换独立上传的原 RGBA8 与候选 RG8。69 个固定姿态在串行 individual 与批量 OVR4 内分别比较全部 16 视点，共 2208 对；R/G/B/alpha 允许误差为零。报告同时检查实际 unit2 绑定、可见几何、16 个独立相机画面、姿态变化、清理和状态恢复。额外原始 ORM 仅在检查期间存在。阻塞 readback 只用于一致性验证，不作为运行帧率。

`run_runtime_check.py --orm-rg8` 为同包联合短测传入调试开关。去掉它就是原上传格式；不保存产品偏好。GPU 采样另有独立开关，本次对照保持关闭；不在 OVR framebuffer 上开启 timer query。

## 构建和主机验证

离线 Gradle assembleDebug 通过。版本 code36/name0.2.5；minSdk24、targetSdk30、arm64、主页启动和 CAMERA 权限保持。APK SHA `9a11385a4a4ab63a6971d210bb8c2ff6598c69768bbe26d586d957690b3dfe10`，130,604,967 bytes；证书仍为 `64a4af6baa9f10fd9d5bd09003ded7def368211604e3e91181bf015cdfbf6da8`，内部 debug 安装版。78 个 assets 和 9 个 native entries 与 V35 逐字节一致。

实际 SDK35 编译与主机检查：ORM policy/通道打包与真实 uploadMap 边界，PBR/颜色 shader 保留，双纹理创建失败和关闭恢复，strict pixel门；运行配置22项、预览调试配置16项、GPU配置24项、生命周期34项、表情选项22项、已有检查分派224项通过。Python collector47项通过。主机不模拟 Mali，设备检查单独记录。

## 设备结果与交付

实际 Mali-G52 的 fresh UUID `f8894a10-8e45-438f-84b9-dea67f43294c` 完成69姿态、2208层对、69次prepare；两种格式各1380次实际纹理绑定核对。所有RGB/alpha差异为零，两模式各67个姿态的中央画面不同于neutral，清理与状态恢复通过。516秒是阻塞诊断检查的总时长，不是运行帧率。

同一安装包、录制的动态人脸NV21、RKNN478点和mixed CPU/NPU52表情、真实杰洛特、16×400×640、批量OVR4、persistent FBO、cached camera VP共同运行。GPU采样关闭。每组45秒采集，仅统计确认INTERACTIVE内约29.7–29.9秒的SurfaceFlinger呈现，三组历史完整：

| 格式 | 实际呈现FPS | 整机CPU | GPU busy | NPU busy |
| --- | ---: | ---: | ---: | ---: |
| 原RGBA8 | 8.949 | 57.39% | 98.17% | 45.50% |
| 候选RG8 | 8.712 | 57.01% | 98.07% | 49.34% |
| 原RGBA8复测 | 8.889 | 56.72% | 98.38% | 50.00% |

没有达到30FPS，也没有证明RG8能提高帧率。候选较两组原格式均值低2.32%，三组顺序运行、温度60.6–70.6°C，因此不据此断定精确的因果退化或长期性能。候选PSS均值254.22MiB/峰值344.11MiB，两个原格式均值260.29/243.80MiB，包含可回收录像映射，不能用差值当作GPU驻留或实时相机内存节省。测试不包含USB采集或视频解码。

正常入口实际采用原RGBA8，GPU查询池仍为零，原杰洛特GL首帧ready。摄像头继续报Camera2流配置错误，没有采集帧；错误界面提供重试与导航，没有将录像冒充真人实时面捕。正常Back先收起菜单，再回首页，最终没有GLThread或AvatarPose工作线程。覆盖升级、全部检查和短测后，四份原配置逐字节保持。

原始证据保存在本机 `E:/tripo/output/mirror-program/20261006/v36-orm-rg8-pixels-v1/`、`v36-orm-rg8-ablation-v1/`、`v36-device-qa-v1/`，不进入公开仓。主机冻结说明在 `E:/tripo/output/mirror-assets/20261006/orm-rg8-candidate-v1/`。现有11个独立测试脚本只补新增类的显式编译依赖，隔离class-path从缺类RED到GREEN；管理、预览、视点选取检查通过。

本轮不增加可选模型。萨菲罗斯候选另在实际Mali完成10截图，以及9姿态×16层的串行/OVR与individual/batch核对，各144对零差异；头发边缘与牙齿自然度仍待美术完善，没有当前角色联合FPS或实时面捕结论。宿傩、摩尔的技术检查点保留，美术状态仍为未通过。

复现上传对照使用 `scripts/run_runtime_check.py --input replay --seconds 45 --checkpoint-seconds 0 --avatar-batched --persistent-fbos --cached-camera-vp --npu-blendshapes --view-preset 400x640 --view-count 16 --active-target-fps 31 --orm-rg8`，另须提供唯一 `--name` 和 `--output-dir`；去掉`--orm-rg8`即原格式。默认关闭保留为后续内存验证工具，下一步验证材质着色和实际多视图渲染成本。每版按准确冻结清单同步Git；最终提交SHA由仓库和本机同步回执记录。没有刷机、重启、改驱动、卸载或清除数据。全部旧模型与失败候选保留；新角色的艺术验收、三维背景接入、实时 USB 面捕和正式角色 16+ 视点30FPS仍未完成。
