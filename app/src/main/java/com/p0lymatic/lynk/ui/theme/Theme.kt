package com.p0lymatic.lynk.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Lynk is a tool, not a toy: one restrained grey palette, no dynamic colour, no gradients.
 * Colour only appears where it carries meaning — connection state.
 */
object LynkColors {
    val bg = Color(0xFF0F0F11)
    val surface = Color(0xFF16161A)
    val surfaceHigh = Color(0xFF1C1C21)
    val border = Color(0xFF2A2A30)
    val borderSoft = Color(0xFF212127)
    val text = Color(0xFFE7E7EA)
    val textDim = Color(0xFF9C9CA4)
    val textMuted = Color(0xFF6C6C75)
    val accent = Color(0xFFD6D6DB)
    val ok = Color(0xFF7FA98C)
    val warn = Color(0xFFB49A5E)
    val err = Color(0xFFB4716F)
    val termBg = Color(0xFF0B0B0D)
}

val Mono = FontFamily.Monospace

private val LynkScheme = darkColorScheme(
    primary = LynkColors.accent,
    onPrimary = Color(0xFF121214),
    primaryContainer = LynkColors.surfaceHigh,
    onPrimaryContainer = LynkColors.text,
    secondary = LynkColors.textDim,
    onSecondary = LynkColors.bg,
    background = LynkColors.bg,
    onBackground = LynkColors.text,
    surface = LynkColors.surface,
    onSurface = LynkColors.text,
    surfaceVariant = LynkColors.surfaceHigh,
    onSurfaceVariant = LynkColors.textDim,
    outline = LynkColors.border,
    outlineVariant = LynkColors.borderSoft,
    error = LynkColors.err,
    onError = Color(0xFF1A0F0F),
)

private val LynkTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 1.6.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.2.sp,
    ),
)

@Composable
fun LynkTheme(content: @Composable () -> Unit) {
    // Dark only, on purpose: admin panels look wrong on a white background at 3 a.m.
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = LynkScheme,
        typography = LynkTypography,
        content = content,
    )
}