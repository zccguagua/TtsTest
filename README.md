# Android TTS 测试

验证 Android 系统自带文字转语音（TTS）能力的小应用。不依赖任何第三方语音库，
直接调用设备上已安装的 TTS 引擎做语音合成。

## 功能

- **文字朗读**：输入文字点「朗读」，滑杆调速（0.1×~2.0×），可随时停止。
- **悬浮窗播报**：开启后屏幕上出现一个可拖动的圆形按钮，先在其他应用复制文字，
  再点按钮即朗读剪贴板内容。
- **拍照识别朗读**：拍照或从相册选图，用 ML Kit 离线识别中文文字，识别结果可
  编辑、可复制、可朗读。
- **语音识别（ASR）**：按住说话、松开识别，用 sherpa-onnx 的 SenseVoice 模型
  离线识别，支持中 / 英 / 日 / 韩 / 粤语，并保留最近识别历史。
- **状态提示**：显示引擎初始化中 / 就绪 / 正在朗读 / 缺少语音数据 / 初始化失败。

## 说明

- 朗读（TTS）是否离线，取决于设备上该引擎已安装的语音数据，App 本身不含 TTS 语音
  模型。引擎、音色、系统默认语速在「设置 → 语言和输入法 → 文字转语音输出」里配置。
- 语音识别（ASR）使用 sherpa-onnx（本地 AAR `sherpa-onnx-1.13.8.aar`）的 SenseVoice
  多语言离线模型（`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`，int8 量化），
  模型文件 `model.int8.onnx` 约 239MB，随 App 打包进 assets，识别全程离线无需联网。
  加上该模型后 debug 安装包约 340MB。
- 拍照识别使用 Google ML Kit 的中文离线模型（`com.google.mlkit:text-recognition-chinese`），
  识别在本地完成、无需联网；模型会打包进 APK，因此安装包体积较大（约 48M）。
- 悬浮窗由前台服务保活，状态栏有一条常驻通知；关闭开关即消失。
- 悬浮窗需要「显示在其他应用上层」权限，首次开启会跳系统设置授权。
- Kotlin + XML 布局，minSdk 24 / targetSdk 36。用 Android Studio 直接 Run，
  或 `./gradlew installDebug` 安装到已连接设备。
