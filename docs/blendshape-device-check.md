# Isolated RKNN expression model check

This route does not install an APK, replace any application model, load a new driver, or alter system properties. The parent/device operator first finishes the application workload and stops `com.mirror.bench`; concurrent rendering/face inference would invalidate isolated timings. The host scripts never invoke ADB. The native executable loads the existing `/vendor/lib64/librknnrt.so` explicitly.

Candidate: `E:/tripo/output/mirror-program/20261003/blendshape-broadcast/gamma-conv-div-neg-v1-compile/face_blendshapes.rknn`, SHA256 `1c4053dada0f60bb6201f4738b005d52fa3c4e12971fd8be4dbe47eb956980e3`, 6,131,230 bytes. Compilation includes CPU fallback partitions, so wall time is mixed CPU/NPU execution, not NPU kernel time or utilization. Compilation and FP32 TFLite/ONNX equivalence do not establish RKNN accuracy or runtime compatibility.

Current evidence summary: the original candidate failed device initialization; replacing nine Sqrt nodes with equivalent Pow nodes passed the independent original-TFLite host gate and enabled initialization. The first Pow run stopped at the conservative input-format guard; a narrowly updated helper accepted the observed coordinate format, but that run logged `Div: unsupported type!` and aborted before any complete iteration or 52-value output. No candidate has yet passed device numerical validation, and none has valid isolated inference timing or full-application performance evidence. The application expression model remains unchanged.

## Frozen accuracy and input contract

Before the first device run, the preliminary FP16 engineering gate is fixed at maximum absolute error <= 0.01, global mean absolute error <= 0.002, all 4,784 outputs finite, and every output within [0,1] with tolerance 1e-5. The output report separates 72 recorded-landmark inputs from 20 deterministic synthetic inputs and reports all 52 named channels and all 92 fixtures. This gate does not establish final facial expression quality or real-person acceptance.

Each input is 292 little-endian float32 values in original C-order `[1,146,2]`: adjacent pixel x/y coordinates, already selected using the official 146 indices and scaled by width 640/height 480. Do not select, normalize, rescale, mirror, or transpose again. Centering and mean-radius normalization inside the expression model must remain intact. For NCHW/NHWC, input attributes must strip singleton dimensions to exactly `[146,2]`; output must strip to `[52]`. Starting with helper-v2, the public `RKNN_TENSOR_UNDEFINED=3` format is also accepted only for the observed exact `n_dims=3, dims=[1,146,2], n_elems=292` coordinate tensor. Any other dimensions with format 3, packed NC1HWC2, or unknown format fail explicitly. Input is `RKNN_TENSOR_FLOAT32`, `pass_through=0`, using the queried format unchanged; output requests `want_float=1`. The public header defines format 3 at `native/vendor/rknn/rknn_api.h:185` and input conversion semantics at lines 325–334; this permits requesting conversion but does not prove that the device performs it correctly.

Reference archive `reference-fixtures.npz` SHA256 `45dfa3e2781ede9599758874346e5532f8e250be2f640a6293b2f3e2bc97a6e8`; validation JSON SHA256 `7f06f7168c47bbfad2933d1e61300c6bd03f5987ed4f7b14a21fa6811b35f8da`. Reference is the original TFLite, SHA256 `4f36dded049db18d76048567439b2a7f58f1daabc00d78bfe8f3ad396a2d2082`. Preparation verifies archive, individual input/reference hashes, compilation-to-validation linkage and model artifact hash before creating the bundle. Bundles are never overwritten.

## Build and prepared bundle

Only `scripts/build_blendshape_probe.ps1` builds the standalone executable. Do not use `scripts/build_native.ps1`, which also writes application JNI libraries.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build_blendshape_probe.ps1 -OutputDirectory app/build/blendshape-device-helper-v1
```

Existing compiled ARM64 Android API 30 helper: `app/build/blendshape-device-helper-v1/rknn_blendshape_probe`, SHA256 `4f551207818586cb05c7ab3f0ac1e862e72127568075290242effef7deb65c48`, 15,240 bytes. It uses NDK r25c, `-O2 -Wall -Wextra -Werror`, and the existing checked-in RKNN header. Build provenance is in the adjacent `build.json`.

The ready bundle is `E:/tripo/device-lab/app/build/blendshape-device-bundle-v1`:

| File | Bytes | SHA256 |
|---|---:|---|
| `face_blendshapes.rknn` | 6,131,230 | `1c4053dada0f60bb6201f4738b005d52fa3c4e12971fd8be4dbe47eb956980e3` |
| `inputs.f32` | 107,456 | `36aea412c6765dfbe7222db47253e2e3c734ef6366a2578c0ede282fb4cc13fc` |
| `references.f32` | 19,136 | `5945bbecedd7ee827a75091b81e12a84a88ca8f05807b034a52f528c9b917602` |
| `rknn_blendshape_probe` | 15,240 | `4f551207818586cb05c7ab3f0ac1e862e72127568075290242effef7deb65c48` |

`manifest.json` records these hashes, source evidence, individual fixture identity and the predeclared gate. Reference floats remain on the host. Reproduce preparation with the `prepare` subcommand:

```powershell
$python = 'C:\Users\lNessie\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$source = 'E:\tripo\output\mirror-program\20261003\blendshape-broadcast'
& $python scripts/blendshape_device_check.py prepare `
  --fixtures "$source/gamma-conv-div-neg-v1/reference-fixtures.npz" `
  --validation "$source/gamma-conv-div-neg-v1/numerical-validation.json" `
  --model "$source/gamma-conv-div-neg-v1-compile/face_blendshapes.rknn" `
  --compilation "$source/gamma-conv-div-neg-v1-compile/compile-report.json" `
  --helper app/build/blendshape-device-helper-v1/rknn_blendshape_probe `
  --bundle app/build/blendshape-device-bundle-v1
```

## Device operator commands

These are instructions for the sole device operator, not actions taken by the host checker. Select the already-authorized connected device using the project's existing ADB selection. Use a fresh isolated directory and fresh host evidence directory per run. Do not run an application benchmark concurrently.

```powershell
$deviceDir = '/data/local/tmp/mirror-blendshape-v1'
$bundle = 'E:/tripo/device-lab/app/build/blendshape-device-bundle-v1'
$evidence = 'E:/tripo/device-lab/app/build/blendshape-device-run-v1'
New-Item -ItemType Directory -Path $evidence -ErrorAction Stop
adb shell am force-stop com.mirror.bench
adb shell mkdir $deviceDir
adb push "$bundle/face_blendshapes.rknn" "$deviceDir/face_blendshapes.rknn"
adb push "$bundle/inputs.f32" "$deviceDir/inputs.f32"
adb push "$bundle/rknn_blendshape_probe" "$deviceDir/rknn_blendshape_probe"
adb shell chmod 755 "$deviceDir/rknn_blendshape_probe"
adb shell sha256sum "$deviceDir/face_blendshapes.rknn" "$deviceDir/inputs.f32" "$deviceDir/rknn_blendshape_probe" /vendor/lib64/librknnrt.so |
  Set-Content -Encoding utf8 "$evidence/before.sha256"
adb shell "$deviceDir/rknn_blendshape_probe" /vendor/lib64/librknnrt.so "$deviceDir/face_blendshapes.rknn" "$deviceDir/inputs.f32" 92 1 "$deviceDir/report.jsonl" "$deviceDir/outputs.f32" 2>&1 |
  Set-Content -Encoding utf8 "$evidence/console.txt"
$probeExit = $LASTEXITCODE
$probeExit | Set-Content "$evidence/exit-code.txt"
adb shell sha256sum "$deviceDir/face_blendshapes.rknn" "$deviceDir/inputs.f32" "$deviceDir/rknn_blendshape_probe" /vendor/lib64/librknnrt.so "$deviceDir/outputs.f32" "$deviceDir/report.jsonl" |
  Set-Content -Encoding utf8 "$evidence/after.sha256"
adb pull "$deviceDir/report.jsonl" "$evidence/report.jsonl"
adb pull "$deviceDir/outputs.f32" "$evidence/outputs.f32"
& $python scripts/blendshape_device_check.py check --bundle $bundle `
  --log "$evidence/report.jsonl" --actual "$evidence/outputs.f32" `
  --before "$evidence/before.sha256" --after "$evidence/after.sha256" `
  --output "$evidence/comparison.json"
```

Check every ADB exit code, not only the probe result. The actual native process exit code must independently be zero; the host numerical checker does not receive that external process status. In particular, report close/flush can fail after the helper has written its finish row, causing process exit 16 despite a success finish row. The helper does not override permissions or change SELinux; a loader/CPU-operator/driver failure is evidence to retain. Its report/output opens are exclusive and refuse replacement. If it stalls, the operator must terminate only this isolated process and preserve partial reports; no incomplete run can pass. Use ADB pull for binary outputs, never PowerShell text redirection. The expected existing runtime SHA256 is `01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de`; SDK 1.3.0 / driver 0.7.2 are checked and all attributes retained.

## Completion and timing interpretation

The helper executes five warmups then the entire 92-case sequence. Every `inputs_set`, `run`, `outputs_get`, `outputs_release`, initialization/query and destroy return code is recorded. Warmups must also succeed. Successfully acquired output is copied before release; buffers are released even when the acquired result is invalid. Each measurement carries fixture index, cycle index and byte-stream offset. The checker requires exactly five warmups plus 92 times cycle-count complete measurements, successful finish/destroy, matching before/after input/model/helper/runtime hashes, and matching pulled-output and native-report hashes. Truncation, ordering/shape mismatch, missing release, changed files or failed calls are structural errors, never a numerical pass.

Timings separately cover each API and their total including the small output copy and release. File and JSON writes are outside the measured interval, but still affect surrounding scheduling/cache state. Warmup and measurement percentiles are separate. A repeated isolated timing run may increase the `cycles` argument only after the one-cycle precision result is accepted; use a fresh directory and unchanged bundle, collecting hashes again. It still does not establish end-to-end inference FPS, concurrent render FPS, utilization or thermal endurance. The complete application must be measured separately before choosing any production model.

Host regression: `python tests/test_blendshape_device_check.py`, 21 tests passed. The initial 13 tests were RED with the absent checker, then GREEN; added checks cover separate MAE/max gates, tolerated error, warmup range, destroy failure, unknown format, repeated cycles and production channel names. Independent review found a report-hash binding gap: a timing-only log replacement first passed incorrectly in a new failing regression, then was correctly rejected after requiring the device report hash. Host tests validate evidence handling, not hardware correctness.

## First real device result: initialization failed

The device operator ran the frozen candidate with this helper. Evidence directory: `E:/tripo/output/mirror-program/20261003/blendshape-device-v1`. `native-exit.json` records process exit 134; `logcat-pid9118.txt` records `ERROR: unsupport Sqrt op in current` followed by SIGABRT in the isolated probe. The JSONL contains only the start event, with no successful initialization or tensor attributes; `outputs.f32` is zero bytes. The before/after hash chain matches the prepared model/helper/input and existing runtime, and `after-with-report.sha256` also binds the pulled report. The checker produces `status:error`, `passed:false`, `Missing start/finish; incomplete run` in `comparison.json`.

This establishes a runtime CPU-fallback/operator compatibility failure for this candidate. It supplies no RKNN coefficient accuracy result and no valid inference timing. Do not replace the application expression model on this evidence. An equivalent compatible-operator candidate must preserve the original TFLite reference and pass the same frozen 92-case numerical gate before any application integration or performance claim.

## Second candidate: Pow replacement, host gate repeated

An independent bundle was prepared at `E:/tripo/device-lab/app/build/blendshape-device-bundle-v2`; v1 remains unchanged. Its model is `gamma-conv-div-neg-pow-v1-compile/face_blendshapes.rknn`, 6,133,662 bytes, SHA256 `33c72f2f6d6de12ebc48fe575445106fbb30bc12692a0b28b6b12323fbf4765f`. Input floats, original reference floats and compiled helper are byte-for-byte identical to v1. Manifest SHA256 is `22bd9c0a6b23045e918a931b27e663ad67556461f9e3db02a412aef89d57abd7`; numerical-validation SHA256 is `9126795b8dda648a5697c048e239f896730222f2bad52377ee3dbe5d83a3efbb`; compilation-report SHA256 is `4aa34c9200d3b2a9c07e5f11ae5af7c7da7087d5880c10342a0c249ebc75129f`. Compilation launcher reports terminal exit 0; config/load/build/export each returned 0.

Independent host audit `app/build/audit-blendshape-pow-v2.py` loads both actual ONNX graphs and verifies that only nine Sqrt nodes became Pow nodes with float32 exponent 0.5, adding nine constants. Existing initializers, remaining nodes, node ordering and graph input/output contracts are unchanged. It reruns ONNX Runtime against the frozen original TFLite references, checking all 92 individual reference/input hashes again. Result: all 4,784 outputs finite; maximum absolute error `2.592802047729492e-6`, global MAE `1.1960694902877725e-7`, below the unchanged FP32 `1e-5` gate. Recorded72 max/MAE are `2.592802047729492e-6` / `1.142793150442795e-7`; synthetic20 max/MAE are `1.5497207641601562e-6` / `1.387864313729691e-7`. Full result is `app/build/blendshape-pow-v2-independent-audit.json`.

For the device run, reuse the operator commands above with bundle/device/evidence directories changed from v1 to v2, preserving five warmups, all 92 cases, all before/after hashes, report hash and the external process exit-code check. The host checker and predefined FP16 engineering thresholds are unchanged. This host pass does not establish that the old device runtime supports Pow or that its FP16 outputs are accurate; both still require a completed isolated run.

## Observed coordinate format and helper-v2 / bundle-v3

The second real run is in `E:/tripo/output/mirror-program/20261003/blendshape-device-v2`. Pow candidate initialization returned 0 in 198.672562 ms, SDK 1.3.0 / driver 0.7.2. The public input is exactly rank 3 `[1,146,2]`, 292 elements, model FLOAT16/584 bytes, format UNDEFINED (3); output is rank 1 `[52]`, model FLOAT16/104 bytes, format 3. The original helper's format guard rejected it with exit 12, destroy returned 0, and no inference/output occurred. This was an intentional unsupported-contract stop, not a failed inference or precision result.

`native/rknn_blendshape_contract.h` now contains the shared shape check and the narrow format-3 exception described above. Host C tests exercise the actual header: observed format passes, `[1,2,146]`, `[146,2]`, extra singleton dimensions under format 3, altered element counts, invalid extents and formats 2/4/99/-1 reject. The new positive test first failed against the original guard, then passed after the change; 21 C checks pass using the existing MSVC compiler via `tests/run_blendshape_contract_tests.cmd`. The Python evidence suite likewise first rejected the valid observed record, then passed; 23 tests pass. An independent peer reran both suites and found no P1/P2. These tests establish guard behavior, not device-side FLOAT32-to-FLOAT16 conversion accuracy.

Rebuilt standalone helper is `app/build/blendshape-device-helper-v2/rknn_blendshape_probe`, 15,536 bytes, SHA256 `1c0e50c366cd08c52ad3c32beeeb64d09ede542db069c75c2ddfa0a1ebe7ffb3`. Build metadata now hashes both the C source and shared contract header. `app/build/blendshape-device-bundle-v3` contains this helper with the unchanged Pow model and unchanged original inputs/references; manifest SHA256 `ac6e039ff1057806afb7a77c43d4cb76cc745080139d57e9cc06c4151c831743`. Use fresh v3 device/evidence directories and the same 92-case command/checker/thresholds. Existing v1/v2 evidence and helper binaries remain intact.

## Third real run: accepted input contract, no completed iteration

Evidence is in `E:/tripo/output/mirror-program/20261003/blendshape-device-v3`. `native-exit.json` records exit 134. The JSONL records successful initialization (198.453520 ms), SDK 1.3.0 / driver 0.7.2, one input and one output, the same `[1,146,2]` / `[52]` FLOAT16 model attributes, then the feed contract FLOAT32, format 3, pass-through 0, want-float 1. There are **no iteration events and no finish event**; `outputs.f32` is zero bytes.

The saved PID-specific log `logcat-pid10032.txt` records `Div: unsupported type!` at 11:03:02.497, followed by signal 6 (SIGABRT) in the isolated helper. Its SHA256 is `4308faddd8a6e53b49d1e4abb1b3913f011ce16d0d5d90d605f4e4adf6765798`. This is the new observed operator/type diagnostic, distinct from the first run's unsupported Sqrt. `comparison.json` records `status:error`, `passed:false`, `Missing start/finish; incomplete run` with the unchanged thresholds; SHA256 `f2d197f482d4dfee2b4620961029f64282e961473b97303b0df837cfb532dd9a`.

Independent offline audit verifies before/after model, input, helper and vendor-runtime hashes against bundle-v3, plus the pulled report and output hashes against the device's final hash list. Report SHA256 is `13ec2b9301e4c1a0add5dd480ed77bd2176aedf9cb52ebaf8df51f43e906b5fd`; empty-output SHA256 is `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.

The report emits each iteration only after its API calls complete, and it has no per-API begin trace. Therefore this evidence establishes an abort after successful initialization and feed-contract acceptance, before the first complete warmup iteration, but cannot attribute the abort specifically to `rknn_inputs_set`, `rknn_run`, `rknn_outputs_get` or `rknn_outputs_release`. Initialization time is not inference latency. No coefficients exist to compare against the frozen thresholds; numerical accuracy, valid inference timing, and device input conversion correctness remain unproven.

## MulPow candidate: independent host audit and bundle-v4

The next candidate replaces each of the nine division nodes with multiplication by the original denominator raised to -1. Independent audit `app/build/audit-blendshape-mulpow-v4.py` compares the actual previous Pow ONNX with `gamma-conv-mulpow-v1/gamma-conv-mulpow.onnx`: all nine original numerator/denominator identities are retained; the only additions are nine Pow nodes and nine float32 -1 constants. All 89 existing initializers, remaining nodes and graph input/output contracts remain byte-for-byte unchanged. The candidate graph contains no Div, Sqrt, Reciprocal or Neg nodes. This is a model arithmetic rewrite, not a change to device input dtype or order.

Independent ONNX Runtime replay against the identical frozen original-TFLite archive passes all 92×52 outputs, all finite: max absolute error `1.9669532775878906e-6`, global MAE `1.269932822921237e-7`. Recorded72 max/MAE are `1.9669532775878906e-6` / `1.2175237003202273e-7`; synthetic20 are `1.430511474609375e-6` / `1.4586056642848723e-7`. The unchanged FP32 gate is `1e-5`. Evidence: `app/build/blendshape-mulpow-v4-independent-audit.json`. All per-case reference/input hashes, compilation linkage, all four compile-stage return codes and terminal child exit 0 were independently checked.

Ready bundle `app/build/blendshape-device-bundle-v4` has manifest SHA256 `cda847a6e516e1f19501e4f8f8faceeb4f7bb5678f1b9aad0db4fe4f4d084c61`. Model is 6,135,582 bytes, SHA256 `f378b6d75b84ba81a52a566d85b05e9e4840c85c0493cc70d5129088ecc21c40`; ONNX SHA256 is `fbfed69de13a93e5f0ce3c48843261651c353e66ca30ded2a9f08a33429ce287`; numerical-validation SHA256 is `8e9b99d024f723eb5c3f0e7736431b40b882da955800cd6bc242ea3863793746`. It retains helper-v2 and exactly the same inputs/references, feed format 3 with FLOAT32/pass-through 0, five warmups, 92 cases, hash checks and predefined FP16 gate. Neither successful compilation nor this host replay establishes a working RKNN model; a complete device run is still required.

Subsequent device evidence supersedes the preceding preparation status: v4 completed all 92 cases with native exit 0, but failed the unchanged accuracy gate (max absolute error 0.9722383022, global MAE 0.2395760047). See [independent full v4 accuracy audit](blendshape-device-accuracy-v4.md). This candidate cannot enter the application; its execution time is not valid performance evidence.
