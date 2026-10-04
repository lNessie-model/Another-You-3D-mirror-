# 头部材质恢复与法线修正

用户指出模型质量下降、需要更真实材质。当前头模的 Tripo 原件仍有 2048² Color、NormalGL 和 ORM；旧适配器只保留颜色，设备仅执行简化光照。重新计算法线时未合并 UV 接缝的重复顶点：当前原表面 13,975 个顶点对应 5,092 个完全相同的 rest-position / authored-normal / 全部 morph 轨迹组，导致明显三角块。

新增显式 GLB `extras.mirrorPbrAtlas=1`，必须同时声明 `mirrorAlbedoAtlas=1`。恰好三张内嵌 RGB8 PNG 和三个固定索引纹理，分别颜色、法线、ORM；沿用每张 2048²、4 MiB、完整 CRC/inflate 检查，拒绝 URI、sampler、动画与未知纹理字段。PBR 解码上限 80 MiB；旧 profile 仍限制 32 MiB。Manifest 声明 `mirror-pbr-v1`，必须与实际 PBR maps 一致。

原颜色和 NormalGL 编码字节不变；ORM 从 JPEG 换为 PNG 容器，逐像素验证解码 RGB 一致。原形变、节点、UV、索引拓扑均逐项验证；仅把白 atlas UV 的新眼睑/唇圈与原表面分成独立 primitive，眼球另设粗糙度 0.18。原表面法线强度 0.65，ORM AO 强度 0.5，非金属。三角形仍 16,005、实例顶点 17,832，解码计费 72,350,558 bytes。

`smooth-deformed-v1` 只合并 rest POSITION、authored NORMAL 和每个 POSITION morph delta 全部相等的顶点。硬边和不同形变轨迹保持分离。分组在构造时完成，每次形变按共享组累积面积法线，无每帧 hash/Buffer 读取；旧 recompute/authored 路径保持数值兼容。GL、工作线程与导入校验统一使用 manifest policy。

新增轻量 GGX 主光、漫反射补光与半球环境光。颜色纹理用 sRGB，法线/ORM 用线性 RGBA8，AO 只影响环境光。切线框架由当前形变几何的片元导数近似生成，并非 authored MikkTSpace。v29 为三张纹理建立 GPU mip 链，原 level-zero 数据不变；总纹理约 64 MiB，比旧单颜色 16 MiB 增加约 48 MiB。不是完整 glTF 通用 PBR、HDR 环境反射或皮肤次表面散射。

## 验证与实测

加载器 368、atlas 29、PBR maps 15、batch layout 32、旧 shader 288、atlas shader 112、PBR shader 128 合同检查通过；平滑法线解析几何检查保证 UV seam / hard edge / divergent morph 三种行为。实际资产 51 输入与 64 工作线程快照通过，所有 primitive 与同步结果逐位一致。旧内置资产另外通过 3,893 数值参考和 5,309 cache 检查。通用 rig/schema/controller 检查通过。历史 `AvatarRigV2Test.actual` 专为旧 22° jaw/旧 corrective 名称写定，向它传阶段6会失败，因此未作为阶段6验收依据；其 fixture 部分仍通过。

v29 Android APK 构建通过，所有 native inference `.so` 与 v28 逐字节一致。Mali-G52 真实渲染10姿态；9姿态×16层 serial/OVR 和 individual/batch 检查通过。serial/OVR 容差 RGB=0；batch 历史 profile 容差 RGB=1、RMSE=0.1，不能把两个门槛都称“严格零误差”。用户配置逐字节保留，live camera 采集和状态递增确认；复核时无人脸，实时新眼部效果仍待用户验收。

录制人脸 + NPU + 16×400×640 + 用户 scale 2.34、背景9、mirror=true、嘴眉强度2.5，同步开启所有部分，60秒短测试：v28 无 mip 10.268 FPS，v29 mip 10.835 FPS，均约49.7秒确认交互且完整呈现历史。v29 交互资源和其证据见输出报告；两轮温度不同，不能把小幅差异当稳定性能收益。均未达到30 FPS。此前阶段5 live 15.244 FPS 输入与材质不同，不能做严格 A/B。

## 当前交付与限制

设备 v29 APK SHA256 `e7b5ffc0c596524a5f7aa306d50a40a4c7ad03bb1c2f45cbf06cf74b84b95a1a`，GLB `b52b4e6a459a8c840f7cde55c4140e398e88e576c6a611a99fcffbd153cd8f8a`，阶段6目录 `E:/tripo/assets/mirror-models-20261003/lowpoly-heads-v2/geralt-rig-stage6-pbr-v2`。v27 driver gate 通过后检查脚本读到启动空 avatar，引发自动回退 v26；检查逻辑已修复，v28/v29 完整预览通过。无刷机、重启、root、驱动或摄像头 HAL 修改。原 v26 APK、阶段5 head/manifest 和配置有完整备份。新 Tripo 支出0，累计535/4000，剩3465。

眼睑和嘴唇新拓扑仍用采样顶点色，尚未恢复细纹；胡须几何与眼睛仍是低模，牙齿/唇形、光学对准、全表情碰撞和艺术验收未完成。下一阶段应在保持平滑法线和材料细节的前提下减少16视图片元负载，再处理新拓扑的 UV 细节。当前目标保持进行中。

[设备原始截图和状态](E:/tripo/output/mirror-assets/20261004/stage6-pbr-mip-device-check/receipt.json) · [局部对比](E:/tripo/output/mirror-assets/20261004/stage6-pbr-mip-device-check/material-before-after.svg) · [实际呈现性能](E:/tripo/output/mirror-assets/20261004/stage6-pbr-mip-performance/stage6-pbr-mip-16v-replay.json)
