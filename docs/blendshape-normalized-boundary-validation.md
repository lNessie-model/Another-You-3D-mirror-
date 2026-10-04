# 归一化边界的一次 FP16 舍入：仅主机诊断

2026-10-03。原 FP32 前段完成归一化后，只把 `[1,146,2]` 边界张量做一次 `float32 → float16 → float32`，再运行不变的 FP32 后段：92 组相对原 TFLite 的最大绝对误差为 **0.00253546238**，MAE **0.000113180100**。这明显小于原始像素坐标先做一次相同舍入时的 0.0745585561 / 0.00134012009。

这是固定输入集上的数值敏感度证据。后段计算仍是 ORT FP32，**没有编译 RKNN、没有运行设备，也没有验证 FP16 后段**。本次舍入结果未通过原 FP32 等价门 `atol=1e-5, rtol=0`；它满足既有 `.01/.002` 数值界限也不代表设备验收通过。正式模型不变。

## 来源、切点和保留合同

脚本：`scripts/diagnose_normalized_boundary.py`；测试：`tests/test_normalized_boundary.py`。

本报告的“原图”特指已经通过原 TFLite 52 输出门的冻结 FP32 MulPow ONNX，不是声称 ONNX 中间量已经与 TFLite 中间量逐位比较。

| 固定来源 | SHA-256 |
|---|---|
| 原 MulPow ONNX | `fbfed69de13a93e5f0ce3c48843261651c353e66ca30ded2a9f08a33429ce287` |
| 八处负均值复制候选 | `5d55f9f012499d177717e0a97954ee80858a0c2c0e67e4bed03313cde1e26200` |
| 原 TFLite | `4f36dded049db18d76048567439b2a7f58f1daabc00d78bfe8f3ad396a2d2082` |
| 92 组原 TFLite 数值清单 | `8e9b99d024f723eb5c3f0e7736431b40b882da955800cd6bc242ea3863793746` |
| 原输入 `.f32` | `36aea412c6765dfbe7222db47253e2e3c734ef6366a2578c0ede282fb4cc13fc` |
| 原 TFLite 52 输出 `.f32` | `5945bbecedd7ee827a75091b81e12a84a88ca8f05807b034a52f528c9b917602` |
| 已冻结 all8 模拟器最终输出 | `84b9d97b34b9107619ed88375e1879ea591b70de69adf5efeb14000b7cc3c242` |

固定切点为 `model_1/tf.math.truediv_1/truediv`，FLOAT32 `[1,146,2]`，顺序仍为既有 146 点 × XY。原图前 8 个节点依次为 `ReduceMean / Sub / Mul / ReduceSum / Pow / ReduceMean / Pow / Mul`。既有属性、常量、Pow 指数和计算次序全部原样保留，没有新加缩放、epsilon 或拟合参数。前段直接执行这些原节点，并与仅增加同名 graph output 的完整原图输出逐字节核对。

原图后段 202 节点；八处复制候选后段 218 节点。两个后段只有该切点一个运行时输入。按拓扑序检查全部依赖，拒绝未解析输入、绕过切点的原始输入、重复 producer 或错误顺序。每个分区保留其来源的**全部** initializer 字节，包括不再使用的常量，因此本次 `.onnx` 文件大小不是内存或速度优化证据。还原节点列表和 graph IO 后，每个分区都必须恢复完整来源 ModelProto 字节；该检查包含权重、所有运算属性、opset 和元数据。

## 92 组结果与切分对照

原集合、顺序和 ID 没有筛选：前 72 组是冻结真实 landmark 输入，后 20 组是原 synthetic 输入。原始整图 FP32 相对 TFLite 的全量 maxAbs 为 `1.9669532775878906e-6`、MAE `1.269932822921237e-7`，原 `1e-5 / 0` 门通过。

以下四组完整 `[92,52]` 字节全部相同：原始整图 FP32、八处复制整图 FP32、原前段接未舍入原后段、原前段接未舍入八处复制后段。统一输出 SHA 是 `4bfeeebea37c598573edee17f7e3daae07d3a8cc29c18056e663e25d7d93057d`。原/复制后段在“边界舍入”和“原输入舍入”两种实验下也各自逐字节一致。

表中均对照同一原始 TFLite 52 输出；maxAbs / MAE 使用 float64 差值统计：

| 计算方式 | 全 92 组 maxAbs / MAE | 真实 72 组 maxAbs / MAE | 合成 20 组 maxAbs / MAE | 单值误差 >.01 |
|---|---|---|---|---|
| 原 FP32 归一化 → 边界一次半精度往返 → FP32 后段 | .00253546238 / .000113180100 | .00253546238 / .000126643541 | .000667154789 / .0000647117134 | 0 / 4784 |
| 原始像素坐标一次半精度往返 → 原整图 FP32 | .0745585561 / .00134012009 | .0147493780 / .000819646886 | .0745585561 / .00321382364 | 102 / 4784 |
| 已冻结 all8 RKNN 模拟器（本次只读取旧输出） | .243225247 / .00280263850 | .0353224277 / .00141477583 | .243225247 / .00779894412 | 283 / 4784 |

原始像素坐标舍入后有 24 组出现 >.01（真实 12、合成 12），模拟器为 72 组（真实 58、合成 14），边界一次舍入为 0 组。三组都有限且在既有范围界限内。JSON 中 `original_1e_5_gate` 和 `device_numeric_gate` 仅按各比较块的 reference 计算数值界限；即使这些布尔值为 true，也不表示执行了设备验收。

原像素坐标范围约 `[-82.6671,658.6074]`，一次半精度往返本身 maxAbs `.2498779296875`；原 FP32 归一化量范围 `[-2.8111513,2.8357098]`，相同往返 maxAbs `.0009613037109375`。这是当前输入集的量化尺度现象，不证明内部 FP16 运算误差已经消失，也不能把两个误差指标相减当作可加的误差来源分解。

## 对齐同一 case / 通道

全量报告保留每种方法的逐 case 52 项统计、最坏通道、case ID、分组及各组前十最坏 case，没有在不同方法之间更换样本。以下是固定同一 case / 通道的原始数值：

| case / fixture / channel | 原 TFLite | 归一化边界舍入 | 原始输入舍入 | all8 模拟器 |
|---|---:|---:|---:|---:|
| 0 / npu-0 / 20 | .275332153 | .274205267 | .290081531 | .291503906 |
| 20 / npu-10000 / 19 | .613603354 | .613440633 | .621056974 | .648925781 |
| 57 / reference-28000 / 3 | .293003678 | .295539141 | .296480298 | .297851563 |
| 86 / synthetic-14 / 21 | .541079164 | .540892959 | .466520607 | .325683594 |
| 87 / synthetic-15 / 47 | .237975925 | .238080114 | .262558222 | .481201172 |

边界舍入最坏是 case 57 / channel 3；原始输入舍入最坏是 case 86 / channel 21；all8 模拟器最坏是 case 87 / channel 47。边界舍入的合成组最坏是 case 84 / channel 39，误差 `.0006671547889709473`。

## 产物、重现与下一步边界

排他新目录：`E:/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/normalized-boundary-host-v1/`。`diagnostic.json` SHA：`dcde869aebbfd5fd3c160dfe906e5ef75bec2c0d97c775cccaba69847000cbc4`。目录保存四个仅主机 ONNX、原输入和原 TF 输出、两个输入舍入边界数组、九组 final52 数组以及完整 case 报告。

原 FP32 归一化数组 SHA `ff9ffb0b905d58966fb2c0a19f3406c8a4a7250e285837d2c7f9376c36357c8b`；边界往返 SHA `38f67341624c9d41147acfa65cb9bc5dd4a11f01a55613666eaab6ca95a68617`；边界实验 final52 SHA `1d5fca4e0ce4d3a30b636be04dcef6abbf5f55527db3fe2fb0597eb3919febd8`。

测试先因新模块不存在而 RED，实际既有 WSL ONNX 1.7.0 / ORT 1.6.0 / NumPy 1.19.5 下 8 项 GREEN：实际图完整恢复、实际后段输出、切点 name/shape/type/order、权重/元数据、输入逃逸/重复 producer、固定位置、half tie-to-even/符号零/非有限/溢出、最坏 case 和原数值界限。正式 92 组运行 exit0，`equivalence_gate=true`、`original_reference_gate=true`。这两个 gate 验证拆分和原 FP32 参照，不把舍入结果错误标成 1e-5 等价。

```powershell
wsl.exe -d Ubuntu-22.04 -- /mnt/e/tripo/native-tools/rknn13-venv/bin/python -B -m unittest discover -s /mnt/e/tripo/device-lab/tests -p test_normalized_boundary.py
wsl.exe -d Ubuntu-22.04 -- /mnt/e/tripo/native-tools/rknn13-venv/bin/python -B -u /mnt/e/tripo/device-lab/scripts/diagnose_normalized_boundary.py --validation /mnt/e/tripo/output/mirror-program/20261003/blendshape-broadcast/gamma-conv-mulpow-v1/numerical-validation.json --copied /mnt/e/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/all-ln-negmean-copy-v1/candidate.onnx --simulation /mnt/e/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/all-ln-negmean-copy-v1-simulator/diagnostic.json --output /mnt/e/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/normalized-boundary-host-reproduction-new
```

输出目录必须不存在；不会覆盖旧证据。脚本没有 RKNN compile/init/inference 路径。它读取既有 all8 模拟器输出，并核原图→八处复制候选→观测模型→已完成模拟器报告→final52 文件的 SHA / 合同链。

这个结果支持将“保持前端 FP32、把归一化后数据交给后端”作为**下一候选边界**交 root 评估，但不能预测真实 FP16 后段通过。若后续授权，最小实验应只编译同一八处复制后段，使用本次原 FP32 前段输出和不变 92 组原 TF 参照，先模拟器、再严格板端门；不扩展观察点、不改变其余参数/运算/阈值。当前尚未执行此编译或设备实验，当前 all8 完整模型仍不能进入正式应用，参见 `blendshape-all-ln-negmean-copy-device-validation.md`。
