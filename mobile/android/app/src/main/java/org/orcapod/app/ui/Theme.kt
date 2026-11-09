package org.orcapod.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// The Workbench palette: deep navy surfaces with a cyan accent, one dark
// scheme on every device so the 3D plate always sits on the same background.
private val Cyan = Color(0xFF22D3EE)
private val Navy = Color(0xFF0B1220)

private val Workbench = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF04212A),
    primaryContainer = Color(0xFF0E3A4A),
    onPrimaryContainer = Color(0xFFB9F2FB),
    inversePrimary = Color(0xFF0891B2),
    secondary = Color(0xFF9FB3D1),
    onSecondary = Color(0xFF14233D),
    secondaryContainer = Color(0xFF22314F),
    onSecondaryContainer = Color(0xFFD6E2F5),
    tertiary = Color(0xFFF59E0B),
    onTertiary = Color(0xFF2A1A00),
    tertiaryContainer = Color(0xFF4A3300),
    onTertiaryContainer = Color(0xFFFFDDB0),
    background = Navy,
    onBackground = Color(0xFFE6EDF7),
    surface = Navy,
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF1A2540),
    onSurfaceVariant = Color(0xFF8F9BB3),
    surfaceTint = Cyan,
    outline = Color(0xFF55627E),
    outlineVariant = Color(0xFF24324F),
    surfaceDim = Navy,
    surfaceBright = Color(0xFF26324D),
    surfaceContainerLowest = Color(0xFF070C16),
    surfaceContainerLow = Color(0xFF0F1727),
    surfaceContainer = Color(0xFF121B2E),
    surfaceContainerHigh = Color(0xFF1A2540),
    surfaceContainerHighest = Color(0xFF22304F),
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
        colorScheme = Workbench,
        shapes = AppShapes,
        content = content,
    )
}
