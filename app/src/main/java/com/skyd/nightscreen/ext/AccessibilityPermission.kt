package com.skyd.nightscreen.ext

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.skyd.nightscreen.BuildConfig
import com.skyd.nightscreen.log.NSLog
import com.skyd.nightscreen.ui.service.OverlayAccessibilityService

/**
 * 无障碍服务授权的持久化判断。
 *
 * 为什么需要单独一个文件来管这件事：
 *
 * 无障碍权限是「系统级开关」，一旦用户在系统设置里打开过，它就一直存在
 * （除非用户手动关掉、或者被 force-stop/清理软件清掉），**不需要也不应该每次开 App 都重新申请**。
 *
 * 原来每次启动都会走一遍 `startActivity(Settings.ACTION_ACCESSIBILITY_SETTINGS)`，
 * 即使已经授权成功也照跳不误，用户看到的就是「每次打开都要等 3~7 秒、还要自己点一下」。
 * 这里把判断收敛成三步，只有真的没授权时才跳系统页：
 *
 * 1. [isEnabledInSystem]：系统设置里是否勾着（权威来源，Security Exception 安全）
 * 2. [hasGrantedOnce]：本地是否曾经成功授权过（用于区分「首次安装」和「被 ROM 清掉」）
 * 3. 两者结合决定是「静默等待服务绑定」还是「引导用户去授权」
 */
object AccessibilityPermission {

    private const val TAG = "A11yPermission"

    /** Secure 设置里同一行可能有多个服务，用 ':' 分隔，且可能带包名后缀差异。 */
    fun isEnabledInSystem(context: Context): Boolean = runCatching {
        val enabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        if (enabled != 1) return@runCatching false

        val list = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        val target = componentName(context).flattenToString()
        val targetShort = componentName(context).flattenToShortString()
        list.split(':').any { entry ->
            entry.equals(target, ignoreCase = true) ||
                    entry.equals(targetShort, ignoreCase = true) ||
                    // 兼容厂商在组件名前后加空格/大小写差异的情况
                    entry.trim().equals(target, ignoreCase = true)
        }
    }.onFailure { Log.w(TAG, "读取无障碍设置失败", it) }
        .getOrDefault(false)

    fun componentName(context: Context = com.skyd.nightscreen.appContext): ComponentName =
        ComponentName(context, OverlayAccessibilityService::class.java)

    /** 组件类名，供日志与 adb 命令使用。 */
    fun componentString(context: Context = com.skyd.nightscreen.appContext): String =
        "${context.packageName}/${OverlayAccessibilityService::class.java.name}"

    /** 记录一次「用户已经完成授权」，用于后续区分首次安装与被清理。 */
    fun markGranted(context: Context) {
        runCatching {
            context.getSharedPreferences("App", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_GRANTED_ONCE, true)
                .apply()
        }
        NSLog.i(TAG, "无障碍权限已记录为「曾授权」")
    }

    fun hasGrantedOnce(context: Context): Boolean = runCatching {
        context.getSharedPreferences("App", Context.MODE_PRIVATE)
            .getBoolean(KEY_GRANTED_ONCE, false)
    }.getOrDefault(false)

    private const val KEY_GRANTED_ONCE = "accessibilityGrantedOnce"

    /**
     * 是否需要向用户弹出「去开启无障碍」的引导。
     *
     * 已经授权过且系统里仍然勾着 → 不需要（这是绝大多数启动的情况，避免每次都等待/弹窗）。
     */
    fun needsUserAction(context: Context): Boolean = !isEnabledInSystem(context)
}