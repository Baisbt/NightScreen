package com.skyd.nightscreen

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.skyd.nightscreen.ui.component.shouldRestoreNightScreen
import com.skyd.nightscreen.ui.service.AccessibilityGuard
import com.skyd.nightscreen.ui.service.isAccessibilityServiceConnected
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = this
        installCrashRecorder()
        scheduleAccessibilitySelfHeal()
    }

    /**
     * 进程启动几秒后检查一次无障碍服务的「真实」状态。
     *
     * 「系统设置里勾着、实际根本没连上」是本应用最常见的故障：ROM 清理后台杀进程之后，
     * 系统的 AccessibilityManagerService 会把服务记进 Crashed services 并且不再自动重绑。
     * 如果此时拿到了 WRITE_SECURE_SETTINGS，就重写一次服务列表让系统重新绑定，
     * 遮罩会随之自动回来；没有这个权限就什么都不做，
     * 交给用户点「启动」时的提示去引导，不会造成任何副作用。
     */
    private fun scheduleAccessibilitySelfHeal() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (!AccessibilityGuard.canRestore()) return@postDelayed
            if (isAccessibilityServiceConnected()) return@postDelayed
            // 只处理「用户本来就想要这个服务」的情况：
            // 要么系统设置里还勾着，要么用户之前正开着遮罩。
            if (!AccessibilityGuard.isServiceEnabled() && !shouldRestoreNightScreen()) {
                return@postDelayed
            }
            Log.i(TAG, "无障碍服务未连接，尝试自动恢复")
            AccessibilityGuard.restore()
        }, SELF_HEAL_DELAY_MS)
    }

    /**
     * 把未捕获异常写到 filesDir/crash/ 下。
     *
     * 这个应用没有接任何崩溃上报，崩溃后唯一的痕迹只有 logcat，而 logcat 在系统重启、
     * 缓冲区溢出或者被 adb 清掉之后就没了，导致「偶发崩溃无法定位」。
     * 落盘后可以用下面的命令取出来：
     *   adb shell run-as <包名> ls files/crash
     *   adb shell run-as <包名> cat files/crash/<文件名>
     *
     * 注意：记录完必须把异常继续交给原来的处理器，否则系统不会再杀进程、
     * 也不会写 dropbox，反而会留下一个卡死的进程。
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordCrash(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun recordCrash(thread: Thread, throwable: Throwable) {
        val dir = File(filesDir, "crash")
        if (!dir.exists() && !dir.mkdirs()) return

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val content = buildString {
            appendLine("time=${Date()}")
            appendLine("thread=${thread.name}")
            appendLine(Log.getStackTraceString(throwable))
        }
        File(dir, "$stamp.txt").writeText(content)

        // 只保留最近若干份，避免长期使用后无限增长
        dir.listFiles()
            ?.sortedByDescending { it.name }
            ?.drop(MAX_CRASH_FILES)
            ?.forEach { file -> runCatching { file.delete() } }
    }

    private companion object {
        const val MAX_CRASH_FILES = 10
        const val TAG = "App"
        const val SELF_HEAL_DELAY_MS = 4000L
    }
}

lateinit var appContext: Context
