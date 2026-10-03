# Tripo 头模的受限纹理支持（设备快速检查通过）

需求：只含头部和短颈的成熟 IP 风格模型；MediaPipe / NPU 面捕；16 视点、每视点 400×640，目标 30 FPS。保留已验收 v20，新增代码与资产先独立检查，失败回退。

观察：Tripo 原件具有 baseColor / ORM / normal 三张纹理，原始身体自动绑定对纯头部返回 riggable=false。离线顶点色采样虽通过加载器和数据检查，仍丢失眼睛细节；增加细分也未达到外观要求。因此需要受限的 albedo atlas 支持，不能把顶点色版本称最终外观。

## 新资产契约

GLB root extras 声明 `mirrorAlbedoAtlas=1` 时允许恰好一个内嵌 PNG atlas，恰好一个 texture，UV 为 TEXCOORD_0。PNG 限 8-bit RGB、1..2048 像素、4 MiB 编码长度；检查 chunk 长度和 CRC，拒绝 URI、PNG 动画、未知纹理扩展或 sampler 配置。所有图像仍来自嵌入 BIN；不联网读取。

允许 opaque、metallicFactor=0 的既有材质额外引用 baseColorTexture.index=0 / texCoord=0。不引用 texture 的材质仍按既有因子 / 顶点色渲染。拒绝 normal / ORM / occlusion / emissive 纹理和 KHR_texture_transform。

默认不声明此 profile 的 GLB 继续执行既有 factor-only 规则；已有角色、渲染 shader 的数学顺序和测试契约保留。

CPU 资产增加不可变 atlas 编码和尺寸、材质 texture 标志，计入 decoded budget：编码字节 + 一份 RGBA 解码尺寸。GPU 创建场景时仅解码并上传一次为 sRGB texture，然后释放 Bitmap。纹理归创建该场景的 EGL 上下文所有，dispose 与上下文丢失遵守已有 VBO 生命周期。

新增 shader 只用于 atlas 资产，纹理采样由 sRGB 格式转换为线性 RGB，再进入原有光照和输出流程。批渲染共享一个 atlas，在静态材质参数第三分量区分是否使用纹理；UV 静态上传，不增加逐帧 CPU 打包。

## 验证

先以小 PNG 的合法与非法 fixtures 验证生产 loader：缺失声明、外部 URI、多 atlas、过大尺寸、非法 CRC、非 RGB、缺失 UV 等明确拒绝。验证旧角色与 shader 完整回归。新增 shader 在实际 Mali 上编译和对比像素；随后再用新头模完整人脸回放验证联合 FPS / 资源。

北京时间 2026-10-04：实际杰洛特局部校准头模在 Mali 上完成 10 个状态截图。新私有头模检查单独使用 9 个冻结姿态、每姿态 16 层：serial vs OVR4 的 144 次比较严格 RGBA 一致；individual vs batch 的 144 次比较通过原有 RGB≤1、RMSE≤0.1 标准，分别耗时 36.629 / 36.683 秒。未降低像素标准，旧 69 姿态回归不变。完整 69 姿态头模运行曾超出 300 秒，失败证据保留；9 姿态结果不等于完整回归或 FPS。

有效设备证据：`app/build/v22-albedo-device-9b016392-34ce-481c-a2dc-4e865b27fc8b`。该次回退同时核验 v20 APK、偏好、实际 `files/avatars/state.json` 字节哈希与两次推进的人脸回放。首次旧检查误用 selection.json 与过短回放等待的失败另存，未覆盖。

当前面部资产仅 11 个校准形变；40 个源未绑定。缺少合格口腔与组合修正，不是完整面捕角色。新头模、背景与 NPU 联合性能尚未验证。新 PNG/UV shader、实际角色生产加载及既有缓存/场景 host 检查通过；报告不会将 host 检查描述为设备 GLES 运行。

接口设计采用 api-and-interface-design 技能的附加字段方式，保留原构造调用；不改 NPU 的输出通道顺序。所有源模型及修正产物另存，不覆盖原件。

截止：北京时间 2026-10-04 06:30 起收尾，07:00 停电。未完成资产或检查必须在交接中标记未完成。
