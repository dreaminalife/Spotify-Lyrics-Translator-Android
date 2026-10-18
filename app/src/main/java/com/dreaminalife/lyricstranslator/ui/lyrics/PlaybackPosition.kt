package com.dreaminalife.lyricstranslator.ui.lyrics

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.NowPlaying
import kotlinx.coroutines.delay

private const val TICK_MS = 100L

/**
 * The playback position, re-estimated about every [TICK_MS] while playing.
 * Waiting for a frame as well pauses the loop while the screen isn't visible.
 */
@Composable
fun rememberPlaybackPosition(nowPlaying: NowPlaying?): State<Long> =
    produceState(initialValue = nowPlaying.positionNow(), nowPlaying) {
        while (true) {
            value = nowPlaying.positionNow()
            if (nowPlaying?.playback?.isPlaying != true) break
            delay(TICK_MS)
            withFrameMillis { }
        }
    }

/**
 * The line of [lyrics] playing now (see [Lyrics.indexAt]). Wakes only when the next line starts instead of every
 * [TICK_MS], so the floating window doesn't keep waking the phone for hours while other apps are open.
 */
@Composable
fun rememberLineIndex(lyrics: Lyrics, nowPlaying: NowPlaying): State<Int> =
    produceState(initialValue = lyrics.indexAt(nowPlaying.positionNow()), lyrics, nowPlaying) {
        while (true) {
            val position = nowPlaying.positionNow()
            value = lyrics.indexAt(position)
            if (!nowPlaying.playback.isPlaying || !lyrics.synced) break
            val nextStart = lyrics.lines.getOrNull(value + 1)?.startTimeMs ?: break
            // Rounded up, so it wakes just after the line starts rather than just before.
            delay(((nextStart - position) / nowPlaying.playback.speed).toLong() + 1)
            withFrameMillis { }
        }
    }

private fun NowPlaying?.positionNow(): Long = this?.positionAt(SystemClock.elapsedRealtime()) ?: 0L

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
