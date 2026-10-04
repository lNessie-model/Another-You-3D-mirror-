# 首 LN scale 分支：固定五点离线准备

2026-10-03。继 [LN 双轮板端验收](blendshape-ln-device-validation.md) 的 41 组精确 token/mean/inv_std 锚点之后，新增独立观察图以区分 gamma Conv、恢复轴序和两条乘法支路。原图全部计算节点、参数与运算顺序保持，只追加四个短 Identity；第五点仍是完整 final52。**离线准备、编译、主机模拟均完成，未运行这个新图的板端探针，也未获应用精度通过。**

## 固定输出及真实编译合同

原输入还是 `[1,146,2]`、292 个原始相邻 x/y 像素值。前缀 `P = model_1/GhumMarkerPoserMlpMixerGeneral/MLPMixer/MixerBlock_0/layer_norm1/`：

|序号/标签|原 tensor|短名出口|实际编译表及模拟器形状|
|---|---|---|---|
|0 gamma_conv|`P + batchnorm/mul/gamma_conv:0`|`diagnostic_scale_gamma`|`[1,64,97,1]`|
|1 restore_scale|`P + batchnorm/mul`|`diagnostic_scale_restored`|`[1,1,97,64]`|
|2 x_scaled|`P + batchnorm/mul_1`|`diagnostic_scale_x`|`[1,1,97,64]`|
|3 negative_mean_scaled|`P + batchnorm/mul_2`|`diagnostic_scale_negmean`|`[1,1,97,64]`|
|4 final52|`StatefulPartitionedCall:0`|同原名|`[52]`|

实际 compiler OutputOperator 五项均 FLOAT16，无名称截断或额外 shape 别名。每个大点 6208 项，总计 **24884 floats / 99536 F32 bytes 每组**，92 组共 **9157312 bytes**。后续板端必须建立新的固定容量与精确属性检查；当前旧探针不接受此 profile。shape/名称合同来自实际编译表，不代表已经观测 Android query 的 format；未知属性须失败，不能猜测转置。

在 host FP32 和同图模拟器上，`restore_scale` 均与 `gamma_conv` 的原 `[0,3,2,1]` 排列逐字节相同。这个关系仅用于诊断，不允许把排列后的值替代最终验收参考。该图没有第六个 affine 出口；如果两条乘法都正确但 final 仍错，还不能直接指认 Add 或后续节点。

## 实际运行与门限

新增 [prepare_blendshape_scale_taps.py](../scripts/prepare_blendshape_scale_taps.py) 与 [test_blendshape_scale_taps.py](../tests/test_blendshape_scale_taps.py)。复用并 SHA 固定已有 first-LN semantic guard，继续核对统计轴、实际 epsilon 字节、全部 gamma 权重及两条乘加的原始顺序。没有修改旧脚本。

6 项真实 ONNX/ORT 测试从缺模块 RED 到 GREEN：原节点/参数字节不变、独立 gamma 乘法与轴序核对、xScaled/negative-meanScaled 公式核对、参数/统计轴/源支路/别名/重复 producer/错误 IO 的拒绝。公式测试针对 host FP32，不宣称替代设备输出观察。

完整 prepare 对原 92 组输入及原 TFlite 参考逐组校验 SHA；新增图 final52 与原 `fbfed69d…ce287` 候选 **4784 项全字节相同**。相对原 TFlite maxAbs `1.9669532775878906e-6`、MAE `1.269932822921237e-7`，通过原 `atol=1e-5, rtol=0` host 门。

prepare、compile、simulate 三进程均 terminal exit 0，无后台任务。编译配置保持 RKNN `1.3.0-11912b58 / rk3566 / float16 / optimization_level=3 / do_quantization=False`；仍是 CPU/NPU 混合图。模拟明确 `init_runtime(target=None)`，全部 92×5 输出有限，shape 与上述表一致。

|模拟 final52 对照|位差|变化 case|maxAbs|MAE|
|---|---:|---|---:|---:|
|前一 LN 图|0 / 4784|无|0|0|
|front 图|0 / 4784|无|0|0|
|stem-v2 图|37 / 4784|仅 case10|0.00146484375|0.00000171180204|

模拟 final 相对原 TF 仍为 maxAbs `0.24322524666786194`、MAE `0.0028026385022491772`，没有通过完整精度验收。观察出口可能改变 RKNN 编译；模拟最终相同不能保证 Android 输出也相同。四个中间点仅报告误差，不添加新阈值；后续 final 仍保持原 TF 与同图 ONNX 双 `.01 maxAbs / .002 global MAE`、finite/range 门，`application_eligible=false`。

## 冻结文件

证据根目录为 `E:/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/`：

|文件|SHA-256|
|---|---|
|新 prepare 脚本|`d15171c1034108dd884304ae8a58a876f014aeaff7405ad697b5573499a9c510`|
|新测试脚本|`f98c573b4402e93677fede28e62ccd1d1f0d06d23a1211e7e3c0fab3788f5bcf`|
|scale-taps-v1/diagnostic.json|`8c24f352f5dddcee411ef55c09c5204879e1467e6859f742194942411a3ab519`|
|scale-taps-v1/scale-taps.onnx|`cfce1938ed6a59774a154e5437c074fa306fb2216d3ebc10276194e8671212d2`|
|scale-taps-v1-compile/scale-taps.rknn（6137275 bytes）|`b22b97351dc95c51817291f91304f9d7bed0d9ff6d4606c94b3bc713dfe7ad12`|
|scale-taps-v1/compiled-contract.json|`f2903366dd60884d7cd3b35324b04b60339be2964484b62155d4875c7c1aadf8`|
|scale-taps-v1/offline-audit.json|`666e3d502d7b220637415888058aabec801d17d96e11ca84c7aaa15430a0f9eb`|

`scale-taps-v1` 保存新源码、测试及实际 `audit_scale_taps.py` 生成器快照；沿用的语义 guard SHA 是 `7dd38c89691f27ab4baa9ddc39a8a046356dbeec7440f70314cfad260a991709`。完整编译表是 `scale-taps-v1-compile-host.log`；模拟输出与逐项误差是 `scale-taps-simulator-v1`。audit 包含对应来源 hash 和仪器化对照。

复现时使用既有 WSL venv；选择全新输出目录，保留 stdout/stderr host.log，不覆盖 v1：

```bash
PY=/mnt/e/tripo/native-tools/rknn13-venv/bin/python
SCRIPT=/mnt/e/tripo/device-lab/scripts/prepare_blendshape_scale_taps.py
BASE=/mnt/e/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis
VALID=/mnt/e/tripo/output/mirror-program/20261003/blendshape-broadcast/gamma-conv-mulpow-v1/numerical-validation.json
"$PY" -B /mnt/e/tripo/device-lab/tests/test_blendshape_scale_taps.py
"$PY" -B -u "$SCRIPT" prepare --validation "$VALID" --output "$BASE/scale-taps-new"
"$PY" -B -u "$SCRIPT" compile --manifest "$BASE/scale-taps-new/diagnostic.json" --output "$BASE/scale-taps-new-compile"
LD_LIBRARY_PATH="$BASE/simulator-existing-libs" "$PY" -B -u "$SCRIPT" simulate --manifest "$BASE/scale-taps-new/diagnostic.json" --output "$BASE/scale-taps-new-simulator"
```

本切片未修改 tap helper/checker、应用、vendor、driver、PLAN 或 Git 状态。下一步为独立只读复核；板端固定 profile 与运行须另行安排。
