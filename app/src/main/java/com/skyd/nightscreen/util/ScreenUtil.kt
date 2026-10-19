package com.skyd.nightscreen.util

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager

/**
 * 判断是否横屏，用于 UI 布局。
 *
 * 注意：这里读的是 Context 自身的配置方向，适合 Activity 等有窗口的 Context；
 * 但如果要计算「物理屏幕」的尺寸（例如全屏遮罩层），请使用 [displayIsLand]。
 */
val Context.screenIsLand: Boolean
    get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * 屏幕当前是否处于横向，以 Display 的真实旋转为准。
 *
 * 不能用 [screenIsLand] 代替：只有服务、没有窗口的进程（例如无障碍服务进程），
 * 其 resources.configuration 不一定会跟随设备旋转更新，会一直报告竖屏，
 * 结果就是横屏时把遮罩层按竖屏尺寸摆放，只能盖住屏幕的一部分。
 */
private val Context.displayIsLand: Boolean
    get() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        return when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> true
            else -> false
        }
    }

/**
 * 屏幕物理尺寸的「长边 / 短边」，与当前方向无关。
 * 不同 Context 拿到的宽高方向可能不同（自然方向 / 当前方向），
 * 统一归一化成长短边后再按当前方向拼装，保证结果稳定。
 */
private fun Context.displayLongAndShortSide(): Pair<Int, Int> {
    val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val dm = DisplayMetrics()
    @Suppress("DEPRECATION")
    windowManager.defaultDisplay.getRealMetrics(dm)
    return maxOf(dm.widthPixels, dm.heightPixels) to minOf(dm.widthPixels, dm.heightPixels)
}

/**
 * 当前方向下的整屏宽高，一次性算出来。
 *
 * 遮罩层每次显示/旋转都要同时拿到宽和高，如果分别读 [screenWidth] 和 [screenHeight]，
 * 会重复做两遍 getRealMetrics（跨进程）和四遍 getIdentifier（按名字查系统资源，很慢）。
 * 在启用遮罩这种对延迟敏感的时刻，这些重复查询是白花的。
 */
val Context.screenWidthHeight: Pair<Int, Int>
    get() {
        val (longSide, shortSide) = displayLongAndShortSide()
        val statusBar = statusBarHeight
        val navigationBar = navigationBarHeight
        return if (displayIsLand) {
            (longSide + navigationBar) to (shortSide + statusBar)
        } else {
            shortSide to (longSide + statusBar + navigationBar)
        }
    }

val Context.screenHeight: Int
    get() = screenWidthHeight.second

val Context.screenWidth: Int
    get() = screenWidthHeight.first

val Context.statusBarHeight: Int
    get() {
        val resourceId: Int = resources
            .getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            return resources.getDimensionPixelSize(resourceId)
        }
        return 0
    }

val Context.navigationBarHeight: Int
    get() {
        val resourceId: Int = resources
            .getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            return resources.getDimensionPixelSize(resourceId)
        }
        return 0
    }
