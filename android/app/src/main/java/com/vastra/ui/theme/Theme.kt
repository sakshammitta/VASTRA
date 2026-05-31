package com.vastra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ShapeDefaults

private val VastraColorScheme = lightColorScheme(
    primary = VastraInk,
    onPrimary = VastraCream,
    primaryContainer = VastraSand,
    onPrimaryContainer = VastraInk,
    secondary = VastraSand,
    onSecondary = VastraInk,
    secondaryContainer = VastraMuted,
    onSecondaryContainer = VastraInk,
    background = VastraCream,
    onBackground = VastraInk,
    surface = VastraCard,
    onSurface = VastraInk,
    surfaceVariant = VastraMuted,
    onSurfaceVariant = VastraMutedText,
    outline = VastraBorderColor,
    error = VastraError,
)

private val VastraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun VastraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VastraColorScheme,
        typography = VastraTypography,
        shapes = VastraShapes,
        content = content
    )
}
