package com.watchfridge.freezer

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.watchfridge.freezer.core.LocaleManager
import com.watchfridge.freezer.core.Masquerade
import com.watchfridge.freezer.core.RunLog
import com.watchfridge.freezer.ui.AboutScreen
import com.watchfridge.freezer.ui.AddAppsScreen
import com.watchfridge.freezer.ui.FiltersScreen
import com.watchfridge.freezer.ui.FreezerViewModel
import com.watchfridge.freezer.ui.HomeScreen
import com.watchfridge.freezer.ui.LinkScreen
import com.watchfridge.freezer.ui.MasqueradeGate
import com.watchfridge.freezer.ui.SettingsScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.watchfridge.freezer.ui.theme.FridgeTheme

object NavRoutes {
    const val GATE = "gate"
    const val HOME = "home"
    const val ADD_APPS = "add_apps"
    const val ADD_APPS_FILTERS = "add_apps_filters"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    /** 链接页（上二维码、下「在手机上打开」）：地址走路由参数，页面本身不写死链接。 */
    const val LINK = "link/{url}"

    fun link(url: String) = "link/" + android.net.Uri.encode(url)
}

open class MainActivity : ComponentActivity() {

    /** Activity 作用域的唯一实例：三个界面共享，前后台切换也由这里通知它。 */
    private val viewModel: FreezerViewModel by viewModels()

    /**
     * 语言切换核心：每次 Activity 创建都用当前语言模式包装 Context，
     * 配合 recreate() 实现无需重启进程的语言切换。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RunLog.d("created ${javaClass.simpleName}")
        // 组件状态对齐开关值：正常情况下读一遍现值就完事，不会写
        Masquerade.syncLauncher(this)
        val masked = Masquerade.isEnabled(this)
        setContent {
            val shape by viewModel.displayShape.collectAsState()
            FridgeTheme(shape = shape) {
                // Activity 作用域的唯一实例：三个界面共享同一份全量列表，
                // 并使 busy / lastResult 在跨界面操作后仍可见。
                val navController = rememberSwipeDismissableNavController()
                SwipeDismissableNavHost(
                    navController = navController,
                    startDestination = if (masked) NavRoutes.GATE else NavRoutes.HOME,
                ) {
                    if (masked) {
                        composable(NavRoutes.GATE) {
                            MasqueradeGate(
                                onUnlock = {
                                    navController.navigate(NavRoutes.HOME) {
                                        // 门扉页要从栈里拿掉：返回键不该再退回假页面
                                        popUpTo(NavRoutes.GATE) { inclusive = true }
                                    }
                                },
                            )
                        }
                    }
                    composable(NavRoutes.HOME) {
                        HomeScreen(
                            onAddApps = { navController.navigate(NavRoutes.ADD_APPS) },
                            onSettings = { navController.navigate(NavRoutes.SETTINGS) },
                            viewModel = viewModel,
                        )
                    }
                    composable(NavRoutes.ADD_APPS) {
                        AddAppsScreen(
                            onFilters = { navController.navigate(NavRoutes.ADD_APPS_FILTERS) },
                            viewModel = viewModel,
                        )
                    }
                    composable(NavRoutes.ADD_APPS_FILTERS) { FiltersScreen(viewModel) }
                    composable(NavRoutes.SETTINGS) {
                        SettingsScreen(
                            onAbout = { navController.navigate(NavRoutes.ABOUT) },
                            viewModel = viewModel,
                        )
                    }
                    composable(NavRoutes.ABOUT) {
                        AboutScreen(
                            onOpenLink = { url -> navController.navigate(NavRoutes.link(url)) },
                        )
                    }
                    composable(
                        route = NavRoutes.LINK,
                        arguments = listOf(navArgument("url") { type = NavType.StringType }),
                    ) { entry ->
                        LinkScreen(entry.arguments?.getString("url").orEmpty())
                    }
                }
            }
        }
    }

    /**
     * 三个 Shizuku 回调跟着界面可见性挂摘（含 binder 死亡）：
     * 挂在 ViewModel 上会活到进程结束，界面重建时旧监听还留着；
     * 不摘 binder-dead 的话，服务端掉了我们还在按「已就绪」显示。
     */
    override fun onStart() {
        super.onStart()
        viewModel.registerShizukuListeners()
    }

    override fun onStop() {
        viewModel.unregisterShizukuListeners()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 授权很可能是在 Shizuku 自己的界面里点的，回来不复查就会一直停在「未授权」
        viewModel.onForeground()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onBackground()
    }
}
