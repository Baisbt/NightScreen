package com.skyd.nightscreen.ui.component.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.skyd.nightscreen.R
import com.skyd.nightscreen.appContext
import com.skyd.nightscreen.ui.NightScreenReceiver
import com.skyd.nightscreen.ui.activity.MainActivity
import com.skyd.nightscreen.ui.component.alphaRange
import com.skyd.nightscreen.ui.component.closeNightScreen
import com.skyd.nightscreen.ui.component.screenAlpha
import com.skyd.nightscreen.ui.component.showDialogAndNightScreen
import com.skyd.nightscreen.ui.screen.settings.SETTINGS_SCREEN_ROUTE
import com.skyd.nightscreen.ui.service.isAccessibilityServiceRunning
import java.lang.Float.max
import java.lang.Float.min

var dialogIsShowing = false
    private set
var dialog: AlertDialog? = null
    private set

fun getNightScreenDialog(c: Context? = null): AlertDialog {
    val context = c ?: ContextThemeWrapper(appContext, R.style.Theme_NightScreen)
    val view = LayoutInflater.from(context).inflate(
        R.layout.dialog_night_screen,
        null,
        false
    )

    // 对话框窗口默认 Gravity.CENTER，正好压在屏幕正中间——也就是遮罩下最想看清的地方。
    // 往上挪一段（负的 y 在 CENTER 下表示向上偏移），让它的中心落在屏幕高度约 1/3 处：
    // 水平方向仍然居中，但不再挡住画面主体。
    val verticalOffset = context.resources.displayMetrics.heightPixels / 5

    val dialog = MaterialAlertDialogBuilder(context)
        .setView(view)
        .create().apply {
            window?.attributes?.let {
                it.dimAmount = 0f
                it.y = -verticalOffset
            }
        }.apply {
            setOnShowListener {
                dialogIsShowing = true
                dialog = this
            }
            setOnDismissListener {
                dialogIsShowing = false
                dialog = null
            }
        }

    val ivSettings = view.findViewById<Button>(R.id.btn_settings_night_screen_dialog)
    val ivPowerOff = view.findViewById<Button>(R.id.btn_power_off_night_screen_dialog)
    val slider = view.findViewById<Slider>(R.id.slider_night_screen_dialog)

    ivSettings.setOnClickListener {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            action = SETTINGS_SCREEN_ROUTE
        })
        dialog.dismiss()
    }
    ivPowerOff.setOnClickListener {
        closeNightScreen()
        dialog.dismiss()
    }
    slider.valueFrom = 1f - alphaRange.endInclusive
    slider.valueTo = 1f - alphaRange.start
    slider.value = max(slider.valueFrom, min(1f - screenAlpha, slider.valueTo))
    slider.setLabelFormatter {
        String.format("%.1f", it * 100f) + "%"
    }
    slider.addOnChangeListener { _, value, _ ->
        screenAlpha = 1f - value
    }

    return dialog
}

@SuppressLint("LaunchActivityFromNotification")
fun requestAllPermissionsWithAccessibilityAndShow(
    context: Context,
    /**
     * 调用方是否就在本进程的前台（例如主页的「启动」按钮）。
     *
     * 是的话直接执行，不用再绕一圈系统广播队列。广播本身要经过 system_server 排队、
     * 再回调到自己进程，正常也要几十毫秒，忙的时候更久；而这条路径是用户按下去就
     * 期待屏幕立刻变暗的，白等一趟最不划算。
     * 通知栏 / 快捷磁贴那种「从外面叫醒 App」的场景仍走广播（默认 false）。
     */
    direct: Boolean = false,
) {
    if (XXPermissions.isGranted(context, Permission.SYSTEM_ALERT_WINDOW) &&
        XXPermissions.isGranted(context, Permission.POST_NOTIFICATIONS) &&
        XXPermissions.isGranted(context, Permission.NOTIFICATION_SERVICE) &&
        XXPermissions.isGranted(context, Permission.WRITE_SETTINGS) &&
        isAccessibilityServiceRunning(context)
    ) {
        if (direct) {
            showDialogAndNightScreen()
        } else {
            NightScreenReceiver.sendBroadcast(
                context = context,
                action = NightScreenReceiver.SHOW_DIALOG_AND_NIGHT_SCREEN_ACTION
            )
        }
    } else {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.REQUEST_PERMISSION_AND_SHOW_ACTION
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
