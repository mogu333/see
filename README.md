# 「看见」See — 视障环境感知辅助 App

> 基于 Android 端实时目标检测与多模态大模型的视障步行障碍预警应用

## 📖 项目简介

「看见」是一款面向视障用户的 Android 出行辅助应用：通过手机摄像头实时识别前方障碍物，结合单目距离估算与方位判断，用语音（TTS）播报障碍物距离与方向，帮助视障人士在人行道上安全行走。

**核心特点**：
- 📷 纯软件方案，零额外硬件成本，一部 Android 手机即可使用
- 🎙️ 语音优先交互，全程无需精确触摸屏幕
- 📵 离线检测保底，无网络也能使用，保护隐私
- ☁️ 有网时自动叠加云端多模态大模型，识别护栏、交通锥等离线模型未覆盖的类别

## ✨ 核心功能

| 功能 | 说明 |
|---|---|
| 实时目标检测 | CameraX 实时取景，TensorFlow Lite 部署 EfficientDet-Lite 模型，识别行人、车辆、自行车、路牌等 COCO 80 类物体 |
| 距离与方位估算 | 单目视觉估算障碍物距离（米），按画面三等分判断左前方/正前方/右前方 |
| 分级语音预警 | 0.5m 内紧急"小心"；2m 内提示绕行；5 秒冷却防刷屏；仅关注正前方中间区域 |
| 语音控制 | 讯飞离线语音识别，说"找杯子/找椅子"进入寻物模式，支持"停止识别/退出"等命令 |
| 联网增强 | SiliconFlow Qwen3-VL 多模态大模型识别护栏、交通锥、垃圾桶等离线模型不认识的类别，网络状态自动切换 |
| 无障碍输出 | TTS 语音播报 + 屏幕字幕双通道，锁竖屏，打开即用 |

## 🛠 技术栈

- **语言**：Kotlin
- **相机**：Android CameraX
- **端侧推理**：TensorFlow Lite（EfficientDet-Lite0，320×320 输入，COCO 80 类）
- **语音**：讯飞离线语音识别（AIKit）、Android TextToSpeech
- **云端**：SiliconFlow OpenAI 兼容 API（Qwen/Qwen3-VL-8B-Instruct 多模态大模型）
- **最低版本**：Android 7.0（Min SDK 24）

## 📁 项目结构

```
app/src/main/java/com/example/see/
├── MainActivity.kt        主逻辑：相机绑定、检测回调、行走预警、联网识别、TTS 调度
├── Detector.kt            TFLite 检测器封装
├── VoiceRecognizer.kt     讯飞离线语音识别与命令词
├── OverlayView.kt         检测框叠加绘制
└── DistanceEstimator.kt   单目距离估算
```

## 🚀 运行方式

### 1. 环境要求
- Android Studio Hedgehog 或更新
- JDK 17
- Android SDK 24+

### 2. 配置 API Key（重要）

本项目的 API Key **不写在源码里**，需要在 `local.properties`（位于项目根目录，已被 .gitignore 忽略）中配置：

```properties
# SiliconFlow 多模态大模型 API Key（用于联网识别，可选）
SILICONFLOW_API_KEY=sk-xxxxxxxx

# 讯飞离线语音识别凭据（可选，不配则语音控制不可用）
IFLYTEK_APP_ID=xxxx
IFLYTEK_API_KEY=xxxx
IFLYTEK_API_SECRET=xxxx
IFLYTEK_ABILITY_ID=xxxx
```

> 没有配置 key 也能编译运行，只是联网识别和语音控制功能不可用，离线检测功能正常。

### 3. 编译运行
```bash
./gradlew assembleDebug
```
APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`，安装到 Android 7.0+ 真机即可。

## 📝 备注

- 模型文件 `assets/models/yolov8n.tflite` 文件名虽为 yolov8n，实际内容是 EfficientDet-Lite0（320×320, COCO 90 类）
- 当前版本仍在迭代中，已知不足：COCO 预训练模型缺少树干、护栏杆、电动滑板车等类别，后续将通过后处理规则或自训模型改进
