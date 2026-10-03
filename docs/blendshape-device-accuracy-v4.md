# v4 RKNN 表情模型：完整执行但精度不通过

2026-10-03。**结论：拒绝将本候选接入主程序或替换现有表情模型。** 设备进程已完整执行，全部输出有限且在概率范围内，但与原始 TFLite 的误差严重超出运行前固定的工程门限。约 11.05 ms 的执行耗时对应错误结果，不能作为可用推理性能、NPU 优化成功或联合帧率达标的证据。

本文仅离线读取并独立复算已有证据，没有操作 ADB、修改应用、模型、运行库、驱动或测试程序。此前 Sqrt 初始化失败、格式保护停止、Div 类型中止及各次候选的主机验证见[隔离测试记录](blendshape-device-check.md)。本轮是首个完整返回所有测试输出的候选；“完成运行”与“输出正确”分别判定。

## 被测模型、输入和预设门限

- 原始证据目录：`E:/tripo/output/mirror-program/20261003/blendshape-device-v4`。
- 测试包：`E:/tripo/device-lab/app/build/blendshape-device-bundle-v4`。manifest SHA256 `cda847a6e516e1f19501e4f8f8faceeb4f7bb5678f1b9aad0db4fe4f4d084c61`。
- 候选为 `gamma-conv-mulpow-v1-compile/face_blendshapes.rknn`，6,135,582 字节，SHA256 `f378b6d75b84ba81a52a566d85b05e9e4840c85c0493cc70d5129088ecc21c40`。
- helper-v2 SHA256 `1c0e50c366cd08c52ad3c32beeeb64d09ede542db069c75c2ddfa0a1ebe7ffb3`；板上原运行库 `/vendor/lib64/librknnrt.so` SHA256 `01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de`。SDK 查询为 1.3.0，驱动为 0.7.2。
- 输入沿用全部 92 组：72 组录制关键点、20 组确定性合成关键点，每组 292 个 float32，原顺序 `[1,146,2]`，已选取和缩放为像素 x/y。没有重新归一化、重新选点或转置。`inputs.f32` SHA256 `36aea412c6765dfbe7222db47253e2e3c734ef6366a2578c0ede282fb4cc13fc`。
- 参考是原始 TFLite 的全部 92×52 输出，没有改用重写后的模型作为参考。原 TFLite SHA256 `4f36dded049db18d76048567439b2a7f58f1daabc00d78bfe8f3ad396a2d2082`；`references.f32` SHA256 `5945bbecedd7ee827a75091b81e12a84a88ca8f05807b034a52f528c9b917602`。

运行前固定的初步 FP16 工程筛查要求全部满足：最大绝对误差 ≤ **0.01**、全局平均绝对误差（MAE）≤ **0.002**、全部输出有限、输出位于 [0,1]（容差 1e-5）。本次没有放宽门限、删除失败样本或重新排列 52 项输出。即使通过该门，也不等同于真人表情效果已验收。

## 证据完整性独立复核

此次独立计算直接读取原始 float32 文件和 JSONL，没有调用现有 `compare_bundle` 函数。复核了：

1. 测试包全部文件的 SHA256 和字节数；设备运行前后 model/input/helper/vendor runtime 的哈希；拉回的报告和输出与设备 after 清单中的哈希。
2. 92 组输入和参考各自的逐样本哈希、fixture 索引及分类；原始输出恰好 19,136 字节，即 4,784 个 float32，没有截断或多余数据。
3. `native-exit.json` 的实际进程退出码为 0；报告中的初始化、SDK 和属性查询返回 0；5 次预热和 92 次测量的 `inputs_set`、`run`、`outputs_get`、`outputs_release` 均返回 0；finish 为 success，completed=expected=92，destroy 返回 0。
4. 97 个 iteration 的阶段、序号、fixture、cycle、每次输出 208 字节、测量输出偏移全部对应；测量记录的最小/最大值与其原始输出一致。预热值也有限且未超出概率范围。
5. 从二进制重新计算全局、全部 52 通道和全部 92 样本的最大误差与 MAE，与现有 `comparison.json` 的每项结果一致。

模型属性报告输入 rank 3、`[1,146,2]`、292 元素、FLOAT16/584 字节、格式 UNDEFINED (3)，输出 rank 1、`[52]`、FLOAT16/104 字节、格式 3。实际 feed 使用 FLOAT32、同格式 3、`pass_through=0`，输出要求 `want_float=1`。这些记录确认调用合同与已接受的结构；**成功返回并不能证明运行库的输入转换、算子数值或分区间布局正确**，还需中间结果定位。

## 独立重算的精度结果

| 范围 | 样本数 | 输出数 | 最大绝对误差 | MAE | RMSE |
| --- | ---: | ---: | ---: | ---: | ---: |
| 全部 | 92 | 4,784 | 0.9722383022 | 0.2395760047 | 0.3690505405 |
| 录制关键点 | 72 | 3,744 | 0.9587444663 | 0.2354498683 | 0.3691424438 |
| 合成关键点 | 20 | 1,040 | 0.9722383022 | 0.2544300958 | 0.3687194989 |

非有限值为 0，越界值为 0；实际输出范围为 **0.003021240234375～0.9970703125**。然而 **3,613 / 4,784** 个输出的绝对误差大于 0.01；**92 / 92** 个样本均至少有一项超门，**52 / 52** 个通道均至少有一个样本超门。这不是仅有少数边界点的舍入差异。

最大差异出现在索引 88 的 `synthetic-16`，通道 11 `eyeLookDownLeft`：设备输出 **0.010833740234375**，原始 TFLite 为 **0.98307204246521**，绝对误差 **0.972238302230835**。

按通道 MAE 排名前六的实际错误如下；完整 52 通道、92 样本统计保留在[comparison.json](../../output/mirror-program/20261003/blendshape-device-v4/comparison.json)，独立复算已逐项核对。

| 索引 / 通道 | MAE | 最大绝对误差 |
| --- | ---: | ---: |
| 17 / eyeLookUpLeft | 0.8403555680 | 0.9601910412 |
| 24 / jawLeft | 0.8288044838 | 0.9720442593 |
| 39 / mouthRight | 0.7251852981 | 0.8893582225 |
| 22 / eyeWideRight | 0.7104346522 | 0.8452964723 |
| 12 / eyeLookDownRight | 0.7040595208 | 0.9202605486 |
| 25 / jawOpen | 0.6740152862 | 0.9446800351 |

所有通道在 92 个输入之间都存在变化，不能将该结果描述为“完全固定输出”。这也不能排除部分输入转换、布局、精度或算子实现问题，现有最终输出不足以定位首次数值分歧。

## 耗时只能用于失败诊断

初始化返回 0，用时 196.358478 ms。五次预热的 total 均值为 9.638301 ms。92 次测量的 total 均值为 **11.0532579348 ms**，p95 为 12.42689735 ms；其中 `rknn_run` 均值为 10.9720318696 ms，其余为输入提交、取出/复制和释放的开销。

这只是该错误模型的隔离 API 墙钟时间，包含 CPU 回退和 NPU 执行，不是 NPU 内核时间或利用率。文件输出不在计时区间内，也没有同时运行完整相机、面捕、角色渲染或交织测试。**不得把它换算成“可用表情 FPS”、宣称 NPU 加速成功，或用它承诺 16 视点以上 30 FPS。** 当前候选不得接入主程序，现有可用模型继续保留。

## 下一步及原始证据哈希

下一步应保持本轮全部失败输出，使用隔离诊断模型和明确列出的中间张量逐段比较：先核输入、中心化、尺度与归一化，再进入 Mixer。每个诊断张量需绑定准确的名称、shape、元素数、原始 ONNX/TFLite 参考和输出偏移。一次只验证一个明确假设；不能依据最终误差直接认定 FLOAT16、格式 3、Pow 或某一具体分区是根因。任何后续候选都要重新通过原来的完整 92×52 门，才能讨论主程序接入与联合性能。

| 原始文件 | SHA256 |
| --- | --- |
| `outputs.f32` | `66b35d13c997d989811bc3b9cd5477c4b2334939349673ea4fa51f45df3bcd11` |
| `report.jsonl` | `b8bbb905b885a1a5d73b97552491abf669e1fc90ac6458bf85424cce17637066` |
| `before.sha256` | `1adac6c8844ca08ff45897e89c3fb5b3142c29e28eaf766ba8c13744774151d9` |
| `after.sha256` | `19b38c7c31f2bc302367abac8af796073a0792a6b6d6328ace92ce526bdba22e` |
| `native-exit.json` | `124fd50cb6d223cdd18fedc166d00af7254199b6e6dedf9be82f71db87af6d01` |
| `comparison.json` | `0ce3f7441c5f1eb3fe4b1e84eb85311d00cb569e808a3d120fa7b8b10989c3d2` |

上述文件均位于前述 `blendshape-device-v4` 原始目录。现有 checker 的结构与数值复查命令如下，预期生成 `status:failed` 并退出 1；请指定新的输出文件名，避免覆盖已有报告。实际 native exit 0 已从单独的 `native-exit.json` 检查，不能只看报告 footer。

```powershell
$python = 'C:\Users\lNessie\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$raw = 'E:/tripo/output/mirror-program/20261003/blendshape-device-v4'
& $python scripts/blendshape_device_check.py check `
  --bundle app/build/blendshape-device-bundle-v4 `
  --log "$raw/report.jsonl" --actual "$raw/outputs.f32" `
  --before "$raw/before.sha256" --after "$raw/after.sha256" `
  --output app/build/blendshape-device-v4-recheck.json
```
