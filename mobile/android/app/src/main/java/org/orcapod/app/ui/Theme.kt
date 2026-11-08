package org.orcapod.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// The app's palette matches the launcher icon: OrcaGreen as the primary, the
// icon's deep green for containers and its lime "fresh layer" as the accent.
private val OrcaGreen = Color(0xFF00AE42)
private val DeepGreen = Color(0xFF0E5C3A)
private val Lime = Color(0xFFC6FF6B)

private val Dark = darkColorScheme(
    primary = OrcaGreen,
    onPrimary = Color.White,
    primaryContainer = DeepGreen,
    onPrimaryContainer = Color(0xFFB8F5C9),
    inversePrimary = Color(0xFF00873A),
    secondary = Color(0xFFA8CFB2),
    onSecondary = Color(0xFF133723),
    secondaryContainer = Color(0xFF2A4E38),
    onSecondaryContainer = Color(0xFFC4EBCD),
    tertiary = Lime,
    onTertiary = Color(0xFF253500),
    tertiaryContainer = Color(0xFF3A5200),
    onTertiaryContainer = Color(0xFFD9FF9E),
    background = Color(0xFF111413),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF111413),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFC0C9C0),
    surfaceTint = OrcaGreen,
    outline = Color(0xFF8A938B),
    outlineVariant = Color(0xFF404943),
    surfaceDim = Color(0xFF111413),
    surfaceBright = Color(0xFF373A38),
    surfaceContainerLowest = Color(0xFF0C0F0D),
    surfaceContainerLow = Color(0xFF191C1A),
    surfaceContainer = Color(0xFF1D201E),
    surfaceContainerHigh = Color(0xFF272B28),
    surfaceContainerHighest = Color(0xFF323533),
)

private val Light = lightColorScheme(
    primary = Color(0xFF00873A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F5C9),
    onPrimaryContainer = Color(0xFF00210C),
    inversePrimary = Color(0xFF6FDD8B),
    secondary = Color(0xFF4F6354),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1E8D5),
    onSecondaryContainer = Color(0xFF0C1F13),
    tertiary = Color(0xFF4A6800),
    onTertiary = Color.White,
    tertiaryContainer = Lime,
    onTertiaryContainer = Color(0xFF141F00),
    background = Color(0xFFF6FBF4),
    onBackground = Color(0xFF181D19),
    surface = Color(0xFFF6FBF4),
    onSurface = Color(0xFF181D19),
    surfaceVariant = Color(0xFFDCE5DB),
    onSurfaceVariant = Color(0xFF414942),
    surfaceTint = Color(0xFF00873A),
    outline = Color(0xFF717971),
    outlineVariant = Color(0xFFC0C9C0),
    surfaceDim = Color(0xFFD6DBD5),
    surfaceBright = Color(0xFFF6FBF4),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F5EE),
    surfaceContainer = Color(0xFFEAEFE8),
    surfaceContainerHigh = Color(0xFFE5EAE3),
    surfaceContainerHighest = Color(0xFFDFE4DD),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun OrcaPodTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = AppShapes,
        content = content,
    )
}
