# Isolated normalized-expression JNI candidate

This slice adds a separate `libmirror_rknn_expression.so` and `RknnExpression` wrapper. It is not connected to the default application pipeline, copied to production `jniLibs`, installed, or run on the device by these tests. Existing `rknn_face.c`, `RknnModel`, image tensor formats, native libraries, models and build integration are unchanged.

The new wrapper consumes the normalized 292-float output of [the CPU front](normalized-blendshape-input.md), not raw pixel coordinates. It uses the fixed normalized suffix model. Its tensor contract comes from both completed isolated device reports, not the earlier compiler's quantization metadata.

## Java contract

```java
RknnExpression(String modelPath, String applicationApkPath) throws IOException
static ByteBuffer buffer()
synchronized float[] run(ByteBuffer normalizedInput)
synchronized String info()
synchronized void close()
```

These package-level APIs are for `com.mirror.bench`. The constructor's second argument is the running application's `ApplicationInfo.sourceDir`. It reads the model into a bounded owned byte array, verifies size and SHA256, and passes those same bytes to JNI. It verifies the fixed runtime entry inside that APK using `ZipFile`, rejecting duplicate entry names, compressed entries, wrong sizes or SHA256. It does not extract or create another copy of the vendor runtime. The package-private `NativeCalls` seam is for host tests and retains the same actual file/hash validation.

`buffer()` creates a 1,168-byte direct buffer in native byte order. Before `run`, fill it with absolute `putFloat(i*4,value)` calls, or restore position zero and full limit after a relative write. Capacity and limit must be 1,168, position must be zero, and all 292 values must be finite. The caller must not modify the buffer until the call returns. JNI copies it into an aligned, bounded stack snapshot before calling RKNN. There is no transpose, new normalization, image format conversion, manual FP16 conversion, epsilon or clipping in this wrapper.

`run` returns a newly allocated 52-element array after native output release succeeds. Every output must be finite and in `[0,1]`. Even a value just outside that range is rejected rather than clipped; the application has a stricter valid-weight contract than the isolated comparison report's tolerance. Bad caller buffers are rejected before any RKNN call. A native/API/output error locks the Java instance and native context against further inference; the owner must close it and explicitly arrange recovery. No CPU fallback is hidden in this class. There is no reset method.

Construction may throw `IOException` for model/runtime identity or file errors. Invalid call arguments throw `IllegalArgumentException`; native initialization, API, tensor-contract, output or destruction errors throw `IllegalStateException`. Java allocation errors are preserved. `info()` returns JSON containing observed SDK/driver, all queried input/output fields, the feed contract, fixed model/runtime SHA256 values verified by Java, model bytes and native poisoned state. It does not run inference or issue another SDK query. The class must be closed by its owner; it has no finalizer.

All post-construction lifecycle methods use the same Java monitor. Sequential transfer between the camera worker and face-post worker is allowed. `close` cannot destroy a context concurrently with an in-progress `run` or `info`. It clears the Java handle before native destruction, so a thrown destruction error cannot cause the freed native wrapper to be used or destroyed again. This is serialization, not a guarantee that a wedged vendor API can be interrupted; it must not be called from the UI thread while work may be blocked.

## Model, runtime and exact I/O identity

| Item | Required value |
| --- | --- |
| Model bytes / SHA256 | 1,209,569 / `17b0773a404521a8cf9624fa8147467d9fb3fdb309a641f244244b2910d4831c` |
| APK runtime entry | `lib/arm64-v8a/librknnrt.so`, STORED, 5,518,336 bytes |
| Runtime SHA256 | `01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de` |
| SDK / driver | `1.3.0 (9b36d4d74@2022-05-04T20:16:47)` / `0.7.2` |
| Input count / output count | 1 / 1 |
| Input name / shape | `model_1/tf.math.truediv_1/truediv` / exact `[1,146,2]` |
| Output name / shape | `StatefulPartitionedCall:0` / exact `[52]` |
| Queried type / format / quantization | FLOAT16=1 / UNDEFINED=3 / AFFINE_ASYMMETRIC=2 |
| Quantization fields | `zp=0`, `scale=1` |
| Input elements / bytes / bytes-with-stride | 292 / 584 / 584 |
| Output elements / bytes / bytes-with-stride | 52 / 104 / 104 |
| Tensor indices / width stride | 0 / 0 |
| Actual feed | 292 FLOAT32 values, 1,168 bytes, UNDEFINED, `pass_through=0` |
| Requested output | `want_float=1`, exactly 208 returned bytes |

The exact observed fields above are all validated. Unused dimension slots and the SDK struct's additional `fl`, `pass_through`, and `h_stride` fields are not invented acceptance criteria; their observed values are included in `info`. Both counts, both query return codes and the SDK query return code are checked. Unknown SDK versions, layouts or shapes fail explicitly.

The source metadata was read from:

- `E:/tripo/output/mirror-program/20261003/blendshape-normalized-suffix-device-v1/report.jsonl`, SHA256 `510eeb1343be96540e61d68a08aab8612a0278477d3500254f309c7ecd3dac92`.
- `E:/tripo/output/mirror-program/20261003/blendshape-normalized-suffix-device-v2/report.jsonl`, SHA256 `191a094c66f36f7d92894c80ef0cb98b9407a2471c4e301d6fe707662875cf20`.

Those earlier runs used the separate diagnostic executable. Their successful execution does not establish this new JNI library's device operation.

## Runtime loading and cleanup

JNI uses the canonical APK path plus `!/lib/arm64-v8a/librknnrt.so` in `dlopen`, then resolves the seven required public RKNN functions from that handle. AOSP documents direct ZIP loading since API23 when the entry is uncompressed and page-aligned. See [Android Bionic changes for NDK developers](https://android.googlesource.com/platform/bionic/+/pie-release/android-changes-for-ndk-developers.md#opening-shared-libraries-directly-from-an-apk). Root separately inspected the v18 APK entry as STORED at offset 27,996,160, aligned to 4,096 bytes; the Java wrapper checks entry bytes/method but does not parse ZIP offsets. Actual `dlopen` and namespace behavior for the new library remain a device validation step. A process-map read was denied, so this report does not claim an observed existing runtime mapping or a verified native load path.

The pure C core is shared by the production wrapper and host fault tests. It initializes with flags zero, queries/validates SDK, counts and tensors, then runs `inputs_set → run → outputs_get → outputs_release`. It copies the 52 returned floats before release, but commits the caller's output only after successful validation and release. A failing `outputs_get` with a nonnull buffer still releases that original descriptor once. A successful get with an invalid buffer/shape/range also attempts release and fails. Output allocation in the JVM happens only after release, so JVM OOM cannot retain an RKNN output buffer.

Any acquired nonzero context receives one `rknn_destroy` attempt on failed initialization or explicit close. Query, get, release and destroy failures are not treated as successful inference. Destruction failures are surfaced; no retry of the already-cleared Java/native owner is attempted. A failed vendor destroy cannot be claimed to have freed its internal resources. Existing pending Java exceptions are not overwritten; JNI local references are deleted where applicable. Library open/close references belong to this wrapper; it does not replace or unload another owner's runtime reference.

## Host validation and build

The core test first failed because the new contract header did not exist, and the Java test first failed because the new wrapper class did not exist. GREEN results:

| Test | Result and boundary |
| --- | --- |
| `tests/run_rknn_expression_core_tests.ps1` | 1,064 checks; actual RKNN header, fake API functions; exact metadata and independent one-field mutations, every init/query/inference/release/destroy failure, partial output, no destination write before successful release, no use after fault |
| `tests/run_rknn_expression_jni_tests.ps1` | 440 checks; actual JDK JNI header and production C compiled into a host harness; fake VM/loader/RKNN; UTF/model-buffer/context allocation failures, partial get, JVM result allocation/region failures, local-reference cleanup, output release before allocation, destruction failure |
| `tests/run_rknn_expression_java_tests.ps1` | 64 checks; actual SDK35 compile and JDK17, real fixed model and v18 APK bytes, fake native boundary; both SHA gates, compressed-runtime rejection, buffer shape/order/finite gates, constructor cleanup preserving OOM, failed-close idempotence, latched fault, two-thread run/close serialization |
| `scripts/build_rknn_expression.ps1` | NDK25c (`25.2.9519653`), Android30 AArch64 shared library, `-O2 -Wall -Wextra -Werror`, explicit SONAME and defined-symbol link check |

The JNI host harness suppresses MSVC warning 4152 only around the included production C because the Windows host compiler diagnoses the POSIX `dlsym` function-pointer idiom. The Android NDK build retains all warnings as errors without that suppression. An initial WSL attempt found no installed `gcc`; the existing MSVC compiler was used instead, without installing tools.

Final isolated binary: `E:/tripo/device-lab/app/build/expression-jni-v2/libmirror_rknn_expression.so`, 14,040 bytes, SHA256 `cfc42c4abc78e0be521a20e5f7521e3138cadaf73a9dfa44f9189b0a485861c4`. `build.json` pins source, public header, compiler and command. ELF inspection shows AArch64/DYN, SONAME `libmirror_rknn_expression.so`, the four intended JNI exports, and dependencies only on public `libdl.so`, `libm.so`, `libc.so`. The earlier `expression-jni-v1` build is retained; v2 adds model/runtime identity fields to metadata only.

Reproduce Java tests with fresh scratch paths; no command here runs an APK or device:

```powershell
& E:\tripo\device-lab\tests\run_rknn_expression_core_tests.ps1
& E:\tripo\device-lab\tests\run_rknn_expression_jni_tests.ps1
& E:\tripo\device-lab\tests\run_rknn_expression_java_tests.ps1 -ModelPath E:\tripo\output\mirror-program\20261003\blendshape-numerical-diagnosis\normalized-suffix-v1-compile\normalized-suffix.rknn -ApkPath E:\tripo\output\mirror-program\20261003\400x640\AvatarRuntime-v18-400640.apk -OutputDirectory E:\tripo\device-lab\app\build\rknn-expression-peer-java-files
& E:\tripo\device-lab\scripts\build_rknn_expression.ps1 -OutputDirectory E:\tripo\device-lab\app\build\expression-jni-peer-build
```

The build script permits only a new directory beneath `app/build`; it never writes production `jniLibs`. These tests do not prove the packaged JNI loader, the device's Java floating-point behavior, integrated expression accuracy, frame pacing, long-run memory behavior or recovery from a permanently blocked vendor call. Those remain explicit integration/device gates. No CPU fallback, default model replacement, NPU clock/driver changes or new performance claims are introduced here.
