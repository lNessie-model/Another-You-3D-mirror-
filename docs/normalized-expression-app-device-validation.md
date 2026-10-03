# Android expression integration: two completed device checks

On 2026-10-03, both fixed checks on RK3566 Android 11 passed through the actual v19 Android Java/JNI integration. An independent host audit rebuilt the errors from every recorded Float32 array, checked the native contract, and verified the command and artifact chain. This establishes numerical integration on the fixed fixtures and recorded frame sequence. It does not establish live-camera quality, presented FPS, power savings, or sustained joint performance.

## Identity and fresh invocation

The package is `com.mirror.bench`, serial `6L32552009566714`. Both runs used APK SHA256 `ad8f7e219da380cf46e3dfea55b1c8238024aeab8c7cd8c28664e4a77a35448a` (39,866,952 bytes). Run 1 replaced v18 using `install -r -t`; run 2 reused the same v19 APK. Each run force-stopped the package, removed only `files/expression-app-check.json`, verified that the old report was absent, then launched `.ExpressionAppCheckActivity`. The runner treated the missing-file text returned with exit 0 as absence, not a valid report. The final report UUID and elapsed times had to fit the captured device-uptime window.

| Run | Invocation UUID | Report SHA256 | Start / completion elapsed ns | Device-uptime bracket, s |
| --- | --- | --- | --- | --- |
| 1 | `d194bf18-0d0d-48af-859f-8bbf1f2730bb` | `e6adbf956d8a3f15cfa618ba626d0d51c8e2b8ecbee38f516be9f4826e357e38` | `186397978370454` / `186402731019515` | 186397.4–186403.88 |
| 2 | `52bd25cf-6f3d-4052-aad5-a7ddd22a7c70` | `f77613fe1a94b5e6e8ed3715d28a1b227c12feb1e7bd16bccc137c20e35097fa` | `186438235918290` / `186442777822455` | 186437.64–186444.07 |

The 19 and 18 recorded commands, respectively, had exit code 0 and ordered host start/end times. Both runner results were `passed`, and each ended with force-stop, preferences read, package-path discovery and installed-APK hash verification. The UUID identifies a validation worker invocation. The completion timestamp is emitted after the source's try-with-resource close path; the report is not a per-call native destroy trace.

Before installation, after installation, and after validation, all six 973-byte preferences snapshots had SHA256 `43bd19b1d31c03648a81fb7e18ecd778c47f4d34b3879e3041f2e0f815056b37`. The audit checked all 19 key/type/value entries: schema 4, 16 views, `400x640`, 17 active FPS, the saved optical calibration, and the unchanged camera settings/revision. No test option was saved. The two runs did not clear application preferences or perform a settings restore; this evidence does not demonstrate arbitrary downgrade compatibility.

## Original numerical gates

Five warmups precede the same 92 ordered cases: 72 real and 20 synthetic. The three fixture files shipped in the APK retain their original hashes and order. Their raw input and original TensorFlow reference hashes also match the earlier per-case numerical validation. The gates remain front maximum error ≤1e-5, 52-expression maximum error ≤0.01 and overall MAE ≤0.002, with finite values and probabilities in [0,1].

| Recomputed comparison, each run | Float32 values | Maximum absolute error | MAE | Unequal Float32 bit patterns |
| --- | ---: | ---: | ---: | ---: |
| Android normalization vs fixed normalized reference | 26,864 | 0 | 0 | 0 |
| Fixed suffix output vs original TensorFlow 52 | 4,784 | 0.004244357347488403 | 0.0012582556981626887 | 4,784 |
| Real 72 subset vs original TensorFlow | 3,744 | 0.004244357347488403 | 0.0013939023497326613 | 3,744 |
| Synthetic 20 subset vs original TensorFlow | 1,040 | 0.0041792988777160645 | 0.0007699277525107872 | 1,040 |

The worst fixed value is case 40, channel 5. Every case's stored max/MAE was independently recomputed; no value exceeded 0.01. All fixed Android suffix outputs were byte-identical to the previously validated isolated suffix device output (`c620f173d30c418e49e96208bdecd7227a03996bebc63e00b4da409e1e5bb9d6`). Passing the tolerance gate does not mean equality with the original FP32 reference.

## Complete application pipeline sequence

The source feeds the same 24 decoded recorded frames to the CPU reference and ordered asynchronous candidate pipeline. Frames 8 and 17 are explicitly black; both paths report no face there, then reacquire at 9 and 18. All other 22 frames return complete 478-point XYZ landmarks, a 16-value pose and 52 expressions. The unchanged OneEuro settings are min cutoff 0.05, beta 80 and derivative cutoff 1, with reset after lost face.

| Recomputed candidate vs CPU, each run | Values | Maximum absolute error | MAE | Unequal Float32 bit patterns |
| --- | ---: | ---: | ---: | ---: |
| 22 × 478 × XYZ landmarks | 31,548 | 0 | 0 | 0 |
| 22 × 16 pose values | 352 | 0 | 0 | 0 |
| 22 × 52 expressions | 1,144 | 0.004462569952011108 | 0.0014092493696295564 | 1,144 |

The original landmark/pose maximum gate is 1e-5, and the pipeline expression gates are 0.01/0.002. Every complete expression value is finite and in [0,1]. Both outer pipelines report 3 detector calls, 24 mesh calls and 22 post calls. CPU has zero pending post jobs; the candidate has at most one, exercising the existing ordered asynchronous boundary while image inference may overlap the preceding post job.

The candidate's final `expression_post` snapshot contains **6** successes, corresponding to frames 18–23 of the final postprocessor context. Each black frame recreates the outer postprocessor, so these counters describe only the last context, while `NpuFacePipeline.post_calls` describes all **22** complete frames. The final snapshot was taken before close (`closed=false`); successful report completion follows resource close. These observations do not imply 16 missing post calls or provide a native API return-code trace.

All eight packed array families were byte-identical across the two fresh processes: normalized inputs, fixed 52 outputs, and both CPU/candidate landmark, pose and expression arrays. This comparison excludes wall-clock timings, timestamps and counters. The recorded sequence is a limited reacquisition and integration check; it does not test arbitrary live faces, camera mounting, HOME recovery, GL rendering, or long-term contention.

## Native and artifact chain

Both reports contain the same whole native contract: runtime `1.3.0 (9b36d4d74@2022-05-04T20:16:47)`, driver `0.7.2`, one input `[1,146,2]` with 292 elements/584 bytes, and one output `[52]` with 52 elements/104 bytes. Native tensor type is F16 (`type=1`), format `3`, quantization type `2`, zero point `0`, scale `1`; width/height strides and pass-through are `0`, and `size_with_stride` equals size. The feed is 1,168 bytes of F32 (`type=0`), format `3`, `pass_through=0`, with Float32 output requested. Names and every reported attribute are checked exactly. `poisoned=false` in both reports.

The model is 1,209,569 bytes, SHA256 `17b0773a404521a8cf9624fa8147467d9fb3fdb309a641f244244b2910d4831c`. The existing APK runtime is 5,518,336 bytes, SHA256 `01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de`. The candidate JNI is 14,040 bytes, SHA256 `cfc42c4abc78e0be521a20e5f7521e3138cadaf73a9dfa44f9189b0a485861c4`. Android successfully used the actual packaged Java/JNI path. The audit does not infer unrecorded API calls from these summaries.

The audit verifies all 146 frozen build-input files against the saved source ZIP, all 24 asset/library APK entries against the build manifest, and the original 19 asset/library entries byte-for-byte against v18. Only the new model, three fixed validation assets and candidate JNI were added. CPU remains the default backend; the candidate is a debug opt-in and does not change rendering algorithms or saved settings.

## Reproduction and scope

Terminal raw directories are `E:/tripo/output/mirror-program/20261003/expression-app-check-device-v1` and `...-v2`. The host-only script `app/build/audit_expression_app_check_device.py` produces `E:/tripo/output/mirror-program/20261003/expression-app-check-device-audit-v1.json`, covering 165 pinned paths, all cases and complete frames. To recompute without writing or using a device:

```python
import json, runpy
from pathlib import Path
ns = runpy.run_path(str(Path("app/build/audit_expression_app_check_device.py").resolve()), run_name="audit_recheck")
saved = json.loads(Path("E:/tripo/output/mirror-program/20261003/expression-app-check-device-audit-v1.json").read_text(encoding="utf-8"))
assert ns["report"] == saved
```

This result permits a separate same-APK CPU → candidate → CPU joint performance experiment. It does not replace that experiment or apply the completed v18 CPU endurance result to v19's NPU candidate. No joint-performance, power, rendering-speed or broad accuracy claim is made here.
