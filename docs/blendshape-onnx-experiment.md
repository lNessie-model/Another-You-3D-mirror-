# 52 表情网络的等义 ONNX / RKNN 1.3 编译实验

2026-10-03：已在现有离线工具链生成可导出的 RKNN 候选，首个产物 `gamma-conv-div-neg` 的 92 × 52 ONNX 数值对照及编译导出通过；**随后独立板端验证在初始化阶段因不支持 Sqrt 而 SIGABRT，尚无一组 RKNN 输出。** 板端兼容性、FP16 精度、耗时及联合负载收益均未通过。编译表明确包含 CPU / NPU 混合分区，不能称为纯 NPU 模型。后续 Pow 候选的进展见末节。

离线转换没有修改应用默认模型、Java/native 运行流水线、驱动或系统，没有安装依赖或上传模型。设备测试由独立诊断程序加载现有 vendor runtime，未替换运行库或默认模型。目标仍是保留 478 点、52 项表情、姿态及 20 视图联合负载，为 UI 留出资源；本轮不能据此宣称 30 FPS 达标。

## 输入与环境

- 原始模型：`E:/tripo/output/npu-face-optimization/20261002/npu-models/face_blendshapes.tflite`，955,312 字节，SHA256 `4f36dded049db18d76048567439b2a7f58f1daabc00d78bfe8f3ad396a2d2082`。
- 固定工具链：现有 WSL `rknn13-venv`；RKNN Toolkit2 `1.3.0-11912b58`，TensorFlow 2.2.0，Rockchip tf2onnx 1.8.0，ONNX 1.7.0，ONNX Runtime 1.6.0。编译目标 `rk3566`、`float_dtype=float16`、`optimization_level=3`、`do_quantization=false`。
- 输入 FLOAT32 `[1,146,2]`。146 个索引来自已缓存官方 `face_blendshapes_graph.cc`；记录样本使用 `x*640,y*480` 的像素坐标，按 x/y 相邻的 C 顺序排列。模型内部中心化及尺度归一化保留。输出是原顺序全部 52 项。
- 原始 TFLite 是唯一数值基准。不是拿一种改写与另一种改写互相证明。

全部实验材料位于 `E:/tripo/output/mirror-program/20261003/blendshape-broadcast/`。独立 `summary.json` 和 `audit_broadcast.py` 复核模型、输入、报告、日志和导出物 SHA；下文路径以该目录为基准。

## 定位与逐步结果

旧版 TFLite 编译以及原样导出的 ONNX 都触发图优化器 `_p_swap_two_op` 的常量维度错误。生成的 `const_fold_opt__NNN` 数字随转换进程变化，不能据编号删改常量。本轮按消费者名字、类型、形状及原始参数字节哈希定位：

`const_fold_opt__388` 对应原始 TFLite tensor 57，Block 0 / LayerNorm 1 的 gamma。它是 64 个有意义的缩放系数，原始 FLOAT16 解码成 FLOAT32 后 SHA 为 `1afc69f8c025b18297241346060211825e083066982f9af1c5ecad3bc3f2a955`，有 56 个不同值。全部 8 组 gamma 都逐组与原始 TFLite 参数核对，未将 64 维压成 1。

| 候选 | 等义改写 | 92 × 52 最大绝对误差 | 编译结果 |
| --- | --- | ---: | --- |
| baseline | 原样 tf2onnx 优化导出 | 0.000001937151 | gamma `[64] → [1]` 失败 |
| rank4-gamma | gamma 仅改维度为 `[1,1,1,64]` | 0.000001937151 | 同一常量仍被错误改为 `[1]` |
| gamma-conv | 8 处广播乘法改为单输入通道卷积及轴还原 | 0.000001937151 | 越过维度错误；原生编译器不支持 Reciprocal |
| gamma-conv-div | 再将 8 处 Reciprocal(x) 写为 Div(1,x) | 0.000001937151 | 越过 Reciprocal；不支持 Neg |
| gamma-conv-div-neg | 再将 8 处 Neg(x) 写为 Mul(x,-1) | 0.000001937151 | config/load/build/export 全部 0，成功导出 |

卷积等义关系是：

`[1,1,97,1]` 按 NCHW 输入，gamma 原有 64 个 FLOAT32 字节仅改形状为 `[64,1,1,1]`。无偏置 1×1 Conv 产生 `[1,64,97,1]`，再用 `Transpose[0,3,2,1]` 恢复 `[1,1,97,64]`。每个输出只有一个乘积，保留所有权重和 97 个位置顺序。其余矩阵、归约、epsilon、网络输出连接不变。

Div / Mul 改写只新增精确可表示的 1 和 −1，保留 Sqrt 及其输入。没有编辑编译器私有断言、跳过算子、降低输出维度或修改 epsilon。现有原生库包含 Div lowering 的静态符号，这只作为实验方向依据；实际是否接受由后续编译结果确认。

## 数值门与异常证据

预先固定 `atol=1e-5,rtol=0`，所有 52 项必须有限且逐值通过。92 组输入由 36 帧记录的 NPU / MediaPipe 两套关键点构成 72 组，再加 20 组确定性随机位置、尺度、平移输入。历史 MediaPipe 记录输出与重新运行原始 TFLite 的最大差异为 0.000004887581，作为像素坐标预处理的交叉检查。

这证明本次语料上 FLOAT32 ONNX 与原模型一致，不能替代所有人脸/极端输入的覆盖，也不能证明 RKNN 内部 FP16 与原始模型相同。

[转换脚本](../scripts/blendshape_broadcast.py) 只接受受检查的 8 处私有 gamma、明确的变量布局和节点；未知布局、共享常量、非有限参数、名称冲突均拒绝。保留输入模型不变，每个输出目录必须是新目录。`compile` 在加载编译器之前检查完整数值报告、源 SHA、候选 SHA、92 个通过的用例及固定误差门。

[主机回归](../tests/test_blendshape_broadcast.py) 首版共 11 项通过，包括真实 ONNX / ORT 的广播轴次序、64 参数字节保留、Div 对照、取负的正负零字节对照和拒绝路径；加入 Sqrt/Pow 后共 12 项通过，舍入范围见末节。它们不模拟设备输出。

曾调用 ONNX 1.7 原生 `shape_inference` 处理真实转换图，进程 exit 139；`rank4-v2-prepare-host.log` 的 faulthandler 指向该调用。后续改为读取明确、全正的 Reshape 目标维度及已有 value info，不再调用该原生推断。两种导入顺序的独立探针均正常，因此不能归因于模块导入顺序。

gamma-conv / gamma-conv-div 的原生编译器 fatal 直接结束 Python，原始 `compile-report.json` 留在最后的 `running/build` 检查点。相邻 `*-compile-launch.json` 和 host log 明确记录 terminal exit 1；这些不是仍在运行的任务。保留原始报告，未将失败改成成功。

## 首个可编译产物与混合分区（板端初始化失败）

最终目录 `gamma-conv-div-neg-v1-compile/`：

| 文件/身份 | SHA256 |
| --- | --- |
| `face_blendshapes.rknn`，6,131,230 字节 | `1c4053dada0f60bb6201f4738b005d52fa3c4e12971fd8be4dbe47eb956980e3` |
| 数值门 `gamma-conv-div-neg-v1/numerical-validation.json` | `7f06f7168c47bbfad2933d1e61300c6bd03f5987ed4f7b14a21fa6811b35f8da` |
| 最终 ONNX `gamma-conv-div-neg-v1/gamma-conv-div-neg.onnx` | `f75b7a3a8d5c8abc7404cda258f5987b5cb89c67431ad08bf1df7d4cb4094541` |
| `gamma-conv-div-neg-v1/reference-fixtures.npz` | `45dfa3e2781ede9599758874346e5532f8e250be2f640a6293b2f3e2bc97a6e8` |
| 首版转换脚本快照 | `3339eef1e51f0ddc11b861ad3e9e01fc9a589372746577771e20c7284358b193` |
| 首版主机测试快照 | `304303f37f58b4a4d98859023d9ee9b152224769a91d2cc2c9a049f3ed59af32` |

成功编译共 4.38 秒，其中 build 1.93 秒。**这不是推理耗时。** host log 的最后完整算子分配表包含 186 行：

| 静态分配 | 行数 | 构成 |
| --- | ---: | --- |
| NPU | 132 | Conv 46、ConvRelu 8、Mul 33、Add 16、Sub 9、Reshape 18、Concat 1、Sigmoid 1 |
| CPU | 54 | Transpose 25、Sqrt 9、Div 9、Reshape 6、Slice 2、ReduceSum 1、Input/Output 各 1 |

这些计数包括 I/O 和形状节点，不能换算为 CPU / NPU 占用比例或提速比例。CPU 与 NPU 之间的同步、数据转换和传输也可能抵消收益；编译表中的零时间不是实测时间。

NPZ 的 `inputs` 为 FLOAT32 `(92,1,146,2)`，`outputs` 为原始 TFLite FLOAT32 `(92,52)`。行顺序与数值报告 `cases` 一致，每行有 `input_sha256/reference_sha256`。后续设备 runner 应直接发送这 292 个输入值，不再选点、乘分辨率、转置或预归一化；以运行时查询的公开 tensor attr 验证布局、dtype、元素数，再要求 float 输出。未知/反向布局应明确失败，不猜测修复。

## 复现与下一道验收

原始 ONNX 导出入口保存在 `probe-v4/probe_tf2onnx.py`，使用现有 bundled `process_tf_graph(None,tflite_path=...,opset=11)`，随后 `optimize_graph(graph,catch_errors=False)`。所谓 raw graph 已经包含转换重写，不称为原始 TFLite 拓扑。若重新导出，应先将该探针复制到全新目录再运行，避免覆盖证据；生成常量名可能变化。

现有基线 `probe-v4/baseline-optimized.onnx` 的 SHA 是 `5c2e3eaa7d42d532d044191c9973a4508e4c34f8eea6fac25c437aa0c7fcb075`。在现有 WSL Python 中运行脚本 `prepare`，显式提供 `--source/--base/--quality/--graph/--output`，成功后再运行 `compile --validation <新目录>/numerical-validation.json --candidate gamma-conv-div-neg --output <另一新目录>`。每次保留 host stdout/stderr 及外层退出码；原生 fatal 可能跳过 Python finally。

仍须通过隔离的真实 RKNN 输出核对：92 组全部 52 项，事前确定适合 FP16 的精度门，分别报告录制与合成输入，保留逐项输出/最大误差。精度通过后，再测试 warmup 后独立耗时与原 TFLite，最后在同 APK、同输入、同 20 视图负载下比较整机 CPU/GPU/NPU、表情率、延迟、温度和渲染帧率。当前 CPU post 还包含平滑、姿态、proto/JNI 与回调等待，不能将整段 post 时间都视为这个 52 表情网络的潜在收益。

## 板端 Sqrt 拒绝与下一候选

`E:/tripo/output/mirror-program/20261003/blendshape-device-v1/` 保留首次设备证据：`native-exit.json` 为 exit 134，`report.jsonl` 只有 start，`outputs.f32` 为 0 字节。`logcat-pid9118.txt` 明确记录 `ERROR: unsupport Sqrt op in current`，随后 SIGABRT；模型、输入、helper 和 vendor runtime 的前后 SHA 一致。该组未到 init 返回，更未执行 92 组推理，不能计算精度或推理性能。

本地板运行库含 Pow 的具体 dtype/power/dimension 诊断，编译器也有 Pow lowering，因此下一受限候选将全部 9 个 Sqrt 写为 `Pow(x,float32 0.5)`：8 个为 LayerNorm 的方差加 epsilon 后开方，1 个为输入坐标归一化的范数开方。保留前置平方、求和、平均、epsilon、全部参数与 52 输出；不通过增加 epsilon、截断输入或近似式绕过问题。

这是固定源图在非负模型域上的受限改写，不是通用负值/负零等价证明。ORT 1.6 的局部 Sqrt 与 Pow 内核存在舍入差异：8 个测试值中 3 个不同，最大 0.00000011920929，**不称逐位等同**。完整网络仍沿用原先 `atol=1e-5,rtol=0`；92 × 52 对照已通过，Pow 候选最大误差 0.000002592802048。门槛未放宽。

Pow 候选保存在独立 `gamma-conv-div-neg-pow-v1/`。编译进程 terminal exit 0，config/load/build/export 均返回 0，产物位于 `gamma-conv-div-neg-pow-v1-compile/`。最后完整的 186 行静态分配表仍为 NPU 132 / CPU 54；CPU 的 9 个 Sqrt 全部变为 Pow，表中没有 Sqrt，其余算子计数与上一候选一致。这仍是 CPU/NPU 混合分区，不能称纯 NPU 网络。

| 文件/身份 | SHA256 |
| --- | --- |
| Pow ONNX | `3305e770a0c8c4210d7cbfc5e1c8fcbb13ae3ceb0c1689bd4039672c136d84b7` |
| Pow RKNN，6,133,662 字节 | `33c72f2f6d6de12ebc48fe575445106fbb30bc12692a0b28b6b12323fbf4765f` |
| Pow 数值门 `numerical-validation.json` | `9126795b8dda648a5697c048e239f896730222f2bad52377ee3dbe5d83a3efbb` |
| `reference-fixtures.npz`（同前一候选） | `45dfa3e2781ede9599758874346e5532f8e250be2f640a6293b2f3e2bc97a6e8` |
| 本版转换脚本快照 | `caee27ecaec35eff8cc86abcb70de4347bba1bf0ef45ab3bb81d49def448272b` |
| 本版 12 项主机测试快照 | `14c6db41aac7d2d1a9da58bb8ee8d11d13fcbdcd4d252d04a5bdbb4341908404` |

报告记录总耗时 56.46 秒，包含导入工具的等待；build 为 7.35 秒，均不是推理耗时。仍须重新走板端初始化及全 52 值核对；静态符号、成功导出或 ONNX 对照不能替代这一步。目前没有 Pow 候选的设备可用性、RKNN 精度或性能通过结论。
