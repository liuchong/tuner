package com.liuchong.tuner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** Dark 配色 → Material3 角色映射（token 驱动，design-system §3）。 */
private val DarkScheme = darkColorScheme(
    primary = LumenDark.accent,
    onPrimary = LumenDark.bgCanvas,
    primaryContainer = LumenDark.bgSurfaceRaised,
    onPrimaryContainer = LumenDark.inkPrimary,
    secondaryContainer = LumenDark.accent.copy(alpha = 0.18f),
    onSecondaryContainer = LumenDark.inkPrimary,
    secondary = LumenDark.inkSecondary,
    onSecondary = LumenDark.bgCanvas,
    background = LumenDark.bgCanvas,
    onBackground = LumenDark.inkPrimary,
    surface = LumenDark.bgSurface,
    onSurface = LumenDark.inkPrimary,
    surfaceVariant = LumenDark.bgSurfaceRaised,
    onSurfaceVariant = LumenDark.inkSecondary,
    outline = LumenDark.lineSubtle,
    outlineVariant = LumenDark.lineSubtle,
    error = LumenDark.tuneOff,
)

/** Light 配色 → Material3 角色映射。 */
private val LightScheme = lightColorScheme(
    primary = LumenLight.accent,
    onPrimary = LumenLight.bgSurface,
    primaryContainer = LumenLight.bgSurfaceRaised,
    onPrimaryContainer = LumenLight.inkPrimary,
    secondaryContainer = LumenLight.accent.copy(alpha = 0.12f),
    onSecondaryContainer = LumenLight.accent,
    secondary = LumenLight.inkSecondary,
    onSecondary = LumenLight.bgSurface,
    background = LumenLight.bgCanvas,
    onBackground = LumenLight.inkPrimary,
    surface = LumenLight.bgSurface,
    onSurface = LumenLight.inkPrimary,
    surfaceVariant = LumenLight.bgSurfaceRaised,
    onSurfaceVariant = LumenLight.inkSecondary,
    outline = LumenLight.lineSubtle,
    outlineVariant = LumenLight.lineSubtle,
    error = LumenLight.tuneOff,
)

/**
 * 「Lumen / 微光」主题：token 经 [LocalLumenColors] 提供，
 * MaterialTheme colorScheme 同步映射（design-system §10）。
 */
@Composable
fun TunerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tokens = if (darkTheme) LumenDark else LumenLight
    CompositionLocalProvider(LocalLumenColors provides tokens) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            content = content,
        )
    }
}
