package com.liuchong.tunar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuchong.tunar.audio.CaptureHub
import com.liuchong.tunar.data.DataStoreSettingsRepository
import com.liuchong.tunar.data.ThemeMode
import com.liuchong.tunar.ui.common.LocalHapticsEnabled
import com.liuchong.tunar.ui.navigation.AppNav
import com.liuchong.tunar.ui.settings.SettingsViewModel
import com.liuchong.tunar.ui.theme.TunarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // activity 级 SettingsViewModel：主题设置即时生效；
            // 设置页复用同一实例（activity 作用域）
            val appContext = LocalContext.current.applicationContext
            val settingsVm: SettingsViewModel = viewModel(initializer = {
                SettingsViewModel(
                    repo = DataStoreSettingsRepository(appContext),
                    configSink = CaptureHub,
                )
            })
            val settings by settingsVm.settings.collectAsStateWithLifecycle()
            TunarTheme(
                darkTheme = when (settings.theme) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
            ) {
                CompositionLocalProvider(
                    LocalHapticsEnabled provides settings.hapticsEnabled,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        AppNav()
                    }
                }
            }
        }
    }
}
