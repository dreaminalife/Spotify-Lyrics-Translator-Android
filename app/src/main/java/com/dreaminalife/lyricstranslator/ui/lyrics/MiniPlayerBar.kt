package com.dreaminalife.lyricstranslator.ui.lyrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dreaminalife.lyricstranslator.R
import com.dreaminalife.lyricstranslator.domain.model.NowPlaying

@Composable
fun MiniPlayerBar(
    nowPlaying: NowPlaying,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val position by rememberPlaybackPosition(nowPlaying)
    val duration = nowPlaying.track.durationMs
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(
                progress = { if (duration > 0) position.toFloat() / duration else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
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
