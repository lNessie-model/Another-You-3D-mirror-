# Scale 分支两轮板端数值验收

2026-10-03。两轮新 scale 图均 native exit 0；checker 为 `diagnostic_complete` / exit 1，表示执行与证据完整、原精度门失败。五个输出文件跨独立进程全部逐字节相同。当前图的 gamma 卷积、恢复轴序和 xScaled 没有观察到严重分歧；**首个已采样严重分歧出现在 negative_mean_scaled 支路。** 这仍不能区分其实际左操作数、广播乘法及缓冲区问题，不能据此宣布单算子根因。

图与探针来源分别见 [离线准备](blendshape-scale-diagnostic.md) 和 [固定 profile3 合同](blendshape-scale-device-check.md)。没有替换正式模型或修改任何精度门，不从诊断耗时声称性能收益。

## 原始证据与实际接口

两轮完整 raw 位于 `E:/tripo/output/mirror-program/20261003/blendshape-scale-tap-device-v1` 和 `blendshape-scale-tap-device-v2`。每轮 5 次 warmup + 原 92 组测量；398 对有序 API begin/end 全 rc0，485 条 tensor_result 完整。冻结 checker 重新计算的两个完整字典分别与原 `comparison.json` 全等。

核对了新包 10 个文件、9 个来源、同图模拟器及五个输出、设备 model/input/helper/vendor 前后 hash 和输出/report 后 hash；独立进程退出也为 0。主审计记录 53 项原始/来源 hash，补充审计还核对 helper build metadata 中四个实际 native 源 hash。全部模型、输入与参考身份对应，未将旧图输出混作当前图参考。

|冻结对象|SHA-256|
|---|---|
|bundle manifest|`104ba7ce16c56510086706a83f912d386de3e0800c590e02fddcd2695339b495`|
|RKNN model|`b22b97351dc95c51817291f91304f9d7bed0d9ff6d4606c94b3bc713dfe7ad12`|
|helper|`3c45a2e6cbf6fa35b638bbea9aa2739aa4c6636bc2e4de53aeb5ecc77f007a01`|
|vendor runtime|`01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de`|

实际 SDK 1.3.0 / driver0.7.2。输入 query 是 FLOAT16 `[1,146,2]`、584 bytes、format3；提交仍为原 292 个 FLOAT32 像素坐标、pass-through0，不转置或重新归一化。四个中间输出均 FLOAT16、6208 项、query size12416、format0 / NCHW；gamma 形状为 `[1,64,97,1]`，其余三个为 `[1,1,97,64]`。final52 为 FLOAT16 `[52]`、104 bytes、format3。所有 `w_stride=0`、`size_with_stride=size`；want-float1 返回前四各24832 bytes、final208 bytes，严格保持原始平铺顺序。

## 同图模拟器与重复性

下表对照 `scale-taps-simulator-v1`，两轮统计完全相同；数值均有限且可逐位往返为 FP16。

|点|不同值 / 总值|变化 case|maxAbs|MAE|
|---|---:|---:|---:|---:|
|gamma_conv|309 / 571136|5|0.015625|0.00000428832843666|
|restore_scale|309 / 571136|5|0.015625|0.00000428832843666|
|x_scaled|556 / 571136|44|0.001953125|0.000000429726888291|
|negative_mean_scaled|571029 / 571136|92|8.021148681640625|0.732024031545878|
|final52|4784 / 4784|92|0.9940162897109985|0.2198343613624174|

同一轮的 `restore_scale` 与 `gamma_conv` 按原 `[0,3,2,1]` Transpose 恢复后，571136 值全部逐位相等；主机模拟也满足该关系。这里只检查同图关系，没有改写原验收排列。gamma/restore 共有 87 组完整输出与模拟器逐位一致。

更强子集有 **48 组**，其 gamma、restore、xScaled 三个完整输出都与同图模拟器逐位一致，但 48 组 negative_mean_scaled 全部出错，297928 / 297984 值不同，maxAbs `8.021148681640625`、MAE `0.7343017821439424`。这些组 final 的2496值也全部不同。少量 gamma 或 xScaled 舍入差异不是这个子集发生严重错误的必要条件。

### 负均值广播的诊断不变量

观测 scale 全为正，范围 `[2.44921875,37.53125]`。原算式的负均值输入是 `[1,1,97,1]`，每个 token 只有一个标量，乘上64通道的正 scale 后应保持一致的非零符号；正确的 FP16 舍入不会把单个标量的符号分裂。

设备 negative_mean_scaled 却在 **8504 / 8924 个 token** 内同时出现正数和负数，覆盖全部92个 case；同图模拟器混符号 token 为0。其范围为 `[-7.96484375,7.2890625]`，模拟器仅 `[-0.12054443359375,0.1527099609375]`。

只作诊断的 `negative_mean_scaled / restore_scale` 在每 token 的64通道间，极差中位数为 `0.3524361480402403`、最大 `1.0805254048317052`；模拟器分别仅 `0.000004241098416153145`、`0.00003280292716133226`。比值没有被用于修正输出或替代原精度门。这个关系优先指向负均值支路的操作数/广播/缓冲边界，而非已测正确的恢复 Transpose；由于没有直接观察该图的负均值输入或内部 live buffer，尚不能只责归 Mul 内核。

## 原 TF/ONNX 门与前图边界

final52 全有限，warmup/测量范围均合法，但精度严重失败：

|参考|maxAbs|global MAE|原 `.01 / .002` 门|
|---|---:|---:|---|
|原 TFlite|0.9940169155597687|0.21985049446637525|失败|
|当前诊断 ONNX|0.9940168857574463|0.21985049684061653|失败|

相对原 TF，3418 / 4784 项绝对误差大于 .01，覆盖92 / 92个 case、52 / 52个通道。中间点没有发明新的通过阈值；`application_eligible=false`。

与此前 LN 图的 final52 相比，4037 / 4784 项改变，92组全部改变，maxAbs `0.993011474609375`、MAE `0.26787727891800794`。此前 LN 图观测到的 affine 幅度407.75不能直接套用于当前图（本轮没有 affine 出口）。相对 stem/front/full-v4 final 的 MAE 分别为 `0.22008217138590222`、`0.1609762695720762`、`0.09868882412097127`。虽然两图主机模拟最终值相同，板端故障模式仍显著受观测出口影响；不拼接不同图的中间值来证明单算子根因。

## 最小候选修复建议（尚未实施）

仅针对首 LN 的 `batchnorm/mul_2`，将隐式的最后维广播改为**显式同形状输入**：保持原负均值计算 `[1,1,97,1]`，用无 bias、64个全1权重的1×1 Conv复制成 `[1,64,97,1]`，再用原型的 `[0,3,2,1]` Transpose 得到 `[1,1,97,64]`，最后交给原 Mul 与 scale 做同形状逐元素相乘。

这个候选利用已能编译/执行的1输入通道复制方式，数学上只是复制原负均值；保留原统计轴、实际 epsilon、gamma、xScaled、Add 顺序和全部52输出。它仍可能受到优化合并、舍入和 buffer 生命周期影响，所以不是已证修复，也不应一次改八处 LayerNorm。

后续需另行授权：独立生成候选、逐节点限定唯一改动，先通过原 TFlite92×52 `atol=1e-5, rtol=0` 和原图 ORT final 逐字节门，再实际编译并确认没有折回原隐式广播，跑同图模拟，最后用新独立包做板端完整门。若任何门失败，应保留失败证据，不放宽阈值。本轮没有新建观察图、修改模型或运行该候选。

## 可复核记录

主审计 `E:/tripo/output/mirror-program/20261003/blendshape-scale-device-audit.json`，SHA `b193a6f50592fdb542e54dc3d82a844a8d61829d06077ed97908ca56632ab7f9`。负均值不变量及 native 来源补充为 `blendshape-scale-branch-audit.json`，SHA `f0a8b6b395667316581c3c80b1074511aca7d1d9746e71cc0022d9174d4cae97`。实际审计脚本随输出保存，拒绝覆盖原证据。

|文件|SHA-256|
|---|---|
|v1 comparison.json|`f67a7706165d44997296123a1f29e8633b77241f294ea1c602cdefff162bbaac`|
|v1 report.jsonl|`7a9ee2de49d318dfa43584f2b12dc8574c9c86243440806ca510c16b7d2e51d1`|
|v2 comparison.json|`d3f4f59d28e1b19024a71951296078d95555ffd4666fac679159151cac8b4f7e`|
|v2 report.jsonl|`b0bc041cbb81226a52a420b8a8d2987cf6789133e1cb23fe290c57ed53785c3b`|
|两轮 native-exit.json|`124fd50cb6d223cdd18fedc166d00af7254199b6e6dedf9be82f71db87af6d01`|
|同图 simulator diagnostic.json|`1842f17312d0d5db5bd96c1022b30d6ab5e2aa363ae884b421b037bcb3c4e55e`|

本轮只做离线审计与新文档，没有 ADB、应用、PLAN 或 Git 写操作。
