# YS-L6 无摄像头性能验证

目标：可恢复地精简无关后台应用；在设备上运行真实 MediaPipe Face Landmarker；测裸眼 3D 多视图渲染与交织、以及两者联合负载。

范围：保留系统框架、ADB、网络、输入、相机/显示驱动、预装 3D 软件及配置备份；以用户级停用与恢复脚本完成精简。没有厂商明确校准参数时，交织结果仅作为通用性能测试，不验证光学效果。

测试：精简前/后空闲基线；CPU/GPU 面部推理；原生输出分辨率的 2/4/8/16 视点交织；动态程序网格多视角渲染与交织；与推理联合。分别记录推理耗时、有效处理率、帧间隔、GL 完成耗时、PSS、CPU、MemAvailable、温度及频率。

输入：Google 官方人脸测试图，视频模式连续送帧。其结果代表稳定人脸跟踪计算负载，不包括摄像头采集、视频解码或真实表情准确率。场景负载是可复现的程序生成网格，不等同于最终角色材质/动画。

实现顺序：先部署最小 MediaPipe APK并验证有效人脸输出；再增量加入 OpenGL ES 多视图与交织；最后执行矩阵、保存原始数据和复现脚本。

官方来源：
- https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker/android
- https://ai.google.dev/edge/mediapipe/solutions/setup_android
- https://developer.android.com/reference/android/opengl/GLSurfaceView
- https://developer.android.com/reference/android/opengl/GLES30
- https://developer.android.com/tools/adb
