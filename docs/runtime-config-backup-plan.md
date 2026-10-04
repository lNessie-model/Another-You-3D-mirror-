# 应用内配置备份与恢复计划

2026-10-03提案；截至2026-10-04，纯codec、事务／Store CAS／Owner／私有目录候选已有独立检查，**完整产品接线和恢复UI尚未完成**。主程序输入关闭失败保护已接入源码，范围见 [输入停止记录](runtime-input-stop-receipts.md)；后续增加了[实际JNI释放失败隔离](rknn-native-cleanup.md)和[CPU pose真实线程／借出帧停止记录](avatar-pose-stop.md)。这些仍不构成全应用硬件停止资格，退役GL／CPU及全部预览Owner合同待接。下一阶段在维护页增加“配置备份与恢复”，备份运行档位、光学、相机安装配置和当前角色引用；一键恢复必须先完成下面的事务与启动恢复门。保持离线、有限大小，不复制偏好 XML、不替换整个应用数据目录。

## 当前依据与范围

`MirrorSettings` 将 schema4 设置写入 `mirror-runtime` SharedPreferences；`AvatarPackageStore` 将角色 current/previous/candidate 写入 `files/avatars/state.json`，使用自己的锁、校验 Ticket 和同步文件后原子 rename。两个提交没有共同原子性。`commit(false)` 可能已改变进程内映射，`finally` 再写旧值也可能失败，不能称为完整回滚。[Android commit 合同](https://developer.android.com/reference/android/content/SharedPreferences.Editor#commit())只确认单份偏好持久化结果；[AtomicFile 合同](https://developer.android.com/reference/android/util/AtomicFile)也要求调用者自己提供互斥。

“全配置”指本应用当前持久设置和当前角色选择。角色 GLB、缩略图、APK、录像、相机图片、478 点、52 系数、个人中性样本、动作历史和任意日志均不进入文件。角色 previous/candidate 历史不作为用户备份恢复目标；它们仅进入内部回滚记录。原相机/光学构造器、角色载入与 GPU 预览资格继续使用，不改渲染数学。

原 CLI [当前状态 ZIP](runtime-bundle-export.md)仍是多次读取的非原子诊断摘要，不能作为恢复输入。APK 回装改变程序版本，通常保留原应用数据；配置恢复改变设置，不能回装程序、找回删除的角色包、恢复 HOME/系统或保证旧 APK 理解 schema4。

## 最小界面与本地文件

新增不导出的 `RuntimeConfigBackupActivity`，仅维护页内部 Intent 打开，不接受文件路径、配置值或自动恢复 extra。界面提供“备份当前已保存配置”、有界本地备份列表、“查看差异并恢复”；返回只取消草稿。差异页展示 FPS/尺寸/视点、光学各项、相机方向/绑定、角色来源及包哈希；明确“恢复并重新运行”才进入提交。角色有变化时，先复用现有精确包 GL 预览与明确接受门，不凭文件中的 GPU 通过标志启用。

首版只写 `files/config-backups/<UUID>.json`，最多16份、每份64 KiB、总量1 MiB；到上限拒绝新增，由用户明确删除一份，不自动覆盖或轮换。UUID由应用生成，列表只读固定目录中的普通文件，拒绝链接、子目录及额外路径。新文件独占创建、同步后发布；失败仅清理本次临时文件。文件完整字节 SHA-256 显示在详情页，正文 payload 另有 SHA-256，均用于损坏检测，不是来源认证。

此处“导出”仅为应用私有本地配置文件。首版不接 SAF、分享、云文档提供者或网络；将来若要复制到 USB/主机，另加明确本地复制入口。私有备份随卸载、清数据或存储损坏丢失，不能当作异机/灾难恢复；本页须直接说明。

## 版本1文件合同

UTF-8 严格 JSON，固定 kind=`mirror-runtime-config-backup`、format_version=1、package=`com.mirror.bench`、生成 UUID/时间、来源应用 versionCode/签名证书 SHA-256、原偏好 schema、payload SHA-256。正文只有以下字段，未知字段、重复键、null、非有限数、宽松 JSON、嵌套超8层/超过256值均拒绝；最多64 KiB，后台单任务，文件读取/验证30秒上限，不回显任意文件内容。

| 正文 | 固定内容与恢复规则 |
| --- | --- |
| runtime | active_fps=10/17/20；view_count=Integer16/20；view_preset=现有四档，包括400x640；无 debug 覆盖 |
| panel | pitch/tan/phase、PIXELS或SUBPIXELS、RGB或BGR、reverse、TOP或BOTTOM，复用 `PanelCalibration` 合法值及规范 phase |
| camera | ID/characteristics fingerprint、640×480、0/90/180/270、reflect_input、mirror_interaction；相机绑定必须空/空或成对存在 |
| avatar | builtin：当前 APK 内置 manifest/model 哈希；imported：现有库 UUID 与 Ticket 的 model/manifest/bundle 三哈希；无模型字节、自由文本名称或任意路径 |

JSON Integer 必须整数词法且在目标类型范围，Boolean 不接受0/1；光学数按有限 float32 往返编码/读取，测试逐位保留。`camera_revision` 属于运行所有权标记，记录原值为精确十进制 int64，恢复时显式生成高于当前/备份值的新 revision，溢出拒绝；它不复活旧个人校准或旧 worker。

备份从独占配置读取区间抓取**已确认持久化的配置**与精确角色选择，记录采集起止及来源 schema；不取当前 debug/options、相机校准草稿或运行状态文件。合法旧 schema0–3按现有语义展开为完整 schema4正文（非空旧配置仍20）；空配置为16/17/400640。坏值的默认回退不能冒充完整备份：版本只读、设置警告、损坏角色 catalog、待处理事务或下面的dirty隔离状态时拒绝普通备份，保留原数据供原诊断 CLI 观察。单独 `getAll()` 的合法结果不构成已保存证据。

仅接受 format1/当前已知设置合同/本包同签名来源；不同应用版本可预检，但不能据此宣称任意跨版本兼容。未来格式/设置版本拒绝，未知现有偏好键保留原位，不导出、不清除。当前设置若未来版本或只读，恢复也拒绝覆盖。导入角色不存在、Ticket哈希变化或内置资源哈希不符时整份恢复失败，不偷偷换内置/跳过角色。绑定相机须用现有 Camera2 ID+fingerprint合同重验；不匹配/缺失则不允许整份应用，提示重新接回原相机或另作安装校准。fingerprint不是 USB 序列号或物理屏幕身份证明；光学数恢复后仍需安装目视确认。

## 一键恢复的最小事务

新增应用专用 `RuntimeConfigCoordinator`，不建通用备份框架。所有设置 save、角色 activate/import/discard/recover，以及运行/校准/角色加载的读取入口须参与同一门；锁顺序固定为配置门→已有角色库锁。取消并等待当前 camera/calibration/GL预览所有权退出，禁止 pending 期间的库清理，旧 main renderer 不逐字段更新。预检只产生不可变 RestorePlan，按当前设置/selection摘要做 compare-and-set；预检后任何变更、文件替换或超时都重读，不沿用旧资格。

普通运行档位、光学和相机保存也必须进入同一个durable事务，不能仅在restore路径包一层锁。协调器保留 last-confirmed raw snapshot，包含受支持偏好键的原类型/值/存在性及精确selection。它只在新进程完成启动恢复、尚无本进程写入时初始化，或在本门确认持久提交与读回后更新；失败后的 `MirrorSettings.load/getAll()` 不能替换它。普通 `commit(false)` 可以已经把合法新map放入SharedPreferences缓存，当前旧settings/options/renderer对象不变并不使后续读取安全。

任何普通save写失败立即进入dirty/quarantine，保留事务及last-confirmed snapshot，拒绝备份、新配置读取发布、其他save和新运行/校准入口；不得因合法schema、无warning或读回新缓存而解除。提交点前失败必须以last-confirmed旧值成功持久重写、重验且完成旧selection恢复后才解门，写回再次失败就保持隔离。原活动可保留原不可变配置作为错误显示依据，但不得发布失败缓存为已保存或激活新档位。这个协议是下一实现要求，**现有v18普通save尚未具有此门**。

内部 `files/config-transaction-journal.json` 有固定 schema/事务 UUID、操作kind（ordinary_save或restore）、PREPARED或COMMITTED、旧/目标配置和 selection摘要/哈希，最大32 KiB。普通保存不改角色时目标selection与旧值相同，也记录preimage，保证进程死亡后同样以journal决定胜出值。旧配置保存**白名单键的原类型、值和存在性**；unknown偏好键永远不触及。旧角色记录精确 current/previous/candidate及各 retained Ticket；目标按现有 activation规则计算。只补角色库窄内部 snapshot/CAS/回滚接口，仍在原 guard 下校验保留包，不直接写其私有文件、不通过UUID构造任意路径。journal使用与角色库一致的 checked sync+atomic rename协议，写入/发布/读回失败均失败关闭；不把无返回错误的写 API 当作提交证明。

1. 在配置门内重验文件、旧配置、所有相关角色 Ticket、相机绑定和本轮 GL资格。同步并发布 PREPARED，读回完整字节/hash成功后才允许第一次设置写入。
2. 用**一个 Editor**更新受支持键并 `commit()`，不调用 `clear()`，随后发布角色目标 selection。两个存储虽暂时不一致，但所有应用读取/写入及运行入口处于隔离门内。
3. 两者均成功且读回匹配后原子发布 COMMITTED；这是逻辑提交点。随后按新配置一次重建会话、清除个人校准，不先改旧 renderer。确认落地后清理 journal；清理失败保留 COMMITTED，重放必须幂等。
4. 任何提交点之前的失败/进程死亡：PREPARED 唯一选择旧配置，重新写白名单旧值/删除原本不存在的白名单键并还原精确旧 selection。COMMITTED 唯一选择目标，启动时补全目标。先同步成功及重验，再解除门；rollback也失败时保留 journal、只显示恢复错误/重试，禁止以半配置启动或继续普通保存。

启动恢复必须先于 `MirrorSettings.load`/角色选取/包清理/新 worker；覆盖直接打开校准、PanelPreview和角色管理等入口，不能只放主 Activity 的 `onCreate`。未知/损坏 journal或存储不属于预期旧/新状态时失败关闭，不删记录猜默认。进程死在 journal清理后也已具有完整落地配置。原 APK 不懂此门，因此待恢复事务必须解决后才能回装旧APK；不承诺旧版本自动完成恢复。

提交前返回/超时可取消并恢复旧配置；第一次持久写入后 UI 的离开不能靠取消 worker打断事务。提交点不确定时显示“结果待核对”，重读 journal决定旧/新，不声称“未改变”。COMMITTED后要回到原配置须新建逆向事务，不能撤销已完成提交。保证范围是参与本门的应用视角及可恢复的进程死亡；硬件断电、存储永久损坏、同UID外部改文件/旧APK写入不在已验证原子性范围。

## 实现顺序与验收门

先纯codec/不可变数据预检，再journal+启动恢复+全部普通save/read门，随后才开放本地备份，最后GUI接线/角色预览及恢复会话重建；未完成保存失败隔离门前不开放“备份已保存配置”，未完成完整事务门前不开放“一键恢复”。纯codec不读取SharedPreferences或角色Store，不宣称其输入已落盘。必要触点为新codec/coordinator/backup Activity、MirrorSettings窄读写、AvatarPackageStore窄selection事务、各实际UI入口与manifest；CLI/GPU算法/资产/NPU无需改。

先RED后GREEN验证：schema0–4迁移及未来只读；所有类型/未知键/脸字段拒绝与大小/超时；float32/long往返、草稿取消、不覆盖；builtin及现有 imported包精确三哈希/GPU资格、缺失包/相机变更/陈旧预检拒绝；commit(false)已变内存、selection失败、rollback失败；每次journal/设置/selection写入及commit点前后用独立进程中断，再从磁盘重建验证旧或新唯一胜出；包清理/其他保存竞争与直接Activity入口不得越门。专门注入普通profile/panel/camera save的真实缓存语义：Editor先改变内存map再返回false，随后 `getAll/load` 已显示合法新值且无warning，备份和新读取仍必须拒绝；旧运行配置不切换。只有旧值 `commit(true)` 且重验、PREPARED旧胜出恢复完成才允许备份；恢复旧值也false则持续dirty。再测失败后杀进程、从磁盘启动由PREPARED恢复旧值，不能把重新读缓存当持久化证明。host真实编排不只比mock实现；SDK编译后由root另做 release UI保存/恢复、强停重启及16/20实际count/尺寸/校准传播，记录范围，不把旧v17像素或APK回装证据套作恢复通过。
