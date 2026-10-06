# V38 / 0.2.7：独立设置首页与正确返回来源

从首页查看设置以前会先启动魔镜、相机和渲染；关闭设置后落到运行页，错误菜单进一步增加返回层级。V38 增加独立的椭圆主题设置页：浏览设置和关于不创建相机、面捕 worker 或 GLSurfaceView；系统返回键与“返回首页”直接回首页。进入画面预览、相机校准或高级编辑后继续使用原编辑器和保存/取消事务，退出运行页返回设置；运行菜单明确“返回首页”仍保留原首页动作。

最终 APK versionCode 38 / versionName 0.2.7，131140462 bytes，SHA256 `fba44e64390f1968385aba54134d595e571d88414a2ce17df0cd8fe558dc328c`。SDK35/JDK17离线构建通过；原签名、单产品launcher、私有设置Activity；78 assets和9 native entries与V37逐字节一致。没有新增权限、网络框架、模型生成或固件改动。内部覆盖安装保留ADB与原四份配置。

## 导航与冷启动修复

新设置页仅用户选择编辑项后进入MirrorActivity，并带返回来源标记。相机校准需要角色和首GL帧：请求在两个条件满足前保留，通过原onResume与tick在UI线程消费一次；暂停时不分派，Back先取消待打开请求。原showCameraCalibration guard、失败提示和资源退出路径保留。普通从首页进入魔镜的Back与明确返回首页动作保持。

首个候选发现相机action提前消费的P2，已拒绝部署为通过版。最终v2修复后独立审查未发现新的确认P1/P2；真实两个新Activity/运行会话均打开相机校准面板、角色动作预览READY、main_multiview_paused=true，返回设置后无GLThread/AvatarPose。READY表示角色预览，不表示相机采集恢复。

## 实际验证

- 真机独立设置/关于版本0.2.7（38）/关于返回/系统Back回首页/首页按钮均通过。浏览设置时原runtime status字节未改变，未见GLThread、AvatarPose或MirrorRuntime线程；Android原生UI自身仍正常绘制。
- 真正画面编辑面板与预览控件、相机校准面板两次冷启动、高级编辑面板均验证。画面与相机各经3个Back（对话框、错误菜单、运行页）回设置，高级2个Back；没有选择保存。明确页面操作与返回来源正确，但仍可继续简化子编辑器的层级。
- 普通魔镜实际GL frame ready、Geralt、PBR reference、两个候选关闭、GPU query pool0；角色页、普通Back回首页与无GL/pose线程通过。最终安装SHA及5项APK输入源码复核一致。
- 原runtime、scene-view、角色选择及导入store状态四份配置逐字节保持；全部素材/native不变。当前设备停在首页。
- 原GL生命周期34检查通过；真实Activity输入停止桥接52检查通过。后者旧测试用Unsafe跳过构造，漏初始化avatarStartup，在修改前V37也复现相同NPE；补齐真实启动门后通过，未修改生产preflight guard。host不代替EGL/相机HAL验收。
- 连续更新的相机面板在持续硬件流失败时会使uiautomator idle/root采集失败。保留失败捕获，改用实际PNG、两个不同新session的camera_calibration_open及READY角色预览报告独立验证打开，未把无XML当成功。实际截图已由root检查。

## 保留边界与下一步

摄像头仍CAMERA_ERROR(3) / endConfigure流配置失败，无实时采集成功或个人面捕验收；保存安装按钮在输入未准备时禁用是原有正确行为。本轮没有重做联合FPS测试，V37录像+RGA+RKNN478+混合CPU/NPU52+完整Geralt/16视点的8.897/8.817/8.894FPS仍仅是其原实验条件，未达30FPS；V38不宣称提速。

三个可选角色、27项素材库、全部78打包assets沿用。其它独立头部的自然闭眼/眉毛/微笑/口腔/PBR美术校正和真实设备验收、三维背景接入仍待完成；未以未通过的候选替换产品。场景与相机详细编辑器还需继续统一视觉与简化返回层级。下一性能迭代是按比例缩小tile的真实16视点候选及同包画质/联合实际呈现测试，保留完整材质并实测，不预测30FPS必达。完整用户目标仍未完成。
