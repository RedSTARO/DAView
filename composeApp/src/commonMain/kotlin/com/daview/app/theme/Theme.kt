package com.daview.app.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A single expressive palette in two tones. The accent is a saturated violet
 * that reads well over poster art, with a warm secondary used for progress and
 * "continue watching" affordances.
 */
private val Violet = Color(0xFF6C4BF6)
private val VioletLight = Color(0xFFCFC0FF)
private val Amber = Color(0xFFFFB35C)

val DaViewDarkColors: ColorScheme = ColorScheme(
    primary = Color(0xFFC9BCFF),
    onPrimary = Color(0xFF33128F),
    primaryContainer = Color(0xFF4B2CD6),
    onPrimaryContainer = Color(0xFFE8DEFF),
    inversePrimary = Violet,
    secondary = Color(0xFFFFC98A),
    onSecondary = Color(0xFF4A2800),
    secondaryContainer = Color(0xFF693C00),
    onSecondaryContainer = Color(0xFFFFDDB8),
    tertiary = Color(0xFF7DD8C0),
    onTertiary = Color(0xFF00382C),
    tertiaryContainer = Color(0xFF005141),
    onTertiaryContainer = Color(0xFF9AF4DC),
    background = Color(0xFF0E0D14),
    onBackground = Color(0xFFE7E2EC),
    surface = Color(0xFF0E0D14),
    onSurface = Color(0xFFE7E2EC),
    surfaceVariant = Color(0xFF2B2735),
    onSurfaceVariant = Color(0xFFC9C2D6),
    surfaceTint = Color(0xFFC9BCFF),
    inverseSurface = Color(0xFFE7E2EC),
    inverseOnSurface = Color(0xFF322F38),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF938DA0),
    outlineVariant = Color(0xFF48434F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF35323B),
    surfaceDim = Color(0xFF0E0D14),
    surfaceContainer = Color(0xFF1A1822),
    surfaceContainerHigh = Color(0xFF24212D),
    surfaceContainerHighest = Color(0xFF2F2B38),
    surfaceContainerLow = Color(0xFF15131C),
    surfaceContainerLowest = Color(0xFF09080E),
    primaryFixed = Color(0xFFE8DEFF),
    primaryFixedDim = Color(0xFFC9BCFF),
    onPrimaryFixed = Color(0xFF200066),
    onPrimaryFixedVariant = Color(0xFF4B2CD6),
    secondaryFixed = Color(0xFFFFDDB8),
    secondaryFixedDim = Color(0xFFFFC98A),
    onSecondaryFixed = Color(0xFF2A1700),
    onSecondaryFixedVariant = Color(0xFF693C00),
    tertiaryFixed = Color(0xFF9AF4DC),
    tertiaryFixedDim = Color(0xFF7DD8C0),
    onTertiaryFixed = Color(0xFF00201A),
    onTertiaryFixedVariant = Color(0xFF005141)
)

val DaViewLightColors: ColorScheme = ColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DEFF),
    onPrimaryContainer = Color(0xFF200066),
    inversePrimary = Color(0xFFC9BCFF),
    secondary = Color(0xFF8A5000),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDB8),
    onSecondaryContainer = Color(0xFF2A1700),
    tertiary = Color(0xFF006A56),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF9AF4DC),
    onTertiaryContainer = Color(0xFF00201A),
    background = Color(0xFFFDF8FF),
    onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFFDF8FF),
    onSurface = Color(0xFF1C1B20),
    surfaceVariant = Color(0xFFE6E0EC),
    onSurfaceVariant = Color(0xFF48434F),
    surfaceTint = Violet,
    inverseSurface = Color(0xFF322F38),
    inverseOnSurface = Color(0xFFF5EFF7),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF7A7480),
    outlineVariant = Color(0xFFCAC4D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFDF8FF),
    surfaceDim = Color(0xFFDDD8E0),
    surfaceContainer = Color(0xFFF1ECF4),
    surfaceContainerHigh = Color(0xFFEBE6EE),
    surfaceContainerHighest = Color(0xFFE5E0E9),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainerLowest = Color.White,
    primaryFixed = Color(0xFFE8DEFF),
    primaryFixedDim = VioletLight,
    onPrimaryFixed = Color(0xFF200066),
    onPrimaryFixedVariant = Color(0xFF4B2CD6),
    secondaryFixed = Color(0xFFFFDDB8),
    secondaryFixedDim = Amber,
    onSecondaryFixed = Color(0xFF2A1700),
    onSecondaryFixedVariant = Color(0xFF693C00),
    tertiaryFixed = Color(0xFF9AF4DC),
    tertiaryFixedDim = Color(0xFF7DD8C0),
    onTertiaryFixed = Color(0xFF00201A),
    onTertiaryFixedVariant = Color(0xFF005141)
)

/** Expressive shapes lean on larger, less uniform radii than the baseline set. */
private val DaViewShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(36.dp)
)

val LocalDarkTheme = staticCompositionLocalOf { true }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DaViewTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialExpressiveTheme(
            colorScheme = if (darkTheme) DaViewDarkColors else DaViewLightColors,
            shapes = DaViewShapes,
            motionScheme = MotionScheme.expressive(),
            content = content
        )
    }
}
