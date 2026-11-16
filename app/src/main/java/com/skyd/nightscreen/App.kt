package com.skyd.nightscreen

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.skyd.nightscreen.log.CrashRecorder
import com.skyd.nightscreen.log.HealthMonitor
import com.skyd.nightscreen.log.NSLog
import com.skyd.nightscreen.ui.service.HealthWatchdog
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = this

        // 日志必须最先初始化：后面任何一步出问题都要有据可查
        NSLog.init(this)
        NSLog.i(TAG, "Application 启动")

        // 记录上一次进程是怎么没的（被 LMK 杀 / 崩溃 / force-stop），
        // 这是排查「后台放久了失效」最关键的证据
        CrashRecorder.install(this)
        HealthMonitor.start(this)

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
     *
     * 这里只用很短的延迟（而不是原来的 4 秒）：服务绑定本身很快，
     * 之前那段等待正是用户感知到的「打开后要等 3~7 秒」的一部分。
     */
    private fun scheduleAccessibilitySelfHeal() {
        Handler(Looper.getMainLooper()).postDelayed({
            HealthWatchdog.checkAndHeal("启动自检")
        }, SELF_HEAL_DELAY_MS)
    }

    private companion object {
        const val TAG = "App"

        /** 给系统绑定无障碍服务留一点时间，但远短于原来的 4 秒。 */
        const val SELF_HEAL_DELAY_MS = 1_200L
    }
}

lateinit var appContext: Context