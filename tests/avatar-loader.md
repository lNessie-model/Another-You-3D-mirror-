# GLB loader host tests and current profile

`AvatarGlbLoaderTest.java` constructs real little-endian GLB 2.0 bytes, including JSON and BIN chunks. It does not mock JSON or call Android, ADB, Gradle, or GL. The deterministic byte-mutation loop accepts only a valid parsed asset or the documented `FormatException`; unchecked parser failures fail the test.

Run from the repository:

```powershell
./tests/run_avatar_tests.ps1 -JavaHome 'C:\Program Files\Java\jdk-17'
# Also parse an actual asset with the identical production loader:
./tests/run_avatar_tests.ps1 -JavaHome 'C:\Program Files\Java\jdk-17' -AssetPath 'path/to/character.glb'
```

The script never downloads dependencies. It expects `app/build/avatar-tests/json-20240303.jar`, or an explicit `-JsonJar` path. This is an ignored build artifact, not a production Gradle dependency.

- Maven coordinates: `org.json:json:20240303`.
- Exact source: [Maven Central JSON-java 20240303](https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar).
- SHA-256: `3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed`.
- The embedded Maven POM declares **Public Domain**, with [upstream license](https://github.com/stleary/JSON-java/blob/master/LICENSE). Retain that provenance if distributing the test dependency. No JSON-java code or binary is copied into the APK or committed here.
- Production source also compiles against the existing Android 35 `android.jar`. That jar alone does not execute JSON on the JVM; its methods are stubs. Android runtime JSON parity remains a separate device check.

## Implemented support

- GLB 2.0: exact header length, one JSON chunk then one embedded BIN chunk, strict UTF-8/JSON, valid 0–3 byte BIN zero padding; no external resource reads.
- Triangle meshes: FLOAT POSITION/NORMAL/TEXCOORD_0, optional COLOR_0 (float or normalized unsigned byte/short), indexed unsigned byte/short/int or indexless triangles. Interleaved stride and accessor/bufferView offsets are honored. Arrays are decoded once per accessor and exposed through read-only buffers.
- Up to 64 POSITION/NORMAL morph targets per mesh, including sparse morph data; target counts consistent across primitives. Exact `mesh.extras.targetNames` names are retained; absent names remain unnamed and require an explicit index-based manifest. Missing base normals remain null and require recomputation before lit rendering.
- Rigid node trees: column-major matrix or TRS, parent × local world matrices, finite unit quaternions, positive nonuniform scales. Cycles, shared children, reflected/sheared matrices and conflicting TRS/matrix are rejected. Default mesh/node morph weights are applied for finite/overflow validation before world-position checks. Scene geometry budgets include mesh instances.
- Explicit opaque factor-only materials: base color, roughness and nonmetallic lit materials, or `KHR_materials_unlit`. This parses material data; it does not implement a lighting shader. Each primitive must name a supported material; glTF's implicit metallic default is deliberately rejected in this profile.
- Hard resource limits: file 32 MiB, JSON 1 MiB / 30,000 values / 48 levels, decoded array budget 32 MiB (caller may lower it), at most 128 nodes, 8 mesh primitives, 20,000 vertices and 30,000 triangles; scene instances are checked as well. Input bytes and bounded JSON DOM are separate transient storage from `decodedBytes()`.

## Explicitly rejected in this slice

Skin/joints, animation, cameras, textures/images/samplers (including otherwise valid embedded PNG/JPEG), alpha blend/mask, double-sided materials, emissive factors, metallic lit materials, tangent and unimplemented attributes, sparse index buffers, external/data URIs, unknown extensions (including optional extensions), extra GLB chunks, negative/reflected/degenerate scales and shear. Rejections provide a field or semantic in `FormatException`.

Texture decoding and skin support are future explicit profile extensions; this limited loader is not a claim of complete glTF conformance. Reference: [Khronos glTF 2.0 specification](https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html). There is no renderer/Activity integration, full facial rig coverage validation, user file picker or recognizable character in this change.

Callers keep ownership of input streams. Returned asset buffers have independent positions and cannot be written; mutating input file bytes after load does not change the parsed asset. Package-private model constructors accept only fresh parser-owned arrays. Runtime morph evaluation and inverse-transpose normal transforms are the subsequent renderer/deformer's responsibility.

The stream entry reads and validates the GLB header before allocating one declared-size input array; it does not keep an additional growable copy of the whole file. The loader validates the default deformed pose, not every possible future facial coefficient combination: the runtime deformer must check finite results each update as well.

## CPU deformer cache

The same test script also runs `AvatarDeformerTest`: analytic triangle normals, area weighting, degenerate/tiny geometry, authored normal deltas, read-only results, all-primitive rollback on overflow, recovery, and HotSpot allocated-byte checks for 10,000 unchanged and 10,000 changed updates. With `-AssetPath`, every target is additionally exercised at 0/0.5/1, then combined weights; output position finiteness and unit normals are checked. The loader prints the SHA-256 of the exact bytes parsed. These numeric tests do not prove the face's appearance or absence of interpenetration.

`AvatarDeformer(asset, policy)` initially commits the mesh defaults. `updateMesh(meshIndex, fullWeights)` returns whether geometry changed. Changes only to all-zero effective deltas commit the new weights without recomputing normals or changing output revisions. All affected primitives of that mesh commit together after validation; unaffected primitives retain their revisions. `primitives(meshIndex)` exposes stable output objects with read-only position/normal views and a direct native-order interleaved xyz+nxyz view. Cache the upload view and check `revision()` to avoid redundant uploads. Views are backed by the latest mutable cache, not frozen snapshots: one thread must own updates and uploads. Independent node instances requiring different weights need separate caches or explicit rejection in the scene layer.

`RECOMPUTE_DEFORMED` uses double-precision, area-weighted face-normal sums on shared indexed vertices. Degenerate/isolated vertices use a normalized base normal, or +Z if no usable base normal exists. It deliberately does not weld duplicate vertices across seams or primitive boundaries; the asset's authored topology controls continuity. `AUTHORED` requires base normals and normal deltas for each positional morph, then normalizes the blended vectors. Runtime overflow fails before publishing any part of the mesh.

Base and target buffers are cached from the immutable asset. Optional sparse component-index maps are capped at 4 MiB; dense targets retain the original read-only buffers. Position/normal staging, accumulation and direct upload buffers are allocated once. Neither unchanged nor changed successful updates allocate large arrays. Scene/rig matrices and inverse-transpose normal transforms remain external; all views share each committed mesh deformation.

The optimized commit fills one reusable heap interleaved array and performs one bulk `FloatBuffer.put`, instead of a direct-buffer call for every float. This adds 24 bytes per vertex of fixed storage. `AvatarDeformerOptimizationTest` compares position, normal and packed upload buffers bitwise with the frozen pre-optimization `AvatarDeformerReference` (source SHA-256 `93a9fa2cfbe81b16c8ee40a1a3637b785c8d52681bfd3c248490f3e871d03d36`). The original analytic area-normal/overflow tests remain independent of that copied reference. The frozen 15,005-vertex GLB passes 1,010 single-target, extreme and seeded random mesh snapshots; with synthetic no-op fixtures, 3,839 checks pass. Allocation tests warm the JVM bulk-put path before measuring and retain the 256-byte total limit for each 10,000-update measurement window.

`AvatarPoseWorker` is the separate bounded handoff for asynchronous use; see [avatar-worker.md](avatar-worker.md). Compare synchronous/asynchronous modes in the same APK using this same deformer before attributing a device improvement to thread scheduling.
