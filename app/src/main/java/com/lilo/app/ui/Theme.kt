package com.lilo.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SafeGreen = Color(0xFF2ECC71)
val FarAmber = Color(0xFFFFB020)
val LostRed = Color(0xFFFF3B30)
val OffGrey = Color(0xFF6B7280)
val Bg = Color(0xFF0B0D10)
val Card = Color(0xFF14181D)
val Track = Color(0xFF232830)

private val Scheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Bg,
    onBackground = Color.White,
    surface = Card,
    onSurface = Color.White,
    surfaceVariant = Track,
    onSurfaceVariant = Color(0xFFB4BAC4),
    secondaryContainer = Color(0xFF2F3640),
    onSecondaryContainer = Color.White,
    outline = Color(0xFF4A515C),
)

@Composable
fun LiloTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
