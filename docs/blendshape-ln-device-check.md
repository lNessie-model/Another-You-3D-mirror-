# First-LayerNorm fixed device probe

2026-10-03. This is the third explicitly fixed five-output diagnostic profile. It does not change the app, its models, vendor runtime, driver, or existing front/stem bundles. The frozen observation graph and its numerical preparation are described in [the first-LN model record](blendshape-first-ln-diagnostic.md). Device execution and numerical accuracy remain unverified at this handoff.

`MIRROR_TAP_PROFILE=2` / build `-Profile ln` selects this profile. Default `0` remains front; `1` remains stem. Other profile IDs fail compilation. Only the fixed contract branch and build/checker whitelist changed; `rknn_tap_probe.c` and `rknn_tap_iteration.h` are unchanged. No arbitrary JSON tensor list, alias, transpose or dimension reinterpretation is accepted.

Input remains original C-order 292 FLOAT32 values, adjacent x/y pixels, with no normalization or reindexing by the helper. Queried input must be the exact original name, FLOAT16 `[1,146,2]`, 584 bytes, format 3. Feed uses FLOAT32, queried format, pass-through 0. Five outputs request want-float 1 and must have exact query names, ranks, shapes, elements and FLOAT16 sizes. Output format whitelist 0/1/3 is a guard, not a claim about this model's yet-unobserved Android attributes.

| Index | Label / query name | Exact query shape | Elements | Returned F32 bytes | File bytes, 92 cases |
|---:|---|---|---:|---:|---:|
| 0 | token_embedding / `diagnostic_ln_token` | `[1,64,1,97]` | 6,208 | 24,832 | 2,284,544 |
| 1 | mean / `diagnostic_ln_mean` | `[1,1,1,97]` | 97 | 388 | 35,696 |
| 2 | inv_std / `diagnostic_ln_invstd` | `[1,1,1,97]` | 97 | 388 | 35,696 |
| 3 | affine / `diagnostic_ln_affine` | `[1,1,97,64]` | 6,208 | 24,832 | 2,284,544 |
| 4 | final52 / `StatefulPartitionedCall:0` | `[52]` | 52 | 208 | 19,136 |

The native destination is bounded at 12,662 floats / 50,648 bytes. Its five in-memory offsets are 0, 6,208, 6,305, 6,402 and 12,610 floats. Every tensor is prevalidated before any destination copy; all copies precede release. The existing partial-get and complete-get release paths are shared unchanged. Each separate output file uses offset `case_index * that_tensor_elements`, not a shared combined-file offset. The five files total 4,659,616 bytes for 92 measurements. Five warmups also check all API return codes and output metadata, but do not write measurement data.

Intermediate mean/inv_std/affine values are not probabilities. They receive error/extrema/nonfinite diagnostics, without an invented common acceptance threshold. There is no input echo output; reports say `echo.available=false`. final52 retains both original-TFLite and diagnostic-ONNX preliminary gates: maxAbs ≤ 0.01, global MAE ≤ 0.002, finite values and range [0,1] ± 1e-5; warmup final52 finite/range is also required. `application_eligible=false` and `performance_evidence=false` remain unconditional. Changing observation outputs can change RKNN optimization, so this graph's results cannot be directly assigned to the original full model's operators.

## Frozen artifacts

Bundle: `E:/tripo/device-lab/app/build/blendshape-ln-tap-bundle-v1`.

| Artifact | Bytes | SHA256 |
|---|---:|---|
| manifest.json | — | `2b7f8eba2204c2f5b45f9b93800c61a798c373948f57cfe6769f0648ad4dc533` |
| ln-taps.rknn | 6,137,325 | `2e52062dfe3bf2c374dd594931c670a5bcd3c61495386b28c7f9c165c47725d3` |
| rknn_tap_probe | 19,448 | `9ffa6166f424a76466d3d46c4af38a6fd8330824f1d04e4c93b6834af0db3f12` |
| inputs.f32 | 107,456 | `36aea412c6765dfbe7222db47253e2e3c734ef6366a2578c0ede282fb4cc13fc` |
| compiled-contract.json | 6,980 | `e3038c3e41dc4672c9ea0cf822ff66f5de11bd3d24d4ee817a4743d37f8947fc` |

Build metadata is `app/build/ln-device-helper-v1/build.json`. It records profile `ln`, NDK r25c, target Android30/aarch64, exact helper hash/size and source hashes. The manifest includes all five reference files, original TFLite52, and nine source/provenance hashes. Preparation rechecks actual 92-case original-TFLite input/output hashes, source ONNX/compile/model binding, exact output layout, helper metadata, and the unchanged original FP32 `1e-5` equivalence gate. Vendor runtime pin remains `01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de`.

Reproduce from `E:/tripo/device-lab`, choosing new directories because existing binaries/bundles refuse overwrite:

```powershell
$python = 'C:/Users/lNessie/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$diagnosticRoot = 'E:/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis'
& ./scripts/build_tap_probe.ps1 -Profile ln -OutputDirectory app/build/ln-device-helper-v1
& $python scripts/blendshape_tap_check.py prepare `
  --source "$diagnosticRoot/ln-taps-v1" `
  --compilation "$diagnosticRoot/ln-taps-v1-compile/diagnostic.json" `
  --compiler-log "$diagnosticRoot/ln-taps-v1-compile-host.log" `
  --validation E:/tripo/output/mirror-program/20261003/blendshape-broadcast/gamma-conv-mulpow-v1/numerical-validation.json `
  --model "$diagnosticRoot/ln-taps-v1-compile/ln-taps.rknn" `
  --helper app/build/ln-device-helper-v1/rknn_tap_probe `
  --bundle app/build/blendshape-ln-tap-bundle-v1
```

## Device operator handoff

Only the root/device operator executes the probe, after current application tests finish and the app stops. Use a new isolated directory such as `/data/local/tmp/mirror-ln-tap-v1`. Push only `ln-taps.rknn`, `inputs.f32` and `rknn_tap_probe`; verify those three and `/vendor/lib64/librknnrt.so` before starting. Exact native invocation:

```text
/data/local/tmp/mirror-ln-tap-v1/rknn_tap_probe /vendor/lib64/librknnrt.so /data/local/tmp/mirror-ln-tap-v1/ln-taps.rknn /data/local/tmp/mirror-ln-tap-v1/inputs.f32 /data/local/tmp/mirror-ln-tap-v1/report.jsonl /data/local/tmp/mirror-ln-tap-v1
```

Record actual process exit independently in `native-exit.json` (`exit_code`), not just the native footer; a late report close error can return nonzero after a success footer. Preserve stdout/stderr and complete `before.sha256` / `after.sha256`. After hashes must include unchanged helper/model/input/vendor, `report.jsonl`, and all five binary outputs:

- `output-token_embedding.f32`
- `output-mean.f32`
- `output-inv_std.f32`
- `output-affine.f32`
- `output-final52.f32`

Pull outputs as binary and run the checker offline. Example evidence path below is a proposed destination, not a record of device execution:

```powershell
& $python scripts/blendshape_tap_check.py check `
  --bundle E:/tripo/device-lab/app/build/blendshape-ln-tap-bundle-v1 `
  --evidence E:/tripo/output/mirror-program/20261003/blendshape-ln-tap-device-v1 `
  --baseline-output E:/tripo/output/mirror-program/20261003/blendshape-device-v4/outputs.f32 `
  --output E:/tripo/output/mirror-program/20261003/blendshape-ln-tap-device-v1/comparison.json
```

Checker exit 0 means complete transport evidence and both final52 preliminary gates pass; exit 1 means complete evidence but numerical failure; exit 2 means contract/evidence failure. None authorizes application integration. The optional original failed-v4 comparison is hash-pinned and diagnostic only. Same-graph simulator and prior observation graphs require separately hash-bound offline comparisons.

## Verification performed on the host

The new C contract first failed because profile2 was unsupported, then passed 25,411 assertions. The unchanged API iteration suite compiled for the LN capacity passes 25,465 assertions. Old front contract/iteration remain 1,912/1,999; stem remains 14,148/14,213. Run `cmd /c tests\run_tap_contract_tests.cmd`.

Eight new tests in `tests/test_blendshape_ln_check.py` first failed on the missing LN profile, then passed. They cover full 12,662-value contracts, exact token/affine layouts despite equal element counts, mean/inv_std identity, final-case offsets/bytes for all five files, extra singleton/unknown profile rejection, profile-specific helper provenance, external process exit and log hash, nonfinite reporting, unchanged final52 gates and no false echo claim. The existing 28 front/stem tests also pass. Run each Python test file directly.

The updated checker additionally re-read both saved front device runs and both saved stem runs; each complete result dictionary equals its previous `comparison.json`, including timing, hashes and the optional v4 comparison. Every old bundle file still matches its original manifest. Front manifest remains `6680bfe12bc78b4ecbeb7a4e4ce5cb23d2664391189c05318fb312e657dd68fb`; stem remains `7e2c623c1f2817e343c8c7a6051e574e98ca4baba84b01da5264d56731e5bea1`.
