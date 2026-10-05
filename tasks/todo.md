# 正式 App 任务与证据

- [x] 基线Git同步并远端c966355核对。
- [x] 用户完整需求和阶段规格写入docs/production-app-spec-20261005.md。
- [x] 当前开机ADB及新相机会话约24.5FPS采集计数前进。
- [ ] stage13 GPU/实时部署复验（root，out独立、自动回滚）；用户个人表情验收另计。
- [x] V31单正式入口/主题/首页（expression_runtime）；最终2093 APK设备真实截图与单launcher审计通过。
- [x] V31实时中央安全区菜单/状态/权限引导（expression_runtime）；48dp滑条/选择框、52dp按钮、菜单/设置返回及真实相机ERROR文案通过；实际权限拒绝流程未在设备改动。
- [x] V31复用背景/镜像/校准/设置事务（expression_runtime）；保存/取消、返回首页显示背景、覆盖升级持久化通过，原两份设置与备份逐字节一致。
- [x] V31版本真实递增及build/签名/回归/设备导航（root）；最终2093 APK/109源文件匹配，两真实GLB首帧、原配置逐字节、切换后单运行页、退出后无运行Activity通过。摄像头ERROR/完整实时面捕/30FPS另计未通过。
- [ ] V31代码审阅、冻结、推送并核对远端（repo_sync）。
- [x] 全部独立角色及背景原始asset清单与预算（mouth_detail）；144 GLB、115独立SHA、10种IP角色、四PNG/八程序/四旧3D背景已清点。逐模型校正版manifest仍随后完成。
- [x] BundledAvatarCatalog与稳定选择协议（repo_sync）；97纯逻辑检查、实际SDK编译、真实2角色设备切换通过，不碰三槽cleanup。
- [ ] 每模型自拓扑面部校正/PBR/资源控制/实际表情预览（mouth_detail）；验收逐项清单。
- [ ] V32全库入包/选择/懒加载及切换释放（root集成）；验收：每项准确SHA、GPU加载、配置持久。
- [ ] 16+视点30FPS联合优化与实测；保留不达标记录，不以预算替代性能证据。
- [ ] 每通过一版按同样门槛同步GitHub并记录提交。
