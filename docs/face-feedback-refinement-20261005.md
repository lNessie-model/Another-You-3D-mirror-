# 面捕反馈精修与接续，2026-10-05

用户不再手调，由作者直接修四个问题：眉头持续下压、微笑弱而不自然、张嘴下缘尖、闭眼/睁大眼响应不足。用户明确授权多个子 agent 并行；root 统一操作 ADB。

## 实现

- `FacePlayback` v3 对 browDown 设置 0.12 的初始死区，抬眉时抑制冲突下压；嘴部及抬眉继续使用端点保留的强度曲线。眼睛单独响应：blink 原始 0.04–0.72 经余弦映射，wide 使用 1.65 指数；视线、眯眼、闭唇补偿保持原语义。这些阈值是初始艺术响应，尚非用户个人测量。
- 阶段11的抬眉字段在切眼眶之前的原三角载体上制作，再插值到切割顶点，避免 T 接点裂缝。眼睑内圈的所有眉毛字段归零，防止抬眉拉开已闭合的眼睛。已部署主候选为 6 mm 峰值、较宽上方衰减、鼻根局部减弱，下压几何为原来的 65%。后续原生纹理射线发现真正可见灰色眉毛主要位于 RetopologizedSkin Ring 第1–4行；此前九个标为 gray 的 Skin 探针实际命中棕色皮肤阴影，不能用它们的位移代表灰眉头改善。
- 微笑的唇线抬升按每侧距中心的 q² 分配，嘴角上扬，中央安静；脸颊联动保留。皮肤、胡须、唇环共用载体插值，避免原 W 型唇峰及新的裂缝。
- 张嘴下唇中心改为平缓 U 形。中心区域开口下降量比例由约 0.82 改为 0.981；张颌闭唇组合的最大配对间隙约 1.28e-9 m。下颌与牙齿仍使用既有独立节点。
- 口腔上下各 10 颗牙、5 种牙型，深腔与厚舌体替换旧简化口腔。最终阶段11包含19747顶点、18837三角形、7个绘制实例，GLB 28686028字节、解码资产73538414字节，低于现有资源上限。舌根与口底交叠，前缘沿用真实96个内唇点及其形态键。详见 `oral-detail-20261005.md`。

## 验证与已发现的问题

真实 Java 响应测试 335 + 463 项通过。v30 离线 Gradle 构建、APK 签名验证通过，签名与 v29 一致；生产 `drawAvatarViews` 确实先调用新的 FacePlayback，再交给 AvatarRig。

v2 的眉上黑裂缝、v3 的挑眉拖动眼皮、v5 的外眉叠加闭眼泄漏都已作为失败证据保留。初始 19 姿态未覆盖全部眉毛组合；独立子 agent 补充 6 组完整闭眼/内眉/外眉/下压组合，3 个视角每眼 2145 射线。

v7仍发现鼻侧皮肤与眶桥之间约0.1 mm缺口，极限组合有2–4个采样看到眼球。眼球后退0.3/0.6/1 mm均未解决，因此没有改眼球中心。阶段11用两个真实皮肤克隆点、两个小三角面封住此缝，全部原数组前缀、PBR、节点与绑定逐项保留。204项原字段比较、44姿态接点检查、6组闭眼/眉毛组合×3视角全部通过；每个视角每眼2145条射线均未看到闭合后的眼球。该门槛是指定采样组合的遮挡检查，不是全角度碰撞证明。

真实Blender生产对照253状态、47058项检查、68个驱动通过，最大形态权重误差约1.46e-7、节点矩阵误差约1.19e-7。实际单键1毫米编辑隔离检查通过。生产有界形变worker的64次输入与同步输出逐位相同，57项断言和22次退出检查通过。原未保存手调GUI没有被覆盖。

口腔 stage10-v4 独立检查通过：19 实际姿态前壁唇缘误差为 0，闭唇 14640 个牙齿采样点均被遮挡，牙弓刚性误差约 9.53e-11 m。PBR 图片、原节点及口腔以外字段在口腔替换阶段逐项保留。

## 软件、设备与积分状态

v30 APK：`E:\tripo\output\mirror-program\20261005\AvatarRuntime-v30-face-response.apk`；SHA256 `45e3e0893ed9dd3d44f6b8eab7a9176af99de356de6def5e9871d9d1657af62f`。来源与日志在相邻 `v30-face-response-build`。

设备已安装v30与阶段11：`E:\tripo\assets\mirror-models-20261003\lowpoly-heads-v2\geralt-rig-stage11-orbital-closure-v1`，模型SHA256 `11397607700b2223d63b5cd05bb2f929fbc18d3dbc80b71ab47e0375a909041d`。发布前核对与v29的原生库字节相同、签名相同；真实Mali驱动的10个固定姿态，以及serial/OVR、individual/batch多视图门槛均通过。

完整部署回执在 `E:\tripo\output\mirror-assets\20261005\face-feedback-device-v2\receipt.json`，旧APK、模型、manifest和配置均已备份，场景/运行/选择配置前后字节相同。v1首次部署的驱动门槛通过，但恢复摄像头时检查脚本未处理加载中的null avatar，触发已验证的v29/阶段6回退；空值处理修复后v2完整通过。后续回退路径也明确恢复旧配置字节。

45秒录制人脸联合测试完成，输入为原录制的人脸视频，NPU关键点/表情、CPU形变、16×400×640的真实多视图与交织同时运行。SurfaceFlinger确认INTERACTIVE的29.786秒内317个呈现帧，实际10.623 FPS，完整呈现历史；交互期间整机CPU均值60.01%、GPU98.69%、NPU46.07%，应用PSS均值242.89 MiB/交互峰值318.03 MiB，最低可用内存897.65 MiB，温度76.875–78.75℃。全45秒含启动的PSS峰值355.74 MiB。报告在 `E:\tripo\output\mirror-assets\20261005\face-feedback-performance\stage11-v30-16v-replay.json`。

本次温度高于旧v29测试，不据10.835与10.623的差值判定稳定性能回退；这也不是持续热稳定测试。16视点30FPS尚未达到，主要瓶颈仍为GPU。结束后已恢复实时摄像头，状态归档为同目录 `restored-live-status.json`，采集约24.43 FPS、无捕获错误、当前WAITING/无人脸。采集FPS与实际多视图呈现FPS不是同一指标。

个人闭眼/睁大/眉毛阈值尚未现场测量，屏幕光学对齐和表情自然度尚未验收。强挑眉仍保留底模尖褶。阶段12-v1/v2均未接受：v2在两个闭眼组合的右视角各出现1个眼白漏出采样，眉头改善仍不足；候选没有覆盖设备阶段11。stage12-v2 的 actual-gray-brow-surfaces 属历史探针记录，后续纹理检查已纠正其中命中 Skin 的九个 gray 标签。真正灰毛定位使用 face-geometry-review 内的 true-gray-pigment-native-rays.json 与 true-gray-ring-barycentric.json。

进一步直接约束求解发现两处旧 Skin 探针的重心行处于128个眶边行的线性张成空间，投影残差范数约7.1e-14/8.7e-15。眶边严格为零时这两处皮肤点也被强制为零，7.5毫米目标不满足。证据在 `E:\tripo\output\mirror-assets\20261005\face-geometry-review\brow-constrained-feasibility-v1`。这不是对真实灰眉运动能力的证明。后续固定 X、连续 Y、指定16条投影面积约束所得约4.1毫米上界，也只适用于旧棕皮肤探针与该约束系统，不能称为整个眉毛或一般三维造型的上限。

局部加密实验 stage12-brow-local-v3 在 Skin 中新增168点，总19915顶点、GLB22965808字节；它拟合的是上述棕皮肤探针。整眼环 Up 归零冻结了真正可见灰眉，实际预览仍尖褶，未接受且未部署，历史证据保留。旧模板眶桥检查报告约121.6微米，但模板旧 Skin 索引未包含新顶点和新三角，不能代表该候选加密后的 Up 曲面。独立按实际加密面复查，31个反馈/补充/半眨眼姿态的923端点贴合误差最大7.893e-9 m，189项非Up字段及新点bary插值、单个父三角内分区、非Up实际表面保持通过。它没有验证跨父三角原边的分割参数一致性，也没有据此宣称所有Up的T接点无裂缝或最终像素不变；动态 smooth-deformed-v1 法线重算可能改变光照。

新的可见灰眉候选从阶段11原 Ring 拟合真正灰毛区域，无需 Skin 加密。visible-brow-v1 技术回归通过，但眉间皮肤尖褶仍在，未艺术接受且未部署。后续预案曾考虑SkinUp×0.35、眉身约1.2毫米；实际生成的 visible-brow-v2-scaled 使用阶段11 SkinUp×0.65、v1 RingUp×0.65，可见灰毛内端约3.9毫米、眉身约0.65毫米，Ring外圈跟随新Skin，并按源同圈上下对应Up差重建Blink×Up补偿。其SHA为7fce21e46a419383f9e7b1077e992e769f1929874ef5efb4938bb08bf354cab6；189项静态源字段/非Up目标及PBR图片字节保持检查通过，不能代替姿态或美术验收。内眼睑第7行必须保持源固定字段，完整闭眼必须在补充组合中零眼白漏出，并通过923端点、半眨眼及3D相对中立叉积法线检查；整个眉毛满Blink回到阶段11旧表面不是v2目标，因为抬眉时眨眼仍应保留自然眉毛上扬。设计调整及尚未完成的检查不能写成已完成或已部署。原阶段11快照已保存于 `E:\tripo\output\mirror-assets\20261005\face-feedback-delivery-stage11`。预览图来自实际模型/骨骼渲染，不是AI重绘，也不是光栅屏照片。

随后唯一待交付候选 visible-brow-v3-wide 改用宽额头载体场，真正灰毛内端目标4.5毫米、眉身1.7毫米；没有新增顶点，只改Skin Up与Ring Up/两条Blink×Up，共四个POSITION字段。模型SHA为375d853ae57e603976f1344c512f9f3ef86b60fefe99c601fe83409828309015。独立按实际19反馈、6补充完整闭眼、6半眨眼/睁大组合及2抬眉端点共33姿态检查，189项非Up目标/静态字段、实际纹理字节及节点绑定保持通过，内眼睑第7行精确保留，923端点最大贴合误差7.445e-9 m；11种完整闭眼姿态的3视角共141570条眼区射线均未漏出眼球，半眨眼的可见眼球采样随闭合强度单调减少。但严格新增反向门槛仍未通过：7次新增XY投影反向、9次新增相对中立3D叉积法线反向，均在Ring，不能据此写成最终通过或部署。3D指标表示相对中立转过90度，不能等同三角退化或全三维自碰撞；例如3211号三角实际面积仍约4.515平方毫米。最终记录在face-geometry-review/visible-brow-v3-wide-independent-regression-v2.json，精确索引保留供作者增加混合姿态约束。此前regression.json使用绝对面积积阈值，既把一个原有负向小三角误归新增，也漏掉部分小三角；v2报告改为源/候选采用同一无量纲符号阈值，旧报告保留为检查器修正记录。

随后局部安全修正 visible-brow-v3-safe 的新模型SHA为3e30c0bea18c3264f39650b4c7bf6fe29c585b6226d6653fe458dbb749c9431d。原拓扑不变，总19747顶点、GLB29071272字节；第5/6行Up恢复阶段11源场，鼻侧第3/4行用实际混合姿态的线性面积/叉积约束作局部修正，再重绑眶桥及配对Blink×Up。独立对wide→safe复查，宽Skin全部目标、7个真实灰毛父三角的21个节点和外第0行逐位保留，内第7行保持源固定；实际灰头4.5毫米、眉身1.7毫米硬点未被局部修正削弱。按新SHA重新真实Java导出的19+6姿态，加6半眨眼/睁大组合和2抬眉端点，共33姿态严格门槛通过：新增XY投影反向0、新增相对中立3D叉积法线反向0、923端点最大贴合误差7.445e-9 m，141570条完整闭眼射线零眼球泄漏，半眨眼可见眼球采样单调减少。189项非Up/静态字段及原PBR图像字节、节点、绑定保持通过。报告为face-geometry-review/visible-brow-v3-safe-independent-regression.json与visible-brow-v3-safe-independent-local-scope.json，精确来源哈希归档于visible-brow-v3-safe-independent-audit.json。这是指定前眉/眼眶区域和采样姿态的技术通过，仍不是全部三维自碰撞或最终美术效果证明；此时仍等待root的实际worker/Blender/设备门槛，不能把离线通过写成已部署接受。

用户随后明确牙齿长短不齐、牙齿太黑，问题不是口腔黑。只读GPU诊断确认旧20颗牙的体积均为正、没有朝内面，NORMAL与实际叉积累加方向最小点积约0.99999996；牙材质无normal/ORM/AO，不是皮肤贴图误采或背面翻法线。旧牙冠实际线性色均值约[0.361,0.320,0.253]，加上当前PBR的方向光/环境项显得暗暖；上牙按不同冠高对齐牙根，使切缘锯齿明显。新stage13只修牙弓，保持深口腔黑色与Face原字段：上下各10颗牙，牙釉质改为不采atlas的WarmWhiteDentition，实际线性色均值约[0.751,0.716,0.647]，roughness从0.32改0.28；牙龈有效线性色保持[0.068,0.020,0.029]，去atlas时已去除旧COLOR补偿，避免连带提亮牙龈。牙与牙龈共用材质，roughness变化也会轻微影响牙龈高光，不能称其所有光照响应完全不变。未修改shader或全场灯光。

最终牙候选为geralt-rig-stage13-dentition-appearance-v3，模型SHA9381f452c53098314f97e1a1799ec55d2be37878e66bf205c7a26958afcef531，总19907顶点、GLB29141924字节。V1没有最终闭唇门槛；V2发现右犬齿根盖中立缝漏出1点；V3把根顶限制到原牙龈缝线以下0.75毫米，作者的17520点、3视角闭唇采样零露牙，上下牙切缘变化约0.1233/0.0974毫米。独立194项非牙数组比较使用dtype、shape及原始字节（含正负零），精确保持已通过v3-safe的全部Face/眉眼/原口腔pr2、节点、绑定与非牙PBR图片；牙龈位置与有效线性色差均约7.45e-9，属float32重新生成舍入。没有冒称新牙模型再次重跑33姿态眉眼射线，保持证明和原3e30模型通过的33姿态报告分别保留。独立记录为face-geometry-review/stage13-dentition-v3-independent-brow-material-preservation.json和stage13-dentition-v3-independent-audit.json。

2026-10-04 21:04 UTC（北京时间10月5日05:04）开始的face-feedback-device-stage13-v3/receipt.json确认，该9381牙候选真实GPU驱动门槛通过。root随后人工查看同目录candidate-renders/jaw-open.png：牙冠暖白、切缘平顺，口腔没有整体提亮，符合本次明确的牙色反馈；这是实际Mali图的人工确认。实时相机故障在部署前就已存在：同目录before-status.json的state为ERROR，错误是CAMERA_ERROR (3): endConfigure，配置流失败。不能把该已有摄像头故障归因为新牙候选，也不因此阻塞工程同步。

这次全流程因相机预览没有推进而passed=false，自动回退核对通过；设备模型仍为阶段11的11397607700b2223d63b5cd05bb2f929fbc18d3dbc80b71ab47e0375a909041d，v30 APK未变。旧回执中的artist_accepted=false是生成时点记录，未覆写；上面的Mali牙色人工确认来自root后续通知，不代表现场实时面捕整体验收。个人阈值与相机重插后的现场复测仍待完成，不能把回执的deployed=true中间状态当成最后持续部署成功。root已请用户重插USB摄像头，外部相机恢复与最终实时部署应使用后续新回执更新。

Tripo 牙齿参考请求网络失败、exit 7、无 task_id；本次实际收费未知，不把预估 40 积分当成扣费，也不把本地程序模型称为 Tripo 生成。总授权仍为 4000，先前确认用量 535；失败请求需后续核对账户。未修改网络、固件或驱动，未执行刷机/重启/长时测试。

## 接续与证据入口

当前绑定为头、眼球、下颌及上下牙的节点变换与Face形态键联合驱动，52个规范输入、7个组合修正，Face共有58个形态键（中立分类输入不对应单独形态键）。不是Tripo自动产出的完整面部蒙皮骨架。新GLB和可编辑Blender工程均保留实际运行时映射；所有作者脚本拒绝覆盖已有输出。

阶段链：stage7-dentition-v2 → stage8-ocular-v6 → stage9-expression-v7 → stage10-oral-detail-v4 → stage11-orbital-closure-v1。对应生成器为 `refine_head_ocular.py`、`refine_head_expression.py`、`refine_head_oral_detail.py`、`patch_head_orbital_gap.py`。眉毛/微笑插值载体使用原stage5-lip-ring-v3，而不是重新猜测被切割的网格。不要把v1–v6的失败艺术候选混作最终v7/阶段11。

- 阶段11目录内：`orbital-gap-check.json`、`feedback-ocular-check.json`、`authoring-check.json`、实际生产姿态JSON、32张反馈预览、`geralt-controls.blend`。
- 补充6组合门槛：`E:\tripo\output\mirror-assets\20261005\face-geometry-review\v11-v1-extra-blink-check.json`。
- v30构建：`E:\tripo\output\mirror-program\20261005\v30-face-response-build\build-provenance.json`。实际Java源文件指纹固定在构建归档，不把后来仅修改的工具/文档当作重建APK。
- 部署故障注入：`E:\tripo\output\mirror-program\20261005\preview-deployment-audit\fault-injection-tests.log`，6场景通过。真实device-v2另有`same-run-audit.json`，确认两份门槛报告来自同一新run_id。
- `diag_*`是只读调查工具，记录像素位置、折叠和假设性眼球后退/接片。尤其`diag_face_geometry_response.py`的默认字段对应早期试验，不是最终场形的通过门槛。最终通过结论使用上面的实际资产SHA及独立检查文件。

源码按运行时响应、口腔、眶桥、唇形、局部接片、部署恢复分别提交到`codex/mirror-runtime-iteration`。当前最新设备状态与续做入口在 `E:\tripo\output\mirror-assets\20261004\设备当前状态-最新.md`、`续做入口-最新.md`；其较老段落仅是历史。
