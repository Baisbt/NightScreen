package com.skyd.nightscreen.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import com.skyd.nightscreen.ext.AccessibilityPermission
import com.skyd.nightscreen.ext.openAccessibilitySettings
import com.skyd.nightscreen.ext.requestAllPermissionsAndShow
import com.skyd.nightscreen.ext.sharedPreferences
import com.skyd.nightscreen.log.HealthMonitor
import com.skyd.nightscreen.log.NSLog
import com.skyd.nightscreen.ui.NightScreenReceiver
import com.skyd.nightscreen.ui.component.autoNightScreenOnLaunch
import com.skyd.nightscreen.ui.component.showDialogAndNightScreen
import com.skyd.nightscreen.ui.component.showNightScreenLayer
import com.skyd.nightscreen.ui.component.showToast
import com.skyd.nightscreen.ui.local.LocalNavController
import com.skyd.nightscreen.ui.screen.about.ABOUT_SCREEN_ROUTE
import com.skyd.nightscreen.ui.screen.about.AboutScreen
import com.skyd.nightscreen.ui.screen.home.HOME_SCREEN_ROUTE
import com.skyd.nightscreen.ui.screen.home.HomeScreen
import com.skyd.nightscreen.ui.screen.license.LICENSE_SCREEN_ROUTE
import com.skyd.nightscreen.ui.screen.license.LicenseScreen
import com.skyd.nightscreen.ui.screen.settings.SETTINGS_SCREEN_ROUTE
import com.skyd.nightscreen.ui.screen.settings.SettingsScreen
import com.skyd.nightscreen.ui.service.isAccessibilityServiceRunning
import com.skyd.nightscreen.ui.theme.NightScreenTheme
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class MainActivity : BaseComposeActivity() {

    private lateinit var navController: androidx.navigation.NavController

    /** 只在本次 Activity 生命周期内申请一次，避免旋转/重组时反复跳系统设置页。 */
    private var permissionRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setContent {
            val navController = rememberNavController()
            CompositionLocalProvider(LocalNavController provides navController) {
                this.navController = navController
                NightScreenTheme {
                    NavHost(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        navController = navController,
                        startDestination = HOME_SCREEN_ROUTE,
                    ) {
                        composable(HOME_SCREEN_ROUTE) {
                            HomeScreen()
                        }
                        composable(
                            route = SETTINGS_SCREEN_ROUTE,
                            deepLinks = listOf(navDeepLink { action = SETTINGS_SCREEN_ROUTE })
                        ) {
                            SettingsScreen()
                        }
                        composable(ABOUT_SCREEN_ROUTE) {
                            AboutScreen()
                        }
                        composable(LICENSE_SCREEN_ROUTE) {
                            LicenseScreen()
                        }
                    }
                }
                // 必须放在 LaunchedEffect 里只跑一次。
                // 直接写在 setContent 的 lambda 里会在每次重组时执行，
                // 于是权限申请、页面跳转会被反复触发（叠几十个权限弹窗）。
                LaunchedEffect(Unit) {
                    doIntentAction(intent?.action)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        doIntentAction(intent?.action)
    }

    override fun onResume() {
        super.onResume()
        autoStartNightScreenIfNeeded()
        HealthMonitor.snapshot("回到前台")
    }

    /**
     * 「启动时自动开启」：回到前台时如果遮罩还没开，就自动打开。
     *
     * 这里不再等待、也不再申请无障碍权限——授权是持久化的系统设置，
     * 已经开启过的用户在 [AccessibilityPermission.isEnabledInSystem] 里会直接命中，
     * 只需要等系统把服务绑定好（通常几百毫秒，由服务自身的 onServiceConnected 完成）。
     *
     * 只有真的没授权时才引导用户去系统设置页，而且每个 Activity 生命周期只引导一次。
     */
    private fun autoStartNightScreenIfNeeded() {
        if (!autoNightScreenOnLaunch) return
        if (showNightScreenLayer) return

        if (AccessibilityPermission.isEnabledInSystem(this)) {
            // 已授权：直接等首帧后开遮罩，不做任何跳转
            window.decorView.post {
                if (!autoNightScreenOnLaunch) return@post
                if (showNightScreenLayer) return@post
                showDialogAndNightScreen()
            }
        } else {
            NSLog.w("MainActivity", "自动开启被跳过：无障碍权限不可用")
            if (!permissionRequested &&
                !AccessibilityPermission.hasGrantedOnce(this) &&
                autoNightScreenOnLaunch
            ) {
                // 首次安装才会走到这里：引导去授权，避免用户以为是「每次都要等」
                requestPermission()
            }
        }
    }

    /**
     * 把「需要用户去系统设置里打开无障碍」这件事收敛成一次。
     *
     * 老实现无论是否已授权都会跳系统设置页，而且每次 onResume 都可能跳，
     * 用户感受到的就是「每次打开 App 都要等 3~7 秒」。
     */
    private fun requestPermission() {
        if (permissionRequested) return
        permissionRequested = true
        NSLog.i("MainActivity", "引导用户开启无障碍服务")

        if (isAccessibilityServiceRunning(this)) {
            requestAllPermissionsAndShow()
        } else {
            openAccessibilitySettings()
        }
    }

    private val startForResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 从系统设置页返回：这里只做「用户是否真的把它打开了」的确认，
        // 不再重复弹权限申请。
        if (AccessibilityPermission.isEnabledInSystem(this)) {
            AccessibilityPermission.markGranted(this)
            NSLog.i("MainActivity", "用户已开启无障碍服务")
            requestAllPermissionsAndShow()
        } else {
            NSLog.w("MainActivity", "用户从无障碍设置返回，但服务仍未启用")
            getString(com.skyd.nightscreen.R.string.no_accessibility_permission).showToast()
        }
    }

    private fun doIntentAction(action: String?) {
        action ?: return
        when (action) {
            REQUEST_PERMISSION_AND_SHOW_ACTION -> {
                requestPermission()
            }

            SETTINGS_SCREEN_ROUTE -> {
                if (::navController.isInitialized) {
                    navController.navigate(SETTINGS_SCREEN_ROUTE) {
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    }

    companion object {
        const val REQUEST_PERMISSION_AND_SHOW_ACTION = "requestPermissionsAndShow"
    }
}