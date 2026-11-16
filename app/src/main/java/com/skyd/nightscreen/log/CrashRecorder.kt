package com.skyd.nightscreen.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃与「进程被系统杀掉」的记录器。
 *
 * 这个应用最常见的故障不是崩溃，而是**进程被 ROM 的省电策略直接杀掉**：
 * 没有异常、没有 FATAL、`logcat -b crash` 里什么都没有，用户只看到「夜屏突然不亮了」。
 * 所以只记录未捕获异常是不够的，还必须记录「上一次进程是怎么没的」。
 *
 * 手段有两层：
 * 1. 运行时：Thread.setDefaultUncaughtExceptionHandler 抓 Java 崩溃，并把崩溃前的日志 ring 一起刷盘。
 * 2. 下次启动时：读 ActivityManager.getHistoricalProcessExitReasons()，
 *    把「上次进程退出的原因」（被 LMK 杀 / 被 force-stop / 崩溃 / ANR / 自杀死）写进日志。
 *
 * 第 2 点很关键：进程被 OOM killer 杀掉时来不及执行任何代码，
 * 唯一的证据就是系统记账，而这份记账只有下一次启动才读得到。
 */
object CrashRecorder {

    private const val TAG = "CrashRecorder"

    private const val MAX_CRASH_FILES = 10

    /** 只在启动时读一次历史退出原因，避免反复跨进程查询。 */
    private var exitReasonsDumped = false

    fun install(context: Context) {
        installUncaughtHandler()
        dumpPreviousExitReasons(context)
    }

    private fun installUncaughtHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordCrash(thread, throwable) }
            // 必须把异常继续交给原处理器：否则系统不会再杀掉进程、不会写 dropbox，
            // 反而留下一个卡死但仍然活着的进程，问题会从「崩溃」退化成「整个应用无响应」。
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun recordCrash(thread: Thread, throwable: Throwable) {
        // 先把崩溃前的上下文落盘：真正能定位问题的往往是崩溃前那几十行操作记录
        runCatching { NSLog.flushRingSync("CrashRecorder", "未捕获异常即将终止进程") }
        runCatching { NSLog.critical("CrashRecorder", "FATAL on thread=${thread.name}", throwable) }
        runCatching { writeCrashFile(thread, throwable) }
    }

    private fun writeCrashFile(thread: Thread, throwable: Throwable) {
        val dir = crashDir() ?: return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val content = buildString {
            appendLine("time=${Date()}")
            appendLine("thread=${thread.name}")
            appendLine("pid=${Process.myPid()}")
            appendLine(Log.getStackTraceString(throwable))
        }
        File(dir, "crash-$stamp.txt").writeText(content)

        dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_CRASH_FILES)
            ?.forEach { file -> runCatching { file.delete() } }
    }

    private fun crashDir(): File? {
        val base = NSLog.logDir() ?: return null
        val dir = File(base, "crash")
        return if (runCatching { dir.isDirectory || dir.mkdirs() }.getOrDefault(false)) dir else null
    }

    /**
     * 把上一次（以及更早几次）进程退出的原因写进本次会话日志。
     *
     * `ApplicationExitInfo` 需要 API 30+；低版本没有这套记账，直接跳过。
     * 这是判断「后台放久了为什么失效」最直接的证据：
     *   - reason=REASON_LOW_MEMORY / REASON_KILLED  → 被系统省电或 LMK 回收
     *   - reason=REASON_CRASH / REASON_CRASH_NATIVE → 我们自己的代码崩了
     *   - reason=REASON_ANR                         → 主线程被卡住
     *   - reason=REASON_USER_REQUESTED              → 用户手动停止 / 被厂商管家清理
     */
    @Suppress("NewApi")
    fun dumpPreviousExitReasons(context: Context) {
        if (exitReasonsDumped) return
        exitReasonsDumped = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            NSLog.i(TAG, "系统版本过低，无法读取历史退出原因")
            return
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val selfPid = Process.myPid()

        val records = runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        }.onFailure { Log.w(TAG, "读取历史退出原因失败", it) }.getOrNull().orEmpty()

        if (records.isEmpty()) {
            NSLog.i(TAG, "无历史进程退出记录（首次安装或系统未记账）")
            return
        }

        NSLog.i(TAG, "上次启动以来的进程退出记录 ${records.size} 条：")
        records.forEach { info ->
            // 当前正在跑的这次进程也会出现在列表里，跳过
            if (info.pid == selfPid) return@forEach
            val line = buildString {
                append("time=")
                append(SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp)))
                append(" pid=")
                append(info.pid)
                append(" reason=")
                append(reasonName(info.reason))
                append(" importance=")
                append(info.importance)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    append(" description=")
                    append(info.description)
                }
                val rssMb = info.rss / 1024
                append(" rss=")
                append(rssMb)
                append("MB")
            }
            NSLog.w(TAG, line)

            // 崩溃类原因把系统保存的堆栈一并取出
            val trace = runCatching { info.traceInputStream?.bufferedReader()?.use { it.readText() } }
                .getOrNull()
            if (!trace.isNullOrBlank()) {
                NSLog.e(TAG, "系统记录的退出堆栈 (pid=${info.pid}):\n$trace")
            }
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF(主动退出)"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED(被信号杀死)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY(内存不足被回收)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH(Java 崩溃)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE(native 崩溃)"
        ApplicationExitInfo.REASON_ANR -> "ANR(无响应)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED(用户/管家停止)"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($reason)"
    }
}