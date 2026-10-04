# 面部精修工程：实际设备控制预览

新版工程：`E:/tripo/output/mirror-assets/20261004/blender-controls-v1/geralt-face-editable.blend`。
旧版纯形态键工程仍在阶段6素材目录，可继续按原操作方法使用。

## 调整表情

在右上角物体列表选 `FaceCaptureControls`，打开右侧橙色物体属性图标，展开“自定义属性”。
52项名称与 MediaPipe 一致；这里输入的是设备经过校准和平滑之后的系数，不包含摄像头识别、个人校准或时间滤波。

- `eyeBlinkLeft / Right`：闭眼，0睁开、1完全闭合。
- `eyeWideLeft / Right`：睁大眼睛；`eyeSquintLeft / Right`：眯眼。
- `jawOpen`：张嘴；`mouthClose`：闭唇输入。
- `mouthSmileLeft / Right`：嘴角微笑；`browInnerUp`：眉头上抬。
- `head_pitch / yaw / roll`：点头、转头、歪头，单位为度。
- `mirror_motion`：镜像；`expression_gain`：设备嘴眉表情曲线强度，范围0.5–4。

默认镜像开、强度2.5，与当前设备设置一致。眼部、cheekSquint和mouthClose不放大。
镜像交换角色左右表情和眼球输入，并反转头部偏航/侧倾；角色自身左侧是名称中的Left。
眼球是 LeftEye/RightEye 物体转动；旧eyeLook形态键保持零。

## 修改某一个表情的形状

1. 先另存副本。把全部面捕系数设为0，`mirror_motion`关闭，`expression_gain`设为1，头部三个角度设为0。
2. 将要修改的控制设为1，例如 `eyeBlinkLeft=1`。
3. 选择 `Face`，绿色三角形“物体数据”→“形态键”，选同名 `eyeBlinkLeft`。
4. 按Tab进入编辑模式，移动眼睑顶点。可以用G移动、O衰减编辑；只修改这个形态键。
5. Tab返回物体模式，回到控制物体，逐步测试0、0.25、0.5、0.75、1。
6. 单项满意后再恢复设备镜像和强度，测试眨眼+眯眼、眨眼+睁大眼睛、张嘴+闭唇。

新版形态键“值”由驱动控制，显示为受驱动颜色；从控制物体修改数值即可。
形态键顶点仍可手工修改。不要改名字、Basis、顶点数量、UV和材质；不要直接删除驱动或应用全部形态键。

## 组合修正的区别

`corrective...` 是相对于中性脸的附加位移，不是完整的闭眼/闭嘴姿态。
设备与新版Blender都会在对应两个输入同时出现时，按乘积自动叠加：

- correctiveJawOpenMouthClose = 经强度曲线处理后的jawOpen × mouthClose。
- correctiveBlinkSquintLeft/Right = 同侧eyeBlink × eyeSquint。
- correctiveBlinkWideLeft/Right = 同侧eyeBlink × eyeWide。

修改组合修正前，先完成单项。组合键里只保存消除组合问题需要的额外位移，否则基础表情会重复叠加。

## 检查范围和限制

253组实际生产 Java `FacePlayback` + `AvatarRig` 对照包含全部52输入、镜像、0.5/1/2.5/4强度、混合头部角度和组合端点。
实测56形态键权重、6个模型节点世界矩阵共38,456项检查通过；最大权重误差8.81e-8，矩阵误差1.20e-7。
驱动全部属于Blender简单表达式，关闭Python自动执行仍可运行。
另实际进入编辑模式移动一个左眼闭眼顶点1毫米，验证Basis与其他表情逐项不变，评估后的网格跟随修改；检查未保存输入文件。
头部网格、拓扑、自定义法线、UV、顶点色、形态键数据、实际引用材质图和内嵌贴图保持一致。
Blender会清除无用户引用的默认空材质，这与头部材质无关。

这是数值控制一致性，不是“所有表情已经自然”的证明。离线灯光和材质管线与设备不同，预览不证明光学交织或30FPS。
已查看7组实际驱动的斜前视角预览：眼部组合能闭合，但张嘴口腔缺少层次、牙齿偏平，唇部和眼睑细纹尚不足。
组合表情穿插、牙齿及真人眼部响应仍待精修验收。

保存Blender不会自动更新设备。当前设备继续使用v29/PBR阶段6模型。普通GLB导出还需适配现有受限贴图格式、manifest SHA和节点映射；本次未新增通用Blender回写打包器。

## 重现检查

生产对照导出器：`tests/BlenderFaceControlReference.java`，用真实生产类编译，JSON-java版本20240303。
构建：Blender加载原可编辑工程，执行 `scripts/build_blender_face_controls.py -- --reference 对照.json --manifest avatar.json --out 新工程.blend`。
验证：Blender加载新工程，使用 `--disable-autoexec --python-exit-code 1 --python tests/check_blender_face_controls.py -- --reference 对照.json --baseline 原工程.blend --report 新报告.json`。
路径的输出必须不存在，防止覆盖之前的工程与证据。
也可运行 `tests/run_blender_face_controls.ps1 -Workfile 新工程.blend -AssetDirectory 阶段6素材目录 -Baseline 原工程.blend -OutDirectory 新证据目录`，自动编译生产类、记录源码哈希、导出对照并运行实际编辑模式检查。
Baseline用于确认工程搭建不改形状；你主动精修过几何后，应省略Baseline，仅检查控制与手动编辑能力，另行检查修改后的形变质量。

参考：[Blender形态键编辑](https://docs.blender.org/manual/en/4.5/animation/shape_keys/workflow.html)、[简单驱动表达式](https://docs.blender.org/manual/en/4.5/animation/drivers/drivers_panel.html)。
