<div align="center">
    <div>
        <img src="image/icon.svg" style="height: 220px"/>
    </div>
    <h1>🌙 夜屏 Night Screen</h1>
    <p>夜间护眼遮罩 · 二次开发分支</p>
    <p>
        <a href="https://github.com/Baisbt/NightScreen" style="text-decoration:none">
            <img src="https://img.shields.io/badge/GitHub-Baisbt%2FNightScreen-181717?style=for-the-badge&logo=github&logoColor=white" alt="GitHub"/>
        </a>
        <a href="https://www.android.com/versions/nougat-7-0" style="text-decoration:none">
            <img src="https://img.shields.io/badge/Android-7.0%2B-brightgreen?style=for-the-badge&logo=android&logoColor=white" alt="Android version"/>
        </a>
        <a href="https://github.com/Baisbt/NightScreen/blob/master/LICENSE" style="text-decoration:none">
            <img src="https://img.shields.io/github/license/Baisbt/NightScreen?style=for-the-badge" alt="License"/>
        </a>
        <a href="https://github.com/Baisbt/NightScreen/commits/master" style="text-decoration:none">
            <img src="https://img.shields.io/github/last-commit/Baisbt/NightScreen?style=for-the-badge" alt="Last commit"/>
        </a>
    </p>
    <p>
        夜间使用手机时，把整块屏幕压暗到舒适的亮度，减少强光对眼睛的刺激。
    </p>
</div>

---

## 📌 关于这个仓库

这是 [SkyD666/NightScreen](https://github.com/SkyD666/NightScreen) 的**二次开发分支**。

上游项目的界面和整体设计很棒，但在部分国产 ROM 上存在两个影响日常使用的问题，
本分支针对性地做了修复，并补充了便于远程排查的诊断能力。

如果你只是想用这个应用，直接装下面的 APK 即可；如果你关心改了什么、为什么改，
可以看 [改动说明](#-相对上游的主要改动)。

## ✨ 功能

| 功能 | 说明 |
|---|---|
| 🌙 **护眼遮罩** | 整屏压暗，颜色与透明度可调 |
| 🎨 **屏幕颜色** | 自选叠加色，支持任意色值 |
| 💡 **自动降低亮度** | 开启时把系统亮度降到最低，关闭时还原 |
| 🔆 **屏幕常亮** | 护眼期间保持屏幕不熄灭 |
| ⚡ **快捷磁贴** | 从下拉通知栏一键开关 |
| 🔔 **常驻通知** | 运行时显示状态，可一键关闭 |
| 🛡️ **后台稳定** | 前台服务保活 + 无障碍服务掉线自动恢复 |
| 📋 **可离线排查的日志** | 用 ADB 直接取走日志分析 |

## 📸 界面截图

<div align="center">

| 主页 | 调节亮度 | 设置 |
|:---:|:---:|:---:|
| ![主页](image/screenshot/ic_home_screen.jpg) | ![调节亮度](image/screenshot/ic_nignt_screen_dialog.jpg) | ![设置](image/screenshot/ic_settings_screen.jpg) |

| 透明度 | 颜色 | 关于 |
|:---:|:---:|:---:|
| ![透明度](image/screenshot/ic_alpha_dialog.jpg) | ![颜色](image/screenshot/ic_color_dialog.jpg) | ![关于](image/screenshot/ic_about_screen.jpg) |

| 快捷磁贴 | 常驻通知 |
|:---:|:---:|
| ![磁贴](image/screenshot/ic_tile.png) | ![通知](image/screenshot/ic_notification.png) |

</div>

## 🔧 相对上游的主要改动

### 1. 修复「后台放久了夜屏失效」

**这是上游最影响日常使用的问题。** 实机排查（vivo V2302A / Android 16）确认，
根因并不是代码崩溃，而是：

> 进程被 ROM 的省电策略（`single-cleaner`）以 `LOW_MEMORY` 为由杀掉，
> 随后系统的 `AccessibilityManagerService` 会把无障碍服务记入 `Crashed services`，
> 并且**此后不再自动重绑**。

进程一死遮罩就消失，而且服务再也不会自己回来，用户只能跑去系统设置里关开一次。

修复方式：

- **前台服务保活**：护眼期间把进程重要性从 `CACHED(400)` 抬到 `FOREGROUND_SERVICE(125)`，
  显著降低被回收的概率。服务只在遮罩开启时存在，关闭即退出，**不留常驻通知**。
- **无障碍服务自愈**：检测到掉线后重写系统无障碍服务列表触发重绑，无需人工干预。
- **持久化期望状态**：进程被杀后重新连上时自动恢复遮罩；
  并严格区分「用户主动关闭」与「系统解绑」，避免自愈时把状态一起清掉。

> 自愈需要一次性授予 `WRITE_SECURE_SETTINGS`（见下方安装说明）。
> 不授予也能正常使用，只是失效时需要手动恢复。

### 2. 无障碍权限只申请一次

无障碍授权是系统级、持久化的设置，正常情况下一台手机只需要授权一次。
上游无论是否已授权都会跳转系统设置页，导致每次冷启动都要等待数秒。

现在仅在**确实未授权**时引导用户，已授权时直接等待服务绑定完成
（实测冷启动到遮罩可用约 1.7 秒）。

### 3. 落盘日志

新增可离线分析的日志系统，直接写入外部私有目录：

```bash
adb pull /sdcard/Android/data/com.skyd.nightscreen.debug/files/logs
```

内容包括崩溃堆栈、**上一次进程的退出原因**、以及周期性健康快照。
选外部目录是为了让 `adb pull` 不需要 `run-as`，release 包也能直接读。

详细的排查方法见 **[docs/LOG_TROUBLESHOOTING.md](docs/LOG_TROUBLESHOOTING.md)**。

### 4. 其它

- 移除了上游的赞助入口与社群链接，关于页只保留本项目仓库与开源许可
- 修复构建配置中签名参数解析错误，使 debug 构建可在任意环境完成

## 📥 安装

1. 从 [Releases](https://github.com/Baisbt/NightScreen/releases) 下载 APK 安装；
   或自行构建（见下方）。
2. 打开 App，按提示开启两项权限：
   - **显示在其他应用上层**（悬浮窗）
   - **无障碍服务**（夜屏）

### 可选：授予自愈权限

装了下面这条命令后，无障碍服务掉线可以被自动修复，不用再手动去设置里关开：

```bash
adb shell pm grant com.skyd.nightscreen android.permission.WRITE_SECURE_SETTINGS
```

> 调试包请把包名换成 `com.skyd.nightscreen.debug`。
> 不授予也完全能用，只是少了自动恢复这一层保障。

### 国产 ROM 建议

本应用依赖无障碍服务常驻，部分 ROM 的省电策略较激进。建议额外做两件事：

- 在系统设置中允许本应用**自启动**与**后台运行**
- 把电池优化设为**不限制 / 无限制**

## 🛠 自行构建

需要 **JDK 17** 与 Android SDK（`compileSdk 34`、`build-tools 34.0.0`）。

```bash
# 1. 指定 SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. 构建 debug 包
./gradlew :app:assembleGitHubDebug
```

产物：`app/build/outputs/apk/GitHub/debug/app_release_github_<version>.apk`

构建 release 包需要仓库根目录下存在 `key.jks`，并在 `secret.gradle` 中配置签名口令；
`key.jks` 不存在时会自动跳过 release 签名配置，不影响 debug 构建。

## 📦 技术栈

- **Jetpack Compose** — 除全局 Dialog 外完全使用 Compose 开发
- **Material You** — 完整的 M3 设计风格
- **Hilt** — 依赖注入
- **Navigation** — 页面导航
- **Coil** — 图片加载
- **Profile Installer** — 基线配置
- **XXPermissions** — 权限申请

最低支持 Android 7.0（API 24）。

## 🐛 遇到问题

如果遇到夜屏失效、遮罩不显示等问题，**先取日志**，多数情况能直接看出原因：

```bash
adb pull /sdcard/Android/data/com.skyd.nightscreen.debug/files/logs
```

判读方法见 [docs/LOG_TROUBLESHOOTING.md](docs/LOG_TROUBLESHOOTING.md)。
需要反馈时，请附上日志文件与机型/系统版本。

## 📃 许可证

本项目遵循 **GNU General Public License v3.0**，详见 [LICENSE](LICENSE)。

原始项目 [SkyD666/NightScreen](https://github.com/SkyD666/NightScreen) 版权归原作者所有。
按照 GPLv3 的要求，本二次开发分支同样以 GPLv3 分发，并保留原作者署名。