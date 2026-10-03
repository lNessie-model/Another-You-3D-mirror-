# 固定八个 LN 负均值显式复制候选

2026-10-03。基于 [首 LN 单点候选的两轮板端证据](blendshape-first-ln-negmean-copy-device-validation.md)，本轮只将同一种改写扩展到原图中精确识别的其余七个同构分支。离线完整性和编译检查通过；**本候选尚未板测，模拟器对原 TF 仍不满足最终精度门，不能用于应用。**

## 原图枚举和允许改动

源 ONNX 固定为 `fbfed69de13a93e5f0ce3c48843261651c353e66ca30ded2a9f08a33429ce287`。精确枚举为 4 个 MixerBlock × `layer_norm1/2` 共 8 个目标，不是任意名字匹配后自动改写。以下 `R` 为 `model_1/GhumMarkerPoserMlpMixerGeneral/MLPMixer/`，`Bi` 为 `R + MixerBlock_i/`，每行目标前缀为 `Bi + layer_normj/`。

| index / 分支 | mean 的输入张量 | 原 mean Reshape | 原 invStd Reshape | gamma initializer |
|---|---|---|---|---|
| 0 / B0 LN1 | `R + AddExtraTokens/concat` | `Transpose__164` | `Transpose__232` | `const_fold_opt__388` |
| 1 / B0 LN2 | `B0 + residual_tokens/add` | `Transpose__220` | `Transpose__290` | `const_fold_opt__378` |
| 2 / B1 LN1 | `B0 + residual_channels/add` | `Transpose__188` | `Transpose__249` | `const_fold_opt__355` |
| 3 / B1 LN2 | `B1 + residual_tokens/add` | `Transpose__238` | `Transpose__301` | `const_fold_opt__367` |
| 4 / B2 LN1 | `B1 + residual_channels/add` | `Transpose__203` | `Transpose__264` | `const_fold_opt__375` |
| 5 / B2 LN2 | `B2 + residual_tokens/add` | `Transpose__250` | `Transpose__312` | `const_fold_opt__344` |
| 6 / B3 LN1 | `B2 + residual_channels/add` | `Transpose__212` | `Transpose__279` | `const_fold_opt__349` |
| 7 / B3 LN2 | `B3 + residual_tokens/add` | `Transpose__265` | `Transpose__323` | `const_fold_opt__354` |

八个 mean producer 均为各自前缀的 `moments/mean`，均 `ReduceMean(axes=[1], keepdims=1)`，输入 `[1,64,1,97]`、输出 `[1,1,1,97]`。原 Reshape 使用相同 INT64 literal `new_shape__333=[1,1,97,1]`，随后原 `Mul(-1)` 得到负均值。gamma 均 FLOAT32 `[64,1,1,1]`，原无 bias 1×1 Conv 将 invStd 变为 `[1,64,97,1]`，原 `gamma_restore_axes` 为 `Transpose[0,3,2,1]`，得到 scale `[1,1,97,64]`。

目标均为各前缀的 `batchnorm/mul_2`，原输入顺序严格 `[batchnorm/Neg, batchnorm/mul]`；后续 affine 原顺序是 `x*scale + (-mean)*scale`。八点的节点名称、gamma 字节 SHA、形状及来源逐项记录在 prepare 报告 `enumerated_branches`。

新脚本 `scripts/experiment_all_ln_negmean_copy.py` 对每点新增一个全 1 FLOAT32 `[64,1,1,1]` initializer、一个无 bias 1×1 Conv 和一个 `[0,3,2,1]` Transpose，只替换对应 Mul 的第一个输入。首点新增节点与常量字节完全复用已冻结首 LN 候选；其余七点使用不同的固定短名。删除新增 16 节点/8 常量、恢复 8 个输入后，要求**完整 ModelProto 序列化字节与原图相等**。所有原 initializer、epsilon、统计、逆标准差和乘加顺序均受此门约束。

候选原有 210 个计算节点/98 个 initializer 变为 226/106；观测版仅增加已有 scale profile 的四个 Identity，仍为原五输出合同，不新增观测类型。原首 LN 脚本、旧 prepare 的 SOURCE_SHA、helper、checker、应用均未修改。

## 实际离线结果

- 测试先取得真实缺模块 RED；修正测试对旧 protobuf 容器反转 API 的用法后，8 项实际 ORT 测试 GREEN。覆盖逐分支复制公式、错误形状、八点轴/乘法次序/gamma/Transpose 变异、完整恢复、遗漏复制节点/非全1权重/无关变更、重复或未知分支、非法 index；首点与旧候选的节点/常量字节也独立对照。
- 原图 → 八点候选 → 首五点观测版，92×52 全部逐字节一致。原 TFLite maxAbs `1.9669532775878906e-6`、MAE `1.269932822921237e-7`，atol `1e-5`、rtol `0`。
- 四个中间 FP32 参考和最终 FP32 参考均与旧 scale 参考逐字节一致。
- RKNN `1.3.0-11912b58` 实际 config/load/build/export 均返回 0，进程终态 0；配置仍 rk3566、float16、optimization_level3、do_quantization=false。
- 以同一 ONNX/config 重新构建的 RKNN 模拟器 `target=None` 完成 92 组，进程终态 0。五个输出与旧 scale 模拟器全部逐字节一致（首点候选模拟器此前也与旧 scale 全等）。
- 模拟器最终对原 TF 仍为 maxAbs `0.24322524666786194`、MAE `0.0028026385022491772`；没有因此放宽原 `.01/.002` 门。模拟器不是导出 RKNN 的板端运行证明。

测试命令：

```powershell
wsl.exe -d Ubuntu-22.04 -- /mnt/e/tripo/native-tools/rknn13-venv/bin/python -B /mnt/e/tripo/device-lab/tests/test_all_ln_negmean_copy.py
```

使用已有离线 NumPy1.19.5 / ONNX1.7 / ORT1.6 / RKNN1.3 环境。未安装依赖、联网、调用设备或修改 driver。

## 八点编译后检查

真实 compiler table 对每点均保留复制 Conv `[1,1,97,1] + [64,1,1,1] → [1,64,97,1]`（FLOAT16 NPU）、Transpose `[1,64,97,1] → [1,1,97,64]`（FLOAT16 CPU），以及目标 Mul **两个 `[1,1,97,64]` 输入**（FLOAT16 NPU）。未折回隐式广播。八个 mean 在编译表中变为 `moments/mean_2avgpool` Conv，实际输入/输出仍分别 `[1,64,1,97]` / `[1,1,1,97]`。

| 分支 | mean 表 ID | 复制 Conv / Transpose ID | 目标 Mul ID |
|---|---:|---:|---:|
| B0 LN1 | 18 | 21 / 22 | 32 |
| B0 LN2 | 45 | 48 / 49 | 59 |
| B1 LN1 | 67 | 70 / 71 | 81 |
| B1 LN2 | 90 | 93 / 94 | 104 |
| B2 LN1 | 112 | 115 / 116 | 126 |
| B2 LN2 | 135 | 138 / 139 | 149 |
| B3 LN1 | 157 | 160 / 161 | 171 |
| B3 LN2 | 180 | 183 / 184 | 194 |

32 条完整原始表行及行号保留在新 compiled-contract/offline-audit 中。CPU Transpose 等混合分区仍在；RKNN 文件变小不作为性能证据。

实际编译五输出与旧 profile3 完全相同：`diagnostic_scale_gamma [1,64,97,1]`、`diagnostic_scale_restored/diagnostic_scale_x/diagnostic_scale_negmean [1,1,97,64]`、`StatefulPartitionedCall:0 [52]`，均 FLOAT16，共24884元素。设备 fmt/stride 尚待 query，不能从离线形状推定设备已通过。

## 冻结路径和哈希

证据根目录为 `E:/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/`。新目录为 `all-ln-negmean-copy-v1`、`all-ln-negmean-copy-v1-compile`、`all-ln-negmean-copy-v1-simulator`，对应 compile/simulator-host.log 位于同级。所有输出创建拒绝覆盖；旧首点候选保持。

| 文件 | SHA-256 |
|---|---|
| experiment_all_ln_negmean_copy.py | `bf9d28977643e1312182d4edc511d39754f52e7766a965cc478cfac6705dbc30` |
| test_all_ln_negmean_copy.py | `519e096cf86e40eb2d34fe26d1f03df69a9faf80fe82cfa76f1e71587bfb4ee5` |
| candidate.onnx | `5d55f9f012499d177717e0a97954ee80858a0c2c0e67e4bed03313cde1e26200` |
| scale-taps.onnx | `b4f3242b985ec4795f08a5e6699543987d4d4352ebe3741c7fd67fe4daed9cab` |
| prepare diagnostic.json | `7e12160f8e2660a080901ad753e47a73810250323cf173499f45c337ce959318` |
| RKNN，1,224,123 bytes | `954deb1c038b4c61e6f69b635ebe026dcf01d548ffc07bb9a2c1f41aaf758348` |
| compiled-contract.json | `fa5937635d0286ef5a4ff03d633ab5acf538aedd312cb23c07798a54a38f008e` |
| offline-audit.json | `1a8fbf1f14e95dfe62ee51ebc6e5ccf9ec3641583d54cd9916459f661b004a6f` |

只读审计器为 `app/build/audit_all_ln_negmean_copy.py`，其快照随源码/测试/文档归档到新 prepare 目录。variant 明确为 `all_eight_ln_negative_mean_explicit_copy_v1`，原 source、候选、观测与导出 RKNN 各自 SHA 分开记录，不冒充旧图重测。

复现使用新实验脚本的 prepare/compile/simulate，原 `--validation` 保持 `blendshape-broadcast/gamma-conv-mulpow-v1/numerical-validation.json`，各 `--output` 必须新建目录。compile/simulate 对新 manifest 重新核原图、八点完整恢复、观察图重建字节与输入/原 TF 来源；不绕用旧 prepare 的 source guard。

## 板端下一步边界

独立 fresh review 通过后可用原 profile3 helper/checker 建立新 `app/build/blendshape-all-ln-negmean-copy-bundle-v1`，不改 native 或 checker。全部五输出继续 exact name/shape/type 守卫、原 292 FLOAT32 feed/pass0、一次 get 五输出、全校验后复制再释放。两个独立设备进程使用新目录，保留全部退出/API/五文件/前后 SHA。

第一阶段判别是导出的板端输出是否接近**本图模拟器**，不是把模拟器现有 TF 误差当合格。最终对原 TF 与候选 ORT 仍须原 maxAbs .01 / MAE .002 / finite/range；没有通过就维持 application_eligible=false。即使设备与模拟器接近，后续仍需独立处理对原 TF 的 FP16 精度问题，不在本实验修改 epsilon、坐标、输出数或阈值。
