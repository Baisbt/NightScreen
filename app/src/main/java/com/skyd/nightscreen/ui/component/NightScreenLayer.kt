package com.skyd.nightscreen.ui.component

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.skyd.nightscreen.R
import com.skyd.nightscreen.appContext
import com.skyd.nightscreen.ext.editor
import com.skyd.nightscreen.ext.sharedPreferences
import com.skyd.nightscreen.ui.NightScreenReceiver
import com.skyd.nightscreen.ui.component.dialog.dialog
import com.skyd.nightscreen.ui.component.dialog.dialogIsShowing
import com.skyd.nightscreen.ui.component.dialog.getNightScreenDialog
import com.skyd.nightscreen.ui.service.NightScreenService
import com.skyd.nightscreen.ui.service.isAccessibilityServiceConnected

private const val TAG = "NightScreenLayer"

fun showDialogAndNightScreen() {
    if (dialogIsShowing) return
    if (XXPermissions.isGranted(appContext, Permission.SYSTEM_ALERT_WINDOW)) {
        showNightScreen()
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
        }.onFailure { Log.e(TAG, "显示调节亮度对话框失败", it) }
    }
}

fun closeDialog() {
    // 窗口可能已被系统回收，dismiss 会抛 IllegalArgumentException 或
    // BadTokenException，这里不能让它冒到主线程
    runCatching { dialog?.dismiss() }
}

fun showNightScreen() {
    if (showNightScreenLayer) return
    if (XXPermissions.isGranted(appContext, Permission.SYSTEM_ALERT_WINDOW)) {
        showNightScreenLayer = true
        nightScreenRequested = true
        // 显示前按当前屏幕方向刷新尺寸：横屏启动时若沿用竖屏尺寸，遮罩只能盖住一半屏幕
        layerView.updateSize()
        layerView.visible()
        if (!layerView.isAttachedToWindow) {
            // 无障碍服务没连上时遮罩根本画不出来，只调 visibility 是静默无效的。
            // 以前这种情况用户只能看到「点了没反应」，完全无法判断原因，所以这里明确提示。
            Log.w(TAG, "遮罩视图未挂到窗口上")
            // 只在服务确实没连上时才提示。服务连着、窗口只是还没挂好属于瞬时状态
            // （例如安装、重启进程的瞬间），弹提示会变成误报。
            if (!isAccessibilityServiceConnected()) {
                appContext.getString(R.string.accessibility_service_not_running).showToast()
            }
        }
        if (getLowestScreenBrightness() && !getActualLowestScreenBrightness()) {
            applyScreenBrightness(true)
        }
        layerView.keepScreenOn = keepScreenOn
        layerView.updateColor(calculatedColor)
        appContext.sendBroadcast(
            Intent(NightScreenService.ACTION_ACTIVE_TILE)
                .setPackage(appContext.packageName)
        )
        NightScreenReceiver.sendBroadcast(action = NightScreenReceiver.SHOW_NOTIFICATION_ACTION)
    }
}

fun closeNightScreen() {
    if (!showNightScreenLayer) return
    showNightScreenLayer = false
    nightScreenRequested = false
    closeDialog()
    layerView.gone()
    applyScreenBrightness(false)
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
        .onFailure { Log.w(TAG, "写入系统设置失败: $key", it) }
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
        systemPutInt(Settings.System.SCREEN_BRIGHTNESS, 0)
        systemPutInt(
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
    } else {
        if (systemInt(Settings.System.SCREEN_BRIGHTNESS, -1) == 0 && originBrightness >= 0) {
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
