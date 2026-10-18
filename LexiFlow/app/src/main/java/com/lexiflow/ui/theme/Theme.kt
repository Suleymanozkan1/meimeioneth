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

// Brand palette: vivid violet + pink + coral + sunshine + mint + sky.
val Violet = Color(0xFF6C3BFF)
val VioletDeep = Color(0xFF4B1FD1)
val Pink = Color(0xFFFF4D9D)
val Coral = Color(0xFFFF6B5B)
val Sunshine = Color(0xFFFFB627)
val Mint = Color(0xFF12C99B)
val Sky = Color(0xFF1FA2FF)
val Ink = Color(0xFF1B1530)

// Kept for existing call sites.
val Indigo = Violet
val Amber = Sunshine
val Success = Color(0xFF12B886)
val Danger = Color(0xFFF03E5E)
val Warning = Color(0xFFF59F00)

/** Gradient pairs used for hero headers and mode groups. */
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

private fun h(a: Color, b: Color, c: Color? = null) = Brush.linearGradient(listOfNotNull(a, b, c))

val BrandGradients = Gradients(
    hero = h(Color(0xFF6C3BFF), Color(0xFFB13BFF), Color(0xFFFF4D9D)),
    violet = h(Color(0xFF7B5CFF), Color(0xFF5B2EE6)),
    sunset = h(Color(0xFFFF8A4C), Color(0xFFFF4D9D)),
    ocean = h(Color(0xFF1FA2FF), Color(0xFF12D8FA)),
    mint = h(Color(0xFF12C99B), Color(0xFF0BAB64)),
    berry = h(Color(0xFFE040FB), Color(0xFF7C4DFF)),
    fire = h(Color(0xFFFFB627), Color(0xFFFF6B5B)),
)

val LocalGradients = staticCompositionLocalOf { BrandGradients }

private val Light = lightColorScheme(
    primary = Violet, onPrimary = Color.White, primaryContainer = Color(0xFFE9E0FF), onPrimaryContainer = Color(0xFF22005D),
    secondary = Pink, onSecondary = Color.White, secondaryContainer = Color(0xFFFFE0EE), onSecondaryContainer = Color(0xFF3E0021),
    tertiary = Sunshine, onTertiary = Ink, tertiaryContainer = Color(0xFFFFEFC7), onTertiaryContainer = Color(0xFF2A1A00),
    background = Color(0xFFF6F3FF), onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    surfaceVariant = Color(0xFFF0EBFF), onSurfaceVariant = Color(0xFF4A4460),
    surfaceContainer = Color(0xFFF3EEFF), surfaceContainerHigh = Color(0xFFECE5FF), surfaceContainerLow = Color(0xFFFAF8FF),
    outline = Color(0xFFCFC6E8), outlineVariant = Color(0xFFE4DDF5),
    error = Danger,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB9A3FF), onPrimary = Color(0xFF2C0A80), primaryContainer = Color(0xFF4520B8), onPrimaryContainer = Color(0xFFEDE5FF),
    secondary = Color(0xFFFF8DC0), onSecondary = Color(0xFF5A0031), secondaryContainer = Color(0xFF7A1F4E), onSecondaryContainer = Color(0xFFFFD9E8),
    tertiary = Color(0xFFFFCA5F), onTertiary = Color(0xFF3A2600), tertiaryContainer = Color(0xFF5C4300), onTertiaryContainer = Color(0xFFFFEBC2),
    background = Color(0xFF110C22), onBackground = Color(0xFFEDE8FF),
    surface = Color(0xFF1A1430), onSurface = Color(0xFFEDE8FF),
    surfaceVariant = Color(0xFF261E42), onSurfaceVariant = Color(0xFFCBC2E6),
    surfaceContainer = Color(0xFF1F1838), surfaceContainerHigh = Color(0xFF2A2248), surfaceContainerLow = Color(0xFF17112B),
    outline = Color(0xFF5B5178), outlineVariant = Color(0xFF3A3256),
    error = Color(0xFFFF6B81),
)

@OptIn(ExperimentalTextApi::class)
private fun nunito(weight: Int) = Font(
    R.font.nunito, weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Nunito = FontFamily(nunito(400), nunito(500), nunito(600), nunito(700), nunito(800), nunito(900))

private val base = Typography()
private fun TextStyle.n(weight: FontWeight? = null) = copy(fontFamily = Nunito, fontWeight = weight ?: fontWeight)

val LexiTypography = Typography(
    displayLarge = base.displayLarge.n(FontWeight.Black),
    displayMedium = base.displayMedium.n(FontWeight.Black),
    displaySmall = base.displaySmall.n(FontWeight.ExtraBold),
    headlineLarge = base.headlineLarge.n(FontWeight.ExtraBold),
    headlineMedium = base.headlineMedium.n(FontWeight.ExtraBold),
    headlineSmall = base.headlineSmall.n(FontWeight.ExtraBold),
    titleLarge = base.titleLarge.n(FontWeight.ExtraBold),
    titleMedium = base.titleMedium.n(FontWeight.Bold),
    titleSmall = base.titleSmall.n(FontWeight.Bold),
    bodyLarge = base.bodyLarge.n(FontWeight.Medium).copy(lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.n(FontWeight.Medium),
    bodySmall = base.bodySmall.n(FontWeight.Medium),
    labelLarge = base.labelLarge.n(FontWeight.Bold),
    labelMedium = base.labelMedium.n(FontWeight.Bold),
    labelSmall = base.labelSmall.n(FontWeight.Bold),
)

val LexiShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Fixed brand colours (no wallpaper-based dynamic colour) so the app always looks lively and consistent.
 */
@Composable
fun LexiFlowTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = LexiTypography, shapes = LexiShapes, content = content)
}
