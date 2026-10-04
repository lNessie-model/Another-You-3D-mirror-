# 成人牙型与口腔细节候选

本轮只替换 Face 的口腔 primitive 和 UpperDentition / LowerDentition 的网格。头部、皮肤、眼球、眉眼/嘴唇的形态键、PBR 图片、材质和节点层级都与输入版本逐项相同。下牙仍随 JawAttachments，上牙仍随 Head。脚本可以在 root 完成的后续同结构头部上重跑。

并行子任务最先完成的是v3（输入stage9-expression-v6），其原始证据保留。root随后在最终expression-v7上使用同一生成器重跑，当前通过检查并进入组合模型的是v4：

`E:\tripo\assets\mirror-models-20261003\lowpoly-heads-v2\geralt-rig-stage10-oral-detail-v4`

模型SHA256：`2d809873a09cfe829009639d24708ded2c80304c07222ff6dd43d1517f320b48`。

输入是stage9-expression-v7，SHA256 `b8d5dab7d60299c9181f037f15f07adba961129fb0c5d28b0276a592672e8410`。口腔阶段后又加2个皮肤连接点和2个鼻侧眶缝三角形，得到已部署stage11模型（SHA256 `11397607700b2223d63b5cd05bb2f929fbc18d3dbc80b71ab47e0375a909041d`）；该接片没有改动口腔或牙齿。设备由root统一操作，原候选和未保存Blender GUI保留。

## 变化

- 每牙弓 10 颗，包括中央切牙、侧切牙、犬牙、前磨牙、磨牙；切缘和犬牙尖端区别更明显，牙色略偏暖。
- 每弓由 600 顶点 / 1164 三角形降到 500 顶点 / 956 三角形；仍为闭合体，不增加绘制 primitive。
- 口腔后壁约在唇部参考深度后 29 mm；口腔壁前缘直接使用输入模型的 96 个内唇顶点及其全部形态键。
- 舌头有厚度、圆钝前端和浅中沟。舌根埋入口底与后腔壁，根部随下颌运动，前端只跟随部分下颌运动。它与口腔是交叠的显示体积，没有宣称整套口腔组织是单一医学网格。

## 实测检查

`tests/check_head_oral_detail.py` 使用实际 Java AvatarRig 导出的 19 组姿态：

- v4模型通过生产GLB loader：19745个实例顶点、18835三角形、7次绘制、26412332字节GLB，均低于现有上限。stage11加入皮肤接片后为19747顶点、18837三角形、7次绘制。
- 所有非口腔皮肤/眼睛/唇部属性、索引、形态键及内嵌 PBR 图片逐项一致；节点与材质字典一致，manifest 的控制绑定逐项一致。
- 96 个口腔壁唇部连接点在 19 组姿态中误差为 0。
- 上下牙弓均闭合、无退化面，采样刚性距离最大误差约 9.53e-11 m。
- 自然闭嘴与张颌闭唇，在正面及左右斜视方向的 14640 个牙顶点/三角形中心采样点均被皮肤/唇部遮挡。
- 19 组姿态都有 18–28 次独立舌根边段与口底/后壁的相交，舌根没有悬空。

这不是设备帧率或光栅屏效果测试，也不是全部观察角度的碰撞证明。离线预览使用实际生产骨骼数据，但美术自然度仍可调整。

## 预览

当前可显示的预览来自已整合的 stage11，路径是：

`E:\tripo\assets\mirror-models-20261003\lowpoly-heads-v2\geralt-rig-stage11-orbital-closure-v1\feedback-preview\jaw-open-front.png`

同目录还有 `jaw-open-oblique.png`、`jaw-open-mouth-close-front.png` 和 `smile-front.png`。预览报告匹配 stage11 模型 SHA256；实际 FacePlayback + AvatarRig 共 19 个表情、32 张正面/斜视预览。这些是 Blender 离线渲染，不是设备屏幕截图。stage11 的中立姿态材质工作文件是 `geralt-orbital-editable.blend`。

最终口腔交付审计核对了 V4 和 stage11 的口腔 primitive、上下牙弓的所有属性、索引及形态键，逐项相同。正面、斜视张嘴及张颌闭唇预览未发现明显穿唇或舌根错误；舌根的实际接触证据仍是 19 组姿态的体积相交检查。

## 重跑与验证

先运行 `scripts/refine_head_oral_detail.py`，传入 `--source` 最终的 stage9 目录、`--landmark-report` stage5-lip-ring-v3/lip-ring-report.json 和全新的 `--out` 目录。脚本拒绝覆盖已存在的输出目录。

接着使用生产 `AvatarPoseExport` 导出 19 组 canonical poses，再运行 `tests/check_head_oral_detail.py --asset 新目录 --source 输入目录 --poses 新目录/production-poses.json --report 新目录/oral-detail-check.json`。最新组合模型的 worker/Blender 驱动检查、整合和设备发布由 root 统一负责。

## Tripo 尝试与积分

按已有预算授权，计划 1 次 P1（P1-20260311）text-to-model，2000 面、PBR，预估 40 积分，无后续付费步骤。doctor 确认存在环境凭据，但 API reachability 失败。make 自然结束为 exit code 7，最后结果为 `network error: fetch failed`，未返回 task_id、模型、预览或 credits_consumed。

本地程序建模额外使用 0 Tripo 积分。该次网络失败请求的实际计费无法从返回结果核对，应记为未确认，不能把本候选说成 Tripo 生成，也不能把预估 40 当作实际扣费。未修改任何系统网络配置、未自动重试付费生成。
