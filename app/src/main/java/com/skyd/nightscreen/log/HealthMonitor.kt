package com.skyd.nightscreen.log

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.skyd.nightscreen.ui.component.getActualLowestScreenBrightness
import com.skyd.nightscreen.ui.component.layerView
import com.skyd.nightscreen.ui.component.showNightScreenLayer
import com.skyd.nightscreen.ui.service.isAccessibilityServiceConnected

/**
 * 周期性健康快照 + 内存压力记录。
 *
 * 这类「放几小时后突然失效」的问题，靠复现很难抓：等用户发现时，进程往往已经被杀过、
 * 甚至被重新拉起过，内存状态全丢。所以改成主动留痕——每隔一段时间把关键状态写一行，
 * 出问题时只要看最后几条快照就能知道：
 *
 *   - 断在「connected=false」      → 无障碍服务掉线（进程被杀 / 设置被 ROM 清掉）
 *   - 断在「overlayAttached=false」→ 遮罩窗口没挂上
 *   - 断在「trim=...」之后          → 系统正在施压内存，紧接着被杀
 *   - 完全没有后续快照              → 进程直接被杀（配合 CrashRecorder 的历史退出原因定位）
 *
 * 这些数据不依赖用户描述，ADB 拉下来就能直接给出结论。
 */
object HealthMonitor {

    private const val TAG = "Health"

    /** 遮罩开启时的心跳间隔：这是真正需要保证可用的阶段，采密一点。 */
    private const val INTERVAL_ACTIVE_MS = 60_000L

    /** 遮罩关闭时的心跳间隔。 */
    private const val INTERVAL_IDLE_MS = 5 * 60_000L

    private val handler = Handler(Looper.getMainLooper())

    private var started = false

    private val heartbeat = object : Runnable {
        override fun run() {
            runCatching { snapshot() }
            handler.postDelayed(this, if (showNightScreenLayer) INTERVAL_ACTIVE_MS else INTERVAL_IDLE_MS)
        }
    }

    fun start(context: Context) {
        if (started) return
        started = true
        logEnvironment(context)
        handler.postDelayed(heartbeat, if (showNightScreenLayer) INTERVAL_ACTIVE_MS else INTERVAL_IDLE_MS)
    }

    /** 环境信息只记一次，用于判断是否与特定机型/系统版本相关。 */
    private fun logEnvironment(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val runtime = Runtime.getRuntime()
        NSLog.i(
            TAG,
            "环境: 机型=%s, Android=%s, 总内存=%dMB, 可用=%dMB, 低内存阈值=%dMB, 本应用heap上限=%dMB, " +
                    "是否低内存设备=%s",
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            android.os.Build.VERSION.RELEASE,
            memInfo.totalMem / 1024 / 1024,
            memInfo.availMem / 1024 / 1024,
            memInfo.threshold / 1024 / 1024,
            runtime.maxMemory() / 1024 / 1024,
            am?.isLowRamDevice == true,
        )
    }

    /** 立刻写一条完整状态快照。改配置、开关键功能后调用，便于对照。 */
    fun snapshot(reason: String = "heartbeat") {
        val context = com.skyd.nightscreen.appContext
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val running = ActivityManager.RunningAppProcessInfo()
        runCatching { ActivityManager.getMyMemoryState(running) }

        val runtime = Runtime.getRuntime()
        val usedHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxHeapMb = runtime.maxMemory() / 1024 / 1024

        val pssKb = runCatching {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            info.totalPss
        }.getOrDefault(-1)

        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenOn = power?.isInteractive == true
        val charging = runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.isCharging == true
        }.getOrDefault(false)

        NSLog.i(
            TAG,
            "[%s] 进程=%s 重要性=%s connected=%s accessibilityEnabled=%s 遮罩=%s 窗口挂载=%s " +
                    "亮度已调最低=%s 屏幕亮=%s 充电=%s heap=%d/%dMB pss=%dMB",
            reason,
            ProcessName(),
            importanceName(running.importance),
            isAccessibilityServiceConnected(),
            runCatching {
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
            }.getOrDefault(-1),
            showNightScreenLayer,
            runCatching { layerView.isAttachedToWindow }.getOrDefault(false),
            runCatching { getActualLowestScreenBrightness() }.getOrDefault(false),
            screenOn,
            charging,
            usedHeapMb,
            maxHeapMb,
            if (pssKb >= 0) pssKb / 1024 else -1,
        )
    }

    private fun ProcessName(): String = runCatching {
        com.skyd.nightscreen.appContext.packageName
    }.getOrDefault("unknown")

    private fun importanceName(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND(100)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE(125)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "TOP_SLEEPING(150)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE(200)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE(230)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE(300)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED(400)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY -> "EMPTY(500)"
        else -> importance.toString()
    }
}