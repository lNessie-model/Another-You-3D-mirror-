# CPU expression-input normalization: host validation

`NormalizedBlendshapeInput` is a new, unconnected pure Java implementation of the expression model's input preparation. The fixed 92-case host suite passes the original FP32 comparison gate (`atol=1e-5`, `rtol=0`). The application does not call this class yet. This report establishes numerical and ownership behavior on the stated host runtimes; it does not establish Android execution time, device output, or an integrated NPU expression pipeline.

## API and ownership

Source: `app/src/main/java/com/mirror/bench/NormalizedBlendshapeInput.java`.

```java
NormalizedBlendshapeInput.Vector pixelCoordinates(float[] smoothedXyz, int width, int height)
NormalizedBlendshapeInput.Vector normalizePixels(float[] pixels)
```

Both static methods return a newly owned immutable `Vector`: `size()` is 292, `get(index)` reads one value, and `toArray()` returns a copy. Caller arrays are never modified or retained. Callers must not concurrently modify an input while the method reads it.

`pixelCoordinates` requires exactly 478 adjacent XYZ triples. All 1,434 values must be finite, including Z and points outside the selected subset. It selects the official 146 unique landmark indices in their original order, ignores Z after validation, and multiplies each normalized X by width and Y by height. The two supported dimensions are 640×480 and 480×640. The latter means the caller has already provided landmarks in that oriented image's coordinate system; this method does not rotate, reflect, smooth, or clip landmarks. Finite coordinates outside `[0,1]` are allowed. Unknown dimensions, wrong shapes, nonfinite inputs, and arithmetic overflow are rejected.

`normalizePixels` accepts exactly 292 adjacent pixel X/Y values (`[1,146,2]`, C order). It uses the eight operations in the pinned FP32 front graph:

1. ReduceMean across point axis 1, keeping dimensions: independent X and Y centroid.
2. Subtract that centroid from every point.
3. Multiply each centered coordinate by itself.
4. ReduceSum over coordinate axis 2, keeping dimensions.
5. Raise each squared radius to the literal power `0.5`.
6. ReduceMean of the 146 Euclidean radii across axis 1, keeping dimensions.
7. Raise the mean radius to the literal power `-1`.
8. Multiply each centered coordinate by that reciprocal.

Each Java arithmetic stage stores `float` results. Reductions traverse point indices 0 through 145. The two powers use `Math.pow` followed by an explicit cast to float. There is no epsilon, RMS normalization, clamp, or alternate point order. Nonfinite intermediate values, zero mean radius, underflow to zero radius, and nonpositive/nonfinite reciprocal throw `IllegalArgumentException`; failure returns no partial vector or neutral expression. An integration caller must handle that failure without passing invalid values to native inference.

## Fixed evidence

Paths below are local evidence, not network downloads. SHA256 values bind the references used by the reproducible script.

| Source | SHA256 |
| --- | --- |
| `E:/tripo/native-tools/mediapipe-source/face_blendshapes_graph.cc` | `a3826ad2738315f1e046b36fd883825980e151db677e53d21ff35eaff64e4bd3` |
| `E:/tripo/output/npu-face-optimization/20261002/quality-final/quality.json` | `1a9dfa35206154e195d928f08d715bdb4d94da9a888f588f02b31d117f92497e` |
| `.../blendshape-broadcast/gamma-conv-mulpow-v1/reference-fixtures.npz` | `45dfa3e2781ede9599758874346e5532f8e250be2f640a6293b2f3e2bc97a6e8` |
| `.../blendshape-broadcast/gamma-conv-mulpow-v1/numerical-validation.json` | `8e9b99d024f723eb5c3f0e7736431b40b882da955800cd6bc242ea3863793746` |
| `.../normalized-boundary-host-v1/front.onnx` | `8c724475e7a630b5703e2d77288b2a9453a40cc62ce01e41ba2f62503c97d3e7` |
| `.../normalized-boundary-host-v1/original_suffix.onnx` | `b08568eedc9f53cb574ec4bd52ee8a78f945e6ba0aba0e7684f91becb8317d5b` |
| `.../normalized-boundary-host-v1/copied_suffix.onnx` | `61c61dda91356545c9397906b6872bcfc52849b796355b6f44cb03de72c6af9b` |
| `.../normalized-boundary-host-v1/normalized_fp32.f32` | `ff9ffb0b905d58966fb2c0a19f3406c8a4a7250e285837d2c7f9376c36357c8b` |
| `.../normalized-boundary-host-v1/original_tflite52.f32` | `5945bbecedd7ee827a75091b81e12a84a88ca8f05807b034a52f528c9b917602` |

The first two abbreviated broadcast paths start at `E:/tripo/output/mirror-program/20261003/`. The abbreviated boundary paths start at `E:/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/`. The test tool carries the complete paths and pins.

The 72 real rows come from 36 recorded time points, each in original NPU-landmark then reference-landmark order. Their selected 640×480 values must match the first 72 raw inputs of the existing 92-case dataset byte for byte. Each of all 92 input and original TFLite output rows is checked against the original per-case hashes. The remaining 20 rows are the unchanged deterministic synthetic cases. Portrait expected values are independently formed from the same full XYZ rows with float32 `[480,640]` scaling; they do not represent new physically rotated-camera recordings.

## Tests and actual result

The initial test-runner execution failed because the production class did not yet exist (RED). Hand fixtures and invalid-input tests then passed (GREEN, 662 checks). The fixture run passed 70,035 checks, including all 146 source-index selections, both image dimensions, immutable result/caller ownership, mean Euclidean radius versus RMS, 3-4-5 radii, null/wrong shapes, nonfinite unused Z/landmarks, overflow, and degenerate radius.

The host used Oracle JDK 17.0.8+9-LTS-211. The class also compiled independently with the installed Android SDK35 `android.jar` on the classpath; that is a compile check, not an Android execution test. Reading that SDK required the host sandbox escalation; the initial restricted read failed before compilation, then the permitted host-only command succeeded. No Gradle or APK build was run.

Actual ORT verification used existing WSL NumPy 1.19.5, ONNX 1.7.0 and ONNX Runtime 1.6.0, one intra-op and inter-op thread. The first verifier attempt had an explicit-import compatibility error (`onnx.numpy_helper` was not implicitly loaded). Adding the explicit import fixed the test tool only; Java arithmetic, models and thresholds were unchanged. The first fixture/output directory is retained. The final `v2` fixtures pin the corrected tool, and both Java and ORT verification were rerun using those fixtures.

| Comparison | Real 72 maximum / MAE | Synthetic 20 maximum / MAE | All 92 maximum / MAE |
| --- | --- | --- | --- |
| Java normalized input versus original FP32 front | 0 / 0 | 0 / 0 | 0 / 0 |
| Java input → unchanged FP32 suffix versus original TFLite52 | `1.9669532776e-6` / `1.2175237003e-7` | `1.4305114746e-6` / `1.4586056643e-7` | `1.9669532776e-6` / `1.2699328229e-7` |

All 26,864 normalized float values match the pinned original front bit for bit on this dataset/runtime. Original and eight-explicit-copy FP32 suffix outputs also match each other byte for byte on the Java inputs. All 4,784 final outputs are finite and within the probability range tolerance. Both the predeclared normalized-input absolute gate and original-TFLite final absolute gate remain `1e-5`, with `rtol=0`; the looser device FP16 screening threshold is not used here. The final outputs are not claimed to be bit-identical to TFLite.

These results do not promise bit-identical reduction or `Math.pow` results across arbitrary inputs or other runtimes. In particular, the Android implementation and proposed float16 RKNN suffix still need their own integration and device gates.

Final evidence root: `E:/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/`.

| Artifact | SHA256 |
| --- | --- |
| `java-normalized-front-v2-fixtures/fixtures.json` | `cdc7375e83b736b737f166406c82bc9596ca0536f4d39e0d16efe789ec0255e6` |
| `java-normalized-front-v2-jdk/java-pixels72.f32` | `49789ff5173747747cd88d92688fee009bd920c1ee8e7a4bff1a2241b778a805` |
| `java-normalized-front-v2-jdk/java-portrait72.f32` | `0498fd4c717d146e3da705357121f9fdd6ea70b99816a399b88e476d8fc13b3b` |
| `java-normalized-front-v2-jdk/java-normalized92.f32` | `ff9ffb0b905d58966fb2c0a19f3406c8a4a7250e285837d2c7f9376c36357c8b` |
| `java-normalized-front-v2-validation.json` | `6b97465658760c583ccf6e6ffe7169245c336e520f1dcceb99fe174fd1757a68` |

## Reproduction

The complete suite requires the existing pinned datasets/models and the stated installed host runtimes. Choose new output names; the fixture creator, Java tensor writer and report writer reject existing output targets. No command below connects to a device or runs RKNN.

```powershell
& 'C:\Users\lNessie\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -B E:\tripo\device-lab\tests\normalized_blendshape_input_reference.py prepare --output E:\tripo\output\mirror-program\20261003\blendshape-numerical-diagnosis\java-normalized-review-fixtures
& E:\tripo\device-lab\tests\run_normalized_blendshape_input_tests.ps1 -FixtureDirectory E:\tripo\output\mirror-program\20261003\blendshape-numerical-diagnosis\java-normalized-review-fixtures -OutputDirectory E:\tripo\output\mirror-program\20261003\blendshape-numerical-diagnosis\java-normalized-review-jdk
wsl.exe -d Ubuntu-22.04 -- /mnt/e/tripo/native-tools/rknn13-venv/bin/python -B /mnt/e/tripo/device-lab/tests/normalized_blendshape_input_reference.py verify --fixtures /mnt/e/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/java-normalized-review-fixtures --java-output /mnt/e/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/java-normalized-review-jdk --report /mnt/e/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/java-normalized-review-validation.json
```

The four code/test files are hash-bound in each fixture manifest and the final report. A changed implementation must generate new fixtures and a new report, retaining the old evidence. The standalone 662-check command is `tests/run_normalized_blendshape_input_tests.ps1` without arguments. The final file hashes, evidence files and scope are recorded in `app/build/normalized-blendshape-input-freeze-v1.json`.

## Integration boundary

The intended input is the same smoothed 478 XYZ stream already used by the CPU expression postprocessor, in the currently selected camera orientation. A future integration must explicitly choose the normalized-input RKNN model/helper contract; these 292 normalized floats must never be fed to a model that still expects raw pixel coordinates. Image dimensions come from the actual oriented frame. The current wrapper supports only the two tested dimensions.

This slice does not change smoothing, landmarks, face presence, expression mapping, camera control, threading, model selection, native libraries or runtime defaults. The new class allocates bounded owned arrays; throughput, allocation cost, device math and any future scratch-buffer design are unmeasured. A invalid/degenerate observation must fail under the application's existing face/error policy, rather than create a fabricated expression. Live integration and the original device precision gate remain separate work.
