package com.skyd.nightscreen.ui.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import com.skyd.nightscreen.ui.component.closeNightScreen
import com.skyd.nightscreen.ui.component.dialog.requestAllPermissionsWithAccessibilityAndShow
import com.skyd.nightscreen.ui.component.showNightScreenLayer


class NightScreenService : TileService() {

    private var receiverRegistered = false

    override fun onStartListening() {
        super.onStartListening()

        // 反复下拉通知栏时 onStartListening 可能连续触发，重复注册会让
        // onStopListening 里的 unregisterReceiver 抛 IllegalArgumentException: Receiver not registered
        if (!receiverRegistered) {
            runCatching {
                ContextCompat.registerReceiver(
                    this,
                    receiver,
                    IntentFilter().apply {
                        addAction(ACTION_ACTIVE_TILE)
                        addAction(ACTION_INACTIVE_TILE)
                    },
                    ContextCompat.RECEIVER_EXPORTED,
                )
                receiverRegistered = true
            }.onFailure { Log.w(TAG, "注册磁贴状态广播失败", it) }
        }

        // getQsTile() 在磁贴尚未就绪时返回 null，直接取属性就是一次空指针崩溃
        qsTile?.apply {
            state = if (showNightScreenLayer) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        unregisterTileReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        // onStopListening 不一定被调用（例如服务直接被销毁），这里兜底
        unregisterTileReceiver()
    }

    private fun unregisterTileReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(receiver) }
            .onFailure { Log.w(TAG, "反注册磁贴状态广播失败", it) }
        receiverRegistered = false
    }

    override fun onClick() {
        super.onClick()

        val tile = qsTile ?: return

        if (tile.state == Tile.STATE_INACTIVE) {
            requestAllPermissionsWithAccessibilityAndShow(this)
        } else {
            closeNightScreen()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val tile = qsTile ?: return
            when (intent?.action) {
                ACTION_ACTIVE_TILE -> {
                    tile.state = Tile.STATE_ACTIVE
                }

                ACTION_INACTIVE_TILE -> {
                    tile.state = Tile.STATE_INACTIVE
                }
            }
            tile.updateTile()
        }
    }

    companion object {
        const val ACTION_ACTIVE_TILE = "active"
        const val ACTION_INACTIVE_TILE = "inactive"
        private const val TAG = "NightScreenService"
    }
}