# AvatarPreview 的 16 视点验证入口

只有被选中的 `verify_persistent_fbos` 与 `verify_cached_camera_vp` 两条入口读取 `test_view_count`，沿用 `RuntimeViewCount` 的严格 debug Java Integer 16/20 合同，缺省 20；非 Integer、null、其它数值会拒绝。验证 flag 本身仍沿用原 debug 开关：真实 release Activity 不启用验证入口，该覆盖被普通预览忽略；主机替身直接选中入口时，parser 的 non-debug guard 仍拒绝显式覆盖。其它入口不读取这个覆盖。`verify_multiview_16` 始终走原独立严格 16 gate；batch、multiview、普通预览语义保持。

两条入口继续调用既有 generic `run`，400×720 单层与 1200×1920 投影视比保持；比较实现、69 组 pose fixtures、RGBA 门和 GPU 数学未改。运行、完成与失败文本/报告使用实际视点数；报告新增 `requested_view_count`，成功报告的 `views` 仍由底层比较器提供。

|入口|16 视点报告|默认/显式 20 报告|
|---|---|---|
|persistent FBO|`avatar-persistent-fbo16-check.json`|`avatar-persistent-fbo-check.json`|
|cached camera VP|`avatar-camera-vp16-check.json`|`avatar-camera-vp-check.json`|

running、失败、成功与取消均走同一选定文件，16 不覆盖旧 20 或另一入口的 16 文件。配置错误沿用失败报告路径；无有效覆盖时报告默认 20，不能以旧 `passed` 作为本轮结果。

主机命令：`powershell -NoProfile -ExecutionPolicy Bypass -File tests/run_avatar_verification_dispatch_tests.ps1`。先链接真实 SDK 35 的两份生产源码，再使用明确的 Activity/AtomicFile/比较器替身，执行生产 `onSurfaceCreated` 和从 `onSurfaceChanged` 提取的实际分发方法，截获真实 `run` 参数并读取生产生成的 JSON 字节。覆盖缺省/16/20、严格输入拒绝、固定16与旧入口独立、running→成功/失败/取消、两条16文件和旧20文件隔离、实际标签。首次新增断言因原实现缺少视点字段而 RED；另一次真实 RED 复现双入口16请求被拒时误写旧20报告，改为先严格读取有效count再拒绝双入口后 GREEN。该测试不调用 GL、不加载模型、不验证 Android AtomicFile 的原子性，也不证明设备像素通过。

既有 `tests/run_runtime_view_count_tests.ps1` 继续核对真实 SDK 链接、严格配置、原矩阵/光学校准与固定16 gate。设备负责人后续须绑定新 APK/model/scene/fixture/尺寸身份，分别执行 **legacy16→persistent16** 与 **persistent16→cached16**：每轮完成 69 fixtures、1104 层比较、无错误/取消且严格 RGBA 相同；然后对全部 fixture 和层逐项核对第一轮 candidate SHA 与第二轮 reference SHA，建立同一中间 persistent16 图像的组合链。还须核对两份报告的运行 UUID/时间与实际 `views=16`，保留完整 raw。当前只有主机分发证据，未声明已有 v16 persistent/cache 组合通过。
