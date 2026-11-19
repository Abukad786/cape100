package com.cape100.coach.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Red & black palette.
val Red = Color(0xFFE11D2E)
val RedBright = Color(0xFFFF3B4E)
val RedDeep = Color(0xFF7F0A14)
val Black = Color(0xFF000000)

// Accent names kept for screens that use them.
val Ocean = Red
val OceanLight = RedBright
val Fynbos = RedDeep
val Protea = RedBright

/** Gradient for hero banners: bright red fading into black. */
val HeroGradient = listOf(Red, RedDeep, Black)

private val RedBlack = darkColorScheme(
    primary = Red,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A0A0E),
    onPrimaryContainer = Color(0xFFFFD9DC),
    secondary = RedBright,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2A0B0E),
    onSecondaryContainer = Color(0xFFFFD9DC),
    tertiary = Color(0xFFB91C1C),
    background = Black,
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF0F0F11),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1B1B1F),
    onSurfaceVariant = Color(0xFFB4B4BA),
    surfaceContainer = Color(0xFF151518),
    outline = Color(0xFF3A3A40),
    error = Color(0xFFFF6B6B),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Black, fontSize = 40.sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.8.sp),
)

/** Always red-on-black, regardless of the phone's light/dark setting. */
@Composable
fun CoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RedBlack, typography = AppTypography, content = content)
}
