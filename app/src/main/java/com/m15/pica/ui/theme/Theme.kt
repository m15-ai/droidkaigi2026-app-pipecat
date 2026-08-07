package com.m15.pica.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PicaColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF222222),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFBBBBBB),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF333333),
    onSecondaryContainer = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF111111),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFCCCCCC),
    error = Color(0xFFFF6B6B),
    onError = Color.Black,
)

@Composable
fun PicaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PicaColorScheme,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
