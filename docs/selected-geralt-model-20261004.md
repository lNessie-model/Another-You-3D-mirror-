# 用户选定的白发白须头模

用户于 2026-10-04 指定截图中的白发白须头部，作为后续模型外观基准。复用已有 `geralt-lowpoly-head-v2-25bb173b` 的头部资产；面部精修沿用同一模型来源，不重新生成角色。

当前可编辑工程：`E:/tripo/output/mirror-assets/20261004/selected-geralt-controls-v2/geralt-face-editable.blend`。
对应运行时候选：`E:/tripo/assets/mirror-models-20261003/lowpoly-heads-v2/geralt-rig-stage7-dentition-v2/character.glb`，SHA256 `4893f3f4e17e540e35feec27f5541d4512f6c37f3e04495df5c151e6ad907fe5`。

该候选保留阶段6皮肤、头发、胡须的三个原始PBR贴图，并保留皮肤和眼唇网格已有基础表情数据。相对于阶段6，新增了局限于唇缘的张嘴闭唇修正、独立上牙弓、跟随下颌节点的下牙弓及口腔组织。原头模基础来源相同；新眼球与唇圈、灯光和材质响应仍需对照用户指定外观进行验收，不能把同源资产称为截图逐像素匹配。

## 调表情幅度

在Blender右上角选 `FaceCaptureControls` → 橙色物体属性 → 自定义属性。调 `eyeBlinkLeft/Right`、`jawOpen`、`mouthSmileLeft/Right` 等输入，范围0–1。
默认镜像开、嘴眉强度2.5；眼部、cheekSquint和mouthClose保持不放大。

## 改某个表情的形状

1. 另存副本，全部面捕输入和三个头部角度归零；关闭 `mirror_motion`，将 `expression_gain` 设为1。
2. 把要改的输入设为1，例如 `eyeBlinkLeft=1`。
3. 选择 `Face` → 绿色三角形物体数据 → 形态键 → `eyeBlinkLeft`。
4. Tab进入编辑模式，用G移动眼睑顶点，可开启O衰减编辑。Tab返回物体模式。
5. 回到 `FaceCaptureControls`，分别测试0、0.25、0.5、0.75、1，再测试眨眼和眯眼/睁大眼睛的组合。

形态键的“值”由控制器驱动，但顶点可以手工改。保留 `Basis`、形态键名称、顶点数量、UV、材质和驱动。`corrective...` 只存组合需要的额外位移，不能存整个完整姿态。
眼球使用独立物体转动，上下牙弓是独立刚性网格；这份工程不含全身骨架。

## 交付与设备的区别

工程包含内嵌贴图、52项MediaPipe输入和实际运行时公式。保存 `.blend` 不会自动更新设备；修改形状后仍需回写GLB、更新manifest并进行设备检查。
离线预览灯光与设备不同；它不证明所有表情自然、光学校准完成或16视点达到30FPS。
本次没有新Tripo任务，新增积分消耗0。
