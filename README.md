# Gemini For HyperOS4

在国行小米 HyperOS 4（Android 15+）上，把系统入口替换为 Google 服务：

- **长按电源键** → 唤起 **Gemini Overlay**（替代超级小爱）
- **长按小白条（手势导航条）** → 唤起 **Circle to Search（圈定即搜）**

基于 **libxposed API 102**（新 LSPosed 接口），支持热重载，方便在真机上迭代调试。
本模块由原 GeminiMi（`com.vince.geminimi`）迁移而来，现以
`io.github.fulu2778.geminimi` 发布。

## 功能

**电源键 → Gemini Overlay**

- 接管 MIUI 的电源键长按路径（`ShortCutActionsUtils#launchVoiceAssistant`
  `long_press_power_key`、`PowerKeyRule#onMiuiLongPress`、PhoneWindowManager 助手入口）
- 自定义 500ms 长按计时触发（不等系统慢判定），长按超过 3 秒仍弹原生电源菜单作兜底
- 唤起成功有震动反馈
- 开机时写入并守护 Google 助手设置：`assistant` / `voice_interaction_service` /
  `voice_recognition_service` 指向 Google，开启屏幕内容/截图辅助，
  `power_button_long_press=5`
- 拦截 MIUI 开机把 `voice_interaction_service` / `assistant` 写回小爱的重写
- 在超级小爱进程内强制 `power_wakeup=false`，防止它重新占用「电源键唤醒」

**小白条 → Circle to Search**

- 桌面默认把「长按小白条」路由成 `ACTION_ASSIST` → 小爱 VoiceService；
  模块在小爱进程拦截该入口，改为调用系统 `ContextualSearchManagerService`
  `startContextualSearch`（CS-only）
- system_server 侧放行该调用（清调用身份 + provider 指向 Google）
- 在 Google App 进程内把设备伪装成 Pixel 9 Pro，使 Google 渲染圈定即搜 UI
- 自带去抖：桌面一次手势会连发两次服务启动，只放行第一次，避免 CS 双开

## 适用范围

- 国行 / 小米 HyperOS 4、Android 15+ 设备（Android 17 已实测）
- 已安装支持 API 102 的 LSPosed，且已为模块勾选作用域
- 已安装并完成初始化的 Google App 与 Gemini

## 安装

1. 安装 APK：`io.github.fulu2778.geminimi`
2. 在 LSPosed 中启用模块 **Gemini For HyperOS4**
3. 作用域勾选：

   | 包名 | 用途 |
   |---|---|
   | `system` | 电源键 / CS system_server 钩子 |
   | `com.miui.voiceassist` | 禁小爱电源键唤醒 + 小白条重定向 |
   | `com.google.android.googlequicksearchbox` | 设备伪装（CS UI 前提） |

4. 重启设备

## 排障

日志统一带 `[GeminiMi]` 前缀：

```text
adb logcat -s "[GeminiMi]"
```

正常启动应看到：

```text
API102 system_server starting
API102 PowerKeyOverlay installed handles=N
CS system-server installed handles=N
hooked VoiceService.onStartCommand      （小爱进程）
CS device spoof applied                 （Google App 进程）
```

关键触发日志：

```text
Gemini overlay launched                       （电源键 → Gemini Overlay）
white-bar ACTION_ASSIST -> Circle to Search  （小白条 → CS）
startContextualSearch bridged                （system_server 放行）
```

常见问题：

- 长按电源键无反应 / 直接关机菜单：确认 `system` 作用域已勾，日志出现
  `PowerKeyOverlay installed`。
- 小白条弹小爱而不是圈搜：确认小爱进程日志出现
  `hooked VoiceService.onStartCommand`，且 `white-bar ACTION_ASSIST -> Circle to Search`
  有触发；system_server 出现 `startContextualSearch bridged`。
- 圈搜无 UI：确认 Google App 进程出现 `CS device spoof applied`。Google App 更新后
  可能收紧设备检测，若失效请在 Issue 附上日志。

反馈问题时请提供：设备型号、HyperOS/Android 版本、Google App/Gemini 版本、上述日志。

## 已知限制

- Gemini Overlay 为**静态快照**样式（与原生行为一致）；长按偶尔会复用上一次的
  会话画面。这与「小白条圈定即搜」的实时取色是 Google 两个不同入口，
  无法在同一次触发里兼得两者外观。
- 小白条圈搜依赖把 Google App 伪装成 Pixel 9 Pro；伪装仅作用于 Google 进程，
  不影响其它进程判断。
- HyperOS 各版本会改动 `PhoneWindowManager` / `ShortCutActionsUtils` 等内部方法名，
  换 ROM 版本后可能需要按新方法名适配。

## 工作原理（简述）

- `Api102PowerKeyHook`（system_server）：接管电源键长按 → 自定义 500ms 计时 →
  走系统 VoiceInteraction 会话唤起 Gemini Overlay；开机守护助手设置、拦 MIUI 重置。
- `CircleToSearchHooker`：在小爱进程把 `ACTION_ASSIST`（小白条）重定向到
  `startContextualSearch`；system_server 对 CS 调用放行并指向 Google；
  Google 进程做设备伪装。全程异常隔离，失败放行原逻辑，不阻塞 system_server。

## 开发

要求 JDK 17 + Android SDK 36；`libxposed` 的 api/interface/service jar 已随仓库放在
`app/libs/libxposed/`。

```text
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

调试产物为：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 参考与致谢

- **MiCTS**（[parallelcc/MiCTS](https://github.com/parallelcc/MiCTS)）：
  「长按小白条 → 圈定即搜」的触发思路与实现参考，包括走系统
  ContextualSearch 服务（`startContextualSearch`）、CSMSHooker /
  NativeLauncherTrigger 等概念；其中面向 Android 17 / HyperOS 4 的适配
  参考了社区用户提交的 PR / fork 版本。
- **GeminiMi**（[SherlockChiang/Gemini-mi](https://github.com/SherlockChiang/Gemini-mi)）：
  本模块的前身（原 `com.vince.geminimi`），电源键 Gemini Overlay 的设计与
  守护助手设置的思路源自该项目。
- **libxposed**（[libxposed/api](https://github.com/libxposed/api)）：
  模块基于 libxposed **API 102** 接口实现（热重载、HookHandle 等）。
- **LSPosed**：模块运行的 Xposed 框架。

## 仓库

- 本模块仓库：[fulu2778/Gemini-For-HyperOS4](https://github.com/fulu2778/Gemini-For-HyperOS4)
- 前身 GeminiMi（`com.vince.geminimi`，SherlockChiang/Gemini-mi）见「参考与致谢」
