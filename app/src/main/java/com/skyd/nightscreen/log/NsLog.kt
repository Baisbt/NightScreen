package com.skyd.nightscreen.log

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.skyd.nightscreen.BuildConfig
import com.skyd.nightscreen.appContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 落盘日志。
 *
 * 为什么不能只用 logcat：
 * logcat 的 ring buffer 只有几 MB，系统重启、缓冲区溢出、或者任何一次 `adb logcat -c` 都会把它清空；
 * 而本项目要查的是「长时间在后台之后突然失效」这种几小时甚至几天后复发的问题，
 * 等用户发现异常时 logcat 早就滚没了。所以关键路径必须自己写文件。
 *
 * 日志落在**外部私有目录**：`Android/data/<包名>/files/logs/`。
 * 选这里而不是内部 `files/` 是因为 adb 可以直接读：
 * ```
 * adb pull /sdcard/Android/data/com.skyd.nightscreen.debug/files/logs
 * ```
 * 内部目录需要 `run-as`（仅 debug 可用），release 包会读不到，而 release 恰恰是用户实际在跑的版本。
 * 同时这个目录在应用被卸载时会被系统清掉，不会留下垃圾。
 *
 * 写入线程是单独的 HandlerThread：主线程上任何一次文件 IO 都可能变成掉帧，
 * 而这个应用最敏感的正是「按下按钮 → 屏幕变暗」那几十毫秒。
 */
object NSLog {

    private const val TAG = "NSLog"

    /** 单个日志文件的最大字节数，超过后滚动到 .1 */
    private const val MAX_FILE_BYTES = 512L * 1024L

    /** 保留的滚动文件个数（含当前文件） */
    private const val MAX_ROTATED_FILES = 4

    /** 常驻内存的最近日志条数，崩溃时一次性刷盘，保证崩溃前的上下文不会丢 */
    private const val RING_CAPACITY = 400

    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    private val executor = HandlerThread("ns-log").apply { start() }
    private val handler = Handler(executor.looper)

    private val ring = ArrayDeque<String>(RING_CAPACITY)
    private val ringLock = Any()

    private val initialized = AtomicBoolean(false)

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var sessionBanner: String = ""

    /**
     * 进程启动时调用一次。
     *
     * 每次都新开一个会话文件而不是续写同一个文件：这样「最近一次运行」和「上一次运行」
     * 天然分开，排查「后台放了几小时后失效」时可以直接对比两次会话的结尾。
     */
    fun init(context: Context = appContext) {
        if (!initialized.compareAndSet(false, true)) return

        val dir = resolveLogDir(context)
        if (dir == null) {
            Log.w(TAG, "无法创建日志目录，日志将只输出到 logcat")
            return
        }
        logDir = dir

        sessionBanner = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val header = buildString {
            appendLine("===== NightScreen session $sessionBanner =====")
            appendLine("version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
            appendLine("package=${context.packageName}")
            appendLine("pid=${Process.myPid()}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("time=${Date()}")
            appendLine("=====")
        }
        write(buildSessionFile(dir, sessionBanner), header, truncate = true)
        rotateIfNeeded(dir)
    }

    /** 会话文件的绝对路径，排查时可以直接 pull 单个文件。 */
    fun sessionFile(): File? {
        val dir = logDir ?: return null
        val banner = sessionBanner.ifEmpty { return null }
        return buildSessionFile(dir, banner)
    }

    fun logDir(): File? = logDir

    fun i(scope: String, message: String, vararg args: Any? = emptyArray()) =
        write(Level.I, scope, message, args, null)

    fun w(scope: String, message: String, vararg args: Any? = emptyArray()) =
        write(Level.W, scope, message, args, null)

    fun e(scope: String, message: String, throwable: Throwable? = null, vararg args: Any? = emptyArray()) =
        write(Level.E, scope, message, args, throwable)

    /**
     * 记录一次「进程退出」或「服务断连」这类关键事件，并立即同步刷盘。
     *
     * 走同步写是为了防止还没来得及写就被系统杀掉；只在极少数关键路径上调用。
     */
    fun critical(scope: String, message: String, throwable: Throwable? = null) {
        val line = format(Level.E, scope, message, emptyArray(), throwable)
        appendToRing(line)
        Log.e("Ns/$scope", message, throwable)

        val dir = logDir ?: return
        val banner = sessionBanner.ifEmpty { return }
        writeSync(buildSessionFile(dir, banner), line + "\n", truncate = false)
    }

    /**
     * 把内存里最近的日志一次性刷盘，用于未捕获异常 / ANR 之前。
     *
     * 崩溃现场 logcat 里只剩一行 FATAL，真正的线索是崩溃前几十行
     * 「什么操作触发了它」，所以必须把 ring buffer 里的内容一起落盘。
     */
    fun flushRingSync(scope: String, reason: String) {
        val snapshot = synchronized(ringLock) { ring.toList() }
        val dir = logDir ?: return
        val banner = sessionBanner.ifEmpty { return }
        val content = buildString {
            appendLine("===== $reason ($scope) =====")
            snapshot.forEach { appendLine(it) }
            appendLine("===== end of ring buffer =====")
        }
        writeSync(buildSessionFile(dir, banner), content, truncate = false)
    }

    private enum class Level(val ch: Char) {
        I('I'), W('W'), E('E')
    }

    private fun write(
        level: Level,
        scope: String,
        message: String,
        args: Array<out Any?>,
        throwable: Throwable?,
    ) {
        val line = format(level, scope, message, args, throwable)
        appendToRing(line)
        when (level) {
            Level.I -> Log.i("Ns/$scope", line)
            Level.W -> Log.w("Ns/$scope", line)
            Level.E -> Log.e("Ns/$scope", line, throwable)
        }
        val dir = logDir ?: return
        val banner = sessionBanner.ifEmpty { return }
        handler.post { write(buildSessionFile(dir, banner), line + "\n", truncate = false) }
    }

    private fun format(
        level: Level,
        scope: String,
        message: String,
        args: Array<out Any?>,
        throwable: Throwable?,
    ): String {
        val rendered = if (args.isEmpty()) message else runCatching { message.format(*args) }
            .getOrDefault("$message [format failed, args=${args.joinToString()}]")
        return buildString {
            append(timeFormat.get()?.format(Date()) ?: "")
            append(' ')
            append(level.ch)
            append('/')
            append(scope)
            append(": ")
            append(rendered)
            if (throwable != null) {
                append('\n')
                append(Log.getStackTraceString(throwable))
            }
        }
    }

    private fun appendToRing(line: String) {
        synchronized(ringLock) {
            ring.addLast(line)
            while (ring.size > RING_CAPACITY) ring.removeFirst()
        }
    }

    private fun buildSessionFile(dir: File, banner: String): File {
        val pid = Process.myPid()
        return File(dir, "session-$banner-$pid.log")
    }

    private fun resolveLogDir(context: Context): File? {
        // 优先外部私有目录：adb 可以直接 pull，不需要 run-as
        val external = context.getExternalFilesDir("logs")
        if (external != null && ensureDir(external)) return external
        // 外部存储不可用（极少数情况）时退化到内部目录，debug 包仍可用 run-as 读取
        val internal = File(context.filesDir, "logs")
        return if (ensureDir(internal)) internal else null
    }

    private fun ensureDir(dir: File): Boolean =
        runCatching { dir.isDirectory || dir.mkdirs() }.getOrDefault(false) && dir.canWrite()

    private fun write(file: File, text: String, truncate: Boolean) {
        runCatching {
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            if (truncate) file.writeText(text) else file.appendText(text)
        }.onFailure { Log.w(TAG, "写日志失败: ${file.name}", it) }
    }

    private fun writeSync(file: File, text: String, truncate: Boolean) {
        runCatching {
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            if (truncate) file.writeText(text) else file.appendText(text)
        }.onFailure { Log.w(TAG, "同步写日志失败: ${file.name}", it) }
    }

    /**
     * 控制日志体积。
     *
     * 只保留最近若干个会话文件：长期在后台跑的应用如果无限写日志，
     * 最终会占满用户存储空间，这比不记日志更糟。
     */
    private fun rotateIfNeeded(dir: File) {
        handler.post {
            runCatching {
                val files = dir.listFiles { f -> f.isFile && f.name.startsWith("session-") }
                    ?.sortedByDescending { it.name }
                    ?: return@runCatching
                files.drop(MAX_ROTATED_FILES).forEach { runCatching { it.delete() } }
                files.take(MAX_ROTATED_FILES)
                    .filter { it.length() > MAX_FILE_BYTES }
                    .forEach { file ->
                        // 超过阈值就截断成「尾部」，保留最近的上下文
                        runCatching {
                            val keepFrom = (file.length() - MAX_FILE_BYTES / 2).coerceAtLeast(0)
                            val text = file.readText().takeLast((file.length() - keepFrom).toInt())
                            file.writeText("===== truncated =====\n$text")
                        }
                    }
            }.onFailure { Log.w(TAG, "日志滚动失败", it) }
        }
    }
}