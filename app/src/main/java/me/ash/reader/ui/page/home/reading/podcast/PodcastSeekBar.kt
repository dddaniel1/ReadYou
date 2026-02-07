package me.ash.reader.ui.page.home.reading.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PodcastSeekBar(
    modifier: Modifier = Modifier,
    positionMs: Int,
    durationMs: Int,
    onSeek: (Int) -> Unit,
) {
    if (durationMs <= 0) return

    val duration = durationMs.toFloat()
    var dragValue by remember(positionMs, durationMs) { mutableFloatStateOf(positionMs.toFloat()) }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Slider(
            modifier = Modifier.fillMaxWidth(),
            value = dragValue,
            onValueChange = { dragValue = it },
            valueRange = 0f..duration,
            onValueChangeFinished = { onSeek(dragValue.toInt()) },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatMs(dragValue.toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = formatMs(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private fun formatMs(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
