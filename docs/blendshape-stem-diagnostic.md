# 固定 stem 五输出诊断

2026-10-03。本实验只观察归一化之后、首个 LayerNorm 之前的计算。输入仍为官方 146 点的 292 个相邻 x/y 像素值，92 组冻结输入和原始 TFLite 的全部 52 项参考值不变。**这是失败模型的隔离诊断，不是可部署模型或性能结果。**

前一轮 [前端五输出诊断](blendshape-numerical-diagnosis.md) 已证明：两次独立设备进程的全部输出逐字节可重复；其中 input echo、centered、scale、normalized 与本机模拟器逐位相同，而 final52 严重失真。这个边界只适用于那张观测图，不能直接归因到原完整 v4 图。后续观测不改写任何计算算子，也不降低精度要求。

## 最小观测范围

冻结 MulPow ONNX 中，normalized 后依次为 Reshape、全范围 Slice、乘 0.5、Reshape，随后是首 Conv、Transpose、embedding Conv、Concat。下表将这三小段分开，最后仍保留 final52 检查整图的变化。

| index | label | 出口 | 每组元素 | ONNX 形状 / 允许的查询形状 |
| ---: | --- | --- | ---: | --- |
| 0 | normalized | 原 `model_1/tf.math.truediv_1/truediv` | 292 | `[1,146,2]` 或实际编译的 `[1,146,2,1]` |
| 1 | stem_input | 首 Conv 输入的 `diagnostic_stem_input` | 292 | `[1,146,1,2]` |
| 2 | stem_projection | 首 Conv 输出的 `diagnostic_stem_projection` | 192 | `[1,96,1,2]` |
| 3 | token_embedding | 原 `model_1/GhumMarkerPoserMlpMixerGeneral/MLPMixer/AddExtraTokens/concat` | 6,208 | `[1,64,1,97]` |
| 4 | final52 | 原 `StatefulPartitionedCall:0` | 52 | `[52]` |

共 7,036 个 FLOAT32/组，92 组五个原始输出文件合计 2,589,248 字节。实际 compiler OutputOperator 表中五个出口都是 FLOAT16，名称与表中精确名称一致，没有需要猜测的别名。输入模型属性预期仍为 `[1,146,2]`、FLOAT16、UNDEFINED (3)，helper 提交原 FLOAT32、`pass_through=0` 和查询得到的 fmt。输出均请求 `want_float=1`，保持 C 顺序；不转置、不归一化、不按元素总数猜布局。

固定原始 RKNN/ONNX 节点和所有参数逐字节保留。仅在两个长名出口追加 Identity，并把 graph outputs 换成这五项；这些 Identity 不参与原最终输出路径。两处 Identity 的输入名完整保存在准备报告的 `source_tensors`，不会把短名误当成新的计算层。

## 长输出名失败与 v2 修正

初版 `stem-taps-v1` 直接导出两个长名称。编译成功，但实际本机模拟器第一次 inference 抛出 `KeyError`，键为编译器截短后的 `...GhumMark__0`。config/load/build/init 均返回 0，外层 exit 1；没有模拟器输出。失败模型、日志和当时的源脚本/测试副本均保留在 v1 目录，不能当作已运行数值证据。

v2 只为这两个出口增加上述短名 Identity。名称限制测试先 RED，再由修正转为 GREEN。6 项 host 测试直接使用实际转换函数与 ORT，覆盖原节点/权重不变、两个 Identity 连接、最终输出不变、首 Conv 轴顺序、碰撞/错误节点/形状拒绝。真实 92 组中，观测图的 final52 与原候选 ONNX **逐字节完全一致**；相对原 TFLite 最大误差 `1.9669532775878906e-6`、MAE `1.269932822921237e-7`，仍通过原 `atol=1e-5, rtol=0` 门。

v2 在原 toolkit `1.3.0-11912b58`、`rk3566 / float16 / optimization_level=3 / do_quantization=false` 下 config/load/build/export 全部返回 0，编译进程 exit 0。没有初始化 Android target 或改动应用、vendor、驱动。

## 同图本机模拟器

v2 使用公开 `init_runtime(target=None)` 跑完全部 92 组，外层 exit 0，五个输出均有限。它仍使用前一实验已核对的现有 OpenMP 库副本和进程级 `LD_LIBRARY_PATH`，没有安装或修改库。

| 输出 | 真实 72 组对候选 FLOAT32 ORT 的最大 / 平均绝对误差 |
| --- | --- |
| normalized | 0.006179929 / 0.001240053 |
| stem_input | 0.003089964 / 0.000620027 |
| stem_projection | 0.005108505 / 0.000670064 |
| token_embedding | 0.001680553 / 0.000130776 |
| final52 | 0.035322487 / 0.001415639 |

normalized 的 26,864 值仍与旧前端观测图的模拟器输出逐位相同。**新图模拟器的 final52 与旧图并非逐位相同**：1 组、37 值改变，最大差异 0.001464844，整体 MAE 0.000001712。增加观测点能影响 RKNN 编译，因此后续设备判断必须使用这张 v2 图的模拟器输出，同时报告与旧设备 final52 的差异。不能把旧模拟器结果直接当作新图逐位参考。

模拟器的部分 final52 仍超过原精度门，不因上述中间误差较小而接受模型。设备中间张量只报告有限数、误差分布、逐值/逐组比较；final52 仍同时对原 TFLite 和观测 ORT 使用最大误差 0.01、全局 MAE 0.002、范围容差 1e-5。真正的 v2 设备数据仍须独立 helper 的 API/文件/哈希完整性门，本文件不声明其已通过。

## 冻结文件与复现入口

所有实验目录位于 `E:/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/`。使用 **stem-taps-v2** 合同；v1 是失败历史。

| 文件 | SHA256 |
| --- | --- |
| `stem-taps-v2/diagnostic.json` | `a980796300e41687b1306342e88a5c0264861b4fb2b660a332b2d5e2804567be` |
| `stem-taps-v2/stem-taps.onnx` | `76dff2bb44a8235acdb8e1b2d3294cf2f9313fb29c389c027765cbd3cbabb62b` |
| `stem-taps-v2/compiled-contract.json` | `93d1250043e75b2a0285a4c3cfd1e097dd009c5595856f340df3c47dec9fdd7f` |
| `stem-taps-v2-compile/diagnostic.json` | `0811dc278c33167a990c9327c8f80f75267cfceb205c26570c45f4f03a89d9e2` |
| `stem-taps-v2-compile/stem-taps.rknn`，6,142,134 字节 | `d7103efdca08d02e91e731930b8c94899b071356c8c2907d32aed97922218e18` |
| `stem-taps-v2-compile-host.log` | `16b00e552f336c5689bc347f4e28a76deb80bf985b3897f018a5b89f33595661` |
| `stem-taps-simulator-v2/diagnostic.json` | `8abbac31c0032f6f0673c44f267bd741e61465a51c43e9debdd02d097fbbd458` |

新增脚本 [`prepare_blendshape_stem_taps.py`](../scripts/prepare_blendshape_stem_taps.py) 提供三个固定模式，均要求新的输出目录，拒绝覆盖已有实验：

```text
prepare --validation <原 gamma-conv-mulpow-v1/numerical-validation.json> --output <新准备目录>
compile --manifest <准备目录/diagnostic.json> --output <新编译目录>
simulate --manifest <准备目录/diagnostic.json> --output <新模拟器目录>
```

在现有 WSL venv 中运行，编译 stdout/stderr 另存独立 host log。准备阶段绑定原输入/参考的每组 SHA，编译/模拟器阶段再次校验 manifest、ONNX、五个参考和输入文件的 SHA/大小。合同从上述真实 OutputOperator 表、准备报告和编译报告建立，精确列出五个名字/形状及所有来源哈希。`tests/test_blendshape_stem_taps.py` 的 6 项测试和真实 92 组数值门支持继续隔离诊断，不代替板端数值验收。
