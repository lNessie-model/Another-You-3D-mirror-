# 静态相机 VP 缓存候选

本切片为默认关闭的调试候选；主机测试及 SDK 35 javac 检查完成，**v15 设备 Matrix 7,200 个 float 位级比较及 69×20 层严格 RGBA 零差异门已通过**。同APK A/B/A2实际呈现29.911/30.078/29.794 FPS，均未通过所有完整互动区间30 FPS的门；矩阵段1.004→0.172ms但总callback work基本未变。HOME/恢复功能通过，具体scope、热条件和历史缺口见 [设备验证记录](../docs/camera-vp-device-validation.md)。不改变 shader、几何、20 独立视点、画质、帧节拍、clear/depth/invalidate 或 GL 提交次序。

## 变更与缓存合同

`MirrorActivity` 仅在 debug APK 接受 Boolean extra `test_cached_camera_vp=true`；省略/false 继续原逐帧 Matrix 运算，非 Boolean 或 release 显式覆盖拒绝。renderer 初始化后不可切换该模式。`AvatarCameraProjectionCache` 在 surface 初始化/尺寸变化时依原有 `float` 表达式和 Android `Matrix.setLookAtM` → `frustumM` → `multiplyMM` 顺序生成矩阵；没有改成数学上等价但舍入不同的简式。

key 包含 views、完整 physical width / height 及由其得到的 aspect raw bits。即使新尺寸比例相同，也不能拿旧尺寸 key 调用 `copyGroup`。每次 group 复制核对全部 key 和 1/4-view 范围，缓存不存在、失效或错误 base 明确失败。`onSurfaceCreated` 和 `onSurfaceChanged` 都先 invalidate，后者重建；该缓存没有 GL ID，不跨 context 复用 FBO 或纹理。头姿/眼球/下颌仍由 Scene 的世界矩阵处理，未被固定到相机缓存中。

上限 32 views；20 views 的有效矩阵为 320 floats / 1,280 bytes。固定最大矩阵存储 512 floats / 2,048 bytes，加 3×16-float scratch 为 2,240 bytes 的数组 payload（另有 Java 对象开销）。`copyGroup` 只做 key/bounds 检查和 arraycopy，既不重新计算 Matrix，也没有每帧数组分配或无限增长历史。渲染器两种配置均持有同一个固定小缓存对象；默认路径不填充/使用它。

状态区分：`debug_render_overrides.cached_camera_vp` 记录请求；`renderer.camera_vp_requested=per_frame|cached`；`camera_vp_actual=uninitialized|per_frame|cached|failed`。`cached` 只说明相应 key 的矩阵已准备，必须另看 `runtime_gl_frame_ready`、error 和真实呈现。原有 `camera_matrices` 子段边界不变，候选测的是 group copy 墙钟，不是 Matrix 或 GPU 执行时间；JSON 描述同步明确两种路径。

## 主机验证与实际 Android 实现边界

```powershell
.\tests\run_camera_vp_tests.ps1
.\tests\run_camera_vp_sdk_tests.ps1
python -m unittest discover -s tests -p test_runtime_metrics.py
```

测试先 RED（新增缓存类/CLI 缺失），实现后：

- 缓存主机测试 107,839 checks：1/4/16/20/24/32 views，portrait / landscape / 改 aspect / 同比例不同尺寸 / 1×1；所有 view 的 raw float bits 与独立原运算路径一致；错误 key/group 拒绝、invalidate 重建、输出借用不污染缓存；100,000 次相同 key prepare/copy 不增加 rebuild 次数，容量恒定。
- 实际 SDK 35 编译新类及所有变更 Android 类；19 个配置/报告/严格像素门检查通过，覆盖默认关闭、release/String 拒绝、初始化前 actual、初始化后禁止切换。旧宽松门可通过的单字节差异被本候选拒绝。
- 采样器 35 项测试通过，包括 `--cached-camera-vp` opt-in、真实 `run()` 初启动和 HOME 恢复命令、默认两次均不传标志、JSON 请求值和不写偏好。
- 原 renderer 92 checks（含 100 万有界 runtime frames）、pacing 22、全部原 `test_java.ps1` 检查、persistent FBO 配置 15、预览头角逐位 3,000、多视图 fixture 4,048 / 69 姿态回归通过。

**主机 Matrix fixture 是受限算术替身，不是 Android native Matrix 的逐位实现承诺。** 它验证缓存 key、调用/复制策略、原表达式与缓存运算顺序的一致性。真实设备不同 ABI/系统版本的 `multiplyMM` 内部运算必须由下面独立设备门核对；不能用 SDK stub 编译或主机替身通过宣称设备浮点一致。

源码对照确认：原诊断 `drawViews` 和 `drawInterlace` 内容未改，avatar 路径的 GL 调用文本/顺序相同；glFinish / glFlush / glClientWaitSync 数量不变。这里只移除逐帧重复 Matrix CPU 计算，没有新增同步。

独立只读 review 未发现 P1/P2：核对完整 key / 部分构建不发布 / context 与 resize 失效、两侧独立 array/FBO、真实旧计算 reference、同 prepare/VBO、7200-value 位级门及严格像素门；未运行设备，不能替代下述实机检查。

## 可执行设备检查

设备持有者使用独立 debug 入口（与旧 multiview/batch/FBO verify 互斥）：

```text
adb -s <serial> shell am start -n com.mirror.bench/.AvatarPreviewActivity --ez verify_cached_camera_vp true
adb -s <serial> exec-out run-as com.mirror.bench cat files/avatar-camera-vp-check.json
```

先运行真实 Android `Matrix` 的 `matrix_gate`：25 cases（1/4/16/20/32 views × 5 physical 尺寸），比较所有视图，逐 case invalidate 后重建再核首组；合计 7,200 float values、25 invalidate 拒绝检查。要求 bit_mismatches=0、passed=true。每组包含 1200×1920、1920×1200、1200×1600、2400×3840、1×1，覆盖比例改变及同比例尺寸 key 变化。

随后用真实 GLB/rig 的 69 个固定姿态，每姿态只 prepare 一次，双方共享 Scene/VBO/world/shader，两侧都采用相同 persistent OVR4 FBO、clear/depth invalidate 和独立 array 存储：reference 在每个四视图 group 重新执行原 Matrix 运算；candidate 通过生产缓存 `copyGroup` 获取矩阵。**这次检查只比较矩阵计算/缓存路径，不重复比较 legacy FBO 与 persistent FBO。** 报告 `comparison_backend=per_group_camera_vp_vs_cached_camera_vp`，`persistent_groups=reference_persistent_groups=5`。

输出覆盖完整 20 layers、每层 400×720、physical 1200×1920/.625；以单层 read FBO 读 RGBA。预先固定 max RGB/RMSE/alpha mismatches=0，非空/全不透明及跨视点、跨姿态变化门保留。旧 `serial_*` 字段在这里指原逐组矩阵 reference，不代表 single-view draw。必须验证新 run_id 的 running=false、completed=true、passed=true、69 completed/prepare、1,380 layer comparisons、matrix_gate 通过，无 error/cleanup_error/cancelled。该入口不含摄像头/NPU，也不构成 FPS 或光学、美术验收。

## 单变量联合与 HOME 参数

collector 增加 `--cached-camera-vp`，初启动和 HOME 恢复共享 `--ez test_cached_camera_vp true`；原始 JSON 保存 `arguments.cached_camera_vp` 及实际 host commands。默认不传该 extra；不写系统设置或偏好。

保持同 APK、模型、相机/回放、NPU、20×320×576、target31 与 chosen FBO 后端，仅切换本缓存选项。例如在 persistent 基线上：

```text
python scripts/run_runtime_check.py --name camera-vp-b-90s --input camera_replay --seconds 90 --output-dir ../output/mirror-program/20261003 --view-preset 320x576 --persistent-fbos --cached-camera-vp
```

A 对照移除 `--cached-camera-vp` 并换报告名，其余不变；HOME 功能另加 `--pause-at 30 --pause-seconds 5`。设备先通过严格像素门，再做反顺序性能复测，核对每轮实际 FBO/cache 后端、完整已确认 SF 历史、温度/CPU0/GPU 频率范围、面捕与 pose age。现有 camera_matrices 约 0.94–1.06 ms 是小而明确的 CPU 成本上限，不保证可全部转成实际 FPS；缓存不减少 GPU 像素/几何负载，不能掩盖热条件变化或降低画质。
