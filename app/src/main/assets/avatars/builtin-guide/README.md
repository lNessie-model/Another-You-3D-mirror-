# 镜中向导：组合修正版角色原型

程序制作的原创、可编辑半身角色。正式入口用同一个 AvatarGlbLoader 读取标准 character.glb；avatar.json 记录实际 SHA256、节点、MediaPipe 来源与显式组合绑定。没有专用程序网格、贴图、skin、网络资源或外部模型。

模型具有真实眼窝与口腔开口、独立左右眼球及几何虹膜/瞳孔、上下眼睑、鼻颊下巴耳、短发、颈肩、嘴唇、暗口腔、上下牙和舌头。颜色使用 FLOAT COLOR_0 和不透明 factor-only 材质。

## 绑定与范围

- 7 个 primitive、15,085 个顶点、29,482 个三角形。
- 43 个原始非 gaze 形变，加 9 个组合 corrective，共 52 个几何目标；这不是 52 个独立表情。8 个 gaze 来源控制刚性眼球，_neutral 忽略。
- Face 的所有目标都有真实非零形变。MouthInterior 保留同样的 52 个索引，但深口腔和上牙是静态零 delta；下牙和舌通过 JawAttachments 节点移动。
- schema v2 要求 product-correctives-v1。先将原始来源系数限制到 [0,1]，再执行 derivedBindings 中 2 或 3 个来源的乘积。来源始终指原始系数，不能引用另一 corrective。
- 新增 jawOpen × mouthClose；每眼各有 blink × wide、blink × squint、wide × squint、blink × wide × squint。张颌闭唇保留完整下颌旋转和非嘴唇脸皮的 jawOpen，不靠清零张嘴完成闭唇。
- 上下闭眼边缘重合；组合睁大/眯眼不会重新撑开 blink=1 的眼睑。唇外缘有真实侧壁；下牙、舌置于口腔内，继续随下颌移动。

## 验证与限制

441 组 jaw/close 网格、2,662 组眼睑组合、32 组混合形变法线检查通过。导出后的 float32 GLB 另查 242 组完全闭眼组合；生产 loader/deformer/rig 测试检查真实文件和派生绑定。普通 Blender 几何预览包含中性、单眼闭、张嘴、笑、眼动、嘴部 3×3 矩阵以及头部 yaw ±25°/pitch ±12° 的组合视图。这些不是完整表面碰撞证明，也不是 Khronos glTF Validator 报告。

角色仍是原型美术：眼睑褶皱明显、闭唇拉伸较大、中性时上牙外露。并非所有 51 来源同时冲突的组合都通过视觉验收；实际 GLES 像素、屏幕效果和性能由设备测试另行确认。normalPolicy 为 recompute-deformed，不跨嘴唇、眼睑、发束盲目焊接。

## 离线复建

在 device-lab 目录使用已安装的 Blender 4.5.12 LTS：

```powershell
& 'C:/Program Files/Blender Foundation/Blender 4.5/blender.exe' --background --factory-startup --python-exit-code 1 --python scripts/build_builtin_avatar.py
& 'C:/Users/lNessie/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' scripts/inspect_builtin_avatar.py
```

独立复建可用 -- --output <目录> --report-dir <目录>；加 --no-render 跳过预览。检查器对应支持 --package <目录> --report-dir <目录> --no-contact-sheet。不需要下载或付费服务。生成时同时生成 schema v2 清单和许可证；检查器生成缩略图。

Blender Boolean 可能改变顶点和面编号，文件不保证逐字节相同。scripts/compare_builtin_avatar.py <原GLB> <复建GLB> --report <JSON> 精确比较全部位置、颜色、形变对应关系、保持绕序的三角形集合、节点和材质。每次替换仍须核对清单中的实际 SHA256。

生产回归入口：tests/run_avatar_tests.ps1 -AssetPath <GLB绝对路径> 和 tests/run_avatar_rig_tests.ps1 -AssetPath <GLB绝对路径>。报告、六状态与组合接触图、可编辑 .blend 位于 app/build/builtin-guide-review/，不打包到 APK。

本次 v1 资产/生成器回退副本在 app/build/builtin-guide-v1-rollback/；已归档 v1 APK 保留，不因本资产提升而重建或覆盖。资产按 LICENSE.txt 的 CC0-1.0 发布，不包含外部模型、图片、字体或 Blender 示例资产。
