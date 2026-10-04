# 配置备份 format1：纯 codec 首片

2026-10-03。已实现 `RuntimeConfigBackupCodec` 的不可变数据、encode/decode与有限输入校验。**没有配置快照读取、文件写入、UI/manifest接线或恢复**；现有Settings/Store/Activity和v18设备包均未改变。[备份与恢复提案](runtime-config-backup-plan.md)中的普通save dirty隔离、durable journal及启动恢复门仍未实现，这段codec不能证明输入已落盘，也不能绕过这些门开放“备份已保存配置”。

同包内部API：`new Backup(id, createdUnixMs, appVersionCode, signerSha256, sourceSchema, settings, avatar)`，`AvatarReference.builtin(modelSha256, manifestSha256)`或`imported(packageId, modelSha256, manifestSha256, bundleSha256)`；`encode(Backup)`返回新UTF-8 byte数组，`decode(byte[], trustedSignerSha256)`复制有界输入并返回同类不可变数据。无Context、SharedPreferences读取、角色库访问、I/O或网络。非法输入抛 `IllegalArgumentException`，消息仅为固定类别，不回显文件内容/身份/角色名称。

## 固定文件合同

| 对象 | 必须且仅允许的键 |
| --- | --- |
| envelope | `kind`, `format_version`, `package`, `backup_id`, `created_unix_ms`, `app_version_code`, `signer_sha256`, `source_settings_schema`, `payload_sha256`, `payload` |
| payload | `runtime`, `panel`, `camera`, `avatar` |
| runtime | `active_fps`, `view_count`, `view_preset` |
| panel | `pitch`, `tan`, `phase`, `units`, `order`, `reverse`, `origin` |
| camera | `id`, `fingerprint`, `width`, `height`, `rotation_degrees`, `reflect_input`, `mirror_interaction`, `revision` |
| avatar builtin | `source`, `model_sha256`, `manifest_sha256` |
| avatar imported | `source`, `package_id`, `model_sha256`, `manifest_sha256`, `bundle_sha256` |

`kind=mirror-runtime-config-backup`、`format_version=1`、`package=com.mirror.bench`固定。ID为小写UUID形状，SHA为64个小写hex；生成时间/应用版本是正int64，来源schema仅0–4。正文始终是完整的当前设置合同；codec不读取或迁移旧偏好，来源schema由调用者的已确认快照提供，合法旧设置的展开责任仍在未来快照层。

运行FPS仅10/17/20，视点仅Integer16/20，尺寸仅240x720/320x576/400x720/400x640。encode拒绝read-only或带warning的 `MirrorSettings`。decode用明确完整字段构造现有 `PanelCalibration`、`CameraControlSettings`，再用 `MirrorSettings.withProfile/withPanel/withCamera`校验，不将异常默认回退作为有效备份。光学phase必须已规范到[0,1)，不偷偷wrap；camera只接受640×480、0/90/180/270、两项绑定均空或成对存在，绑定fingerprint额外要求SHA-256形状。revision是非负int64，**codec只记录，不增加所有权revision或复活任何worker**。

角色只有精确引用：builtin两项hash，imported UUID+三项hash。没有模型、缩略图、名字、任意路径、previous/candidate历史、人脸图/坐标/系数/中性样本、运行状态或日志。codec校验形状与payload一致性，不验证该角色当前存在、Ticket仍有效、GL资格或相机仍接在原设备；恢复预检必须另做这些实际证据。

## 语法、数值和哈希边界

输入最多65,536 bytes，包含允许的JSON空白；65,537拒绝。严格UTF-8拒绝BOM、非法字节/过长编码/未配对surrogate。先扫描严格JSON，按解码后的键拒绝重复（包括Unicode转义别名），拒绝null、注释、单引号、裸键、分号、尾逗号/尾部内容及NaN/Infinity，再交 `JSONObject`。扫描最多256个value节点（含对象/数组本身），根深度0、最多深度8；固定合同最终不接受数组。超限在DOM构造前拒绝。

整数字段必须整数词法，`16.0`、`16e0`、`"16"`、Boolean均拒绝，先精确 `Long.parseLong` 再界定int范围。float字段从扫描保存的原数字词法直接转float32，拒绝非有限及非零值下溢成零；不依赖Android和主机org.json选择的Number类型。已有合法float32位值（含tan负零和最小subnormal）/revision超过2^53及Long.MAX_VALUE可以精确往返。

`payload_sha256` 覆盖**重建后的规范payload UTF-8**：对象键按上表顺序；数值为整型十进制或 `Float.toString` 的float32往返表示；字符串双引号，quote/backslash转义，U+0000–001F写为小写 `\u00xx`，其余合法Unicode保留。不同JSON键顺序/空白、等值float表示可解码为同一payload hash；它不是原始文件字节hash，envelope元数据也不在该payload hash内。未来本地文件层须另计算完整byte SHA并明确损坏检测范围。

`trustedSignerSha256`必须由调用者独立取得；decode要求文件声明与它匹配。**这仅是证据值匹配，不是对文件的数字签名或来源认证**，可重算的payload hash也不是认证。当前codec不读APK、不验签、不信任任意导入路径；未来仍限定提案中的应用私有备份目录。不同应用版本不自动构成兼容或可恢复证明。

## 已跑验证

```powershell
./tests/run_runtime_config_backup_codec_tests.ps1
```

runner用实际SDK35 `android.jar`先编译四个生产类；测试运行使用既有真实JSON-java 20240303（SHA `3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed`），不把SDK的stub JSONObject作为主机运行实现。实际RED：未实现encode抛错；固定payload编码完成后，继续在未实现decode处RED；最终 **1566 checks GREEN**。包括独立固定payload SHA/完整规范字节、错误package/signer/version/哈希、类型/整数词法、缺字段与未知脸字段、重复转义键、非法UTF-8/Unicode/宽松语法、恰好64KiB与超限、DOM前depth/value门；24个profile组合各跑7种tan位值、非默认光学、四旋转、绑定Unicode相机、三种revision以及imported精确引用。

这个结果是SDK编译+主机真实JSON测试，未执行Android JSON runtime、设备UI、release构建、文件断电/进程死亡、commit(false)隔离或实际恢复；没有构建新APK或操作设备。那些验收属于后续事务/文件/UI切片。
