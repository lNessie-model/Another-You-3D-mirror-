# 固定归一化 RKNN 后段：CPU 回退结构与下一微实验

修订 2，2026-10-03：仅更新已完成的测量上下文。原冻结版本保留，算子清单、参数、布局候选与门限不变；原文件是有效 UTF-8，未发现替代字符。

2026-10-03。本次仅在主机读取已冻结的 ONNX、RKNN 编译表、来源报告及同版本厂商头文件。**194 个编译表行中，134 行标为 NPU、60 行标为 CPU；这不是耗时比例。** 没有改模型、运行推理、重新编译、调用设备或更改应用。当前模型在设备上的数值和应用证据分别见[隔离数值验收](E:/tripo/device-lab/docs/blendshape-normalized-suffix-device-validation.md)与[v19 同 APK 联合 ABA](E:/tripo/device-lab/docs/runtime-v19-expression-aba-validation.md)。

结论是先测清不变模型在联合负载中的等待与重建代价。存在一个值得保留的局部布局候选：首 `MixerBlock_0/layer_norm2` 的四次转置可以在纸面上化为两次仅移动单例轴的 reshape，保持原统计和逐元素算术顺序；**尚未生成候选图，也未证明 RKNN 数值或性能收益。**

## 固定来源

| 项目 | SHA-256 |
| --- | --- |
| normalized suffix ONNX，218 节点 / 106 initializer | `61c61dda91356545c9397906b6872bcfc52849b796355b6f44cb03de72c6af9b` |
| RKNN，1,209,569 字节 | `17b0773a404521a8cf9624fa8147467d9fb3fdb309a641f244244b2910d4831c` |
| compile diagnostic | `3292a9039d6c86e3803b9897cc80e47a57626fe09a55dc2b62ebfe16592910e2` |
| 完整 compiler host log | `88be3658e07f6177e109bf4cbdf8b5d05d077fd7208c802674325b8077e0685f` |
| 本次 ONNX 结构清单 | `621f9c8dcd8365886661f04da2279f333d0928f41f35c925576451454b1728e2` |
| 本次独立结构审计 | `b5de4636f110feb6de44f458847d98a84bfc1cabe65b0aafa4122b83ef9bff15` |

原始证据在 `E:/tripo/output/mirror-program/20261003/blendshape-numerical-diagnosis/normalized-suffix-v1*`。Toolkit 为 `1.3.0-11912b58`，API SHA `7ba303d017f4874757e00ac87d570e49e15403fe67ed21bcdef137e4bd670ace`；配置 `rk3566 / float16 / optimization_level=3 / do_quantization=false`。原 config/load/build/export 全部 rc0。本次重新解析原 host log 的 194 个连续编号表行，与已冻结离线审计逐对象相等；源节点按名字、类型和连接核对，IO 边界不冒充同名源计算节点。

## CPU 行到底是什么

| CPU 表行类型 | 数量 | 实际范围 | 逻辑 FP16 输出字节总和 |
| --- | ---: | --- | ---: |
| Transpose | 33 | 首投影 1 次；四个 MixerBlock 各 8 次 | 397,696 |
| Pow | 16 | 八个 LN 各 `.5` 后接 `-1`，每次 97 个值 | 3,104 |
| Reshape | 7 | 入口 2、token mixing 4、输出 1 | 50,936 |
| Slice | 2 | 最终 token 选择，表行 188、189 | 384 |
| InputOperator | 1 | 表行 0，`[1,146,2]` | 584 |
| OutputOperator | 1 | 表行 193，52 输出边界 | 表中无输出张量 |

33 次转置中的首个处理 192 个值，其余 32 次各处理 `97×64=6208` 个值。每个 LN 的三条输入布局转换对应 X、gamma scale 和显式复制的 negative mean，另一次转换连接后续 token/channel MLP。NPU 行具体为 Conv 51、Mul 33、Reshape 16、Add 16、Sub 8、ConvRelu 8、Concat 1、Sigmoid 1。

这些字节数只是张量形状乘以两字节：没有计入隐藏临时区、缓存、重复读写，也不能证明发生了实际拷贝。尤其 reshape 可能只改变元数据。表中相邻 CPU/NPU 标签变化 96 次同样不是实测调度或传输次数。输入和输出两行属于边界，不应与算术 CPU fallback 混为一谈。现有编译日志不含各行耗时，不能用行数、逻辑字节数或 Pow 名字推断联合负载的主瓶颈。

八个 LN 的源码均为 axis 1、keepdims 1 的 mean/variance；现有 `.5 → -1` Pow、epsilon、gamma 与八处负均值全 1 Conv 复制保持不变。复制路径是在此前真实设备数值故障后建立的，不建议为减少算子数量恢复隐式广播。把两次 Pow 合成一个 `Pow(-.5)` 会改变舍入顺序，不能当作逐位等价的低风险优化。

## 一个局部布局候选：只首个 LN2

选择 `MixerBlock_0/layer_norm2`，因为其后 channel MLP 原本就读取 `[1,64,1,97]`。本次审计和独立代理都核对了受影响输出的消费者闭合；没有其他分支需要保留中间 NHWC 数组。

当前编译表的关键行：

| 行 | 当前操作与布局 |
| ---: | --- |
| 34 → 35 | 负均值无 bias 全 1 Conv 得 `[1,64,97,1]`，Transpose `[0,3,2,1]` 得 `[1,1,97,64]` |
| 43 → 44 | gamma Conv 同样从 `[1,64,97,1]` 转到 `[1,1,97,64]` |
| 46 | `Transpose__173`，X 从 `[1,64,1,97]` 经 `[0,2,3,1]` 到 `[1,1,97,64]` |
| 45、47、48 | 原 `negative_mean × scale`、`X × scale`，再按原 operand 顺序相加 |
| 49 → 50 | affine 经 `[0,3,1,2]` 回 `[1,64,1,97]`，进入原 channel ConvRelu |

候选保留两条 Conv 输出及所有统计/Pow/参数，把 `[1,64,97,1]` **按明确轴合同** reshape 为 `[1,64,1,97]`，在此布局执行原两次 Mul 和原顺序 Add，直接连接原 channel Conv。X 的前后 permutation 互逆；另外两条路径与末尾 permutation 合成后只移动大小为 1 的轴，线性元素顺序相同。因此纸面上可移除表行 35、44、46、49 的四次 CPU Transpose，新增两次 reshape。四个转置输出合计 49,664 逻辑字节，仍不是已证明节约的流量或时间。

这不是改成 `(X−mean)×scale`，也不改变 reduction、epsilon、64 个通道或八处显式复制。若获准实验，必须为新中间名更新精确 shape 元数据、拒绝多余 graph output/外部消费者，禁止只凭元素总数猜布局。验证顺序为：

1. 独立 permutation/形状/有限值与正负零测试；其余节点及所有 initializer 原字节保持，仅该闭合局部变化。
2. 原固定 92 组：新旧 FP32 最终 52 逐字节相同，且对原 TFLite `atol=1e-5, rtol=0`。不换输入、参考或阈值。
3. 新的实际 RKNN 表确认同 shape Mul、显式复制仍在、转置未重新生成或广播回折；记录所有 CPU/NPU 放置变化。
4. 同图 simulator 和独立设备仍用原 `.01/.002/finite/range` 门；即使通过也只证明数值。之后同 APK、同暖机/温态范围的联合 ABA 同时检查呈现、完整面捕吞吐和端到端延迟。

现在只完成局部连接和 permutation 推导，未执行上述候选数值门。

## 先做不变模型的最小归因测试

v19 已完成的 90 秒 B1 中，整 session post 是 47.184 ms，当前 post 对象的 expression API 是 20.421 ms；旧隔离同模型 API 总均值约 7.41–8.47 ms。三个数不是同一统计窗口。B1 两次丢脸后重建 post 对象：内层仅最后 171 帧，外层累积 1274 次且包含重建。因此差值不能直接变成初始化耗时，更不能认定为 NPU 核心计算变慢。

下一次建议先保持模型、runtime、292 输入和 52 输出不变：

1. 在隔离诊断入口分别记录 init/destroy，以及每轮 set/run/get/release API 墙钟、上下文编号、丢脸重建编号和统一时间窗。记录首次阶段与后续固定暖机后的阶段，不能把累计均值当滚动稳态。
2. 在 root 独占设备安排下，以相同固定输入和暖机比较表达模型单独运行、与原检测/mesh NPU 负载共同运行、完整应用共同运行。若需要区分渲染争用再单独加入渲染条件；一次只变一项，保留实际温度和频率，避免同时增加多项负载而失去解释力。
3. 先用未开启 detailed profiling 的运行保留端到端基准，再单独启用厂商可查询的明细。若查询不支持或返回信息不足，记录返回码并停止该分支；不因头文件存在接口而假定本设备会返回可用逐算子数据。

随后已完成的 [v19 NPU 30 分钟实测](E:/tripo/device-lab/docs/runtime-v19-npu-endurance-pass.md) 给出整 input session 的 29,357 次 post，累计均值 33.263479 ms；这与此前 90 秒 B1 的 47.184 ms 属于不同测量窗口。30 分钟记录中可见 51 次 post 对象计数回落，末对象仅 115 帧；原 ABA 数值和原统计范围保持不变。这些结果支持继续区分早期阶段、丢脸重建和长期累计窗口，不能直接归因 ART、热状态或 NPU 争用，也不把另一 APK 的 CPU 长测当同温因果对照。本修订不读取任何仍在增长的后续耐久记录。

## 同版本厂商接口边界

实际读取的 [RKNN 1.3 头文件](E:/tripo/device-lab/native/vendor/rknn/rknn_api.h:43) SHA `f280732314c2d9dae871faa84946efaa8477499579236474fe0c9ea8b018571e`，来源固定在[Rockchip v1.3.0 RK356X Android header](https://github.com/airockchip/rknpu2/blob/v1.3.0/runtime/RK356X/Android/librknn_api/include/rknn_api.h)。本次远程重新获取未成功，接口结论依据本地已固定的原始头文件，未假称新下载验证。

- `RKNN_FLAG_COLLECT_PERF_MASK` 允许 detailed report，但厂商明确说明会降低帧率；它属于诊断开关，不用于证明原生产速度。[定义及说明](E:/tripo/device-lab/native/vendor/rknn/rknn_api.h:43)
- `RKNN_QUERY_PERF_DETAIL` 需在 init 时启用该 flag；它和 `RKNN_QUERY_PERF_RUN` 都在 outputs_get 后查询。`run_duration` 单位为微秒。详细报告指针不由调用者释放。[query 合同](E:/tripo/device-lab/native/vendor/rknn/rknn_api.h:100)、[结构体](E:/tripo/device-lab/native/vendor/rknn/rknn_api.h:259)
- 当前头文件的 `set_core_mask` 明确限定 RK3588，不能建议在 RK3566 上分核。[平台限制](E:/tripo/device-lab/native/vendor/rknn/rknn_api.h:449)
- async flag 可以返回前一帧输出，不能为提高表观 FPS 而替换当前帧合同。[async 说明](E:/tripo/device-lab/native/vendor/rknn/rknn_api.h:34)

Toolkit 1.3 的 float16/混合分区限制来源见[精度接口核对](E:/tripo/device-lab/docs/blendshape-fp16-precision-plan.md)。本次没有找到可用证据支持逐层强制 FP32、迁移任意 CPU 算子到 NPU、或增加无用任务来提高利用率。应以相同功能下的完整人脸更新、延迟、呈现和资源余量判断收益。

## 复算与冻结

本次脚本 [read_normalized_suffix_structure.py](E:/tripo/device-lab/app/build/read_normalized_suffix_structure.py) 仅用已有 WSL ONNX 依赖读取固定源，排他生成结构 JSON；未调用 shape inference、ORT 或 RKNN。后续 [audit_normalized_suffix_cpu_structure.py](E:/tripo/device-lab/app/build/audit_normalized_suffix_cpu_structure.py) 仅用 Python 标准库读取 14 个固定文件，重新解析表行、映射 CPU 节点、核八处统计/Pow 和局部消费者，排他生成[审计 JSON](E:/tripo/device-lab/app/build/normalized-suffix-cpu-structure-audit-v1.json)。

已存在产物时不重复直接运行排他写出。纯只读复算可用 `runpy.run_path(path, run_name='recheck')['report']` 与 JSON 比较。这里的结构断言不代替候选图运行测试。独立代理已核首 LN2 permutation/消费者推导；本次没有生成模型候选或对当前应用作变更。
