# 夜屏（NightScreen）日志排查指南

> 本文档面向**人类开发者与 AI 助手**：按顺序执行下面的命令，就能定位绝大多数故障。
> 所有命令都假设已连接 ADB，包名默认 `com.skyd.nightscreen.debug`（release 包去掉 `.debug`）。

---

## 0. 一分钟速查

```bash
# ① 拉取日志（最关键，先做这一步）
adb pull /sdcard/Android/data/com.skyd.nightscreen.debug/files/logs

# ② 看无障碍服务是否真的在跑
adb shell dumpsys accessibility | grep -E "Bound services|Enabled services|Crashed services"

# ③ 看进程上次是怎么没的
adb shell dumpsys activity exit-info com.skyd.nightscreen.debug | head -40

# ④ 看崩溃堆栈
adb shell run-as com.skyd.nightscreen.debug ls files/logs/crash/
adb shell logcat -b crash -d
```

三条 `dumpsys accessibility` 输出是判断的核心：

| 输出 | 含义 | 结论 |
|---|---|---|
| `Bound services:{...}` 非空 | 服务真的连上了 | 正常 |
| `Bound services:{}` 为空 | 设置里勾着但没连上 | **遮罩画不出来**，看第 3 节 |
| `Crashed services:{...}` 非空 | 系统标记为崩溃且不会自动重绑 | **必须自愈或人工关开一次** |

---

## 1. 日志在哪、长什么样

### 位置

```
/sdcard/Android/data/<包名>/files/logs/
├── session-20261116-152900-24108.log     # 每次进程启动一个文件
├── session-20261116-144733-15520.log
├── ...
└── crash/
    └── crash-20261116-143322.txt         # 未捕获异常
```

**为什么放外部私有目录**：`adb pull` 不需要 `run-as`，release 包也能直接读；
内部 `files/` 目录在 release 包上 adb 读不到，而 release 恰恰是用户实际在跑的版本。

保留策略：最多 4 个会话文件、单文件上限 512KB，超出后滚动删除，不会占满用户存储。

### 格式

```
MM-DD HH:mm:ss.SSS 级别/作用域: 消息
11-16 15:29:01.913 I/OverlayAccessibilityService: 遮罩窗口挂载成功
```

级别：`I` 正常、`W` 需要留意、`E` 出错了。

### 作用域对照表

| 作用域 | 含义 |
|---|---|
| `App` | 进程启动、自愈调度 |
| `CrashRecorder` | 崩溃记录、**上次进程退出原因** |
| `Health` | 周期性健康快照 |
| `Watchdog` | 掉线检测与自愈流程 |
| `OverlayAccessibilityService` | 无障碍服务连接/断开、窗口挂载 |
| `NightScreenLayer` | 遮罩开关、亮度读写 |
| `NightScreenFgService` | 前台服务生命周期 |
| `A11yPermission` | 无障碍授权状态 |
| `MainActivity` | 界面侧逻辑 |
| `AccessibilityGuard` | 系统设置重写（自愈实现） |

---

## 2. 判断「进程是不是被系统杀了」

这是本应用**最常见**的故障：没有崩溃堆栈、logcat 里什么都没有，
但用户看到的就是「夜屏突然不亮了」。

### 看下一份日志开头的退出记录

每次启动时 `CrashRecorder` 会把历史退出原因写进日志：

```
15:29:00.151 W/CrashRecorder: time=11-16 12:20:12 pid=15419 reason=USER_REQUESTED(...) importance=125 description=[FORCE STOP] ... due to single-cleaner rss=301MB
```

**关键字段是 `description` 和 `reason`**：

| reason / description | 含义 | 处理 |
|---|---|---|
| `LOW_MEMORY` / `single-cleaner` | ROM 省电策略清理后台 | 前台服务已针对性缓解；仍频繁则需在系统设置里给本应用「允许后台运行」 |
| `REASON_CRASH` | Java 崩溃 | 看 `crash/` 目录与 `logcat -b crash` |
| `REASON_CRASH_NATIVE` | native 崩溃 | 同上，看 tombstone |
| `REASON_ANR` | 主线程卡死 | 看 `/data/anr/` |
| `USER_REQUESTED` + `FORCE STOP` | 被用户/管家强制停止 | 正常操作 |
| `UNKNOWN(16)` + `installPackageLI` | 安装新版本导致 | 正常 |
| `REASON_EXIT_SELF` | 进程主动退出 | 通常正常 |

也可以直接查系统记账：

```bash
adb shell dumpsys activity exit-info com.skyd.nightscreen.debug
```

### 配合健康快照定位断点

`Health` 作用域每隔一段时间写一行（遮罩开着时 1 分钟、关着时 5 分钟）：

```
[heartbeat] 进程=... 重要性=FOREGROUND_SERVICE(125) connected=true accessibilityEnabled=1
            遮罩=true 窗口挂载=true 亮度已调最低=false heap=6/256MB pss=81MB
```

判读方法：

- **快照突然中断** → 进程在那一刻被杀（对照退出原因）
- **`connected=false`** → 无障碍服务掉线（见第 3 节）
- **`遮罩=true` 但 `窗口挂载=false`** → 窗口被系统摘掉了
- **`重要性=CACHED(400)`** → 前台服务没起来，进程很危险
- **`重要性=FOREGROUND_SERVICE(125)`** → 保活生效，正常
- `heap`/`pss` 持续上涨 → 疑似内存泄漏

---

## 3. 无障碍服务掉线怎么修

### 判断当前状态

```bash
adb shell dumpsys accessibility | grep -E "Bound services|Enabled services|Binding services|Crashed services"
```

### 三种典型情形

**情形 A：`Enabled services` 有本应用，`Bound services` 为空**

设置里勾着但没连上。常见于服务崩溃后被记入 `Crashed services`。

```bash
# 若有 WRITE_SECURE_SETTINGS，应用会自己修复，观察日志：
grep "Watchdog" <日志文件>
# 正常自愈序列：
#   [启动自检] 无障碍服务未连接，开始自愈
#   [启动自检] 自愈验证: connected=true
```

**情形 B：`Enabled services` 为空，`accessibility_enabled=0`**

被 force-stop 或清理软件把条目整个清掉了。

```bash
adb shell settings get secure enabled_accessibility_services   # null 表示被清空
```

应用启动时会自愈；若没装 `WRITE_SECURE_SETTINGS` 则只能人工恢复：

```bash
adb shell settings put secure enabled_accessibility_services \
  com.skyd.nightscreen.debug/com.skyd.nightscreen.ui.service.OverlayAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

**情形 C：`Crashed services` 非空**

自愈的实现是重写服务列表（先移除再写回），这会清掉 crashed 标记并触发重绑。
如果自愈反复失败，日志里会连续出现：

```
E/Watchdog: 自愈失败，无障碍服务仍未连接（可能需要用户手动关开一次）
```

此时让用户在系统设置里关开一次无障碍服务即可。

### 自愈需要的一次性授权

```bash
adb shell pm grant com.skyd.nightscreen.debug android.permission.WRITE_SECURE_SETTINGS
```

没有这个权限时，应用**不会**反复弹窗骚扰，只在日志里留一条 `W/Watchdog`。

---

## 4. 遮罩不显示

### 先确认窗口是否真的在 WindowManager 上

```bash
adb shell dumpsys window windows | grep -A 25 "type=2032"
```

`2032` 就是 `TYPE_ACCESSIBILITY_OVERLAY`。正常应该有：

```
mHasSurface=true ... isOnScreen=true ... isVisible=true
Surface: shown=true  mDrawState=HAS_DRAWN
```

逐项核对：

| 异常 | 原因 |
|---|---|
| 完全没有 `type=2032` 的窗口 | 无障碍服务没连上 → 看第 3 节 |
| `isVisible=false` / `mViewVisibility=0x8` | 调用了 `gone()`（遮罩被关闭） |
| `mObscured=true` | 被其他悬浮窗盖住 |
| `mHasSurface=false` | 窗口刚添加，还没完成绘制 |

### 再看日志

```
grep "NightScreenLayer" <日志文件>
```

- `缺少悬浮窗权限，无法开启遮罩` → 需要授予 `SYSTEM_ALERT_WINDOW`
  ```bash
  adb shell appops set com.skyd.nightscreen.debug SYSTEM_ALERT_WINDOW allow
  ```
- `遮罩视图尚未挂载` → 服务还没绑定好，属正常等待；2 秒后会复查
- `添加遮罩窗口失败` + `BadTokenException` → 窗口 token 失效，服务重绑后可自愈

### 亮度调不下去

设置「最低屏幕亮度」需要 `WRITE_SETTINGS`：

```bash
adb shell appops get com.skyd.nightscreen.debug android:write_settings
adb shell appops set com.skyd.nightscreen.debug android:write_settings allow
```

日志对应：`NightScreenLayer: 降低系统亮度: xxx -> 0, mode=x -> MANUAL`

---

## 5. 崩溃后看什么

### 第一顺位：应用自己的 crash 文件

```bash
adb shell run-as com.skyd.nightscreen.debug ls files/logs/crash/
adb shell run-as com.skyd.nightscreen.debug cat files/logs/crash/crash-20261116-143322.txt
```

崩溃前那段日志会被一并刷盘（`flushRingSync`），因为**能定位问题的往往是崩溃前几十行操作**。

### 第二顺位：logcat

```bash
adb logcat -b crash -d | grep -i nightscreen
adb logcat -d | grep -E "Ns/|nightscreen" | tail -100
```

应用所有日志同时输出到 logcat（tag 为 `Ns/<作用域>`），所以即使文件写失败也能抓到。

### 第三顺位：ANR

```bash
adb shell ls -la /data/anr/
adb shell dumpsys activity exit-info com.skyd.nightscreen.debug | grep ANR
```

---

## 6. 排查「打开后要等很久」类问题

未授权时才会跳系统设置页；已授权时不应有任何等待。日志可以验证：

```bash
grep -E "A11yPermission|MainActivity" <日志文件>
```

- 正常（已授权）：不应出现 `引导用户开启无障碍服务`
- 异常：出现 `无障碍权限不可用` → 条件不满足，需要重新授权

时间线判读：

```
15:29:00.224 W/MainActivity: 自动开启被跳过：无障碍权限不可用   ← 尚未授权
15:29:01.367 W/Watchdog: [启动自检] 无障碍服务未连接，开始自愈
15:29:01.913 I/OverlayAccessibilityService: 遮罩窗口挂载成功      ← 1.7 秒内完成
```

冷启动到遮罩可用应在 **2 秒内**；超过 3 秒说明服务绑定慢，查系统侧 logcat 是否有 `AccessibilityManagerService` 相关丢包。

---

## 7. 前台服务与保活

```bash
# 是否在前台
adb shell dumpsys activity services com.skyd.nightscreen.debug | grep -E "NightScreenForegroundService|isForeground"

# 通知是否在
adb shell dumpsys notification --noredact | grep NightScreenKeepAlive

# 进程重要性
adb shell dumpsys activity processes | grep -A 2 "com.skyd.nightscreen.debug" | grep "Proc #"
```

`Proc # 0: vis F/A/FGS` 表示进程因前台服务被当作 visible，不会被当作可回收缓存进程。

前台服务**只在遮罩开启期间存在**，关闭遮罩立即 `stopSelf`——如果发现遮罩已关但通知还在，
说明关闭链路有问题，查日志里是否有 `前台服务销毁`。

---

## 8. 环境变量速查

```bash
PKG=com.skyd.nightscreen.debug
LOGDIR=/sdcard/Android/data/$PKG/files/logs
```

| 需求 | 命令 |
|---|---|
| 拉全部日志 | `adb pull $LOGDIR` |
| 只看最新 | `adb shell cat $(adb shell ls -t $LOGDIR/*.log \| head -1)` |
| 实时跟随 | `adb shell tail -f $(adb shell ls -t $LOGDIR/*.log \| head -1)` |
| 清日志重来 | `adb shell rm -rf $LOGDIR/*` |
| 本应用单独重启 | `adb shell am force-stop $PKG`（**只影响本应用**） |
| 冷启动测速 | `adb shell am start -W -n $PKG/com.skyd.nightscreen.ui.activity.MainActivity` |

---

## 9. 注意事项（给 AI 助手）

1. **不要 `adb shell pm clear`**：会清掉用户所有设置。
2. **不要 force-stop 其他应用**，只操作本包名。
3. **`uiautomator dump` 有副作用**：每次 dump 会新起 `app_process` 以无障碍客户端身份连接，
   **强制系统重绑无障碍服务**。用它抓界面时会在日志里制造假的「解绑→重连」，
   排查掉线问题时应避免使用，或明确排除该干扰。
4. vivo 机型装 APK 默认有「智能安装校验」，`adb install` 可能返回
   `INSTALL_FAILED_ABORTED: User rejected permissions`。临时关闭需修改：
   ```bash
   adb shell settings put global vivo_update_intelligent_installation 0
   adb install -r app.apk
   adb shell settings put global vivo_update_intelligent_installation 1   # 记得恢复
   ```
5. 设备上可能同时存在多个 `adb.exe`（如 vivo 套件自带版本），版本不一致会互相 kill server，
   统一使用 `%LOCALAPPDATA%` 或 Android SDK 里的那一份。
6. debug 与 release 包名不同（`.debug` 后缀），所有命令检查包名。