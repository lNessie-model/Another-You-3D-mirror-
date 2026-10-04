# 16 / 20 视点持久运行配置

新空偏好默认为 **16视点 / 17 FPS / 400×640**。维护页新增“运行视点数”16/20选择；尺寸、分析帧率、视点数在“保存并运行”后通过一个 validated profile commit。修改选择、“恢复运行默认”及返回均只改草稿；默认草稿保留光学、相机与角色选择。保存成功且运行档位改变才请求 Activity 重建，当前渲染器不会逐字段切换。明确保存未改变的档位仍 commit，允许从旧schema显式迁移，成功后无需重建。

## 迁移和边界

- 偏好 schema4 新增 Android `int view_count`，仅支持Integer16/20。所有合法schema1–3旧保存配置保留20视点及原合法尺寸/分析帧率；无版本但非空的旧map也保留20。读取和迁移本身不写盘，旧map中的未知count字段不参与旧版本语义。
- schema4缺count、null、错误类型或非法值进入16视点只读回退并警告；损坏、负数或未来schema版本也只读，不能用保存、默认草稿或光学/相机保存覆盖原配置。原非法fps/preset回退规则继续；光学和相机坏字段仍分别警告，不扩大这次迁移。
- `withProfile(fps,preset)`、光学校准和相机保存保留现有count；新的完整profile API才显式修改count。角色状态在原独立文件，本切片不读取或改写角色选择文件。
- 回装旧schema3 APK遇到schema4会使用其受保护的兼容回退，不能保证保留新count或400×640的实际运行行为。安装同包APK通常保留偏好，不等于配置跨版本兼容；完整导出恢复工具属于后续独立切片。

`SharedPreferences.commit(false)` 可能已更新Android进程内映射。此时维护页明确显示“未保存”，不请求重建；当前settings/options/renderer仍保持原运行档位。后续校准和面板预览携带原会话实际count，避免单独重读失败后的映射而半切换。相机编辑也只更新当前相机值，不激活另一个运行profile。这里证明的是**当前会话没有半切换**，没有实现内存/磁盘原子回滚，也不保证此时任意重建、进程死亡或其它保存后的耐久行为。必须处理原错误后明确重新保存；本切片不扩展为通用恢复框架。

## 主运行与校准传播

主程序以保存的count配置真实 `InterlaceRenderer`，release同样可运行16。主状态 `view_count` 和renderer的 `view_count` 是实际运行count；新增 `configured_view_count` 是本会话settings快照的count，debug覆盖时可与实际不同。400×640实际分配仍须由新APK的renderer状态和独立像素证据确认。

原 debug `test_view_count` 必须是Integer16/20，release拒绝，覆盖不保存；主程序忽略 `runtime_view_count`，不能用它覆盖release保存配置。校准/面板预览的窄 `runtime_view_count` 仅传递主会话已生效的Integer16/20，release不借用debug flag；与 `test_view_count` 同时出现、null、错误类型或非法值均拒绝。主运行→校准→PanelPreview使用同一count，光学参数、物理视比与渲染数学保持。

主运行CLI省略 `--view-count` 时不发覆盖extra，由应用读取保存count；显式参数仍仅调试，不写偏好。CLI帮助文字同步说明此规则，启动与HOME恢复的参数生成没有改变。

诊断旧 `RuntimeViewCount.read(extras,debug)` 缺省20不变；AvatarPreview三种400×640/16入口、旧默认400×720/20与全部报告文件名/严格像素门不变。直接PanelPreview `verify=true` 且没有显式count时仍默认20；普通面板预览无显式count时读取保存配置。PanelPreview的原两参数Intent工厂也保留20默认；维护校准使用携带实际count的工厂。

导出CLI只新增schema4/count与 `configured_view_count` 数值白名单，不增加原始面部数据。schema4偏好必须显式包含合法int count；输出仍是有限、非原子current-state摘要，没有恢复写入能力，也不能把settings摘要当作当前renderer的实际count。

## 实际主机验证

新Settings/count API缺失、主runtime/configured传播缺失和exporter不接受schema4均先实际RED，再GREEN。已运行：

```powershell
./tests/run_view_preset_settings_tests.ps1
./tests/run_product_view_count_tests.ps1
./tests/run_runtime_view_count_tests.ps1
./tests/run_avatar_verification_dispatch_tests.ps1
./tests/run_runtime_gl_lifecycle_tests.ps1
python -m unittest discover -s tests -p test_export_runtime_bundle.py
python -m unittest discover -s tests -p test_runtime_metrics.py
```

实际结果：Settings114 + 原17 + Camera56，product runtime75，view/matrix12934，原preview dispatch224，GL lifecycle34，camera VP19，persistent FBO15均GREEN；exporter原15项全GREEN，新增合法count20分支再定向复验GREEN。

SDK35源码链接及主机测试覆盖旧/新schema、非法Integer类型/值、草稿/取消、显式旧配置保存迁移、光学/相机字段保留、真实主renderer配置方法和校准/PanelPreview Intent工厂、debug不保存、release配置16、失败commit已改变内存映射但旧会话保持、严格16矩阵及旧224诊断分发回归。SharedPreferences/Activity.recreate/Intent/Matrix为主机替身；没有模拟按钮点击、Android生命周期、GPU像素、release APK或设备通过。v17的400×640图像证据不能直接套给本次新UI/schema版本，APK构建与独立实机验收由root另行执行。
