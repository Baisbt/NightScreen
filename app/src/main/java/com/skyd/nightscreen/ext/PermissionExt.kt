package com.skyd.nightscreen.ext

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.skyd.nightscreen.R
import com.skyd.nightscreen.appContext
import com.skyd.nightscreen.log.NSLog
import com.skyd.nightscreen.ui.NightScreenReceiver
import com.skyd.nightscreen.ui.component.showToast
import com.skyd.nightscreen.ui.listener.dsl.requestPermissions
import com.skyd.nightscreen.ui.listener.dsl.requestSinglePermission


fun Activity.requestSystemAlertWindowPermission(
    onDenied: (never: Boolean) -> Unit = { getString(R.string.no_permission_can_not_run).showToast() },
    onGranted: () -> Unit = {}
) {
    XXPermissions
        .with(this)
        .permission(Permission.SYSTEM_ALERT_WINDOW)
        .requestSinglePermission {
            onGranted(onGranted)
            onDenied(onDenied)
        }
}

private val onDeniedCallback: (permissions: MutableList<String>?, never: Boolean) -> Unit =
    { permissions, _ ->
        val permissionList = mutableSetOf<String>()
        if (permissions?.contains(Permission.SYSTEM_ALERT_WINDOW) == true) {
            permissionList.add(appContext.getString(R.string.alert_window_permission))
        }
        if (permissions?.contains(Permission.POST_NOTIFICATIONS) == true) {
            permissionList.add(appContext.getString(R.string.post_notification_permission))
        }
        if (permissions?.contains(Permission.NOTIFICATION_SERVICE) == true) {
            permissionList.add(appContext.getString(R.string.post_notification_permission))
        }
        appContext.getString(
            R.string.request_permission_failed,
            if (permissionList.size > 1) permissionList.joinToString()
            else permissionList.firstOrNull().orEmpty()
        ).showToast()
    }

private val onGrantedCallback: (permissions: MutableList<String>?, all: Boolean) -> Unit =
    { _, all ->
        if (all) appContext.getString(R.string.request_permissions_success).showToast()
    }

fun Activity.requestAllPermissions(
    onDenied: (permissions: MutableList<String>?, never: Boolean) -> Unit = onDeniedCallback,
    onGranted: (permissions: MutableList<String>?, all: Boolean) -> Unit = onGrantedCallback,
) {
    XXPermissions
        .with(this)
        .permission(
            Permission.SYSTEM_ALERT_WINDOW,
            Permission.POST_NOTIFICATIONS,
            Permission.NOTIFICATION_SERVICE,
            Permission.WRITE_SETTINGS,
        )
        .requestPermissions {
            onGranted(onGranted)
            onDenied(onDenied)
        }
}

fun Activity.requestAllPermissionsAndShow(
    onDenied: (permissions: MutableList<String>?, never: Boolean) -> Unit = onDeniedCallback,
    onGranted: (permissions: MutableList<String>?, all: Boolean) -> Unit = { permissions, all ->
        onGrantedCallback(permissions, all)
        if (permissions?.contains(Permission.POST_NOTIFICATIONS) == true &&
            permissions.contains(Permission.NOTIFICATION_SERVICE) &&
            permissions.contains(Permission.WRITE_SETTINGS)
        ) {
            NightScreenReceiver.sendBroadcast(action = NightScreenReceiver.SHOW_DIALOG_AND_NIGHT_SCREEN_ACTION)
            NightScreenReceiver.sendBroadcast(action = NightScreenReceiver.SHOW_NOTIFICATION_ACTION)
        }
    },
) {
    requestAllPermissions(onDenied = onDenied, onGranted = onGranted)
}

/**
 * 只在「真的还没授权」时才打开系统无障碍设置页。
 *
 * 关键点：无障碍授权是系统级、持久化的开关，正常情况下一台手机只需要授权一次。
 * 老实现无论是否已授权都会 `startActivity(ACTION_ACCESSIBILITY_SETTINGS)`，
 * 用户因此每次冷启动都要盯着这个跳转等 3~7 秒、甚至以为每次都要重新开一次。
 *
 * 只有下面两种情况才需要用户动手：
 *   1. 从未授权过（首次安装）；
 *   2. 曾经授权过，但系统里的开关被 force-stop / 清理软件 / ROM 重置掉了。
 *
 * 已经授权的情况下，服务绑定本身只需要几百毫秒，由
 * [com.skyd.nightscreen.ui.service.OverlayAccessibilityService.onServiceConnected] 自动完成，
 * 不需要任何跳转和等待。
 */
fun Activity.ensureAccessibilityPermission(onAlreadyGranted: () -> Unit, onNeedUserAction: () -> Unit) {
    if (AccessibilityPermission.isEnabledInSystem(this)) {
        AccessibilityPermission.markGranted(this)
        NSLog.i("PermissionExt", "无障碍权限已授权，跳过申请流程")
        onAlreadyGranted()
    } else {
        NSLog.w(
            "PermissionExt",
            "无障碍权限不可用（曾授权=%s），需要用户手动开启",
            AccessibilityPermission.hasGrantedOnce(this)
        )
        onNeedUserAction()
    }
}

fun Activity.openAccessibilitySettings() {
    runCatching {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }.onFailure { NSLog.e("PermissionExt", "打开无障碍设置页失败", it) }
}