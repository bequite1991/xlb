# 小萝卜机器人 Android 端 (xlb-robot)

机器人本地控制服务，负责：
- 串口通信（MCU 电机、表情屏、电池、触摸）
- MQTT 连接云端
- 语音对话（录音上传、TTS 播放）
- 唤醒词检测
- SoftAP 配网（长按胸前按钮 2 秒）
- OTA 自动更新

## 技术栈

- Java / Android SDK 22+ (Android 5.1)
- Gradle 8.x
- MQTT (Paho)
- OkHttp
- Picovoice 唤醒词

## 目录结构

```
xlb-robot/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/
│   │   ├── android_serialport_api/
│   │   │   └── SerialPort.java
│   │   └── com/xlb/robot/
│   │       ├── ApManager.java           # SoftAP 热点管理
│   │       ├── BootReceiver.java        # 开机自启
│   │       ├── MainActivity.java        # 主界面（空）
│   │       ├── MqttManager.java         # MQTT 客户端
│   │       ├── RobotApp.java            # Application
│   │       ├── RobotService.java        # 核心后台服务
│   │       ├── SerialManager.java       # 串口协议
│   │       ├── SimpleHttpServer.java    # 配网 HTTP 服务
│   │       ├── TestCmdReceiver.java     # 调试广播接收器
│   │       ├── TtsPlayer.java           # TTS 音频播放
│   │       ├── WakeWordHelper.java      # 唤醒词
│   │       └── WifiConfigReceiver.java  # WiFi 配置广播
│   ├── jniLibs/armeabi-v7a/
│   │   └── libserial_port.so
│   └── res/layout/
│       └── activity_main.xml
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## 构建

```bash
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

## 配网流程

1. 长按胸前按钮 2 秒（触摸事件 `0x11`）
2. 眼睛开始闪烁，进入 SoftAP 模式
3. 手机连接热点 `XLB-Setup`，密码 `xiaoluobo`
4. 浏览器访问 `192.168.43.1:8080`，填写 WiFi 信息
5. 机器人自动关闭热点并连接 WiFi
