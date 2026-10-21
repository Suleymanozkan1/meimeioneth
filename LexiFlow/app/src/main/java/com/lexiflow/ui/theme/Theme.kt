package com.lexiflow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lexiflow.R

// Editorial palette: ink blue on warm paper, with vermilion, ochre, moss and slate accents.
val InkBlue = Color(0xFF2F3E9E)
val InkBlueDeep = Color(0xFF243285)
val Vermilion = Color(0xFFB8432C)
val Ochre = Color(0xFF9A6A12)
val Moss = Color(0xFF2F7A4E)
val Slate = Color(0xFF2E6A86)
val Ink = Color(0xFF1D1B16)
val Paper = Color(0xFFF6F2EA)

// Kept for existing call sites (mapped onto the editorial palette).
val Violet = InkBlue
val VioletDeep = InkBlueDeep
val Pink = Vermilion
val Coral = Vermilion
val Sunshine = Ochre
val Mint = Moss
val Sky = Slate
val Indigo = InkBlue
val Amber = Ochre
val Success = Moss
val Danger = Color(0xFFB3261E)
val Warning = Ochre

/** Gradient pairs used for hero headers and mode groups (subtle, nearly flat). */
@Immutable
data class Gradients(
    val hero: Brush,
    val violet: Brush,
    val sunset: Brush,
    val ocean: Brush,
    val mint: Brush,
    val berry: Brush,
    val fire: Brush,
)

private fun fill(top: Color, bottom: Color) = Brush.verticalGradient(listOf(top, bottom))

val BrandGradients = Gradients(
    hero = fill(Color(0xFF33439F), Color(0xFF283690)),
    violet = fill(Color(0xFF33439F), Color(0xFF283690)),
    sunset = fill(Color(0xFFB8432C), Color(0xFFA63B26)),
    ocean = fill(Color(0xFF2E6A86), Color(0xFF285E77)),
    mint = fill(Color(0xFF2F7A4E), Color(0xFF296C45)),
    berry = fill(Color(0xFF283690), Color(0xFF1F2B78)),
    fire = fill(Color(0xFF9A6A12), Color(0xFF8A5E0F)),
)

val LocalGradients = staticCompositionLocalOf { BrandGradients }

private val Light = lightColorScheme(
    primary = InkBlue, onPrimary = Color.White, primaryContainer = Color(0xFFE2E5F6), onPrimaryContainer = Color(0xFF16205E),
    secondary = Vermilion, onSecondary = Color.White, secondaryContainer = Color(0xFFF6E0D9), onSecondaryContainer = Color(0xFF4A160B),
    tertiary = Ochre, onTertiary = Color.White, tertiaryContainer = Color(0xFFF3E6CC), onTertiaryContainer = Color(0xFF3A2804),
    background = Paper, onBackground = Ink,
    surface = Color(0xFFFFFDF8), onSurface = Ink,
    surfaceVariant = Color(0xFFEDE7DB), onSurfaceVariant = Color(0xFF5C564B),
    surfaceContainer = Color(0xFFF1ECE2), surfaceContainerHigh = Color(0xFFEAE4D8), surfaceContainerHighest = Color(0xFFE3DCCE),
    surfaceContainerLow = Color(0xFFFBF8F2), surfaceContainerLowest = Color.White,
    outline = Color(0xFFCFC7B8), outlineVariant = Color(0xFFE4DDD0),
    error = Danger,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAEB8F2), onPrimary = Color(0xFF16205E), primaryContainer = Color(0xFF2C3879), onPrimaryContainer = Color(0xFFDEE2FA),
    secondary = Color(0xFFEFA38F), onSecondary = Color(0xFF4A160B), secondaryContainer = Color(0xFF5E2618), onSecondaryContainer = Color(0xFFF8DCD3),
    tertiary = Color(0xFFE2B865), onTertiary = Color(0xFF3A2804), tertiaryContainer = Color(0xFF52390A), onTertiaryContainer = Color(0xFFF5E3C0),
    background = Color(0xFF15140F), onBackground = Color(0xFFECE6DA),
    surface = Color(0xFF1C1B16), onSurface = Color(0xFFECE6DA),
    surfaceVariant = Color(0xFF2B2922), onSurfaceVariant = Color(0xFFB9B1A3),
    surfaceContainer = Color(0xFF22211B), surfaceContainerHigh = Color(0xFF2B2A23), surfaceContainerHighest = Color(0xFF35332C),
    surfaceContainerLow = Color(0xFF1A1914), surfaceContainerLowest = Color(0xFF100F0B),
    outline = Color(0xFF4D4940), outlineVariant = Color(0xFF36332C),
    error = Color(0xFFF2B8B5),
)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) = Font(
    res, weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Serif for headlines (Literata) and a neutral sans for UI and body text (Inter); both variable fonts. */
val Literata = FontFamily(variable(R.font.literata, 400), variable(R.font.literata, 500), variable(R.font.literata, 600), variable(R.font.literata, 700))
val Inter = FontFamily(variable(R.font.inter, 400), variable(R.font.inter, 500), variable(R.font.inter, 600), variable(R.font.inter, 700))

private val base = Typography()

private fun TextStyle.serif(weight: FontWeight, size: Int, line: Int, tracking: Double = 0.0) =
    copy(fontFamily = Literata, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

private fun TextStyle.sans(weight: FontWeight, size: Int, line: Int, tracking: Double = 0.0) =
    copy(fontFamily = Inter, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

val LexiTypography = Typography(
    displayLarge = base.displayLarge.serif(FontWeight.Medium, 54, 60, -0.5),
    displayMedium = base.displayMedium.serif(FontWeight.Medium, 44, 50, -0.4),
    displaySmall = base.displaySmall.serif(FontWeight.Medium, 36, 42, -0.3),
    headlineLarge = base.headlineLarge.serif(FontWeight.SemiBold, 30, 36, -0.2),
    headlineMedium = base.headlineMedium.serif(FontWeight.SemiBold, 26, 32, -0.2),
    headlineSmall = base.headlineSmall.serif(FontWeight.SemiBold, 23, 29),
    titleLarge = base.titleLarge.serif(FontWeight.SemiBold, 21, 27),
    titleMedium = base.titleMedium.sans(FontWeight.SemiBold, 16, 22),
    titleSmall = base.titleSmall.sans(FontWeight.SemiBold, 14, 20),
    bodyLarge = base.bodyLarge.sans(FontWeight.Normal, 16, 24),
    bodyMedium = base.bodyMedium.sans(FontWeight.Normal, 14, 21),
    bodySmall = base.bodySmall.sans(FontWeight.Normal, 13, 19),
    labelLarge = base.labelLarge.sans(FontWeight.SemiBold, 14, 20),
    labelMedium = base.labelMedium.sans(FontWeight.Medium, 12, 16, 0.2),
    labelSmall = base.labelSmall.sans(FontWeight.Medium, 11, 16, 0.3),
)

val LexiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * Fixed brand colours (no wallpaper-based dynamic colour) so the app always looks lively and consistent.
 */
@Composable
fun LexiFlowTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = LexiTypography, shapes = LexiShapes, content = content)
}
