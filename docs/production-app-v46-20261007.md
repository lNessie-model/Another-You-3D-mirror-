# 正式 App V46：主表面常量顶点色与同包联合复核

V46（0.2.15 / versionCode46）已同签名覆盖安装。Geralt 主表面经实际 packed 数据验证全白后，新候选去掉该表面的颜色输入与插值，其余有颜色变化的 primitive 保留。实际 Mali 直接普通→新候选的1104层像素门通过；同一个 V46 APK 的五组联合短测，新候选呈现12.1229FPS，相对两次普通参考均值提高15.0416%，相对两次旧分材质候选提高7.4565%。30FPS目标仍未达到，默认普通路径保持，候选仅显式调试启用。

## 实现及保护边界

- 只适用于原 Geralt `9381f452c53098314f97e1a1799ec55d2be37878e66bf205c7a26958afcef531`。主 mesh0/primitive0 共13975顶点，各 RGBA 必须逐项 raw-bit 等于 float1；检查全部 packed range 和实例，不依赖材质描述猜测。非法范围、间隙、非白、NaN、越界等使显式候选整体失败。
- 主 vertex shader 不读取 aColor、不输出 vColor；fragment 用常量 `vec4(1)`，保持原 PBR 运算和乘法次序。其他五个 primitive 仍读取并插值真实顶点颜色。program key 含完整 fragment 源码，避免常量与非恒定模式错配。
- 显式候选每 program 初始化时用真实 GL attribute location 核验：主表面 single/multiview 均为-1；其余均为2。普通关闭路径不新增该查询。单次16视点仍28次实际绘制、4完整组；draw顺序和原 shader 生成器保持。
- 没有删除/重排共享 color VBO，不将省掉shader属性解释成已经节省显存或确定减少物理带宽。
- 新参数 `--constant-white-primary` 默认false，只能与 `--avatar-batched --specialized-batch` 同时显式启用，禁止与 ORM/PBR/空白采样等诊断组合。Android debug typed boolean、null/type错误、依赖/互斥、GL初始化后修改和关闭必需specialized路径均有边界检查。普通产品入口不启用。
- 新主机检查252项（75策略、177实际生产GL调用边界）通过；已有57693材质/布局、200生命周期、528旧shader检查保持通过。实际编译8个改动生产类及55配置检查、6CLI参数案例、310 Scene/Rig/Deformer行为检查通过。GL stub只证明调用边界，实际shader链接和像素由下述设备检查证明。
- 初次构建在最后setter保护改动前完成，配置检查及时发现缺失保护，该包未安装。最终build-v2在15项源码冻结后重新构建，55配置检查通过，DEX含保护代码，构建前后源码hash精确一致。最终证据统一使用这一APK。

## 实际 Mali 直接像素门

新会话 `d349cd92-59ec-4ce7-9196-64b77fe12603`，普通 INDIVIDUAL_OVR4 直接对 `per_entry_specialized_constant_white_ovr4`，没有把两次中间像素门当成可传递。69真实Rig姿态×16独立400×640层，同保存场景、1200/1920投影比例、原完整PBR/贴图。两套独立primitive与packed VBO/IBO接收同一CPU同步VERIFY形变，共用原贴图。

| 检查 | 结果 |
|---|---:|
| 普通/候选实际绘制 | 1932 / 1932 |
| 每侧完整四视图组与真实绑定检查 | 276 |
| 旧packed参考绘制 | 0 |
| 最大RGB字节差 / 允许值 | 1 / 1 |
| 最大每层RMSE / 允许值 | 0.001976424 / 0.1 |
| RGB不同字节数 / alpha不同字节 | 351 / 0 |
| 主表面真实linked color attribute | -1 / -1 |
| 其他表面真实linked color attribute | 2 / 2 |
| 每姿态两侧16个不同视点、非空图像 | 通过 |
| scope结束候选关闭、资源释放检查 | 通过 |

1104个普通reference哈希还与V45有限同姿态reference逐个相同。Root与独立peer分别核对全部1104行、绑定、结束状态和颜色范围。该有限回归不证明全部姿态、光学对位、现场表情自然性或FPS；像素读回不作为跑分。

## 同 V46 APK 五组联合短测

固定最终APK，依次普通／旧分材质／新常量色／旧分材质复测／普通复测，每组请求60秒。事前冻结采用全部确认INTERACTIVE区间且至少40秒，五组完整采集和呈现门均true；各组未确认尾部保留且排除。没有选择固定前35秒替代新门，也没有相互拼接不同session。

输入同853帧NV21录像 `face-reference-stable-20261001-01`，名义24.369907FPS，无USB采集、无视频解码。完整Geralt19907顶点/19157三角形和原PBR/贴图、16×400×640独立视图/1200×1920交织、异步CPU形变、4组持久FBO、逐帧VP、目标31；RKNN检测/478点、CPU FP32归一化和混合CPU/NPU52后段。GPU timer pool0，ORM/PBR/空白/背景缓存诊断关闭。旧分材质与新候选之间仅常量色flag不同；普通对比另外改变batched/specialized两项。测量期间不做截图/JDWP/GPU timer。

| 确认范围指标 | 普通首次 | 旧候选首次 | 新常量色 | 旧候选复测 | 普通复测 |
|---|---:|---:|---:|---:|---:|
| SF实际呈现FPS | 10.5471 | 11.2980 | 12.1229 | 11.2653 | 10.5285 |
| 确认活跃秒数 | 44.4506 | 44.5878 | 44.6800 | 44.6804 | 44.4160 |
| 未确认尾部秒数 | 4.7321 | 4.7746 | 4.8618 | 4.9339 | 4.7173 |
| 整机CPU占用% | 57.06 | 61.45 | 61.13 | 61.20 | 57.60 |
| GPU busy% | 98.36 | 98.28 | 97.84 | 98.09 | 98.30 |
| NPU busy% | 46.82 | 48.16 | 46.30 | 48.74 | 46.16 |
| App PSS均值/峰值MiB | 304.42/425.37 | 281.60/399.94 | 300.17/408.54 | 279.52/398.94 | 301.27/421.26 |
| 温度范围℃ | 60.555–65.625 | 65.625–70 | 70–72.777 | 72.222–75 | 72.777–76.875 |

相对旧分材质两端均值，新候选收益 **7.4564729%**；相对普通两端均值 **15.0415652%**。五组都是同一个新APK，区别于V45用V44 APK测性能的版本边界。顺序短测温态不同，不能排除温度影响或证明长期收益；稀疏PSS不证明释放了显存/内存。GPU持续高负载，NPU busy约46%不代表单纯提高占用即可帮助交织。所有组30FPS均false。

相邻同session、同确认区间INTERACTIVE快照可差分的面捕子范围分别30.0968/35.2200/35.2616/35.2060/35.2129秒，不能与约44.4秒SF/资源范围等同。完整有人脸处理吞吐分别15.9486/15.1334/15.7679/15.0543/15.9885FPS，received→completed均值125.4473/143.2034/130.7566/143.7473/125.7114ms。新候选相对旧候选面捕改善，相对普通CPU约增加3.8百分点、面捕略慢、处理均值约增加5.2ms。不同阶段均值重叠，不能相加作GPU或端到端耗时。

活跃快照result age最大210/325/292/321/213ms；后四组有末端INTERACTIVE与renderer.face_active不同步的快照，原样记录。没有事前定义freshness上限，不宣布每帧新鲜度通过；applied pose age与input result age起点不同不能相加。候选本轮未取得整体日常默认资格，默认保持OFF。

## 正常入口、资产与恢复

最终新runtime会话 `4d8a56dd-e3ec-4307-bf23-bd2536d21174` 的普通Geralt首帧通过，实际普通绘制280次，specialized/constant-white均false，原PBR正确。正常Back回首页，角色页smoke通过，JDI实际Java runtime owners为0；四保存配置hash精确保持。全部78 assets/9 native entries与V45逐字节相同，没有新增模型或降低材质。UI布局沿用，已捕获本轮实际首页和角色页供远程查看；未重跑V44完整角色浏览suite。

USB仍报Camera2 `CAMERA_ERROR (3): endConfigure ... Function not imp`，本轮不能称实时面捕恢复；用户不在设备旁，不重复要求插拔。录像/中性预览不冒充实时采集。ADB正常，未刷固件、root、重启或清数据。

最终APK `AvatarRuntime-v46-primary-color-v1.apk`，SHA256 `ac201955cf475f002b5152ab93a6877039c750c4cb8bf495ad9db8c3b0cd11fb`，原签名证书 `64a4af6baa9f10fd9d5bd09003ded7def368211604e3e91181bf015cdfbf6da8`。私有原始数据、命令journal、录制输入引用、截图、构建/主机检查保存在 `output/mirror-program/20261007/v46-constant-color-qa-v1`；独立复审在 `v46-constant-color-peer-v1`。APK和私人录像不发布到代码仓。

## 继续工作

优先降低候选CPU绘制提交的重复program/VP更新，再直接过原普通像素门和同包联合面捕/资源检查；本轮没有实施这一步或提前宣称收益。NPU形变仍是私有原型，smooth-normal误差未达标，未接入设备；其他历史头部自然52表情、真实材质/口腔、三维背景和USB恢复仍待完成。完整正式作品和16+独立视点30实际呈现FPS保持原目标。

仓库保留所有既有previous_v43/previous_v42等完整历史JSON；上一轮V45以Git commit/路径/SHA引用，不再次嵌套整份旧release manifest。每次经检查的迭代同步，私人诊断和未选入WIP保持原状。
