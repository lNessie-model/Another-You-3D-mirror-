# 52 表情 RKNN 数值发散定位

2026-10-03。完整 MulPow 模型已经在旧运行库上执行完成，但严重精度失败。**尚未定位设备上的第一处分歧，当前结果不能用于替换应用模型或声称提速。** 本轮先排查输入舍入、检查本机模拟器，再准备有固定输入输出合同的五点观测模型；不更改误差门、驱动、vendor 库或应用。

前置证据见 [v4 设备精度验收](blendshape-device-accuracy-v4.md) 和 [等义转换过程](blendshape-onnx-experiment.md)。v4 的 92 组、4,784 值均有限且在范围内，全部 API 返回 0；但最大误差 0.972238、平均绝对误差 0.239576。其约 11.05 ms API 总耗时只属于失败模型，不是可用性能结果。

## 输入舍入不足以解释大误差

[`diagnose_blendshape_numerics.py`](../scripts/diagnose_blendshape_numerics.py) 复核候选 ONNX、原始 TFLite 数值报告与 NPZ 的 SHA，再逐行验证 92 组输入和参考输出身份。它不改变冻结的转换脚本。将输入单独做 `FLOAT32 → FLOAT16 → FLOAT32`，随后仍用 ONNX Runtime FLOAT32 计算，与原始 TFLite 比较：

独立审查发现初版 `analyze` 没有把设备输入和 RKNN 模型绑定到当前候选。现已修复：`analyze` 必须提供 `--compilation`，校验编译报告的候选名、ONNX/原始验证报告 SHA、四阶段返回码和实际 RKNN 文件的 SHA/大小，再要求设备 before/after 中输入 SHA 等于这 92 组 FLOAT32 原始字节、模型 SHA 等于该编译产物。旧代码在 6 种来源替换下没有拒绝，测试先 RED 后 GREEN；补强后的真实 92 组复算保存在 `input-rounding-v2-bound`，原数值结论未改变。保留初版报告作历史记录，不覆盖原证据。

| 实验 | 全 92 组最大 / 平均绝对误差 | 真实 72 组最大 / 平均 | 合成 20 组最大 / 平均 |
| --- | --- | --- | --- |
| 原候选 ONNX / 原输入 | 0.000001967 / 0.000000127 | 原完整数值门通过 | 原完整数值门通过 |
| 只舍入输入，后续 ONNX FLOAT32 | 0.074559 / 0.001340 | 0.014749 / 0.000820 | 0.074559 / 0.003214 |
| RKNN 本机模拟器 | 0.243225 / 0.002803 | 0.035322 / 0.001415 | 0.243225 / 0.007799 |
| v4 设备输出 | 0.972238 / 0.239576 | 0.958744 / 0.235450 | 0.972238 / 0.254430 |

输入坐标舍入的最大误差为 0.249878 像素。它确实影响部分系数，甚至足以使个别测试超过预定 FP16 门；但设备与“仅舍入输入”ONNX 的平均差异仍为 0.239565，不能据此解释设备的大范围失真。此实验没有证明设备真的按预期做了 F32→F16 转换；需要输入 echo 直接验证。

实际输出整体均值 0.267317，而原参考均值 0.107013；小于 0.01 的值分别为 1,241 / 2,424 个，大于 0.99 的值分别为 43 / 0 个。输出会随输入变化，不能归因为完全恒定输出；分布变化也不能单独判定是 gamma Conv、Pow、布局或溢出问题。

## 本机模拟器的实测边界

现有 `rknn/api/rknn.py` 的公开 `init_runtime` 文档明确 `target=None` 使用 simulator。本轮重新加载同一 ONNX，以原 `rk3566 / float16 / optimization_level=3 / do_quantization=false` 配置 build，再调用 `init_runtime(target=None)`；从未选择设备 target 或 device_id。

首次 `simulator-v1` 在初始化时返回 -1，错误是缺少 `libgomp.so.1`。已有 venv 的 `onnxruntime.libs/libgomp-7c85b1e2.so.1.0.0` 经 ELF 检查为 x86-64 GNU OpenMP 库，仅依赖现有 libc/pthread。将其原字节复制到新输出目录并命名 `libgomp.so.1`，仅对该进程设置 `LD_LIBRARY_PATH` 后，`simulator-v2` 的初始化和 92 组推理完成，外层 exit 0。没有安装依赖、修改 venv 或系统库；原库与副本 SHA256 均为 `3157f8f677b214ab1fd9da84c35d6059ee9b710ca6afbd3c3cb87b37486f8652`。

模拟器本身也未通过原定 FP16 精度门。设备相对模拟器的最大 / 平均差异为 0.973053 / 0.239740，真实 72 组平均差异 0.235548。因此不能将该模拟器当成板端的逐位仿真，也不能直接据模拟器输出排除板端算子实现或布局问题。

## 最小五输出探针

[`prepare_blendshape_taps.py`](../scripts/prepare_blendshape_taps.py) 在冻结的 MulPow ONNX 上只增加一个 input Identity 及 graph outputs，原所有节点、参数和输入顺序逐字节保留。完整 92 组 ORT 运行中，新增观测点后的最终 52 值与原候选逐字节一致，且对原 TFLite 最大误差仍为 0.000001967。

| index | label | 观测位置 | 每组元素数 | 允许的运行时形状 |
| ---: | --- | --- | ---: | --- |
| 0 | input_echo | 输入原值的 Identity | 292 | `[1,146,2]` |
| 1 | centered | `model_1/tf.math.subtract/Sub` | 292 | `[1,146,2]` 或 `[1,146,2,1]` |
| 2 | scale | `model_1/tf.math.reduce_mean_1/Mean` | 1 | `[1,1,1]` 或 `[1,1,1,1]` |
| 3 | normalized | `model_1/tf.math.truediv_1/truediv` | 292 | `[1,146,2]` 或 `[1,146,2,1]` |
| 4 | final52 | `StatefulPartitionedCall:0` | 52 | `[52]` |

扩展形状不是猜测：该模型的实际编译表中 centered/normalized 的 OutputOperator 为 `[1,146,2,1]`，scale 为 `[1,1,1,1]`；非单例轴仍是原 146×2。合同只列原 ONNX 与这几种明确的末尾单例扩展，不允许任意转置或自动重排。五个静态输出均为 FLOAT16，名称无别名；未知查询结果必须保存并停止，不临时猜测。

共五个输出、每组 929 个 FLOAT32。设备 helper 应用原 292 个 F32 输入、`pass_through=0`、查询得到的 fmt，分别以 want_float 获取五个输出；每个输出按固定文件名 `output-{label}.f32`、case-major 保存。输入、模型、helper、vendor、五个输出与日志均需要哈希链及完整 API/退出码。合同和 runner 经独立代理复核，运行和校验命令见 [五输出诊断工具](blendshape-five-tap-diagnostic.md)。

三个中间算术点只报告误差和分布，不新设宽松“通过”门。echo 与实际查询的输入/输出 dtype 对原值的确定性投影逐位比较，先确认转换与顺序；final52 同时对原 TFLite 和观测 ONNX 比较，继续使用最大误差 0.01、全局 MAE 0.002、范围容差 1e-5 的原门限。

附加输出可能改变 RKNN 的融合、缓冲区与调度。设备测试后还须比较 probe final52 与 v4 同 92 组实际输出；如果偏差模式改变，不能把观测图中的第一处偏差直接当成原完整图的同一故障。前端若无明显偏差，再按同样办法观察各 MixerBlock，避免未经定位继续改写整网。

五点观测图也已在本机模拟器运行完 92 组（`front-taps-simulator-v1`，exit 0）。其最终 4,784 值与未加观测点的 `simulator-v2` 输出逐字节相同；echo 的 26,864 值与原输入 F32→F16→F32 投影逐字节相同。其余阶段对候选 FLOAT32 ONNX 中间值的误差如下：

| 阶段 | 真实 72 组最大 / 平均绝对误差 | 合成 20 组最大 / 平均 |
| --- | --- | --- |
| centered，像素 | 0.338379 / 0.087690 | 0.528564 / 0.074694 |
| scale，像素 | 0.070976 / 0.031163 | 0.049850 / 0.009714 |
| normalized | 0.006180 / 0.001240 | 0.306558 / 0.016047 |
| final52 | 0.035322 / 0.001415 | 0.243225 / 0.007799 |

这给出了后续设备逐阶段比较的额外参考，并显示部分合成输入的前端归一化对低精度敏感。它仍不能解释设备真实样本约 0.235 的平均系数误差，不能据此确认设备上最早的故障算子。模拟器五个原始输出文件均保留，可与实际设备输出直接比较，不只依赖摘要。

## 五输出设备观测与独立进程复测

`blendshape-five-tap-device-v1` 完成 5 次预热、92 次测量，native exit 0，固定 checker 判定 `diagnostic_complete`、数值 exit 1。独立重放 checker 和哈希核对确认，输入、模型、helper、vendor 前后身份及全部输出/日志链一致。设备与 `front-taps-simulator-v1` 的原始 FLOAT32 文件逐位比较：

| 输出 | 比较值数 | 位差数 | 最大 / 平均绝对差异 |
| --- | ---: | ---: | --- |
| input_echo | 26,864 | 0 | 0 / 0 |
| centered | 26,864 | 0 | 0 / 0 |
| scale | 92 | 0 | 0 / 0 |
| normalized | 26,864 | 0 | 0 / 0 |
| final52 | 4,784 | 4,784 | 0.971100 / 0.212674 |

echo 也与原输入 F32→F16→F32 逐位一致。因此在这张五输出观测图、这 92 组输入中，设备与模拟器在已观测的归一化输出处一致，明显差异发生在其后；这不证明所有中间操作逐位一致，也不能把当前图的边界直接归因到原完整 v4 图。当前 final52 对原 TFLite 仍为最大 0.970285、MAE 0.212714，不能接受。它与原 v4 设备 final52 的 MAE 为 0.159175，说明增加输出确实改变了设备的最终失败模式。

同一冻结模型/helper/92 输入在新目录 `blendshape-five-tap-device-v2` 独立进程重复，完整 API 和退出码再通过，五个输出文件与 v1 **全部字节相同**。这仅证明这两次同顺序、同环境测试可重复，不能排除所有状态或布局问题。后续应只在归一化之后增加最少固定观测点，先比较首 Conv 输入、首 Conv 输出与首 LayerNorm 之前的 token embedding；不据此改写算子或放宽精度门。

独立审计摘要 `E:/tripo/output/mirror-program/20261003/blendshape-five-tap-simulator-audit.json` 的 SHA256 为 `728cf45d8e0a06bdd976e70bed6a0dfc982a53c86165ea2a87c2059fdaf82b1c`。第二次运行 `comparison.json` SHA 为 `906f01ab51126b809145f597bfc0fc7400bab7e3c36236a92246e810cacabd03`，原始 `report.jsonl` SHA 为 `9b0ef8c1dbe1dc65ef2f372eb5656a8351669a0a06dd4c1f93f419c129ce7fa4`。

## 可复核身份与当前验证

输出根目录：`E:/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/`。

| 文件 | SHA256 |
| --- | --- |
| `input-rounding-v1/diagnostic.json` | `8bcfd8647153f520c9611a5fbc6590bdf92397840d0b4554ba9e148d92f8f016` |
| `input-rounding-v2-bound/diagnostic.json` | `a4a9b74172d4b036adeb6b948779a6cda83e7c696a4d947665df3f9ff5b91514` |
| `simulator-v2/diagnostic.json` | `898d422e4e8a7bac0f10ff670d52e9c54374af8f52be4f125053162968b08856` |
| `simulator-v2/simulator-outputs.npy` | `d02067a90e8db6fd96f01d1541423c4b234323011fcf2cec27ed36c3625d1693` |
| `front-taps-simulator-v1/diagnostic.json` | `27c39aad1e74a1c9e8747371b72eb10fd9fe9e754908a6fcf5965136142b9439` |
| `front-taps-v1/front-taps.onnx` | `096401e3eb376589feeba67528a92da59fe6b07c9d42970d2e248774f0bc025c` |
| `front-taps-v1/diagnostic.json` | `96f697959e8ff0ce825033c2fb94ede617460d05fa2fee203d0e20b260ff66b9` |
| `front-taps-v1/compiled-contract.json` | `36e3bc4d67fd123215bc12bba6971128eca9e33705517a1ce873e4f588870eb7` |
| `front-taps-v1-compile/front-taps.rknn`，6,147,026 字节 | `2b0bc99450f094cfea259765ca5657905d4e1498f39824faab1012422372cf70` |
| v4 原设备 `outputs.f32` | `66b35d13c997d989811bc3b9cd5477c4b2334939349673ea4fa51f45df3bcd11` |

两个新诊断工具与冻结的转换脚本分开。新增 [host tests](../tests/test_blendshape_diagnostics.py) 共 8 项通过，覆盖实际输出附加函数、ORT 输入/最终输出不变、命名碰撞/未知节点拒绝、统计函数和 analysis 来源链；另有上述真实 92 组全图观测验证。编译 config/load/build/export 均返回 0、外层 exit 0。这些证据支持继续隔离诊断，不代表模型精度验收通过。
