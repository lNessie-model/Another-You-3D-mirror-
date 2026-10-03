# 隔离 Android 配置存储检查

2026-10-03，RK3566 / Android 11。独立测试包 `com.mirror.configcheck`，没有相机、NPU 或直接GL渲染入口，测试只操作自己的私有偏好与角色包。主程序配置恢复UI尚未接线。本记录不作为主程序恢复通过或完整项目完成声明。

## 首次设备结果

冻结清单 `app/build/config-device-check-v1/freeze-v1.json` SHA `cf99af7161e2838966a5b3182a1d8d99efd8a4fb2258c7b1f4d482106f1372ec`；实际安装APK SHA `506228cd72685cb5e4d2032fc74f9f79b840d854b4d00c12716cd3103623c73f`。构建、33项输入与16项主机 admission 检查已独立审查，不能替代设备检查。

设备初检 [audit.json](E:/tripo/output/mirror-program/20261003/config-device-check-v1/audit.json) 为 `completed=true, passed=false`：创建三槽角色及初始偏好成功，`transaction` 在60秒内没有返回匹配结果。host按既定期限强停该测试包，未继续后续故障点。保留原命令、seed报告和测试应用私有数据，不将超时写成存储通过或确定的死锁。

seed设备耗时12.295255735秒。随后同case显式新进程恢复，另一个新request返回完整报告：[isolated-old-case-recover.json](E:/tripo/output/mirror-program/20261003/v20-ordinary-restored-v1/isolated-old-case-recover.json)，设备耗时37.198160071秒，OLD胜出、原型偏好及完整角色选择通过读回。这项只覆盖这一次旧状态恢复，不代表全部八个中断点通过。恢复启动命令及SIGQUIT诊断来自root工具会话；后续保存目录记录了读取该报告和停止测试包的命令，没有伪造更早命令的独立磁盘日志。

静态读取冻结Store发现，正常完整事务检查累计可达121次GLB完整解析，首次PREPARED发布前约40次。每包模型5,501,220字节，每次validate均重复模型哈希、GLB解析、rig/deformer及几何边界检查；同old/new包在verifyUnion中也重复解析。这个重复工作与实际慢恢复相容，尚未取得超时期间完整栈来断言唯一原因。30秒plan有效期也可能在重复校验后过期；没有延长该有效期或放宽精度/存储门。

下一候选减少同内容重复解析，同时保留实际磁盘文件、完整哈希、三槽ticket、published report及journal检查，并明确限定缓存内存。它仍须独立审查和新的设备结果，不能把主机加速推断为板端通过。

## 第二版存储短检

固定 [设备报告](E:/tripo/output/mirror-program/20261003/config-device-check-v2/audit.json) 的 `completed=true, passed=true`；独立 runner 退出0。外围窗口工具会话77858退出1，因为后续普通USB恢复没有通过；这两个终态分别保留，不能合并成完整窗口成功。隔离存储检查 host耗时369.890秒，设备首份报告开始至末份结束368.261956294秒。

v2冻结清单 SHA `66e28948490c68b12589715112c0e41a6c2964c70caf1e75048e40716942b7d6`，实际安装测试APK SHA `f8d4e5e4e4de2b7c923b140458db08578b7ba0e98872a38d14817dba73ee9459`。v1/v2的Java源文件集合相同，唯一Java修改是已审Store缓存候选；Activity、四个协调器源、fixture及八个中断点字节不变。项目还有来源记录、文档和安装命令 `-r` 的更新，不能将“仅Store变化”扩大到所有文件。Store候选冻结 SHA `187ad09ec8afeedf94379989e6b7da09531eb26066238a2fca72e2b34ee1b18c`，主机370项检查为82项缓存、139项旧Store及149项配置Store；其中九个主机halt/recovery与下表八个Android中断点分开。另16项fakeADB admission只检查host入口。源码/冻结链已获独立peer核对。

缓存最多保留三个已解码资产、共32MiB计费数组；每次仍检查实际磁盘完整哈希、ticket和报告。此次结果支持相同三包短检在原期限内完成，未证明所有角色包、所有存储压力或内存占用都满足相同时间。

| 正常模式 | 设备报告耗时（秒） | 实际 Editor 返回与结果 |
| --- | ---: | --- |
| transaction | 10.948090934 | true、true；先恢复20／320×576／10，再通过普通保存回16／400×640／17 |
| false_response | 9.587091880 | 实际true、true；首次成功响应被注入为false，事务回到OLD |
| disk_failure | 9.735029311 | 真实撤写权限后false、false；恢复原权限再true，PREPARED回到OLD |
| filesystem | 6.486012526 | 私有目录硬链接、目标已存在拒绝、重复owner拒绝及重新打开通过 |

`disk_failure`单独报告15项检查：两个真实失败后缓存都合法、可写且与所请求值匹配，warning为空，仍必须维持QUARANTINED，阻止readConfirmed和备份；恢复原完整权限后显式恢复、journal清理与新进程验证通过。它与“实际持久成功但响应false”的注入是不同证据，不混称真实磁盘失败。硬链接结果只证明本测试包当时的私有目录能力。

| 实际进程中断点 | fresh-process胜出值 | 恢复报告耗时（秒） |
| --- | --- | ---: |
| PREPARED_temp_synced | OLD | 7.091663671 |
| PREPARED_published | OLD | 8.049887059 |
| prefs_committed | OLD | 8.084732771 |
| config_state_published | OLD | 7.916702128 |
| COMMITTED_temp_synced | OLD | 7.929629672 |
| COMMITTED_published | TARGET | 7.677020063 |
| journal_before_delete | TARGET | 7.595393638 |
| journal_deleted | TARGET | 5.246061861 |

独立 [审计 JSON](E:/tripo/device-lab/app/build/config-storage-device-audit-final-v2.json) 对44份独占request报告、12个新case、44个不同PID及885条命令作全序列核对：36份完成报告为true，八份有意中断marker的 `completed/passed=false` 是预期结果。每个marker后均记录 `pidof` 返回1且空输出，随后recover与verify_final各用不同PID；三槽完整ticket、原型偏好及未知键保持的全量比较由被冻结的Android代码执行。其二进制状态文件未在这44份报告中单独导出，host不冒称独立重建了这些原始状态。160项设备检查含八项中断前marker检查。全部raw报告与审计内嵌对象、成功cat的原始stdout逐字节一致；尚未存在的结果即使cat返回0，也未被当成报告。

设备单报告最大10.948090934秒。原host等待60秒、plan有效30秒均不改变；设备报告时间不含host轮询开销，也不是单独测得的plan年龄。transaction报告中的20视点是恢复后、普通保存前的快照；随后fresh verify_final为16／400×640／17且camera_revision=1。OLD和TARGET的全部19个JSON摘要值均核对；摘要数字不代替Android原型比较。这里的17是分析目标FPS，不是已测呈现帧率。

复算仅读取固定终态，不调用ADB、不覆盖输出：

```python
import json, runpy
from pathlib import Path
audit = runpy.run_path('E:/tripo/device-lab/app/build/audit_config_storage_device_v2.py', run_name='audit_recheck')
assert audit['audit']() == json.loads(Path('E:/tripo/device-lab/app/build/config-storage-device-audit-final-v2.json').read_text('utf-8'))
```

本APK没有camera/GL硬件owner，其 `storageQualification` 不能作为主程序停止资源、目标摄像头或GPU角色接受的资格门。生产Application owner、所有配置读写入口、备份恢复UI与硬件资格仍未集成；不保证任意断电恢复，不以此次短检声称完整项目完成。

## 主程序恢复

[普通USB恢复证据](E:/tripo/output/mirror-program/20261003/v20-ordinary-restored-v1/audit.json) 为true；停止独立测试包后，主程序v20无extras重新运行USB摄像头，默认NPU表达后段、persistent FBO及16×400×640，观察到16个已完成帧且错误为空。保存偏好SHA仍为 `43bd19b1d31c03648a81fb7e18ecd778c47f4d34b3879e3041f2e0f815056b37`，APK仍为 `21ee8a3d…01b3da`。现场无脸，这不是完整面捕或呈现性能结果。

用户已跳过两小时耐久。该短检独立于长时测试，没有重新启动两小时任务、刷机或更改系统分区/原厂驱动。

第二版的 [窗口-v1终态](E:/tripo/output/mirror-program/20261003/config-device-check-v2-window-v1/audit.json) 明确为 `storage_passed=true, passed=false, ordinary_restored=false`：30秒内没有核到普通USB恢复所需的成功采集状态。窗口开始前，旧Activity就已报Camera 0 `endConfigure` 的 `CAMERA_ERROR (3)`；重开后新的Activity `5f43f5a1-7b9c-4bad-aaa1-b85b504fffa0`／session `fa4fd4cf-4237-4a3f-bd93-af229af84832` 先WAITING，随后出现相同流配置错误。renderer工作不等于USB采集恢复；没有从该日志推断唯一硬件根因或将错误归因于存储候选。窗口前后主程序偏好SHA仍43bd19b1…056b37，APK仍21ee8a3d…01b3da。此前成功普通恢复记录保留，但不能覆盖这次窗口失败；后续重新插拔与清理须有独立新证据。

## 独立测试包清理

卸载前，第一版失败case的seed／expected／final三个私有journal快照、角色选择与偏好共五个小文件已保存到 [cleanup-v1](E:/tripo/output/mirror-program/20261003/config-device-check-cleanup-v1/audit.json)，审计按对应cat输出逐字节核对。它们是隔离测试数据，不是主程序的完整备份。`uninstall com.mirror.configcheck` 返回0、`Success`；但cleanup-v1的audit仍为false，因为随后将表示包不存在的 `pm path` 返回1、空输出当作错误。另一次verified-v1因df挂载路径断言失败，原失败记录同样保留。

后续 [只读 cleanup-verified-v2](E:/tripo/output/mirror-program/20261003/config-device-check-cleanup-verified-v2/audit.json) 为true：`pm path` 返回1且stdout/stderr为空，另一条 `pm list packages` 返回0且为空，确认仅测试包已移除；主程序偏好仍为43bd19b1…056b37。相同userdata卷的可用空间增加190.62890625MiB，是整卷前后差值，不能完全归因于卸载。这项清理通过不包含摄像头恢复，也不改变窗口-v1的false或现有v20交付包；普通USB流配置问题仍待独立处理。
