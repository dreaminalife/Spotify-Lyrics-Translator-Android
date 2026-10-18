package com.dreaminalife.lyricstranslator.ui.lyrics

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dreaminalife.lyricstranslator.R
import com.dreaminalife.lyricstranslator.domain.model.NowPlaying
import kotlinx.coroutines.delay

/** How long a seek is shown if the player never reports the new position. */
private const val SEEK_PENDING_MS = 2_000L

@Composable
fun MiniPlayerBar(
    nowPlaying: NowPlaying,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val playbackPosition by rememberPlaybackPosition(nowPlaying)
    val duration = nowPlaying.track.durationMs
    // Where the finger is while dragging the bar.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    // Where the user seeked to, until the player reports the new position; else the bar jumps back for a moment.
    var pendingSeek by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(nowPlaying) { pendingSeek = null }
    LaunchedEffect(pendingSeek) {
        if (pendingSeek != null) {
            delay(SEEK_PENDING_MS)
            pendingSeek = null
        }
    }
    val position = dragFraction?.let { (it * duration).toLong() } ?: pendingSeek ?: playbackPosition
    val seekTo = { fraction: Float ->
        val target = (fraction * duration).toLong()
        pendingSeek = target
        onSeek(target)
    }
    // Transparent, so the lyrics screen's background shows through.
    Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.navigationBarsPadding()) {
            // Taller than the bar, so it's easy to grab; tap or drag to seek.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .pointerInput(duration) {
                        if (duration <= 0) return@pointerInput
                        detectTapGestures { seekTo((it.x / size.width).coerceIn(0f, 1f)) }
                    }
                    .pointerInput(duration) {
                        if (duration <= 0) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = {
                                dragFraction?.let(seekTo)
                                dragFraction = null
                            },
                            onDragCancel = { dragFraction = null },
                            onHorizontalDrag = { change, _ ->
                                dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                LinearProgressIndicator(
                    progress = { if (duration > 0) position.toFloat() / duration else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTime(position), style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious) {
                        Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Previous")
                    }
                    FilledIconButton(onClick = onPlayPause) {
                        if (nowPlaying.playback.isPlaying) {
                            Icon(painterResource(R.drawable.ic_pause), contentDescription = "Pause")
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                        }
                    }
                    IconButton(onClick = onNext) {
                        Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next")
                    }
                }
                Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
