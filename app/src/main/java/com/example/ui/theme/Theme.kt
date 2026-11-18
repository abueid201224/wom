package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val WmsDarkColorScheme = darkColorScheme(
    primary = Emerald500,
    onPrimary = Slate950,
    primaryContainer = Emerald950,
    onPrimaryContainer = Emerald400,
    secondary = Indigo500,
    onSecondary = Color.White,
    secondaryContainer = Indigo950,
    onSecondaryContainer = Indigo400,
    tertiary = Amber500,
    onTertiary = Slate950,
    tertiaryContainer = Amber950,
    onTertiaryContainer = Amber400,
    error = Rose500,
    onError = Color.White,
    errorContainer = Rose950,
    onErrorContainer = Rose400,
    background = Slate950,
    onBackground = Slate100,
    surface = Slate900,
    onSurface = Slate100,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate300,
    surfaceContainerLowest = Slate950,
    surfaceContainerLow = Slate900,
    surfaceContainer = Slate850,
    surfaceContainerHigh = Slate800,
    surfaceContainerHighest = Slate700,
    outline = Slate700,
    outlineVariant = Slate800
)

private val WmsShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun WmsAuditorTheme(
    language: String = "ar",
    content: @Composable () -> Unit
) {
    val layoutDirection = if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        MaterialTheme(
            colorScheme = WmsDarkColorScheme,
            typography = WmsTypography,
            shapes = WmsShapes,
            content = content
        )
    }
}
