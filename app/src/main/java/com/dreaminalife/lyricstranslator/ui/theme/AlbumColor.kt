package com.dreaminalife.lyricstranslator.ui.theme

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import com.dreaminalife.lyricstranslator.log.AppLog

/** The lyrics screen's color while nothing is playing or the player sends no cover. */
val NoArtColor = Color(0xFF0B3A35)

/** The main color of [artwork], darkened so white text reads on it; null if the cover has no colors to pick from. */
fun albumColor(artwork: Bitmap): Color? {
    // Palette reads the pixels, which a hardware bitmap doesn't allow.
    val readable = if (artwork.config == Bitmap.Config.HARDWARE) artwork.copy(Bitmap.Config.ARGB_8888, false) else artwork
    val palette = Palette.from(readable ?: return null).generate()
    val swatch = palette.dominantSwatch ?: palette.swatches.maxByOrNull { it.population } ?: return null
    val color = darkenForWhiteText(Color(swatch.rgb))
    AppLog.d("AlbumColor", "Cover color #%08X -> #%08X".format(swatch.rgb, color.toArgb()))
    return color
}

/**
 * [color], moved towards black until its luminance is at most [maxLuminance]. Luminance rather than lightness, so
 * yellows and greens (bright at the same lightness) get dark enough too. 0.12 keeps white text above 6:1.
 */
fun darkenForWhiteText(color: Color, maxLuminance: Float = 0.12f): Color {
    var darker = color
    var step = 0
    while (darker.luminance() > maxLuminance && step < DARKEN_STEPS) {
        step++
        darker = lerp(color, Color.Black, step.toFloat() / DARKEN_STEPS)
    }
    return darker
}

private const val DARKEN_STEPS = 20

/** A dark theme made from [color] (see [albumColor]): white text, and surfaces and tags tinted with the color. */
@Composable
fun AlbumTheme(color: Color, content: @Composable () -> Unit) {
    // Based on the teal scheme, so a role not set here is never Material's default purple.
    val scheme = remember(color) {
        val tint = lerp(color, Color.White, 0.18f)
        val menu = lerp(color, Color.Black, 0.3f)
        val line = lerp(color, Color.White, 0.5f)
        TealDarkScheme.copy(
            primary = Color.White,
            onPrimary = color,
            background = color,
            onBackground = Color.White,
            surface = color,
            onSurface = Color.White,
            onSurfaceVariant = lerp(color, Color.White, 0.85f),
            surfaceVariant = tint,
            secondaryContainer = tint,
            onSecondaryContainer = Color.White,
            tertiaryContainer = lerp(color, Color.White, 0.28f),
            onTertiaryContainer = Color.White,
            surfaceContainerLowest = menu,
            surfaceContainerLow = menu,
            surfaceContainer = menu,
            surfaceContainerHigh = menu,
            surfaceContainerHighest = menu,
            outline = line,
            outlineVariant = line,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
}
