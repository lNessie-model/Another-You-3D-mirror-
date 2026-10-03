# 归一化后段隔离设备包：交接合同

2026-10-03。仅准备设备测试材料，**尚未运行设备**。板端操作由 root 顺序执行，不能与应用耐久/性能测试并发。此包使用原 FP32 前段预先算出的 normalized 输入；设备 helper 不实现归一化，也不证明 CPU 前段已经部署。

前置编译/模拟器证据见 `blendshape-normalized-suffix-validation.md`，固定 `.01/.002/finite/range` 门不变。模拟器通过不代替板端门，`application_eligible` 始终 false。

## 新文件及唯一 native 差异

- `native/rknn_normalized_blendshape_probe.c`：从冻结旧 `rknn_blendshape_probe.c` 逐字节复制，只替换 `start.scope`、`feed_contract.input_order` 两个字符串。API 调用、shape guard、每轮拷贝/释放、输出偏移、计时、失败退出均未改变。
- `scripts/build_normalized_blendshape_probe.ps1`：已有 NDK r25c，参数仍 `--target=aarch64-linux-android30 -O2 -Wall -Wextra -Werror -fPIE -pie ... -ldl -lm`，拒绝复用输出目录。
- `scripts/prepare_normalized_suffix_bundle.py`：新专用 prepare 和严格 check wrapper，**不调用旧 raw-pixel prepare**，保留原 `blendshape_device_check.compare_bundle` 不变。
- `tests/test_normalized_suffix_bundle.py`：真实固定来源/模型/输入验证，加模拟设备日志的故障注入；不会调用 ADB。

旧 C SHA `fbd596fca5678e3820cf153e994c9902e69a2407013c0f6bf1baa8ed9593d361`；新 C SHA `5dce648fff959ab3242230551aca5f823ce5c2423210aec05acb05b25a04b07a`。测试将两处字符串替换后要求整个源文件 bytes 完全相同；修改 `OUTPUT_FLOATS` 等任何额外字节会被拒绝。旧 header SHA `a4ea04445a061a9a8814a6467c28f9bdb6c61f0d3b5457a1790e7b3097245d6f`、API header SHA `f280732314c2d9dae871faa84946efaa8477499579236474fe0c9ea8b018571e` 不变。

新 scope 原文：`CPU FP32 fixed-front normalized [1,146,2] input; isolated mixed CPU/NPU suffix; not full face pipeline or render FPS`。

新 input_order 原文：`CPU FP32 fixed-front normalized C-order [1,146,2], adjacent x/y; no preprocessing or transpose in helper`。

NDK 实际构建 exit0，ELF64 / little-endian / AArch64，**15,616 字节**。构建记录保存完整参数、clang / source / header / build-script / helper SHA；旧 ELF 未覆盖。

## 固定包与来源链

本地包 `E:/tripo/device-lab/app/build/blendshape-normalized-suffix-bundle-v1/`。

manifest SHA `9e18e8c299347bd8d0b9cfcd5dde97080b9cc33eb03b5439ec5bb3068afa00df`。

| 四个固定文件 | 字节数 | SHA-256 |
|---|---:|---|
| `face_blendshapes.rknn` | 1,209,569 | `17b0773a404521a8cf9624fa8147467d9fb3fdb309a641f244244b2910d4831c` |
| `inputs.f32` | 107,456 | `ff9ffb0b905d58966fb2c0a19f3406c8a4a7250e285837d2c7f9376c36357c8b` |
| `references.f32` | 19,136 | `5945bbecedd7ee827a75091b81e12a84a88ca8f05807b034a52f528c9b917602` |
| `rknn_blendshape_probe`（新语义 ELF） | 15,616 | `42d00cee0e8d5bf2a2dd4827da71837fa7bf7ad0883dd233875866f49b339d71` |

包中 `provenance/` 是封闭的 20 个固定来源文件，包含原 92 组验证清单与 NPZ、原/八处复制完整 ONNX、原 front 与固定 suffix ONNX、两份 freeze、prepare/compile/模拟器报告、case 映射、旧/新 C、两个 header、新 build 脚本与记录、未改的核心 checker。每个来源的文件名、长度和 SHA 在 wrapper 内固定，不能仅修改 manifest 声明来换掉来源。

wrapper 核对原图→前段/后段切分→原 FP32 normalized 输入→固定后段 RKNN 的链，并保留原 TF 参考；每 case 同时保存原 raw 输入 SHA、normalized 输入 SHA、原 TF 行 SHA、原 fixture ID 和原真实/合成组别。对照 NPZ 再核每一行，不能使用 normalized 数据伪装原像素输入，不能替换参考为半精度输出。

旧核心 checker SHA 固定为 `e92b014b7889ae0cb43435cc08ae9803cfae289251c0109ccc205a644cea457b`。wrapper SHA `ab2affca16c4d3c87280bbbccd2d0b75588a6c99e61a6a7423b40439a5dde7b0`，由 manifest 绑定。

## root 执行设备时的固定合同

已有最近设备证据的 serial 为 **`6L32552009566714`**，来源 `blendshape-all-ln-negmean-copy-device-v1/commands.json`；运行前仍由 root 确认目标。以下只是交接命令，本文作者没有执行。

建议两次分别使用全新设备目录 `/data/local/tmp/mirror-normalized-suffix-v1`、`...-v2`，以及全新主机 raw 目录 `E:/tripo/output/mirror-program/20261003/blendshape-normalized-suffix-device-v1`、`...-v2`。若目录已经存在应停下，不覆盖。

只推送 `face_blendshapes.rknn`、`inputs.f32`、新 `rknn_blendshape_probe`，参考和 provenance 留在主机。使用现有 vendor `/vendor/lib64/librknnrt.so`，SHA 必须为 `01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de`，不推送/替换 runtime。

单次 native 参数（5 次 warmup + 92 次 measurement，cycles=1）：

```text
/data/local/tmp/mirror-normalized-suffix-v1/rknn_blendshape_probe /vendor/lib64/librknnrt.so /data/local/tmp/mirror-normalized-suffix-v1/face_blendshapes.rknn /data/local/tmp/mirror-normalized-suffix-v1/inputs.f32 92 1 /data/local/tmp/mirror-normalized-suffix-v1/report.jsonl /data/local/tmp/mirror-normalized-suffix-v1/outputs.f32
```

必须保存真实 native 退出码到主机 `native-exit.json`（`{"exit_code":实际整数}`），不能以 collector/checker 的退出码代替。`before.sha256`、`after.sha256` 都包含 model/input/helper/vendor；after 还包含完整 `report.jsonl`、`outputs.f32` SHA。拉取 raw 时保留实际命令记录、stdout/stderr。完整测量输出固定 **19,136 字节**，不用 warmup 数据充数。

新 wrapper 增加保守的精确 query 守卫：input 名称 `model_1/tf.math.truediv_1/truediv`、n_dims=3、dims `[1,146,2]`、292 elements、FLOAT16/type=1、size584、fmt=3；output 名称 `StatefulPartitionedCall:0`、n_dims=1、dims `[52]`、52 elements、FLOAT16/type=1、size104、fmt=3；两者 index0、qnt_type0、w_stride0、size_with_stride 等于 size。这里是**待设备核对的接受合同，不是已经取得 query 证据**。若设备实际属性不同，先拒绝并保留日志，不自动 reshape/transpose 或放宽格式。实际 feed 仍 FLOAT32/type0、queried fmt3、pass_through0、want_float1。

主机检查（bundle 固定；每次输出文件必须不存在）：

```powershell
& 'C:/Users/lNessie/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' -B E:/tripo/device-lab/scripts/prepare_normalized_suffix_bundle.py check --bundle E:/tripo/device-lab/app/build/blendshape-normalized-suffix-bundle-v1 --evidence E:/tripo/output/mirror-program/20261003/blendshape-normalized-suffix-device-v1 --output E:/tripo/output/mirror-program/20261003/blendshape-normalized-suffix-device-v1/comparison.json
```

check 先检查固定 provenance、新语义字符串、精确属性及外部 native exit0，再调用原核心的 API/顺序/计数/数据偏移、before/after SHA 和 `.01/.002/finite/range` 门。exit0 为这轮隔离数值通过；exit1 为完整数值失败；exit2 为证据/结构/来源失败。完整结果 status=`diagnostic_complete`，保留 core_status 和 passed；任何分支都不宣称完整管线或应用可用。

## 本地主机验证

新增 8 项测试 RED→GREEN，覆盖真实包来源、仅两 literal 字节差异、外部退出缺失/134/布尔假0、实际属性/语义变造、normalized 输入替换且伪造 manifest 哈希、原参考/组别/来源变造、原数值和完整性门、API release/afterhash、拒绝覆盖。旧核心 23 项全部 GREEN。结构测试日志明确是 host fixture，不算实机证据。

```powershell
& ./scripts/build_normalized_blendshape_probe.ps1 -OutputDirectory E:/tripo/device-lab/app/build/normalized-suffix-device-helper-reproduction-new
& 'C:/Users/lNessie/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' -B -m unittest discover -s tests -p test_normalized_suffix_bundle.py
& 'C:/Users/lNessie/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' -B -m unittest discover -s tests -p test_blendshape_device_check.py
& 'C:/Users/lNessie/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' -B scripts/prepare_normalized_suffix_bundle.py prepare --bundle E:/tripo/device-lab/app/build/blendshape-normalized-suffix-bundle-reproduction-new
```

prepare 专用于当前已审查 artifact，输入来源和 helper 路径固定，不把未来重编译的任意 helper 静默纳入已有合同。未来换版本需另行显式冻结与审查。当前正式 APK、native 原 helper、旧包、驱动和 vendor runtime 均未修改。
