# 400×640 每视点档位与像素诊断

下文是 v17 / schema3 的独立切片记录；随后 [16视点持久运行配置](runtime-persistent-view-count.md) 将新空配置与维护默认草稿改为16，并使用schema4。旧400×640诊断入口、默认20 API和报告隔离合同继续保留。

新配置、无效运行档位回退和“恢复运行默认（仅修改草稿）”均为 17 FPS / 400×640。维护按钮只改两个 spinner 草稿，返回不写入，明确保存后才 commit/recreate；屏幕与相机配置保留。已有合法 240×720、320×576、400×720 档位原样读取，不自动改成新尺寸。当前默认视点数仍为20，16持久选择属于后续切片。

偏好仍使用 schema3、原字段与类型；读取、迁移和草稿不写盘，坏/未来版本仍禁止保存。旧 APK 的 schema3枚举没有400×640，回装时可能暂用其旧默认320×576，不承诺旧版本支持新档位；原偏好不会因读取被覆盖。恢复旧档位须在支持它的版本明确保存。本切片没有清理配置或刷机动作。

主运行 CLI 新增 `--view-preset 400x640`，初启和 HOME恢复复用同一 debug extras，不保存偏好。例如由唯一设备操作员执行：

```powershell
python scripts/run_runtime_check.py --name runtime-16-400640 --input camera_replay --seconds 90 --output-dir E:/tripo/output/mirror-program/20261003 --view-count 16 --view-preset 400x640 --pause-at 25 --pause-seconds 8
```

显式尺寸经原 `MirrorActivity.InputOptions` 和 `renderer.setViewSize` 传递。设备验收须检查顶层与 renderer 的实际 `view_count=16 / view_width=400 / view_height=640`；仅 CLI arguments 或 requested字段不能代替分配后的实际状态。导出工具同步接受这一个新合法档位，其余白名单/隐私/大小/新鲜度边界保持。

AvatarPreview 三个16视点入口复用 `--es test_view_preset 400x640`。preset必须为 debug String `400x640` 或 `400x720`，显式null、非String和其它值拒绝；400×640仅允许16视点。persistent/cached必须传 `--ei test_view_count 16`，原 `verify_multiview_16` 固定16。真实release页保留原行为：verify flag不启用，尺寸覆盖被普通预览忽略。普通预览、batch、旧multiview不读取此新尺寸；缺省诊断仍400×720，旧20/16报告文件名保留。

|verify flag|400×640报告|
|---|---|
|`verify_multiview_16`|`avatar-multiview16-400640-check.json`|
|`verify_persistent_fbos`|`avatar-persistent-fbo16-400640-check.json`|
|`verify_cached_camera_vp`|`avatar-camera-vp16-400640-check.json`|

入口命令模板为 `am start -n com.mirror.bench/.AvatarPreviewActivity --ez <verify flag> true --ei test_view_count 16 --es test_view_preset 400x640`。合法参数下running、成功、失败和取消均写新文件，保留旧400×720报告；metadata记录请求宽/高/视点、run UUID/context/time。参数解析不完整或非法时只写独立 `avatar-verification-config-error.json`（`configuration_valid=false`），不把默认20/720标成实际请求，也不覆盖任何有效像素报告。不能取旧passed结果，设备runner必须匹配本轮UUID/时间并核查配置错误文件。正式成功报告的实际 `view_width/view_height/views` 来自原底层比较器。

三个入口继续原69 fixtures×16=1104层；strict serial/OVR仍要求所有16独立矩阵/视点与严格RGBA0，persistent/cached沿用原严格配对比较。每层尺寸400×640，物理投影视比仍1200/1920，10张普通角色截图仍480×768。没有改渲染算法、pose、光学或像素误差门。新400×640的serial→OVR、legacy→persistent、persistent→cached须分别真实执行；两配对之间还要逐fixture/层匹配persistent中间侧SHA。旧400×720门或历史v16combo不能替代新尺寸证据。

已实际先RED：空偏好仍320×576、CLI拒绝新值、导出器拒绝新合法偏好、预览缺新尺寸分发；fresh review另复现坏preset与合法count16组合在onSurfaceCreated误写旧20/720报告，独立配置错误文件修复后GREEN。对应GREEN测试命令：

```powershell
./tests/run_view_preset_settings_tests.ps1
./tests/run_avatar_verification_dispatch_tests.ps1
./tests/run_runtime_view_count_tests.ps1
./tests/run_runtime_gl_lifecycle_tests.ps1
python -m unittest discover -s tests -p test_runtime_metrics.py
python -m unittest discover -s tests -p test_export_runtime_bundle.py
```

SDK35源码链接与主机偏好事务/实际分发/JSON文件检查已执行；Activity/AtomicFile/比较器和Matrix均有明确host替身，SDK runner新增MirrorSettings源码依赖，避免绑定旧APK类。主机不模拟Android窗口按钮、真正EGL或设备像素，不声称release/板端已通过；APK构建和设备动作由root执行。
