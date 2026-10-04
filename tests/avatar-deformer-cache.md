# Constructor-owned deformation source cache

The v7 candidate changes only CPU source reads in `AvatarDeformer`. `AvatarAsset`
already owns ordinary `float[]` / `int[]` arrays and returns read-only heap buffer
views (`isDirect() == false`). The old per-component `FloatBuffer.get(int)` and
`IntBuffer.get(int)` calls were therefore not JNI direct-memory reads. Private
constructor copies remove buffer method/index translation overhead from the hot
loops and pack sparse values contiguously. An ART speedup is a hypothesis until
the same-device comparison is complete; host HotSpot timing does not establish it.

The default constructor and output API are unchanged. Source arrays never escape
the deformer. Only constructor work reads asset buffers. Per-frame positions,
normals, accumulation and upload buffers continue to be reused; the deformer is
still confined to one owner thread, once per pose, with all views sharing output.
The worker's slot count, leases and bulk-copy implementation are unchanged.

## Memory ledger

Measured input: `app/src/main/assets/avatars/builtin-guide/character.glb`, SHA-256
`8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407`:
15,085 vertices, 29,482 triangles, 7 primitives, file 5,501,220 bytes and decoded
asset payload 5,890,656 bytes. The active `recompute-deformed` policy does not
cache authored morph NORMAL buffers.

| Constructor source payload | Bytes per deformer |
| --- | ---: |
| Base POSITION and NORMAL copies | 362,040 |
| Triangle index copies | 353,784 |
| 43 compact sparse + 9 dense nonzero POSITION target copies | 923,128 |
| **New array payload** | **1,638,952 (1.563 MiB)** |
| Existing sparse index maps, unchanged | 237,760 |
| Total source values + sparse index payload | 1,876,712 |

The 52 all-zero MouthInterior targets allocate no delta cache. The current
asynchronous scene retains both its initial synchronous deformer and its worker's
private deformer, so the incremental source payload is twice the per-deformer
amount: 3,277,904 bytes (3.126 MiB). Array/object headers, GC alignment and existing
outputs are excluded; device PSS must still be observed. Asset ownership and its
decoded-array sharing are unchanged. No reflective backing-array access is used.

Copy payload has a 32 MiB per-deformer hard limit; each reservation uses `long`
arithmetic and is checked before allocating the array. Existing optional sparse
index maps retain their independent 4 MiB cap. For the loader's profile, even
the conservative all-dense AUTHORED case is bounded by:

```
base positions + normals:        20,000 * 3 * 4 * 2 =    480,000 bytes
triangle indices:               30,000 * 3 * 4     =    360,000 bytes
POSITION + NORMAL morph copies: 20,000 * 3 * 4 * 64 * 2 = 30,720,000 bytes
total source copies:                                  31,560,000 bytes
```

This bound uses total declared geometry, not decoded accessor uniqueness, and
therefore also covers reused accessor arrays copied by different primitives.
Compact sparse values only lower this bound. It is below 32 MiB; sparse maps and
mutable outputs are separate budgets. A package-visible lower-limit constructor
and payload counter allow deterministic boundary tests without a large allocation.

## Exact arithmetic contract

- Base values are copied without conversion; dense morph arrays retain every
  float, including signed zero and subnormals.
- The existing sparse decision, `nonzero < components / 4` subject to the shared
  4 MiB index limit, is unchanged. Nonzero remains `value != 0`, so both signs of
  zero are omitted on sparse paths. Compact values keep their ascending original
  component order, paired with the original ascending index map.
- Targets accumulate in the same order. Each weight is widened to `double` before
  multiplication and addition, exactly as previously. No FMA, float accumulation,
  reassociation, epsilon pruning, triangle reorder or normal approximation is added.
- Position and authored-normal finite/overflow checks, area-weighted normal sums,
  degenerate fallback and normalization remain unchanged. A failed prepare still
  preserves all committed buffers, weights and revisions for the affected mesh.

## Regression and device gate

```powershell
./tests/run_avatar_tests.ps1 -JavaHome 'C:/Program Files/Java/jdk-17' `
    -AssetPath app/src/main/assets/avatars/builtin-guide/character.glb
./tests/run_avatar_worker_tests.ps1 -JavaHome 'C:/Program Files/Java/jdk-17' `
    -AssetPath app/src/main/assets/avatars/builtin-guide/character.glb
```

Observed host results for the SHA above:

- 368 loader assertions; 42 deformation assertions (analytic geometry, rollback,
  finite failure, read-only output and no per-update allocation); 319 actual-asset
  numeric states.
- 3,893 old-path golden checks, including 1,028 actual-asset single/extreme/seeded
  snapshots, compare all positions, normals and interleaved output bitwise.
- 5,309 cache tests: exact payload accounting and pre-allocation budget rejection,
  both normal policies, sparse and dense targets, signed zero, subnormal and
  negative weights. There are another 672 actual-asset snapshots in this suite.
- 55 worker assertions plus 64 actual-asset changing inputs. Every matrix and
  primitive matches synchronous output bitwise, including bytes of an old held
  lease while subsequent results are generated.

The new budget tests were run first and failed because the new constructor/counter
did not yet exist, then passed with the implementation. Existing independent
analytic tests and the frozen numerical reference guard behavior independently
of the payload-counting test.

Next device gate: same exact asset, replay, 20 views at **320x576**, matching the
actual v6 performance capture and its panel/output parameters. First compare
synchronous and asynchronous modes in one v7 APK, then compare v7 asynchronous
against v6 asynchronous at that same workload. Run **400x720 separately** as a
quality/performance baseline; do not treat it as the same-load v6/v7 comparison.
Record actual presented FPS,
morph mean, CPU/GPU/NPU, face FPS, pose age, error latch, and PSS. This change alone
does not certify 30 FPS or improved visual quality. If the measured CPU/morph gain
does not justify the resident-memory increase, retain the independently verified
worker bulk-copy optimization and revert only this cache slice.
