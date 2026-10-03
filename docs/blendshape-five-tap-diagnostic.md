# Fixed five-tap RKNN diagnostic

This independent executable investigates the failed [v4 numerical result](blendshape-device-accuracy-v4.md). It does not install an APK, replace application models, or modify the runtime/driver. The host scripts do not invoke ADB. The device operator must finish other workloads and stop the application before running it.

The diagnostic graph preserves the candidate's nodes/constants and appends five outputs. Its ONNX final52 is byte-identical to the prior candidate for the 92 fixtures; each fixture remains tied to the original TFLite reference. Adding graph outputs can change RKNN compilation, so an intermediate difference is a diagnostic observation, not proof of the original full graph's first failing operator.

## Fixed contract

Input is exactly `[1,146,2]`, 292 adjacent pixel x/y FLOAT32 values per case in original C order. Model query must report FLOAT16, format UNDEFINED (3), 584 bytes, exact name `serving_default_input_points:0`. The helper requests FLOAT32, `pass_through=0`, queried format 3. It does no normalization, point selection, transposition, rotation or scaling.

| Index | Label | Exact query name | Allowed query shapes | Elements |
|---:|---|---|---|---:|
| 0 | input_echo | diagnostic_input_echo | `[1,146,2]` | 292 |
| 1 | centered | model_1/tf.math.subtract/Sub | `[1,146,2]`, `[1,146,2,1]` | 292 |
| 2 | scale | model_1/tf.math.reduce_mean_1/Mean | `[1,1,1]`, `[1,1,1,1]` | 1 |
| 3 | normalized | model_1/tf.math.truediv_1/truediv | `[1,146,2]`, `[1,146,2,1]` | 292 |
| 4 | final52 | StatefulPartitionedCall:0 | `[52]` | 52 |

All output attributes must be FLOAT16 and exactly `2 * elements` bytes. Canonical ONNX shapes and the specifically observed compiler trailing-singleton shapes are the only allowances. Dense output formats 0, 1 and 3 are recorded without reordering; packed format 2 and unknown values are rejected. Query index, name, rank, all dimensions and element count must agree. All five outputs request `want_float=1`, with returned bytes exactly `4 * elements`.

One call acquires all five buffers. All metadata is validated before any copy; all 929 values are copied before releasing the original five-output array exactly once. A partially failed get with any returned buffer also attempts release. No measurement is written after a failed API. NaNs remain in raw diagnostic files and are counted; copying them is not a numerical pass. Every init/query/set/run/get/release/destroy API has a flushed begin event and an end event with return code. A begin without its end identifies the last entered call, not a returned error code.

Five warmups precede all 92 measurements. Each output has its own `output-<label>.f32`, with exactly 92 contiguous C-order records. Each tensor row records phase, case, index, returned index, byte count and float offset. The checker requires the complete ordered event stream, all release/destroy returns, raw/log extrema agreement, before/after model/input/helper/vendor-runtime hashes, and final hashes for all five output files **and report.jsonl**. External `native-exit.json` must independently contain `{"exit_code":0}`: a report close failure can return 16 after a success footer was written.

Echo is compared bitwise against input FLOAT32 → queried FLOAT16 → output FLOAT16 → returned FLOAT32, with no permutation. Centered/scale/normalized report finite counts, absolute/relative error, ratios, real72/synthetic20 groups and every case; they have no invented acceptance threshold. final52 is compared separately against diagnostic ONNX and original TFLite using unchanged maxAbs ≤ 0.01, global MAE ≤ 0.002, finite values, and [0,1] ± 1e-5 (including warmups). All 52 channel statistics are retained.

`status=diagnostic_complete` means complete evidence, not model acceptance. `application_eligible` and `performance_evidence` are always false. CLI exit 0 means echo and both final52 preliminary gates match; exit 1 means complete evidence but an echo/final numerical failure; exit 2 means structural/evidence failure. API timings exclude the flushed begin event but have diagnostic logging between calls, and cannot be used as application performance results.

## Frozen host artifacts

Bundle: `E:/tripo/device-lab/app/build/blendshape-five-tap-bundle-v1`; manifest SHA256 `6680bfe12bc78b4ecbeb7a4e4ce5cb23d2664391189c05318fb312e657dd68fb`.

| Artifact | Bytes | SHA256 |
|---|---:|---|
| front-taps.rknn | 6,147,026 | `2b0bc99450f094cfea259765ca5657905d4e1498f39824faab1012422372cf70` |
| rknn_tap_probe | 19,272 | `fa587b8a2e26b94666abae99ccd955d579ed7ad5e73f8b33070c8e70632f1e46` |
| inputs.f32 | 107,456 | `36aea412c6765dfbe7222db47253e2e3c734ef6366a2578c0ede282fb4cc13fc` |
| compiled-contract.json | 6,397 | `36e3bc4d67fd123215bc12bba6971128eca9e33705517a1ce873e4f588870eb7` |

The manifest additionally records all five reference hashes, original TFLite reference hash, per-case input/reference hashes, source ONNX/prepare/compile/compiler-log hashes and the existing vendor runtime pin `01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de`. SDK 1.3.0 and driver 0.7.2 are checked. Preparation checks all sources before creating a bundle; bundles and evidence files are never overwritten.

Reproduce from `E:/tripo/device-lab`, choosing a new output directory if these already exist:

```powershell
$python = 'C:/Users/lNessie/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$source = 'E:/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis'
powershell -ExecutionPolicy Bypass -File scripts/build_tap_probe.ps1 -OutputDirectory app/build/tap-device-helper-v1
& $python scripts/blendshape_tap_check.py prepare `
  --source "$source/front-taps-v1" `
  --compilation "$source/front-taps-v1-compile/diagnostic.json" `
  --compiler-log "$source/front-taps-v1-compile-host.log" `
  --validation E:/tripo/output/mirror-program/20261003/blendshape-broadcast/gamma-conv-mulpow-v1/numerical-validation.json `
  --model "$source/front-taps-v1-compile/front-taps.rknn" `
  --helper app/build/tap-device-helper-v1/rknn_tap_probe `
  --bundle app/build/blendshape-five-tap-bundle-v1
```

## Device operator sequence

Only the root/device operator executes these commands, using the already selected device and a fresh directory. Check every ADB transport exit code; abort if push or before-hash capture fails. Compare the before hashes against the manifest/runtime pin before running. References stay on the host.

```powershell
$bundle = 'E:/tripo/device-lab/app/build/blendshape-five-tap-bundle-v1'
$deviceDir = '/data/local/tmp/mirror-five-tap-v1'
$evidence = 'E:/tripo/output/mirror-program/20261003/blendshape-five-tap-device-v1'
New-Item -ItemType Directory -Path $evidence -ErrorAction Stop
adb shell am force-stop com.mirror.bench
adb shell mkdir $deviceDir
adb push "$bundle/front-taps.rknn" "$deviceDir/front-taps.rknn"
adb push "$bundle/inputs.f32" "$deviceDir/inputs.f32"
adb push "$bundle/rknn_tap_probe" "$deviceDir/rknn_tap_probe"
adb shell chmod 755 "$deviceDir/rknn_tap_probe"
adb shell sha256sum "$deviceDir/front-taps.rknn" "$deviceDir/inputs.f32" "$deviceDir/rknn_tap_probe" /vendor/lib64/librknnrt.so |
  Set-Content -Encoding utf8 "$evidence/before.sha256"
# Independently compare these four hashes before the next command.
adb shell "$deviceDir/rknn_tap_probe" /vendor/lib64/librknnrt.so "$deviceDir/front-taps.rknn" "$deviceDir/inputs.f32" "$deviceDir/report.jsonl" $deviceDir 2>&1 |
  Set-Content -Encoding utf8 "$evidence/console.txt"
$nativeExit = $LASTEXITCODE
@{exit_code=$nativeExit} | ConvertTo-Json | Set-Content -Encoding utf8 "$evidence/native-exit.json"
adb shell sha256sum "$deviceDir/front-taps.rknn" "$deviceDir/inputs.f32" "$deviceDir/rknn_tap_probe" /vendor/lib64/librknnrt.so "$deviceDir/report.jsonl" "$deviceDir/output-input_echo.f32" "$deviceDir/output-centered.f32" "$deviceDir/output-scale.f32" "$deviceDir/output-normalized.f32" "$deviceDir/output-final52.f32" |
  Set-Content -Encoding utf8 "$evidence/after.sha256"
adb pull "$deviceDir/report.jsonl" "$evidence/report.jsonl"
foreach ($label in @('input_echo','centered','scale','normalized','final52')) {
  adb pull "$deviceDir/output-$label.f32" "$evidence/output-$label.f32"
  if ($LASTEXITCODE) { throw "Failed to pull $label" }
}
& $python scripts/blendshape_tap_check.py check --bundle $bundle --evidence $evidence `
  --baseline-output E:/tripo/output/mirror-program/20261003/blendshape-device-v4/outputs.f32 `
  --output "$evidence/comparison.json"
```

The optional baseline is pinned to v4 actual output SHA256 `66b35d13c997d989811bc3b9cd5477c4b2334939349673ea4fa51f45df3bcd11`. It is a metrics-only comparison to identify whether the diagnostic graph changes the final-output failure pattern. It never substitutes for either final52 reference gate.

If native aborts, retain console, report, partial outputs, process exit and PID-specific logcat. The checker rejects incomplete files/calls; do not infer successful later calls from earlier `api_begin` lines. If execution stalls, the operator may terminate only this isolated process and preserve evidence. No system/property/driver workaround is authorized here. Restore the existing application separately after evidence collection.

## Host validation and limits

`cmd /c tests\run_tap_contract_tests.cmd` passes 1,912 contract checks and 1,999 actual iteration-helper checks. Each header first failed its missing-header RED test, then passed. Tests exercise exact name/rank/dims/type/size, output order, all API failure boundaries, partial acquisition release, copy-before-release, atomic no-partial-copy on bad metadata, and preserved nonfinite diagnostic values.

`python tests/test_blendshape_tap_check.py` passes 20 tests including structure-mutation subcases. It began with missing-module RED, then GREEN. Tests include new-hash but invalid evidence, missing begin/end/release/finish, stale report timing log, native nonzero despite success footer, per-output hash/truncation, per-case offsets, probability/NaN and bitwise echo behavior, baseline pin, preparation provenance failures, semantic corruption with recomputed hashes, and rejection before partial bundle creation. Native build uses NDK r25c with `-O2 -Wall -Wextra -Werror`, source/header hashes in `app/build/tap-device-helper-v1/build.json`.

These tests establish evidence handling and resource-flow behavior, not RKNN correctness. Actual runtime output formats, conversion, intermediate values and final52 accuracy still require the isolated device run.
