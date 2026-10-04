# Normalized suffix: exact observed tensor metadata v2

This is a host-only revision of the evidence contract, not a new model or a changed numerical threshold. The original v1 wrapper/bundle/freeze and both original `comparison.json` rejection reports remain unchanged.

## Reason and evidence

Both completed isolated runs `output/mirror-program/20261003/blendshape-normalized-suffix-device-v1` and `-v2` report native exit 0. Their input and output are `FLOAT16` (`type=1`), `qnt_type=2`, `zp=0`, `scale=1`. The original v1 checker expected `qnt_type=0` without actual-board evidence, so it correctly stopped at its strict attribute gate; that assumption was incorrect for this runtime. No v1 pass is claimed.

The unchanged SDK header `native/vendor/rknn/rknn_api.h` defines tensor data type and quantization type separately: `FLOAT16=1`, `QNT_AFFINE_ASYMMETRIC=2`. The same vendor SHA `01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de` and old `blendshape-device-v4` and `blendshape-all-ln-negmean-copy-device-v1` logs already report this combination. This establishes the exact observed metadata contract for this fixed model/runtime; it does not imply general handling for arbitrary quantized tensors. No scaling, casting or output correction is added by v2.

## Strict v2 contract

New files: `scripts/prepare_normalized_suffix_bundle_v2.py`, `tests/test_normalized_suffix_bundle_v2.py`. The v2 source is a copy of the frozen v1 wrapper with a different bundle kind, three additional provenance payloads (v1 wrapper and the two actual attribute logs), and the complete observed attribute table. All original model/input/reference/helper pins, 20 source pins, preprocessing/source checks, external native exit and the frozen original core comparator remain unchanged. The new manifest embeds the exact table, and validation checks it again against the code. v1 and v2 bundles are distinct; neither accepts the other's manifest.

| Field | Input | Output |
| --- | --- | --- |
| name | `model_1/tf.math.truediv_1/truediv` | `StatefulPartitionedCall:0` |
| rank / dimensions | 3 / `[1,146,2]` | 1 / `[52]` |
| elements / size / size_with_stride | 292 / 584 / 584 | 52 / 104 / 104 |
| type / fmt | 1 / 3 | 1 / 3 |
| qnt_type / zp / scale | 2 / 0 / 1 | 2 / 0 / 1 |
| index / rc / w_stride | 0 / 0 / 0 | 0 / 0 / 0 |

Both complete attribute dictionaries must match, including event/kind and key set. Integer fields and dimensions reject booleans; `scale` is numeric exactly 1, never boolean. Type, quantization, nonzero zero-point, nonunit scale, stride, names, singleton aliases, omissions and unknown fields are not accepted. Feed remains 292 adjacent normalized x/y FLOAT32 values, queried `fmt=3`, `pass_through=0`, and output `want_float=1`; native binary/API/97-iteration sequence have not changed.

Tests first failed because the v2 module did not exist, then passed 10 tests. They retain the original eight provenance/API/exit/hash/numeric cases and add both-input/output quantization mutations, wrong/missing `zp`/`scale`, bool-as-int, stride/rc mutations, and manifest-profile relabeling. The unchanged v1 and original core suites must remain green.

## New package and use

New package: `app/build/blendshape-normalized-suffix-bundle-v2`. Freeze and source snapshots: `app/build/normalized-suffix-bundle-freeze-v2`. The four payloads are byte-identical to v1, including model `17b0773a...d4831c`, normalized inputs `ff9ffb0b...357c8b`, original TF references `5945bbec...b917602`, dedicated helper `42d00cee...339d71`. Full SHA/bytes are in the new manifest/freeze. No new native compile or device execution is needed to check already captured evidence.

After independent review, check each existing raw directory with a **new output path**, never overwrite the original rejection:

```powershell
python -B scripts/prepare_normalized_suffix_bundle_v2.py check --bundle app/build/blendshape-normalized-suffix-bundle-v2 --evidence E:/tripo/output/mirror-program/20261003/blendshape-normalized-suffix-device-v1 --output E:/tripo/output/mirror-program/20261003/blendshape-normalized-suffix-device-v1/comparison-observed-attrs-v2.json
```

Repeat for device-v2. Exit 0 means the original numeric screen passed under the new exact observed contract; exit 1 is complete numeric failure; exit 2 is evidence/contract failure. The original max-absolute `.01`, mean-absolute `.002`, finite and range gates remain fixed against all original 92×52 TFLite values. Even if these pass, `application_eligible=false` and `cpu_front_deployed=false` remain: this is offline CPU FP32 normalization plus an isolated mixed CPU/NPU suffix, not an integrated camera/render performance result.
