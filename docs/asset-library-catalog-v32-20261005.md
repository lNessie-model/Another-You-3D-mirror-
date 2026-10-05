# V32 asset library contract

The asset library lists the preserved source heads, scenes and images in the APK. It shows a single authenticated static PNG preview at a time. Preview selection does not change the selected mirror role. Only a `runtime_ready` head linked to the existing verified bundled-role catalog exposes **使用角色**; that action checks the current bundled model and manifest before saving the existing role preference and returning to the role page.

## Metadata schema

`app/src/main/assets/library/catalog.json` is UTF-8 JSON with `schemaVersion: 1` and `entries`. Each entry contains:

| Field | Contract |
| --- | --- |
| `id` | Unique stable slug matching `[a-z0-9][a-z0-9-]{0,63}`. |
| `displayName` | Nonempty display text, at most 80 UTF-16 code units. |
| `type` | `head`, `scene` or `image`. |
| `status` | `static_preview`, `archive` or `runtime_ready`. |
| `sourceLabel` | Nonempty human-readable provenance, at most 160 code units. |
| `notes` | Nonempty status/use explanation, at most 1200 code units. |
| `sourcePath` | AssetManager-relative `library/<id>/<basename>.glb`, or `.png` for an image. |
| `sourceSha256` | Lowercase SHA-256 of the preserved source bytes. |
| `previewPath` | AssetManager-relative `library/<id>/<basename>.png`. |
| `previewSha256` | Lowercase SHA-256 of the static preview bytes. |
| `liveRoleId` | Required only for `runtime_ready`; an existing bundled-role identity. |
| `liveModelSha256` | Required only for `runtime_ready`; exact model hash from that bundled-role entry. |

Paths stay inside their entry's own directory; no nested directories, `..`, backslashes, URLs, percent escapes or filesystem paths are accepted. Basenames match `[a-z0-9][a-z0-9_-]{0,63}`. Text rejects controls and unpaired Unicode surrogates. Unknown fields and duplicate identities are rejected. The catalog contains 1–128 entries and at most 256 KiB; malformed UTF-8 and excessive JSON nesting are rejected.

The preserved original model's `sourceSha256` can differ from the corrected runtime model's `liveModelSha256`. This is intentional: source provenance does not imply facial animation readiness. Static and archive entries cannot contain live activation fields, and scenes/images cannot claim `runtime_ready`.

## Java API and resource ownership

- `AssetLibraryCatalog.read(AssetManager)` returns an immutable `List<Entry>`. It reads the library metadata and, if needed, the lightweight bundled-role catalog. It never opens GLB files or previews while listing.
- `find(List<Entry>, String)` returns a known identity or throws an explicit `IOException`.
- `readPreview(AssetManager, Entry)` verifies the selected preview's encoded SHA and PNG header, with at most 8 MiB encoded data and dimensions 1–2048. The Activity then decodes only that image, downsampled to at most 1024 pixels per side, and displays a visible error if PNG decoding fails.
- `verifiedRuntimeEntry(AssetManager, Entry)` requires a `device_verified` or `legacy_reference` bundled-role entry with the exact live hash. It streams only that role's `character.glb` (at most 32 MiB) and `avatar.json` (at most 256 KiB), checking both against the bundled catalog. It does not read the preserved source model or decode the GLB.

`AssetLibraryActivity` owns one daemon executor, a cancellable task and a lifecycle generation. Metadata, preview decoding and selected-role hashing run off the UI thread. A new selection, pause or destruction cancels the old work; stale decoded bitmaps are recycled, and the displayed bitmap is released on pause/destruction. Preparing a role disables the selector and use button, shows **正在准备角色…可返回取消**, and keeps **返回角色页** and system Back available. Either return action invalidates the owner/token and cancels the pending task before finishing. Verification that completes after a return cannot save a selection or navigate.

Only the short preference transaction is serialized against lifecycle invalidation. The Activity's real `SelectionLifetime` gate checks `active`, generation, finishing and destroyed state before invoking the selection store. If returning happens before that check, the store callback does not run. If the explicit save has already entered, returning waits for that short transaction and retains the completed choice. The long model-hash stage never holds this monitor. There is no renderer, GL thread, camera or pose worker in this screen. A verification/save failure leaves the previous selection intact and is visible to the user.

The screen uses the existing dark metal, wine-red and gold theme, ellipse-safe scroll region and 52dp selector/buttons. It saves the selected library identity across recreation without writing role preferences. Returning to the existing role page lets that page refresh the saved bundled-role selection; opening the library does not start another mirror runtime.

## Verification and release boundaries

Run the focused production-parser tests with the pinned real JSON-java jar:

```powershell
& .\tests\run_asset_library_tests.ps1
& .\tests\run_asset_library_tests.ps1 -AssetsRoot .\app\src\main\assets
& .\tests\run_asset_library_tests.ps1 -Apk 'PATH-TO-FINAL-APK'
& .\tests\run_asset_library_lifecycle_tests.ps1
```

The actual-source/APK mode authenticates every declared source and preview, fully decodes each PNG sequentially, and verifies the exact current model and manifest for each ready entry. No tests substitute a mirror implementation of the parser.

At source handoff on 2026-10-05, the real source-assets run passed **187 checks**, including all **27 entries** (15 heads, 4 scene GLBs, 8 image versions) and the two ready roles. The contract-only portion passed 103 checks. The three Android source files compiled against the actual Android 35 SDK and existing app dependencies. Host and Android compile evidence is recorded under `output/mirror-assets/20261005/production-app-release-audit/`; source-assets logs are under `app/build/asset-library-tests/runs/454293c3-a797-4218-ba23-d1300a1d63d6`.

The cancellable-preparation correction additionally passed **22 lifecycle checks** against the Activity's actual production preference-boundary class. The concurrent tests hold preparation outside the monitor, return before commit, and verify the store callback cannot run; they separately enter a short transaction, return concurrently, and verify the completed explicit selection is retained. Invalidated/superseded/recreated owner tokens, finishing owners and store errors are also covered. This runner compiles the actual Activity with the SDK and existing app dependencies; it does not construct fake Android widgets or duplicate the production gate. Evidence: `app/build/asset-library-lifecycle-tests/runs/1b5033b3-a038-4dc0-a9ba-bc26e3e4797e`.

At source handoff, no V32 APK had been built and device UI/lifecycle verification had not been performed. Final APK and device validation is recorded in [V32 release validation](production-app-v32-20261005.md). Failed first-generation scenes remain archives; valid second-generation scene originals remain static previews until a scene renderer is integrated. The nine additional IP heads remain under facial correction. Library inclusion does not claim that they are ready for live facial capture, and this feature does not resolve the existing camera error or the 30 FPS goal.
