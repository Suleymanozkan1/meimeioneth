package com.lexiflow.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val Indigo = Color(0xFF4F46E5)
val Amber = Color(0xFFF59E0B)
val Success = Color(0xFF16A34A)
val Danger = Color(0xFFDC2626)
val Warning = Color(0xFFD97706)

private val Light = lightColorScheme(
    primary = Indigo, onPrimary = Color.White, secondary = Color(0xFF0EA5E9), tertiary = Amber,
    background = Color(0xFFF8F6FF), surface = Color.White, surfaceVariant = Color(0xFFEDE9FE),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA5B4FC), onPrimary = Color(0xFF1E1B4B), secondary = Color(0xFF7DD3FC), tertiary = Color(0xFFFCD34D),
    background = Color(0xFF121018), surface = Color(0xFF1C1A24), surfaceVariant = Color(0xFF2A2638),
)

@Composable
fun LexiFlowTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
