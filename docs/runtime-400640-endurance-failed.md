# v18 endurance attempt: incomplete 30 minutes and completed low-pause 90 seconds

The first 30-minute run did **not** pass the sustained interactive 30 FPS gate. Its collector exited 0 with `collection_status=completed`, but presentation evidence is incomplete: a 20.694-second SurfaceFlinger history gap, a 372.251-second unconfirmed tail, and an incomplete final snapshot. Device events also record the main Activity leaving the foreground before the collector's final stop. Neither a crash nor a thermal cause is established.

The later low-pause collector completed a separate 90-second check with complete active evidence and observed presentation above 30 FPS. That short check validates the collector's completed-run data path; it does not repair the failed attempt or establish a 30-minute pass. The ongoing replacement 30-minute run is excluded from this document and audit.

## Fixed application and workload

Both completed runs used serial `6L32552009566714`, package `com.mirror.bench`, unchanged v18 APK SHA256 `c4cf831de43a80e85d59fe0382e14ef439b803219140c66b2580786d1cc04d84`. They requested `camera_replay`, persistent FBOs, per-frame camera matrices, no requested HOME/pause, no context-release test, no cached camera matrices and no batched avatar. The reported analysis target was 17 FPS and active frame cap 31; these are different from measured presentation FPS.

All saved INTERACTIVE snapshots report actual/configured 16 views at 400×640, 1200×1920 output, the complete built-in GLB (`builtin-guide`, model SHA `8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407`), 478 landmarks and 52 blendshapes. The backend remains RKNN detector/landmarks plus MediaPipe CPU expressions/canonical pose. USB camera 0 acquired live frames while recorded face frames supplied inference; this is not a new live-person quality test. Saved active rows have increasing analysis/frame counters, GL generation/frame generation 1 and readiness true, no source/render/processing errors, zero capture failures/recoveries and `stored_face_images=false`. These facts apply to saved active observations, not the unseen tail.

## Recomputed presentation evidence

| Evidence | First 30-minute attempt | Later low-pause 90-second check |
| --- | ---: | ---: |
| Requested collection / actual host span | 1800 / 1801.188 s | 90 / 91.265 s |
| Resource samples / PSS samples | 1726 / 349 | 89 / 18 |
| Saved status / INTERACTIVE snapshots | 280 / 276 | 19 / 17 |
| Confirmed INTERACTIVE duration | 1416.075 s | 84.916 s |
| Sampled presentation duration | 1393.943 s | 84.847 s |
| Observed interactive presentation FPS | 30.5787183 | 30.5609063 |
| Surface history gap | 20.693818288 s | 0 |
| Unconfirmed tail | 372.251089823 s | 0.442730772 s |
| State-history gaps / collection errors | 0 / 1 | 0 / 0 |
| Complete active evidence / strict 30 FPS verdict | false / false | true / true |

The frozen collector's complete `presentation`, `system` and `interactive_system` objects were independently recomputed and matched each final JSON. Presentation uses the actual second SurfaceFlinger latency column, deduplicated, only inside confirmed INTERACTIVE intervals. A direct timestamp calculation also matched all 39 complete intervals of the first run and all three of the short run. The short intervals measured 30.4421187, 30.6536410 and 30.6178564 FPS. The first run has two additional incomplete intervals; its `rate_is_lower_bound=true` flag and incomplete gate remain intact. Its aggregate above 30 cannot establish the missing history or full-duration result.

The original saved state history has no ring gap, but stops advancing at status sequence 285, monotonic time `180992628911635`. The final read repeats that old status, with no presentation timestamps. An old `INTERACTIVE` status and its small `result_age_ms` are not proof of continued fresh processing: the 372.251-second tail is derived from the latest observed device clock and last confirmed status. No missing frames/states have been fabricated.

## Two separate interruptions and their limits

Zero-based resource samples 1120→1121 have host monotonic starts `373726.906→373752.078`, a 25.172-second polling gap. Surface history is missing in two adjacent confirmed-state intervals: 8.142263735 + 12.551554553 = 20.693818288 seconds. These are observation gaps; they do not establish what the application did during that time. The [host checkpoint benchmark](runtime-collector-low-pause.md) did not reproduce a 25-second pause or establish its cause.

Later device event records show:

| Device wall time, 2026-10-03 | Recorded event |
| --- | --- |
| 17:58:45.695 | `RecentsActivity` restart |
| 17:58:45.755 | `MirrorActivity` pause requested, `userLeaving=true` |
| 17:58:45.828 | Main Activity `performPause` |
| 17:58:45.842 | Launcher `HomeActivity` resumed |
| 18:04:56.731 | Collector's final force-stop / `USER REQUESTED` process exit |

The collector's recorded actions contain only initial force-stop, clear-known-status, start and final force-stop; no requested HOME or resume. The events establish a foreground departure, but do not identify the initiating person/input or prove an app-triggered exit. Exit-info lists the final requested stop, not a crash at the earlier departure. The saved crash buffer contains only two older `rknn_blendshape` probe aborts at 10:35 and 11:03. That buffer does not prove the absence of every possible failure; it does not support calling this event an application crash or thermal crash.

Resource interpretation must follow the confirmed workload. For the first run, confirmed INTERACTIVE samples report whole-machine CPU 78.923%, peak temperature 80°C and peak app PSS 496.695 MiB. The whole-collection CPU mean 64.223% includes the later inactive period and cannot be described as sustained joint-load usage or a performance saving. The later short run began under different temperature/history conditions; it is not a controlled thermal A/B against the long run.

## Low-pause 90-second collector check

The application/APK were unchanged. This check used frozen collector SHA `0fea6fcf7a4721326a6e879dd0ab4bacddff3fade9dbb37a73303925af8b1aca`, `--checkpoint-seconds 0`, with a new exclusive append-only journal. No periodic full analysis/JSON checkpoint was requested. The completed journal has 132 records, 4,232,462 bytes and 89 raw poll records. Maximum observed poll-start gap is 1.266 seconds; the 90-second result does not bound arbitrary host stalls.

Folding journal deltas reproduces the seven non-resource raw lists exactly; final clock mapping reproduces the resource samples and complete presentation/resource objects. Raw journal samples intentionally omit the derived `device_monotonic_ns` that final analysis adds. The final snapshot and independent final force-stop are present. The journal's `end` phase is explicitly `after_final_stop_before_full_report_save`; the separately verified final JSON proves this run's full save completed. Flush is not an fsync/power-loss guarantee. No observation warnings or collection errors occurred. The collector neither pulled the app back to the foreground nor filled missing history.

This proves one completed short collection under the stated workload. Sustained operation, cooling, unexpected foreground departure and the replacement long-run verdict remain separate questions. No new rendering pixels, optical alignment, release build or normalized-expression backend are validated here.

## Evidence and read-only reproduction

Raw base: `E:/tripo/output/mirror-program/20261003/400x640/`.

| Artifact | SHA256 |
| --- | --- |
| `avatar-v18-persistent16-400640-30min.json` | `db8c76595db87d01e78e81c205ba4545a273fe4d2df4ead373d64c5a8a875426` |
| `avatar-v18-persistent16-400640-lowpause-90s.json` | `3d9a4c69b33c7c9a8f93db3da0fee2b188ea00ae056b32e2bf3109ec006f8dec` |
| Short-run `.polls.jsonl` | `16bc284ae00737102911ca233c9938e1481a312d5603209c4900512d4e3fd38b` |
| `v18-post-endurance-events.txt` | `13f40915eb066fc856ce71c0ab77b9449931364e22442db39beb08a25013c0a2` |
| `v18-post-endurance-exit-info.txt` | `932609c6e250d852401cabcb2c488f973af727b166abb60b2ffdc365792d72e4` |
| `v18-post-endurance-crash.txt` | `6d5de0ea627ce01c01775b82be8c29085a8b99effa6d58886102ceaf66d823c5` |
| Original frozen `collector-v18/run_runtime_check.py` | `8a0f55cfca36e8ef101f2d302382b7825d2001ac2cb0f63024097f2acfa2c195` |

The independent host-only audit is `E:/tripo/device-lab/app/build/runtime_endurance_failed_audit.py` (SHA `2421958b49169300c443761d52b92e3c30226cc5fdadf156a066d661aca5cddd`), with result `runtime-endurance-failed-audit-v1.json` in the same directory (SHA `a4b6f74627cc69e3e9cf84722c53f42f139e2edc3d7a2b88c26bf2104edb2779`). It pins both frozen collectors, named terminal raw files and post-run logs, and reads no ongoing long-run file. For read-only reproduction execute its prefix before `with OUTPUT.open(...)`, then compare the resulting `report` dictionary against that audit JSON. The complete script rejects an existing result file. No reproduction step invokes ADB or builds an application.
