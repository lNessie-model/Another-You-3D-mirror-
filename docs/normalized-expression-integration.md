# Experimental expression backend integration

The opt-in backend replaces only the CPU 52-expression network. The same detector, 478-point NPU mesh, OneEuro smoothing, canonical face geometry, control mapping, avatar, UI and interlace renderer remain in the path. CPU FP32 input preparation feeds the fixed normalized-input RKNN suffix; its compiled graph still contains CPU fallback. This is a candidate for reducing CPU postprocessing cost, not a mechanism for moving OpenGL rendering to the NPU.

## Selection and lifecycle

`RuntimeExpressionBackend.read` accepts only a Boolean `test_npu_blendshapes` extra in a debuggable build. Missing extras keep the existing CPU backend; release builds reject an explicitly supplied experiment extra. The flag is not saved to preferences. `MirrorActivity.InputOptions` captures it once and reports it as `debug_npu_blendshapes`; `NpuFacePipeline` constructs the selected postprocessor once per input attempt and recreates it on the existing lost-face smoothing reset. Its original constructor overloads retain the CPU selection.

The new path uses `FaceGeometryPostGraph` → `NormalizedBlendshapeInput` → `RknnExpression`. It requires smoothed 478 XYZ points in the actual 640×480 or 480×640 image orientation; there is no additional rotation, reflection, clipping or epsilon. The official 146-point order and mean-radius normalization are preserved. A complete result has 52 finite probabilities in [0,1], 16 validated pose values, and 1,434 validated landmark floats. A failure does not produce neutral coefficients or silently switch backends.

The existing single outstanding post job and ordered callback/backpressure path is retained. Camera-thread image inference may overlap the previous post job, now including an expression RKNN invocation; actual multi-context correctness and contention must therefore be tested. `summary()` drains that job before reading model counters. Closing/recreating a postprocessor follows the existing serial ownership path, while each JNI model synchronizes operations and closing across thread handoff.

## Fixed assets

| Asset | Bytes | SHA256 |
| --- | ---: | --- |
| `face_blendshapes_normalized_suffix.rknn` | 1,209,569 | `17b0773a404521a8cf9624fa8147467d9fb3fdb309a641f244244b2910d4831c` |
| `expression-validation/raw-inputs.f32` | 107,456 | `36aea412c6765dfbe7222db47253e2e3c734ef6366a2578c0ede282fb4cc13fc` |
| `expression-validation/normalized-inputs.f32` | 107,456 | `ff9ffb0b905d58966fb2c0a19f3406c8a4a7250e285837d2c7f9376c36357c8b` |
| `expression-validation/original-tflite52.f32` | 19,136 | `5945bbecedd7ee827a75091b81e12a84a88ca8f05807b034a52f528c9b917602` |

The new model is copied to its own private filename only when selected. Existing image models and the original CPU expression model are preserved. The separate JNI library is `libmirror_rknn_expression.so`. Its Java owner verifies the model bytes and the existing APK's RKNN runtime entry before passing the exact APK zip-library path to native code; no second runtime library is extracted or installed. See the [JNI contract](rknn-expression-jni.md) and [isolated device result](blendshape-normalized-suffix-device-validation.md).

## Device checks and performance sequence

`ExpressionAppCheckActivity` is available only in a debuggable build. It first verifies the three original fixture hashes, runs five warmups followed by 92 measurements through the Android Java front and actual application JNI, and records each normalized input and 52 output. The original front gate is maximum absolute error ≤1e-5; the fixed FP16 device output gates remain maximum ≤0.01 and overall MAE ≤0.002. JNI additionally rejects nonfinite or out-of-[0,1] results. The references, order and thresholds are not changed.

It then compares 24 identical decoded video frames through the smoothed CPU reference and the candidate's ordered asynchronous post path, including black frames 8 and 17 and reacquisition 9 and 18. Presence and callback counts must match; landmark and pose maximum errors must be ≤1e-5; 52-expression maximum and MAE must be ≤0.01/0.002. It records both complete arrays for independent host checking. This validates application integration on the recorded sequence, not arbitrary live-face accuracy or camera/display performance. Cancelling the activity aborts validation. The report is `files/expression-app-check.json`; force-stop the app and remove only this previous report before starting a check, then save each run to a new host directory. A fresh UUID and monotonic elapsed start/completion timestamps identify the invocation; completion is recorded after the native and pipeline resources have closed.

Only after the integration gates pass should the same APK compare CPU → NPU → CPU at 16×400×640, using real USB capture, fixed recorded-face inference, the same avatar, UI and persistent-FBO setting. Use `run_runtime_check.py --npu-blendshapes` for the candidate; omission selects CPU. The flag must survive HOME/resume but never save preferences. Actual presented FPS comes from SurfaceFlinger, separately from camera rate, inference rate and callback counters. NPU utilization and expression API wall-clock time include contention and CPU fallback; neither proves GPU rendering acceleration.

The required next checks are Android integration, short joint A/B/A, NPU lifecycle recovery, and sustained joint performance. At the time this integration was authored these checks had not yet run. The completed v18 endurance collector used its previously frozen source and APK; source changes here did not alter that run.
