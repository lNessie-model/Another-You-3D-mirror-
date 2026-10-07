# V51 / 0.2.20：GPU 切线预计算与主材质成本诊断

V51 已同签名覆盖安装，普通首页、角色、场景、设置及原素材保持。新增两个默认关闭、相互排斥的 debug 选项：GPU 三角切线预计算，以及只跳过主材质光照的成本诊断。后者改变画面，不能计入完整30FPS目标。

## 实际联合性能

每组使用同一 V51 APK、原杰洛特模型、16 个独立400×640视点、1200×1920最终交织、录制人脸输入与RGA/RKNN478/混合52表情同时运行。每腿60秒，至少40秒确认INTERACTIVE实际呈现记录。不是实时USB摄像头恢复，也不是渲染循环回调FPS。

GPU 候选保持原PBR：

| 运行 | 实际呈现 FPS | 全机 CPU | GPU busy | NPU busy | App PSS 均值 MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| 原版A | 10.523 | 57.20% | 97.73% | 46.64% | 301.85 |
| 候选 / 诊断 | 11.158 | 64.94% | 98.50% | 45.75% | 288.46 |
| 原版B | 10.510 | 57.47% | 98.28% | 46.60% | 286.64 |

两次原版平均 10.517 FPS，GPU候选相对均值变化 +6.10%。这是单次ABA顺序观察，存在升温、调度和统计波动，不能推断长期稳态因果收益。候选未进入产品默认。

原shader主材质unlit诊断：

| 运行 | 实际呈现 FPS | 全机 CPU | GPU busy | NPU busy | App PSS 均值 MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| 原版A | 10.543 | 57.67% | 97.95% | 46.23% | 300.94 |
| 候选 / 诊断 | 17.032 | 78.37% | 97.84% | 46.91% | 261.04 |
| 原版B | 10.483 | 57.95% | 98.23% | 45.82% | 303.90 |

诊断相对同组原版平均变化 +62.01%，只表示删除主材质光照后的观察成本；几何、其它六个绘制项与最终交织保留。不能把这个FPS当完整材质的目标结果。

CPU与GPU/NPU busy来自确认活跃区间。PSS含录像mmap驻留，并非独占分配。face/rig/GL提交计时属于各自会话或窗口的CPU wall，互相重叠，不能相加或称为GPU/NPU独占执行时间。原始按范围数据保存在本地root-performance-summary.json。

## GPU画质与资源

实际Mali完成短测9姿态/288层/48最终比较，以及完整69姿态/2208层/48最终比较；在串行和OVR4各自直接比较原shader与候选，同一实际已上传bounded-worker PN/UV/IBO与原材质。16个VP均不同，12背景完整，透明无绘制32层验证。完整max RGB 1，层RMSE最大 0.006038074，最终RMSE最大 0.003294039，alpha不符 0；原固定RGB<=1/RMSE<=0.1/alpha=0门未放宽。PNG解码哈希和差异独立重算通过。

每次实际上传姿态后、view framebuffer之前，在FBO0运行一次compute：借用24字节stride PN、8字节UV、uint IBO，9213三角写512×36 RGBA32F表。所有视点共享该表。没有CPU PN快照或CPU整表上传；表逻辑payload294912字节，不代表driver实际驻留。其它六个绘制项继续原路径。

上传revision加全部拟合world原始float位组成缓存键。成功dispatch/屏障/GL状态恢复才提交；失败锁存且拒绝旧表绘制。六次未消费producer发布不改变当前已上传/准备revision及dispatch。实时status字段不是联合原子快照，因此只按同session/context分别核单调，暂停画质测试才要求revision精确相等。

[Khronos glMemoryBarrier原始参考](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Refpages/main/es3.1/glMemoryBarrier.xml)：覆写前IMAGE_ACCESS屏障排序此前读取，dispatch后TEXTURE_FETCH屏障使写入对采样可见。无OVR timer query/glFinish；cpu_submit_mean_ms仅API提交CPU wall，不是GPU计时。

## 应用与模型状态

原78个assets、9个native库的路径与解码zip字节精确同V50，原签名及minSDK24/target30保持。四份设置逐字节保持。实际Home/Role/Scene/Settings/About、普通原Geralt首帧、自然Back回首页、Java runtime owner退出检查通过。两个新实验在普通入口关闭。

本轮未替换正式模型。萨菲罗斯四个局部闭眼候选在私有输出保留；末版19523顶点/18250三角/8draw/41.48MB逻辑解码预算通过，实际80姿态Skin–Ring动态外接缝精确匹配，仍有3个新增朝向反例、1个法线反例和组合闭眼最多13条眼球漏射线，所以拒绝接入。没有使用Tripo积分，也不能将离线预览称为设备面捕验收。

普通相机状态 ERROR，流配置错误尚未恢复。此轮仅应用安装，没有刷机、重启、root、清数据。16+独立视点30FPS、全历史角色校正、正式新素材接入和更多背景仍未完成；两小时测试按用户要求跳过。

## 保存与继续

APK SHA `52e9e6eea0ac7ddb58a06f574048f0e97909ce1d5ce732d38261b4db4dc6a931`，文件`AvatarRuntime-v51-gpu-triangle-v1.apk`。V50同签名APK保留用于应用层覆盖降级回退。源码构建18路径锁定，最终发布20路径；测试59/169/23、collector86和旧回归通过，父当前默认GL快照均`98e8b5149d63b458411528ae2c924c68091c4e919b955000fced2ea9011cb010`。这些主机GL桩不是真实shader执行；上面的Mali门与实际联合测量为独立证据。

私有实测目录`E:/tripo/output/mirror-program/20261007/v51-gpu-triangle-qa-v1`；模型失败及恢复点在`v51-sephiroth-closure-v1`。Git采用选定源码范围正常提交/推送，保留V49/V50原失败与成功证据、全部历史资产/LICENSE及未选WIP。同步状态以最终remote SHA收据为准。
