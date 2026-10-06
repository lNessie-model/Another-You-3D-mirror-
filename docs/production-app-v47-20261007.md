# 正式 App V47：组内参数提交复用与设备复核

V47（0.2.16 / versionCode47）同签名覆盖安装。默认关闭的 Geralt 调试候选减少四视图组内重复 program、VP、sampler 提交，保持原模型、PBR、贴图、16个独立视点及绘制顺序。实际 Mali 直接普通→候选69×16层像素检查通过。全新同包ABA呈现 12.1453/12.1451/12.1274 FPS，候选相对两端常量色基线均值 +0.0718%。三次约12.1FPS且候选几乎等于首次基线；微小均值差不能证明可重复提升，候选继续默认关闭。30FPS仍未达到，正常产品入口保持普通路径。

## 实现和主机验证

每个draw调用重新清空当前program与variant位图，状态不跨组/帧/上下文。相邻相同program省glUseProgram，同材质第一次上传VP/count和active samplers，此后仍逐primitive上传world、normal，按原索引绘制。完整fragment key、原shader运算、贴图绑定和颜色证明保持。

Geralt每四视图组program/VP/sampler由7/7/7变为5/5/6；world、normal、draw各7保持。每16视点少20次API调用，未证明GPU工作、显存或物理带宽减少。API返回计数不等于GPU完成。

`--reuse-group-uniforms` 默认false，依赖 `--constant-white-primary --specialized-batch --avatar-batched`；旧重载默认false，debug typed boolean、null/type、依赖、互斥、初始化后修改及逆序关闭都有保护。确切原Geralt SHA门、13975主顶点RGBA raw-bit全白检查保持，其他有色primitive仍使用颜色属性。实际选项状态在 `debug_render_overrides.reuse_group_uniforms`。

GL边界606、实际Gradle配置89与独立SDK35配置89、Root6/peer7个CLI案例通过。旧252颜色、57693材质/布局、200生命周期、528shader检查通过；独立编译V46确认默认shader、调用轨迹和每draw矩阵/sampler快照一致。13构建输入前后冻结一致，Gradle成功。之后宿主Windows PowerShell辅助验证缺少Get-FileHash报错，原日志保留；换可用运行时验证实际Gradle类89、最终签名和实际manifest版本。没有生产改动或重建，验证后才安装。

## 实际像素

会话 `499f0f16-c225-40c6-9c1c-f8413084fbb3`，直接 `individual_ovr4` → `per_entry_specialized_constant_white_group_reuse_ovr4`。两侧独立VBO/IBO接收同CPU形变、原完整PBR/贴图，每姿态16个不同且非空400×640层，原1200/1920投影比例。

两侧各1932次draw、276完整组与绑定检查，旧packed参考0。1104层最大RGB差1、RMSE 0.001976423538、351个RGB字节差、alpha零差；两路各1104散列分别匹配V46同姿态。主颜色attribute−1/−1，其他2/2，终态关闭与资源释放通过。同步终态program/VP各1380、sampler1656、world/normal/draw各1932、skip/shared各552，精确对应276组。有限姿态不证明所有表情、美术、光学、现场相机或帧率。

## 全新同包ABA短测与保留的失败

v1第一、第二collector均完整，但wrapper在第二组status11把异步字段当原子组边界而失败，第三组未启动。groups2242时program/VP11213、world/normal/draw15697、sampler13457、skip/shared4484，含下一组前三entry；无渲染故障。独立复核确认runtimeStatus虽有自己的锁，GL draw不持有该锁，累积字段不是原子组快照。原脚本、事前规则、两个raw/journal和失败证据保留，不能称第一次ABA通过。

实际旧快照复现失败，非负整数与同context逐字段单调检查通过。v2不改生产13输入/APK/shader/FPS或像素阈值/采集统计公式；同步像素终态保留精确计数门，运行异步字段检查非负单调并保留原数，不用跨字段倍数等式。单调性只是该数据检查，不是新增线程同步保证。v2事前冻结后三组全新60秒，不拼接v1。

三组同最终V47 APK，只差reuse flag，都显式开启batched/specialized/constant-white。基线是常量色分材质候选；本轮没有普通入口性能端点，V46普通收益不能称为本轮新收益。全部确认INTERACTIVE区间且至少40秒，未知尾保留并排除，不用固定35秒窗口替代门；测量期间无截图/JDWP/GPU timers。

原Geralt19907顶点/19157三角形、原PBR/贴图、16×400×640独立视点/1200×1920交织、异步CPU形变、4持久FBO、逐帧VP、目标31。853帧NV21录像face-reference-stable-20261001-01，名义24.369907FPS，无USB/解码。RGA、RKNN检测/478点、CPU FP32归一化和混合CPU/NPU52后段，表达模型17b0773…。ORM/PBRfastmath/空白/背景缓存关闭，timer pool0。

| 组别 | SF实际FPS | 确认秒 | 未知尾秒 | 整机CPU% | GPU忙% | NPU忙% | PSS均值/峰值MiB | 面捕子范围FPS | received→complete ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| constant-white-first | 12.1453 | 44.7050 | 4.8377 | 61.20 | 98.02 | 46.25 | 300.95/407.87 | 15.8860 | 127.70 |
| group-reuse-candidate | 12.1451 | 44.7733 | 4.8098 | 61.06 | 98.12 | 47.63 | 285.89/405.90 | 15.6740 | 133.02 |
| constant-white-repeat | 12.1274 | 44.6041 | 4.9010 | 60.89 | 97.64 | 46.48 | 301.30/409.76 | 15.8837 | 127.07 |

面捕差分仅同session/确认区间相邻快照，范围分别 35.1883,30.2413,35.1933 秒，比SF/资源范围短。阶段平均墙钟不能相加当GPU/端到端时间；温度范围依次 [60.555, 65.0],[65.625, 69.375],[69.375, 72.222] ℃。顺序温态、有限短测和稀疏PSS不能证明长期因果或显存释放；没有事前freshness上限，全部30FPS false，无日常默认资格。

## 正常入口、远程预览和后续

最终新会话 `1325e10f-0199-40eb-8528-12eeb42d547d` 正常Geralt/PBR首帧与普通绘制308次通过，specialized/constant-white/reuse三flag false。Back回首页、角色页smoke、实际JDI Java runtime owners为0通过，原四保存配置字节/SHA一致，本轮前后原始配置副本保留。未重跑V44完整角色浏览suite。

78 assets/9 native entries对V46逐字节一致，没有新模型/降低材质；UI布局沿用，真实V47首页/角色截图已放会话供远程查看。USB仍 ERROR / `IllegalStateException: android.hardware.camera2.CameraAccessException: CAMERA_ERROR (3): endConfigure:510: Camera 0: Error configuring streams: Function not imp`，录像不证明实时采集恢复；ADB正常，未刷固件/root/重启/清数据。

APK AvatarRuntime-v47-group-submission-v1.apk，SHA256 `6a17d1a28d99389fb4555ded72af06b5109157c7590c6d0a8b667b480c52b0d2`，原证书 `64a4af6baa9f10fd9d5bd09003ded7def368211604e3e91181bf015cdfbf6da8`。私人QA/失败/v2 journals/截图/构建检查在output/mirror-program/20261007/v47-group-submission-qa-v1，独立复核在v47-group-submission-peer-v1；APK/私人录像不发布。

GPU仍是主要负载。NPU形变私有原型smooth-normal精度未达标，未接入；USB恢复、其他头部自然52/眼睑/真实材质口腔、三维背景仍待完成。正式作品与16+独立视点30实际呈现FPS目标保持。

同步仅本轮15源码/测试/说明，180未选WIP保持。所有既有previous历史深JSON保留，V46用Git commit/路径/SHA引用，不加previous_v46嵌套树。每个验证迭代同步，私人QA/原型不发布。
