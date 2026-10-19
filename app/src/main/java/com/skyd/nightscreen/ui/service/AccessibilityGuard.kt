package com.skyd.nightscreen.ui.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.skyd.nightscreen.appContext

/**
 * 无障碍服务「自愈」工具。
 *
 * 国产 ROM 清理后台时会直接杀进程，随后系统的 AccessibilityManagerService 会把本应用的
 * 无障碍服务记进 `Crashed services` 并且**不再自动重绑**；如果是 force-stop，
 * 还会把 `enabled_accessibility_services` 整个清空。
 * 这两种状态下遮罩都画不出来，用户只能跑去系统设置里关开一次。
 *
 * 实测（iQOO Neo8 Pro / Android 16）：只要重写一次
 * `enabled_accessibility_services`，系统就会重新绑定服务，并顺带清掉 crashed 标记。
 * 改写 Secure 设置需要 `WRITE_SECURE_SETTINGS`，普通应用拿不到，
 * 但可以通过 adb 一次性授予：
 * ```
 * adb shell pm grant <包名> android.permission.WRITE_SECURE_SETTINGS
 * ```
 * 没授予时会自动降级为「提示 + 跳到系统无障碍设置页」，不会崩。
 */
object AccessibilityGuard {

    private const val TAG = "AccessibilityGuard"

    /** 两次写入之间要留点时间，否则系统只会观察到最终值，等于没改动。 */
    private const val RESTORE_DELAY_MS = 500L

    private val serviceName: String
        get() = "${appContext.packageName}/${OverlayAccessibilityService::class.java.name}"

    /** 是否拿到了自愈所需的权限。 */
    fun canRestore(): Boolean = runCatching {
        appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 系统设置里是否勾选了本应用的无障碍服务（注意：勾着不等于真的在跑）。 */
    fun isServiceEnabled(): Boolean = readEnabledServices().contains(serviceName)

    /** 直接跳到系统无障碍设置页，让用户手动关开一次。 */
    fun openAccessibilitySettings() {
        runCatching {
            appContext.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "打开无障碍设置页失败", it) }
    }

    /**
     * 重写服务列表，让系统重新绑定本应用的无障碍服务。
     *
     * 必须「先移除、再写回」：写入完全相同的值不会触发系统重新绑定，
     * 而 crashed 标记只有在服务集合发生变化时才会被清掉。
     * 列表里其他无障碍服务的条目会原样保留，不会被误删。
     */
    fun restore(onDone: () -> Unit = {}) {
        if (!canRestore()) return
        val cr = appContext.contentResolver
        val withoutSelf = readEnabledServices().filter { it != serviceName }

        runCatching {
            Settings.Secure.putString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                withoutSelf.joinToString(":")
            )
        }.onFailure { Log.e(TAG, "清空无障碍服务条目失败", it) }

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                Settings.Secure.putString(
                    cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    (withoutSelf + serviceName).joinToString(":")
                )
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }.onFailure { Log.e(TAG, "写回无障碍服务条目失败", it) }
            onDone()
        }, RESTORE_DELAY_MS)
    }

    private fun readEnabledServices(): List<String> = runCatching {
        Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
            ?.split(':')
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }.getOrDefault(emptyList())
}
