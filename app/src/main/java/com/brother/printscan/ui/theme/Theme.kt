package com.brother.printscan.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = NavyPrimary,
    onPrimary = CardSurface,
    secondary = OliveAccent,
    onSecondary = CardSurface,
    tertiary = TerracottaCallout,
    background = SoftCoolBg,
    surface = CardSurface,
    onBackground = CharcoalInk,
    onSurface = CharcoalInk,
)

@Composable
fun BrotherPrintScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}
