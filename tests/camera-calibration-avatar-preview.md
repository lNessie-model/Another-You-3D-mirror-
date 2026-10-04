# Live avatar tile for camera calibration

Status: integrated into the v13 calibration panel. The device has verified a visible matching builtin model, main-view pause while capture/preview continue, cancel, reopening and HOME recovery; see [device evidence](../docs/camera-avatar-preview-validation.md). Live human actions, imported current assets, failure branches and endurance remain unverified. This is a live single-view preview of the actual selected GLB, not a screenshot or a new rendering backend. `AvatarGpuScene.fromAsset(..., false, false)` reuses the existing rig, morphs, gaze, jaw, materials and framing. The viewport is centered at 10:16; requests are limited to 10 FPS. Main 20-view performance evidence does not include this separate maintenance UI.

## Host integration contract

All lifecycle operations and the listener run on the UI thread. `submit(snapshot, active)` and `status()` are thread-safe. The helper is a `GLSurfaceView` and should be laid out as a visible tile beside or above the normalized camera preview, with enough space to distinguish each eye.

1. On opening calibration, pause the **main** `GLSurfaceView` drawing with its `onPause()`. Keep the existing camera worker, Controller sampling and UI tick running. Do not call the main renderer's terminal `closeRuntimeAvatar()`. Its current bounded pose job can finish; no new GL prepare calls means no continuing pose submissions.
2. Construct `CameraCalibrationAvatarPreview(context, new File(getFilesDir(), "avatars"), SDK_INT, listener)`, attach it, and call `resumePreview()`. At most one live helper exists process-wide; a second constructor fails explicitly until the first closes.
3. Pass the real, already calibrated `InteractionController.Snapshot` and runtime active flag to `submit`. Do not synthesize Euler poses in the panel, feed raw pre-calibration values, or apply another mirror. The helper preserves each of all 52 weights, applies the same head extraction/angular limits and smoothing as the main renderer, and neutralizes stale/no-face input. Reposting a stale snapshot cannot refresh its receipt time.
4. On panel dismissal, HOME, Activity teardown or failure, call `close()` before dropping its view. It revokes owner and cancels pending work first. There is no application worker join. Then resume the main renderer via `renderer.resumeRuntimeAvatar()` and `mainSurface.onResume()` **only if the host remains resumed**. On HOME, let the normal Activity resume sequence restore it later.
5. `pausePreview()`/`resumePreview()` may instead reuse the same tile; resume creates a new ownership generation and rereads current selection. Old assets, snapshots, GL frames and errors cannot become results of the new generation. A permanently closed helper cannot resume.

The helper does not activate a package or modify settings. It loads Store current in the background, or builtin only when current is genuinely absent/API unsupported. A corrupt selected package is an explicit preview error, so a different fallback character cannot accidentally be approved. The reported source and model SHA allow comparison with the main runtime selection.

## Bounds and lifecycle

The shared loader has one running job and one queued job; cancel removes a queued future and interrupts a running one without waiting. Reads and existing loader/store budgets remain bounded (32 MiB file and decoded asset budgets, 20k vertices/30k triangles/8 primitives). There is one requested asset, one Scene and one latest pose mailbox. No additional `AvatarPoseWorker` is created; CPU deformation runs once per preview frame on its GL thread. The additional deformer source cache retains its existing 32 MiB payload/4 MiB sparse-index ceilings; output/VBO payload is limited by the existing geometry bounds. These are payload bounds, not a claim about total PSS or peak Java/native allocations; the target's 2 GB memory still requires device measurement.

Loading has a 30-second deadline. Once loaded, first successful GL submission has 20 seconds; context recreation requires a new successful frame. Deadlines revoke the owner and reject late results. They do not promise to interrupt a driver or filesystem implementation that ignores cancellation; the global capacities prevent multiplying blocked loader threads or preview instances.

GL objects are deleted only on the GL thread when their owning EGL context is current. Context loss discards old IDs without deleting them in a new context. Pause does not preserve the preview context and drops its Java scene/asset references after the platform pause returns. `GLSurfaceView.onPause()` and detach retain Android's normal wait for a current GL callback; this helper does **not** promise an absolutely nonblocking UI if the GPU driver itself hangs. It adds no custom join. The main SurfaceView already has the same platform constraint.

## Verification

- `tests/run_camera_preview_tests.ps1`: deterministic owner/deadline/recreation, stale snapshot, independent eyes, actual rotation, limits, neutral return and 10:16 viewport checks.
- `tests/run_camera_preview_sdk_tests.ps1`: compile the new Android helper and real rendering dependencies against installed SDK35; compile current `InterlaceRenderer` afresh and compare 1,000 accepted rotation/scale poses (3,000 exact float angle results).
- First real-device gate must verify selected-model SHA, clear left/right blink, jaw, each gaze direction and head turns in the visible tile; save/cancel/default/reopen/HOME and rapid detach; missing/corrupt selected asset; deadline failure; continued camera ownership with no second camera; bounded PSS/thread counts and zero extra pose worker.
- A Scene GL submission is not proof of human action direction or artwork correctness. Runtime performance and optical 20-view acceptance remain separate.
