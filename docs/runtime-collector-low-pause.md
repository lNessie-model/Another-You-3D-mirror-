# 长期观测：关闭期间全量checkpoint与增量journal

2026-10-03。新host collector支持 `--checkpoint-seconds 0`：运行期间不调用全历史分析/完整JSON保存，仅在结束、异常或KeyboardInterrupt的finally做原完整分析/保存；final snapshot与独立final force-stop顺序保留。未在设备运行新collector。`--checkpoint-seconds`默认仍60（可选0–3600整数）；正值保持原周期全量保存，不创建journal。旧冻结 `collector-v17/`、`collector-v18/`与既有raw未修改。

设备负责人后续可使用新name，避免覆盖任何旧证据：

```powershell
python -B scripts/run_runtime_check.py --name v18-persistent16-400640-lowpause-90s --input camera_replay --seconds 90 --persistent-fbos --view-count 16 --view-preset 400x640 --checkpoint-seconds 0 --output-dir E:/tripo/output/mirror-program/20261003/400x640
```

这只是入口示例，本次作者没有执行ADB或设备动作。CLI终态0仍仅表示采集结束，须检查collection_errors/presentation严格完整性与30FPS门。没有重建旧数据、填补SF空段或把partial当性能通过。

## Journal及progress

0模式在任何ADB前独占创建 `<name>.polls.jsonl`；同名journal或完整JSON已存在即拒绝，不改旧文件、不触设备。每record最多1MiB、运行总量1GiB、最多22,000 records；CLI最长7200s保留。上限超出、短write或flush失败令采集失败，仍走final snapshot/独立force-stop/完整JSON保存；已写prefix保留。上限是守卫，不能保证任何异常大raw的两小时都采完。

header保存固定设备/参数/采集策略，serial/package path/APK checksum的原读取结果另记；identity包含实际APK与原ART package state。action和surface discovery即时记录已有新增对象。poll只切取八个列表自上次record后的增量（samples/statuses/windows/actions/status_reads/probes/errors/markers），以及该轮shell command、完整组合raw和host poll起止；不复制历史payload、不调用analyze或遍历历史。final snapshot和final stop也记录。每record一次UTF-8紧凑JSON+LF write/flush，无新线程、无fsync；这是主机进程hard-stop前已刷到OS的有限raw，不保证断电/存储损坏持久性。

`end`在final-stop之后、最终完整JSON保存**之前**，明确 `phase=after_final_stop_before_full_report_save`。它证明已记录的采集/动作收尾，不能证明随后完整JSON写成功。hard-kill、write中断或journal失败可以没有end或留下最后半行；只能使用完整可解析prefix，标为不完整，不能宣称完整长测。正常/异常finally关闭journal后仍保存完整JSON；完整JSON写失败会向调用者抛错，已闭合journal保留，不能据footer假报成功。

每60秒打印小于几KB的PROGRESS：最近接受的status sequence、view_count、GLframes、source收到数/received及capture FPS、最近system温度、layer/history存在flag，以及本轮已有 `/proc/uptime` 对该status `updated_elapsed_ns` 的年龄。没有额外ADB。此clock先于同shell里的meminfo/status，精度约10ms；可能出现负年龄，明确 `status_clock_precedes_update`，不是绝对freshness夹界或人脸结果年龄。温度与source值分别来自最近采样/最近接受status，不假称同时测量。

app源码每5s报告状态，采集每2s读status，因此陈旧提示阈值15s；SF缺失提示先留5s启动时间。warning集合或预期pause状态变化时打印并保存OBSERVATION marker：missing_surface_history、status_unavailable/status_clock_unavailable、stale_status及恢复为空；请求HOME区间明确expected_pause，其他情况为unexpected。它们是观测标记，不将缺失直接归因于崩溃/过热/collector，也不自动启动应用、阻止HOME或修补timestamp。progress不计算在线完整presentation FPS/通过结论；原分析11个函数的AST与冻结v18完全相同，严格门未变。

## 已跑主机证据

原v18 30min终态 `avatar-v18-persistent16-400640-30min.json` SHA `db8c76595db87d01e78e81c205ba4545a273fe4d2df4ead373d64c5a8a875426`，64,727,602 bytes；本轮读取时已completed且有1个collection_error。1726 samples、280 unique statuses、871 reads、1369 surface windows。观测poll gap最大25.172s、SF history gap20.693818288s；它不是联合负载长测通过证据。本片未归因断档或应用前台中断，失败验收另记录。

- 新 `app/build/collector-checkpoint-benchmark-v1.json`：同机Windows11/Python3.12.14，host epoch1791022114.499，一次内存读取已落盘终态，冻结collector `8a0f55cf…a2c195`。全分析0.2714s、GL recovery分析0.000013s、indent2序列化0.4966s、另一个新临时文件write0.5025s。临时文件删除，没有改source/live。此时测量未复现25.172s，不能把历史gap归因于checkpoint。
- 新 `app/build/collector-poll-journal-benchmark-v1.json`：host epoch1791023305.755，同一raw最大status poll且有匹配system/SF。代表record138,321 UTF-8 bytes，200次顺序serialize/write/flush，median0.6097ms、p950.6944ms、max0.7570ms。raw_poll仅为benchmark将已采section组装的合成文本，**不是找回原shell stdout或补原证据**；不包含ADB或原全历史分析。测试版本collector SHA记录在benchmark，随后仅调整15s提示阈值、总量守卫与启动来源记录，append实现不变。

实际RED：旧CLI没有checkpoint字段，0仍全量分析3次、无journal/marker且同名journal不能阻止设备动作。最小实现后以下全部GREEN：

```powershell
python -B -m unittest discover -s tests -p 'test_runtime*.py'
python -B -m unittest discover -s tests -p test_gl_context_recovery.py
```

52 tests（metrics41+新增journal11）与8 recovery tests通过；新增真实fakeADB编排核default60=3次分析、0=仅最终1次、journal所有增量复算presentation/system/interactive_system整对象相等、独占拒绝、每poll有界增量、header/poll/end append失败、ADB/final-read异常、KeyboardInterrupt、最后JSON写失败仍已stop/close、真实short-write/flush失败、record/总量/count上限、可读hard-stop prefix、轻量progress与未知缺SF/陈旧标记。主机结果不替代新90s/30min设备验收。
