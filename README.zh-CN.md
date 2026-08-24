# Constellation Glass

[English](README.md) · **简体中文**

[Constellation](https://github.com/MRziyi/Constellation) 的眼镜端客户端——一个面向全天候可
穿戴助理的个人 AI 框架。这是一个刻意做薄的 Android 应用：采集语音和画面，经 TLS WebSocket
送到 Mac，再把返回的卡片渲染到 480×640 的单色绿 micro-LED 屏上。

**智能几乎全都不在这里。** 眼镜负责采集和显示，
[服务端](https://github.com/MRziyi/Constellation-Server)负责思考。这个切分是设计本身，不是
以后要补的短板——正是它让眼镜保持凉、轻，以及一天的续航。

## 目标硬件

Rokid Glasses —— JBD4020 单色绿 micro-LED，480×640 竖屏，仅右眼；YodaOS-Sprite
（Android 12 Go，API 32），Qualcomm 8250 + NXP RT600 DSP。

应用走**裸机**路线：一个装在眼镜自己身上的普通 Android 应用，用原生 `AudioRecord`、系统按键
广播和 `SYSTEM_ALERT_WINDOW` 悬浮窗。它**不**链接 CXR-L SDK，也不作为手机侧桥接运行。取舍
理由和代价见
[GLASS-CLIENT-DESIGN.md](https://github.com/MRziyi/Constellation/blob/main/docs/glass/GLASS-CLIENT-DESIGN.md)。

## 它做什么

| | |
|---|---|
| **语音** | 物理按键按下说话，有硬性时长上限。没有唤醒词，不做环境监听——这是能耗承诺，不是功能缺失。原始 16 kHz PCM 流到服务端，转写在那边做。 |
| **视觉** | CameraX 静帧拍摄，由快捷指令或手势触发。画面作为图像块送到服务端。 |
| **HUD** | Jetpack Compose 渲染进 `SYSTEM_ALERT_WINDOW` 悬浮窗，卡片可以盖在任何正在运行的东西上面。 |
| **控制** | 单击批准，双击杀掉，长按续接或重讲。没有 dismiss——每张卡片都会走到一个终态。 |
| **配对** | 扫 Web 控制台的二维码。码里带端点和鉴权 cookie；应用里不编译进任何服务器地址。 |
| **戒指** | 来自 [Halo Ring](https://github.com/MRziyi/Halo-Ring) 的可选手势输入，走广播插件协议。没有它应用也是完整的。 |

## 构建

```bash
./gradlew :app:assembleGlassDebug
adb -s <glass-serial> install -r app/build/outputs/apk/glass/debug/app-glass-debug.apk
```

需要 JDK 17。`minSdk` 28 · `targetSdk` 32（YodaOS 出厂即 API 32）· `compileSdk` 34。

### 两个 flavor

| Flavor | 用途 |
|---|---|
| `glass` | 真机。`AudioRecord` 用声道掩码 `0x6000FC`，前台服务里接系统按键广播，HUD 走悬浮窗。 |
| `phoneDebug` | 任意普通安卓手机。单声道 `AudioRecord`、`SYSTEM_ALERT_WINDOW` 悬浮窗，用通知栏按钮模拟输入。让你不用把眼镜架在脸上就能验协议、状态机和 WSS 链路。 |

日常开发建议用 `phoneDebug`。它是客户端的真实模拟器，不是桩——HUD Composable 两个 flavor 共用。

> **release 构建用的是 debug keystore 签名。** 对一个 sideload 的原型来说这是有意为之，设在
> `app/build.gradle.kts` 里。真要分发之前，换成正式的上传密钥。

## 目录结构

```
app/src/main/kotlin/com/constellation/glass/
  ConstellationService.kt   撑住整个会话的前台服务
  MainActivity.kt           应用 UI 宿主 · BootReceiver.kt  开机自启
  state/                    State.kt + StateMachine.kt —— 客户端全部行为模型
  wss/                      WssClient.kt（OkHttp）+ Frames.kt（kotlinx-serialization）
  hud/                      HUD 表面、布局、主题、滚动窗口、样式化文本渲染
    composables/            具体的卡片
  audio/                    采集 · 管线 · MicGate（硬性时长上限在这里）
  camera/                   CameraCapture · CameraGate · QrScanner
  auth/CookieStore.kt       配对拿到的会话 cookie
  app/                      EndpointStore（DataStore）+ 应用内各界面
  halo/                     Halo Ring 桥接——触发接收器、悬浮层、动作提供方
  input/InputHandler.kt     单击 / 双击 / 长按的语义
  net/HttpRetry.kt          针对易掉线链路的重试策略

app/src/glass/              设备专属：音频采集、HUD 悬浮窗、系统按键
app/src/phoneDebug/         手机模拟器：音频、HUD、模拟输入
```

从 `state/StateMachine.kt` 读起。关于客户端行为的问题，答案几乎都在那里。

## 网络

链路是到公网中继的 TLS WebSocket，再由中继转发给 Mac 上的 Cortex。实际跑过 Wi-Fi、Tailscale、
以及经手机热点的蓝牙 PAN——各有各的失效方式，记录在
[NETWORK-ALTERNATIVES.md](https://github.com/MRziyi/Constellation/blob/main/docs/glass/NETWORK-ALTERNATIVES.md)。

端点从不编译进应用。未配对的设备端点为空，直接显示配对提示。

## 设计文档

全部设计文档在 [Constellation](https://github.com/MRziyi/Constellation) 仓库：

- [GLASS-CLIENT-DESIGN.md](https://github.com/MRziyi/Constellation/blob/main/docs/glass/GLASS-CLIENT-DESIGN.md) —— 客户端设计（v2.1）。
- [GLASS-SDK-REFERENCE.md](https://github.com/MRziyi/Constellation/blob/main/docs/glass/GLASS-SDK-REFERENCE.md) —— 这台硬件在音频、按键、显示、前台服务、相机上的真实行为。**写设备代码前先读这篇。**
- [UI-UX.md](https://github.com/MRziyi/Constellation/blob/main/docs/glass/UI-UX.md) —— 单色绿屏的 HUD 视觉语言。
- [IN-APP-UI-DESIGN.md](https://github.com/MRziyi/Constellation/blob/main/docs/glass/IN-APP-UI-DESIGN.md) —— 应用内界面与扫码配对流程。
- [INTERFACE-CONTRACTS.md](https://github.com/MRziyi/Constellation/blob/main/docs/server/INTERFACE-CONTRACTS.md) —— Glass↔Cortex 的线上协议。
- [PAIRING-AND-AUTH-RECOVERY.md](https://github.com/MRziyi/Constellation/blob/main/docs/glass/PAIRING-AND-AUTH-RECOVERY.md) · [P1.8-MEMORY-ENERGY-PROFILE.md](https://github.com/MRziyi/Constellation/blob/main/docs/glass/P1.8-MEMORY-ENERGY-PROFILE.md)

## 已知局限

- **只认一台设备。** `glass` flavor 针对的是这台硬件的具体脾气——声道掩码、按键广播、AppOps 相机行为。换一副眼镜要真动手改。
- **离不开服务端。** Cortex 不可达时，这个应用就是一个配对界面。
- **UI 文案只有英文。** 没做国际化，非英文语系的资源在构建时被剥掉。
- **不上任何应用商店**，也不是按上架标准做的——它是 sideload 到一副眼镜上的。

## 相关仓库

[Constellation](https://github.com/MRziyi/Constellation)（设计与架构）·
[Constellation-Server](https://github.com/MRziyi/Constellation-Server)（Mac 端运行时）

## 许可

[Apache License 2.0](LICENSE)。
