# Bounded avatar pose handoff

`AvatarPoseWorker(asset, manifestString)` parses the manifest once and owns a
private `AvatarRig` and `AvatarDeformer`. A caller submits all 52 coefficients
and three filtered head angles; each accepted input receives an independent
monotonic ID. No inference-sequence shortcut drops interpolation updates.

There is at most one pending input, one latest completed output, and three
preallocated output slots. A slot is FREE, WRITING, READY or LEASED. Only FREE
slots may be written. A READY slot is replaced only after the next calculation
succeeds, so calculation failure retains the previous complete result.
Backpressure when all slots are held replaces only the pending input.

GL calls `acquireLatest()`, copies the complete node-world matrix array, uploads
each changed primitive's read-only native-order direct xyz/nxyz buffer, and
calls `release(frame)` in `finally`. The frame owns a unique lease wrapper and
generation, preventing double release and stale release after slot reuse. GL
must not retain or access a buffer after releasing its lease. All views share
the single uploaded geometry; no per-view CPU deformation runs.

`close()` signals the worker and discards pending/ready work without joining.
Existing leases stay valid until released. A computation already in progress
finishes into its private WRITING slot and is discarded after close, never
published. `failure()` latches the first background failure, stops further
inputs, and leaves last successful READY/LEASED frames intact. Callers must
report this failure instead of silently treating the frozen output as healthy.

Frame timing separates rig, deformation, and slot copy time, with submitted,
started and completed monotonic timestamps. `status()` provides submitted,
processed, published and dropped counts, the current pending/ready/writing/
leased counts, their bounded pending/ready high-water marks, and lifecycle/error
state. Snapshot allocation is intended for periodic metrics. Only one small
lease wrapper is allocated per successful GL acquire; bulk geometry storage
and the worker's input arrays are preallocated.

```powershell
./tests/run_avatar_worker_tests.ps1 -AssetPath 'E:\tripo\device-lab\app\src\main\assets\avatars\builtin-guide\character.glb'
```

Host tests exercise three simultaneous leases, input copying under deterministic
backpressure, discarded superseded inputs, overwritten READY results, release
generation safety, read-only/direct buffers, close while the producer is
blocked, latched deformation overflow preserving last success, and exact
per-float equivalence with the synchronous rig/deformer on the real GLB. They do
not claim an Android frame-rate improvement; that requires same-APK synchronous
and asynchronous device measurements.

## Android array-bulk slot copy

The first implementation used `destination.put(sourceFloatBuffer)`. This looks
like bulk copying on the JVM, but the AOSP Android 31
[FloatBuffer source](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-31/%2B/refs/heads/main/java/nio/FloatBuffer.java)
implements that overload with a per-float get/put loop. Its
[ByteBufferAsFloatBuffer implementation](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-31/%2B/refs/heads/androidx-savedstate-release/java/nio/ByteBufferAsFloatBuffer.java)
instead specializes the **array** get/put overloads; Android 30's
[DirectByteBuffer implementation](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/%2B/refs/heads/androidx-compose-integration-release/java/nio/DirectByteBuffer.java)
passes float arrays to native memory transfers. This distinction is consistent
with the device's measured roughly 11 ms slot-copy stage; host JVM performance
does not establish the Android cost of this overload.

`AvatarPoseWorker.copyViaArray` now explicitly uses `source.get(scratch,0,count)`
and `destination.put(scratch,0,count)`. A single scratch float array, sized to
the largest primitive's packed xyz/nxyz buffer, is allocated in the constructor
and reused only by the producer. Slots still receive private direct-buffer
copies; a lease never aliases the scratch array. The state machine, revision
skip, fixed output-slot count and close/error behavior are unchanged. There is
no new per-frame large allocation, hidden API or reflection.

Tests now include both byte orders, signed zero/subnormal/extreme finite values,
exact copy bounds, upload cursor state, scratch isolation, and the prior lease/
failure tests (55 host assertions). On the actual GLB, 64 continuously changing
neutral/maximum/seeded-random inputs and head poses exactly match synchronous
node matrices and every primitive's packed output; a prior lease remains stable
while later slots are computed. The measured performance benefit remains a
separate device result. This slice does not change the deformer or renderer.
