# 首个 LayerNorm：固定五点离线诊断

2026-10-03：新增观察图已完成 92 组 ONNX 原参考核对、RKNN 1.3 编译及主机模拟，三个进程均正常退出 0，没有遗留编译任务。**这不是板端精度通过，也不是应用可用模型。** 未改应用、驱动、运行模型或 native probe profile；既有 front/stem 证据保持原样。

此前两次 stem 板端运行中，有 25 组完整 token embedding 与同图模拟器逐位相同，但 final52 仍严重错误，故下一小步观察 embedding 后的首个统计与仿射区间。该证据没有把 LayerNorm 判定为根因，详见 [stem 双运行验收](blendshape-stem-device-validation.md)。

## 原图语义与观测合同

固定源是 `gamma-conv-mulpow.onnx`，SHA-256 `fbfed69de13a93e5f0ce3c48843261651c353e66ca30ded2a9f08a33429ce287`。新图仅追加四个短名 Identity 作为观测出口，并更换 graph.output 元数据；原计算节点的序列及所有 initializer 序列化字节完全不变。原 final52 输出保留原名，五点均不回写原计算路径。

以下形状同时由 ONNX/ORT 输出、实际 RKNN 编译表、主机模拟输出核对，编译表五项均为 FLOAT16：

|序号/标签|固定出口名|形状|每组元素|
|---|---|---|---:|
|0 token_embedding|`diagnostic_ln_token`|`[1,64,1,97]`|6208|
|1 mean|`diagnostic_ln_mean`|`[1,1,1,97]`|97|
|2 inv_std|`diagnostic_ln_invstd`|`[1,1,1,97]`|97|
|3 affine|`diagnostic_ln_affine`|`[1,1,97,64]`|6208|
|4 final52|`StatefulPartitionedCall:0`|`[52]`|52|

合计 12,662 floats/case，92 组 FLOAT32 输出合计 4,659,616 bytes。输入保持原相邻 x/y 像素坐标顺序，`[1,146,2]` 共 292 项；没有转置、坐标归一化或删减输出。后续板端合同要求 query 完整名称、精确形状、FLOAT16 类型及允许的 dense format；未知属性应失败，不能猜测转置。格式列表是保守接口约束，当前没有新模型的板端 format 观测。

首 LayerNorm 的两次 ReduceMean 都是 `axes=[1], keepdims=1`，即每个 token 沿 64 通道统计。保留的实际 epsilon 是 FLOAT32 `1.0132789611816406e-6`，不是重新写入十进制 `1e-6`；其字节 SHA 为 `b213852deb9c883bea76a885d484bfccd6aa025fab4714959e5bed1a32a41d22`。gamma 为 `[64,1,1,1]`，字节 SHA `1afc69f8c025b18297241346060211825e083066982f9af1c5ecad3bc3f2a955`。

原运算顺序保持：`diff=token-mean`，`variance=ReduceMean(diff*diff)`，`invStd=1*Pow(Pow(variance+epsilon,0.5),-1)`。gamma 经原 1×1 Conv 和原转置生成 scale；affine 仍是 **`x*scale + (-mean)*scale`**，没有合并成 `(x-mean)*scale`。输出 affine 的最后两轴为 token/channel，不能与 token embedding 的 channel/token 布局混淆。

## 验证结果与仪器化边界

新增 6 项 actual-ONNX/ORT 测试经历缺模块 RED→GREEN，包含节点/参数字节不变、真实统计轴独立公式核对、改轴/epsilon/gamma/Pow/加法顺序拒绝、短名冲突、重复 producer 及错误 IO 拒绝。完整 prepare 另外逐组验证原 92 个输入、原 TFLite 参考 hash 和输入顺序。新增图的 final52 与原候选 **92×52 全字节相等**；相对原 TFLite，maxAbs `1.9669532775878906e-6`，MAE `1.269932822921237e-7`，满足原 host `atol=1e-5, rtol=0` 门。

RKNN 1.3.0-11912b58 使用 `rk3566 / float16 / optimization_level=3 / do_quantization=False`；编译 config/load/build/export 返回值均 0。模型仍是 CPU/NPU 混合图，编译成功不能称为全 NPU 或板端成功。主机模拟明确 `init_runtime(target=None)`，92 组五输出全部有限。

|新图主机模拟 vs 既有模拟|逐位变化数|变化 case（零起点）|maxAbs|MAE|
|---|---:|---|---:|---:|
|token embedding vs stem-v2|8 / 571136|10|0.000030517578125|1.0770113144e-10|
|final52 vs stem-v2|37 / 4784|10|0.00146484375|0.00000171180204|
|final52 vs front-v1|0 / 4784|无|0|0|

因此只有 ONNX 层的 final 被证明不变；RKNN 模拟器也会受观测出口影响。新图模拟器相对原 TF 的 final52 maxAbs `0.24322524666786194`、MAE `0.0028026385022491772`；真实输入 72 组分别 `0.03532242774963379 / 0.0014147758318846695`，合成 20 组分别 `0.24322524666786194 / 0.007798944115561404`。这些并不满足完整模型精度门。中间四项只提供误差分布，不另造统一通过阈值，也不能将累计输入 FP16 误差直接归因于某个运算符。

下一最小行动：经批准新增一个**固定**五输出板端合同，复用原 92 输入与 5 次 warmup，采集这五项原始 FLOAT32 返回值，与**同一新图**模拟器及 ORT 分别比较。先检查 token 锚点，再比较 mean、inv_std、affine 的首次显著分歧；保留 final52 相对原 TF/ORT 的 `.01 maxAbs / .002 global MAE` 原门及有限、范围检查，不将诊断图当应用候选。不根据此离线结果改 epsilon、统计轴、gamma 或全网络。

## 文件与复现

代码仅新增 [prepare_blendshape_ln_taps.py](../scripts/prepare_blendshape_ln_taps.py) 和 [test_blendshape_ln_taps.py](../tests/test_blendshape_ln_taps.py)。独立只读复核未发现 P1/P2；该复核未代替实际 ORT、编译与模拟运行。

证据根目录：`E:/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/`。所有证据目录独立创建，脚本拒绝覆盖已存在目录。关键 SHA-256：

|文件|SHA-256|
|---|---|
|新 prepare 脚本|`7dd38c89691f27ab4baa9ddc39a8a046356dbeec7440f70314cfad260a991709`|
|新测试脚本|`36ed1296b326efcc715b6c87a551466899a8704a5b55313d96360819a960fd6a`|
|ln-taps-v1/diagnostic.json|`de3cd790741e93b2aaa0c7c9bf4f099f46d4870a3cfbc1c60b2778cb4f28d82b`|
|ln-taps-v1/ln-taps.onnx|`c4f8845a93967ec2b7e866de1c601b8afef6cdcdbfbc4946ec62da54b6d97bce`|
|ln-taps-v1-compile/ln-taps.rknn（6,137,325 bytes）|`2e52062dfe3bf2c374dd594931c670a5bcd3c61495386b28c7f9c165c47725d3`|
|ln-taps-v1/compiled-contract.json|`e3038c3e41dc4672c9ea0cf822ff66f5de11bd3d24d4ee817a4743d37f8947fc`|
|ln-taps-v1/offline-audit.json|`dca18160d7740a1a111ebcfa881d72fcc573550bc44880d5efd7c35e40b024fc`|

`offline-audit.json` 另含 compile/sim 日志及报告哈希、逐项数值比较；生成它及固定编译合同的 `audit_ln_taps.py` 随证据保存。prepare 与测试源也在同目录存档。实际 compiler table 原始输出保存在 `ln-taps-v1-compile-host.log`，模拟原始日志为 `ln-taps-simulator-v1-host.log`。

在既有 WSL 环境运行（输出名换成全新名称，不覆盖 v1；stdout/stderr 分别保存为对应新 host.log）：

```bash
PY=/mnt/e/tripo/native-tools/rknn13-venv/bin/python
SCRIPT=/mnt/e/tripo/device-lab/scripts/prepare_blendshape_ln_taps.py
BASE=/mnt/e/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis
VALID=/mnt/e/tripo/output/mirror-program/20261003/blendshape-broadcast/gamma-conv-mulpow-v1/numerical-validation.json
"$PY" -B /mnt/e/tripo/device-lab/tests/test_blendshape_ln_taps.py
"$PY" -B -u "$SCRIPT" prepare --validation "$VALID" --output "$BASE/ln-taps-new"
"$PY" -B -u "$SCRIPT" compile --manifest "$BASE/ln-taps-new/diagnostic.json" --output "$BASE/ln-taps-new-compile"
LD_LIBRARY_PATH="$BASE/simulator-existing-libs" "$PY" -B -u "$SCRIPT" simulate --manifest "$BASE/ln-taps-new/diagnostic.json" --output "$BASE/ln-taps-new-simulator"
```

没有新增依赖；模拟器库路径使用已保存的现有 venv 库，不改系统库或板端运行时。
