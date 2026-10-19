# 「看见」视障辅助 Android App — 项目交接说明（供 AI 优化使用）

> 这是一份给 AI 编程助手的交接文档。目标是让接手的 AI 快速理解项目现状、已知问题和优化方向，然后动手改进。



***

## 一、项目是什么

一个给视障人士用的 Android 手机 App：**打开后摄像头实时识别前方环境，用语音（TTS）播报障碍物位置和距离**，辅助盲人步行出行。课程设计项目。



* **包名**：`com.example.see`

* **Min SDK**：24（Android 7.0）

* **targetSdk**：37

* **真机**：荣耀 300（测试机，夜间 / 白天户外人行道场景）

* **横竖屏**：已锁竖屏



***

## 二、技术栈



| 模块                | 用的什么                                                                                                                  |
| ----------------- | --------------------------------------------------------------------------------------------------------------------- |
| 相机预览              | CameraX（PreviewView 全屏预览）                                                                                             |
| 目标检测（离线）          | TensorFlow Lite，模型文件 `assets/models/yolov8n.tflite`（**注意：文件名是 yolov8n，实际内容是 EfficientDet-Lite0 320×320，COCO 90 类标签**） |
| 距离估计              | 单目测距（基于已知物体尺寸比例的简化估算，非立体视觉）                                                                                           |
| 离线语音识别（找 X / 控制词） | 讯飞离线听写（APPID/ABILITYID 写死在代码里）                                                                                        |
| 语音播报              | Android 系统 TextToSpeech（中文）                                                                                           |
| 联网识别（云端大模型）       | 硅基流动 SiliconFlow，OpenAI 兼容接口 `/v1/chat/completions`，模型 `Qwen/Qwen3-VL-8B-Instruct`                                    |
| 网络状态监听            | ConnectivityManager 默认网络回调                                                                                            |



***

## 三、目录结构（核心源码）

路径：`D:\AndroidProjects\see\app\src\main\`



```
java/com/example/see/
├── MainActivity.kt        58 KB，约 1200 行，主逻辑（相机绑定、检测回调、行走预警、联网识别、TTS调度、语音命令）
├── Detector.kt            13 KB，TFLite 检测器封装（加载模型、输入320×320、输出COCO框+类别+置信度）
├── VoiceRecognizer.kt     15 KB，讯飞离线语音识别（关键词唤醒："找杯子"、"停止识别"、"退出"等）
├── OverlayView.kt          10 KB，SurfaceView/自定义View，画检测框和距离/方向标签
└── DistanceEstimator.kt     6 KB，根据检测框尺寸估算距离（米）

res/
├── layout/activity_main.xml   全屏 PreviewView + OverlayView + 顶部标题 + 底部字幕 tv_status
└── values/strings.xml          所有播报文案

assets/models/
├── yolov8n.tflite          【实际加载的模型】EfficientDet-Lite0 320×320
└── efficientdet_lite1.tflite  【未使用的冗余文件】可删
```



***

## 四、整体运行流程



```
CameraX 每帧
   ↓
Detector 离线检测（EfficientDet，COCO 90类）
   ↓ 得到 [框, 类别label, 置信度]
DistanceEstimator 估算距离
   ↓
MainActivity.handleWalkingMode() 决策播报：
   ├─ <0.5米 → "小心！前方X米有Y"（紧急）
   ├─ 0.5~2米 → "前方X米，有Y，请注意绕行"（普通，5秒冷却）
   └─ 仅正前方中间1/3画面、框面积<35%才考虑
   ↓
TTS speak() 播报 + OverlayView 画框 + 底部字幕更新
```

**联网分支**（每 3 秒截一帧）：



```
networkRunnable → 缩放图片到640长边 → JPEG→Base64
   → POST 硅基流动 Qwen3-VL-8B，让它描述障碍物
   → 返回中文文本 → TTS播报 + 字幕
```

**网络模式自动切换**：有网 = 联网增强（云端描述护栏 / 交通锥等 COCO 没有的类别）；断网 = 纯本地检测。

连续联网请求失败 2 次会主动判定断网并播报 "已切换本地识别"。



***

## 五、当前关键参数（在 MainActivity.kt companion object 里）



| 常量                               | 当前值   | 含义                     |
| -------------------------------- | ----- | ---------------------- |
| WALK\_DANGER\_DISTANCE\_METERS   | 2.0f  | 白名单类别预警范围              |
| WALK\_URGENT\_DISTANCE\_METERS   | 1.0f  | 非白名单类别近距阈值             |
| WALK\_CRITICAL\_DISTANCE\_METERS | 0.5f  | 只有 < 0.5 米才说 "小心"      |
| WALK\_COOLDOWN\_MS               | 5000L | 普通预警冷却                 |
| WALK\_URGENT\_COOLDOWN\_MS       | 5000L | 紧急预警冷却                 |
| WALK\_MAX\_BOX\_AREA\_RATIO      | 0.35f | 框占画面 > 35% 过滤（贴脸 / 身体） |
| WALK\_START\_GRACE\_MS           | 3000L | 启动后前 3 秒不播报            |
| SCORE\_THRESHOLD (Detector)      | 0.4   | 检测置信度阈值                |
| NETWORK\_INTERVAL\_MS            | 3000L | 联网截帧间隔                 |
| NETWORK\_IMAGE\_MAX\_EDGE        | 640   | 上传图片长边                 |
| NETWORK\_TIMEOUT\_MS             | 10000 | 联网超时                   |
| NETWORK\_FAIL\_THRESHOLD         | 2     | 连续失败 2 次判断网            |

**行走白名单**（COCO label 英文名）：



* 高优先级：person, car, bicycle, motorcycle, bus, truck, dog, cat

* 中优先级：bench, fire hydrant, stop sign, traffic light, potted plant, chair, dining table, suitcase, backpack, refrigerator, laptop, tv



***

## 六、已实现功能



1. CameraX 实时预览 + TFLite 离线目标检测

2. 单目距离估算（米）+ 方向判断（左前方 / 正前方 / 右前方）

3. 分级预警：<0.5 米 "小心"、0.5\~2 米报位置

4. 检测框叠加绘制，紧急红色框 + 顶部标签

5. 底部实时字幕（tv\_status）显示最近一次播报

6. 语音控制：说 "找杯子 / 找瓶子 / 找椅子" 会进入找物模式（播报目标方向距离）；"停止识别 / 开始识别 / 保存画面 / 退出"

7. 讯飞离线语音识别（不依赖网络的命令词）

8. 联网增强：硅基流动 Qwen3-VL 描述 COCO 没有的障碍物（护栏、交通锥、垃圾桶等）

9. 网络自动切换：有网联网、断网降级，切换时语音提示

10. 生命周期：onPause 释放相机 / 语音，onResume 恢复



***

## 七、【重点】真机测试发现的问题（需要优化）

> 以下是用户真机实测截图发现的真实问题，按严重程度排序：

### P0 — 误识别离谱



1. **树干被识别成 "键盘 /laptop"**（截图：夜里梧桐树干就在正前方 0.9 米，App 播报 "前方 0.9 米有键盘"）——COCO 模型把树干误检成笔记本电脑，名字完全错误

2. **共享单车在路中间，联网大模型却回复 "前方无障碍物"**—— 离线检测到了自行车（红框标出），但联网分支说畅通，两个分支结论矛盾

### P1 — 该识别的不识别（安全隐患）



1. **电动滑板车（红色）停在人行道边缘，完全没框、没播报**——COCO 没有 "electric scooter" 类别

2. **路边分类垃圾桶（分类回收箱）完全没反应**——COCO 没有 trash bin，距离 > 1 米也不播

3. **黄黑防撞护栏杆、交通锥**——COCO 完全没有这些类别（这是当初加联网识别的原因，但联网识别效果不理想）

### P2 — 语音体验



1. **开屏欢迎语太长**—— 用户要求改成简短的 "欢迎使用看见" 即可

2. 联网识别有延迟（虽然已缩图到 640，但大模型生成仍需 2-5 秒）

3. 开飞行模式时系统回调误报 "网络可用"（已加失败计数兜底，但仍可能不准）

### P3 — UI / 界面



1. 界面简陋：顶部 "看见" 黄字 + 中间摄像头 + 底部字幕，**没有任何模式指示（当前是联网还是离线？）**

2. OverlayView 框样式单一，紧急 / 普通没视觉区分（除了红色框）



***

## 八、建议优化方向（给 AI 参考）



1. **修复 "树干→键盘" 误报**：

* 方案 A：加后处理白名单 ——laptop/keyboard/ 手机等小物体如果框位置在画面下半部（地面附近）且高度 > 画面 1/3，判定为误检，不报

* 方案 B：把误报率高的 label（laptop、keyboard、mouse、remote 等小电子物）从行走预警中排除，因为人行道上不会真有笔记本电脑挡路

1. **解决离线 / 联网结论矛盾**：离线检测到高置信度（>0.6）的自行车 / 人 / 车时，即使联网模型说 "无障碍"，也要以离线结果为准播报

2. **扩充 "地面障碍物" 识别能力**：

* 离线端：把 COCO 的 fire hydrant、stop sign、parking meter、truck、bus 等靠近地面的类别全部纳入近距预警

* 联网端：优化 prompt，明确要求 "必须识别出路面上的任何立体障碍物，哪怕是垃圾桶、杆子、树、台阶"

1. **欢迎语缩短**：找到 initTts/playWelcome 相关代码，改成 "欢迎使用看见"

2. **加模式指示灯**：顶部标题旁加个小圆点，绿色 = 联网智能识别、灰色 = 本地识别，让用户和答辩老师一眼看到当前模式

3. **联网识别结果也要画框 / 标注**：现在联网结果只出文字，不画框，演示时看不出 AI 在工作



***

## 九、如何编译



```
cd D:\AndroidProjects\see
.\gradlew.bat assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`（约 200MB，debug 签名，可直接覆盖安装真机）



***

## 十、约束（不要改的东西）



* **论文 / PPT 口径统一写 "基于 EfficientDet-Lite"，禁止写 YOLOv8**（虽然文件名是 yolov8n.tflite，那只是文件名，模型本身是 EfficientDet）

* **不要换模型文件**（EfficientDet-Lite0 已在真机验证可用，换 YOLO 需要导出 tflite，新版 ultralytics 在 Windows 不支持）

* 不要加需要额外权限的功能（已经申请了相机、麦克风、网络）

* App 是给视障用户用的，所有交互都要语音优先，不要加需要精确触摸的按钮

* 语音播报要短，每句不超过 15 个字，避免一句话说不完