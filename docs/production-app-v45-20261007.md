# 正式 App V45：普通渲染参考与联合负载复核

V45（0.2.14 / versionCode45）已同签名覆盖安装并通过本轮检查。默认仍是普通 INDIVIDUAL 渲染；分材质 shader 候选保持关闭。本轮补齐它与普通路径的直接像素对照，并纠正此前只对调试合批路径报告约26%收益的适用范围。16+视点30FPS、实时USB采集恢复和完整作品验收仍未完成。

## 这轮实际改动

- 像素检查器使用同步VERIFY：一次计算表情/头姿，原primitive与packed两套独立VBO/IBO接收相同CPU形变结果，共用原贴图。普通draw与显式候选drawBatched分别绘制，不能把VERIFY中的draw误当候选。
- 两侧每姿态实际提交28次绘制/4组，并检查真实CURRENT_PROGRAM；旧packed参考绘制必须为0。普通运行仅增加返回成功的绘制/完整组计数，原shader、姿态、上传和材质运算未改；glGet只出现在诊断helper。
- Scene及诊断清理尝试所有资源类别并保留首异常，后续错误作为suppressed；不把驱动删除失败描述成已物理释放。
- 测试CLI两条过时帮助文本修正：产品默认已使用持久FBO和混合CPU/NPU表情后段。参数逻辑与采集公式未改。
- 所有78项APK资产、9项原生库与V44逐字节相同。角色选择、背景和四份保存配置保持；没有新增模型或降材质版本。

## 实际Mali像素门：V45 APK

独立Activity、本轮UUID `9c0a2948-6016-46bf-8634-7aeea1be0051`，普通INDIVIDUAL_OVR4对per-entry specialized OVR4。69组真实Rig回归姿态 × 16个独立400×640层，共1104对；同保存场景、1200/1920投影比例、原Geralt模型 `9381f452…ef531`。

| 检查 | 结果 |
|---|---:|
| 普通/候选实际绘制 | 1932 / 1932 |
| 完整四视图组及绑定检查，各侧 | 276 |
| 旧packed参考绘制 | 0 |
| 最大RGB字节差 / 允许值 | 1 / 1 |
| 最大每层RMSE / 允许值 | 0.001976424 / 0.1 |
| RGB不同字节数 / alpha差 | 351 / 0 |
| 每姿态两侧16个不同视点哈希 | 通过 |
| scope结束候选关闭并释放 | 通过 |

1104个普通reference哈希还与V44有限同姿态的packed reference逐个相同。这不证明全部姿态、光学对位、现场表情自然性或FPS；像素读回耗时也不作为跑分。主机独立复算了1104行与绑定/计数，310项实际Scene/Rig/Deformer/双buffer行为检查及独立代码复审通过。

## 联合短测：固定V44 APK，普通／候选／普通

性能原始数据采自尚未更换的V44 APK `8e6a6f33…f652a6`；本轮V45 APK负责上面的新像素门及普通启动检查，未将旧APK数据称作V45性能实测。采集器源码快照也固定为V44字节。

三组各请求60秒，预先约定使用所有确认INTERACTIVE区间且至少40秒。完整采集/呈现门均true；各约4.75–4.98秒未确认尾部排除。旧V44固定35秒辅助窗口false原样保留。

输入同一853帧NV21录像 `face-reference-stable-20261001-01`，名义24.369907FPS；无USB采集、无视频解码。完整Geralt/PBR、16×400×640/1200×1920交织、异步CPU形变、4组持久FBO、逐帧VP、目标31；RKNN检测/478点 + CPU FP32归一化 + 混合CPU/NPU 52后段。只有候选改变batched/specialized两项；GPU timer query pool0。

| 确认范围指标 | 普通首次 | 候选 | 普通复测 |
|---|---:|---:|---:|
| SF实际呈现FPS | 10.5459 | 11.2921 | 10.5407 |
| 确认活跃秒数 | 44.6893 | 44.7494 | 44.7307 |
| 整机CPU占用% | 57.13 | 61.56 | 57.94 |
| GPU busy% | 97.98 | 97.93 | 98.25 |
| NPU busy% | 46.14 | 48.09 | 46.66 |
| App PSS均值/峰值MiB | 301.50/410.17 | 295.86/402.82 | 302.70/420.46 |
| 温度范围℃ | 58.888–63.333 | 63.888–68.125 | 68.125–71.666 |

候选相对两次普通参考均值的呈现提升 **7.1018453%**，普通参考彼此约0.05%差。GPU原始采样为活跃800MHz高负载；不是低频限制的证据。单次顺序ABA不能排除温态或证明长期收益，稀疏PSS不能宣称内存优化。

相邻活跃快照可归属的面捕子范围约35.1秒，完整有人脸结果吞吐16.099/15.250/15.969FPS，received→completed均值122.40/142.05/126.00ms。候选呈现更快，但CPU增加、面捕略慢且处理延迟增加；这些子范围不与SF范围等同。完整input session（含acquisition/idle）的face FPS为15.836/14.998/15.712，另一个统计范围。所有均值从累计总量差分，未直接减均值；expression context重建后不跨对象拼累计值。

活跃快照result age最大277/290/304ms，存在状态切换时INTERACTIVE与renderer.face_active不同步的快照；未约定age上限，不宣布每帧新鲜度已通过。已应用pose age与输入结果age起点不同，不能相加冒充端到端。候选保持默认OFF，未取得整体生产体验资格，30FPS三组均false。

## 正常启动与保留

V45新runtime会话 `304c9dc2-87aa-4074-9819-18bcd1afa8f8` 的普通Geralt首GL帧通过，实际普通绘制280次；默认candidate/ORM/fast-math/query均关闭。正常Back返回首页，角色页smoke通过，JDI实际Java runtime owners为0。四配置hash与安装前相同；未重跑V44整套角色浏览验证，UI代码保持该版本字节。

USB仍报Camera2 `CAMERA_ERROR (3): endConfigure ... Function not imp`。当前用户不在设备旁，不重复要求插拔。录像测试和中性模型预览不冒充实时恢复；ADB正常。未刷固件、root、重启或清数据。

APK：`AvatarRuntime-v45-individual-reference-v1.apk`，SHA256 `309e4a977b65d35da5284e293fb8a7eee5bccbcab70671ddf7183b11b8c8edd3`；原签名证书 `64a4af6b…bf6da8`。测试、截图、原始JSON/journal/复审及配置证据保存在私有目录 `output/mirror-program/20261007/v45-ordinary-reference-v1` 与两个peer目录，私人录像不发布到仓库。

## 下一步

从实际COLOR_0确认主表面13975顶点全白，其余五个primitive有真实颜色变化。下一候选仅消除经过bit-equal验证的主表面aColor/vColor，其他颜色继续插值；不删除共享color VBO、不按逻辑字节宣称实际释放或收益。推广须直接对普通reference过同姿态像素门，不能将两个maxRGB1门按传递性当作普通→新也maxRGB1。之后同APK再测呈现/面捕/温度和资源。NPU形变仍是私有仿真候选，smooth-normal精度未合格；其他头部自然表情、材质和口腔优化继续。

仓库历史保留既有previous_v43/previous_v42完整JSON。后续版本引用旧release的Git commit/路径/hash，避免再次嵌套完整旧manifest；旧记录仍可精确读回。