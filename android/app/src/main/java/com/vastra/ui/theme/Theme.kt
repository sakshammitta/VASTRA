package com.vastra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ShapeDefaults

private val VastraColorScheme = lightColorScheme(
    primary = VastraGold,
    onPrimary = VastraCream,
    primaryContainer = VastraGoldLight,
    onPrimaryContainer = VastraCharcoal,
    secondary = VastraCharcoal,
    onSecondary = VastraCream,
    secondaryContainer = VastraSurfaceVariant,
    onSecondaryContainer = VastraCharcoal,
    background = VastraCream,
    onBackground = VastraCharcoal,
    surface = VastraSurface,
    onSurface = VastraCharcoal,
    surfaceVariant = VastraSurfaceVariant,
    onSurfaceVariant = VastraSubtext,
    outline = VastraOutline,
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
