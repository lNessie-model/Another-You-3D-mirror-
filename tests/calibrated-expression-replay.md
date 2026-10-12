# Calibrated expression candidate replay

The candidate is intentionally unreachable from the ordinary product camera path. `MirrorActivity`
accepts it only when all of the following are true:

1. The APK is debuggable.
2. `test_calibrated_expression=true` is explicitly supplied.
3. `runtime_input` is `replay` or `camera_replay`.

The default audit configuration uses `eyeWideGamma=3.5`. A debug replay may override that one
temporary parameter with `test_expression_eye_wide_gamma` in the validated range 1..4.

Example after building a debug APK and installing it:

```powershell
adb -s 6L32552009566714 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 6L32552009566714 shell am force-stop com.mirror.bench
adb -s 6L32552009566714 shell am start -n com.mirror.bench/.MirrorActivity `
  -e runtime_input replay `
  -e record_id web_blinking `
  -e test_calibrated_expression true `
  --ef test_expression_eye_wide_gamma 3.5
```

Use `runtime_input=camera_replay` to keep USB acquisition active while the recorded face stream
drives the NPU. Read `files/mirror-runtime-status.json` with `adb shell run-as com.mirror.bench cat
files/mirror-runtime-status.json`; `calibrated_expression_candidate` and
`face_playback.response_policy` identify the active mapping. A normal `runtime_input=camera` launch
or a release APK rejects the candidate option and continues to use `FacePlayback.apply`.

Host gates:

```powershell
powershell -ExecutionPolicy Bypass -File tests/run_runtime_view_count_tests.ps1
powershell -ExecutionPolicy Bypass -File tests/run_face_playback_tests.ps1
```

The current checkout has no `gradlew` or `gradle` executable, so these commands validate SDK
linkage and host behavior but do not produce a new APK.
