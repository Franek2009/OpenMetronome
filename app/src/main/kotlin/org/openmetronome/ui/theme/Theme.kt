package org.openmetronome.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val InstrumentColors = darkColorScheme(
    primary = Color(0xFF8FB89B), onPrimary = Color(0xFF102B1B),
    primaryContainer = Color(0xFF214D37), onPrimaryContainer = Color(0xFFD2E1D6),
    secondary = Color(0xFFA6B5AB), onSecondary = Color(0xFF202B24),
    secondaryContainer = Color(0xFF344B3C), onSecondaryContainer = Color(0xFFD3DED5),
    tertiary = Color(0xFFAEB9A7), onTertiary = Color(0xFF233020),
    tertiaryContainer = Color(0xFF36442E), onTertiaryContainer = Color(0xFFD7E0D0),
    background = Color(0xFF222421), onBackground = Color(0xFFE0E2DB),
    surface = Color(0xFF222421), onSurface = Color(0xFFE0E2DB),
    surfaceVariant = Color(0xFF343A34), onSurfaceVariant = Color(0xFFBAC2B9),
    surfaceContainer = Color(0xFF292D29), surfaceContainerHigh = Color(0xFF333833),
    surfaceContainerLowest = Color(0xFF171A17), surfaceContainerLow = Color(0xFF242824),
    surfaceContainerHighest = Color(0xFF394039), surfaceDim = Color(0xFF1B1E1B), surfaceBright = Color(0xFF3B403B),
    surfaceTint = Color(0xFF8FB89B), outlineVariant = Color(0xFF414C43),
    outline = Color(0xFF819085), error = Color(0xFFECB3A9),
)

/** Deliberately dark-only: a quiet instrument panel, independent of system theme. */
@Composable
fun OpenMetronomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = InstrumentColors, content = content)
}
