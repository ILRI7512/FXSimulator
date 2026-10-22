package com.fxsimulator.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * GoldLab 固定使用深色配色：金融终端在深色下对比度更好，也不刺眼。
 */
private val GoldLabDarkScheme = darkColorScheme(
    primary = GoldLabColors.Gold,
    onPrimary = GoldLabColors.Background,
    primaryContainer = GoldLabColors.GoldDim,
    onPrimaryContainer = GoldLabColors.Gold,
    secondary = GoldLabColors.Info,
    onSecondary = GoldLabColors.TextPrimary,
    background = GoldLabColors.Background,
    onBackground = GoldLabColors.TextPrimary,
    surface = GoldLabColors.Surface,
    onSurface = GoldLabColors.TextPrimary,
    surfaceVariant = GoldLabColors.SurfaceVariant,
    onSurfaceVariant = GoldLabColors.TextSecondary,
    outline = GoldLabColors.Outline,
    error = GoldLabColors.Down,
    onError = GoldLabColors.TextPrimary,
)

@Composable
fun GoldLabTheme(content: @Composable () -> Unit) {
    // isSystemInDarkTheme() 仅用于保留扩展点；当前始终使用深色方案
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = GoldLabDarkScheme,
        typography = GoldLabTypography,
        content = content,
    )
}
