package com.sievex.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Canvas = Color(0xFF121314)
val Surface1 = Color(0xFF1C1D1E)
val Surface2 = Color(0xFF252628)
val Line = Color(0xFF313336)
val Ink = Color(0xFFF5F6F8)
val Ink2 = Color(0xFFA9ABAF)
val PlaceholderInk = Color(0xFF8E9095)
val Signal = Color(0xFFF44C26)
val SignalFill = Color(0xFFC93412)
val SignalText = Color(0xFFFF7A59)
val SignalSoft = Color(0xFF4A241B)
val Blue = Color(0xFF6AB1F3)
val BlueText = Color(0xFF7EC0FA)
val BlueSoft = Color(0xFF162637)
val Mint = Color(0xFF4BCFB6)
val Green = Color(0xFF60CB7C)
val Amber = Color(0xFFF5B34C)
val Red = Color(0xFFF86F69)
val RedFill = Color(0xFFC93B36)

private val scheme = darkColorScheme(
    primary = SignalFill,
    onPrimary = Color.White,
    secondary = Blue,
    onSecondary = Color(0xFF001D34),
    tertiary = Mint,
    onTertiary = Color(0xFF00382F),
    background = Canvas,
    onBackground = Ink,
    surface = Surface1,
    onSurface = Ink,
    surfaceVariant = Surface2,
    onSurfaceVariant = Ink2,
    outline = Line,
    error = Red
)

private val type = Typography(
    displaySmall = TextStyle(
        fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.6).sp, lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontSize = 26.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp, lineHeight = 34.sp
    ),
    headlineSmall = TextStyle(
        fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp
    ),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(
        fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp
    ),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun SievexTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
