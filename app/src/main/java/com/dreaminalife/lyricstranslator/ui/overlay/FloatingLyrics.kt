package com.dreaminalife.lyricstranslator.ui.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreaminalife.lyricstranslator.R
import com.dreaminalife.lyricstranslator.domain.PlaybackController
import com.dreaminalife.lyricstranslator.domain.model.FloatingLyricsMode
import com.dreaminalife.lyricstranslator.domain.model.FloatingLyricsSettings
import com.dreaminalife.lyricstranslator.domain.model.FloatingStyle
import com.dreaminalife.lyricstranslator.domain.model.LongLines
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.NowPlaying
import com.dreaminalife.lyricstranslator.domain.model.Track
import com.dreaminalife.lyricstranslator.ui.lyrics.LyricsContent
import com.dreaminalife.lyricstranslator.ui.lyrics.LyricsSession
import com.dreaminalife.lyricstranslator.ui.lyrics.rememberLineIndex
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** With [LongLines.SHRINK], text gets no smaller than this share of its size before a line is cut with "…". */
private const val MIN_FIT_SCALE = 0.7f

/** How long the playback row takes to open or close. */
private const val PLAYBACK_REVEAL_MS = 300

/** How long the lines take to scroll to the next one. */
private const val LINE_SCROLL_MS = 400

/** Alpha of the lines around the one playing now. */
private const val DIM_ALPHA = 0.45f

/** Space between one row and the next. */
private val ROW_GAP = 6.dp

/** Text color of [FloatingStyle.LIGHT]. */
private val LIGHT_STYLE_TEXT = Color(0xFF1A1A1A)

/**
 * The floating window's lyrics: the line playing now, with the lines around it when showing more than one, and the
 * track while there is no line.
 *
 * @param showControls draws the settings button and the resize grip in the bottom-end corner (the service resizes
 *   the window with it).
 * @param showPlayback draws buttons for [playback], and the lyrics source, under the lyrics.
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
    playback: PlaybackController,
    showPlayback: Boolean,
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
    // The playback row is laid out, at full height, from the start of its animation to the end, and only what is drawn
    // grows or shrinks: the window then resizes once each way instead of every frame, which makes it jitter.
    val playbackTransition = updateTransition(showPlayback, label = "playback")
    val rowShown = playbackTransition.currentState || playbackTransition.targetState
    val revealed = playbackTransition.animateFloat(
        transitionSpec = { tween(PLAYBACK_REVEAL_MS, easing = FastOutSlowInEasing) },
        label = "playbackRevealed",
    ) { if (it) 1f else 0f }
    var rowHeight by remember { mutableIntStateOf(0) }
    // Read while drawing, so the animation doesn't recompose.
    val hiddenHeight = { if (rowShown) rowHeight * (1 - revealed.value) else 0f }
    Box(Modifier.onGloballyPositioned { onBounds(it.boundsInRoot()) }) {
        Surface(
            color = background,
            contentColor = foreground,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    val shown = RoundRect(0f, 0f, size.width, size.height - hiddenHeight(), CornerRadius(16.dp.toPx()))
                    clipPath(Path().apply { addRoundRect(shown) }) { this@drawWithContent.drawContent() }
                }
                .pointerInput(onTapLyrics, onDoubleTapLyrics) { detectTapsAtOnce(onTapLyrics, onDoubleTapLyrics) },
        ) {
            Column {
                // Font scale only changes sp sizes, so the text grows while paddings stay as they are.
                CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * settings.textScale)) {
                    // Wide enough on both sides to keep the text clear of the settings button.
                    Box(Modifier.padding(horizontal = 28.dp, vertical = 10.dp)) {
                        when (content) {
                            LyricsContent.NothingPlaying, LyricsContent.Loading ->
                                LyricRow(FloatingRow(track.title, "Finding lyrics…"), true, 1f, false, settings)
                            LyricsContent.NotFound, is LyricsContent.Error ->
                                LyricRow(FloatingRow(track.title, "No lyrics"), true, 1f, false, settings)
                            is LyricsContent.Loaded ->
                                CurrentLines(track, content.lyrics, state.translationLines, playing, settings)
                        }
                    }
                }
                if (rowShown) {
                    PlaybackRow(
                        (content as? LyricsContent.Loaded)?.lyrics?.source?.displayName,
                        playing.playback.isPlaying,
                        playback,
                        settings.style == FloatingStyle.CLEAR,
                        Modifier.onSizeChanged { rowHeight = it.height }.graphicsLayer { alpha = revealed.value },
                    )
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
            ResizeHandle(
                foreground.copy(alpha = 0.6f),
                Modifier.align(Alignment.BottomEnd).offset { IntOffset(0, -hiddenHeight().roundToInt()) },
            )
        }
    }
}

/**
 * Like detectTapGestures, but [onTap] is called as soon as the first tap ends instead of once a double tap is ruled
 * out, so the window answers without a delay; a double tap calls [onTap] and then [onDoubleTap].
 */
private suspend fun PointerInputScope.detectTapsAtOnce(onTap: (() -> Unit)?, onDoubleTap: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown()
        // Null when cancelled, dragged away, or taken by a button.
        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
        up.consume()
        onTap?.invoke()
        withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() } ?: return@awaitEachGesture
        val secondUp = waitForUpOrCancellation() ?: return@awaitEachGesture
        secondUp.consume()
        onDoubleTap()
    }
}

/** Previous, play/pause and next, with the lyrics' [source] (null without lyrics) at the start. */
@Composable
private fun PlaybackRow(source: String?, isPlaying: Boolean, playback: PlaybackController, shadow: Boolean, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (source != null) {
            val style = MaterialTheme.typography.labelSmall
            Text(
                source,
                style = if (shadow) style.copy(shadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 1f), 6f)) else style,
                color = LocalContentColor.current.copy(alpha = 0.6f),
                maxLines = 1,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        // Buttons no taller than they look (not the usual 48dp touch target), so the window's edge sits close under them.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = playback::previous) {
                    Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Previous")
                }
                IconButton(onClick = playback::playPause) {
                    if (isPlaying) {
                        Icon(painterResource(R.drawable.ic_pause), contentDescription = "Pause")
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                    }
                }
                IconButton(onClick = playback::next) {
                    Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next")
                }
            }
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
    val index by rememberLineIndex(lyrics, nowPlaying)
    if (!lyrics.synced) {
        LyricRow(floatingRows(track, lyrics, translations, index, settings.mode, count).single(), true, 1f, false, settings)
        return
    }
    val half = count / 2
    // Every row has room for a translation when showing several, so the window keeps its height from line to line.
    val keepSecondLines = count > 1 && settings.mode == FloatingLyricsMode.BOTH
    // The line in the middle, in lines: between two while scrolling.
    val position = remember(lyrics) { Animatable(index.toFloat()) }
    // The lines scrolled between; the rows around both stay laid out until the scroll ends.
    var first by remember(lyrics) { mutableIntStateOf(index) }
    var last by remember(lyrics) { mutableIntStateOf(index) }
    LaunchedEffect(lyrics, index) {
        val from = position.value
        // A seek far away jumps rather than scrolling through every line in between.
        if (abs(index - from) <= count) {
            first = minOf(floor(from).toInt(), index)
            last = maxOf(ceil(from).toInt(), index)
            position.animateTo(index.toFloat(), tween(LINE_SCROLL_MS, easing = FastOutSlowInEasing))
        }
        first = index
        last = index
        position.snapTo(index.toFloat())
    }
    val firstLine = first
    val lastLine = last
    // One copy of the lines, moved as a whole: cross-fading an old and a new copy leaves a ghost of each line wherever
    // the two don't line up exactly.
    Layout(
        content = {
            for (line in firstLine - half..lastLine + half) {
                key(line) {
                    val alpha by animateFloatAsState(if (line == index) 1f else DIM_ALPHA, tween(LINE_SCROLL_MS), label = "lineAlpha")
                    LyricRow(floatingRow(track, lyrics, translations, line, settings.mode), line == index, alpha, keepSecondLines, settings)
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clipToBounds(),
    ) { measurables, constraints ->
        val rows = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
        val gap = ROW_GAP.roundToPx()
        // Where each row starts, and one more where a row after the last would.
        val tops = IntArray(rows.size + 1)
        rows.forEachIndexed { k, row -> tops[k + 1] = tops[k] + row.height + gap }
        // Row k is line firstLine - half + k, so the rows around a line start at row (line - firstLine).
        fun startRow(line: Int) = (line - firstLine).coerceIn(0, rows.size - count)
        fun heightAround(line: Int) = tops[startRow(line) + count] - gap - tops[startRow(line)]
        // Wrapped lines change the height. The window is as tall as its content, and resizing it every frame makes it
        // flicker: it takes the taller height at once and keeps it until the scroll ends.
        val height = maxOf(heightAround(firstLine), heightAround(lastLine)).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(constraints.maxWidth, height) {
            // Read only here, so scrolling moves the rows without recomposing or measuring them.
            val p = position.value
            val start = startRow(floor(p).toInt())
            val scroll = tops[start] + (tops[start + 1] - tops[start]) * (p - (firstLine + start))
            rows.forEachIndexed { k, row ->
                // Rows fade out as they leave the window and in as they enter it.
                val outside = abs(firstLine - half + k - p) - half
                row.placeWithLayer(0, (tops[k] - scroll).roundToInt()) { alpha = (1 - outside).coerceIn(0f, 1f) }
            }
        }
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
    return (index - half..index + half).map { floatingRow(track, lyrics, translations, it, mode) }
}

/** The row for line [index] of synced [lyrics]: the track for -1, null past either end. */
internal fun floatingRow(track: Track, lyrics: Lyrics, translations: List<String>?, index: Int, mode: FloatingLyricsMode): FloatingRow? =
    when {
        index == -1 -> FloatingRow(track.title, track.artist.ifBlank { null })
        index !in lyrics.lines.indices -> null
        else -> {
            val original = lyrics.lines[index].text.ifBlank { "♪" }
            val translation = translations?.getOrNull(index)?.takeIf { it.isNotBlank() }
            when (mode) {
                FloatingLyricsMode.BOTH -> FloatingRow(original, translation)
                // No translation (yet, or the song is already in the target language): the original beats an empty row.
                FloatingLyricsMode.TRANSLATION_ONLY -> FloatingRow(translation ?: original, null)
            }
        }
    }

/**
 * One [row], drawn empty when null; bold when [current].
 *
 * @param keepSecondLines leaves room for a second line under the row, even without one.
 */
@Composable
private fun LyricRow(row: FloatingRow?, current: Boolean, alpha: Float, keepSecondLines: Boolean, settings: FloatingLyricsSettings) {
    val color = LocalContentColor.current
    // Without a background, a shadow keeps the text readable over whatever is underneath.
    val shadow = settings.style == FloatingStyle.CLEAR
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Line(
            row?.main.orEmpty(),
            MaterialTheme.typography.titleMedium.copy(fontWeight = if (current) FontWeight.Bold else FontWeight.Medium),
            color.copy(alpha = alpha),
            shadow,
            settings.longLines,
            Modifier,
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
