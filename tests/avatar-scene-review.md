# Scene integration review tests

Run from any directory with JDK 17, Android SDK 35, and the real JSON-java jar described in `avatar-loader.md`:

```powershell
E:/tripo/device-lab/tests/run_avatar_scene_review_tests.ps1 -JavaHome 'C:\Program Files\Java\jdk-17' -AndroidSdk 'C:\Users\lNessie\AppData\Local\Android\Sdk'
```

The runner compiles current production sources to `app/build/avatar-scene-review-tests/classes`. It does not require a prior Gradle build, use compiled APK classes, download dependencies, call ADB, or install anything. JSON-java precedes `android.jar` on both compile and runtime classpaths; Android's JSON stubs must not be used as a host implementation.

Two review-specific tests use `sun.misc.Unsafe.allocateInstance` solely to bypass EGL-dependent scene construction. They explicitly initialize the required CPU fields and invoke the real scene methods. No GL method executes: the progress test leases all worker output slots, and the metrics test calls only publication/status methods. The expected JDK warning is limited to test code; production sources do not use Unsafe.

- `AvatarGpuSceneProgressReviewTest` exercises a real worker with no available output, proves the actual scene detects expired progress, and verifies explicit resume grants a new interval without inventing an applied result. The original callback-gap reset was reproduced RED before the watchdog integration.
- `AvatarGpuSceneMetricsReviewTest` races 100,000 real metric publications against status reads and checks exact relationships among every counter, last timing, online mean and maximum. The snapshot count varies with scheduling.
- `AvatarPoseProgressWatchdogTest` uses deterministic timestamps for initialization, explicit resume, stale/future results, exact freshness/timeout endpoints, negative `nanoTime`, and signed wrap.
- `RuntimeStatusOrderTest` verifies first publication, unique resume epochs, obsolete worker rejection, delayed initialization versus newer success, equal-timestamp ties, failed-write preflights, and clock-sampling/scheduling races.

Runtime status begins a new UUID/epoch on every Activity resume. The UI prepares its initialization snapshot in memory; a background task writes it. Every input worker retains its creation epoch, and every publication keeps a timestamped ticket. Disk writes are serialized and recheck ticket validity before AtomicFile completion. An old report cannot overwrite a report already committed for the new epoch. An epoch can start while a previous disk commit is finishing, so readers can briefly observe the explicitly labeled previous session before the queued initialization write completes; there is no promise of zero transient old reads and no UI wait for filesystem synchronization.

These tests establish CPU branch and publication behavior only. HOME/resume, context recreation, native cancellation, GLES pixels and actual presentation rates still require separate device checks. The isolated runner is intentionally outside the Android application runtime.
