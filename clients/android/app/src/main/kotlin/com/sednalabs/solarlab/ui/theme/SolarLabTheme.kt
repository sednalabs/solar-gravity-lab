package com.sednalabs.solarlab.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val SolarLabColorScheme = darkColorScheme(
    primary = Color(0xFF91C6EA),
    onPrimary = Color(0xFF102233),
    primaryContainer = Color(0xFF193D58),
    onPrimaryContainer = Color(0xFFD1ECFF),
    secondary = Color(0xFFD7C9AF),
    onSecondary = Color(0xFF282319),
    secondaryContainer = Color(0xFF3D362A),
    onSecondaryContainer = Color(0xFFF1E4CE),
    tertiary = Color(0xFFB5A5FF),
    onTertiary = Color(0xFF221A4F),
    tertiaryContainer = Color(0xFF34296A),
    onTertiaryContainer = Color(0xFFE2DBFF),
    error = Color(0xFFFF8686),
    onError = Color(0xFF3A0006),
    errorContainer = Color(0xFF5C1020),
    onErrorContainer = Color(0xFFFFDADA),
    background = Color(0xFF050812),
    onBackground = Color(0xFFE8EEF9),
    surface = Color(0xFF0D1420),
    onSurface = Color(0xFFE8EEF9),
    surfaceVariant = Color(0xFF182232),
    onSurfaceVariant = Color(0xFFC0CCDE),
    outline = Color(0xFF4B5F7A),
    outlineVariant = Color(0xFF2A3648),
)

private val SolarLabTypography = androidx.compose.material3.Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    ),
)

@Composable
fun SolarLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SolarLabColorScheme,
        typography = SolarLabTypography,
        content = content,
    )
}
