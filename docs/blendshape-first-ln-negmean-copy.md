# 首个 LayerNorm 负均值显式复制候选

2026-10-03。此实验针对 [scale 板端诊断](blendshape-scale-device-validation.md) 中的一个明确区间：48 组输入的 gamma、restore_scale、xScaled 与同图模拟器全部逐位一致，但 negative_mean_scaled 显著不同。实验仅将首个 LayerNorm 的负均值广播改为显式复制，尚未进行板端验证，不能称为已修复根因或可替换正式模型。

## 改动和限制

源模型固定 SHA-256 为 `fbfed69de13a93e5f0ce3c48843261651c353e66ca30ded2a9f08a33429ce287`。新脚本 `scripts/experiment_first_ln_negmean_copy.py` 不改变已冻结的 `prepare_blendshape_scale_taps.py` 或其 SOURCE_SHA 门。

令 `P = model_1/GhumMarkerPoserMlpMixerGeneral/MLPMixer/MixerBlock_0/layer_norm1/`。唯一计算路径改动是：

1. 从原 `P + batchnorm/Neg` 的 `[1,1,97,1]` 张量出发，用无 bias、全 1 FLOAT32 `[64,1,1,1]` 权重的 1×1 Conv 复制为 `[1,64,97,1]`。
2. 使用 `Transpose[0,3,2,1]` 得到 `[1,1,97,64]`。
3. 仅替换原 `P + batchnorm/mul_2` 的第一个输入，第二个输入仍为原 scale；乘法顺序、后续加法、统计轴、epsilon、gamma、x 分支及其余 LayerNorm 均保持原样。

验证器删除这两个指定节点和一个新常量、还原这个输入后，要求完整 ModelProto 序列化字节与原模型相同。新增候选是 212 个计算节点、99 个 initializer；观测版另加现有 scale profile 的四个短名 Identity，五个输出的名字和形状不变。没有引入新的观测图类型。

单通道全 1 Conv 的复制公式独立运行验证，包括正负值和零；不声称 Conv 累加在所有后端保持负零符号。完整 92 组最终 52 项输出的逐字节门独立执行，不因这个说明放宽。

## 实际主机结果

| 检查 | 结果 |
|---|---|
| 测试先于实现 | 实际缺模块 RED，随后 6 项实际 ORT 测试 GREEN |
| 原 ONNX → 新候选 → 观测版，92×52 | 全部逐字节相等 |
| 对原始 TFLite 92×52 | maxAbs `1.9669532775878906e-6`，MAE `1.269932822921237e-7`；atol `1e-5`、rtol `0` 通过 |
| 四中间参考及 final52 对旧 scale ONNX 参考 | 五文件全部逐字节相等 |
| RKNN 1.3.0-11912b58 编译 | config/load/build/export 均 0，进程终态 0 |
| 同 ONNX、同配置 RKNN 模拟器 | `target=None`；92 组全部完成，进程终态 0 |
| 五模拟器输出对旧 scale 模拟器 | 五文件全部逐字节相等 |

六项测试覆盖独立复制公式、实际 ORT 输出、唯一改动恢复、错误输入/reshape/统计轴/乘法输入、名称冲突及重复改写、无关节点/参数改动拒绝。命令：

```powershell
wsl.exe -d Ubuntu-22.04 -- /mnt/e/tripo/native-tools/rknn13-venv/bin/python -B /mnt/e/tripo/device-lab/tests/test_first_ln_negmean_copy.py
```

使用已有离线 venv：NumPy 1.19.5、ONNX 1.7、ORT 1.6、RKNN 1.3.0-11912b58。未安装依赖、联网、调用 ADB 或修改应用。

模拟器最终值对原 TFLite 仍为 maxAbs `0.24322524666786194`、MAE `0.0028026385022491772`，没有达到原 `.01/.002` 的最终精度门。本次模拟器只验证改写未改变既有模拟器数值模式；它通过重新 build 相同 ONNX/config 运行，不能替代导出 RKNN 在设备上的验收。CPU/NPU 混合分区仍然存在，无性能收益结论。

## 编译后显式复制确实保留

`first-ln-negmean-copy-v1-compile-host.log` 的真实算子表：

| 表中 ID | 算子/位置 | 类型、执行端 | 输入 → 输出 |
|---|---|---|---|
| 21 | `diagnostic_negmean_copy_conv` | FLOAT16、NPU | `[1,1,97,1]` 和 `[64,1,1,1]` → `[1,64,97,1]` |
| 22 | `diagnostic_negmean_copy_restore` | FLOAT16、CPU | `[1,64,97,1]` → `[1,1,97,64]` |
| 32 | 首 LN `batchnorm/mul_2` | FLOAT16、NPU | 两个 `[1,1,97,64]` → `[1,1,97,64]` |

这三行分别在日志第 784、785、795 行，保存于 `compiled-contract.json` 与 `offline-audit.json` 的原始行字段。编译器没有将这个操作折回原来的广播乘法；其余 LN 仍保留原广播路径，未扩大修复范围。

实际 OutputOperator 表与模拟器均为以下五个输出，每组 24,884 个值：

| index | 名字 | 实际编译形状 | 类型 |
|---|---|---|---|
| 0 | `diagnostic_scale_gamma` | `[1,64,97,1]` | FLOAT16 |
| 1 | `diagnostic_scale_restored` | `[1,1,97,64]` | FLOAT16 |
| 2 | `diagnostic_scale_x` | `[1,1,97,64]` | FLOAT16 |
| 3 | `diagnostic_scale_negmean` | `[1,1,97,64]` | FLOAT16 |
| 4 | `StatefulPartitionedCall:0` | `[52]` | FLOAT16 |

设备 fmt/stride 尚未查询。合同只容已冻结 profile3 的原 shape/name/type 与 dense fmt 守卫；不允许为通过检查而转置、更名或重解释布局。

## 文件和复现

证据根目录是 `E:/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/`，均为独立新目录，创建时拒绝覆盖：

- `first-ln-negmean-copy-v1/`：candidate.onnx、scale-taps.onnx、原输入/原 TF 输出、五份 FP32 参考、diagnostic.json、compiled-contract.json、offline-audit.json。
- `first-ln-negmean-copy-v1-compile/`：实际导出 RKNN 与诊断报告；同级 `first-ln-negmean-copy-v1-compile-host.log`。
- `first-ln-negmean-copy-v1-simulator/`：五份同图模拟器值与报告；同级 `first-ln-negmean-copy-v1-simulator-host.log`。
- 审计器 `app/build/audit_first_ln_negmean_copy.py` 只读原始文件、核哈希与表，再排他创建两份新证据；不能在同目录覆盖重跑。

| 项目 | SHA-256 |
|---|---|
| 实验脚本 | `2d46d085cac380790335c2c55be9290231af9be27a78ab038a003c61679cb439` |
| 六项测试 | `538523eb2922ff6f24a6d5015ee7e2919319a16c04da0ef20722f81b3f5b2ead` |
| 无观测候选 ONNX | `f39ec69233fbc193f93e9362bec24eb5eee3b66a8f1e4351a91efaba9710b862` |
| 观测 ONNX | `b5993d82134d4c4850e72b4b5ec62f2d831a56caf7fec79df294becb1e03bd5d` |
| prepare 报告 | `7ed84e8beb9be3ae5025b4a482cd0522d04084ed4ba380a2b9df6170b8df7cdb` |
| RKNN，5,523,195 字节 | `71058d175561ea0ca2cdcdb283305be23e0ab5ae41238735a204f638fe8d2240` |
| 编译合同 | `d8c4883005a04a2563865ed866bd213eab76d2d9d5e98e89987cf9dc7540dce0` |
| 离线审计 | `1ec5e6e42b79ee977329cd2f5788d2f5096707d0f413874823d6dc00b87bdc15` |

复现使用脚本的 `prepare/compile/simulate` 三个子命令。prepare 的 `--validation` 指向原 `blendshape-broadcast/gamma-conv-mulpow-v1/numerical-validation.json`；compile/simulate 的 `--manifest` 指向新 prepare 报告，各自 `--output` 必须为不存在的新目录。simulate 使用既有 `simulator-existing-libs` 的 LD_LIBRARY_PATH，禁止隐式选择 Android target。

## 最小板端交接提案

待独立 fresh review 后，由 root 决定是否执行。已有冻结 profile3 helper 可承载这五个精确输出，故不需要改 helper、checker 或增加任意张量选择机制。可使用现有 `blendshape_tap_check.py prepare`，但必须指向这里的新 source、compile report、compiler log、RKNN，保留原 TFLite validation 与原 scale helper。新包目录建议 `app/build/blendshape-first-ln-negmean-copy-bundle-v1`，与旧 `blendshape-scale-tap-bundle-v1` 分开；旧模型、包与报告全部保持。

新 `diagnostic.json` 和合同均显式记录 `model_variant=first_ln_negative_mean_explicit_copy_v1`、原 source SHA、新 candidate SHA、observed SHA。旧 checker 的原始 provenance 会覆盖这些文件及哈希；实际包也应保存一份单独 variant 交接记录，不能把本次结果叫作旧 scale 重测。

板端仍需原 292 个 FLOAT32 相邻 x/y 像素值、pass_through=0、query fmt、5 warmup+92 cases、一次获取五输出且全量校验后复制、原数组释放。建议两次独立进程、两个新 raw 目录；每次收齐退出码、API 日志和输入/模型/vendor/helper/五输出的前后哈希。原 final52 对 TF 和该候选 ORT 的 maxAbs `.01`、MAE `.002`、finite/range 门不变，无 echo 验收。中间输出只作诊断，并比较同图 simulator、旧 scale 数值模式以及跨进程确定性。

首要判别是此前负均值通道混合正负和大幅误差是否消失，以及其他分支是否改变。即使这个区间改善，后续七个 LN 和完整 52 项精度也必须分别过门；未通过前，不替换正式应用模型，不宣称整网修复、NPU 加速或 400×640 渲染目标已经达成。
