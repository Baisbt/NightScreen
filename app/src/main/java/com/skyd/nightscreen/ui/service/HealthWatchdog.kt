package com.skyd.nightscreen.ui.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.skyd.nightscreen.appContext
import com.skyd.nightscreen.log.HealthMonitor
import com.skyd.nightscreen.log.NSLog
import com.skyd.nightscreen.ui.component.layerView
import com.skyd.nightscreen.ui.component.shouldRestoreNightScreen
import com.skyd.nightscreen.ui.component.showNightScreen
import com.skyd.nightscreen.ui.component.showNightScreenLayer

/**
 * 无障碍服务的「看护 + 自愈」调度中心。
 *
 * 集中处理一个现实：国产 ROM 会在后台把本进程杀掉，而系统此后不再自动重绑无障碍服务，
 * 于是遮罩永久消失。这里把「掉线检测 → 修复 → 验证」合成一条可重复执行的流程，
 * 并且把每一步都写进日志，便于事后用 ADB 复盘。
 *
 * 自愈分两级：
 *   1. 有 WRITE_SECURE_SETTINGS（可通过 adb 一次性授予）→ 直接重写系统无障碍服务列表，
 *      这是最可靠的，系统会立刻重绑；
 *   2. 没有该权限 → 仅记录日志并提示用户，绝不反复弹窗骚扰。
 */
object HealthWatchdog {

    private const val TAG = "Watchdog"

    /** 两次自愈之间的最小间隔，避免短时间内反复改写系统设置。 */
    private const val MIN_HEAL_INTERVAL_MS = 15_000L

    /** 自愈后等系统重绑的时间，然后再验证。 */
    private const val VERIFY_DELAY_MS = 3_000L

    private val handler = Handler(Looper.getMainLooper())

    private var lastHealAt = 0L
    private var verifying = false

    /**
     * 自愈进行中标记。
     *
     * 自愈的实现是「重写系统无障碍服务列表」，这个动作**必然**让系统解绑再重绑，
     * 期间 `onServiceConnected`/`onDestroy` 会连着触发、`isAccessibilityServiceConnected()`
     * 也会短暂变成 false。如果不拦住这段窗口，看护会把「自己刚造成的解绑」
     * 当成「又掉线了」再自愈一次，形成无限震荡。
     */
    @Volatile
    private var healing = false

    /**
     * 检查一次健康状态，必要时自愈。
     *
     * @param source 触发来源，写进日志用于区分「前台服务看护 / 启动自检 / 用户点启动」。
     */
    fun checkAndHeal(source: String) {
        val context = appContext

        // 只在用户「真的需要遮罩挂着」时才去修复。
        //
        // 这里刻意用 shouldRestoreNightScreen()（持久化的期望状态）而不是
        // AccessibilityGuard.isServiceEnabled()（系统里是否勾着）：
        // 遮罩关掉之后，用户在系统设置里的开关通常还勾着，但此时去自愈毫无意义——
        // 而自愈的实现是重写系统无障碍服务列表，必然造成「解绑 → 重绑」，
        // 用户会在没开夜屏的情况下看到服务莫名其妙重启一次。
        if (!shouldRestoreNightScreen()) {
            NSLog.i(TAG, "[%s] 遮罩未开启，无需看护", source)
            return
        }

        if (isAccessibilityServiceConnected()) {
            // 已连接但遮罩窗口没挂上：说明窗口在服务存活期间被系统摘掉了
            val attached = runCatching { layerView.isAttachedToWindow }.getOrDefault(false)
            if (showNightScreenLayer && !attached) {
                NSLog.w(TAG, "[%s] 服务已连接但遮罩窗口未挂载，重新挂载", source)
                runCatching { showNightScreen() }
                    .onFailure { NSLog.e(TAG, "重新挂载遮罩失败", it) }
            }
            return
        }

        // 自愈本身会造成短暂解绑，这期间的「未连接」不算故障
        if (healing) {
            NSLog.i(TAG, "[%s] 自愈进行中，忽略本次未连接", source)
            return
        }

        NSLog.w(TAG, "[%s] 无障碍服务未连接，开始自愈", source)
        HealthMonitor.snapshot("自愈前:$source")

        if (!AccessibilityGuard.canRestore()) {
            NSLog.w(
                TAG,
                "没有 WRITE_SECURE_SETTINGS，无法自动恢复；可用 adb 一次性授予: " +
                        "adb shell pm grant %s android.permission.WRITE_SECURE_SETTINGS",
                context.packageName
            )
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastHealAt < MIN_HEAL_INTERVAL_MS) {
            NSLog.i(TAG, "距上次自愈不足 %dms，本次跳过", MIN_HEAL_INTERVAL_MS)
            return
        }
        lastHealAt = now
        healing = true

        AccessibilityGuard.restore { verify(source) }
    }

    private fun verify(source: String) {
        if (verifying) return
        verifying = true
        handler.postDelayed({
            verifying = false
            healing = false
            val connected = isAccessibilityServiceConnected()
            NSLog.i(TAG, "[%s] 自愈验证: connected=%s", source, connected)
            if (connected) {
                if (shouldRestoreNightScreen() && !showNightScreenLayer) {
                    NSLog.i(TAG, "自愈成功，恢复遮罩")
                    runCatching { showNightScreen() }
                        .onFailure { NSLog.e(TAG, "恢复遮罩失败", it) }
                }
            } else {
                NSLog.e(TAG, "自愈失败，无障碍服务仍未连接（可能需要用户手动关开一次）")
            }
            HealthMonitor.snapshot("自愈后:$source")
        }, VERIFY_DELAY_MS)
    }

    /**
     * 清掉节流状态。
     *
     * **不要**在「服务被系统解绑」时调用：自愈本身就会造成解绑，
     * 清零节流会让紧随其后的看护立刻再自愈一次，形成震荡。
     * 只在用户明确关闭夜屏时重置。
     */
    fun resetThrottle() {
        lastHealAt = 0L
        healing = false
    }
}