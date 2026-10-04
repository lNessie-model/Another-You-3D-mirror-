# Actual-avatar serial / OVR4 verification

The existing `InterlaceRenderer.compareScenePixels` sets only the diagnostic
mesh's four `expressions` and `fixedAngle`. The avatar branch reads
`runtimeBlendshapes[52]` and `runtimeAngles` instead, so those fixtures do not
exercise the real avatar's facial/head controls. Calling its asynchronous
`prepare` twice also would not guarantee the same completed CPU snapshot.
Finally, comparing only the interlaced image can miss errors in unselected
texture-array samples.

`AvatarMultiviewCheck` is an explicit diagnostic helper, wired to
`AvatarPreviewActivity` through the debug Intent extra `verify_multiview=true`
(`--ez verify_multiview true` with `am start`). The preview invokes it on its
current GLES3 thread, outside active queries or transform feedback:

```java
JSONObject check = AvatarMultiviewCheck.run(
        getAssets(), 400, 720, 20, 1200f / 1920f, () -> cancelled);
// Save with the Activity's current run ID/context generation and AtomicFile.
// Check both completed and passed; cancellation/partial rows do not constitute a pass.
```

The helper creates a private synchronous `AvatarGpuScene` with both shader
programs enabled. Each of 69 fixtures calls `prepare` exactly once, then renders
the identical VBO/world matrices through single-view and OVR4 paths. Fixtures
include neutral, all 52 individually activated input positions, head rotations,
both-eye gaze, jaw translation/opening, mouth-close correction and combinations.
The neutral schema slot is tested too; it is not expected to create a facial
action. Fixture names, exact coefficients and angles are included in the report.

OVR color/depth arrays contain the full requested view count. Batches use actual
base indices 0, 4, 8, 12 and 16 for 20 views. Every layer is attached separately
to a read FBO and compared with its independently rendered serial camera; the
checker does not reuse four array layers as a substitute for testing all global
layers. OpenGL disallows reading directly from a multiview framebuffer, so the
ordinary single-layer attachment is required. See the official
[OVR_multiview specification](https://registry.khronos.org/OpenGL/extensions/OVR/OVR_multiview.txt),
especially the ReadPixels error rule and issue 14.

Each pose/view reports RGB mismatch count, maximum channel error, RMSE, alpha
mismatch/nonopaque counts, nonempty foreground counts, and SHA-256 hashes of
both RGBA buffers. RGB maximum <= 1, RMSE <= 0.1, opaque matching alpha, and
visible geometry are required. Additionally, the first/last serial views and
first/last OVR views must each differ. This prevents accidentally passing by
rendering one identical camera into every layer. At least one fixture must also
change the center view relative to neutral. Per-source expression quality or
correctness is recorded for inspection, not inferred from a pixel difference.

Own FBOs, textures, renderbuffer, VAO and avatar programs/buffers are deleted in
`finally` while the owning context is current. The helper preserves the caller's
framebuffer/VAO/buffer/program/texture/viewport, pixel-pack and affected raster
states. Cancellation is checked between poses, batches and views. Dimension and
layer-pixel bounds limit diagnostic storage; for the normal 400x720x20 run,
storage is reused across all 69 fixtures. It intentionally performs synchronous
readback and must not run during a performance capture.

The returned JSON identifies the GL implementation and exact avatar SHA via its
scene status. Unsupported extensions, GL failures and cancellation return
`passed=false`, an error, and any completed/partial rows. Illegal dimensions
throw before GL work. A cleanup failure also makes the result fail. The Activity
must wrap/save failures with its own fresh run metadata so an old success file
cannot be mistaken for the current result.

Host-only fixture/metric tests:

```powershell
./tests/run_avatar_multiview_fixture_tests.ps1
```

They cover all source positions, immutable fixture inputs, nonempty/opaque
gates, numeric mismatch rejection, hashing and cursor preservation (4,048
checks). The GPU helper also compiles against the installed SDK35. Neither
host result executes a GLES driver. Only the returned device report can confirm
serial/OVR equivalence; even that does not certify interlacing, panel optics,
asynchronous pose delivery, artwork, or 30 FPS.

## Device evidence, 2026-10-03

The root device run is saved at
`E:/tripo/output/mirror-program/20261003/avatar-v4-multiview.json`, run ID
`8584e865-931c-4393-a7aa-6ae6615eb7ae`, context generation 1. The report was read
back independently from disk: `completed=true`, `passed=true`, `cancelled=false`.
The device reports ARM Mali-G52 / OpenGL ES 3.2, with maximum OVR view count 4.

All 69 fixtures completed at 400x720, 20 independent views, physical aspect
0.625: 1,380 layer pairs, each with matching RGBA hashes, zero RGB mismatches,
zero maximum RGB error and zero alpha mismatches. Every fixture's first and
last camera images differ in both paths, and 67 fixtures change the center view
relative to neutral. The diagnostic took 361,225.744 ms; synchronous readback
and serial/reference rendering make that duration unsuitable as FPS evidence.

The recorded asset SHA-256 is
`8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407`
(15,085 vertices, 29,482 triangles, 7 primitives). This establishes serial/OVR4
pixel equivalence for that asset and implementation. The report explicitly
keeps `artwork_validated=false` and `performance_evidence=false`; it does not
verify panel optics, asynchronous pose delivery, later changes, or 30 FPS.
