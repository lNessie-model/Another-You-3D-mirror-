The RGA JNI caller uses Rockchip's Android NDK library **1.10.6_[3]**, loaded from the application, with the board's existing RGA driver **1.2.25**. It does not replace `/vendor/lib64/librga.so`.

- Headers: <https://github.com/airockchip/librga/tree/main/include>, Apache-2.0 notices retained in `vendor/rga`.
- Tested binary: <https://github.com/airockchip/librga/blob/main/libs/AndroidNdk/arm64-v8a/librga.so>.
- Binary SHA256: `d0a7c20120601e010d5a84653d36d552156743f75da7d64e2161b96b19fe602b`.
- NDK: official Android **r25c** Windows distribution, SHA1 `18c4a3cd108916f553b1bedad2672f2c6cd85a10`.
- C++ runtime: NDK r25c arm64 `libc++_shared.so`, SHA256 `33f661fc80c90913fe365a2bb6ee0bb7ed4c01b9c797fcecf1e60070e00019d6`.
- RKNN header: <https://github.com/airockchip/rknpu2/blob/v1.3.0/runtime/RK356X/Android/librknn_api/include/rknn_api.h>, original Rockchip notice retained; see the header for its declaration. The native probe dynamically loads the existing **1.3.0** vendor runtime, matching board driver **0.7.2**.

`scripts/build_native.ps1` checks the RGA binary and compiles both callers. Generated native libraries are excluded from Git. Rebuild them before assembling an APK that uses RGA. RGA is opt-in and conversion errors are reported explicitly.

The optional application NPU smoke entry uses a package-local copy of the existing board `librknnrt.so`, SHA256 `01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de`. Put this matching copy in `app/src/main/jniLibs/arm64-v8a/` when building that test APK; it is not replaced on the system partition. `NpuSmokeActivity` at app UID 10116 completed 1000 MobileNet runs at 5.666 ms/run with correct class 156/score 0.984375 and actual NPU peak 88%. This verifies access from an ordinary application as well as the shell.

Official RKNN Toolkit2 1.3.0 is from commit `9ad79343fae625f4910242e370035fcbc40cc31a`; cp38 wheel MD5 `f37e2296e631587b929a75028dd235bf`. The isolated WSL runtime is Python-build-standalone 3.8.19 (20240814), archive SHA256 `9ebb4d3ff993e977c5f2c043369024be8429447cee67a16e7d4a84f03064116a`. Python, virtual environment and pip cache are kept under `E:/tripo/native-tools/`. On 2026-10-02 the exact face detector and 478-point image models were compiled and verified on the board using this matching compiler/runtime, without a driver update. The 52-expression model failed RKNN 1.3 compilation and is retained on CPU. See `npu-asset-manifest.json` and the README's NPU section for actual face results.

NPU hardware validation uses the official SDK's MobileNet model and RGB dog fixture. Its timing is classification timing and cannot be claimed as Face Landmarker performance. The installed MediaPipe 1.0.0 Java artifact exposes `Delegate.NPU`, but its `BaseOptionsUtils` switch only maps CPU/GPU into acceleration options. The direct NPU-enum trial selected XNNPACK CPU inference and had zero RKNPU activity. A real face port requires RKNN conversion plus compatible preprocessing, tracking, and postprocessing.
