package com.skyd.nightscreen.ui.screen.home

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.skyd.nightscreen.R
import com.skyd.nightscreen.ext.activity
import com.skyd.nightscreen.ext.requestAllPermissions
import com.skyd.nightscreen.ui.component.NsTopBar
import com.skyd.nightscreen.ui.component.NsTopBarStyle
import com.skyd.nightscreen.ui.component.autoNightScreenOnLaunch
import com.skyd.nightscreen.ui.component.dialog.requestAllPermissionsWithAccessibilityAndShow
import com.skyd.nightscreen.ui.component.showToast
import com.skyd.nightscreen.ui.local.LocalNavController
import com.skyd.nightscreen.ui.screen.about.ABOUT_SCREEN_ROUTE
import com.skyd.nightscreen.ui.screen.settings.SETTINGS_SCREEN_ROUTE
import com.skyd.nightscreen.ui.service.isAccessibilityServiceRunning

const val HOME_SCREEN_ROUTE = "homeScreen"

@Composable
fun HomeScreen() {
    val navController = LocalNavController.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            NsTopBar(
                style = NsTopBarStyle.Large,
                title = {
                    Text(text = stringResource(R.string.app_name))
                },
                navigationIcon = {},
            )
        }
    ) { innerPadding ->
        val startForResult = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (isAccessibilityServiceRunning(context)) {
                context.activity.requestAllPermissions()
            } else {
                context.getString(R.string.no_accessibility_permission).showToast()
            }
        }
        // 这几项固定不会超出屏幕，做成可滚动列表只会带来无意义的回弹和顶部栏折叠。
        // 顶部栏也就不再需要 scrollBehavior，标题保持展开状态。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            var autoStartChecked by remember { mutableStateOf(autoNightScreenOnLaunch) }
            HomeItem(
                imageVector = Icons.Default.PlayArrow,
                text = stringResource(id = R.string.run_night_screen),
            ) {
                requestAllPermissionsWithAccessibilityAndShow(context, direct = true)
            }
            HomeSwitchItem(
                imageVector = Icons.Default.Autorenew,
                text = stringResource(id = R.string.auto_start_on_launch),
                description = stringResource(id = R.string.auto_start_on_launch_description),
                checked = autoStartChecked,
            ) {
                autoStartChecked = it
                autoNightScreenOnLaunch = it
            }
            HomeItem(
                imageVector = Icons.Default.DoneAll,
                text = stringResource(id = R.string.request_permissions),
            ) {
                if (isAccessibilityServiceRunning(context)) {
                    context.activity.requestAllPermissions()
                } else {
                    startForResult.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            HomeItem(
                imageVector = Icons.Default.Settings,
                text = stringResource(id = R.string.settings),
            ) {
                navController.navigate(SETTINGS_SCREEN_ROUTE)
            }
            HomeItem(
                imageVector = Icons.Default.Info,
                text = stringResource(id = R.string.about),
            ) {
                navController.navigate(ABOUT_SCREEN_ROUTE)
            }
        }
    }
}

@Composable
private fun HomeItem(imageVector: ImageVector, text: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.padding(vertical = 10.dp),
        shape = RoundedCornerShape(percent = 100)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(30.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                modifier = Modifier.size(30.dp),
                imageVector = imageVector,
                contentDescription = null
            )
            Text(
                modifier = Modifier.padding(start = 25.dp),
                text = text,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1
            )
        }
    }
}

/**
 * 和 [HomeItem] 同款卡片，右侧多一个开关。
 *
 * 整张卡片都是可点区域，点文字等于点开关；开关自身不单独响应点击，
 * 免得出现「点开关一次、点卡片又一次」的双重切换。
 */
@Composable
private fun HomeSwitchItem(
    imageVector: ImageVector,
    text: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier.padding(vertical = 10.dp),
        shape = RoundedCornerShape(percent = 100)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    enabled = true,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .padding(horizontal = 30.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                modifier = Modifier.size(30.dp),
                imageVector = imageVector,
                contentDescription = null
            )
            Column(
                modifier = Modifier
                    .padding(start = 25.dp, end = 15.dp)
                    .weight(1f)
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}
