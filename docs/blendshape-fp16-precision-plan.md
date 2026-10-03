# 52 表情网络的 FP16 精度控制与下一步实验

2026-10-03。本文记录现有工具链的公开能力、两组独立只读复算，以及据此收窄的后续假设。本轮没有修改模型、运行 RKNN build/simulator、操作设备、修改应用或改变验收门。ORT 重放仅在主机内存中执行，未保存新 ONNX。

当前八处负均值显式复制候选已经解决先前设备与自身模拟器之间的大幅分歧，但对原 TFLite 的精度仍失败；接近自己的模拟器不等于原模型精度通过。设备证据见 [八处复制候选](blendshape-all-ln-negmean-copy.md) 及 `E:/tripo/output/mirror-program/20261003/blendshape-all-ln-negmean-copy-device-v2/`。应用默认模型未由本文变更。

## 精确版本与可用接口

本地发行包为 **rknn-toolkit2 1.3.0-11912b58**，不是支持其他芯片的 RKNN-Toolkit1。包位置为 `E:/tripo/native-tools/rknn13-venv/lib/python3.8/site-packages/`。现有设备报告查询到 API `1.3.0 (9b36d4d74@2022-05-04T20:16:47)`、driver `0.7.2`；这是既有 `report.jsonl` 第 7 行的只读复核，本轮没有重新查询设备。

| 项目 | 1.3 实际合同 | 对当前非量化 FP16 模型的含义 |
| --- | --- | --- |
| `config(float_dtype=...)` | 本地签名默认 `float16`，文档仅支持 `float16` | 没有已证实可用的全网 FP32/BF16 开关 |
| `build(do_quantization=False)` | 关闭整数量化，当前候选已使用 | 仍为 FP16，不能解释为内部 FP32 |
| `quantized_algorithm`、`quantized_method` | `normal/mmse`、`layer/channel` | 服务于整数量化；不是现有非量化模型的 FP32 修复参数 |
| `hybrid_quantization_step1/2` | 有实际公开接口；配置指定层输出为 `float16` | 用于 INT8/FP16 混合，不能把已经 FP16 的敏感层提升到 FP32 |
| `optimization_level` | 默认 3；官方说明 1/2 关闭部分可能影响精度的优化、0 关闭优化 | 可作同图编译对照；不会改变 `float_dtype`，且必须核对实际编译表 |
| `force_cpu`、`op_target`、逐层 FP32 配置 | 本地 `config` 没有这些参数，也没有 `**kwargs` | 不能从新版 SDK 或 Toolkit1 示例直接套用 |
| 注册自定义算子 | 1.3 手册写明尚未支持 | 不能通过旧 SDK 公开接口直接插入自定义 CPU FP32 LayerNorm |
| `accuracy_analysis(inputs, output_dir, target, device_id)` | 已安装接口存在；应在原模型 build 后调用 | 官方建议用于逐层结果定位；`load_rknn` 路径不支持该分析 |
| `RKNN(verbose='debug')` | 本地构造函数只接受小写 `debug` | 手册中的 `Debug` 大写示例不能原样照抄 |
| C API 的输入 `FLOAT32/pass_through=0` 与输出 `want_float=1` | 输入按模型类型转换，输出转换为 float | 只改变边界数据表示，不使已编译的 FP16 运算变成 FP32 |

上述能力核对来自本地 `rknn/api/rknn.py` 第 57–103、169–203、236–280 行，以及 `native/vendor/rknn/rknn_api.h` 第 320–341 行。官方 1.3 API 差异表仍列有 `output_tensor_type`，但这份已安装 wheel 的实际 `config` 签名没有；因此本文不把它列为可调用选项。类似地，发行包依赖包含 `bfloat16` 也不能证明公开配置支持 BF16 模型。

官方来源固定到 1.3 发布提交 `9ad79343fae625f4910242e370035fcbc40cc31a`：

- [RKNN Toolkit2 1.3 用户手册](https://raw.githubusercontent.com/rockchip-linux/rknn-toolkit2/9ad79343fae625f4910242e370035fcbc40cc31a/doc/Rockchip_User_Guide_RKNN_Toolkit2_EN-1.3.0.pdf)：PDF 第 15/17 页为混合量化，第 23–24 页为 dtype/优化等级，第 41–43 页为逐层分析及自定义算子限制，第 48–49 页为 FP16 精度排查。PDF 964,465 字节，SHA256 `1f71ebc19f65ce3e7d53e854fc298f467a900f134ee2ffd412aff848b8944774`。
- [同版本 API 差异表](https://raw.githubusercontent.com/rockchip-linux/rknn-toolkit2/9ad79343fae625f4910242e370035fcbc40cc31a/doc/RRKNNToolKit2_API_Difference_With_Toolkit1-1.3.0.md)：Toolkit1 的 `dtype='float32'` 已弃用；与本地签名有差异时以实际 wheel 为准。
- [同版本 hybrid 示例](https://raw.githubusercontent.com/rockchip-linux/rknn-toolkit2/9ad79343fae625f4910242e370035fcbc40cc31a/examples/functions/hybrid_quant/step1.py) 和 [accuracy_analysis 示例](https://raw.githubusercontent.com/rockchip-linux/rknn-toolkit2/9ad79343fae625f4910242e370035fcbc40cc31a/examples/functions/accuracy_analysis/test.py)。后者展示量化模型分析，不能当作本轮非量化模型已经执行该 API 的证明。
- [官方后续变更日志](https://raw.githubusercontent.com/rockchip-linux/rknn-toolkit2/master/CHANGELOG.md)：自定义 CPU/GPU 算子出现在 1.6；这是版本边界依据，不是本轮升级建议。

现有原始 TFLite 在优化等级 0/1/2 下曾全部 build 失败，详见 [转换记录](blendshape-npu-conversion.md)。那是旧输入图的结果，不能直接证明当前八处复制图在低优化等级下成功或失败；本轮未重试。

## 只舍入 292 个原始输入的独立重放

使用固定 92 组输入（真实 72 + 合成 20）和原 TFLite 输出，原 ONNX SHA/NPZ SHA/逐行输入及参考 SHA 均先校验。只进行 `input.astype(float16).astype(float32)`，后续仍用 ORT 1.6.0 的原 FP32 图。另用冻结八处复制候选重放相同舍入输入，最终 4,784 值与原 ONNX **逐字节相同**。

固定门为最大绝对误差 ≤0.01、全局 MAE ≤0.002，全部有限且输出位于 `[0,1]` 的 1e-5 容差内。真实/合成分组是诊断拆分，不能代替全部 92 组门。下表每格为最大误差 / MAE：

| 对原始 TFLite | 全 92 组 | 真实 72 组 | 合成 20 组 |
| --- | --- | --- | --- |
| 原 ONNX / 原输入 / FP32 | 0.000001966953 / 0.000000126993 | 0.000001966953 / 0.000000121752 | 0.000001430511 / 0.000000145861 |
| 仅原始输入做 F16 roundtrip | 0.074558556080 / 0.001340120094 | 0.014749377966 / 0.000819646886 | 0.074558556080 / 0.003213823641 |
| 冻结八处复制 SIM | 0.243225246668 / 0.002802638502 | 0.035322427750 / 0.001414775832 | 0.243225246668 / 0.007798944116 |
| 冻结八处复制设备第二轮 | 0.242248684168 / 0.003794938571 | 0.033857583999 / 0.002571958845 | 0.242248684168 / 0.008197665585 |

原 FP32 门通过；其余三行均失败。仅输入舍入已有 102 个输出、24/92 组超过最大误差 0.01（真实 12 组、合成 12 组），说明原始像素坐标的 F16 转换本身需要处理。但 SIM 相对输入舍入对照仍有最大差异 **0.218642950058**、MAE **0.002260536117**，因此输入损失不足以解释全部剩余偏差。

索引均从 0 开始。对齐的代表样本如下；同一行固定同一通道，不能比较各自不同位置的最大值来推导因果：

| case / fixture / 通道 | 原 TF | 只舍入输入 | SIM | 设备第二轮 |
| --- | ---: | ---: | ---: | ---: |
| 20 / `npu-10000` / 19 | 0.613603353500 | 0.621056973934 | 0.648925781250 | 0.647460937500 |
| 86 / `synthetic-14` / 21 | 0.541079163551 | 0.466520607471 | 0.325683593750 | 0.325439453125 |
| 87 / `synthetic-15` / 47 | 0.237975925207 | 0.262558221817 | 0.481201171875 | 0.480224609375 |

全模型 SIM/设备最坏位置是 case 87、通道 47：原 TF 0.237975925207，SIM 0.481201171875，设备 0.480224609375。同一 case 的“只舍入输入”最大误差为 0.041028290987，出现在通道 4；这不是对通道 47 的逐项差分。真实样本最坏位置 case 20、通道 19 的输入舍入误差仅 0.007453620434，SIM/设备则为 0.035322427750 / 0.033857583999。

## 首 LayerNorm 改运算顺序的只读反证

原 affine 为 `x * scale + (-mean) * scale`，其中 `scale = gamma * invStd`。考察的假设是先中心化再乘 scale 能否显著减少 F16 消减误差。本轮没有改 ONNX，只使用冻结首 LN 的 SIM token/mean，以及八处复制 SIM 的 scale/两个乘积文件，令 `q(a)=float16(a).astype(float32)`：

- 旧顺序：`q(q(x * scale) + q(-mean * scale))`。
- 假设顺序：`q(q(x - mean) * scale)`。

旧顺序的两个乘积、最终 affine 都与已有真实 SIM 文件 **571,136 个值逐位相同**；不是把未经校验的 NumPy 舍入模型当作 RKNN 实测。固定这些已舍入输入后，假设顺序的结果如下：

| 对原 FP32 首 LN affine | 全 92 最大误差 / MAE | 真实 72 最大误差 / MAE | 合成 20 最大误差 / MAE |
| --- | --- | --- | --- |
| 旧顺序，复现已有 SIM | 0.755510561168 / 0.004137123676 | 0.021359801292 / 0.001589150448 | 0.755510561168 / 0.013309827296 |
| 先中心化再乘 | 0.755510561168 / 0.004136819971 | 0.021359801292 / 0.001588405973 | 0.755510561168 / 0.013311110362 |

最大误差未改善，平均改善很小，合成组 MAE 反而略增。首层数据中确实有消减，但这些证据不支持把它称为剩余 final52 大误差的主要原因，更不能保证其他七层或最终 52 项会改善。还未考虑重新编译的融合/舍入变化。因此当前不优先构建这个候选。

另一个只读辅助结果：保留已有 SIM 操作数、只将最后 affine 组合换为 FP32，最大误差仍为 0.755664937198；而将原 FP32 操作数逐个先舍入再做旧 F16 affine 时最大误差只有 0.003458738327。这提示首层进入 affine 前已带入明显偏差，但混合参考操作数的算术反事实不能独立定位具体算子或证明修复。

## 下一步：仅主机的 normalized 边界实验

Root 已把以下实验分配给 NPU 作者，本文保存时尚未获得其结果，不据此标记精度通过：

1. 原始 F32 坐标按原图做前端中心化/尺度归一化，在固定 `model_1/tf.math.truediv_1/truediv` 输出处切分。原节点、参数、常量、轴、归一化规则保持不变。
2. 先验证未舍入切分重新组合与原完整图等价，并继续通过原 TF 的全部 92 组 FP32 门 `maxAbs≤1e-5`。不能因改变模型入口就丢掉前端参考。
3. 只把该 normalized 张量做 F16→F32 roundtrip，后段仍保持原 FP32 运算；对全部/真实/合成样本及对齐最坏项报告原固定门。它只评估边界选址，不代表真实 NPU 后段精度。
4. 在看到这个结果及逐层证据前，不创建新的 RKNN 编译候选。如果改善明显，再讨论隔离的 CPU F32 前端 + RKNN 后段；后段内部 F16、板端误差、额外复制及联合资源成本仍需独立验证。

这是一种应用级拆分候选，不是声称 Toolkit1.3 支持任意层强制 CPU/F32。若仍失败，继续固定最少观测点区分前端误差与内部误差；官方 `accuracy_analysis(target=None)` / 小写 `verbose='debug'` 可作为后续诊断选择，但本文没有调用它们，也不假设其 golden 一定等同原 TF。保持原 52 项、语义和误差门；不清零表情、不调整常量或 epsilon 来“通过”。

## 来源固定与复现

下表 `B` 表示 `E:/tripo/output/mirror-program/20261003`，`D` 表示 `B/blendshape-numerical-diagnosis`。四个 `diagnostic.json` 内分别包含每个被读取 F32 文件的 SHA、长度、shape；下方复现程序先固定这四个清单，再逐文件核验，不只信文件名。

| 来源 | SHA256 |
| --- | --- |
| 本地 `rknn/api/rknn.py` | `7ba303d017f4874757e00ac87d570e49e15403fe67ed21bcdef137e4bd670ace` |
| 本地 wheel `METADATA` | `5e4fb6dada4d9d0810e7500e56bb4af89956bc559a426efcbdaa046be18d494a` |
| `native/vendor/rknn/rknn_api.h` | `f280732314c2d9dae871faa84946efaa8477499579236474fe0c9ea8b018571e` |
| `B/blendshape-broadcast/gamma-conv-mulpow-v1/numerical-validation.json` | `8e9b99d024f723eb5c3f0e7736431b40b882da955800cd6bc242ea3863793746` |
| 同目录 `gamma-conv-mulpow.onnx` | `fbfed69de13a93e5f0ce3c48843261651c353e66ca30ded2a9f08a33429ce287` |
| 同目录 `reference-fixtures.npz` | `45dfa3e2781ede9599758874346e5532f8e250be2f640a6293b2f3e2bc97a6e8` |
| `D/all-ln-negmean-copy-v1/candidate.onnx` | `5d55f9f012499d177717e0a97954ee80858a0c2c0e67e4bed03313cde1e26200` |
| `D/ln-taps-v1/diagnostic.json` | `de3cd790741e93b2aaa0c7c9bf4f099f46d4870a3cfbc1c60b2778cb4f28d82b` |
| `D/ln-taps-simulator-v1/diagnostic.json` | `0e7146b0f097eec56bbe43743c626bdc3e988265157f63e9a5717a878b6223e6` |
| `D/all-ln-negmean-copy-v1/diagnostic.json` | `7e12160f8e2660a080901ad753e47a73810250323cf173499f45c337ce959318` |
| `D/all-ln-negmean-copy-v1-simulator/diagnostic.json` | `bcb7e2cd8f90927281fbd02f5e7141402f0ca00dabfcdac1f1ab888b0099069a` |
| `B/blendshape-all-ln-negmean-copy-device-v2/comparison.json` | `ceaa1d88f1b11e4a27e4872bed285b5d87685ee9e33f77dd170786003765eede` |
| 同目录 `output-final52.f32` | `4e21fd7d98e059dedc75c6fe1548ee27251a235e25fb886d08fe080cba6a4f3a` |

在 Windows PowerShell 中执行下面的命令。它使用已有 WSL venv，只读文件、内存 ORT 和 NumPy，输出 JSON 到标准输出，不使用 RKNN 对象、build、设备 target 或 ADB。`-B` 禁止写 Python bytecode。

```powershell
@'
from pathlib import Path
import sys, json, hashlib
import numpy as np, onnxruntime as ort
sys.path.insert(0, '/mnt/e/tripo/device-lab/scripts')
from diagnose_blendshape_numerics import load_frozen
B=Path('/mnt/e/tripo/output/mirror-program/20261003')
D=B/'blendshape-numerical-diagnosis'
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def pin(p,h): assert sha(p)==h, str(p)
v=B/'blendshape-broadcast/gamma-conv-mulpow-v1/numerical-validation.json'
pin(v,'8e9b99d024f723eb5c3f0e7736431b40b882da955800cd6bc242ea3863793746')
p,inputs,tf=load_frozen(v,'gamma-conv-mulpow')
pin(p,'fbfed69de13a93e5f0ce3c48843261651c353e66ca30ded2a9f08a33429ce287')
c=D/'all-ln-negmean-copy-v1/candidate.onnx'
pin(c,'5d55f9f012499d177717e0a97954ee80858a0c2c0e67e4bed03313cde1e26200')
opts=ort.SessionOptions(); opts.intra_op_num_threads=opts.inter_op_num_threads=1
def run(path,data):
    s=ort.InferenceSession(str(path),opts); name=s.get_inputs()[0].name
    return np.stack([s.run(None,{name:x})[0] for x in data])
raw=run(p,inputs); rounded=run(p,inputs.astype('f2').astype('f4'))
assert run(c,inputs.astype('f2').astype('f4')).tobytes()==rounded.tobytes()
pins={
 'ln-taps-v1':'de3cd790741e93b2aaa0c7c9bf4f099f46d4870a3cfbc1c60b2778cb4f28d82b',
 'ln-taps-simulator-v1':'0e7146b0f097eec56bbe43743c626bdc3e988265157f63e9a5717a878b6223e6',
 'all-ln-negmean-copy-v1':'7e12160f8e2660a080901ad753e47a73810250323cf173499f45c337ce959318',
 'all-ln-negmean-copy-v1-simulator':'bcb7e2cd8f90927281fbd02f5e7141402f0ca00dabfcdac1f1ab888b0099069a'}
arrays={}
for directory,h in pins.items():
    manifest=D/directory/'diagnostic.json'; pin(manifest,h)
    for row in json.loads(manifest.read_text())['outputs']:
        path=manifest.parent/row['file']; pin(path,row['sha256'])
        assert path.stat().st_size==row['bytes']==92*row['elements']*4
        data=np.fromfile(str(path),'<f4').reshape((92,)+tuple(row['shape']))
        assert np.isfinite(data).all()
        arrays[directory,row['label']]=data
sim=arrays['all-ln-negmean-copy-v1-simulator','final52'].reshape(92,52)
dp=B/'blendshape-all-ln-negmean-copy-device-v2/output-final52.f32'
pin(dp,'4e21fd7d98e059dedc75c6fe1548ee27251a235e25fb886d08fe080cba6a4f3a')
dev=np.fromfile(str(dp),'<f4').reshape(92,52)
def error(a,b):
    e=np.abs(a.astype('f8')-b.astype('f8'))
    return dict(max=float(e.max()),mae=float(e.mean()),
                worst=list(map(int,np.unravel_index(e.argmax(),e.shape))))
report={'versions':dict(numpy=np.__version__,ort=ort.__version__),'input_only':{}}
for name,a in [('fp32',raw),('rounded',rounded),('sim',sim),('device',dev)]:
    e=np.abs(a.astype('f8')-tf.astype('f8'))
    report['input_only'][name]={k:error(a[sl],tf[sl]) for k,sl in
        [('all',slice(None)),('real72',slice(0,72)),('synthetic20',slice(72,None))]}
    report['input_only'][name]['cases_gt_01']=int((e.max(axis=1)>.01).sum())
    report['input_only'][name]['finite_range_ok']=bool(np.isfinite(a).all() and a.min()>=-1e-5 and a.max()<=1.00001)
    report['input_only'][name]['gate']=bool(e.max()<=.01 and e.mean()<=.002 and report['input_only'][name]['finite_range_ok'])
report['sim_vs_input_only']=error(sim,rounded)
report['aligned_cases']=[dict(case=i,channel=j,tf=float(tf[i,j]),input_only=float(rounded[i,j]),
    sim=float(sim[i,j]),device=float(dev[i,j])) for i,j in [(20,19),(86,21),(87,47)]]
def arr(directory,label): return arrays[directory,label]
q=lambda a:a.astype('f2').astype('f4')
sx=arr('ln-taps-simulator-v1','token_embedding').transpose(0,1,3,4,2)
sm=arr('ln-taps-simulator-v1','mean').reshape(92,1,1,97,1)
ss=arr('all-ln-negmean-copy-v1-simulator','restore_scale')
sa=arr('all-ln-negmean-copy-v1-simulator','x_scaled')
sz=arr('all-ln-negmean-copy-v1-simulator','negative_mean_scaled')
old=q(q(sx*ss)+q(-sm*ss)); new=q(q(sx-sm)*ss)
assert q(sx*ss).tobytes()==sa.tobytes()
assert q(-sm*ss).tobytes()==sz.tobytes()
assert old.tobytes()==arr('ln-taps-simulator-v1','affine').tobytes()
ref=arr('ln-taps-v1','affine')
report['ln_order']={name:{k:error(a[sl],ref[sl]) for k,sl in
    [('all',slice(None)),('real72',slice(0,72)),('synthetic20',slice(72,None))]}
    for name,a in [('old',old),('center_first',new)]}
rx=arr('ln-taps-v1','token_embedding').transpose(0,1,3,4,2)
rm=arr('ln-taps-v1','mean').reshape(92,1,1,97,1)
rs=arr('all-ln-negmean-copy-v1','restore_scale')
report['ln_auxiliary']={
    'sim_operands_fp32_affine':error((sx-sm)*ss,ref),
    'reference_operands_rounded_old':error(q(q(q(rx)*q(rs))+q(-q(rm)*q(rs))),ref)}
print(json.dumps(report,indent=2,allow_nan=False))
'@ | wsl.exe -d Ubuntu-22.04 -- /mnt/e/tripo/native-tools/rknn13-venv/bin/python -B -
```

原独立重放及从本文件直接提取命令的复验均终态 exit 0，使用 NumPy 1.19.5 / ORT 1.6.0；原图与八处复制图的舍入输入输出逐位一致，首 LN 三项逐位断言通过。上述统计不是新 RKNN 性能结果，亦不证明后续 normalized 边界实验或 CPU/NPU 拆分已经通过。
