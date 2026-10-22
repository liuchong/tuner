package com.liuchong.tunar.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.liuchong.tunar.audio.AudioTrackReferenceTonePlayer
import com.liuchong.tunar.ui.practice.PracticeWorkspaceViewModel
import com.liuchong.tunar.ui.practice.PracticeWorkspaceScreen
import com.liuchong.tunar.ui.practice.PracticePresentation
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.liuchong.tunar.ui.instrument.InstrumentScreen
import com.liuchong.tunar.ui.metronome.MetronomeScreen
import com.liuchong.tunar.ui.settings.SettingsScreen
import com.liuchong.tunar.ui.spectrum.ProfessionalSpectrumScreen
import com.liuchong.tunar.ui.theme.LocalLumenColors
import com.liuchong.tunar.ui.tools.ToolsScreen
import com.liuchong.tunar.ui.tuner.TunerScreen

/** 底部 tab 定义（spec-ui：调音 / 乐器 / 频谱 / 节拍器 / 设置）。 */
enum class AppTab(val route: String, val label: String, val icon: ImageVector) {
    TUNER("tuner", "调音", Icons.Filled.MusicNote),
    INSTRUMENT("instrument", "乐器", Icons.Filled.Piano),
    SPECTRUM("spectrum", "频谱", Icons.Filled.GraphicEq),
    METRONOME("metronome", "节拍器", Icons.Filled.Timer),
    SETTINGS("settings", "设置", Icons.Filled.Settings),
}

/** 小工具页从设置进入，不占底部 tab；底栏仍高亮「设置」。 */
const val TOOLS_ROUTE = "tools"

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val context = LocalContext.current
    val practice: PracticeWorkspaceViewModel = viewModel(viewModelStoreOwner = context as ComponentActivity)
    val player = remember { AudioTrackReferenceTonePlayer(context.applicationContext) }
    DisposableEffect(practice) {
        practice.setPlayer(player)
        onDispose { practice.suspend() }
    }
    fun updateCapture() {
        com.liuchong.tunar.audio.CaptureHub.setEnabled(PracticePresentation.isCaptureRoute(currentRoute))
        if (PracticePresentation.isCaptureRoute(currentRoute) && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) practice.acquire()
        else practice.suspend()
    }
    LaunchedEffect(currentRoute) { updateCapture() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { updateCapture() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { practice.suspend(); com.liuchong.tunar.audio.CaptureHub.setEnabled(false) }
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
                        selected = currentRoute == tab.route ||
                            (tab == AppTab.SETTINGS && currentRoute == TOOLS_ROUTE) || (tab == AppTab.TUNER && currentRoute == "practice"),
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
            composable(AppTab.TUNER.route) {
                TunerScreen(onOpenPractice = { navController.navigate("practice") { launchSingleTop = true } }, onOpenSpectrum = {
                    navController.navigate(AppTab.SPECTRUM.route) {
                        launchSingleTop = true
                    }
                })
            }
            composable(AppTab.INSTRUMENT.route) { InstrumentScreen(onPractice = { midi, instrument, variant, fingering ->
                practice.lockInstrument(midi, instrument, variant, fingering)
                navController.navigate("practice") { launchSingleTop = true }
            }) }
            composable("practice") { PracticeWorkspaceScreen(practice, onBack = { navController.popBackStack() }, onAnalysis = {
                navController.navigate(AppTab.SPECTRUM.route) { launchSingleTop = true }
            }) }
            composable(AppTab.SPECTRUM.route) { ProfessionalSpectrumScreen() }
            composable(AppTab.METRONOME.route) { MetronomeScreen() }
            composable(AppTab.SETTINGS.route) {
                SettingsScreen(onOpenTools = {
                    navController.navigate(TOOLS_ROUTE) { launchSingleTop = true }
                })
            }
            composable(TOOLS_ROUTE) { ToolsScreen(onBack = navController::popBackStack) }
        }
    }
}
