# 首 LN 负均值显式复制：两轮板端验收

2026-10-03。**该候选在被观测的负均值分支上显著改善，完整 52 项输出仍未通过，禁止替换应用模型。** 两个独立进程的原始五输出全部逐字节一致。以下结论只针对 [首 LN 单点复制候选](blendshape-first-ln-negmean-copy.md) 的固定五输出图，不能扩展为全部 LayerNorm、原始无观测全图或某个 runtime 内核根因已确认。

## 证据完整性和执行范围

raw 位于 `E:/tripo/output/mirror-program/20261003/blendshape-first-ln-negmean-copy-device-v1`、`...-v2`。两轮 native exit 均为 0；冻结 checker 各为 `diagnostic_complete`、exit1，表示数据完整但精度失败。离线重新运行现有 checker 后，两个完整结果字典分别与原 comparison.json 全等。

每轮 5 次 warmup 和 92 次正式输入；398 对有序 API begin/end 均 rc0，485 条 tensor_result 完整。输入是原 146 点相邻 x/y 像素坐标共 292 个 FLOAT32，pass_through=0；query 为 FLOAT16 `[1,146,2]`、fmt3、584 bytes。输出均 query FLOAT16，want_float 返回 FLOAT32，不转置、不补值。SDK `1.3.0 (9b36d4d74@2022-05-04T20:16:47)`、driver `0.7.2`；vendor SHA 不变。

| index / label | 实际 query dims | fmt | 原 size / want_float bytes |
|---|---|---:|---:|
| 0 gamma_conv | `[1,64,97,1]` | 0 | 12416 / 24832 |
| 1 restore_scale | `[1,1,97,64]` | 0 | 12416 / 24832 |
| 2 x_scaled | `[1,1,97,64]` | 0 | 12416 / 24832 |
| 3 negative_mean_scaled | `[1,1,97,64]` | 0 | 12416 / 24832 |
| 4 final52 | `[52]` | 3 | 104 / 208 |

所有输入/输出 `w_stride=0`、`size_with_stride=size`。helper 原数组释放、完整 API 顺序、warmup/正式偏移和外部退出由冻结 checker 核对。新审计核对包 10 个 payload、9 项 provenance、源 freeze、原模型/原 TF 输入参考、同图模拟器、native 源、两个 raw 的全部文件及旧 scale 证据，共 129 个路径哈希。所有实际值均有限，且本身为可精确 FP16 往返的 FLOAT32 值；这不代表精度通过。

## 对同一候选模拟器的五点对照

模拟器路径为 `blendshape-numerical-diagnosis/first-ln-negmean-copy-v1-simulator`。它与本模型共享冻结 ONNX/config 来源，五模拟器文件与旧 scale 模拟器全部逐字节一致。每个中间点共 571,136 个值，final52 共 4,784 个值。

| 观测点 | 不同 FLOAT32 位模式数 | 全部位相同 case 数 / 92 | maxAbs | MAE |
|---|---:|---:|---:|---:|
| gamma_conv | 309 | 87 | 0.015625 | 4.2883284367e-6 |
| restore_scale | 309 | 87 | 0.015625 | 4.2883284367e-6 |
| x_scaled | 556 | 48 | 0.001953125 | 4.2972688829e-7 |
| negative_mean_scaled | 755 | 80 | 0.000091552734375 | 5.0681313823e-8 |
| final52 | 4784 | 0 | 0.92938232421875 | 0.164534811367 |

两轮五文件全 byte 相同，包括最终错误输出。每轮 gamma 按原 `[0,3,2,1]` 排列后与本轮 restore_scale 的 571,136 个值全部逐位相同；此检查只验证已声明的张量关系，没有重排验收数据。

前三个设备观测点与 [旧 scale 图板端结果](blendshape-scale-device-validation.md) 也全部逐字节一致。原先前三点同时与模拟器一致的 48 个 case 集合完全保持；本次这 48 个 case 的 negative_mean_scaled **297,984 个值也全部逐位一致**。旧图相同集合的负均值分支 maxAbs 8.0211486816、MAE 0.7343017821，此处下降为 0。

但是，这 48 个 case 的 final52 仍有 2496/2496 位模式不同：对本图模拟器 maxAbs 0.9293823242、MAE 0.1658004760；对原 TF maxAbs 0.9287149012、MAE 0.1663538149。**相同的四个已观测中间张量不足以证明未观测的加法及后续网络执行正确。** 不把旧 LN 图的 affine 或其他图的内部张量拼接成当前图的执行链。

48 case 索引（完整 fixture 名保留在原包 manifest 中）：

```text
2,4,5,6,7,8,9,12,13,15,16,23,24,25,28,30,33,34,37,39,43,46,47,48,
52,53,55,56,57,58,60,62,64,66,68,70,72,73,75,76,77,78,79,81,84,87,89,91
```

## 负均值分支的不变量

scale 始终为正，因此正确复制的一个负均值标量乘以该 token 的 64 个 scale，不应在同一个 token 内同时产生非零正值和负值。

| 量 | 旧 scale 设备 | 显式复制候选设备 | 本候选模拟器 |
|---|---:|---:|---:|
| 混合非零正负 token / 8924 | 8504 | 0 | 0 |
| negativeMeanScaled 最小/最大 | −7.96484375 / 7.2890625 | −0.1205444336 / 0.1527099609 | −0.1205444336 / 0.1527099609 |
| 每 token `negativeMeanScaled/scale` 跨通道 spread 中位数 | 0.3524361480 | 4.2410984162e-6 | 4.2410984162e-6 |
| 同一 spread 最大值 | 1.0805254048 | 3.2802927161e-5 | 3.2802927161e-5 |

新负均值输出对同图模拟器 maxAbs 为约 9.16e-5，原先约 8.02 的严重偏差消失。上述范围和 spread 摘要一致不等于所有 92 case 逐位一致：仍有 755 个值不同，分布在 12 个 case。比值仅用于分支诊断，不代替原始输出或最终精度门。

这支持“只改首 LN 负均值复制后，该图被观测分支恢复到接近模拟器的数值”，并且前三锚点不变。仍不能单凭这个结果把底层原因定为 Mul 广播内核；复制操作也改变编译、内存布局及内部数据流。其余七个 LN 没有改写，本轮没有观测它们。

## 最终精度仍失败

门限保持 maxAbs≤0.01、global MAE≤0.002、有限且范围 `[0,1]±1e-5`，对原 TF 与候选 ORT 分别执行。两轮 warmup 与正式输出都有限且范围合法，但精度均失败。

| final52 对照 | maxAbs | MAE |
|---|---:|---:|
| 新候选设备 → 原 TF（92 case） | 0.928714901209 | 0.164862000165 |
| 新候选设备 → 候选 ORT（92 case） | 0.928714841604 | 0.164862002056 |
| 新候选设备 → 原 TF（72 real） | 0.878004103899 | 0.157615371115 |
| 新候选设备 → 原 TF（20 synthetic） | 0.928714901209 | 0.190949864743 |
| 旧 scale 设备 → 原 TF（92 case） | 0.994016915560 | 0.219850494466 |

新候选仍有 3281/4784 个值误差大于 .01，92 个 case 以及 52 个通道都至少出现一次超限。整体误差数值减少，但距离验收门仍很远。此处没有报告推理加速、渲染收益或应用性能通过。

## 冻结产物

独立审计：`E:/tripo/output/mirror-program/20261003/blendshape-first-ln-negmean-copy-device-audit.json`，SHA `cccad5a465f3613f26515f07a88014b5118c1cdbb45ddceec4ad395c4e1f4b52`。审计器 `app/build/audit_first_ln_negmean_copy_device.py`（同名快照归档到 output），SHA `cf6bf74ded022300f15709060d354ea7a35ddf9f7682042b06c3fe6ee47be2ab`，使用排他创建，不覆盖 raw 或原 comparison。

| 产物 | SHA-256 |
|---|---|
| 新包 manifest | `4b9146015699e7c1a0e435a5496db85d682bf7d45b365fc41d9961e0319c0156` |
| 新 RKNN，5,523,195 bytes | `71058d175561ea0ca2cdcdb283305be23e0ab5ae41238735a204f638fe8d2240` |
| profile3 helper | `3c45a2e6cbf6fa35b638bbea9aa2739aa4c6636bc2e4de53aeb5ecc77f007a01` |
| v1 comparison | `6d2748126760cd42b06fd97ee107af1c068d41fab95e7eff222bf1b20b4ed8e6` |
| v1 report | `e72f955bd574e9ecab4c64a4baaa0f3944989e50a8e49175cd1e277e1d4bfadd` |
| v2 comparison | `108ee856841d62c43c7daf777b42f9e5039795845842f8e7474ff053d0ae4cc1` |
| v2 report | `d708e085878b9258b1dbd2464104546d4a68e55c50195cd7c46c709bc9fc4f90` |

原始命令、before/after SHA、独立 native-exit、stdout/stderr、五个 float32 文件均保存在各 raw 目录，完整哈希列在新 audit。模型 variant 为 `first_ln_negative_mean_explicit_copy_v1`，不是旧 scale 模型重测。

后续应保留这个单点候选作为可复核基线，先界定当前图最后四个一致观测点之后的未观测区间；是否追加固定观察点或扩展另一个广播节点，需要单独的最小实验授权。本轮未新建模型、编译、修改 helper/应用或操作设备。
