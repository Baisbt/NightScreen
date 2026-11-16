package com.skyd.nightscreen.ui.component

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.skyd.nightscreen.R
import com.skyd.nightscreen.appContext
import com.skyd.nightscreen.ext.editor
import com.skyd.nightscreen.ext.sharedPreferences
import com.skyd.nightscreen.log.HealthMonitor
import com.skyd.nightscreen.log.NSLog
import com.skyd.nightscreen.ui.NightScreenReceiver
import com.skyd.nightscreen.ui.component.dialog.dialog
import com.skyd.nightscreen.ui.component.dialog.dialogIsShowing
import com.skyd.nightscreen.ui.component.dialog.getNightScreenDialog
import com.skyd.nightscreen.ui.service.HealthWatchdog
import com.skyd.nightscreen.ui.service.NightScreenForegroundService
import com.skyd.nightscreen.ui.service.NightScreenService
import com.skyd.nightscreen.ui.service.isAccessibilityServiceConnected

private const val TAG = "NightScreenLayer"

fun showDialogAndNightScreen() {
    if (dialogIsShowing || dialogScheduled) return
    if (XXPermissions.isGranted(appContext, Permission.SYSTEM_ALERT_WINDOW)) {
        showNightScreen()
        // 对话框要 inflate 布局、构造 Material 组件和 Slider，起步就是几百毫秒。
        // 如果和遮罩放在同一个消息里同步执行，遮罩那一帧会被一起推迟到对话框建好之后，
        // 用户按了按钮却还要再等一会儿屏幕才变暗。所以先让遮罩出一帧，再弹对话框。
        dialogScheduled = true
        Handler(Looper.getMainLooper()).postDelayed({
            dialogScheduled = false
            if (dialogIsShowing) return@postDelayed
            // 悬浮窗类型必须改成 TYPE_APPLICATION_OVERLAY，否则 Android 8 以上直接抛
            // BadTokenException（permission denied for window type 2003）
            runCatching {
                getNightScreenDialog().apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    } else {
                        window?.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
                    }
                }.show()
            }.onFailure { NSLog.e(TAG, "显示调节亮度对话框失败", it) }
        }, DIALOG_SHOW_DELAY_MS)
    }
}

private var dialogScheduled = false

/** 一帧的时长，留一点余量保证遮罩已经完成绘制。 */
private const val DIALOG_SHOW_DELAY_MS = 32L

fun closeDialog() {
    // 窗口可能已被系统回收，dismiss 会抛 IllegalArgumentException 或
    // BadTokenException，这里不能让它冒到主线程
    runCatching { dialog?.dismiss() }
}

fun showNightScreen() {
    if (showNightScreenLayer) return
    if (XXPermissions.isGranted(appContext, Permission.SYSTEM_ALERT_WINDOW)) {
        NSLog.i(TAG, "开启夜屏遮罩")
        showNightScreenLayer = true
        nightScreenRequested = true
        // 显示前按当前屏幕方向刷新尺寸：横屏启动时若沿用竖屏尺寸，遮罩只能盖住一半屏幕
        layerView.updateSize()
        layerView.visible()
        // 开启不做淡入：颜色动画默认 300ms，作用在整屏压暗上就是实打实的一段延迟
        layerView.updateColor(calculatedColor, animate = false)
        if (!layerView.isAttachedToWindow) {
            // 遮罩画不出来，说明无障碍服务还没连上。
            // 但进程刚启动时服务绑定需要几百毫秒，此刻就判定「坏了」并弹提示属于误报；
            // 而且重写无障碍列表会让所有无障碍服务一起重绑，更是误伤。
            // 所以这里先静默等一会儿：服务自己连上的话，onServiceConnected 会把遮罩挂上。
            NSLog.i(TAG, "遮罩视图尚未挂载，稍后复查无障碍服务")
            scheduleConnectionCheck()
        }
        if (getLowestScreenBrightness() && !getActualLowestScreenBrightness()) {
            applyScreenBrightness(true)
        }
        layerView.keepScreenOn = keepScreenOn
        // 启动前台服务，把进程重要性抬高，避免息屏后被动被回收
        startForegroundService()
        // 更新磁贴和通知栏都要走系统进程（发广播、建通知渠道、提交通知），
        // 同步做完会推迟遮罩的首次绘制，所以挪到遮罩画出来之后再补。
        Handler(Looper.getMainLooper()).post { publishRunningState() }
        HealthMonitor.snapshot("遮罩开启")
    } else {
        NSLog.w(TAG, "缺少悬浮窗权限，无法开启遮罩")
    }
}

/**
 * 启动前台服务。
 *
 * 失败不影响功能：遮罩本身由无障碍服务的窗口承载，前台服务只是提高存活概率，
 * 所以这里吞掉异常并记录，绝不让它变成崩溃。
 */
private fun startForegroundService() {
    runCatching { NightScreenForegroundService.start(appContext) }
        .onSuccess { NSLog.i(TAG, "已启动前台服务") }
        .onFailure { NSLog.w(TAG, "前台服务启动失败（功能不受影响，仅保活变弱）", it) }
}

private fun publishRunningState() {
    appContext.sendBroadcast(
        Intent(NightScreenService.ACTION_ACTIVE_TILE)
            .setPackage(appContext.packageName)
    )
    NightScreenReceiver.sendBroadcast(action = NightScreenReceiver.SHOW_NOTIFICATION_ACTION)
}

private const val CONNECTION_CHECK_DELAY_MS = 2_000L

private var connectionCheckScheduled = false

/**
 * 延后复查无障碍服务是否已经连上。
 *
 * 只有「确实连不上」才动作：能自愈就静默自愈，不能自愈才提示用户。
 * 正常启动（服务只是绑定得慢一点）不会弹任何东西，遮罩会自己出现。
 */
private fun scheduleConnectionCheck() {
    if (connectionCheckScheduled) return
    connectionCheckScheduled = true
    Handler(Looper.getMainLooper()).postDelayed({
        connectionCheckScheduled = false
        if (isAccessibilityServiceConnected()) {
            NSLog.i(TAG, "无障碍服务已连接，无需处理")
            return@postDelayed
        }
        NSLog.w(TAG, "无障碍服务仍未连接，交由看护流程处理")
        // 交给统一的看护逻辑：它会判断权限、节流、自愈并记录日志
        HealthWatchdog.checkAndHeal("遮罩开启复查")
    }, CONNECTION_CHECK_DELAY_MS)
}

fun closeNightScreen() {
    if (!showNightScreenLayer) return
    NSLog.i(TAG, "关闭夜屏遮罩（用户主动）")
    showNightScreenLayer = false
    nightScreenRequested = false
    HealthWatchdog.resetThrottle()
    teardownUi()
    HealthMonitor.snapshot("遮罩关闭")
}

/**
 * 只撤下界面表现，**保留**「期望状态」。
 *
 * 用于「系统主动解绑无障碍服务」的场景（ROM 回收、自愈重写设置、用户换设备等）。
 * 这类情况下用户并没有说要关掉夜屏，如果把期望状态一并清掉，
 * 服务重新连上时就不会再自动恢复遮罩——而且自愈本身就靠重写设置实现，
 * 重写必然触发一次解绑，等于自愈一次就把自己的状态清空了，
 * 表现为「第一次能自愈，之后再也恢复不了」。
 */
fun detachNightScreenUi() {
    if (!showNightScreenLayer) return
    NSLog.i(TAG, "系统解绑无障碍服务，仅撤下遮罩并保留期望状态")
    showNightScreenLayer = false
    teardownUi()
    HealthMonitor.snapshot("服务解绑撤下遮罩")
}

private fun teardownUi() {
    closeDialog()
    layerView.gone()
    applyScreenBrightness(false)
    // 遮罩不在时不占着前台服务，避免留下长期挂着的通知
    runCatching { NightScreenForegroundService.stop(appContext) }
    appContext.sendBroadcast(
        Intent(NightScreenService.ACTION_INACTIVE_TILE)
            .setPackage(appContext.packageName)
    )
    NightScreenReceiver.sendBroadcast(action = NightScreenReceiver.CLOSE_NOTIFICATION_ACTION)
}

var showNightScreenLayer: Boolean = false
    private set

/**
 * 用户「期望」的护眼状态，持久化在 SharedPreferences 里。
 *
 * 和内存里的 [showNightScreenLayer] 不同：进程被系统回收之后内存状态就丢了
 * （熄屏一段时间被 ROM 省电策略杀掉是常态），遮罩再也回不来，用户看到的就是
 * 「用着用着突然没了」。所以把期望状态存下来，等无障碍服务重新连上时自动恢复。
 *
 * 注意：用户在无障碍设置里主动关掉服务时会走 [closeNightScreen]，这里会被置回 false，
 * 因此「主动关闭」不会在下次开启时又自动亮起来。
 */
private var nightScreenRequested: Boolean
    get() = sharedPreferences().getBoolean("nightScreenRequested", false)
    set(value) = sharedPreferences().editor { putBoolean("nightScreenRequested", value) }

/**
 * 无障碍服务重新连上后是否需要恢复遮罩。
 * 进程被杀死时不会走 onDestroy，所以这个值会保持 true；正常关闭服务时会被重置。
 */
fun shouldRestoreNightScreen(): Boolean = nightScreenRequested

var screenAlpha: Float = sharedPreferences().getFloat("screenAlpha", 0.5f)
    set(value) {
        field = value
        layerView.updateColor(calculatedColor)
        sharedPreferences().editor { putFloat("screenAlpha", value) }
    }

var screenColor: Int = sharedPreferences().getInt("screenColor", Color.Black.toArgb())
    set(value) {
        field = value
        layerView.updateColor(calculatedColor)
        sharedPreferences().editor { putInt("screenColor", value) }
    }

val calculatedColor: Int
    get() = (screenColor and 0xFFFFFF) or ((0xFF * screenAlpha).toInt() shl 24)

val alphaRange = 0f..0.9f

var keepScreenOn = sharedPreferences().getBoolean("keepScreenOn", false)
    set(value) {
        field = value
        layerView.keepScreenOn = value
        sharedPreferences().editor { putBoolean("keepScreenOn", value) }
    }

/**
 * 每次打开应用时自动开启遮罩。
 *
 * 和 [nightScreenRequested] 不是一回事：后者是「进程被系统杀掉后要不要把遮罩恢复回来」，
 * 由用户上次的状态自动决定；这个则是用户显式打开的开关，用来省掉每次手动点「启动」。
 */
var autoNightScreenOnLaunch = sharedPreferences().getBoolean("autoNightScreenOnLaunch", false)
    set(value) {
        field = value
        sharedPreferences().editor { putBoolean("autoNightScreenOnLaunch", value) }
    }

/**
 * 读系统设置的兜底封装。
 *
 * Settings.System.getInt 在键不存在时会抛 SettingNotFoundException。下面的值是文件级
 * 属性的初始化代码，一旦抛出就会变成 ExceptionInInitializerError，
 * 之后这个文件里所有函数（包括 showNightScreen）都会连锁失败，表现为整个 App 崩掉，
 * 而堆栈里往往只剩一句 NoClassDefFoundError，极难定位。
 */
private fun systemInt(key: String, default: Int): Int =
    runCatching {
        Settings.System.getInt(appContext.contentResolver, key)
    }.getOrDefault(default)

private fun systemPutInt(key: String, value: Int) {
    runCatching { Settings.System.putInt(appContext.contentResolver, key, value) }
        .onFailure { NSLog.w(TAG, "写入系统设置失败: $key", it) }
}

private var originBrightness: Int = systemInt(Settings.System.SCREEN_BRIGHTNESS, -1)

private var originBrightnessMode: Int = systemInt(
    Settings.System.SCREEN_BRIGHTNESS_MODE,
    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
)

var runAsScheduled = sharedPreferences().getBoolean("runAsScheduled", false)
    set(value) {
        field = value
        sharedPreferences().editor { putBoolean("runAsScheduled", value) }
    }

fun setLowestScreenBrightness(value: Boolean) {
    val old = sharedPreferences().getBoolean("lowestScreenBrightness", false)
    if (value == old) return
    sharedPreferences().editor { putBoolean("lowestScreenBrightness", value) }
}

fun applyScreenBrightness(value: Boolean) {
    if (value && !showNightScreenLayer || !Settings.System.canWrite(appContext)) {
        return
    }
    if (value) {
        val current = systemInt(Settings.System.SCREEN_BRIGHTNESS, -1)
        val currentMode = systemInt(
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        )
        // 只有读到有效值才覆盖原始值，避免把 -1 写回去
        if (current >= 0) originBrightness = current
        originBrightnessMode = currentMode
        NSLog.i(TAG, "降低系统亮度: $current -> 0, mode=$currentMode -> MANUAL")
        systemPutInt(Settings.System.SCREEN_BRIGHTNESS, 0)
        systemPutInt(
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
    } else {
        if (systemInt(Settings.System.SCREEN_BRIGHTNESS, -1) == 0 && originBrightness >= 0) {
            NSLog.i(TAG, "恢复系统亮度: 0 -> $originBrightness")
            systemPutInt(Settings.System.SCREEN_BRIGHTNESS, originBrightness)
        }
        if (systemInt(
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            ) == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        ) {
            systemPutInt(Settings.System.SCREEN_BRIGHTNESS_MODE, originBrightnessMode)
        }
    }
}

fun getLowestScreenBrightness(): Boolean =
    sharedPreferences().getBoolean("lowestScreenBrightness", false)

fun getActualLowestScreenBrightness(): Boolean = getLowestScreenBrightness() &&
        systemInt(Settings.System.SCREEN_BRIGHTNESS, -1) == 0 &&
        systemInt(
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL

val layerView by lazy { LayerView(appContext) }