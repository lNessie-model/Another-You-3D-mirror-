# Five-tap device validation — complete diagnostic, accuracy failed

Two isolated runs of the five-output model each completed five warmups and all 92 measurement cases with native exit 0. Their five raw output files are byte-identical. Input echo exactly matches the expected FLOAT16 conversion, but final52 fails the unchanged original-TFLite accuracy gate by a large margin. **Do not integrate this model into the application.** Both checker invocations returned 1 with `status=diagnostic_complete`, `application_eligible=false`, and `performance_evidence=false`.

Raw evidence: `E:/tripo/output/mirror-program/20261003/blendshape-five-tap-device-v1`. Frozen bundle: `E:/tripo/device-lab/app/build/blendshape-five-tap-bundle-v1`. The diagnostic contract and reproducible build/prepare/run/check commands are in [the five-tap tool specification](blendshape-five-tap-diagnostic.md). The original full-model failure is documented in [v4 accuracy validation](blendshape-device-accuracy-v4.md).

This audit independently read the raw files with Python/NumPy, recomputed SHA256 and every tensor's error statistics, and checked API event pairs, fixture offsets and extrema. It did not call the checker implementation. All reported tap max/MAE results agree with the saved `comparison.json`; the original-TFLite max error agrees exactly. No device commands or tool changes were made for this audit.

## Evidence and execution integrity

All ten bundle files and all eight source-provenance files match the frozen manifest. The device's before/after lists both match the model, helper, input and existing vendor-runtime pins. Each of the five pulled output files and the pulled `report.jsonl` matches its after-run hash. All 92 input and original-TFLite reference records match their individual fixture hashes. Raw file lengths are exact, with no extra or missing floats.

| Artifact | SHA256 |
|---|---|
| Bundle manifest | `6680bfe12bc78b4ecbeb7a4e4ce5cb23d2664391189c05318fb312e657dd68fb` |
| RKNN model | `2b0bc99450f094cfea259765ca5657905d4e1498f39824faab1012422372cf70` |
| ARM64 helper | `fa587b8a2e26b94666abae99ccd955d579ed7ad5e73f8b33070c8e70632f1e46` |
| Input records | `36aea412c6765dfbe7222db47253e2e3c734ef6366a2578c0ede282fb4cc13fc` |
| Vendor librknnrt.so | `01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de` |
| before.sha256 | `4fb1e780f9b2d1d14fe5aeb0fec58f224a5a00cea03e03906c21c726807dfa8e` |
| after.sha256 | `767b456a38c0277a410443bc5f53d386fb88a77370e586956ad65f31a2eaff47` |
| report.jsonl | `5bc487df89c3fbcacd4cea2031132cb1aec40227304a7c4ef9598a3efff89d1e` |
| native-exit.json | `124fd50cb6d223cdd18fedc166d00af7254199b6e6dedf9be82f71db87af6d01` |
| comparison.json | `90bd26175a622b401c992f3c94c6db76f1b04a40ca39ea03ce0b89a68ac576b4` |
| commands.json | `ddfea5edb6ef35d51ef0aa8ec91c4ef4d334cbd19acc5e5cc0f3fd2c9f3ef654` |

The 1,390 JSONL records contain 398 adjacent API begin/end pairs with consecutive sequence values 0–397, all return codes 0: init once, SDK query once, IO-count query once, input-attribute query once, five output-attribute queries, 97 each of inputs_set/run/outputs_get/outputs_release, and destroy once. There are exactly 97 complete iteration rows and 485 valid tensor-result rows: five warmups plus 92 measured cases, each with five outputs in fixed order. Every measured tensor's finite count and extrema agree with its raw float record; all 85,468 measured float values are finite.

The finish row reports all 92 measurements completed and destroy 0. The independently captured process status is also 0, so success does not rely on the footer alone. In `commands.json`, the only nonzero ADB command is the expected preflight `test -e` returning 1 for the new isolated directory; it is not a failed push or inference. Native stderr is empty. These observations establish a complete diagnostic execution, not numerical correctness.

## Observed IO contract

SDK is `1.3.0 (9b36d4d74@2022-05-04T20:16:47)`, driver `0.7.2`. Initialization returned 0 in 201.549271 ms. The queried input is `[1,146,2]`, 292 elements, FLOAT16/584 bytes, format 3. The feed requests FLOAT32, format 3, pass-through 0 and want-float 1, keeping adjacent x/y pixel coordinates unchanged.

| Output | Exact queried shape | Query format | Model bytes per case | Returned F32 bytes per case | Raw file bytes |
|---|---|---:|---:|---:|---:|
| input_echo | `[1,146,2]` | 3 | 584 | 1,168 | 107,456 |
| centered | `[1,146,2,1]` | 0 | 584 | 1,168 | 107,456 |
| scale | `[1,1,1,1]` | 0 | 2 | 4 | 368 |
| normalized | `[1,146,2,1]` | 0 | 584 | 1,168 | 107,456 |
| final52 | `[52]` | 3 | 104 | 208 | 19,136 |

All queried output names match the fixed contract. All types are FLOAT16, with `qnt_type=2`, zero point 0 and scale 1 as recorded by the runtime. The centered/scale/normalized trailing singleton dimensions are the explicitly allowed compiler-observed forms; the helper did not transpose or reinterpret the returned sequence. Measured offsets are exactly `case_index * tensor_elements`, separately in each file.

## Numerical observations

All 26,864 echo values are **bit-for-bit equal** to input FLOAT32 → FLOAT16 → FLOAT32. There are zero mismatched bits at the value level. This supports the feed conversion and coordinate order for this diagnostic graph and runtime path. Comparing echo to unrounded FP32 coordinates naturally shows the rounding errors below; that difference is not an echo-contract failure.

Intermediate comparisons below use the frozen diagnostic ONNX FP32 tensors. Each cell is `maximum absolute error / mean absolute error`. They are measurements without an intermediate acceptance threshold.

| Tap | All 92 | Recorded 72 | Synthetic 20 |
|---|---|---|---|
| input_echo vs unrounded input | 0.249877930 / 0.045587080 | 0.125000000 / 0.047722249 | 0.249877930 / 0.037900472 |
| centered | 0.528564453 / 0.084865077 | 0.338378906 / 0.087690496 | 0.528564453 / 0.074693569 |
| scale | 0.070976257 / 0.026500517 | 0.070976257 / 0.031163428 | 0.049850464 / 0.009714037 |
| normalized | 0.306557894 / 0.004458978 | 0.006179929 / 0.001240053 | 0.306557894 / 0.016047106 |
| final52 vs diagnostic ONNX | 0.970285177 / 0.212713674 | 0.913154960 / 0.203826310 | 0.970285177 / 0.244708182 |

The original-TFLite gate remains maxAbs ≤ 0.01, global MAE ≤ 0.002, all values finite and inside [0,1] ± 1e-5. It was not relaxed for this diagnostic run.

| final52 versus original TFLite | Max absolute error | MAE | RMSE |
|---|---:|---:|---:|
| All 92 × 52 | 0.970285177230835 | 0.2127136734394552 | 0.33675153969755517 |
| Recorded 72 × 52 | 0.9131550788879395 | 0.20382630763291373 | 0.330749387344395 |
| Synthetic 20 × 52 | 0.970285177230835 | 0.2447081903430045 | 0.3575259314016359 |

All 4,784 final coefficients are finite and in range, with minimum 0.003021240234375 and maximum 0.9970703125. **Accuracy still fails:** 3,655 coefficients exceed absolute error 0.01; every one of the 92 cases and every one of the 52 channels has at least one such error. The worst result is fixture index 88 (`synthetic-16`), channel 11 (`eyeLookDownLeft`): actual 0.012786865234375, original reference 0.98307204246521. The diagnostic ONNX final52 comparison also fails both error gates; the near-identical reference error is consistent with its prior FP32 equivalence, not device correctness.

| Raw tensor file | SHA256 |
|---|---|
| output-input_echo.f32 | `cafd928aaf31bee959da4185568c8edaff69498e2fc1da294c7452eb7181aff8` |
| output-centered.f32 | `2cfb61008cd72320b151d7de7ac30a6c263d02226a129dba5515c2e43db6afd7` |
| output-scale.f32 | `cc389b6e49a5d83fdaff87ba10b1db8d513db21ab3240ee3c4a8f6f513f3460b` |
| output-normalized.f32 | `dfaae9a533da6e8ff6316951df271a4fba5c891ff4a6bd8e149e0cd2a3393e44` |
| output-final52.f32 | `67daa1668ee95319a63b6fb428d0fda03179a38a6c2cbf4e5fd69431afb6b216` |

## Independent second-run check

Repeat evidence is in `E:/tripo/output/mirror-program/20261003/blendshape-five-tap-device-v2`. This audit independently checked its actual native exit 0, all before/after input/model/helper/vendor-runtime pins, the five pulled output hashes and report hash, and the unchanged local bundle/source hashes. It again has 1,390 rows, 398 successful API begin/end pairs, 97 complete iterations and 485 valid tensor records. Removing only each API end's elapsed-time field makes the entire first and second event streams equal, including queried attributes, return codes, phases, sequence, offsets, extrema and successful finish.

All five raw output files are byte-for-byte equal across the two runs, including the 4,784 wrong final52 values. Their SHA256 values are the output hashes in the preceding table. Thus the error pattern is reproducible in these two runs of this frozen diagnostic model; repetition does not improve accuracy or establish correctness of the original full graph. The same original-TFLite/diagnostic-ONNX failure metrics apply to both runs.

| Second-run artifact | SHA256 |
|---|---|
| before.sha256 | `25b6322781a2e673610444550818b58e27c45921e81b8ed6347ff3e725c0ab62` |
| after.sha256 | `2d792da26f8a19c053b57ada96635eb5728fc62253331b423afbfdc2484b7f32` |
| report.jsonl | `9b0ef8c1dbe1dc65ef2f372eb5656a8351669a0a06dd4c1f93f419c129ce7fa4` |
| native-exit.json | `124fd50cb6d223cdd18fedc166d00af7254199b6e6dedf9be82f71db87af6d01` |
| comparison.json | `906f01ab51126b809145f597bfc0fc7400bab7e3c36236a92246e810cacabd03` |
| commands.json | `e8085f16ea1b87871b9783a8b148b08cf9ed09cea2a022af703bb51d182b9dc9` |

## Instrumentation and performance limits

Against the original failed v4 full-model actual output (SHA256 `66b35d13c997d989811bc3b9cd5477c4b2334939349673ea4fa51f45df3bcd11`), the five-tap final52 has max difference 0.952362060546875 and MAE 0.15917540633160135. 4,205 of 4,784 coefficients differ at the float-bit level, and all 92 cases change. The diagnostic graph therefore changes the device failure pattern materially, even though its ONNX final52 was byte-identical before RKNN compilation.

This prevents attributing the original full model's failure to the first observed tap difference without further evidence. Echo correctness excludes coordinate reordering in the observed diagnostic echo path, but does not prove all intermediate operations or the original v4 graph correct. Small recorded-input normalization differences and larger synthetic normalization differences are not sufficient to identify an operator fault or to declare the later network sound. Simulator comparisons, if used next, must retain this instrumentation boundary and remain separate from real-device accuracy acceptance.

Mean measured API durations were inputs_set 0.041958750 ms, run 11.851070957 ms, outputs_get 0.297607783 ms and outputs_release 0.001826076 ms. These are logged diagnostic timings with five returned tensors; they are not valid model-performance gains, NPU-only time, NPU utilization, concurrent render FPS, or end-to-end latency. The final output is wrong, the graph differs from production, and the instrumentation adds work. No application model, driver or system setting is accepted or changed by this result.

## Restored application boundary

After the repeat, the operator cold-started the existing v14 application. Saved `E:/tripo/output/mirror-program/20261003/v14-restored-after-five-tap-repeat-status.json` has SHA256 `b731f3c125385a7a70abaa69a976ecfd414d5bf50e708d88f964dbcdc7c63dae`. It records session `d3b1c987-8882-4907-b4f8-91b40009e78d`, status sequence 25, input `camera`, Camera2 ID 0, 352 submitted/completed frames, and 1,184 rendered frames. General/source/renderer/control errors are empty, with render and processing faults false. This is restoration evidence at that saved snapshot.

The restored state is WAITING, with no detected face and 3.006 analysis frames/s over its input session. It proves that the existing live camera/processing/rendering paths resumed; it does not establish face-driven interaction, 30 FPS performance, long-term stability, or numerical correctness of any new RKNN expression model. The diagnostic candidate remains isolated from the application's default model.
