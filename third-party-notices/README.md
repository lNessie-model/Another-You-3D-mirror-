# Production dependency sources and retained notices

The repository's original MIT `LICENSE` applies to this project's original source. It does not replace the original notices or distribution statements of third-party files. This directory records the existing dependencies; it does not assign a new license to their models or binaries.

All 15 production model/native files in `production-dependency-manifest.json` match the corresponding unpacked bytes of the frozen v30 APK, SHA-256 `45e3e0893ed9dd3d44f6b8eab7a9176af99de356de6def5e9871d9d1657af62f`. No SDK, vendor runtime, driver or model version was upgraded for this repository sync.

| Files | Existing source and notice |
| --- | --- |
| `face_landmarker.task` | [Pinned Google Face Landmarker float16/1 task](https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task), also recorded in `asset-manifest.json` |
| `face_blendshapes.tflite`, `geometry_pipeline_metadata_landmarks.binarypb` | Extracted from the same fixed Google task; hashes verified by `scripts/prepare_npu_assets.py` |
| `face_detector.rknn`, `face_landmarks_detector.rknn` | Local RKNN Toolkit2 1.3.0 conversion of the exact Google task models, unquantized; source/model hashes recorded in `npu-asset-manifest.json` |
| `face_blendshapes_normalized_suffix.rknn` | Fixed normalized-suffix conversion of the same Google expression model; its original validation/design are documented in `docs/normalized-expression-integration.md` |
| `portrait.jpg` | [Public Google MediaPipe portrait fixture](https://storage.googleapis.com/mediapipe-assets/portrait.jpg), not a capture of the device user; exact source and hash in `asset-manifest.json` |
| `libc++_shared.so` | Existing Android NDK r25c arm64 library; original `NOTICE` and `NOTICE.toolchain` are copied as `Android-NDK-r25c-NOTICE.txt` and `Android-NDK-r25c-NOTICE.toolchain.txt` |
| `librga.so` | Existing Rockchip Android NDK RGA 1.10.6_[3] binary; source reference in `native/DEPENDENCIES.md`, [upstream binary location](https://github.com/airockchip/librga/blob/main/libs/AndroidNdk/arm64-v8a/librga.so); original RGA header statement is retained |
| `librknnrt.so` | Existing package-local copy of the board's matching 1.3.0 vendor runtime, not a new runtime download; original source identity and hash are recorded in `native/DEPENDENCIES.md` and `npu-asset-manifest.json` |
| `libmirror_accel.so`, `libmirror_gl.so`, `libmirror_npu.so`, `libmirror_rknn_face.so`, `libmirror_rknn_expression.so` | This project's compiled JNI source, using the fixed native headers/libraries above; corresponding source and build scripts are included |

The original `META-INF/NOTICE` files were extracted from the locally existing pinned MediaPipe `tasks-core:1.0.0` and `tasks-vision:1.0.0` AARs. The AARs themselves remain Gradle dependencies rather than copied SDK caches.

The actual existing RKNN API header begins with a Rockchip proprietary/confidential statement. That original comment is retained both in `native/vendor/rknn/rknn_api.h` and in `Rockchip-RKNN-header-original-notice.txt`; it is not relabeled Apache-2.0 or MIT. The RGA header carries its own Apache-2.0 statement, retained in its source and `Rockchip-RGA-header-original-notice.txt`. Do not infer the RKNN runtime's license from the RGA header or the project's MIT file.

The procedural `builtin-guide` asset retains its separate [CC0 declaration](../app/src/main/assets/avatars/builtin-guide/LICENSE.txt). Project character/background assets are separate from the third-party SDK dependency files described here.

Personal replay videos, captured images, 92-case real-face numerical fixtures, device private preferences and signing/API credentials are excluded from the sync.
