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