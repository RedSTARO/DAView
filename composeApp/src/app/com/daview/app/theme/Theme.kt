package com.daview.app.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// DAView's static palette: violet for the main action, quiet lavender for navigation
// and selection, and warm amber for occasional markers. The hex colours are product
// choices; the roles and their foreground/background pairing come from Material 3.
private val VioletLight = Color(0xFFD0BCFF)
private val VioletDark = Color(0xFF65469C)
private val VioletContainer = Color(0xFFEBDEFF)
private val LavenderLight = Color(0xFFCCC2DC)
private val LavenderContainer = Color(0xFFE8DEF7)
private val AmberLight = Color(0xFFF6BD83)
private val AmberContainer = Color(0xFFFFDCBB)
private val DarkSurface = Color(0xFF15131B)
private val LightSurface = Color(0xFFFDF8FF)
private val OnDarkSurface = Color(0xFFEAE4F1)
private val OnLightSurface = Color(0xFF201A28)

val DaViewDarkColors: ColorScheme = darkColorScheme(
    primary = VioletLight,
    onPrimary = Color(0xFF362064),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = VioletContainer,
    inversePrimary = VioletDark,
    secondary = LavenderLight,
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4B4259),
    onSecondaryContainer = LavenderContainer,
    tertiary = AmberLight,
    onTertiary = Color(0xFF42280F),
    tertiaryContainer = Color(0xFF5E3E21),
    onTertiaryContainer = AmberContainer,
    background = DarkSurface,
    onBackground = OnDarkSurface,
    surface = DarkSurface,
    onSurface = OnDarkSurface,
    surfaceVariant = Color(0xFF4C4456),
    onSurfaceVariant = Color(0xFFCAC1D4),
    surfaceTint = VioletLight,
    inverseSurface = OnDarkSurface,
    inverseOnSurface = Color(0xFF332D3B),
    outline = Color(0xFF968EA2),
    outlineVariant = Color(0xFF4C4456),
    surfaceBright = Color(0xFF3B3644),
    surfaceDim = DarkSurface,
    surfaceContainerLowest = Color(0xFF0F0D15),
    surfaceContainerLow = Color(0xFF1D1A25),
    surfaceContainer = Color(0xFF221F2B),
    surfaceContainerHigh = Color(0xFF2D2837),
    surfaceContainerHighest = Color(0xFF383242),
    primaryFixed = VioletContainer,
    primaryFixedDim = VioletLight,
    onPrimaryFixed = Color(0xFF241047),
    onPrimaryFixedVariant = Color(0xFF4F378B),
    secondaryFixed = LavenderContainer,
    secondaryFixedDim = LavenderLight,
    onSecondaryFixed = Color(0xFF20182B),
    onSecondaryFixedVariant = Color(0xFF4B4259),
    tertiaryFixed = AmberContainer,
    tertiaryFixedDim = AmberLight,
    onTertiaryFixed = Color(0xFF2B1708),
    onTertiaryFixedVariant = Color(0xFF5E3E21)
)

val DaViewLightColors: ColorScheme = lightColorScheme(
    primary = VioletDark,
    onPrimary = Color.White,
    primaryContainer = VioletContainer,
    onPrimaryContainer = Color(0xFF4F378B),
    inversePrimary = VioletLight,
    secondary = Color(0xFF645B73),
    onSecondary = Color.White,
    secondaryContainer = LavenderContainer,
    onSecondaryContainer = Color(0xFF3B324A),
    tertiary = Color(0xFF804C24),
    onTertiary = Color.White,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = Color(0xFF603715),
    background = LightSurface,
    onBackground = OnLightSurface,
    surface = LightSurface,
    onSurface = OnLightSurface,
    surfaceVariant = Color(0xFFE8E0EE),
    onSurfaceVariant = Color(0xFF4C4456),
    surfaceTint = VioletDark,
    inverseSurface = Color(0xFF352F3D),
    inverseOnSurface = Color(0xFFF7F0FB),
    outline = Color(0xFF766D81),
    outlineVariant = Color(0xFFCFC5D6),
    surfaceBright = LightSurface,
    surfaceDim = Color(0xFFDDD6E3),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F2FB),
    surfaceContainer = Color(0xFFF2EBF7),
    surfaceContainerHigh = Color(0xFFECE5F1),
    surfaceContainerHighest = Color(0xFFE5DEEA),
    primaryFixed = VioletContainer,
    primaryFixedDim = VioletLight,
    onPrimaryFixed = Color(0xFF241047),
    onPrimaryFixedVariant = Color(0xFF4F378B),
    secondaryFixed = LavenderContainer,
    secondaryFixedDim = LavenderLight,
    onSecondaryFixed = Color(0xFF20182B),
    onSecondaryFixedVariant = Color(0xFF4B4259),
    tertiaryFixed = AmberContainer,
    tertiaryFixedDim = AmberLight,
    onTertiaryFixed = Color(0xFF2B1708),
    onTertiaryFixedVariant = Color(0xFF5E3E21)
)

// Use the library's exact type (including the emphasized counterparts) and shape
// tokens. Long CJK text reflows in the layouts rather than changing the type scale.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val DaViewTypography = Typography()
private val DaViewShapes = Shapes()

val LocalDarkTheme = staticCompositionLocalOf { true }

val favoriteColor: Color
    @Composable get() = MaterialTheme.colorScheme.tertiary

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DaViewTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialExpressiveTheme(
            colorScheme = if (darkTheme) DaViewDarkColors else DaViewLightColors,
            typography = DaViewTypography,
            shapes = DaViewShapes,
            motionScheme = MotionScheme.expressive(),
            content = content
        )
    }
}
