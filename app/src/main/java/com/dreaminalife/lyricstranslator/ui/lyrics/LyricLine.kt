package com.dreaminalife.lyricstranslator.ui.lyrics

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One lyric line with its translation underneath.
 *
 * @param highlight null when lyrics aren't synced (every line looks the same); otherwise whether this is the current line.
 * @param onClick null disables tapping.
 */
@Composable
fun LyricLine(
    text: String,
    translation: String?,
    highlight: Boolean?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(if (highlight == false) 0.4f else 1f, label = "lineAlpha")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .graphicsLayer { this.alpha = alpha }
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Text(
            text = text.ifBlank { "♪" },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = if (highlight == true) FontWeight.Bold else FontWeight.Medium,
            color = if (highlight == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (!translation.isNullOrBlank()) {
            Text(
                text = translation,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
