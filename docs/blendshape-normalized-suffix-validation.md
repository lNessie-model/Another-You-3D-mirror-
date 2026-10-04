# 原 FP32 归一化 + 固定八处复制后段：离线编译与模拟器

2026-10-03。固定后段以原 FP32 前段输出作为输入，RKNN 1.3 模拟器完整执行 92 组后，对原 TFLite 的 maxAbs 为 **0.00417956710**，MAE **0.000259048384**。4784 项均有限、范围合法，无一项误差超过 `.01`；对原 TFlite及对应FP32后段的既有 `.01/.002/finite/range` 数值门均通过。**这只是主机模拟器结果，尚未运行 Android 设备或完整应用，也没有性能结论。**

本次没有改写后段算子或降低 52 项输出。仍使用八处负均值复制，见 `blendshape-all-ln-negmean-copy.md`；切点依据及一次舍入敏感度实验见 `blendshape-normalized-boundary-validation.md`。FP16 模拟结果没有通过 FP32 等价门 `1e-5/0`，该门仅由本次编译前的原 FP32 全链通过，两个验收范围不混用。

## 固定来源与参考

变体 `all8_copy_normalized_fp32_front_suffix_v1`；新增脚本 `scripts/experiment_normalized_suffix.py`，测试 `tests/test_normalized_suffix.py`。原所有模型、工具和门限保持冻结。

| 项目 | SHA-256 |
|---|---|
| 来源完整 MulPow ONNX | `fbfed69de13a93e5f0ce3c48843261651c353e66ca30ded2a9f08a33429ce287` |
| 来源完整八处复制 ONNX | `5d55f9f012499d177717e0a97954ee80858a0c2c0e67e4bed03313cde1e26200` |
| 本次候选 `candidate.onnx`（已冻结 copied_suffix 原字节） | `61c61dda91356545c9397906b6872bcfc52849b796355b6f44cb03de72c6af9b` |
| 实际输入：原前段 FP32 normalized 92×292 | `ff9ffb0b905d58966fb2c0a19f3406c8a4a7250e285837d2c7f9376c36357c8b` |
| 原 TFLite 92×52 参考 | `5945bbecedd7ee827a75091b81e12a84a88ca8f05807b034a52f528c9b917602` |
| 原整图 / FP32 后段 final52 | `4bfeeebea37c598573edee17f7e3daae07d3a8cc29c18056e663e25d7d93057d` |
| prepare 报告 | `6993184bdcfbfcdbb15bf4f17e3989b1bb53d91b14723e8f006b65a58420e4b2` |
| 导出的 RKNN（1,209,569 字节） | `17b0773a404521a8cf9624fa8147467d9fb3fdb309a641f244244b2910d4831c` |
| 模拟器最终 92×52 输出 | `5feef0f03d83288ea4f867ffe2db60eb9ae37c4f0381a06e6e467aec518a957b` |
| 已完成模拟器报告 | `b7561b2cc0e381db5a8b8398c768aa3c7a66fb0527a743f45ba75ba500043999` |

prepare 实际重新运行原前段 8 节点，所得 normalized 数组必须逐字节等于此前冻结 FP32 边界数组。原图整图、八处复制整图以及“原 FP32 前段 → 固定后段”92×52 全字节相同；对原 TFlite maxAbs `1.9669532775878906e-6`、MAE `1.269932822921237e-7`，通过 `atol=1e-5, rtol=0`。不会把预先半精度往返的数组替换为输入或参考。

图输入仍是 `model_1/tf.math.truediv_1/truediv`，FLOAT32 `[1,146,2]`，292 个值按原 XY 邻接顺序。其含义已经是归一化坐标，**不是旧单输出模型的原始像素坐标**。`cases.json` 保留原 92 个 case ID / 真实合成分组 / 原始像素输入 SHA / 原 TF 行 SHA，并新增各行 normalized 输入 SHA。输出仍是 `StatefulPartitionedCall:0` `[52]`。

## 实际编译与失败记录

现有 Toolkit2 `1.3.0-11912b58`，API SHA `7ba303d017f4874757e00ac87d570e49e15403fe67ed21bcdef137e4bd670ace`。配置固定 `target_platform=rk3566, float_dtype=float16, optimization_level=3, do_quantization=false`。config/load/build/export 均 rc0，主机进程 terminal0。

实际 compiler 表是 194 行，134 个 NPU 行、60 个 CPU 行；所有表行 dtype FLOAT16。这是**CPU/NPU 混合后段**，行数不代表耗时比例。输入行 0 为 FLOAT16 / CPU / `[1,146,2]`、原 normalized 名称；输出行 193 为 FLOAT16 / CPU / `[52]`。八组新增复制各自保留 NPU Conv `[1,1,97,1]×[64,1,1,1]→[1,64,97,1]`、CPU Transpose `→[1,1,97,64]`，相应负均值 Mul 两输入均 `[1,1,97,64]`；24 条对应表行已单独保存，未折回旧隐式广播。Android 实际 query format/stride 尚未查询，不能由 compiler 表冒充设备合同。

第一次模拟器进程已 terminal1：config/load/build rc0，`init_runtime(target=None)` rc=-1；官方栈 `_fake_weight_bias → fake_tensor` 报 `ImportError: libgomp.so.1`，尚未执行 inference。这是启动时漏配既有库搜索路径，保留整个失败目录和日志，未误记为数值失败。

随后仅对新进程指定此前已成功使用的 `LD_LIBRARY_PATH=.../simulator-existing-libs`，其中既有 `libgomp.so.1` SHA `3157f8f677b214ab1fd9da84c35d6059ee9b710ca6afbd3c3cb87b37486f8652`。未安装库、未修改 venv、未更换模型/脚本。新目录第二次模拟器 config/load/build/init 全 rc0，92/92 inference 完整，terminal0；`inputs_pass_through=[0]`，传入的 NumPy 数据仍 FLOAT32 原 normalized。初始化始终明确 `target=None`。

## 保持原门的数值结果

| 固定对照范围 | 对原 TF maxAbs | 对原 TF MAE | 对原 FP32 后段 maxAbs / MAE |
|---|---:|---:|---:|
| 全 92 组 | .004179567098617554 | .00025904838441949244 | .004181206226348877 / .0002590481029904407 |
| 真实 72 组 | .004179567098617554 | .00023419828900736486 | .004181206226348877 / .00023420315840814868 |
| 合成 20 组 | .0036910176277160645 | .0003485087279031518 | .0036913156509399414 / .000348489903486692 |

各组全部有限且在 `[-1e-5,1+1e-5]` 范围内。原 max `.01`、全局 MAE `.002` 未改变。全量最坏为 case 56 / `npu-28000` / channel 3：模拟器 `.286376953125`，原 TF `.29055652022361755`。合成最坏为 case 89 / `synthetic-17` / channel 19：`.548828125` 对 `.5525191426277161`。

此前 all8 完整图模拟器对同 92 原 TF 输出为 `.24322524666786194 / .0028026385022491772`，该旧图仍失败。本次改善与输入边界/所编译图一起变化，支持继续做隔离板端验证，不能仅凭跨图端点差异把剩余误差归因单一前端算子。新图只观察单一 final52，不拼接旧五点张量证明本图中间量，也不承诺设备与主机模拟器相同。

## 证据目录、测试和重现

证据根目录 `E:/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/`：

- `normalized-suffix-v1/`：候选、FP32 normalized 输入、原 TF 和原 FP32 输出、case 映射、prepare 报告、离线审计。
- `normalized-suffix-v1-compile/`：RKNN 导出、报告、backend log；同级 `-compile-host.log` 含实际 compiler 全表。
- `normalized-suffix-v1-simulator/`：缺现有库路径的失败记录。
- `normalized-suffix-v1-simulator-existing-libs/`：完整模拟器 92 输出和逐 case 报告。
- 各次 `*-command.json` 保存精确 argv；成功模拟器命令另存已有库路径/SHA。

审计脚本 `app/build/audit_normalized_suffix.py` 只读取已完成证据，独立重算每 case 和三组数值，核源链、输入/参考字节、实际表行及第一次失败，再排他创建 `offline-audit.json`。不得覆盖旧结果。正式脚本的输出目录同样要求不存在。

7 项新测试先缺模块 RED，再 actual ORT + mock RKNN 边界 GREEN：原 FP32 全链、拒绝半精度输入替换/行序颠倒/模型或参考改字节、原门和 IO 合同不可放宽、target=None/F32/pass0、config/load/build/export/init 与 release 同时异常、空导出、拒绝覆盖或设备模式。Mock 仅用于失败分支，不构成真实编译或模拟精度证据；真实运行如上独立记录。

```bash
PY=/mnt/e/tripo/native-tools/rknn13-venv/bin/python
SCRIPT=/mnt/e/tripo/device-lab/scripts/experiment_normalized_suffix.py
BASE=/mnt/e/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis
"$PY" -B -m unittest discover -s /mnt/e/tripo/device-lab/tests -p test_normalized_suffix.py
"$PY" -B -u "$SCRIPT" prepare --boundary-report "$BASE/normalized-boundary-host-v1/diagnostic.json" --validation /mnt/e/tripo/output/mirror-program/20261003/blendshape-broadcast/gamma-conv-mulpow-v1/numerical-validation.json --output "$BASE/normalized-suffix-reproduction-new"
"$PY" -B -u "$SCRIPT" compile --manifest "$BASE/normalized-suffix-reproduction-new/diagnostic.json" --output "$BASE/normalized-suffix-reproduction-new-compile"
LD_LIBRARY_PATH="$BASE/simulator-existing-libs" "$PY" -B -u "$SCRIPT" simulate --manifest "$BASE/normalized-suffix-reproduction-new/diagnostic.json" --output "$BASE/normalized-suffix-reproduction-new-simulator"
```

下一隔离设备包必须明确 normalized 输入语义、原始 case 与 TF 参考来源，不能使用旧 raw-pixel prepare 冒名通过。任何板端验收仍须原 `.01/.002/finite/range`，APK、CPU 前段部署、端到端延迟和资源占用均另需真实验证；本轮没有执行这些步骤。
