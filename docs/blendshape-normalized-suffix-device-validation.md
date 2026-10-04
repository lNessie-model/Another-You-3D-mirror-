# Normalized expression suffix: two-process device validation

Both isolated RK3566 runs pass the unchanged original TFLite numerical screen after an independently reviewed, exact correction to the metadata contract. All 92×52 outputs are finite and in range; maximum absolute error is **0.004244357347488403**, mean absolute error **0.0012582556981626887**, below the original `.01` / `.002` limits. The two output files are byte-identical. This validates the fixed offline FP32 front plus mixed CPU/NPU suffix on this dataset. It does not yet validate Java/Android preprocessing, app integration, joint rendering performance, or resource savings. `application_eligible=false` and `cpu_front_deployed=false` remain explicit.

## Original rejection and revised contract

The root operator ran `run_normalized_suffix.py --run 1`, then `--run 2` after the prior collector's final force-stop completed. Both native processes returned 0. The original v1 checker rejected each report because it expected `qnt_type=0`; actual input and output both report **FLOAT16/type1, qnt_type2, zp0, scale1**. This also appears in earlier logs from the same vendor runtime. The SDK header separately defines data type and quantization type; no runtime output transformation was justified or applied.

The old wrapper, package, freeze and the two original `comparison.json` files remain unchanged and continue to represent rejection. A separate [observed-attribute v2 contract](blendshape-normalized-suffix-attribute-profile-v2.md) strictly accepts the full observed dictionaries, including exact names, rank/dimensions, size, dtype, layout, quantization, stride, query return code and numeric field types. No fallback, shape alias, rescaling, threshold change, reference replacement, model rewrite or device rerun was used. Independent review found no P1/P2; new 10, old wrapper 8 and original core 23 tests passed before the new wrapper read actual evidence.

The new reports are `comparison-observed-attrs-v2.json`, each created separately after review. Both CLI checks returned 0 and `diagnostic_complete / passed=true`. This is a numerical-screen pass, not permission to deploy a new default backend. The first attempt to save the new host reports was blocked by filesystem sandbox permissions; the authorized host-only retry saved new files. It did not execute ADB or repeat inference.

## Fixed model, input and device

The numerical path is the [original eight FP32 normalization operations](blendshape-normalized-boundary-validation.md), computed offline, followed by the [unchanged suffix with eight explicit negative-mean copies](blendshape-normalized-suffix-validation.md). The 292 input floats are normalized XY, not raw pixels. Original 72 real rows (36 paired NPU/reference observations) and 20 synthetic rows retain their case order and original TFLite52 references.

| Item | Exact contract |
| --- | --- |
| Device serial | `6L32552009566714` |
| Runtime / driver | `1.3.0 (9b36d4d74@2022-05-04T20:16:47)` / `0.7.2` |
| Input | `model_1/tf.math.truediv_1/truediv`, `[1,146,2]`, 292 elements, FLOAT16 query storage 584 bytes |
| Output | `StatefulPartitionedCall:0`, `[52]`, FLOAT16 query storage 104 bytes |
| Both queried tensors | `fmt=3`, `qnt_type=2`, `zp=0`, `scale=1`, `w_stride=0`, size_with_stride equals size |
| Native feed | FLOAT32, queried fmt3, pass_through0; output want_float1 |
| Iterations | 5 warmups + 92 single measurements, no reordered/repeated cases |

The dedicated native helper changes only two explanatory strings from the old single-output helper. Its API, float32 input submission, copy-before-release, cleanup, offset and timing logic remain unchanged. The RKNN graph remains mixed CPU/NPU; no vendor/runtime/driver change was made. The main application was force-stopped for the isolated probe. APK and preferences were checked before/after each run and remained unchanged.

## Independent complete audit

`app/build/audit_normalized_suffix_device_v2.py` independently recomputes both complete revised checker dictionaries, 89 SHA/size paths, source/freeze/bundle identity, all normalized/reference row mappings, raw before/after hashes, exact native command and exit, output sizes, measured extrema/offsets, APK and preferences. Each log has 105 events and 97 iteration records, all 388 per-iteration API return codes zero, plus successful init, four query results and destroy. There are 19,136 output bytes, exactly 4,784 float32 values, per process. The native event schema reports return-code sets per iteration; API ordering and resource ownership are additionally bound to the unchanged native source, not inferred from timestamps.

| Against original TF52 | Max absolute error | Mean absolute error | Values over .01 |
| --- | ---: | ---: | ---: |
| All 92 | 0.004244357347488403 | 0.0012582556981626887 | 0 / 4,784 |
| Real 72 | 0.004244357347488403 | 0.0013939023497326613 | 0 / 3,744 |
| Synthetic 20 | 0.0041792988777160645 | 0.0007699277525107872 | 0 / 1,040 |

Actual range is `[0.003021240234375, 0.984375]`; both runs pass the original finite and probability-range checks, including warmup log extrema. Worst case is row40 `npu-20000`, channel5 `browOuterUpRight`: device `0.390869140625`, original TF `0.3866247832775116`. No output is claimed FP32/TFLite bit-identical or within `1e-5`.

Against the same suffix graph's actual simulator output, device max error is `0.0030210018157958984`, MAE `0.0011800632229616809`. Thus simulator and device are not identical, even though both separately meet the original numerical screen. Across the two device processes all 19,136 bytes match. This establishes repeatability for these two ordered runs, not arbitrary inputs or runtime schedules.

## Timing scope and next gate

| Native API wall time, 92 measurements | Run 1 mean ms | Run 2 mean ms |
| --- | ---: | ---: |
| inputs_set | 0.0337161 | 0.0323085 |
| run | 8.4031807 | 7.3502131 |
| outputs_get | 0.0296707 | 0.0286721 |
| outputs_release | 0.0005073 | 0.0005136 |
| Total measured API | 8.4686472 | 7.4129310 |

These isolated wall times include CPU fallback and IO conversion. They exclude the FP32 CPU front, face detector/landmark inference, smoothing, pose calculation, camera acquisition, rendering and UI. Run order is not a controlled performance A/B. No NPU-only time, utilization, full face FPS or screen FPS is derived from these numbers.

The next gate is an opt-in integration with the exact CPU front and normalized-input suffix contract, independent JNI error/cleanup tests, Android end-to-end original-reference checks, and same-APK joint resource/FPS measurement. The 478 landmarks, 52 expression channel order and pose must remain available. This document authorizes no model or runtime replacement by itself.

## Evidence pins and reproduction

Raw directories: `E:/tripo/output/mirror-program/20261003/blendshape-normalized-suffix-device-v1` and `-v2`. Both original rejected comparisons have SHA `7e0eecdcd4e28ca2883d3a2fc91c85f86f5a12eaa4157c134cbd698ba1adc77d`.

| Artifact | SHA256 |
| --- | --- |
| Original package v1 manifest | `9e18e8c299347bd8d0b9cfcd5dde97080b9cc33eb03b5439ec5bb3068afa00df` |
| Revised package v2 manifest | `baa799c773487967acabaa10f707edc3a0de26b495f9ef07db3387a6c12ee9e7` |
| Revised package freeze | `fc57f206ac7a5fee3b399b31626bd1ccd32635332a9660af324d94078296a8f8` |
| Model | `17b0773a404521a8cf9624fa8147467d9fb3fdb309a641f244244b2910d4831c` |
| Normalized FP32 input92 | `ff9ffb0b905d58966fb2c0a19f3406c8a4a7250e285837d2c7f9376c36357c8b` |
| Original TF52 references | `5945bbecedd7ee827a75091b81e12a84a88ca8f05807b034a52f528c9b917602` |
| Dedicated helper | `42d00cee0e8d5bf2a2dd4827da71837fa7bf7ad0883dd233875866f49b339d71` |
| Vendor runtime | `01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de` |
| Unchanged installed APK | `c4cf831de43a80e85d59fe0382e14ef439b803219140c66b2580786d1cc04d84` |
| Unchanged preferences | `43bd19b1d31c03648a81fb7e18ecd778c47f4d34b3879e3041f2e0f815056b37` |
| Both device outputs | `c620f173d30c418e49e96208bdecd7227a03996bebc63e00b4da409e1e5bb9d6` |
| Revised comparison run1 | `81e18989fa6a43b0bc23ec4722597f5314b451e708441b18520171f89c16b433` |
| Revised comparison run2 | `2d911f7f17c2f6d407bbaf552b98f53432c3e1f7d56b8c7e141dcd73a5df1cfc` |
| Independent audit JSON | `8f833a441a132ca8b69b92be84102c3fc864d44b2cd4688216da47ee0e468638` |
| Audit source | `8a38c4736f91843f9a5e3b793165525d70a1f7b60e09946a2389e2498033f1df` |

Audit JSON: `E:/tripo/output/mirror-program/20261003/blendshape-normalized-suffix-device-audit-v2.json`; it includes per-case/channel metrics, full source paths and run logs. The audit source creates that file with exclusive mode; it refuses to overwrite. For read-only reproduction, execute its prefix before the final `output=ROOT/...` write block and compare the resulting `report` dictionary against the saved JSON. The bounded archive `app/build/normalized-suffix-device-audit-freeze-v2` preserves source, document and full result snapshots. No reproduction step requires ADB or model compilation.
