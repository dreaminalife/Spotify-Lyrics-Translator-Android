package com.dreaminalife.lyricstranslator.ui.lyrics

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
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

private fun NowPlaying?.positionNow(): Long = this?.positionAt(SystemClock.elapsedRealtime()) ?: 0L

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
