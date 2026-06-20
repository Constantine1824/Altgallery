package com.altgallery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Alt Gallery is dark-only by design (see the prototype). We expose a single
// dark scheme regardless of the system setting.
private val AltGalleryColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = TextPrimary,
    secondary = Teal,
    tertiary = Purple,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    outline = Border,
)

@Composable
fun AltGalleryTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AltGalleryColorScheme,
        typography = AltGalleryTypography,
        content = content,
    )
}
