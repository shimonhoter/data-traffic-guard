package com.shimonhoter.datatrafficguard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GuardBlue = Color(0xFF3B82F6)
private val GuardGreen = Color(0xFF22C55E)
private val GuardRed = Color(0xFFEF4444)

private val DarkColors = darkColorScheme(
    primary = GuardBlue,
    secondary = GuardGreen,
    error = GuardRed
)

private val LightColors = lightColorScheme(
    primary = GuardBlue,
    secondary = GuardGreen,
    error = GuardRed
)

@Composable
fun DataTrafficGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
