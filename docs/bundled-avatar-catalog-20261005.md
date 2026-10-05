# Bundled avatar catalog and selection contract

V31 uses a small APK catalog for bundled roles. It does not replace `AvatarPackageStore` or decode models on the navigation thread. `BundledAvatarCatalog` opens only `avatars/catalog/catalog.json`; the renderer remains responsible for reading and validating the selected GLB and manifest, checking their hashes, and releasing GL resources.

## Java API

All entry points below are static. Catalog and preference failures throw `IOException` and must become a visible error in the caller. Reading or writing these APIs belongs on the caller's background worker because even metadata and `SharedPreferences.commit()` can perform disk I/O.

```java
List<BundledAvatarCatalog.Entry> entries = BundledAvatarCatalog.read(context.getAssets());
String defaultId = BundledAvatarCatalog.defaultId(context.getAssets());
BundledAvatarCatalog.Entry entry = BundledAvatarCatalog.find(entries, selectedId);

boolean explicit = BundledAvatarSelection.hasSelection(context);
String selectedId = BundledAvatarSelection.load(context);
BundledAvatarSelection.save(context, entry.id);
BundledAvatarSelection.clear(context);
```

`read()` returns an unmodifiable list. `find()` rejects invalid or unknown identities; it does not return another role as a successful selection. Every entry has public final fields:

| Type | Fields |
| --- | --- |
| `String` | `id`, `displayName`, `directory`, `modelSha256`, `manifestSha256`, `thumbnail`, `status` |
| `int` | `vertices`, `triangles`, `drawCalls` |
| `long` | `decodedBytes` |

`defaultId()` and `read()` each read a catalog snapshot. `load()` uses one internally parsed snapshot to find its explicit or default identity. Separate calls to `hasSelection()` and `load()` are not an atomic UI transaction; callers should serialize changes through their navigation worker.

## Asset schema and limits

The UTF-8 catalog contains `schemaVersion: 1`, a `defaultId` present in `entries`, and 1–64 entries. Its serialized size is at most 256 KiB. Required string and numeric types are checked, duplicate identities are rejected, and the input nesting is bounded before parsing with the existing `org.json` implementation. This is metadata validation for APK assets; it does not attest to the existence, correctness, or artistic acceptance of the referenced model bytes.

Identities use `[a-z0-9][a-z0-9-]{0,63}`. Each directory must be exactly `avatars/catalog/<id>`, except `builtin-guide` may reference the retained `avatars/builtin-guide`. The thumbnail is a full AssetManager-relative path to a single PNG/JPG/JPEG/WebP inside that role's directory. Absolute paths, traversal, nested thumbnail paths, cross-role paths, and URIs are rejected. SHA-256 values require 64 lowercase hexadecimal characters.

Resource metadata follows the existing production loader ceilings: 20,000 instance vertices, 30,000 triangles, eight draw calls, and 80 MiB decoded bytes. The new corrected-head target remains 20,000 triangles. The legacy guide has 29,482 triangles and is labeled `legacy_reference`; retaining it does not establish that it meets the new-head target. The metadata gate does not substitute for `AvatarAssetLoader`'s actual byte and resource checks.

The first catalog contains `geralt` and `builtin-guide`. Its exact model and manifest hashes and driver-rendered thumbnails are maintained with the asset files. A catalog `status` is descriptive release evidence, not permission to skip model validation or a guarantee of live-camera performance.

## Selection, upgrade, and error behavior

Selection uses a new independent preference file `bundled_avatar_selection_v1`, key `selected_id`. It does not modify existing scene, optical, response, imported-avatar, or device preferences.

An absent key makes `hasSelection()` return false and `load()` return the catalog default without persisting it. This distinction lets the renderer preserve an existing imported role during the first upgrade. A deliberately chosen bundled role is saved explicitly. A successful imported-role activation clears this key; choosing the guide saves `builtin-guide`. Unknown or removed saved identities are errors and are retained for diagnosis instead of silently rewritten.

The renderer's load priority is: explicit debug-private model; explicitly selected bundled role; existing imported current package; bundled default; original guide. Directory/hash inputs cross the existing renderer boundary; the catalog does not own GL decoding or loader state.

`save()` and `clear()` use synchronous `commit()` and throw on a false result or runtime failure. Android can publish an edited value in memory before reporting disk failure, so the implementation attempts to restore the previous presence/value and checks its visible state. Failed rollback persistence is attached to the error. This preserves observable state in the tested failure cases but cannot guarantee durable storage when the filesystem keeps failing or power is lost. Callers must not show a successful switch after an exception.

## Verification

Run from the project root:

```powershell
& .\tests\run_bundled_avatar_tests.ps1 -CatalogPath .\app\src\main\assets\avatars\catalog\catalog.json
```

The runner uses the real production parser and the pinned JSON-java 20240303 host jar (SHA-256 `3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed`). Its default local path is `app/build/avatar-tests/json-20240303.jar`; supply `-JsonJar` for another checked copy. No personal face fixture is required. AssetManager fakes reject any attempted GLB/thumbnail open, and preference fakes reproduce memory publication before failed disk commits.

The initial verified run passed 72 catalog checks and 25 selection checks, including the actual two-entry catalog. Cases cover duplicate/invalid identities, path escape and cross-role rejection, metadata resource bounds, unknown or removed saved IDs, implicit default behavior, successful save/clear, commit failures, and preservation of the old preference state. The two production classes also compiled against the real Android 35 `android.jar` with Java 17. These host checks do not claim device navigation, APK asset completeness, or rendered-camera acceptance; those belong to the complete V31 release evidence.
