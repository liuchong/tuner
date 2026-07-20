package com.liuchong.tuner.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.liuchong.tuner.ui.instrument.InstrumentScreen
import com.liuchong.tuner.ui.metronome.MetronomeScreen
import com.liuchong.tuner.ui.settings.SettingsScreen
import com.liuchong.tuner.ui.theme.LocalLumenColors
import com.liuchong.tuner.ui.tuner.TunerScreen

/** 底部 tab 定义（spec-ui：调音 / 乐器 / 节拍器 / 设置）。 */
enum class AppTab(val route: String, val label: String, val icon: ImageVector) {
    TUNER("tuner", "调音", Icons.Filled.MusicNote),
    INSTRUMENT("instrument", "乐器", Icons.Filled.Piano),
    METRONOME("metronome", "节拍器", Icons.Filled.Timer),
    SETTINGS("settings", "设置", Icons.Filled.Settings),
}

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val colors = LocalLumenColors.current
    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = colors.bgCanvas,
                tonalElevation = 0.dp,
            ) {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.accent,
                            selectedTextColor = colors.accent,
                            unselectedIconColor = colors.inkSecondary,
                            unselectedTextColor = colors.inkSecondary,
                            indicatorColor = colors.accent.copy(alpha = 0.12f),
                        ),
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppTab.TUNER.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(AppTab.TUNER.route) { TunerScreen() }
            composable(AppTab.INSTRUMENT.route) { InstrumentScreen() }
            composable(AppTab.METRONOME.route) { MetronomeScreen() }
            composable(AppTab.SETTINGS.route) { SettingsScreen() }
        }
    }
}
