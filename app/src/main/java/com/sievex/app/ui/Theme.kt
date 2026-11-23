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
    val green: Color,
    val amber: Color,
    val red: Color,
    val redFill: Color
)

private val LightFolioPalette = FolioPalette(
    isDark = false,
    canvas = Color(0xFFFAF6EE),
    surface1 = Color(0xFFF2ECE0),
    surface2 = Color(0xFFE8E0CF),
    line = Color(0xFFDED5C4),
    ink = Color(0xFF171512),
    ink2 = Color(0xFF635D54),
    placeholderInk = Color(0xFF9C9486),
    signal = Color(0xFFC84B31),
    signalFill = Color(0xFFC84B31),
    signalText = Color(0xFF9E331D),
    signalSoft = Color(0xFFF6E2DC),
    blue = Color(0xFF3A5F8A),
    blueText = Color(0xFF29496E),
    blueSoft = Color(0xFFE3ECF5),
    mint = Color(0xFF3E7B67),
    mintSoft = Color(0xFFE0EFE9),
    onMint = Color(0xFF0E3529),
    green = Color(0xFF3E7B67),
    amber = Color(0xFFB07A28),
    red = Color(0xFFB93829),
    redFill = Color(0xFFB93829)
)

private val DarkFolioPalette = FolioPalette(
    isDark = true,
    canvas = Color(0xFF141210),
    surface1 = Color(0xFF1D1A17),
    surface2 = Color(0xFF27231E),
    line = Color(0xFF2F2A24),
    ink = Color(0xFFF3ECE0),
    ink2 = Color(0xFFADA496),
    placeholderInk = Color(0xFF756E63),
    signal = Color(0xFFEA6C50),
    signalFill = Color(0xFFEA6C50),
    signalText = Color(0xFFFFA08A),
    signalSoft = Color(0xFF331C16),
    blue = Color(0xFF7AA3D6),
    blueText = Color(0xFFA1C2EB),
    blueSoft = Color(0xFF1A2636),
    mint = Color(0xFF66B298),
    mintSoft = Color(0xFF182C25),
    onMint = Color(0xFF08241C),
    green = Color(0xFF66B298),
    amber = Color(0xFFE3AD54),
    red = Color(0xFFF06D5D),
    redFill = Color(0xFFB83A2C)
)

val LocalFolioPalette = staticCompositionLocalOf { DarkFolioPalette }

val IsDarkTheme: Boolean @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.isDark
val Canvas: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.canvas
val Surface1: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.surface1
val Surface2: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.surface2
val Line: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.line
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
val Green: Color @Composable @ReadOnlyComposable get() = LocalFolioPalette.current.green
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
        fontFamily = FontFamily.Serif,
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
        lineHeight = 33.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 27.sp
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
                window.navigationBarColor = palette.surface1.toArgb()
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
