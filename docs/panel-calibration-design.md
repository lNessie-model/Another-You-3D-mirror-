# 屏幕软件校准核心设计

本切片已接入纯 Java 数学核心、配置持久化、运行界面维护入口、20视点测试图和四条 GPU 交织路径。设备全像素数值核对、HOME 中断及原 Activity 恢复后重验已有证据，见下文；光学对齐仍为 **UNVERIFIED**。用户提供的 `pitch=10`、`tan=0.2777777` 默认按 RGB 子像素 pitch 解释；这不是对实际屏幕单位、视图顺序或光学校准完成的确认。

## 坐标、通道与参数

`viewIndex(int x, int y, int height, int channel, int count)` 返回该输出色彩分量应采样的视图层。

- `x/y` 是输出 framebuffer 的整数像素索引；`x=0` 为左列，`y=0` 始终为 GL 底行。
- 像素中心为 `fx=float(x)+0.5f`、`fy=float(y)+0.5f`。
- `BOTTOM` 使用 `fy`；`TOP` 使用 `float(height)-fy`。例如高 4 像素的底行，在 TOP 下的坐标是 `3.5`，不是 `3` 或 `4`。
- `channel=0/1/2` 始终代表输出 R/G/B 色彩分量；它不随物理排列改变。
- RGB 物理排列的子像素位置 `offset(channel)=channel`；BGR 为 `2-channel`。因此 BGR 的红色分量仍采样纹理 `.r`，只是用于选择该红色分量视图的物理位置变成 2。
- `pitchUnits=SUBPIXELS` 时 `P=pitch`；`PIXELS` 时 `P=pitch*3f`。`tan` 单位固定为“每向上/下一个纵向像素，对应的横向完整像素偏移”，shader 系数 `T=tan*3f`；所选 Y 原点决定正方向。
- `phaseCycles` 是周期偏移，有限输入归一化为 `[0,1)`。正 phase 使未反转的视图编号沿增加方向移动，跨周期后回到 0；它不交换颜色通道。负 phase 合法。
- `reverseViews` 在得到未反转的整数视图层后执行 `count-1-index`，不修改几何坐标、颜色或 phase。

## 公式与 float 顺序

概念公式为：

```text
fx = float(x) + 0.5f
fy = float(y) + 0.5f
Y  = BOTTOM ? fy : float(height) - fy
P  = PIXELS ? pitch * 3f : pitch
T  = tan * 3f
O  = RGB ? float(channel) : float(2 - channel)

xTerm = fx * 3f
yTerm = Y * T
sum   = xTerm + yTerm
sum   = sum + O
q     = sum / P
q     = q + normalizedPhaseCycles    // phase=0 时直接保留 q，维持默认原路径
f     = q - float(floor(q))          // GLSL fract，不能使用负数 remainder
index = min(count - 1, int(floor(f * float(count))))
view  = reverseViews ? count - 1 - index : index
```

实现每步使用 Java 17 `float`，不使用 double 中间表达式或 `Math.fma`；`Math.floor` 的输出在 fract 减法前转换回 float。默认配置逐步等价于现有 `InterlaceRenderer` 中的：

```glsl
floor(fract((gl_FragCoord.x * 3.0 + gl_FragCoord.y * uTilt + c) / uPitch) * float(uCount))
```

此处默认 `uPitch=10f`、`uTilt=.2777777f*3f`、`c=0/1/2`。CPU 参考定义未融合的运算顺序。真实 GLSL 编译器的运算收缩、重排或精度实现可能使边界像素不同，因此随机 CPU 对照不能代替实际 GPU 像素校验。

校准路径现在使用 GLSL ES 3.20，并对相位函数中的局部量标记 `precise`，禁止将中间运算合并或重排；同一程序的屏幕顶点和片元 shader 同时使用 3.20。目标 Mali-G52 的现有设备报告支持 OpenGL ES 3.2。未调用 `setPanelCalibration` 的旧 bench 保持原 GLSL ES 3.00 原文；不支持 ES 3.2 的校准设备会明确编译失败，不静默退回有歧义的表达式。`precise` 约束运算次序，不将所有 GPU 除法提升为 CPU 的正确舍入保证，仍需逐像素验收。依据：[Khronos GLSL ES 3.20 规范](https://registry.khronos.org/OpenGL/specs/es/3.2/GLSL_ES_Specification_3.20.html) 的 precise 限定符、表达式求值、精度及链接版本要求。

phase 先规范化再相加，`0.25`、`1.25`、`-0.75` 应生成相同配置。浮点输入本身仍有精度限制；例如较大整数加非二进制精确小数，不能要求它保留原始小数的所有精度。

两个浮点端点需要明确处理：极小负 phase 的 `phase-floor(phase)` 可能舍入成 `1f`，此时规范为等周期的 `0f`；空间公式的 `q` 若极接近零但为负，`q-floor(q)` 也可能舍入成 `1f`，最终 index 应限制为 `count-1` 后再反转，否则会出现 `index=count` 或反转后的 `-1`。例如 `pitch=4096`、`tan=nextDown(-1f/3f)`、像素 `(0,1)` 的红分量应选最后一个视图。新 shader 必须加入同样的最终上界保护。默认正 tan、phase=0 的既有路径不会触发此修正，随机原公式对照仍保持一致。

## API 与边界

不可变 `PanelCalibration` 提供 `defaults()`，以及构造参数 `pitch`、`tan`、`PitchUnits`、`phaseCycles`、`SubpixelOrder`、`reverseViews`、`YOrigin`。数组和 Android 类型不进入接口；`opticalAlignmentVerified()` 恒为 false，本类不产生光学验收结论。

软件支持边界如下，超出时抛出 `IllegalArgumentException`，不静默钳制：

| 项目 | 边界 | 依据 |
| --- | --- | --- |
| pitch | `[1,4096]`，使用所声明单位 | 保留当前 10 子像素以及可用完整像素配置，排除零、负值和极小周期 |
| tan | `[-4,4]` | 本切片明确的软件支持范围，不是硬件标称范围 |
| phaseCycles 输入 | `[-1024,1024]` | 支持正负及多周期输入，避免无意义的大周期数损失精度 |
| framebuffer height | `[1,16384]` | 明确整数坐标预算；不声称设备支持此最大尺寸 |
| x | `[0,16383]` | 接口没有 width；实际 width 由集成层另外验证 |
| y | `[0,height-1]` | 防止把屏幕外坐标当有效像素 |
| channel | `0/1/2` | 固定输出 RGB 分量编号 |
| count | `[1,32]` | 与当前 renderer 的视图数范围一致 |

所有 float 参数必须有限；枚举不可为 null。不能把这组软件范围当成厂商光学参数范围。

## 验证与已实现集成

先测手算：零倾斜的视图端点及横向完整周期、正负倾斜的方向、phase 正负/跨周期、RGB/BGR 的物理位置、反转只改索引、高度 1 与首末行的 TOP/BOTTOM 对应。随后用固定种子的随机像素对照默认原公式，并覆盖无效参数和输入。

当前接线：

1. `MirrorSettings` 的 `mirror-runtime` SharedPreferences schema 2 保存 pitch 数值及单位、tan、规范化 phase、排列、反转和原点。schema 0/1 无写入迁移：保留有效运行档位、补入默认光学参数，显式保存时才写成 v2。损坏光学字段暂用默认值并显示提醒；未知版本禁止保存，避免覆盖新版本数据。没有伪造“已校准”标志。
2. `MirrorActivity` 维护 → 屏幕校准打开 `CalibrationActivity`，停止现有工作线程后展示独立草稿。恢复默认、预览、返回或系统 Back 均不写配置。保存验证成功且 `commit()` 成功后才返回 RESULT_OK，主画面 recreate；硬件所有权沿既有取消/串行重开路径。保存运行档位保留光学参数，保存光学参数保留运行档位。
3. 主画面使用 `InterlaceRenderer.setPanelCalibration`，仅允许 GL 初始化前配置；新实例统一 upload `P/T/phase/order/reverse/origin/height`，共用同一段 shader 相位函数。已有 bench 的 `setPanelParameters` 和无参数默认 `pitch=9.69, tilt=.28` 保留旧 shader 原文和运算语义。
4. shader 对每个输出 R/G/B 分量计算物理 offset，但采样组件固定为 `.r/.g/.b`。TOP 只改变索引公式的 Y；不顺带翻转内容的纹理 UV。array、explicit LOD array、atlas、lookup 生成共用相位函数；lookup 索引纹理在参数或输出高度改变时重建。
5. shared-phase 仅接受 20 视点、有效 pitch=10 子像素、phase=0、RGB、不反转、BOTTOM；其它组合 GL 初始化明确报错，主运行入口未开启该优化。不能把该快速式用于任意新配置。
6. `PanelTestImages` 生成真实不同背景颜色及 01–20 大号七段数字的20张图；另有向上箭头、底部横条辅助判断上下。`PanelPreviewActivity` 全物理 viewport 交织这些图，不使用原 bench 的3色循环。UI控制条覆盖底部少量屏幕，不改变 GL viewport 尺寸。

## 设备逐像素核对入口

此入口不使用摄像头/NPU，启动会使主运行 Activity 进入暂停；应在性能/耐久测试结束后独立运行。以下命令可复测：

```powershell
adb shell am start -n com.mirror.bench/.PanelPreviewActivity --ez verify true
adb shell run-as com.mirror.bench cat files/panel-pixel-check.json
```

`verify=true` 对默认、phase=.137、BGR、反转、TOP，以及 `PIXELS + 负tan + phase + BGR + 反转 + TOP` 共6组参数，分别验证 array、explicit-LOD array、atlas、lookup 共24个输出。纹理每视图/通道使用无歧义的8位编码：R=`17+7*v`、G=`31+5*v`、B=`47+3*v`，`v` 为0基视图层。验证使用原生 framebuffer 的**全部像素和三个颜色分量**，以 `PanelCalibration.viewIndex` 独立计算期望编码，不把 shader互相比对当成正确性结论。

固定输出 `files/panel-pixel-check.json` 原子写入，包含 `running`、`passed`、`rgb_byte_mismatches`、`width/height`、`view_count`、每组参数/路径的 `checked_rgb_bytes`、首个错误位置与期望/实际值、`updated_elapsed_ns`。`passed` 仅在全组零差异时为 true。未完成、异常、退出中断都不能报告通过；暂停会逐行取消长循环。后续生命周期修复增加 `run_id`、`started_elapsed_ns`、`context_generation` 和 `cancelled`：启动时先使旧成功报告失效，每个新 GL context 创建新 renderer 并重新核对；同 context 尺寸变化也重验，避免沿用旧 framebuffer 的结果。下面 v2 像素验收 APK 不含该后续修复；v4 已完成 HOME/恢复回归，具体证据独立列出，不回填到旧 APK 的成绩中。

### 首轮设备失败与修复依据（2026-10-03）

原始失败报告为 `E:/tripo/output/mirror-program/20261003/panel-pixels-avatar-v1-red.json`，SHA-256 `34bee24004a1671cda5e703d320a012ee82912f573400e70549107bf0f9b278c`。原生 1200×1920 下比较 165,888,000 个 RGB 字节：case 0–4 四条路径均零差异；case 5 的每条路径出现 239 个蓝通道差异，总计 956，故报告 `passed=false`。

case 5 参数是 `pitch=3.5 PIXELS, tan=-.125, phase=.375, BGR, reverse=true, TOP`。第一个差异 `(x=238,y=1,channel=2)`：`sum=-3.9375`、`P=10.5`，逐步计算 `q=sum/P` 舍入为 `-.375`，再加 `.375` 恰为零，反转后选 view 19（蓝字节 104）。将除法的倒数乘法与加 phase 融合成一次 FMA 会保留一个微小负误差，跨过周期边界选 view 0（蓝字节 47）。

`tests/PanelPhaseContractionTest.java` 对原生尺寸全部 RGB 分量运行，`Math.fma(sum,1f/P,phase)` 恰好复现同一首像素和 239 个差异；未融合的参考乘法、加法得到零差异。此证据支持最小修复为 shader 的 `precise`，CPU oracle、参数、测试案例和零差异阈值均未变更。

修复版同步 APK 在同一设备完成核对：6组参数 × 4路径 × 1200×1920 × RGB = **165,888,000 字节，零差异，passed=true**，包含原失败 case 5。被测 APK SHA-256 为 `0df7ffd0e62e8a9abd7f5ce4cfb481affa61f085f6c5d05299ae4ffab43d1e36`。报告 `E:/tripo/output/mirror-program/20261003/panel-pixels-avatar-v2-green.json`，SHA-256 `40bbe006709bfab7845684fa6b3437774374e67d8ed39d591b4b375f480d409b`。这是该驱动和测试配置下的 GPU 数值验收；`optical_alignment_verified=false`，尚未验证实际光栅、观看区或串扰。

### v4 HOME 中断与恢复重验（2026-10-03）

本轮使用 APK SHA-256 `0e8f129ade4001f4fdb3927137267a3ee0c47897f19ee02b23c4bcf9ef7322eb`。同一 Activity 经 HOME 暂停后以 reorder 恢复，归档顺序如下：

| 阶段 | 原始报告与实际状态 | 报告 SHA-256 |
| --- | --- | --- |
| HOME 前正在核对 | [panel-v4-before-home.json](E:/tripo/output/mirror-program/20261003/panel-v4-before-home.json)：run `2d8c5b82-7257-40f6-b6f0-649397e7ec51`，context 1，`running=true`、`passed=false`、`cancelled=false` | `9045a318a09ceabd1f521aa411bd7b022714d76f3c79c62ae0c223667205e614` |
| HOME 中断 | [panel-v4-home-cancelled.json](E:/tripo/output/mirror-program/20261003/panel-v4-home-cancelled.json)：相同 run/context，`running=false`、`passed=false`、`cancelled=true`，明确记录 `InterruptedException: Panel check cancelled` | `a550d198e1fcf1b278b8cd3b5a5f5d83abaedc8d31ce41c1069ba99233e222c5` |
| 原 Activity 恢复并完成新核对 | [panel-v4-resumed-green.json](E:/tripo/output/mirror-program/20261003/panel-v4-resumed-green.json)：新 run `8d57cdff-4db9-449d-94ea-85896e1ad937`，context 2，`running=false`、`passed=true`、`cancelled=false` | `5e037a9ff99064ab054a2986ddf70754ede6e1cd8bd3515661dbeb6e12acb996` |

恢复后的 6 组参数×4 路径×1200×1920×RGB 共 **165,888,000 字节，零差异**，view_count=20。它证明本次中断未留下成功结果、恢复后新 GL context 发起了新一轮完整核对；不扩展为所有生命周期/尺寸变化分支通过。报告仍为 `optical_alignment_verified=false`，不代表实际光栅、观看区或串扰验收。

普通预览不传 verify；从校准草稿进入时用受限 Float/enum/Boolean Intent 参数传递，不保存。测试图没有相机内容，不产生人脸持久化。

后续仍需实屏观看区、左右顺序、串扰与中心相位验收，以及参数导出/回滚包。数学或 GPU 像素核对通过不等于完成裸眼 3D 光学校准。
