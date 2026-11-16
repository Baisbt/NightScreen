package com.skyd.nightscreen.ui.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.skyd.nightscreen.R
import com.skyd.nightscreen.log.NSLog
import com.skyd.nightscreen.ui.NightScreenReceiver
import com.skyd.nightscreen.ui.activity.MainActivity
import com.skyd.nightscreen.ui.component.showNightScreenLayer
import com.skyd.nightscreen.ui.screen.settings.SETTINGS_SCREEN_ROUTE

/**
 * 护眼状态的前台服务。
 *
 * 解决的是「长时间放后台后夜屏失效」这个根因问题：
 *
 * 无障碍服务理论上由系统托管，但在国产 ROM 上并不是——实测本机（V2302A / Android 16），
 * 进程在息屏一段时间后会被 `single-cleaner` 以 `reason=LOW_MEMORY` 杀掉
 * （见 `dumpsys activity exit-info`）。进程一死，遮罩层的窗口就跟着没了；
 * 更糟的是系统会把该无障碍服务记进 `Crashed services`，此后**不再自动重绑**，
 * 于是用户看到的就是「夜屏突然不亮了，怎么点都没反应，还得去设置里关开一次」。
 *
 * 前台服务把本进程的重要性抬到 `IMPORTANCE_FOREGROUND_SERVICE(125)`，
 * 而不是可以随时被回收的 `CACHED(400)`，这能显著降低被清理的概率；
 * 同时它负责在服务掉线后把无障碍服务重新拉回来（见 [HealthWatchdog]）。
 *
 * 通知外观上刻意做得克制：仅在遮罩开启时显示，关闭遮罩即 stopSelf，
 * 不给用户留一个「永远挂在那」的通知。
 */
class NightScreenForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    /** 周期性检查无障碍服务是否掉线，掉线则自愈。 */
    private val watchdog = object : Runnable {
        override fun run() {
            // 遮罩已被用户关掉时立刻收工：既不再占着通知，也不该触发自愈
            // （自愈会重写系统无障碍列表，必然造成一次解绑重绑，对用户是纯噪音）
            if (!showNightScreenLayer) {
                NSLog.i(TAG, "遮罩已关闭，前台服务看护退出")
                stopSelf()
                return
            }
            HealthWatchdog.checkAndHeal("前台服务看护")
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NSLog.i(TAG, "前台服务创建")
        startForegroundSafely()
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                NSLog.i(TAG, "前台服务收到停止指令")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // 先确保已经进入前台状态，否则系统会在 5 秒内抛
        // RemoteServiceException: Context.startForegroundService() did not then call Service.startForeground()
        startForegroundSafely()

        // 遮罩已经关掉时不必再占着通知
        if (!showNightScreenLayer) {
            NSLog.i(TAG, "遮罩未开启，前台服务自行退出")
            stopSelf()
            return START_NOT_STICKY
        }

        // START_STICKY：被系统杀掉后尝试重建，重建时再由 watchdog 把无障碍服务拉回来
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(watchdog)
        NSLog.w(TAG, "前台服务销毁")
    }

    private fun startForegroundSafely() {
        val notification = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            // 前台服务启动失败不是致命错误：退化成普通服务，遮罩仍能工作，
            // 只是保活能力变差。绝不能因此让整个 App 崩掉。
            NSLog.e(TAG, "startForeground 失败，降级为普通服务", it)
        }
    }

    private fun buildNotification(): Notification {
        createChannel()

        val clickIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            action = SETTINGS_SCREEN_ROUTE
        }
        val clickPending = PendingIntent.getActivity(
            this, 0, clickIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, NightScreenForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_icon_128)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_keep_alive))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(clickPending)
            .addAction(0, getString(R.string.power_off), stopPending)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_keep_alive),
            // 保活通知不需要打扰用户：LOW 重要性不会弹出、不会响铃
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_keep_alive)
            setShowBadge(false)
        }
        runCatching { manager.createNotificationChannel(channel) }
    }

    companion object {
        private const val TAG = "NightScreenFgService"
        const val CHANNEL_ID = "NightScreenKeepAlive"
        const val NOTIFICATION_ID = 2
        const val ACTION_STOP = "com.skyd.nightscreen.STOP_FOREGROUND"

        /** 看护间隔：比心跳更密，掉线后能较快恢复。 */
        private const val WATCHDOG_INTERVAL_MS = 20_000L

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, NightScreenForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { NSLog.e(TAG, "启动前台服务失败", it) }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, NightScreenForegroundService::class.java))
            }.onFailure { NSLog.w(TAG, "停止前台服务失败", it) }
        }

        /** 通知渠道是否已创建，避免重复跨进程调用。 */
        fun ensureChannelCreated(context: Context) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            runCatching {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notification_channel_keep_alive),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }

        fun canPostNotification(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
}