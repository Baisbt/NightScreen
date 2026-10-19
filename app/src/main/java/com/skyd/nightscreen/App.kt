package com.skyd.nightscreen

import android.app.Application
import android.content.Context
import android.util.Log
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
    }
}

lateinit var appContext: Context
