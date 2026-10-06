# Another You V35 / 0.2.4（2026-10-06）

本版增加可关闭的 GPU 诊断，帮助定位正式头部模型的多视点渲染瓶颈。正式入口默认关闭，只有 debug 的明确 Boolean `test_gpu_profile=true` 或采集脚本 `--gpu-profile` 才开启。主页、角色、场景、设置和素材库保持 V34，实际 UI 截图已在会话展示。三个可选角色和 27 项素材保持原样；完整目标没有完成。

## 正确的测量范围

OVR_multiview 的原始扩展明确不允许在多视图 framebuffer 绘制期间使用 timer query；OVR_multiview2 不取消这一限制。第一次隔离候选误把查询包在多视图绘制外，实际 Mali 报 `a timer query is active and an OVR multiview drawcall is being performed` 后发生 native SIGSEGV。该 V1 候选已撤回，没有提交为可用版本。

本版 V2 只在绑定 framebuffer 0、完成原有状态/纹理准备之后，包住最终一次 `glDrawArrays`，结束后才执行原有同步。多视图阶段只有参数元数据，`views_gpu_status=unsupported_ovr_multiview`、`views_gpu_ns=null`；不能把帧间隔减去交织耗时当作多视图 GPU 耗时。背景融合在最终 shader 中，也不能单独拆出背景时间。

每 8 个真实绘制回调采一个查询，固定 16 个查询槽、128 个事件和 128 个参数记录。下一回调只读取已经 ready 的历史结果，计时不增加 glFinish、flush 或 fence，不阻塞等待查询完成。发生 disjoint、超出 500 ms 主机年龄或 unsigned 32-bit 饱和值时舍弃，GPU 耗时来自驱动 query，CPU 时间仅用于关联和有效期约束。即使设备报告 64 个 counter bits，当前 Android GLES30 的 unsigned 32-bit 读数也只在上述严格短时约束内使用，不声称读取了完整 64-bit 结果。

跨线程状态读取只取已发布 JSON；发布最多每秒一次。新 GL 上下文建立新查询池，不在新上下文删除旧数值 ID。正常上下文销毁由 EGL 回收所属对象；默认关闭时不创建查询 backend 或查询池。产品不新增诊断控件，渲染画面和用户操作流程保持原样。

依据：[OVR_multiview](https://registry.khronos.org/OpenGL/extensions/OVR/OVR_multiview.txt)、[OVR_multiview2](https://registry.khronos.org/OpenGL/extensions/OVR/OVR_multiview2.txt)、[EXT_disjoint_timer_query](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_disjoint_timer_query.txt)。计时是该绘制的驱动完成区间，不是独占 GPU busy，也不是屏幕呈现 FPS。

## 同 APK 实机结果

RK3566 / Android 11 / Mali-G52，实际完整杰洛特 SHA `9381f452c53098314f97e1a1799ec55d2be37878e66bf205c7a26958afcef531`；16 视点，每视点 400×640，最终 1200×1920。原 pitch=10（子像素）、tan=.2777777、镜像与场景原配置保持。OVR 批量、持久 FBO、cached camera VP、原 RKNN 478 点和 normalized mixed CPU/NPU 52 表情同时工作。输入为此前录制的动态人脸 NV21，包含推理和真实角色渲染，不包含 USB 采集或视频解码。

每组采集 45 秒，只统计确认 INTERACTIVE 的 SurfaceFlinger 实际呈现时间戳，排除启动、WAITING 和未知尾段。三个有效区间各约 29.8 秒，呈现历史完整：

| 采样 | 实际呈现 FPS | 整机 CPU | GPU busy | NPU busy |
| --- | ---: | ---: | ---: | ---: |
| 关闭，对照 | 8.915 | 55.97% | 98.67% | 47.97% |
| 开启 | 8.870 | 56.72% | 98.48% | 50.14% |
| 关闭，复测 | 8.865 | 56.85% | 98.24% | 48.55% |

资源均为对应确认互动区间，CPU 按四核整机 100% 归一化。开启组 PSS 平均 245.94 MiB、峰值 319.88 MiB，包含可回收的测试录像映射页，不能当成正式实时相机的常驻内存预算。

按提交单调时间、实际 face_active=true 和 16×400×640 参数去重筛选后，取得 33 个 VALID 最终绘制查询：GPU 耗时中位 16.258 ms、p95 16.629 ms；该段没有丢失参数元数据。上下文累计记录过一次 disjoint，采样器处理后只使用有效事件。开启 FPS 相对两次关闭均值约 -0.226%；三组顺序运行、温度约 57.2–69.4°C，所以只证明短测没有重现 V1 崩溃，不据此认定精确开销或长期稳定性。

上述完整正式模型仍未达到 30 FPS；历史诊断网格的 30+ FPS 不能作为当前角色的成绩。此前去掉背景图片只提升约 2.22%，当前需要继续验证多视图/PBR 负载的可控取舍。NPU 已在承担面捕，不能直接执行 OpenGL 的交织或材质着色。

## 恢复、退出与包一致性

实际 HOME/onPause/resume 两组各 55 秒：保留策略保持 context generation 1 并继续产出有效交织事件；调试释放策略重建 generation 2，`previous_generation=1`，恢复完整人脸进度、实际 GL 帧和新的有效查询。释放组自动恢复核对为 verified。暂停期间存在状态事件环缺口，因此这两组只用于恢复验证，不作为连续 FPS 达标证据，也不声称物理像素完全一致或所有驱动调用已被硬件追踪。

正式入口实际默认 `gpu_profile={requested:false,state:disabled,query_pool_capacity:0}`，包内杰洛特首帧 ready。相机仍报 `CAMERA_ERROR (3)` / `endConfigure` 流配置失败，没有成功采集帧；这次没有让离线测试冒充实时 USB 面捕。正常 Back 先收起实时菜单，再回到首页；最终进程线程清单没有 GLThread 或 AvatarPose 工作线程。Android/Mali 系统线程保留，不把线程检查说成 GPU 内存归零。

最终覆盖安装 APK `AvatarRuntime-v35-gpu-final-only-v2.apk`，131,060,060 bytes，SHA256 `3a179c3be168588d92b78280e65ccfd0bbc6d7caa4f9521ffdb4e40fd886d1de`。versionCode 35、versionName 0.2.4、minSdk 24、targetSdk 30、arm64，MAIN/LAUNCHER 仍为 MirrorHomeActivity，只保留 CAMERA 权限。实际验签证书仍为 `64a4af6baa9f10fd9d5bd09003ded7def368211604e3e91181bf015cdfbf6da8`，属于内部 debug 安装版。

最终包全部 78 个 assets、9 个 native libraries 与 V34 逐字节一致；未增加网络权限、私人视频、测试向量或新模型。安装及所有测试后，运行设置、场景画面设置、bundled 角色选择、导入角色 state 四份设备文件的 SHA 全部保持原值。没有刷机、更换驱动、卸载或清除数据。

## 复现与交付范围

使用已有设备上的录制参考，运行现有采集脚本：

```powershell
python scripts/run_runtime_check.py --name v35-final-gpu-check --input replay --seconds 45 --checkpoint-seconds 0 --avatar-batched --persistent-fbos --cached-camera-vp --npu-blendshapes --view-preset 400x640 --view-count 16 --active-target-fps 31 --gpu-profile
```

去掉 `--gpu-profile` 即同包关闭对照，不写产品设置。输出文件名必须使用新名称，原始采样记录不覆盖。

当前生产源码/实际编译产物验证：GPU probe 91 项、final wrapper 82 项、GLES adapter 19 项；InputOptions/config 24 项；GL lifecycle host 34 项；Python collector 45 项通过。主机测试不模拟 Mali；上述实机证据单独保存。没有增加与改动无关的完整重测。

本版源码仅 19 个路径：`app/build.gradle`；`MirrorActivity.java`、`InterlaceRenderer.java`、`GpuTimerProbe.java`、`Gles30GpuTimerBackend.java`、`RuntimeGpuProfile.java`（均在 `app/src/main/java/com/mirror/bench/`）；`scripts/run_runtime_check.py`；`tests/GpuProfileConfigTest.java`、`run_gpu_profile_config_tests.ps1`、`GpuTimerProbeTest.java`、`GpuTimerAdapterTest.java`、`RuntimeGpuFinalProfileTest.java`、`run_runtime_gpu_profile_tests.ps1`、`test_runtime_metrics.py`；三个 `tests/gpu-timer-stubs/android/opengl/` 的 `EGLContext.java`、`EGL14.java`、`GLES30.java`；`README.md` 和本文。其它候选/实验源码、模型、输出、APK 和私人数据不按目录混入提交。

本地证据在 `E:/tripo/output/mirror-program/20261006/`：`v35-gpu-profile-build/build-provenance-v4.json`、`v35-gpu-ablation-v2/comparison.json`、`v35-gpu-lifecycle-v2/recovery-verified.json`、`v35-device-qa-v1/receipt.json`。最新实际界面为 `v2-home.png`、`v2-roles.png`、`v2-home-after-back.png`；原始报告、查询事件、前后配置与失败 V1 驱动证据保留在本机，不进公共源码仓。

仍待完成：其它八个 IP 的独立形体/材质/映射校正，艾达个人实时确认，更多三维场景接入，摄像头流恢复后的本人联合验证，以及正式角色 16+ 视点 30 FPS。下一轮先用真实模型做分辨率和材质成本对照，再按通过的模型 SHA 逐个接入。此前角色状态和全目标见 [V34](production-app-v34-20261006.md) 和 [正式 App 规格](production-app-spec-20261005.md)。
