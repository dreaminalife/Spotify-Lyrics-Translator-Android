package com.dreaminalife.lyricstranslator.ui.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dreaminalife.lyricstranslator.domain.model.FloatingLyricsMode
import com.dreaminalife.lyricstranslator.domain.model.FloatingLyricsSettings
import com.dreaminalife.lyricstranslator.domain.model.LongLines
import kotlin.math.roundToInt

/** Changes the saved settings with the function given, so quick changes don't overwrite each other. */
typealias FloatingSettingsChange = ((FloatingLyricsSettings) -> FloatingLyricsSettings) -> Unit

/** The floating window's display options; shown in its own settings panel and in the app's Settings. */
@Composable
fun FloatingLyricsOptions(settings: FloatingLyricsSettings, onChange: FloatingSettingsChange, modifier: Modifier = Modifier) {
    Column(modifier) {
        OptionRow("Lines") {
            Choice(FloatingLyricsSettings.LINE_COUNTS, settings.lineCount, label = { "$it" }) { count ->
                onChange { it.copy(lineCount = count) }
            }
        }
        OptionRow("Translation only") {
            Switch(
                checked = settings.mode == FloatingLyricsMode.TRANSLATION_ONLY,
                onCheckedChange = { only ->
                    onChange { it.copy(mode = if (only) FloatingLyricsMode.TRANSLATION_ONLY else FloatingLyricsMode.BOTH) }
                },
            )
        }
        OptionRow("Long lines") {
            Choice(LongLines.entries, settings.longLines, label = { it.displayName }) { longLines ->
                onChange { it.copy(longLines = longLines) }
            }
        }
        OptionRow("Text size") {
            Slider(
                value = settings.textScale,
                onValueChange = { value ->
                    // Steps of 0.1, saved only when the step changes.
                    val scale = (value * 10).roundToInt() / 10f
                    if (scale != settings.textScale) onChange { it.copy(textScale = scale) }
                },
                valueRange = FloatingLyricsSettings.MIN_TEXT_SCALE..FloatingLyricsSettings.MAX_TEXT_SCALE,
                steps = ((FloatingLyricsSettings.MAX_TEXT_SCALE - FloatingLyricsSettings.MIN_TEXT_SCALE) * 10).roundToInt() - 1,
                modifier = Modifier.weight(1.5f),
            )
        }
    }
}

/** The panel the floating window opens under itself: [FloatingLyricsOptions], and buttons to lock or close it. */
@Composable
fun FloatingSettingsPanel(
    settings: FloatingLyricsSettings,
    onChange: FloatingSettingsChange,
    onLock: () -> Unit,
    onTurnOff: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            FloatingLyricsOptions(settings, onChange, Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onLock) { Text("Lock") }
                TextButton(onClick = onTurnOff) { Text("Turn off") }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, control: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        control()
    }
}

@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
            ) { Text(label(option)) }
        }
    }
}
