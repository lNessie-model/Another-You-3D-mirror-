# V37 / 0.2.6：完整 PBR 算术候选与真机对照

V37 保留原 PBR 为默认路径，新增显式调试候选及可复核的画面对照。候选把 Schlick 五次幂写成乘法、合并 Smith/spec 分母，保留 highp、GGX roughness 下限、掠射夹限、原三贴图、导数 TBN、所有 normalize、灯光和 sRGB。完整 Geralt、真实独立视点和表情绑定不变。同包联合测试未显示提速，候选继续默认关闭。

APK：versionCode 37 / versionName 0.2.6，130621351 bytes，SHA256 `58ffb077578ea64314724d4d8f5422e1b436953b6ffd6e6b5b645e127286c1e7`。SDK35/JDK17离线构建，原签名匹配；78 assets与9 native entries和V36逐字节一致。包名与覆盖升级路径保持，原四份设置复核一致。内部调试安装版仍可 ADB；未刷机、重启、卸载或清除数据。本轮没有付费生成。

## 画面与真实程序检查

`AvatarPbrFastMathCheck` 用同一同步 Geralt VERIFY scene、同一纹理/VBO，每姿态一次 prepare；各自比较 serial-individual 与 batched-OVR4 内的 reference/candidate。固定九姿态为neutral、两侧blink、jawOpen、jawClose corrective、两侧yaw、smile+blink+tilt和all-controls。每种绘制方式均16独立相机矩阵与16不同layer hashes，共288组，prepare=9（另有scene构造中性初始化）。这不是全部69表情回归，也不是光学验收。

实际ARM Mali-G52通过：最大RGB差1级，最高逐层RMSE0.001141088661469096，总共4个颜色字节不同，alpha差0；各后端实际原/候选程序绑定检查144/144与36/36（GL_CURRENT_PROGRAM）；状态恢复和清理通过。36张中心view8 PNG（18对）的文件SHA独立复核，查看中性及复合微笑未见可察觉变化。数值初筛上限RGB1/RMSE0.1，有可见几何与opaque守卫；passed不自动表示美术通过。阻塞读回79.974秒只用于诊断，不能当作FPS。

## 同时运行的短测

三组同APK各45秒：完整19907顶点/19157三角形/6 primitive Geralt、16×400×640独立视图、1200×1920输出、batched OVR4、4个persistent FBO、缓存camera VP、异步CPU morph；相同已保存pitch/tan、镜像、gain与场景参数。输入为动态人脸NV21录像的memory-map，经RGA转色、RKNN478与normalized mixed CPU/NPU52。检测到人脸的比例均1，表情52项，面捕约15FPS。没有USB采集或视频解码。所有GPU queries关闭（pool=0）。

| 路径 | 实际呈现FPS | 整机CPU% | GPU busy% | NPU busy% | PSS均值/峰值MiB | 温度°C |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 原版前测 | 8.897365 | 56.32 | 98.50 | 47.27 | 262.12 / 353.54 | 59.44–63.33 |
| highp算术候选 | 8.816525 | 56.21 | 98.83 | 49.41 | 245.36 / 319.21 | 63.89–66.88 |
| 原版复测 | 8.893640 | 56.31 | 98.41 | 47.69 | 245.17 / 319.04 | 67.50–70.00 |

候选相对两个原版平均为-0.88784%，没有证明提升，未达到30FPS。三组都是确认INTERACTIVE区间的SurfaceFlinger实际呈现时间，完整历史通过；温度顺序变化与缓存变化使这些短测不能证明长期表现或因果差异。PSS包含约374.85MiB可回收录像memory-map，不是实时USB摄像头内存估计。CPU/morph/NPU阶段均值交叠，不能相加或从FPS减去某个CPU均值推算独占GPU耗时。

## NPU路由与计时边界

此产品/replay路径的YuvConverter是RGA；detector/mesh为RKNN；478平滑、几何和FP32 normalization为CPU；52 suffix为RKNN混合CPU/NPU。没有剩余MediaPipe GPU face或RenderScript转色工作可以再搬到NPU。GPU busy为整机观测，不能证明全部属于PBR，也不意味着提升NPU busy即可加速OpenGL。现有GPU数值优化收益未知时必须实测。

多视图绑定期间 TIME_ELAPSED 与 TIMESTAMP 结果未定义，本轮均不用；此前V35只在非多视图FBO0包围最终交织draw采样，不能代表view渲染时间。见[Khronos OVR_multiview规范](https://registry.khronos.org/OpenGL/extensions/OVR/OVR_multiview.txt)。

## 复现入口与验证

MirrorActivity 的 `test_pbr_fast_math` 仅显式debug Boolean生效，普通入口默认false，不写偏好；非debug、null或错误类型拒绝。最长scene factory追加bool，旧overloads保持false。AvatarPreviewActivity `verify_pbr_fast_math=true` 独占其它验证模式，报告带新run UUID/context/timestamp，失败与取消不会使用旧成功报告。生成图位于app-private `avatar-pbr-fast-math-preview`，生成结果 `avatar-pbr-fast-math-check.json`。

采集器 `scripts/run_runtime_check.py --pbr-fast-math` 只在显式选择时传入启动/恢复两处。对照省略该参数。其余同上16/400640、replay、batched、persistent、cached、NPU52参数固定；不清除用户设置。

验证范围：SDK35真实build；163159数值/源守卫、528原shader、71实际生产Program/Comparison所有权与异常清理host检查；InputOptions22、Preview16、gate容差/fixture38、原ORM22+16、GPU选项24、生命周期34、旧验证分派224、Python采集器49。host不模拟Mali；实际GPU门与FPS见上。独立只读review未发现确认的阻断缺陷，10项构建源码、APK、36图SHA匹配。

## 普通使用与后续

设备正常进入首页/角色页，普通Geralt GL frame ready、PBR reference、ORM关闭、GPU query pool0。正常Back两步回首页，检查无GLThread或AvatarPose线程；原设置和安装SHA一致。Android其它线程可继续存在，此检查不表示GPU驻留内存为零。

相机仍CAMERA_ERROR(3) endConfigure流配置失败，普通入口状态ERROR/OPENING_CAMERA，报告未包含source/NPU运行统计，无实时采集验收。错误UI仍可导航返回，用户不在设备旁时不要求插拔。录像联合结果不替代现场面捕。

UI沿用V36椭圆中间安全区、三个可选角色与27项素材库；更多头部模型仍逐项修正。玛奇玛本轮离线收敛模型e2a122…c1217技术采样通过但闭眼竖纹、眼环灰暗及微笑角部仍不自然，美术/设备/正式通过均false，未加入catalog。本轮所有模型资产字节未变；其它已冻结收敛头和三维背景仍待艺术、设备及性能验收。

下一轮先比较按比例缩小的view tile（如240×384或200×320）与原400×640；这需要独立候选、实际截图和16视点联合测。已有240×720测试提高了高度，不能仅凭总像素少32.5%推定角色着色面积同样减少。此处是待验证假设，不是FPS预测。若画质不合适，再验证保留高精度UV/导数/GGX小分母的选择性低精度着色或其它渲染结构。不得宣称这些足以达到30FPS。完整资产逐角色校正、合理正式操作流程、三维背景和16+视点30FPS仍未完成。
