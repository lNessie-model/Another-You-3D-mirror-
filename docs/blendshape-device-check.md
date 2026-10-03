# Isolated RKNN expression model check

This route does not install an APK, replace any application model, load a new driver, or alter system properties. The parent/device operator first finishes the application workload and stops `com.mirror.bench`; concurrent rendering/face inference would invalidate isolated timings. The host scripts never invoke ADB. The native executable loads the existing `/vendor/lib64/librknnrt.so` explicitly.

Candidate: `E:/tripo/output/mirror-program/20261003/blendshape-broadcast/gamma-conv-div-neg-v1-compile/face_blendshapes.rknn`, SHA256 `1c4053dada0f60bb6201f4738b005d52fa3c4e12971fd8be4dbe47eb956980e3`, 6,131,230 bytes. Compilation includes CPU fallback partitions, so wall time is mixed CPU/NPU execution, not NPU kernel time or utilization. Compilation and FP32 TFLite/ONNX equivalence do not establish RKNN accuracy or runtime compatibility.

## Frozen accuracy and input contract

Before the first device run, the preliminary FP16 engineering gate is fixed at maximum absolute error <= 0.01, global mean absolute error <= 0.002, all 4,784 outputs finite, and every output within [0,1] with tolerance 1e-5. The output report separates 72 recorded-landmark inputs from 20 deterministic synthetic inputs and reports all 52 named channels and all 92 fixtures. This gate does not establish final facial expression quality or real-person acceptance.

Each input is 292 little-endian float32 values in original C-order `[1,146,2]`: adjacent pixel x/y coordinates, already selected using the official 146 indices and scaled by width 640/height 480. Do not select, normalize, rescale, mirror, or transpose again. Centering and mean-radius normalization inside the expression model must remain intact. Input attributes must strip singleton dimensions to exactly `[146,2]`; output must strip to `[52]`. A different shape or unknown input format fails explicitly. Input is `RKNN_TENSOR_FLOAT32`, `pass_through=0`, using the queried NCHW/NHWC format; output requests `want_float=1`.

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
