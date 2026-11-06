package org.orcapod.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OrcaGreen = Color(0xFF00AE42)

private val Dark = darkColorScheme(
    primary = OrcaGreen,
    onPrimary = Color.White,
    secondary = Color(0xFF7FD6A0),
    surface = Color(0xFF1C1D20),
    background = Color(0xFF141518),
)

private val Light = lightColorScheme(
    primary = Color(0xFF009639),
    onPrimary = Color.White,
    secondary = Color(0xFF2E7D4F),
)

@Composable
fun OrcaPodTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
