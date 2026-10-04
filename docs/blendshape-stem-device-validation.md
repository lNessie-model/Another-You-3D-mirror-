# Stem 观测图设备数值验收

2026-10-03。固定 `stem-taps-v2` 模型在两个独立设备进程中都完整运行，但 **final52 精度失败，不能用于应用**。严格的首次位差在首 Conv；首个已观测到的严重失真区间则是 token embedding 到最终输出之间。尚未证明具体算子或内存生命周期的根因。

原始目录为 `E:/tripo/output/mirror-program/20261003/blendshape-stem-tap-device-v1` 和 `.../blendshape-stem-tap-device-v2`。两次 native exit 都为 0，checker 都为 `diagnostic_complete`、退出 1；退出 1 表示数值不符合原门限，不是缺少数据或 API 失败。每次有 398 对按序 begin/end、485 条 tensor 记录，即 5 次预热和 92 次测量的五个输出，所有 API/释放/destroy 返回 0。

独立审计重新运行冻结 checker，所得完整结果字典与两份保存报告分别完全相等，并核对了包与来源、模型/输入/helper/vendor 前后、五个输出、日志、外部退出码的哈希链。模型 SHA `d7103efdca08d02e91e731930b8c94899b071356c8c2907d32aed97922218e18`，helper SHA `81ca6b6d96b289039921a35989dbdc5976c68f062db24f04ae52ddd4e748a4eb`；运行库/SDK/driver 均为原固定版本。

实际查询名称与合同精确一致，输入为 FLOAT16 `[1,146,2]`、fmt 3，提交仍是原 292 个 FLOAT32、pass-through 0。前四个输出 fmt 0，final52 fmt 3；五个输出均 FLOAT16，want-float 返回的字节数严格符合合同，没有转置或猜测布局。

## 同图模拟器对照

参考为 `stem-taps-simulator-v2` 的实际原始输出，不混用旧观测图的模拟器。五个设备输出全部有限。

| 输出 | 值数 | 设备/模拟器位差数 | 最大绝对差异 | 平均绝对差异 |
| --- | ---: | ---: | ---: | ---: |
| normalized | 26,864 | 0 | 0 | 0 |
| stem_input | 26,864 | 0 | 0 | 0 |
| stem_projection | 17,664 | 155 | 0.000244141 | 0.0000001592 |
| token_embedding | 571,136 | 995 | 0.000244141 | 0.0000000428 |
| final52 | 4,784 | 4,784 | 0.995718956 | 0.218398473 |

首 Conv 和 embedding 的绝对差异很小，但不能笼统称为“全部只有 1 ULP”。首 Conv 最大相隔 6 个 FP16 编码步；embedding 在接近零的值上最多相隔 917 步，对应绝差约 0.000062406。没有非零值变号，全部值可自身 F16 round-trip 原样恢复。简单的 96×2 转置或反转会使对照明显更差，未发现能解释当前现象的非零固定 bias；这不能代替算子级证明。

**关键证据：25 组的整个 6,208 维 token_embedding 与模拟器逐位完全相同，final52 却仍严重不同。** 这些组各自最大的 final52 差异为 0.730255 至 0.995719，完整组号和 fixture 名列在审计 JSON。因此，已导出的 stem/embedding 小幅舍入差异并非这些严重输出错误的必要条件。它支持继续观察 embedding 之后的区域，尚不能直接指认 LayerNorm、Pow、gamma Conv、转置或缓冲区。

final52 对原始 TFLite 的最大误差为 **0.995708972**、全局 MAE **0.218339258**；对同图 FLOAT32 ONNX 为 0.995708913 / 0.218339264。两项均远超过保留的最大误差 0.01、全局 MAE 0.002 门。虽然值有限且在 [0,1] 容差内，仍明确失败。

## 可重复性和增加观测点的影响

第二次独立进程的五个原始输出文件与第一次 **全部逐字节相同**。这只证明这两次相同包、相同输入顺序、相同环境中的可重复性，不证明任意顺序或长时运行的确定性。

当前 final52 与原完整 v4 设备输出有 4,296/4,784 值发生位差，MAE 0.241467841；与上一张前端五输出图有 4,357 值位差，MAE 0.261692563，均涉及全部 92 组。增加观测点明显改变了设备最终失败模式，不能把本图的定位直接套到原完整模型。本图模拟器相对旧图也有 1 组、37 个 final52 值变化，已在 [准备与模拟器记录](blendshape-stem-diagnostic.md) 中说明。

## 下一最小诊断

当前没有证据支持直接修复某个计算算子。下一步建议仍限定五个出口，保留 token embedding（6,208）和 final52（52）作为前后参照，在首个 LayerNorm 内观察 mean（97）、invStd（97）和 affine output（6,208），共 12,662 值/组。原 graph 的 mean/variance 都沿通道轴 1 归约且 keepdims=1；最终形状和运行时合同仍须由实际准备/编译记录确认。

这能区分均值归约、方差/平方根倒数路径、以及 gamma/轴恢复/仿射组合三段。选择 LayerNorm 是因为它是当前区间的下一段计算，并非已经证明它有故障。先核对真实统计轴、epsilon 和运算顺序，保留所有节点参数及原 92×52 参考门，每张新图都重新检查最终输出变化；不修改 vendor/驱动，不根据小幅差异扩大容差。

## 审计文件

完整审计为 `E:/tripo/output/mirror-program/20261003/blendshape-stem-device-audit.json`，SHA256 **`43ef566ca221fce5acf27b5b165c2f3e51059ba2a582c7b76fa3424fecf5642c`**，含全部来源哈希、逐阶段差异、FP16 步数分布、25 组精确 embedding 子集及两次运行对照。

| 原始文件 | SHA256 |
| --- | --- |
| v1 `comparison.json` | `7dab60798a50724f42c818f34743ab1d3ad39ee899b5c47042bc07d7918a7a16` |
| v1 `report.jsonl` | `fdeedc92f2565cdf7b94875b1ae67924a8eccf9ac1403872259010ceaad8e763` |
| v2 `comparison.json` | `aa18c3f43857e554650f431c4c71af79e856ae055f465ef17ca319dc74ed1bdb` |
| v2 `report.jsonl` | `8f0fa154fe243fc61e2327e3c22e317a627ab9c1ed92bcb8ac548113fba7e8f5` |

本次文档审计仅复核已经收集的两次设备证据，未另运行 ADB、生成下一张观测模型或修改应用。诊断耗时不作为可用模型的性能收益。
