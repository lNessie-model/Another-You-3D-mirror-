# 眉毛与眼睑作者流程及范围

本记录覆盖已通过技术门槛的眉眼资产，用户尚未完成艺术验收。没有将本记录当作模型效果完美、已部署或牙齿效果通过的证明。牙齿后续由口腔任务处理，眉眼源码不修改口腔或渲染 shader。

## 冻结资产

最终眉眼目录：`E:\tripo\assets\mirror-models-20261003\lowpoly-heads-v2\geralt-rig-stage12-visible-brow-v3-safe`。

`character.glb` SHA-256：`3e30c0bea18c3264f39650b4c7bf6fe29c585b6226d6653fe458dbb749c9431d`。同目录 `brow-review.blend` 从这个 GLB 重新导入，不复用旧模型。

该资产为 19,747 实例顶点、18,837 三角、7 draws；GLB 29,071,272 字节，生产 `AvatarGlbLoader` 收取解码预算 73,538,414 字节。解码预算不包含输入 GLB 和 JSON DOM，不等于进程总内存。

## 最终变化

相对阶段 11，只有四个 POSITION 字段变化：Face primitive 0 的 `browInnerUp`；Face primitive 1 的 `browInnerUp` 与左右两个 `correctiveBlinkBrowInnerUp`。静态顶点、拓扑、其它表情字段、PBR 图像及材质/节点/绑定保持。没有新增骨骼、键、primitive 或 draw。

宽额头 Up 在切口前 stage5 carrier 上计算后连续 barycentric 插值，避免在 T 接点独立计算非线性场。场的下缘 Y .015→.033 m，平台至 .065 m，上缘衰减至 .115 m；横向中心 .0015 m，半径 .04→.06 m；前表面 Z .025→.05 m 渐变，峰值 .0045 m。真实灰眉主要位于原 Eye Ring，而非旧误选的棕色 Skin 点。

原始宽场在极端闭眼、睁眼与眉毛组合中产生局部反向。最终修正固定宽 Skin 字段、outer row 0、inner row 7，以及 7 个真实灰眉父三角的全部 21 顶点；两 Eye row 5/6 的 256 顶点恢复阶段 11 Up。剩余变量限定为低 Y<.028 m 的 row 3/4 且排除真实灰眉父节点，共 192 个顶点。33 固定姿态的 source-relative XY 面积及三维叉积与 neutral 法线点积形成 cutting planes，3 轮新增反向数 26→2→0，累计 28 个约束。额外局部 QP 改动最大 .368843 mm；这个数不包含 row 5/6 还原步骤，不能称整个资产只改动 .368843 mm。

Blink×Up 使用源式同圈上下对应点差值乘渐变权重，不把整条灰眉取消回源形。Up 与两个产品场的 921 个桥端分别按实际 Skin/Ring 附着重绑，另外两枚阶段 11 克隆端复制相应 Skin 字段。

## 技术证据与局限

独立报告：`E:\tripo\output\mirror-assets\20261005\face-geometry-review\visible-brow-v3-safe-independent-regression.json`。同一标准 33 姿态通过：19 实际 Java 反馈、6 实际 Java 极限闭眼组合、6 带全部产品的 halfBlink/wide 组合、2 挑眉端点。新增 XY 反向 0、新增相对 neutral 的三维法线反向 0；923 端最大误差 7.444727875e-9 m；141,570 次闭眼射线采样全部 0 漏眼，halfBlink 可见眼球样本数单调。独立保留检查还确认 189 个非改动字段、PBR、节点和绑定，以及宽 Skin、真实灰眉 21 父节点、outer/inner 边界和 source row 5/6 精确保持。

这些固定姿态/三视方向检查不是所有角度自碰撞证明，也不是现场光学效果或 FPS 证明。闭眼眉面整体不要求回到源几何，仅内眼睑及严格遮挡/接缝门槛保持。

原 `v3-wide/material-diagnostic` 中 neutral/Up 的 clay-smooth、clay-flat、albedo-only 对照证实：原中性底模已有眉骨下压轮廓，烘焙 Base Color 已有鼻根竖纹和棕色 V 纹。宽场消除以前的局部前额尖凸，真正灰眉内端能抬起；源轮廓和 PBR 纹理仍带皱眉观感。不能宣称完全自然或把所有残留纹理当作新 morph 错误。

最终预览位于 safe 目录 `canonical-brow-preview/brow-up-front.png`、`feedback-brow-preview/brow-up-front.png`、`brow-up-oblique.png`、`blink-brow-up-front.png`。原始误选九个棕 Skin 点的 stage12 natural/local 版本及其“灰眉”位移说法均已废弃，不能作为真实灰眉完成证据。

## 可重现流程

在 `E:\tripo\device-lab` 运行作者脚本，使用支持 NumPy/Pillow 的 Python。每次必须指定新的输出目录；旧资产和证据不覆盖。源 fingerprint、精确 7 条 probe 标签/三角/barycentric/坐标、Eye anatomy 和加载预算均为作者断言。

1. `scripts/refine_visible_brow.py` 的 `--carrier` 模式从冻结 stage11 和 stage5 carrier 生成宽场。`--probes` 使用 `E:\tripo\output\mirror-assets\20261005\face-geometry-review\true-gray-ring-barycentric-v2-verified.json`。`--reference` 模式仅用于早期缩放诊断。
2. 由实际 `AvatarPoseExport --feedback` 和 `AvatarBlinkBrowReviewExport` 导出该宽场 SHA 的 19/6 姿态。
3. `scripts/refine_brow_pose_safety.py --source <stage11> --candidate <v3-wide> --probes <verified-probes> --poses <feedback-json> --poses <extra-json> --out <fresh-dir>` 进行源 row 还原、局部 33 姿态约束及产品/桥重绑。
4. 对新 SHA 重新实际 Java 导出，运行独立 `tests/check_brow_tessellation.py`，重新生成 Blender 预览。不得使用旧 SHA 姿态、旧 Blender mesh 或作者 float64 自检替代最终读回门槛。

源阶段 11 SHA 为 `11397607700b2223d63b5cd05bb2f929fbc18d3dbc80b71ab47e0375a909041d`；carrier SHA 为 `f7f66125d316b7da7c145024d70a3663263198f8f39365a54c4b29dab67fa5cb`；安全修正输入宽场 SHA 为 `375d853ae57e603976f1344c512f9f3ef86b60fefe99c601fe83409828309015`。安全作者冻结这组源，适合重现，不接受别资产坐标。
