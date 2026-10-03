# 首 LayerNorm 五点：两轮板端数值验收

2026-10-03。两轮独立进程均 native exit 0；冻结 checker 均为 `diagnostic_complete` / exit 1：**运输与执行证据完整，精度未通过。** 五个输出文件在两轮间全部逐字节相同。结果把这张诊断图的首个已观察严重分歧缩小到首 LayerNorm 的统计量之后、affine 输出之前；尚未定位单个算子，也不能直接归因于原始单输出模型。

模型和接口来自 [首 LN 离线准备](blendshape-first-ln-diagnostic.md) 与 [固定探针合同](blendshape-ln-device-check.md)。没有将错误模型用于正式程序，没有调整原门限；诊断 API 耗时不作为性能收益证据。

## 完整性与实际属性

原始目录：

- `E:/tripo/output/mirror-program/20261003/blendshape-ln-tap-device-v1`
- `E:/tripo/output/mirror-program/20261003/blendshape-ln-tap-device-v2`

每轮均为 5 次 warmup + 原 92 组测量；398 对有序 API begin/end 全部返回 0，485 条 tensor_result 完整。输入、模型、helper、vendor runtime 的前后哈希一致；全部输出、原生日志及独立进程退出记录齐全。审计重新执行冻结 checker，结果字典与两份已保存 `comparison.json` 全部相等。

包 manifest SHA `2b7f8eba2204c2f5b45f9b93800c61a798c373948f57cfe6769f0648ad4dc533`；模型 SHA `2e52062dfe3bf2c374dd594931c670a5bcd3c61495386b28c7f9c165c47725d3`；helper SHA `9ffa6166f424a76466d3d46c4af38a6fd8330824f1d04e4c93b6834af0db3f12`。10 个包文件、9 个原始来源文件，以及同图模拟器模型/manifest/五个输出哈希均核对。vendor runtime 仍为 `01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de`，SDK 1.3.0 / driver 0.7.2。

实际 query 输入为 FLOAT16 `[1,146,2]` / 584 bytes / format 3；提交仍为原 292 个 FLOAT32 相邻 x/y 像素值、pass-through 0，不转置、不重新归一化。五输出均 FLOAT16，want-float 1 后按原顺序复制并保存：

|输出|实际 query shape|format|query bytes|返回 F32 bytes/case|
|---|---|---:|---:|---:|
|token_embedding|`[1,64,1,97]`|0 / NCHW|12416|24832|
|mean|`[1,1,1,97]`|0 / NCHW|194|388|
|inv_std|`[1,1,1,97]`|0 / NCHW|194|388|
|affine|`[1,1,97,64]`|0 / NCHW|12416|24832|
|final52|`[52]`|3 / UNDEFINED|104|208|

这些是原始属性值，affine 的 format 0 不被改写为“语义 NHWC”；`w_stride=0`、`size_with_stride=size`。同图编译表及 ONNX 输出同样声明上述形状。所有比较保留原始平铺顺序，未用猜测转置后的结果代替验收。

## 同图模拟器对照

模拟器使用 `ln-taps-simulator-v1` 的完整 92 组输出；不是不同观测图的中间值。两轮结果相同，以下统计适用于两轮：

|点|逐位不同比例（值数）|变化 case|maxAbs|MAE|
|---|---:|---:|---:|---:|
|token_embedding|454 / 571136|51|0.000244140625|2.45971851475e-8|
|mean|12 / 8924|10|0.00000762939453125|4.48838203598e-9|
|inv_std|5 / 8924|5|0.0078125|0.00000437724114747|
|affine|570816 / 571136|92|410.1171875|2.81768997810|
|final52|4781 / 4784|92|0.995075702667|0.226991156845|

关键子集有 **41 组**：完整 token、mean、inv_std 三项均与同图模拟器逐位相同；其 affine 全部明显分歧，254378 / 254528 项不同，maxAbs `409.6015625`、MAE `2.7221906779672103`。这些组的 final52 仍有 2131 / 2132 项不同，maxAbs `0.9950757026672363`。所以，前面少量 stem/statistics 的数值差异不是这 41 组发生 affine 严重分歧的必要条件。

第一处全量的细小位差仍在 token 观测点，不能说之前“所有数值都完全正确”。但已采样的严重分歧明确发生在三项锚点与 affine 之间。逆标准差的 5 个差值最大仅 2 个 FP16 编码步；token 在近零处最大 917 步，不能概括为全部只有 1 ULP。所有原始数值有限且都可逐位往返为 FP16，未见 NaN/Inf。

### 幅度与排列边界

device affine 范围 `[-9.09375, 407.75]`，同图模拟器范围仅 `[-2.76171875, 2.7734375]`；每个 case 的 affine 最大绝对误差都在 `155.28494262695312` 至 `410.1171875`。全量共有 8924 个 `abs(value)>10`，只出现在语义最后维索引 `0,4,8,…,60`。这是描述性的周期分布，阈值 10 不用于验收，也不意味着每个 token 恰有一项异常。

纯排列不会改变值域，因此仅调整 channel/token 顺序不能解释高达 407.75 的幅度。这个周期仍不足以证明 stride、padding、内存复用或某个乘法内核有错；后续必须观察真实中间结果，而不是拟合一个排列来“修复”原门。

## 原参考精度及仪器化影响

final52 范围 `[0.003021240234375, 0.9970703125]`，warmup 和正式值均有限、范围合法，但原精度门严重失败：

|参考|maxAbs|global MAE|原 `.01 / .002` 门|
|---|---:|---:|---|
|原 TFLite 52|0.9950820803642273|0.22712367294274516|失败|
|同图 ONNX 52|0.9950820505619049|0.22712366857307412|失败|

与此前 stem 板端图相比，token 改变 921 / 571136 项、maxAbs `0.000244140625`；final52 改变 4063 / 4784 项，92 组全部改变，maxAbs `0.991119384765625`、MAE `0.27339370832794085`。相对 front 板端图和原 full-v4，final 的 MAE 分别为 `0.2944480089040903` 与 `0.28889291501762876`。观测出口显著改变了板端故障模式，因此当前结果不是原 full-v4 在相同节点出错的证明。

## 下一最小观测方案

实际编译表中首 LN 区间为：inv_std 后 reshape → NPU gamma Conv → CPU Transpose → 两条 NPU Mul → NPU Add。mean 路径还经过负号和 reshape，token 路径经过 CPU Transpose；现有点不能区分这些步骤。

建议下一张独立图保留全部原计算节点、参数和运算顺序，只新增四个短 Identity，仍严格五个输出：

|原 tensor（`P` 为下方首 LN 前缀）|原图形状|诊断用途|
|---|---|---|
|`P + batchnorm/mul/gamma_conv:0`|`[1,64,97,1]`|观察 inv_std 与 gamma 的 1×1 Conv 结果|
|`P + batchnorm/mul`|`[1,1,97,64]`|观察恢复轴序后的 scale，区分上一 Conv 与此 Transpose|
|`P + batchnorm/mul_1`|`[1,1,97,64]`|观察 xScaled 分支|
|`P + batchnorm/mul_2`|`[1,1,97,64]`|观察 negative_mean_scaled 分支|
|`StatefulPartitionedCall:0`|`[52]`|保留原完整 52 项 host/device 门|

`P = model_1/GhumMarkerPoserMlpMixerGeneral/MLPMixer/MixerBlock_0/layer_norm1/`。

每个新增图仍须重新通过原 TFlite 92×52 `atol=1e-5, rtol=0` 和原候选 ONNX final 逐字节等同，实际编译/模拟形状才形成后续板端合同。四个大点合计 24884 floats/case，不能沿用本轮 12662 容量。此取舍没有第六个 affine 出口：如果两乘积正确而 final 仍错，仍不能据此指认 Add，需下一小步再观察 Add。新图的仪器化影响必须单独记录；本报告没有执行这张建议图的板端测试。

## 可复核文件

审计汇总 `E:/tripo/output/mirror-program/20261003/blendshape-ln-device-audit.json`，SHA `2fa19532db5a05860c935a6f1ffc831d62f909374042b4bf28fde68e7549755c`，含每组误差、41 组索引、所有原始与来源哈希、完整重复性和前图比较。幅度/通道分布另见 `blendshape-ln-layout-audit.json`，SHA `e0430b8e75ad22d1ecf0a20eec3f8f97b515d8bb291eaf0ff4b8228704fe9150`。两个实际审计脚本随输出保存；原始文件不被改写。

|文件|SHA-256|
|---|---|
|v1 comparison.json|`ae0ed60db3d9a6d1ed2eddafe4755c4801995cad0913492f38701505177beafe`|
|v1 report.jsonl|`d67e2e4202409bd12a33ba6ff1df7eee77feea4ce33aafb2bf21071999f9b14a`|
|v2 comparison.json|`ba6f8bf365ca349001970da5ea154a220bfa02aa6597cb3372a027fa9b72bc4f`|
|v2 report.jsonl|`79fc6796742d1aafcc26ea99475a926b858ef75a0427695515964b7b16a07675`|
|两轮 native-exit.json|`124fd50cb6d223cdd18fedc166d00af7254199b6e6dedf9be82f71db87af6d01`|
|同图 simulator diagnostic.json|`0e7146b0f097eec56bbe43743c626bdc3e988265157f63e9a5717a878b6223e6`|

光学效果、正式应用功能和帧率都不在此次隔离数值验收范围。
