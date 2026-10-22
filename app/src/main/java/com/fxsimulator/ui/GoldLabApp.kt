package com.fxsimulator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fxsimulator.ui.screens.AboutScreen
import com.fxsimulator.ui.screens.DataScreen
import com.fxsimulator.ui.screens.GuessScreen
import com.fxsimulator.ui.screens.HistoryScreen
import com.fxsimulator.ui.screens.EventGameScreen
import com.fxsimulator.ui.screens.FixedOddsGameScreen
import com.fxsimulator.ui.screens.GamesHubScreen
import com.fxsimulator.ui.screens.HomeScreen
import com.fxsimulator.ui.screens.OptionGameScreen
import com.fxsimulator.ui.screens.IndicatorScreen
import com.fxsimulator.ui.screens.LogScreen
import com.fxsimulator.ui.screens.SettingsScreen
import com.fxsimulator.ui.screens.TradeTabScreen
import com.fxsimulator.ui.theme.GoldLabColors
import com.fxsimulator.viewmodel.GameViewModel

/** 底部导航项 */
private data class NavItem(val route: String, val label: String, val icon: ImageVector)

private val bottomItems = listOfNotNull(
    NavItem("home", "首页", Icons.Filled.Home),
    // 玩法放在最显眼的位置：这个 App 首先是个游戏。
    // 但玩法正在重做，入口由 FeatureFlags.GUESS_ENABLED 控制（false 时整项不出现）。
    if (FeatureFlags.GUESS_ENABLED) NavItem("guess", "猜涨跌", Icons.Filled.PlayArrow) else null,
    // 图表与下单合并为同一个 tab
    NavItem("trade", "交易", Icons.Filled.List),
    // 「统计」原先是一个独立 tab，现已并入首页（HomeScreen 内的 StatsSection），
    // 因此这里不再有 stats 入口与对应路由。
    NavItem("games", "玩法", Icons.Filled.Star),
            NavItem("settings", "设置", Icons.Filled.Settings),
)

/**
 * 应用级界面宿主：单一 ViewModel + 单一回放引擎，页面之间共享同一份状态。
 * 通过 activity 级 ViewModel 注入，避免每个页面各建一个引擎（会造成多个回放任务）。
 */
@Composable
fun GoldLabApp(vm: GameViewModel) {
    val ui by vm.ui.collectAsState()
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.message?.id) {
        val m = ui.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m.text)
        vm.consumeMessage()
    }

    Scaffold(
        containerColor = GoldLabColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { BottomBar(nav) },
    ) { inner ->
        Box(
            Modifier
                .background(GoldLabColors.Background)
                .padding(inner),
        ) {
            if (ui.loading) {
                // 首次启动 / 重新载入大文件时（例如 127MB 的 CSV 要重新建索引），
                // 必须明确告诉用户正在加载，否则界面静止十几秒会被误认为卡死。
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = GoldLabColors.Gold)
                    Spacer(Modifier.height(14.dp))
                    Text("正在加载历史行情…", color = GoldLabColors.TextPrimary, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "首次载入大文件需要建立索引，可能要十几秒",
                        color = GoldLabColors.TextDisabled,
                        fontSize = 11.sp,
                    )
                }
                return@Box
            }
            NavHost(nav, startDestination = "home") {
                composable("home") { HomeScreen(ui, nav) }
                // 路由也一起收：只藏标签页的话，旧的深链接/首页卡片仍能把人带进来
                if (FeatureFlags.GUESS_ENABLED) {
                    composable("guess") { GuessScreen(vm, nav) }
                }
                composable("trade") { TradeTabScreen(vm, ui, nav) }


                composable("games") { GamesHubScreen(vm, nav) }
                composable("game-event") { EventGameScreen(vm, nav) }
                composable("game-option") { OptionGameScreen(vm, nav) }
                composable("game-fixed") { FixedOddsGameScreen(vm, nav) }
                composable("settings") { SettingsScreen(vm, ui, nav) }
                composable("about") { AboutScreen(vm, ui, nav) }
                composable("history") { HistoryScreen(vm, ui) }
                composable("logs") { LogScreen(vm, ui) }
                composable("data") { DataScreen(vm, ui, nav) }
                composable("indicators") { IndicatorScreen(vm, ui, nav) }
            }
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    NavigationBar(containerColor = GoldLabColors.Surface) {
        for (item in bottomItems) {
            val selected = current == item.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        nav.navigate(item.route) {
                            popUpTo("home") { inclusive = item.route == "home" }
                            launchSingleTop = true
                        }
                    }
                },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = GoldLabColors.Gold,
                    selectedTextColor = GoldLabColors.Gold,
                    unselectedIconColor = GoldLabColors.TextSecondary,
                    unselectedTextColor = GoldLabColors.TextSecondary,
                    indicatorColor = GoldLabColors.SurfaceVariant,
                ),
            )
        }
    }
}
