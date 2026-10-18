package com.dreaminalife.lyricstranslator.ui.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreaminalife.lyricstranslator.domain.model.FloatingLyricsMode
import com.dreaminalife.lyricstranslator.domain.model.FloatingLyricsSettings
import com.dreaminalife.lyricstranslator.domain.model.FloatingStyle
import com.dreaminalife.lyricstranslator.domain.model.LongLines
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.NowPlaying
import com.dreaminalife.lyricstranslator.domain.model.Track
import com.dreaminalife.lyricstranslator.ui.lyrics.LyricsContent
import com.dreaminalife.lyricstranslator.ui.lyrics.LyricsSession
import com.dreaminalife.lyricstranslator.ui.lyrics.rememberPlaybackPosition

/** With [LongLines.SHRINK], text gets no smaller than this share of its size before a line is cut with "…". */
private const val MIN_FIT_SCALE = 0.7f

/** Text color of [FloatingStyle.LIGHT]. */
private val LIGHT_STYLE_TEXT = Color(0xFF1A1A1A)

/**
 * The floating window's lyrics: the line playing now, with the lines around it when showing more than one, and the
 * track while there is no line.
 *
 * @param showControls draws the settings button and the resize grip in the bottom-end corner (the service resizes
 *   the window with it).
 * @param onTapLyrics called for a tap on the lyrics (not on the settings button); null when taps do nothing.
 * @param onDoubleTapLyrics called for a double tap on the lyrics.
 * @param onBounds reports where the lyrics are drawn, so the service can tell a drag on them from a touch elsewhere.
 * @param albumColor the background for [FloatingStyle.ALBUM]; null without a cover.
 */
@Composable
fun FloatingLyrics(
    session: LyricsSession,
    settings: FloatingLyricsSettings,
    albumColor: Color?,
    showControls: Boolean,
    onOpenSettings: () -> Unit,
    onTapLyrics: (() -> Unit)?,
    onDoubleTapLyrics: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val state by session.uiState.collectAsStateWithLifecycle()
    val nowPlaying by session.nowPlaying.collectAsStateWithLifecycle()
    val playing = nowPlaying ?: return
    val track = playing.track
    // Right after a track change the lyrics may still be the previous song's; never time them with the new position.
    val content = state.content.takeIf { state.track?.key == track.key } ?: LyricsContent.Loading
    val density = LocalDensity.current
    val opacity = settings.backgroundOpacity
    val (background, foreground) = when (settings.style) {
        FloatingStyle.DARK -> Color.Black.copy(alpha = opacity) to Color.White
        FloatingStyle.LIGHT -> Color.White.copy(alpha = opacity) to LIGHT_STYLE_TEXT
        FloatingStyle.ALBUM -> (albumColor ?: Color.Black).copy(alpha = opacity) to Color.White
        FloatingStyle.CLEAR -> Color.Transparent to Color.White
    }
    Box(Modifier.onGloballyPositioned { onBounds(it.boundsInRoot()) }) {
        Surface(
            color = background,
            contentColor = foreground,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().pointerInput(onTapLyrics, onDoubleTapLyrics) {
                detectTapGestures(onTap = onTapLyrics?.let { { _ -> it() } }, onDoubleTap = { onDoubleTapLyrics() })
            },
        ) {
            // Font scale only changes sp sizes, so the text grows while paddings stay as they are.
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * settings.textScale)) {
                // Wide enough on both sides to keep the text clear of the settings button.
                Box(Modifier.padding(horizontal = 28.dp, vertical = 10.dp)) {
                    when (content) {
                        LyricsContent.NothingPlaying, LyricsContent.Loading ->
                            Rows(listOf(FloatingRow(track.title, "Finding lyrics…")), false, settings)
                        LyricsContent.NotFound, is LyricsContent.Error ->
                            Rows(listOf(FloatingRow(track.title, "No lyrics")), false, settings)
                        is LyricsContent.Loaded ->
                            CurrentLines(track, content.lyrics, state.translationLines, playing, settings)
                    }
                }
            }
        }
        if (showControls) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.align(Alignment.TopEnd).size(28.dp).clickable(onClick = onOpenSettings),
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Floating lyrics settings",
                    tint = foreground.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )
            }
            ResizeHandle(foreground.copy(alpha = 0.6f), Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** Three diagonal strokes, like a window's resize corner. */
@Composable
private fun ResizeHandle(color: Color, modifier: Modifier) {
    Canvas(modifier.padding(6.dp).size(14.dp)) {
        val stroke = 1.5.dp.toPx()
        for (i in 1..3) {
            val offset = size.width * i / 3
            drawLine(color, Offset(size.width - offset, size.height), Offset(size.width, size.height - offset), stroke)
        }
    }
}

@Composable
private fun CurrentLines(
    track: Track,
    lyrics: Lyrics,
    translations: List<String>?,
    nowPlaying: NowPlaying,
    settings: FloatingLyricsSettings,
) {
    val count = settings.lineCount
    val position = rememberPlaybackPosition(nowPlaying)
    val index by remember(lyrics) { derivedStateOf { lyrics.indexAt(position.value) } }
    // Every row has room for a translation when showing several, so the window keeps its height from line to line.
    val keepSecondLines = count > 1 && settings.mode == FloatingLyricsMode.BOTH
    AnimatedContent(
        targetState = index,
        transitionSpec = {
            // Old and new content each move by one row, so the lines they share stay in place and the rest scroll.
            val direction = if (targetState >= initialState) 1 else -1
            (slideInVertically { it * direction / count } + fadeIn()) togetherWith
                (slideOutVertically { -it * direction / count } + fadeOut()) using SizeTransform(clip = true)
        },
        label = "floatingLines",
    ) { shownIndex ->
        Rows(floatingRows(track, lyrics, translations, shownIndex, settings.mode, count), keepSecondLines, settings)
    }
}

/** One row of the floating window: a lyric line, or the track before the first line, and the text under it. */
internal data class FloatingRow(val main: String, val second: String?)

/**
 * The [count] rows around line [index] of [lyrics], that line in the middle. A row before the track or after the
 * last line is null, and drawn empty so the window keeps its height.
 */
internal fun floatingRows(
    track: Track,
    lyrics: Lyrics,
    translations: List<String>?,
    index: Int,
    mode: FloatingLyricsMode,
    count: Int,
): List<FloatingRow?> {
    // Checked first: indexAt is also -1 for lyrics that aren't synced.
    if (!lyrics.synced) return listOf(FloatingRow(track.title, "Not time-synced"))
    val half = count / 2
    return (index - half..index + half).map { i ->
        when {
            i == -1 -> FloatingRow(track.title, track.artist.ifBlank { null })
            i !in lyrics.lines.indices -> null
            else -> {
                val original = lyrics.lines[i].text.ifBlank { "♪" }
                val translation = translations?.getOrNull(i)?.takeIf { it.isNotBlank() }
                when (mode) {
                    FloatingLyricsMode.BOTH -> FloatingRow(original, translation)
                    // No translation (yet, or the song is already in the target language): the original beats an empty row.
                    FloatingLyricsMode.TRANSLATION_ONLY -> FloatingRow(translation ?: original, null)
                }
            }
        }
    }
}

/**
 * [rows] top to bottom, the middle one highlighted.
 *
 * @param keepSecondLines leaves room for a second line under every row, even rows without one.
 */
@Composable
private fun Rows(rows: List<FloatingRow?>, keepSecondLines: Boolean, settings: FloatingLyricsSettings) {
    val current = rows.size / 2
    val color = LocalContentColor.current
    // Without a background, a shadow keeps the text readable over whatever is underneath.
    val shadow = settings.style == FloatingStyle.CLEAR
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, row ->
            val alpha = if (i == current) 1f else 0.45f
            Line(
                row?.main.orEmpty(),
                MaterialTheme.typography.titleMedium.copy(fontWeight = if (i == current) FontWeight.Bold else FontWeight.Medium),
                color.copy(alpha = alpha),
                shadow,
                settings.longLines,
                if (i > 0) Modifier.padding(top = 6.dp) else Modifier,
            )
            val second = row?.second
            if (second != null || keepSecondLines) {
                Line(
                    second.orEmpty(),
                    MaterialTheme.typography.bodyMedium,
                    color.copy(alpha = alpha * 0.8f),
                    shadow,
                    settings.longLines,
                    Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Line(text: String, style: TextStyle, color: Color, shadow: Boolean, longLines: LongLines, modifier: Modifier) {
    val wrap = longLines == LongLines.WRAP
    Text(
        text,
        // The shadow fades with the text, so dimmed lines don't leave a dark smudge.
        style = if (shadow) style.copy(shadow = Shadow(Color.Black.copy(alpha = color.alpha * 0.9f), Offset(0f, 1f), 6f)) else style,
        color = color,
        textAlign = TextAlign.Center,
        // Wrapped lines are never cut, however many lines they take.
        maxLines = if (wrap) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = if (wrap) null else TextAutoSize.StepBased(style.fontSize * MIN_FIT_SCALE, style.fontSize, 0.5.sp),
        modifier = modifier,
    )
}
