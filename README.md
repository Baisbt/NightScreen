<div align="center">
    <div>
        <img src="image/icon.svg" style="height: 220px"/>
    </div>
    <h1>🌙 Night Screen / 夜屏</h1>
    <p>
        <a href="https://github.com/Baisbt/NightScreen" style="text-decoration:none">
            <img src="https://img.shields.io/github/v/release/Baisbt/NightScreen?display_name=release&style=for-the-badge" alt="GitHub release"/>
        </a>
        <a href="https://www.android.com/versions/nougat-7-0" style="text-decoration:none" >
            <img src="https://img.shields.io/badge/Android 7.0+-brightgreen?style=for-the-badge&logo=android&logoColor=white" alt="Android version"/>
        </a>
        <a href="https://github.com/Baisbt/NightScreen/blob/master/LICENSE" style="text-decoration:none" >
            <img src="https://img.shields.io/github/license/Baisbt/NightScreen?style=for-the-badge" alt="GitHub license"/>
        </a>
    </p>
    <p>
        当您在<b>夜间🌙</b>使用手机时，Night Screen 可以帮助您<b>减少屏幕亮度</b>，减少对眼睛的伤害。
    </p>
    <p>
        软件完全采用<b> <a href="https://m3.material.io/">Material You</a> </b>设计风格。除全局 Dialog 外，<b>完全使用 <a href="https://developer.android.com/jetpack/compose">Jetpack Compose</a> </b>开发。
    </p>
</div>



## 💡主要功能

- 支持从系统下拉**任务栏开关启动**
- 支持调整**屏幕颜色**
- 支持屏幕**常亮**
- 支持**自动降低系统亮度**
- 支持运行时通知栏显示**常驻通知**
- **后台稳定保活**：前台服务 + 无障碍服务自动恢复
- **可离线排查的落盘日志**：用 ADB 直接取走分析
- ……

## 📸应用截图

![ic_home_screen](image/screenshot/ic_home_screen.jpg) ![ic_home_screen](image/screenshot/ic_nignt_screen_dialog.jpg)
![ic_settings_screen](image/screenshot/ic_settings_screen.jpg) ![ic_alpha_dialog](image/screenshot/ic_alpha_dialog.jpg)
![ic_color_dialog](image/screenshot/ic_color_dialog.jpg) ![ic_about_screen](image/screenshot/ic_about_screen.jpg)
![ic_tile](image/screenshot/ic_tile.png) ![ic_notification](image/screenshot/ic_notification.png)

## 📖 二次开发说明

本仓库是 [SkyD666/NightScreen](https://github.com/SkyD666/NightScreen) 的**二次开发分支**，在原项目基础上做了以下改动：

### 1. 崩溃与后台失效的修复

原版在部分国产 ROM 上存在「后台放置一段时间后夜屏失效」的问题。经实机排查（vivo V2302A / Android 16），
根因不是代码异常，而是**进程被 ROM 的省电策略（`single-cleaner`）以 `LOW_MEMORY` 杀掉**，
随后系统的 `AccessibilityManagerService` 会把无障碍服务记入 `Crashed services` 并且**不再自动重绑**。

对应修复：

- **前台服务保活**：在遮罩开启期间把进程重要性从 `CACHED(400)` 提升到 `FOREGROUND_SERVICE(125)`。
  服务仅在遮罩开启时存在，关闭即退出，不留常驻通知。
- **无障碍自愈**：掉线后自动重写系统无障碍服务列表触发重绑，无需用户手动去设置里关开。
  需要一次性授予 `WRITE_SECURE_SETTINGS`（可选，不授予则退化为提示）。
- **持久化期望状态**：进程被杀后重新连上时自动恢复遮罩；
  且区分「用户主动关闭」与「系统解绑」，避免自愈时把自己的状态清空。

### 2. 落盘日志

新增可离线分析的日志系统，直接写入外部私有目录：

```bash
adb pull /sdcard/Android/data/com.skyd.nightscreen.debug/files/logs
```

包含崩溃堆栈、**上一次进程的退出原因**（通过 `ApplicationExitInfo`）、以及周期性健康快照。
完整的排查方法见 [docs/LOG_TROUBLESHOOTING.md](docs/LOG_TROUBLESHOOTING.md)。

### 3. 无障碍授权只申请一次

无障碍授权是系统级持久设置，原版无论是否已授权都会跳转系统设置页，
导致每次冷启动都要等待数秒。现在仅在**确实未授权**时引导，已授权时直接等待服务绑定（通常 1~2 秒内完成）。

## 🛠主要技术栈

- Jetpack **Compose**
- **Material You**
- **ViewModel**
- **Hilt**
- Splash Screen
- Navigation
- Coil
- Profile Installer

## 🔧 自行构建

需要 JDK 17 与 Android SDK（compileSdk 34）。

```bash
# 1. 配置 SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. 构建 debug 包
./gradlew :app:assembleGitHubDebug
```

release 构建需要仓库根目录下存在 `key.jks` 并配置 `secret.gradle` 中的签名口令；
`key.jks` 不存在时会自动跳过 release 签名配置，不影响 debug 构建。

产物：`app/build/outputs/apk/GitHub/debug/app_release_github_<version>.apk`

## 📃许可证

本项目遵循 **GNU General Public License v3.0**，详见 [LICENSE](LICENSE)。

原始项目版权归 [SkyD666](https://github.com/SkyD666/NightScreen) 所有；
本二次开发分支同样以 GPLv3 分发。