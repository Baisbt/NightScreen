package com.skyd.nightscreen.ui.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.skyd.nightscreen.BuildConfig
import com.skyd.nightscreen.log.HealthMonitor
import com.skyd.nightscreen.log.NSLog
import com.skyd.nightscreen.ui.component.detachNightScreenUi
import com.skyd.nightscreen.ui.component.layerView
import com.skyd.nightscreen.ui.component.shouldRestoreNightScreen
import com.skyd.nightscreen.ui.component.showNightScreen

class OverlayAccessibilityService : AccessibilityService() {

    /**
     * 屏幕旋转时 Display 会发生变化，此时需要让遮罩层重新贴合屏幕。
     * 不能只依赖 onConfigurationChanged：没有窗口的服务进程不一定能收到配置变化。
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            runCatching { layerView.updateSize() }
                .onFailure { NSLog.w(TAG, "屏幕变化后更新遮罩尺寸失败", it) }
        }
    }

    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转屏幕后窗口尺寸不会自动跟随，需要按新的方向重算，否则会只遮住一半屏幕
        runCatching { layerView.updateSize() }
            .onFailure { NSLog.w(TAG, "配置变化后更新遮罩尺寸失败", it) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        accessibilityServiceConnected = true
        NSLog.i(TAG, "无障碍服务已连接")

        val attached = attachLayer()
        registerDisplayListener()

        // 进程被 ROM 省电策略回收后，无障碍服务会被系统重新拉起，但内存里的护眼状态已经丢了，
        // 遮罩不会自己回来，用户看到的就是「锁屏一段时间后软件突然失效」。
        // 这里按持久化的期望状态恢复；如果是用户主动关掉服务（走 onDestroy），期望状态已被清掉，不会恢复。
        if (attached && shouldRestoreNightScreen()) {
            NSLog.i(TAG, "按持久化状态恢复护眼遮罩")
            runCatching { showNightScreen() }
                .onFailure { NSLog.e(TAG, "恢复护眼遮罩失败", it) }
        }

        // 服务能连上就说明授权是有效的，记下来，避免下次启动又去申请
        com.skyd.nightscreen.ext.AccessibilityPermission.markGranted(this)

        HealthMonitor.snapshot("服务连接")
    }

    private fun registerDisplayListener() {
        runCatching {
            (getSystemService(DISPLAY_SERVICE) as? DisplayManager)?.apply {
                // 重复注册会抛异常，先反注册一次（未注册时是无副作用的）
                unregisterDisplayListener(displayListener)
                registerDisplayListener(displayListener, null)
            }
        }.onFailure { NSLog.w(TAG, "注册屏幕变化监听失败", it) }
    }

    /**
     * 把遮罩窗口挂到 WindowManager 上。
     *
     * 这里有两个必须处理的坑，否则服务会崩溃并被系统标记为 crashed，
     * 表现出来就是「无障碍权限失效 / 每次都要重新授权」：
     *
     * 1. layerView 是进程级单例，服务在「进程存活」的情况下被重新绑定时（用户在设置里关开、
     *    ROM 重启服务、系统手势后重新绑定等），它可能仍挂在 WindowManager 上，
     *    直接 addView 会抛 IllegalStateException: has already been added to the window manager。
     *    所以先摘干净。
     *
     * 2. AccessibilityService 的 WindowManager 会在首次 addView 时把本次连接的 token
     *    写进 LayoutParams.token（WindowManagerImpl.applyDefaultToken）。因为复用的是同一个
     *    LayoutParams 对象，第二次连接时里面还留着上一次连接的、已经失效的 Binder，
     *    于是 addView 抛 BadTokenException: token ... is not valid。
     *    所以每次 addView 之前必须先把 token 清空，让框架填入本次连接的新 token。
     */
    private fun attachLayer(): Boolean {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (layerView.isAttachedToWindow) {
            runCatching { windowManager.removeViewImmediate(layerView) }
                .onFailure { NSLog.w(TAG, "移除旧遮罩窗口失败，继续尝试重新添加", it) }
        }

        // 关键修复：丢弃上一次连接留下的窗口 token
        layerView.resetWindowToken()
        layerView.updateSize()

        return runCatching { windowManager.addView(layerView, layerView.layoutParams) }
            .onSuccess { NSLog.i(TAG, "遮罩窗口挂载成功") }
            .onFailure { NSLog.e(TAG, "添加遮罩窗口失败", it) }
            .isSuccess
    }

    override fun onDestroy() {
        accessibilityServiceConnected = false
        NSLog.w(TAG, "无障碍服务断开（onDestroy）")
        super.onDestroy()

        runCatching {
            (getSystemService(DISPLAY_SERVICE) as? DisplayManager)
                ?.unregisterDisplayListener(displayListener)
        }

        // 先解绑遮罩，再关遮罩：closeNightScreen() 会写 SharedPreferences 并广播，
        // 这些操作产生的看护回调不应该在服务已经断开之后再触发一次自愈。
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (layerView.isAttachedToWindow) {
            runCatching { windowManager.removeViewImmediate(layerView) }
                .onFailure { NSLog.w(TAG, "移除遮罩窗口失败", it) }
        }

        // 服务断开有两种情况，必须区分对待：
        //
        // 1. 系统主动解绑（ROM 回收、自愈重写设置、用户toggle）：只撤下遮罩表现，
        //    保留「期望状态」，等重新连上时自动恢复；
        // 2. 用户主动关闭夜屏：此时期望状态已经被 closeNightScreen() 置空，
        //    这里的 detach 不会造成任何副作用。
        //
        // 老实现无条件调用 closeNightScreen()，会在系统解绑时把期望状态一起清掉，
        // 而自愈恰恰是靠重写设置实现的——等于「自愈一次就把自己废了」。
        detachNightScreenUi()
        HealthMonitor.snapshot("服务断开")
    }

    private companion object {
        const val TAG = "OverlayAccessibilityService"
    }
}

/**
 * 无障碍服务当前是否真的连上了（而不是只在系统设置里勾着）。
 *
 * 系统设置里「已开启」和「实际在跑」是两件事：服务崩溃、被 ROM 冻结时，
 * 设置项依然存在，但 Bound services 是空的，此时画不了任何遮罩。
 * 这个标志只在服务进程内有效，进程被杀后会回到 false，正好可以当作健康探针。
 */
@Volatile
var accessibilityServiceConnected: Boolean = false
    private set

fun isAccessibilityServiceConnected(): Boolean = accessibilityServiceConnected

fun isAccessibilityServiceRunning(context: Context): Boolean {
    val prefString: String? = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )

    val serviceName = OverlayAccessibilityService::class.java.name

    val patterns = arrayOf(
        "${BuildConfig.APPLICATION_ID}/$serviceName",
        serviceName.replaceFirst(
            context.packageName.substringBeforeLast(".debug"),
            "${BuildConfig.APPLICATION_ID}/"
        )
    )
    return prefString != null && patterns.any { prefString.contains(it) }
}