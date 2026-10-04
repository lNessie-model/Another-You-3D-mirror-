# 主程序运行基础：2026-10-03 验证记录

本记录验证持续运行入口与裁剪优化，不是整个互动魔镜的完成声明。当前画面仍为四形变诊断椭球；正式眼口角色、角色导入、软件校准、长时热稳定与实屏光学体验尚需完成。

## 构建与环境

- 设备：`6L32552009566714`，RK3566，Android 11，物理输出 1200×1920 竖屏。
- 对照 APK：`RuntimeCropOptimized.apk`，SHA256 `7abfd399facfd9374f4049b7bd77689ab163689becb763bc1a759826ffe375b7`。
- 新基础 APK：`RuntimeFoundation.apk`，SHA256 `cf1951bcebe3a0650e36c92b1bfe18a93997cdd7f4a28137866af2cb14c77a75`。增加处理阶段超时诊断、采样时间竞态修复和异常安全清理。
- 在此基础上补齐无 worker 时的权限/参数错误报告：`RuntimeFoundation-v2.apk`，SHA256 `cd4f4bcc97f2352cb57dfdaeb43a0d32df37218146e5284d668ec09c8a220e81`。120 秒连续数据来自上一基础 APK，v2 的独立设备结果以文件名 `*-v2-*` 和报告中实际 APK hash 为准。
- APK、原始 JSON、日志均在 `E:/tripo/output/mirror-program/20261003/`。模型与驱动未更换；未改系统分区、CPU/GPU/NPU 频率或调试通道。
- `scripts/test_java.ps1`：6 个 YUV 用例、92 个清理断言、149 个交互断言、320 个 watchdog 断言、86 个 renderer 断言（含百万帧常量空间检查）通过；这是主机逻辑证据，不替代 Android 生命周期与实际 GPU 测试。

## 已取得的设备证据

| 项目 | 结果 | 原始文件 |
| --- | --- | --- |
| 相机失败构造泄漏 | 修复前 30 次非法尺寸打开使采集线程 0→30；修复后 0→0。重复关闭、持有帧时关闭、等待取帧唤醒通过 | `camera-lifecycle-red.json`、`camera-lifecycle-green.json` |
| 原始/优化裁剪 | 360 组、176,947,200 字节，0 字节及 float bit 差异 | `npu-crop-check.json` |
| 串行/并行完整输出 | 24 帧含 2 次黑帧、22 次完整脸；478 点、52 表情、16 pose float 最大差异均为 0，重获通过 | `npu-pipeline-optimized.json`、`npu-pipeline-foundation.json` |
| 与 MediaPipe 精度对照 | 36/36 完整；XY RMSE 均值 0.171 像素、最大 0.431；表情 MAE 均值 0.00147、最大 0.00414；单系数最大误差 0.0467 | `quality-cropopt/quality.json` |
| 前后台及丢脸 | 90 秒中实际 USB 采集、录像面捕、20 视点及 UI 同时运行；黑帧后进入 GRACE/WAITING，恢复脸后重获；HOME 后同 Activity 恢复，无处理或 GL 故障 | `runtime-foundation-blackout-pause-90s.json` |

上述 90 秒记录的实际呈现采样约 30.64 FPS，但 HOME 前最后约 0.678 秒 SurfaceFlinger 历史未取得，报告正确标记证据不完整。因此它支持状态恢复验证，不能作为完整的 30 FPS 性能验收。第一份 `runtime-cropopt-blackout-pause-90s.json` 因采样器把背景包装层误认作渲染层，完全没有实际 FPS 证据；保留原文件，不改写成通过。

修正采样器在同一 adb shell 中先抓 SF 尾部再 HOME 后，`runtime-foundation-v2-home-tail-90s.json` 取得约 30.66 FPS，但动作边界仍剩约 0.103 秒未覆盖，仍如实标记不完整。丢脸/重获及同进程 HOME 恢复正常，无运行错误。HOME 场景用于生命周期验收，完整 FPS 性能证据使用下方不切后台的连续测试。采样器的 19 个主机用例验证空数据、缺口、状态重置、真假图层、多真实图层歧义、动作顺序和失败清理，未放宽 34 ms 边界容差。

## 相同工作量的裁剪对照

四组顺序为参考、优化、优化、参考；每组 60 秒，同一 APK、20 个独立视点、每视点 400×720、原生竖屏、完整 NPU 面捕目标 17 FPS、真实 USB 640×480/25、额外录像面捕输入转换与动态 UI，均有完整 SF 时间戳。参考通过 `--crop-reference` 选择，优化为默认。

| 组 | 裁剪 ms/处理帧 | 实际显示 FPS | 完整面捕调用 FPS | CPU %（全机） | GPU % |
| --- | ---: | ---: | ---: | ---: | ---: |
| ref1 | 9.024 | 29.402 | 16.579 | 70.92 | 94.80 |
| opt1 | 7.297 | 29.386 | 16.587 | 70.12 | 94.10 |
| opt2 | 7.283 | 29.403 | 16.560 | 70.01 | 94.35 |
| ref2 | 9.125 | 29.445 | 16.575 | 70.49 | 94.06 |

全部完整脸比例约 99.9%，均保留 478/52 输出。裁剪平均减少约 19.7%，完整脸完成延迟均值约 71.94→69.77 ms；未证明整体显示 FPS 提升。温度范围约 63–74°C，反序复测减少顺序偏差，但不是精确恒温实验。NPU 持续有负载；不会以额外无用推理来提高占用数字。

另测 `portrait-v16-400x720-60s.json`：16 个独立视点、同样 400×720 与联合输入，实际 30.77 FPS、面捕调用 16.61 FPS、脸比例 99.9%、CPU 66.47%、GPU 82.34%。这是降低视点数的产品档位取舍，仍需长时验证；不能冒充 20 视点同负载优化。

默认运行档位另以 `runtime-foundation-continuous-120s.json` 验证：20 个独立视点、每视点 **320×576**、原生竖屏、USB+完整录像面捕+界面同时运行。确认的互动区间实际 **30.633 FPS**，SF 历史无缺口，所有互动区间达到 30 FPS；最后状态的完整面捕约 16.47 FPS（统计包含启动和短暂丢脸）。互动区间 CPU 68.72%、GPU 81.59%、NPU 32.78%；可用内存最低约 958.54 MiB，回放映射计入 PSS。它证明这一默认档位的两分钟结果，**不证明 400×720/20 视点达标，也不证明正式角色或两小时稳定性**。

`preflight-foundation-v2.json` 验证无效输入配置能够在没有 worker 的情况下写入 ERROR、正常配置恢复 USB 与完整 478/52 输出，以及 HOME 后进程仍在但 NPU 为 0%、相机客户为空。权限拒绝分支**未完成实机验证**：厂商系统不支持 set/clear-permission-flags；仅 `pm revoke` 后启动仍正常采集，未触发预期拒绝分支。两个未成功报告保留为 `preflight-unsupported-permission-flags.json` 和 `preflight-permission-denial-unverified.json`，原授权已恢复且 flags 与测试前一致。最终用明确 `--skip-permission-test` 完成其余检查，报告 `permission_denial_exercised=false`；不会把跳过项算作通过。采样脚本恢复逻辑另有故障注入测试，覆盖启动与 force-stop 同时失败仍执行 grant。

## 可复现入口与恢复

`scripts/run_runtime_check.py` 测量长期入口；`scripts/run_case.py` 保留固定实验语义。前者严格区分 INTERACTIVE/待机/HOME 与采样缺口，读取 SurfaceFlinger 第二列实际呈现时间戳，不以 GL 回调 FPS 代替。运行报告的内存包括回放映射，不能直接等同实时摄像头生产内存。

安装调试包用 `adb -s 6L32552009566714 install -r <apk>`，启动 `adb -s 6L32552009566714 shell am start -n com.mirror.bench/.MirrorActivity`。默认使用实时 USB，相机授权保留；界面有维护和退出。HOME 会停止相机/NPU工作，系统与 ADB 继续可用。回滚包仍为 `E:/tripo/output/render-quality-optimization/20261002/RenderQuality.apk`，同包名替换安装，无需刷机或清除录像/配置数据。
