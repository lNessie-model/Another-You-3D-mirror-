# Offline avatar package store

`AvatarPackageStore.java` is a standalone storage/validation layer. The SAF picker, Activity integration, GPU preparation, visible switching and Android device recovery checks remain separate work. It does not change the existing app `minSdk=24`, install anything, upload assets, convert formats or add texture/skin support.

## API and ownership

Call on a background worker with an application-private directory, for example `open(new File(context.getFilesDir(), "avatars"), Build.VERSION.SDK_INT)`. Import currently requires **Android 11 / API 30** because the existing loader/rig also uses collection factories such as `Set.of`; NIO file APIs alone would require API26. The public facade accepts/returns `File`, rejects older API values before filesystem access, and has no `java/nio/file` constant-pool linkage. Actual API24 verifier behavior remains a device check. See the official [Android Map factories](https://developer.android.com/reference/java/util/Map#of(K,%20V)) and [Files API](https://developer.android.com/reference/java/nio/file/Files).

- `importZip(InputStream)` returns `LoadedPackage` containing a store-generated `Ticket`, the real parsed `AvatarAsset`, its `AvatarRig`, conservative `AvatarFraming`, validated `manifestJson`, a validation report and possible deferred-cleanup warnings. The manifest string is the exact bounded UTF-8 data covered by the ticket's SHA; the UI must not reread a path outside the store to build a scene. It publishes a **candidate only**. The caller owns and closes the SAF stream; the store never closes it. Thread interruption cancels between bounded reads/work phases. A provider blocked inside `read` may require the caller to close/cancel that stream.
- Prepare the candidate on the GL owner, check its first frame, then call `activate(loaded.ticket)`. The ticket binds the generated package UUID plus model, manifest and all-file SHA-256 values. Activation rechecks bytes and current retention; a superseded candidate cannot activate another package. `LoadedPackage.rig` is mutable and must have one runtime owner.
- `readCurrent()`, `readPrevious()`, `readCandidate()` return a newly parsed/validated package, or `null` for an absent slot. A null current means APK builtin. `load(packageId)` accepts only a currently retained UUID, not a manifest id or user path. Read errors stay explicit; UI may show the error and choose the APK builtin without deleting evidence.
- `readCatalog()` returns lightweight `Catalog.current/previous/candidate` summaries: packageId, displayName, coverage, modelSha256, vertices, triangles. These are stored report metadata, not a fresh byte/rig validation and not an activation ticket. Use them to list packages, then load only the selected package for preview instead of retaining three decoded assets.
- To roll back, call `readPrevious()`, prepare that exact result on the GPU, then `restorePrevious(expectedTicket)`. It rejects if the previous slot changed. `activateBuiltin()` selects the APK builtin while keeping the former current as previous. `discard(ticket)` removes only the current candidate from the catalog; it cannot remove current/previous.
- `recover()` removes abandoned staging/archive/pointer-temporary files and unreferenced store-generated package directories. Invoke after opening the store and before loading runtime assets. A corrupt catalog fails closed before deleting any packages.

Every operation uses a nonblocking in-process guard and a cross-process `FileLock`. Busy returns `BusyException` for a bounded UI retry, without waiting. The in-process guard runs before opening another lock descriptor; this avoids platforms where closing one descriptor can release another lock held by the same process. Direct concurrent writes by the same application UID are outside the storage contract.

## ZIP profile and budgets

The import format now explicitly permits one local ZIP, a deliberate extension to the earlier design's separate-file-only entrance. Only these exact, case-sensitive, flat filenames are accepted; no directory entries, aliases, duplicate names, extra entries or executable metadata are used:

| File | Required | Expanded bound |
| --- | --- | --- |
| `character.glb` | Yes | 32 MiB |
| `avatar.json` | Yes | 256 KiB |
| `README.md` | No | 64 KiB |
| `LICENSE.txt` | No, but required if referenced by manifest license | 64 KiB |
| `thumbnail.png` | No | 1 MiB |

ZIP input is bounded at 36 MiB, total expanded data at 34 MiB, and entry count at five. The ZIP is copied once to private storage, then its central directory is opened with `ZipFile`; all entries are streamed with actual byte counts and independently calculated CRC32. Extraction always creates ordinary files and does not honor ZIP link/permission metadata. Exact filename allowlisting prevents ZIP paths from escaping the private staging directory. Existing filesystem symlinks are not followed when reading package files or cleaning store-owned flat directories.

Manifest UTF-8 must be valid. A strict JSON precheck enforces one complete object, standard quoted keys/strings, number/literal syntax, decoded-key uniqueness, at most 48 nested value levels and 30,000 values before `org.json` runs. This rejects Android/host lenient parser forms such as single quotes, comments and trailing objects. The model name must be `character.glb`; `modelSha256` is mandatory and must match its actual bytes. A manifest license file may reference only an included `LICENSE.txt`.

The existing production loader and rig perform their full profile checks, followed by neutral deformation and continuous geometry bounds. Validation reports distinguish full **source coverage** from partial coverage; neither is visual acceptance. Texture/skin/GPU/artwork checks remain false. Optional README/LICENSE are opaque data; the thumbnail is only stored within its byte budget and **is not decoded or certified as a safe image**. Future UI must inspect dimensions and impose decode limits before displaying it.

At most current, previous and one candidate are retained after import/recovery. Replacing a candidate can temporarily retain three old packages plus a new expanded candidate and ZIP: at most **172 MiB plus small reports/catalog/lock files**. New import requires at least 74 MiB usable free space (36+34+4 reserve), while actual write failures still abort safely. The GLB loader has its separate 32 MiB decoded budget; deformer source cache has another 32 MiB bound. These are payload limits, not a promise of 32 MiB total heap or simultaneous GPU scene memory. The UI must retire obsolete loaded assets/workers and apply the runtime's own candidate/GL memory budget.

The Android implementation now reads the canonical private store filesystem with [`StatFs.getAvailableBytes()`](https://developer.android.com/reference/android/os/StatFs#getAvailableBytes()), which reports bytes available to applications, excluding reserved blocks. It does not query NIO `Files.getFileStore`: the first Android 11 SAF attempt reached this older space check and failed with `SecurityException: getFileStore` before ZIP validation. A failed statistic remains an explicit `IOException` with its original cause, and zero/negative/insufficient available bytes reject before consuming provider input. No storage permission or budget was relaxed. A successful precheck does not reserve space against other writers; subsequent write errors still abort through the existing transaction cleanup.

## Commit and crash boundary

1. Under the guard, parse the existing catalog and clean unreferenced leftovers. Current/previous/candidate are preserved.
2. Copy ZIP to `incoming.zip`, safely expand into `staging`, validate actual GLB/rig/deformation/framing, and write `validation.json`.
3. Force each new file to storage, then atomically rename staging to a fresh UUID directory. Atomically replace `state.json` to publish that UUID as candidate. `ATOMIC_MOVE` is mandatory; an unsupported provider fails, with no copy/delete fallback.
4. Only explicit activation changes current. The former current becomes previous. Later import/recovery removes unreferenced generations.

An exception before catalog publication leaves the prior selection intact and attempts cleanup, retaining cleanup failures as suppressed diagnostics. A process death before the rename/catalog commit can leave staging or an unreferenced UUID; `recover()` removes it. After commit, candidate publication is successful even if orphan cleanup is deferred, and the returned warning makes that distinction visible. Catalog corruption is never interpreted as an empty store for cleanup.

Atomic namespace visibility does not claim sudden-power-loss durability: files are forced, but this portable Java implementation does not fsync the containing directory. Android storage/provider atomic replacement, process-kill recovery and power-loss behavior need separate device validation. A final lock-release I/O error can occur after a commit; reread the catalog before retrying an uncertain outcome. The semantics follow the official [Files.move contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)).

## Host validation

Run without ADB/Gradle/network:

```powershell
./tests/run_avatar_package_tests.ps1 -JavaHome 'C:\Program Files\Java\jdk-17' -AssetPath 'E:\tripo\device-lab\app\src\main\assets\avatars\builtin-guide\character.glb'
```

The runner verifies the same pinned real JSON-java jar used by the loader tests, then compiles production store/loader/rig/deformer/bounds and the filesystem test. Tests use real ZIP/GLB bytes and the actual exported model; there is no mocked model validator. The only Android boundary substitute is `tests/stubs/android/os/StatFs.java`, compiled only by host runners. Its default uses the host filesystem's usable space; overrides supply exact space counts or failures. It is outside Android source sets and is not packaged into the APK. Test outputs remain under ignored `app/build/avatar-package-tests/runs/`.

Coverage includes publication without activation, lightweight catalog summaries, SHA-bound manifest strings, token-bound activation/rollback, stale/tampered candidate rejection, current/previous retention, malformed/path/duplicate/oversized/truncated/CRC-invalid ZIPs, compressed-input and expansion bounds, strict manifest grammar/depth and UTF-8, invalid rig/hash/material profile, cancellation/caller stream ownership, cancellation immediately before current/builtin/previous pointer publication, nonblocking same-process/second-instance lock behavior, injected failures before directory/catalog commits, abandoned temp/orphan recovery and corrupt-catalog fail-closed behavior. The trailing-object case and single-quote hidden-depth case both produced real failures before their respective guards were repaired.

The StatFs correction ran RED against the unchanged production space check: `available=77594623` (74 MiB minus one byte) unexpectedly imported instead of rejecting. GREEN passed **139 checks**, including exact 74 MiB, more than 2 GiB, zero/negative available bytes, failed statistics retaining their cause, unconsumed input, unchanged current/candidate and absent temporary files. This host boundary test does not reproduce Android's actual system call; the original `SecurityException` is the device evidence. Actual Android StatFs and SAF import must be rerun by the root after the corrected APK build.

Android35 compilation is assigned to the root's normal Gradle build (the earlier v8 build succeeded before the device exposed this platform behavior). The Manager runner separately checks production sources against the real SDK before using the StatFs substitute in host tests. No SDK workaround or device test was performed by this slice. The store remains a CPU/storage prerequisite for formal import, not its full UI/GPU/product acceptance.
