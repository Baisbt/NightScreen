package com.skyd.nightscreen.ui.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.provider.Settings
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
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 服务可能在同一进程内被系统重新绑定（旋转屏幕、重新绑定、进程存活时被重启等），
        // 而 layerView 是进程级单例，此时它仍挂在 WindowManager 上，直接 addView 会抛：
        //   IllegalStateException: View ... has already been added to the window manager
        // 并导致该服务崩溃、遮罩彻底失效，所以先摘干净再加。
        if (layerView.isAttachedToWindow) {
            windowManager.removeViewImmediate(layerView)
        }

        layerView.updateSize()
        windowManager.addView(layerView, layerView.layoutParams)

        (getSystemService(DISPLAY_SERVICE) as DisplayManager).apply {
            // 重复注册会抛异常，先反注册一次（未注册时是无副作用的）
            unregisterDisplayListener(displayListener)
            registerDisplayListener(displayListener, null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        (getSystemService(DISPLAY_SERVICE) as? DisplayManager)
            ?.unregisterDisplayListener(displayListener)

        closeNightScreen()

        // 服务结束时把遮罩窗口摘掉，避免下次 onServiceConnected 重复添加
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (layerView.isAttachedToWindow) {
            windowManager.removeViewImmediate(layerView)
        }
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
