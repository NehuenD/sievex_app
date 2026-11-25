package com.sievex.app.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

enum class ThemeMode { SYSTEM, DARK, LIGHT }

object ThemePrefs {
    private const val FILE = "sievex_theme"
    private const val KEY = "mode"

    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    private var restored = false

    fun restore(context: Context) {
        if (restored) return
        val stored = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, null)
        mode = ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
        restored = true
    }

    fun set(context: Context, next: ThemeMode) {
        mode = next
        restored = true
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, next.name)
            .apply()
    }
}

@Immutable
data class FolioPalette(
    val isDark: Boolean,
    val canvas: Color,
    val surface1: Color,
    val surface2: Color,
    val line: Color,
    val lineStrong: Color,
    val navBar: Color,
    val scrimAlpha: Float,
    val ink: Color,
    val ink2: Color,
    val placeholderInk: Color,
    val signal: Color,
    val signalFill: Color,
    val signalText: Color,
    val signalSoft: Color,
    val blue: Color,
    val blueText: Color,
    val blueSoft: Color,
    val mint: Color,
    val mintSoft: Color,
    val onMint: Color,
    val amber: Color,
    val red: Color,
    val redFill: Color
)

private val LightFolioPalette = FolioPalette(
    isDark = false,
    canvas = Color(0xFFF7F6F2),
    surface1 = Color(0xFFEEF1EC),
    surface2 = Color(0xFFE3E9E2),
    line = Color(0xFFCFD9D2),
    lineStrong = Color(0xFFBFCBC3),
    navBar = Color(0xFFEEF1EC),
    scrimAlpha = 0.52f,
    ink = Color(0xFF0E3A2F),
    ink2 = Color(0xFF4A655D),
    placeholderInk = Color(0xFF536B63),
    signal = Color(0xFFE14B64),
    signalFill = Color(0xFFD93855),
    signalText = Color(0xFFB52642),
    signalSoft = Color(0xFFFCE7EB),
    blue = Color(0xFF2B627A),
    blueText = Color(0xFF1E4B60),
    blueSoft = Color(0xFFE0EFF4),
    mint = Color(0xFF147356),
    mintSoft = Color(0xFFDDF4EC),
    onMint = Color.White,
    amber = Color(0xFF88560D),
    red = Color(0xFFB5293B),
    redFill = Color(0xFFB5293B)
)

private val DarkFolioPalette = FolioPalette(
    isDark = true,
    canvas = Color(0xFF0A211B),
    surface1 = Color(0xFF102E26),
    surface2 = Color(0xFF173D33),
    line = Color(0xFF224E42),
    lineStrong = Color(0xFF2E6355),
    navBar = Color(0xFF0D2720),
    scrimAlpha = 0.74f,
    ink = Color(0xFFF5F8F6),
    ink2 = Color(0xFF9AB5AC),
    placeholderInk = Color(0xFF88A79C),
    signal = Color(0xFFFB7185),
    signalFill = Color(0xFFCE3652),
    signalText = Color(0xFFFF94A4),
    signalSoft = Color(0xFF351923),
    blue = Color(0xFF7CC7D9),
    blueText = Color(0xFFA8E0ED),
    blueSoft = Color(0xFF112E36),
    mint = Color(0xFF6EE7B7),
    mintSoft = Color(0xFF143B30),
    onMint = Color(0xFF07241C),
    amber = Color(0xFFE8BC66),
    red = Color(0xFFFF7A8A),
    redFill = Color(0xFFC9364C)
)

val LocalFolioPalette = staticCompositionLocalOf { DarkFolioPalette }

val IsDarkTheme: Boolean @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.isDark
val Canvas: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.canvas
val Surface1: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.surface1
val Surface2: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.surface2
val Line: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.line
val LineStrong: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.lineStrong
val NavBar: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.navBar
val ScrimAlpha: Float @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.scrimAlpha
val Ink: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.ink
val Ink2: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.ink2
val PlaceholderInk: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.placeholderInk
val Signal: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.signal
val SignalFill: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.signalFill
val SignalText: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.signalText
val SignalSoft: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.signalSoft
val Blue: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.blue
val BlueText: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.blueText
val BlueSoft: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.blueSoft
val Mint: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.mint
val MintSoft: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.mintSoft
val OnMint: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.onMint
val Amber: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.amber
val Red: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.red
val RedFill: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.redFill

val EditorialSerif: FontFamily = FontFamily.Serif
val EditorialMono: FontFamily = FontFamily.Monospace

private fun buildColorScheme(p: FolioPalette) = if (p.isDark) {
    darkColorScheme(
        primary = p.signalFill,
        onPrimary = Color.White,
        secondary = p.blue,
        onSecondary = Color(0xFF0A1624),
        tertiary = p.mint,
        onTertiary = p.onMint,
        background = p.canvas,
        onBackground = p.ink,
        surface = p.surface1,
        onSurface = p.ink,
        surfaceVariant = p.surface2,
        onSurfaceVariant = p.ink2,
        outline = p.line,
        error = p.red
    )
} else {
    lightColorScheme(
        primary = p.signalFill,
        onPrimary = Color.White,
        secondary = p.blue,
        onSecondary = Color.White,
        tertiary = p.mint,
        onTertiary = p.onMint,
        background = p.canvas,
        onBackground = p.ink,
        surface = p.surface1,
        onSurface = p.ink,
        surfaceVariant = p.surface2,
        onSurfaceVariant = p.ink2,
        outline = p.line,
        error = p.red
    )
}

private val type = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 33.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
        lineHeight = 39.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
        lineHeight = 32.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 23.sp
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.7.sp
    ),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun SievexTheme(
    updateSystemBars: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    ThemePrefs.restore(context)
    val systemDark = isSystemInDarkTheme()
    val dark = when (ThemePrefs.mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val palette = if (dark) DarkFolioPalette else LightFolioPalette
    val scheme = buildColorScheme(palette)

    if (updateSystemBars) {
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = view.context.findActivity()?.window ?: return@SideEffect
                @Suppress("DEPRECATION")
                window.statusBarColor = palette.canvas.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = palette.navBar.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }

    CompositionLocalProvider(LocalFolioPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
