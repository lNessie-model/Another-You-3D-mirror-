# 固定八个 LN 复制候选：两轮板端验证

2026-10-03。**设备输出已显著接近同图模拟器，但对原始 TFLite 的精度仍未达标，application_eligible=false。** 真实人脸数据部分也不满足原门，不能把剩余问题仅归为 synthetic 输入。本轮只做离线审计，没有操作用户正在查看摄像头预览的设备。

模型是 [固定八个同构负均值分支复制候选](blendshape-all-ln-negmean-copy.md)，variant `all_eight_ln_negative_mean_explicit_copy_v1`。与 [首 LN 单点候选](blendshape-first-ln-negmean-copy-device-validation.md) 区分，仍使用相同的首 LN 四个中间观察点和最终 52 项出口；其他七个 LN 的内部值没有增加观察。

## 运输、接口和重复性

原始目录为 `E:/tripo/output/mirror-program/20261003/blendshape-all-ln-negmean-copy-device-v1`、`...-v2`。两个独立 native 进程均 terminal0，checker 均 `diagnostic_complete` / exit1，表示完整执行但原精度门失败。离线重新调用冻结 checker，两个完整 comparison 字典与原保存结果分别全等。

每轮原 92 组输入、5 次 warmup，398 对有序 API begin/end 全部 rc0，485 条 tensor_result 完整。外部 native-exit.json、before/after SHA、commands.json、stdout/stderr、report、五个 FLOAT32 文件均保留。新包10个payload/9个provenance、源码 freeze、原输入/原 TF、同图模拟器及历史对照来源共201个路径哈希核对通过。

query 输入仍为 FLOAT16 `[1,146,2]`、292元素、584 bytes、fmt3；实际 feed 为原292个相邻 x/y 像素 FLOAT32、pass_through0、query fmt。五输出严格按下表，无转置、插值、截取或维度别名；want_float 返回 FLOAT32。输入输出均 `w_stride=0`、`size_with_stride=size`。

| index / label | query shape | fmt | query bytes / 返回 bytes |
|---|---|---:|---:|
| 0 gamma_conv | `[1,64,97,1]` | 0 | 12416 / 24832 |
| 1 restore_scale | `[1,1,97,64]` | 0 | 12416 / 24832 |
| 2 x_scaled | `[1,1,97,64]` | 0 | 12416 / 24832 |
| 3 negative_mean_scaled | `[1,1,97,64]` | 0 | 12416 / 24832 |
| 4 final52 | `[52]` | 3 | 104 / 208 |

五输出 query 类型均 FLOAT16。SDK 为 `1.3.0 (9b36d4d74@2022-05-04T20:16:47)`，driver `0.7.2`，vendor 与 helper SHA 沿用已冻结版本。原五输出数组的获取、完整校验/复制/释放顺序及 offset、size 均由原 checker 验证。两轮全部五输出文件逐字节相同，包括剩余精度误差；这只是两个相同输入序列的独立进程复测范围。

## 同图模拟器与历史图

同图模拟器为 `blendshape-numerical-diagnosis/all-ln-negmean-copy-v1-simulator`。其模型/manifest/config/五输出 SHA 由 freeze 和新审计绑定。该模拟器五文件此前已与旧 scale 和首点候选模拟器逐字节相同；本轮仍使用当前候选自己的文件作主对照。

| 输出 | 对本图模拟器不同位模式数 | 完全相同 case / 92 | maxAbs | MAE |
|---|---:|---:|---:|---:|
| gamma_conv | 309 / 571136 | 87 | 0.015625 | 4.2883284367e-6 |
| restore_scale | 309 / 571136 | 87 | 0.015625 | 4.2883284367e-6 |
| x_scaled | 556 / 571136 | 48 | 0.001953125 | 4.2972688829e-7 |
| negative_mean_scaled | 755 / 571136 | 80 | 9.1552734375e-5 | 5.0681313823e-8 |
| final52 | 4480 / 4784 | 0 | 0.003173828125 | 0.00117626773773 |

前四个设备观察点与首 LN 单点候选两轮的对应值全部逐字节一致；前三点也与更早的旧 scale 图逐字节一致。首个负均值分支仍有 0/8924 个 token 出现通道内混合非零正负号，范围为 `[-0.1205444336, 0.1527099609]`，比值 `negativeMeanScaled/scale` 跨通道 spread 中位数 `4.2410984162e-6`、最大值 `3.2802927161e-5`，与首点候选保持。

每轮 gamma 按原 `[0,3,2,1]` 排列后，与本轮 restore_scale 的571136值全部逐位一致。该关系仅作诊断，不改变验收输出顺序。

原来的48个 case 集合保持：这48组四个中间点均与本图模拟器逐位一致；其中负均值点297984值无差异。final52 对模拟器仍有2322/2496位模式不同，maxAbs `0.0030210018157958984`、MAE `0.001155848782031964`。首点候选在相同集合的 final52 对模拟器 maxAbs 为约0.929、MAE约0.166；当前已明显缩小。

这个比较支持固定八分支候选的端到端设备数值更接近模拟器，同时首 LN 锚点保持。它不能证明各未观测 LN 的内部值逐点正确，也不能单独锁定某一个 runtime 内核根因。没有把不同图的内部张量拼成一条执行链，没有新增“对模拟器通过就可应用”的门。

## 原 TF / ORT 门仍失败

最终门保持 maxAbs≤0.01、global MAE≤0.002、全部有限且位于 `[0,1]±1e-5`，对原 TF 与候选 ORT 分别判定。warmup 和正式输出的有限/范围检查通过，精度均失败。

| final52 对照 | maxAbs | MAE |
|---|---:|---:|
| 当前设备 → 原 TF（全部92组） | 0.242248684168 | 0.003794938571 |
| 当前设备 → 候选 ORT（全部92组） | 0.242248535156 | 0.003794938077 |
| 当前设备 → 原 TF（72 real） | 0.0338575839996 | 0.002571958845 |
| 当前设备 → 原 TF（20 synthetic） | 0.242248684168 | 0.008197665585 |
| 本图模拟器 → 原 TF（全部92组） | 0.243225246668 | 0.002802638502 |
| 首点候选设备 → 原 TF（全部92组） | 0.928714901209 | 0.164862000165 |
| 旧 scale 设备 → 原 TF（全部92组） | 0.994016915560 | 0.219850494466 |

当前有279/4784个值对原 TF 误差大于.01，涉及70个case和44个输出通道。最坏 synthetic-15（case87）maxAbs约.242249；real部分最坏 npu-10000（case20）maxAbs约.033858。不能删除 synthetic 输入或只选择少数通道来宣布过关。各case/fixture的完整数值保存在新审计 JSON 中。

本轮只证明严重的设备/模拟器差距已缩小；原 TF 的剩余偏差仍需独立数值分析。输入 FLOAT32→FLOAT16、模型内部舍入和其它执行差异均不能在本报告中直接定为唯一原因。未修改 epsilon、统计顺序、坐标、维度、52项输出或任何阈值。

## 冻结证据

审计 JSON：`E:/tripo/output/mirror-program/20261003/blendshape-all-ln-negmean-copy-device-audit.json`，SHA `85bed45141665efb67aa017ea9d866038ef1f2c267e2c22ce477f6fe2b588e83`。有界审计器：`app/build/audit_all_ln_negmean_copy_device.py`，SHA `63fd1a29f795a4fbf620a7ef7cf8ff97ffa71994160f39d2af8b729649da9676`，同字节快照归档为 output 目录下 `blendshape-all-ln-negmean-copy-device-audit.py`。

审计器固定两个run、92×292输入、四个6208元素中间点和52元素出口；先核准确文件大小及有限值，再统计、复算原checker和核历史哈希。输出采用排他创建，禁止覆盖旧审计和原始报告。复核可在写出前只读重算并与保存的整个report字典比较；不要把原始comparison重新写回。

| 产物 | SHA-256 |
|---|---|
| all8包 manifest | `f03cb52b7a141c57c0f83a294a2dc2736f8befd4c79850eff64d801a9d2a1035` |
| RKNN，1224123 bytes | `954deb1c038b4c61e6f69b635ebe026dcf01d548ffc07bb9a2c1f41aaf758348` |
| profile3 helper | `3c45a2e6cbf6fa35b638bbea9aa2739aa4c6636bc2e4de53aeb5ecc77f007a01` |
| v1 comparison | `dfb7d21acae26c86edb4d1b3ffa99b1fd5e34ade7b2bf80624ab2377a4b56e39` |
| v1 report | `3e21bc68f2e03a54568831ea356397c8da5435bd787fead73efb8d258d3fb01a` |
| v2 comparison | `ceaa1d88f1b11e4a27e4872bed285b5d87685ee9e33f77dd170786003765eede` |
| v2 report | `6836c96345bc15bb9822e6c552448174d669c478ddd8adafb652d143b7717e21` |

所有before/after哈希、独立退出及其余文件均列入新审计201项来源链。本轮没有新观测模型、helper或编译，没有改正式应用、PLAN或Git，也不构成400×640渲染性能或NPU应用收益的证据。
