# V42：空白区域交织采样候选

V42 / 0.2.11 保留现有正式入口与全部素材。新增默认关闭的调试候选；真实Mali上的三个角色、共285项最终RGBA逐字节零差门通过。同APK A-B-A完整联合负载约8.92/8.96/8.93FPS，未证明可复现提升，仍保持默认关闭；16+视点30FPS未完成。

候选仍渲染16个不同相机矩阵的完整视图。只在全部当前显示几何的屏幕包围矩形之外省去视图数组的RGB三次采样；背景在分支前无条件按原方法计算。基准路径的SceneBackground生成源码保持原样。

每个primitive仅在实际VBO上传的revision改变时扫描6float顶点流。边界使用已接受姿态的world矩阵、同一fit及全部实际相机VP；不引用尚未上传的新面捕姿态。全部活动节点包括头外附件；任一无效输入或near/W不确定时整屏完整采样。GL上下文建立、尺寸变化、每帧开始和异常时撤销旧边界。额外预留2个视图texel与2个输出像素，并向外舍入；这是保守数值设计，实际Mali像素门才是本轮验收证据，不作极端import/subnormal/所有GPU通用证明。

验证范围：三个已打包角色；每角色69个动作/组合、12背景、8场景变换、6交织参数组合。每fixture只prepare/渲染16层一次，之后在同一array上切换基准/候选最终shader，对1200×1920全部RGBA字节要求0差；读取GL_CURRENT_PROGRAM与边界uniform，至少一项有效非全屏边界必需，避免全部回退的假通过。中性与all-controls另检查全部16层边界之外RGBA清零。

1025项CPU边界检查及76项实际编辑器/输入选项边界检查已通过。离开/取消诊断不会发布成功；无效debug值与release覆盖拒绝。候选未加入普通设置，也不写持久配置。

OpenGL ES的线性过滤与裁剪依据[官方3.0.6规范](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf)。最终颜色门和性能门分开；几何空白比例不等于实际GPU带宽节省，边界projection均值不包含上传位置扫描，扫描计入avatar prepare/upload wall。

完整历史模型逐项校正、真实USB面捕恢复、动态三维背景接入及16+视点30FPS仍未完成。当前回放性能检查不包含USB采集或视频解码，也不是长时间稳定性测试。

## 真机像素与联合负载终态

实际安装最终候选：`AvatarRuntime-v42-empty-interlace-v2.apk`，SHA256 `a882c26ac9ad4c7e9c3be6d240c031fdf181a824ff5911184f8fc1686152d1f1`；V42 / 0.2.11，签名与V41一致。78个assets及9个native库条目字节与V41全部相同，没有新增或重新校正角色的声明。

Geralt、builtin-guide、ada-wong各95项，所有最终RGB/alpha差为0。有效非全屏fixture计数93/92/94，其它项保守回退；中性与all-controls各读取全部16层，边界外没有非零RGBA texel。host包围验证不能替代这些实际GL输出；质量验收仍不等于角色艺术质量已达标。

采样前固定A-B-A、每次60秒采集和相同前35秒墙钟分析方案。场景保持1200×1920输出、16×400×640真实独立视图、完整19907顶点/19157三角形Geralt、原PBR/ORM、异步CPU变形、OVR4/持久FBO/相机矩阵缓存。NV21录像送入RGA/RKNN478和CPU规范化+混合52表情；无USB采集/视频解码，测量期间无截图/readback/GPU timer。

以下帧率来自SurfaceFlinger第二列实际呈现时间，只统计确认INTERACTIVE区间。三组完整采集门均true。faceFPS是最终状态的当前会话累计均值，不能当完全同时间窗独占阶段速率。CPU是整机四核归一化占用，PSS是应用内存的离散采样均值/峰值；启动分配及温度漂移均保留。

| 组 | 呈现FPS | 面捕会话FPS | CPU整机 | GPU busy | NPU busy | App PSS MiB均值/峰值 | 温度℃范围 |
|---|---:|---:|---:|---:|---:|---:|---:|
| reference-first | 8.920 | 15.110 | 56.27% | 98.34% | 49.64% | 297.2 / 404.5 | 63.89–68.12 |
| empty-region-candidate | 8.957 | 15.024 | 59.28% | 98.23% | 48.74% | 279.9 / 403.7 | 62.22–67.50 |
| reference-repeat | 8.932 | 15.163 | 55.96% | 98.16% | 49.66% | 297.8 / 410.5 | 67.50–71.11 |

预先固定的35秒窗口均未通过完整门：其中有GRACE边界和短末段INTERACTIVE没有足够呈现时间戳。辅助窗口FPS约8.888/8.930/8.897，仅保留原值和false结论，不能替代完整门、不能重定义窗口挑选有利结果。首个分析器因assert此false停止，原始reference采集未重跑；继续脚本保留该false后完成原计划另外两组，所有初次失败/日志/原始poll journal均保留。

候选最后一个INTERACTIVE快照几何空白比例16.49%，边界projection累计均值约0.992ms（不包含位置扫描），不是全采样平均、GPU纹理流量或GPU耗时测量。确定性的2.34倍中性fixture中Geralt/guide边界为整屏，Ada约3.07%空白；这与录制人脸持续转头后的最后快照是不同姿态，不能混用。

候选未显示足够帧率提升，CPU从约56%增加到约59%。GPU仍约98%，故不默认启用；NPU也未由此承担图形栅格/PBR工作。内存波动不能解释成候选省下约18MiB，此候选只增加小的常量边界缓存，无已证GPU存储减少。普通入口/设置、原角色SHA、关闭候选、四份配置和自然Back后GL/pose/runtime线程释放另有真机记录。

代码复现：调试会话增加`--ez test_empty_interlace true`，或脚本`run_runtime_check.py --empty-interlace`；不写保存设置。像素诊断`EmptyInterlaceCheckActivity --es role_id geralt`（其余两role同理），只允许debugAPK，报告`files/empty-interlace-check.json`。本轮证据保存在本机`E:/tripo/output/mirror-program/20261006/v42-empty-interlace-v1`，不发布私人录像、设备路径原始日志或诊断图像到公仓。

下一阶段应先减少16视图角色本身的GPU着色成本，或验证更细的保守覆盖域是否抵得过额外CPU/分支成本；当前矩形裁剪不足以解决30FPS目标。完整模型校正/背景接入目标继续保留。
