# Debug-only avatar draw batching

The default `AvatarGpuScene` path remains `DrawMode.INDIVIDUAL`. Explicit
`DrawMode.BATCHED` combines the original geometry into one draw per view group;
`DrawMode.VERIFY` retains both independent sets of GPU buffers for a diagnostic
pixel comparison. VERIFY rejects asynchronous CPU ownership. None of these
changes alter the loader profile, asset, rig, deformer or panel parameters.

Integration entry points (current owning GLES context required):

```java
AvatarGpuScene.builtin(assets, multiview, asynchronous, drawMode);
AvatarGpuScene.fromAsset(asset, manifestJson, modelSha256, multiview, asynchronous);
AvatarGpuScene.fromAsset(asset, manifestJson, modelSha256, multiview, asynchronous, drawMode);
```

The five-argument `fromAsset` defaults to INDIVIDUAL. The caller supplies an
already-validated CPU asset/manifest and verified model hash. Each scene creates
its own mutable rig and, if requested, private worker. Construction failures
release created GL objects and stop CPU ownership. The factory has no dependency
on package storage. Existing prepare/draw/stopCpu/dispose contracts remain intact;
context loss requires discarding the old scene and signalling stopCpu, not deleting
old GL names in the new context.

## Data and arithmetic

`AvatarBatchLayout` enumerates active nodes in the same numeric order as the
individual renderer, then primitives and triangle indices in their original
order. Every item has a vertex/index subrange and node/mesh/primitive identity.
Shared meshes under different nodes receive distinct items, preserving their
different transforms. Only the debug backend rejects more than 8 items, 20,000
expanded vertices or 30,000 expanded triangles; individual behavior is unchanged.

`AvatarBatchGpu` owns one dynamic xyz/nxyz VBO, a merged original COLOR_0 buffer,
one uint draw-id buffer and one rebased uint index buffer. Missing COLOR_0 becomes
the exact original white attribute constant. Material base color is a uniform,
not duplicated in vertex data. A dirty source primitive uploads its existing
interleaved buffer into the matching item subranges; there is no CPU repacking or
per-view deformation. All views still share one completed pose.

The vertex shader retains the original multiplication sequence:
`world = uWorld[id] * vec4(position, 1)`, then `gl_Position = VP[view] * world`.
The CPU still calculates `fit * nodeWorld` and its inverse-transpose normal
matrix. It does not premultiply MVP or pretransform vertices, which would change
rounding. The current v9 candidate uses the integer id in the vertex shader to
select material color, unlit flag and roughness, then passes them through flat
highp vec4/vec2 varyings. All lighting and COLOR_0 arithmetic stays in the
original order. Integer data uses `glVertexAttribIPointer`, not normalized or
float attribute conversion. The measured v8 backend instead passed a flat id
and performed material-array selection in the fragment shader.

For v9 with 8 items and 4 OVR views, the conservative vertex requirement is 88
vec4 uniform slots (32 world + 24 normal + 16 VP + 16 material/parameters);
fragment requirement is zero. The actual 7-item model requests 79/0. The
varyings require five vectors conservatively, compared with four in v8.
GL limits are queried before allocation and reported, then shader compile/link
and every required uniform location are checked. Unsupported debug
configuration fails explicitly. GLES3
minimum uniform limits are 256 vertex / 224 fragment slots; see the official
[OpenGL ES 3.0 specification](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
The limits do not establish driver correctness or performance.

For asset SHA `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407`,
7 items / 15,085 vertices / 88,446 indices retain the same geometry and colors.
At 20 views in groups of 4, draws decrease from 35 to 5. Added id storage is
60,340 GPU bytes; merged original position/normal/color/index payload is not
additional runtime duplication in BATCHED mode. The immutable CPU layout also
retains 655,484 bytes of packed color/id/index arrays; constructor direct upload
copies of that size are transient. VERIFY intentionally duplicates original and
batched GPU storage and uploads, so it must not be used for performance results.
Array indexing and additional flat varyings can offset reduced driver calls;
v8 failed the speed gate below, and v9 has no measured speedup yet.

## Verification

`tests/run_avatar_tests.ps1 -AssetPath .../character.glb` includes the batch layout
regression. It first failed for the absent implementation and then passed.
Tests independently compare every packed index, draw id and COLOR_0 value against
the original primitives, including repeated mesh instances, inactive nodes,
missing colors, nonempty geometry and item limits. Existing golden deformation,
finite/rollback, source-cache and allocation tests continue to pass.

The scene review runner includes the new dependencies; its Unsafe-only fixture
sets the new final mode explicitly. Real metric publication, stale-worker
progress and status ordering tests pass without invoking a GL method. All new
GPU classes compile against installed SDK35. Host checks do not compile shaders
on the Mali driver and do not prove rendering or performance.

```java
JSONObject report = AvatarBatchCheck.run(assets, 400, 720, 20, .625f, cancelled);
```

This diagnostic reuses the existing multiview checker lifecycle and framebuffer
handling. For each of 69 fixtures it prepares CPU pose exactly once and uploads
both independent buffer sets. It compares original single-view rendering with
batched OVR4 across all 20 actual array layers: 1,380 pairs. All source slots,
head extremes, gaze, jaw and combinations remain covered. Reports carry the
exact model hash, explicit comparison backend and queried uniform limits.
Nonempty opaque output, matching alpha, RGB maximum <=1, RMSE <=0.1, differing
first/last cameras and visible pose variation are required. Original public
`AvatarMultiviewCheck.run` continues its original individual serial/OVR check.

## Device pixel gate: v8, 2026-10-03

Independently re-read and checked
`E:/tripo/output/mirror-program/20261003/avatar-v8-batch.json`, report SHA-256
`c7eae1b2b1c26b303a6ea8b36f7a3529a312efb5d2b8e27546cafb2700c55293`.
Run ID `1ccb18fd-6909-49db-ac1a-3d76bba3e52c`, context generation 1, completed
without cancellation and passed the declared tolerance. The archived
`AvatarRuntime-v8-import-batch.apk` hashes to
`5f38fe6cb60f14f4249cae1122f96a1c8217950ee82821358edb540789a65af9`.
The model remains the `8349…e407` SHA recorded above.

On ARM Mali-G52 / OpenGL ES 3.2, all 69 fixtures and all 20 global layers per
fixture completed: 1,380 pairs at 400x720 and physical aspect 0.625, with 69 CPU
prepare calls. Both vertex and fragment uniform limits were 4,096 vec4 slots,
against actual requirements of 65 and 14. Every layer passed foreground/opaque
and alpha gates; every fixture retained distinct first/last cameras, and 67
fixtures changed the center view relative to neutral.

There was **one differing RGB byte across the entire report**, so this is
tolerance-level equivalence, **not bitwise equality**. The difference occurs in
`source-mouthFrownLeft` (source index 30, zero head angles), global view 16
(OVR base 16, relative view 0). That layer has maximum RGB error 1, exactly one
RGB-byte mismatch and RMSE `0.001075828707279838`; its two RGBA hashes differ.
The other 1,379 pairs have matching hashes. All alpha mismatches and nonopaque
counts are zero; foreground counts in the differing pair are both 39,980 pixels.
These results satisfy the pre-existing maximum-error <=1 and RMSE <=0.1 gates;
the allowance was not changed after observing the result.

Diagnostic duration was 360,071.789 ms. This synchronous readback test certifies
the stated individual-serial versus batched-OVR4 comparison only; its report
explicitly sets `performance_evidence=false` and `artwork_validated=false`.
It does not validate panel optics or asynchronous pose scheduling.

## Device performance gate: v8 same-APK A/B, 2026-10-03

Independently checked the completed 90-second captures in
`E:/tripo/output/mirror-program/20261003/`:

- `avatar-v8-individual-a-90s.json`, SHA-256
  `59282d2b1dc76d459c107548ff73194810c39dab9a2d56e3df402f749c563ce9`.
- `avatar-v8-batched-b-90s.json`, SHA-256
  `ecad7fc52fd0cae754c81a2dbfa3e50d2c18a8f8e4f05eb79f489fe553751cf9`.

Both use the APK and model SHA above, `camera_replay`, 20 independent views at
320x576, 1200x1920 panel output, physical aspect 0.625, render target 31 FPS,
and the same calibration. Status confirms `bounded_cpu_worker` in both and
actual `draw_backend=individual` versus `batched`; the latter has 7 items and
one draw per view group. VERIFY is not involved. Geometry, shading, input and
quality settings are unchanged. This is a 320x576 performance comparison;
the preceding 400x720 pixel gate does not establish 400x720 runtime FPS.

| Metric | Individual A | Batched B |
| --- | ---: | ---: |
| Confirmed INTERACTIVE actual presented FPS | 29.315825 | 27.856218 |
| Confirmed INTERACTIVE duration, seconds | 84.721565 | 84.759121 |
| Whole-machine CPU, active mean | 87.529% | 86.767% |
| GPU busy, active mean | 87.481% | 93.772% |
| NPU busy, active mean | 45.886% | 45.848% |
| Temperature, active min–max | 62.777–71.666°C | 65.000–75.000°C |
| Temperature, active sample mean | 68.016°C | 72.069°C |
| App PSS, active sample mean / peak | 362.050 / 466.295 MiB | 341.136 / 456.334 MiB |
| Face FPS, last input-session status | 15.057144 | 14.974251 |
| Morph mean, last scene status | 10.033 ms | 11.982 ms |
| Pose buffer copy mean, last scene status | 0.602 ms | 0.721 ms |
| GL upload mean, last scene status | 0.426 ms | 1.092 ms |
| Applied pose age mean, last scene status | 33.740 ms | 36.105 ms |

Both collectors completed with empty collection errors, status errors, state
history gaps and SurfaceFlinger history gaps. Each has three complete active
intervals; idle/acquiring and the short replay-loop GRACE transitions are
excluded. Their interval rates are respectively 28.389 / 29.893 / 30.100 and
27.449 / 28.055 / 28.326 FPS. `complete_active_evidence=true` and
`rate_is_lower_bound=false` in both. This certifies the confirmed active
intervals only: the final unconfirmed 0.631 / 0.287 seconds are explicitly
outside the claim. Neither report meets the 30 FPS gate.

Resource means above use the 79 samples inside confirmed active intervals in
each run; PSS has only 16 / 17 samples. Face metrics are the final input
session's online values. Scene morph/copy/upload/age means are cumulative
scene values, including startup/acquisition; they are not active-only means
and are not obtained by subtracting counters across sessions. No recovery or
latched rendering/processing error was recorded. Both workers retain the
bounded three slots, with maximum pending and ready queue counts of one.

Batching loses 1.460 FPS (4.98%) while lowering measured CPU only 0.762
percentage points and raising GPU busy by 6.291 points. Reduced draw count
therefore fails the measured speed gate, and INDIVIDUAL remains the default.
The sequential B run was warmer; a single ordered A/B cannot isolate thermal
or order effects. Sampled CPU0 and GPU frequencies remained 1.8 GHz and
800 MHz in both, so these samples do not demonstrate thermal downclocking.
PSS differences do not establish a memory saving because sample timing and
demand paging differ.

The next isolated candidate, implemented as v9 below, moves material-array selection from the
fragment shader to the vertex shader and passes material color/parameters as
flat highp varyings. The measured v8 batched fragment shader performs dynamically
indexed `uColors[vPrimitive]` / `uParams[vPrimitive]` reads, unlike the
individual path. Increased GPU busy makes this a testable hypothesis, not a
proven root cause; extra varying bandwidth could offset any benefit. Preserve
the existing lighting arithmetic, vertex COLOR_0, transforms, geometry and
views, rerun the 69x20 pixel gate, then compare performance with matched
starting temperature and reversed or repeated A/B order. Separately, the
larger upload time warrants testing shared-VBO read/write dependency costs;
do not combine that change with the shader experiment. The VBO candidate has
not been implemented; v9 performance evidence remains pending.

## v9 vertex material selection: host-only gate

Only `AvatarBatchGpu` changes production behavior for this candidate: shader
material selection moves to VS, associated uniform/varying limits are updated,
and status reports `material_selection_stage=vertex_flat`. The individual
renderer, integer vertex/index/COLOR_0 layout, buffer allocation and upload,
draw sequence, node transforms, precision, lighting arithmetic, model and
multiview grouping stay unchanged. The additional material varyings use flat
interpolation; all three vertices of each triangle already carry the same
item id, as checked by the existing layout tests.

`AvatarBatchShaderTest` first failed against the v8 source with
`material array is selected in vertex stage`, then passed 288 checks after
implementation. Across 1–8 items and single/OVR4 shaders it checks stage
placement, matching flat highp interfaces, uniform declarations/budgets,
integer attribute identity, world-then-VP order, and the frozen v8 lighting
body. The SDK35 scene review runner compiles the production source and also
passes its existing stale-worker, 100,000-update concurrent metrics, watchdog
and status-order tests. These are source/Java linkage checks, not host GPU
shader execution. The v8 device pixel and performance evidence above does not
validate v9; its independent device pixel gate is recorded below, and same-APK
performance A/B remains required. INDIVIDUAL remains the default.

## Device pixel gate: v9, 2026-10-03

Independently re-read all fixtures/layers in
`E:/tripo/output/mirror-program/20261003/avatar-v9-batch.json`, report SHA-256
`422e1a9c6a1b7625379d82b57a7383687794bacdc1133e6ed512b0516dca718d`.
Run ID `3ddb8907-a8df-4fe3-9249-c5e7bc79738f`, context generation 1, completed
without cancellation. The archived
`AvatarRuntime-v9-import-fix-flat-material.apk` independently hashes to
`8ab4674ca965c199697a1e91ff058b2df247e5f472f090be825a1c8cb157a24e`.

The same model `8349…e407`, 15,085 vertices, 29,482 triangles and 7 primitives
were used. The diagnostic explicitly reports synchronous CPU preparation,
VERIFY drawing and `material_selection_stage=vertex_flat`. This is original
individual single-view versus v9 batched OVR4, before interlacing. All 69
fixtures / 1,380 layer pairs at 400x720 and aspect 0.625 completed, with exactly
69 CPU prepare calls. All 52 isolated source indices and all global layer
indices 0–19 are present; OVR base/relative indices are consistent.

On the same Mali-G52 / OpenGL ES 3.2, vertex/fragment uniform limits are
4,096/4,096 versus requirements of 79/0; varying limit is 31 versus the
conservative requirement of 5. All fixtures have differing first/last camera
images in both implementations; the report retains 20 distinct eye positions
and 67 changed-center fixtures. Every layer is nonempty and opaque, with a
minimum foreground count of 33,796. Alpha mismatches and nonopaque counts
are zero.

The declared maximum RGB error <=1 / RMSE <=0.1 gate passes unchanged. There
is exactly **one differing RGB byte**, again `source-mouthFrownLeft`, global
view 16 (OVR base 16, relative 0), with maximum error 1, RMSE
`0.001075828707279838`, and 39,980 foreground pixels in each rendering. Thus
individual versus batch remains tolerance-equivalent, not bitwise equal.
Separately, comparing v9 to the archived v8 report shows that **both hashes
of every one of the 1,380 corresponding pairs are unchanged**: the shader
stage move has not changed any captured fixture output on this device.

Diagnostic elapsed time is 361,021.388 ms and is not runtime FPS evidence.
The report still declares `performance_evidence=false` and
`artwork_validated=false`; it does not certify asynchronous scheduling,
physical panel optics or all expression artwork. v9 same-APK reverse-order B/A performance completed at 27.0074 / 29.3386
actual FPS with identical 20×320×576 and full face workload. Both retain
complete confirmed active histories and neither sustains 30 FPS. This single
ordered pair does not isolate thermal or scheduling effects; individual stays
the default. See [runtime evidence](../docs/avatar-runtime-validation.md).
