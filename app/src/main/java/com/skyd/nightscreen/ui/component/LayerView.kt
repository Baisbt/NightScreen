package com.skyd.nightscreen.ui.component

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.view.isVisible
import com.skyd.nightscreen.util.screenHeight
import com.skyd.nightscreen.util.screenWidth


class LayerView(context: Context) : View(context) {

    var layoutParams = WindowManager.LayoutParams(
        context.screenWidth,
        context.screenHeight,
        0,
        0,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        },
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        fitsSystemWindows = true
    }
        private set

    private var bgColor = Color.TRANSPARENT

    /**
     * 清空窗口 token，必须在每次 addView 之前调用。
     *
     * AccessibilityService 的 WindowManager 会把「当前这次连接」的 token 通过
     * WindowManagerImpl.applyDefaultToken() 写入 LayoutParams.token（写入时机是首次 addView）。
     * 而 layerView 是进程级单例，复用同一个 LayoutParams：
     * 服务被关闭再打开（或崩溃后被系统重启）时，token 仍是上一次连接的 Binder，
     * 此时 addView 会抛 BadTokenException（token ... is not valid）并把服务打成 crashed 状态，
     * 表现为「无障碍权限莫名其妙失效、每次都要重新授权」。
     * 置空后 WindowManager 会自动填入本次连接的新 token。
     */
    fun resetWindowToken() {
        layoutParams.token = null
    }

    /**
     * 按当前屏幕方向重新计算遮罩尺寸，并同步给 WindowManager。
     *
     * 已添加到 WindowManager 的窗口不会随屏幕旋转自动改变宽高，
     * 如果继续沿用竖屏时的尺寸，横屏下遮罩就只能盖住屏幕的一部分，
     * 所以旋转屏幕或显示遮罩前都要刷新一次尺寸。
     */
    fun updateSize() {
        layoutParams.width = context.screenWidth
        layoutParams.height = context.screenHeight
        if (isAttachedToWindow) {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .updateViewLayout(this, layoutParams)
        }
    }

    fun updateColor(color: Int) {
        ObjectAnimator.ofInt(this, "backgroundColor", bgColor, color).apply {
            setEvaluator(ArgbEvaluator())
        }.start()
        bgColor = color
    }

    fun visible() {
        if (isVisible) return
//        windowManager.addView(this, layoutParams)
        visibility = VISIBLE
    }

    fun gone() {
        if (!isVisible) return
        visibility = GONE
//        windowManager.removeView(this)
    }

    init {
        setBackgroundColor(bgColor)
    }
}