package com.skyd.nightscreen.ui.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.skyd.nightscreen.BuildConfig
import com.skyd.nightscreen.ui.component.closeNightScreen
import com.skyd.nightscreen.ui.component.layerView

class OverlayAccessibilityService : AccessibilityService() {

    /**
     * 屏幕旋转时 Display 会发生变化，此时需要让遮罩层重新贴合屏幕。
     * 不能只依赖 onConfigurationChanged：没有窗口的服务进程不一定能收到配置变化。
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            layerView.updateSize()
        }
    }

    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转屏幕后窗口尺寸不会自动跟随，需要按新的方向重算，否则会只遮住一半屏幕
        layerView.updateSize()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        attachLayer()
        registerDisplayListener()
    }

    private fun registerDisplayListener() {
        (getSystemService(DISPLAY_SERVICE) as? DisplayManager)?.apply {
            // 重复注册会抛异常，先反注册一次（未注册时是无副作用的）
            unregisterDisplayListener(displayListener)
            registerDisplayListener(displayListener, null)
        }
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
    private fun attachLayer() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (layerView.isAttachedToWindow) {
            runCatching { windowManager.removeViewImmediate(layerView) }
                .onFailure { Log.w(TAG, "移除旧遮罩窗口失败，继续尝试重新添加", it) }
        }

        // 关键修复：丢弃上一次连接留下的窗口 token
        layerView.resetWindowToken()
        layerView.updateSize()

        runCatching { windowManager.addView(layerView, layerView.layoutParams) }
            .onFailure { Log.e(TAG, "添加遮罩窗口失败", it) }
    }

    override fun onDestroy() {
        super.onDestroy()

        (getSystemService(DISPLAY_SERVICE) as? DisplayManager)
            ?.unregisterDisplayListener(displayListener)

        closeNightScreen()

        // 服务结束时把遮罩窗口摘掉，避免下次 onServiceConnected 重复添加
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (layerView.isAttachedToWindow) {
            runCatching { windowManager.removeViewImmediate(layerView) }
                .onFailure { Log.w(TAG, "移除遮罩窗口失败", it) }
        }
    }

    private companion object {
        const val TAG = "OverlayAccessibilityService"
    }
}

fun isAccessibilityServiceRunning(context: Context): Boolean {
    val prefString: String? = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )

    val raysAccessibilityServiceName = OverlayAccessibilityService::class.java.name

    val patterns = arrayOf(
        "${BuildConfig.APPLICATION_ID}/${raysAccessibilityServiceName}",
        raysAccessibilityServiceName.replaceFirst(
            context.packageName.substringBeforeLast(".debug"),
            "${BuildConfig.APPLICATION_ID}/"
        )
    )
    return prefString != null && patterns.any { prefString.contains(it) }
}
