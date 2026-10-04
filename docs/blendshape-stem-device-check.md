# Fixed stem-v2 device diagnostic route

This is the second and only additional fixed profile of the isolated five-output probe. It observes normalization, stem input, stem projection, token embedding and final52. There is no runtime tensor selector or arbitrary shape contract. Application code, the original front helper/bundle and all precision thresholds remain unchanged.

Use **stem-v2 short-name artifacts only**. The earlier stem-v1 simulator failed on compiler-truncated output names and is retained as failure evidence, not a deployable profile. The v2 graph adds two named observation Identity nodes while preserving original computational nodes and constants. Its 92-case ONNX final52 remains byte-identical to the original candidate and passes the original TFLite FP32 `1e-5` gate. See [the model-generation evidence](blendshape-stem-diagnostic.md). That result is distinct from Android numerical accuracy.

## Fixed native and host contracts

`MIRROR_TAP_PROFILE=0` is the unchanged default front profile. `=1` selects only stem-v2; other values fail compilation. The shared API iteration implementation (`rknn_tap_iteration.h`) is byte-for-byte unchanged. The fixed helper still loads only the explicitly supplied existing runtime, then performs one input set, run, one five-output get, complete validation/copy and one five-output release. Its per-case destination is 7,036 floats (28,144 bytes); five outputs total 2,589,248 bytes across 92 cases. All buffers are copied before release, with no partial destination writes when any output metadata is invalid.

Input remains 292 FLOAT32 values, `[1,146,2]`, adjacent pixel x/y in original C order, no reprocessing. Query input must be FLOAT16/584 bytes, format 3, exact original input name. Feed remains FLOAT32, queried format 3, pass-through 0; all five outputs request want-float 1. No runtime alias, prefix matching or inferred transposition is allowed.

| Index | Label / exact query name | Allowed query shapes | Elements | F16 query bytes | Returned F32 bytes | File bytes, 92 cases |
|---:|---|---|---:|---:|---:|---:|
| 0 | normalized / `model_1/tf.math.truediv_1/truediv` | `[1,146,2]`, `[1,146,2,1]` | 292 | 584 | 1,168 | 107,456 |
| 1 | stem_input / `diagnostic_stem_input` | `[1,146,1,2]` | 292 | 584 | 1,168 | 107,456 |
| 2 | stem_projection / `diagnostic_stem_projection` | `[1,96,1,2]` | 192 | 384 | 768 | 70,656 |
| 3 | token_embedding / `model_1/GhumMarkerPoserMlpMixerGeneral/MLPMixer/AddExtraTokens/concat` | `[1,64,1,97]` | 6,208 | 12,416 | 24,832 | 2,284,544 |
| 4 | final52 / `StatefulPartitionedCall:0` | `[52]` | 52 | 104 | 208 | 19,136 |

Every output type must be FLOAT16; query formats are limited to 0, 1 or 3 with unchanged order. The only optional terminal singleton is the specifically observed normalized output. Stem input `[1,146,1,2]` must not be silently treated as `[1,146,2,1]`. Output record offset is exactly `fixture_index * elements`, separately in each `output-<label>.f32` file. Shape, rank, index, name, query size, returned size and all API return codes are enforced independently.

The checker chooses between two hard-coded contracts by manifest kind; it cannot accept arbitrary output definitions from a JSON file. Preparation also checks matching `build.json` with `profile=stem`, binary hash and byte count. Mixing a front helper, obsolete v1 aliases, an unknown profile, or a different tensor layout is rejected. All model/compile/source/reference/input hashes and per-case original-TFLite hashes remain required.

There is **no input echo output in this profile**. Reports explicitly set `echo.available=false` and make no echo-correctness claim. The first four outputs have numerical metrics only. final52 must separately satisfy the original-TFLite and diagnostic-ONNX preliminary gates: maxAbs ≤ 0.01, global MAE ≤ 0.002, finite values and [0,1] ± 1e-5, including warmup finite/range checks. Complete execution is not acceptance: `application_eligible=false` and `performance_evidence=false` remain unconditional. CLI exit 0 means both final52 preliminary gates match; exit 1 means complete evidence but numerical failure; exit 2 means evidence/contract failure.

The same flushed API begin/end trace, exact 5-warmup + 92-measurement sequence, external native-exit requirement, bounded fixed output files, partial-get release and before/after hash checks from [the front profile](blendshape-five-tap-diagnostic.md) apply. Final device hashes must cover the five output files **and report.jsonl**, as well as unchanged helper/model/input/vendor runtime. Never derive native success from a footer alone.

## Frozen isolated bundle

Bundle: `E:/tripo/device-lab/app/build/blendshape-stem-tap-bundle-v2`.

| Artifact | Bytes | SHA256 |
|---|---:|---|
| manifest.json | — | `7e2c623c1f2817e343c8c7a6051e574e98ca4baba84b01da5264d56731e5bea1` |
| stem-taps.rknn | 6,142,134 | `d7103efdca08d02e91e731930b8c94899b071356c8c2907d32aed97922218e18` |
| rknn_tap_probe | 19,336 | `81ca6b6d96b289039921a35989dbdc5976c68f062db24f04ae52ddd4e748a4eb` |
| inputs.f32 | 107,456 | `36aea412c6765dfbe7222db47253e2e3c734ef6366a2578c0ede282fb4cc13fc` |
| compiled-contract.json | 7,128 | `93d1250043e75b2a0285a4c3cfd1e097dd009c5595856f340df3c47dec9fdd7f` |

The model contract binds preparation report `a980796300e41687b1306342e88a5c0264861b4fb2b660a332b2d5e2804567be`, compilation report `0811dc278c33167a990c9327c8f80f75267cfceb205c26570c45f4f03a89d9e2`, and compiler host log `16b00e552f336c5689bc347f4e28a76deb80bf985b3897f018a5b89f33595661`. References and helper-build provenance are in the bundle manifest. Vendor runtime pin remains `01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de`.

Reproduce from `E:/tripo/device-lab`; existing helper and bundle directories refuse overwrites:

```powershell
$python = 'C:/Users/lNessie/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$source = 'E:/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis'
powershell -ExecutionPolicy Bypass -File scripts/build_tap_probe.ps1 -Profile stem -OutputDirectory app/build/stem-device-helper-v2
& $python scripts/blendshape_tap_check.py prepare `
  --source "$source/stem-taps-v2" `
  --compilation "$source/stem-taps-v2-compile/diagnostic.json" `
  --compiler-log "$source/stem-taps-v2-compile-host.log" `
  --validation E:/tripo/output/mirror-program/20261003/blendshape-broadcast/gamma-conv-mulpow-v1/numerical-validation.json `
  --model "$source/stem-taps-v2-compile/stem-taps.rknn" `
  --helper app/build/stem-device-helper-v2/rknn_tap_probe `
  --bundle app/build/blendshape-stem-tap-bundle-v2
```

## Device operator handoff

Only the root/device operator runs ADB, after finishing application performance tests and stopping the app. Use a new isolated device directory, for example `/data/local/tmp/mirror-stem-tap-v2`, and a new host evidence directory. Push only `stem-taps.rknn`, `inputs.f32` and `rknn_tap_probe`. Check every transport result and verify all four before hashes against the frozen bundle/runtime pin before execution.

The native invocation is exactly:

```text
/data/local/tmp/mirror-stem-tap-v2/rknn_tap_probe /vendor/lib64/librknnrt.so /data/local/tmp/mirror-stem-tap-v2/stem-taps.rknn /data/local/tmp/mirror-stem-tap-v2/inputs.f32 /data/local/tmp/mirror-stem-tap-v2/report.jsonl /data/local/tmp/mirror-stem-tap-v2
```

Capture the actual shell/native process exit in `native-exit.json`, stdout/stderr, and the complete before/after sha256sum lists. Pull raw files as binary. Required after-hash/output names are `output-normalized.f32`, `output-stem_input.f32`, `output-stem_projection.f32`, `output-token_embedding.f32`, `output-final52.f32`, and `report.jsonl`. The checker also expects `before.sha256`, `after.sha256`, and `native-exit.json` in that evidence directory.

```powershell
& $python scripts/blendshape_tap_check.py check `
  --bundle E:/tripo/device-lab/app/build/blendshape-stem-tap-bundle-v2 `
  --evidence E:/tripo/output/mirror-program/20261003/blendshape-stem-tap-device-v2 `
  --baseline-output E:/tripo/output/mirror-program/20261003/blendshape-device-v4/outputs.f32 `
  --output E:/tripo/output/mirror-program/20261003/blendshape-stem-tap-device-v2/comparison.json
```

The optional baseline remains the hash-pinned original failed-v4 final52. It is a diagnostic difference, not a pass gate. Other comparisons, including front device outputs and simulator tensors, should retain their own exact hashes. Changed graph outputs can change RKNN optimization; even stem-v2 simulator final52 changed 37 values in one case relative to front simulator. ONNX equivalence does not establish RKNN output equivalence or identify the original full model's first faulty operator.

## Host regression and device boundary

The new fixed stem C contract first failed with the old 929-float profile, then passed 14,148 checks, including 7,036-value copy canaries and invalid-last-output transactional rejection. The unchanged iteration implementation passes 14,213 checks when compiled for the stem capacity; front remains 1,912 contract and 1,999 iteration checks. Run `cmd /c tests\run_tap_contract_tests.cmd`.

`python tests/test_blendshape_stem_check.py` runs 28 tests: the original 20 front tests plus eight stem tests. The eight initially failed with the missing fixed-profile API, then passed. Added cases cover short-name/v1-alias rejection, rank/axis order, 6,208-element offsets and byte lengths, cross-profile mixing, arbitrary third profiles, final-only probability gates, and helper-build provenance. The native helper builds with NDK r25c and `-O2 -Wall -Wextra -Werror`.

As a compatibility check, the updated checker independently re-read both saved front device runs: its entire result object, including metrics/timings/hashes and optional v4 comparison, exactly equals each previously saved report. The old front helper SHA `fa587b8a2e26b94666abae99ccd955d579ed7ad5e73f8b33070c8e70632f1e46` and bundle-manifest SHA `6680bfe12bc78b4ecbeb7a4e4ce5cb23d2664391189c05318fb312e657dd68fb` remain unchanged.

Host tests and simulator completion do not prove this profile's device conversion, layout, numerical accuracy or performance. Those conclusions require a completed isolated device run; no application model selection follows from this handoff.
