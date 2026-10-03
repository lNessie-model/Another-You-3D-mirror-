# Scale 分支固定板端探针交接

2026-10-03。本切片增加 `MIRROR_TAP_PROFILE=3` / `-Profile scale`，只支持 [已冻结的 scale 五点图](blendshape-scale-diagnostic.md)。默认 profile0 front、profile1 stem、profile2 LN 均保持。**新模型尚未在设备上执行；此交接不宣称精度或性能通过。** 不改应用、模型默认路径、vendor 或 driver。

## 固定接口与资源边界

输入 query 必须精确为原名 `serving_default_input_points:0`、FLOAT16 `[1,146,2]`、584 bytes、format3；实际 feed 保持原 292 个相邻 x/y 像素 FLOAT32、pass-through0、queried format。以下名字、形状、类型、索引与大小都严格校验，无 alias、额外 singleton 或任意张量列表：

|index / label|query name|shape|元素|want_float 后 bytes|92 组文件 bytes|
|---|---|---|---:|---:|---:|
|0 gamma_conv|`diagnostic_scale_gamma`|`[1,64,97,1]`|6208|24832|2284544|
|1 restore_scale|`diagnostic_scale_restored`|`[1,1,97,64]`|6208|24832|2284544|
|2 x_scaled|`diagnostic_scale_x`|`[1,1,97,64]`|6208|24832|2284544|
|3 negative_mean_scaled|`diagnostic_scale_negmean`|`[1,1,97,64]`|6208|24832|2284544|
|4 final52|`StatefulPartitionedCall:0`|`[52]`|52|208|19136|

query 类型必须 FLOAT16，size 必须 `2*elements`；允许 dense format0/1/3，拒绝 packed2/未知值。格式列表是合同限制，不是新模型已测 query 属性。保持原始 C-order，不根据名称或误差猜测转置。

整体目标容量为 **24884 floats / 99536 bytes**，内存偏移分别为 0、6208、12416、18624、24832。一次 outputs_get 取得五个输出，先全部检查，再全部复制，最后用原五项数组释放；部分 get 失败但已有非空指针时也走原释放路径。`rknn_tap_probe.c`、`rknn_tap_iteration.h` 完全未改。每个文件独立使用 `case*elements` 偏移，92 组总输出 9157312 bytes；5 次 warmup 不写测量文件。

没有 echo 出口，报告明确 `echo.available=false`。四个中间点只报告统计，不能作为概率使用。final52 对原 TF 与同图 ONNX 的门仍是 maxAbs≤.01、global MAE≤.002、全部 finite、范围 `[0,1]±1e-5`，且要求 warmup final 同样有限/范围合法。`application_eligible=false`、`performance_evidence=false` 不随结果改变。诊断出口可能改变编译，当前图不能直接替代正式模型。

## 冻结包与验证

新包：`E:/tripo/device-lab/app/build/blendshape-scale-tap-bundle-v1`。

|文件|bytes|SHA-256|
|---|---:|---|
|manifest.json|—|`104ba7ce16c56510086706a83f912d386de3e0800c590e02fddcd2695339b495`|
|scale-taps.rknn|6137275|`b22b97351dc95c51817291f91304f9d7bed0d9ff6d4606c94b3bc713dfe7ad12`|
|rknn_tap_probe|19384|`3c45a2e6cbf6fa35b638bbea9aa2739aa4c6636bc2e4de53aeb5ecc77f007a01`|
|inputs.f32|107456|`36aea412c6765dfbe7222db47253e2e3c734ef6366a2578c0ede282fb4cc13fc`|
|compiled-contract.json|7092|`f2903366dd60884d7cd3b35324b04b60339be2964484b62155d4875c7c1aadf8`|

构建记录 `app/build/scale-device-helper-v1/build.json` 使用既有 NDK r25c、Android30/aarch64，并记录四个 native 源哈希。新包包含 10 个文件和 9 个来源 hash，prepare 核对真实 compiler/report/model/ONNX、原 92 组输入和原 TF52 文件、全部五项参考以及 `profile=scale` 的 helper build metadata。旧 helper 错配会在建包前被拒绝。vendor runtime SHA 仍为 `01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de`。

新增 C 合同测试从 profile3 的编译 `#error` RED 到 GREEN：49855 个断言；原 iteration 实现按新容量编译后 49909 个断言通过。front 的1912/1999、stem的14148/14213、LN的25411/25465六套也全部通过。新增 Python 8 项测试从缺 `SCALE_PROFILE` RED 到 GREEN，覆盖同6208元素不同形状、同形状不同来源名称、最后 case 偏移/bytes/index、旧 helper/profile 拒绝、原精度门/非有限值/独立退出及日志哈希。既有 front/stem/LN 测试全部通过。

更新 checker 实际重读历史 front、stem、LN 各两轮共六份完整 raw，每个结果字典与原 `comparison.json` 全等；三份旧 bundle 的全部文件也仍匹配各原 manifest。记录见 `app/build/scale-profile-history-regression.json`，SHA `119af2b7922255a74da22d0b3ab9917d4c556c9fe0f3a6a1c3b86adffafa612d`，对应实际只读脚本 `app/build/check_scale_profile_regression.py`。

主机复现：`cmd /c tests\run_tap_contract_tests.cmd`；分别运行 `tests/test_blendshape_tap_check.py`、`test_blendshape_stem_check.py`、`test_blendshape_ln_check.py`、`test_blendshape_scale_check.py`。测试入口会复用导入的 fixture，部分旧入口测试数量包含导入类重复运行，不作为新增独立覆盖量。

新构建/建包必须换新目录，已有二进制和 bundle 拒绝覆盖：

```powershell
$python = 'C:/Users/lNessie/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$diag = 'E:/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis'
& ./scripts/build_tap_probe.ps1 -Profile scale -OutputDirectory app/build/scale-device-helper-new
& $python scripts/blendshape_tap_check.py prepare `
  --source "$diag/scale-taps-v1" --compilation "$diag/scale-taps-v1-compile/diagnostic.json" `
  --compiler-log "$diag/scale-taps-v1-compile-host.log" `
  --validation E:/tripo/output/mirror-program/20261003/blendshape-broadcast/gamma-conv-mulpow-v1/numerical-validation.json `
  --model "$diag/scale-taps-v1-compile/scale-taps.rknn" `
  --helper app/build/scale-device-helper-new/rknn_tap_probe --bundle app/build/blendshape-scale-tap-bundle-new
```

## 唯一设备操作员后续步骤

root 等当前应用测试结束并停止应用后，使用全新目录，例如 `/data/local/tmp/mirror-scale-tap-v1`，只推送已冻结 `scale-taps.rknn`、`inputs.f32`、`rknn_tap_probe`。先核这三文件和 `/vendor/lib64/librknnrt.so` 的 SHA；执行：

```text
/data/local/tmp/mirror-scale-tap-v1/rknn_tap_probe /vendor/lib64/librknnrt.so /data/local/tmp/mirror-scale-tap-v1/scale-taps.rknn /data/local/tmp/mirror-scale-tap-v1/inputs.f32 /data/local/tmp/mirror-scale-tap-v1/report.jsonl /data/local/tmp/mirror-scale-tap-v1
```

保存真实进程退出为 `native-exit.json`（`exit_code`），不能只依赖 native footer：报告最后关闭失败仍可能返回非零。保留完整 stdout/stderr、前后哈希；后哈希须包含 model/helper/input/vendor、report 和五个 `output-{gamma_conv,restore_scale,x_scaled,negative_mean_scaled,final52}.f32` 文件。二进制方式拉回；第二独立进程用另一个新目录，不覆盖第一轮。

完整 raw 后运行（示例设备目录仅为拟定目的地，不是执行证据）：

```powershell
& $python scripts/blendshape_tap_check.py check `
  --bundle E:/tripo/device-lab/app/build/blendshape-scale-tap-bundle-v1 `
  --evidence E:/tripo/output/mirror-program/20261003/blendshape-scale-tap-device-v1 `
  --baseline-output E:/tripo/output/mirror-program/20261003/blendshape-device-v4/outputs.f32 `
  --output E:/tripo/output/mirror-program/20261003/blendshape-scale-tap-device-v1/comparison.json
```

退出0表示完整运输证据与两项 final52 初步门符合；退出1表示证据完整但数值不符；退出2表示合同或证据失败。所有情况均不自动允许应用集成。same-graph simulator 与既有 LN 图比较仍需另做有来源 hash 约束的数值审计。
