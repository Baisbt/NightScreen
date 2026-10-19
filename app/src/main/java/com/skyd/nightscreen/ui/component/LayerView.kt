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
import com.skyd.nightscreen.util.screenWidthHeight


class LayerView(context: Context) : View(context) {

    var layoutParams = context.screenWidthHeight.let { (width, height) ->
        WindowManager.LayoutParams(
            width,
            height,
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
        )
    }.apply {
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
     *
     * 尺寸没变时不再调用 updateViewLayout：那是每一次都要跨进程同步等待的
     * WindowManager 调用，而遮罩层在「关闭」状态下窗口是常驻的，
     * 每次开启都白付一次往返开销，正好卡在用户按下按钮的这一刻。
     */
    fun updateSize() {
        val (width, height) = context.screenWidthHeight
        val changed = layoutParams.width != width || layoutParams.height != height
        layoutParams.width = width
        layoutParams.height = height
        if (changed && isAttachedToWindow) {
            // 熄屏 / 服务被销毁的瞬间可能刚好被 DisplayListener 回调到，
            // 此时窗口已经不在 WindowManager 上了，updateViewLayout 会抛
            // IllegalArgumentException: View not attached to window manager
            runCatching {
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .updateViewLayout(this, layoutParams)
            }
        }
    }

    private var colorAnimator: ObjectAnimator? = null

    /**
     * 更新遮罩颜色。
     *
     * [animate] 为 false 时直接赋值。开启遮罩必须走这条路：ObjectAnimator 的默认时长是
     * 300ms，而它作用在「屏幕整体压暗」上会被感知成一个明显的延迟——
     * 用户按下按钮后屏幕还会继续亮 0.3 秒才慢慢变暗。
     * 亮度滑杆那种连续变化仍然需要动画，否则颜色会一格一格地跳。
     */
    fun updateColor(color: Int, animate: Boolean = true) {
        // 旧实现每次都新建动画且从不取消。拖动亮度滑杆时会瞬间堆积几十个动画，
        // 它们互相抢同一个 backgroundColor 属性，表现为颜色抖动、掉帧甚至卡死。
        colorAnimator?.cancel()
        colorAnimator = null
        if (!animate) {
            bgColor = color
            setBackgroundColor(color)
            return
        }
        colorAnimator = ObjectAnimator.ofInt(this, "backgroundColor", bgColor, color).apply {
            setEvaluator(ArgbEvaluator())
            start()
        }
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