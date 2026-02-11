package me.ash.reader.ui.page.home.reading.podcast

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.android.PodcastPlayerManager

@Composable
fun PodcastInlinePlayer(
    state: PodcastPlayerManager.State,
    podcastUrl: String?,
    onToggle: (String) -> Unit,
    onSeek: (Int) -> Unit,
) {
    if (podcastUrl == null) return

    val currentPositionMs =
        when (state) {
            is PodcastPlayerManager.State.Playing -> if (state.url == podcastUrl) state.positionMs else null
            is PodcastPlayerManager.State.Paused -> if (state.url == podcastUrl) state.positionMs else null
            else -> null
        }
    val currentDurationMs =
        when (state) {
            is PodcastPlayerManager.State.Playing -> if (state.url == podcastUrl) state.durationMs else null
            is PodcastPlayerManager.State.Paused -> if (state.url == podcastUrl) state.durationMs else null
            else -> null
        }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row {
                IconButton(onClick = { if (currentPositionMs != null) onSeek(currentPositionMs - 10_000) }) {
                    Icon(
                        imageVector = Icons.Rounded.Replay10,
                        contentDescription = stringResource(R.string.podcast_backward_10_seconds),
                    )
                }
                PodcastButton(
                    state = state,
                    podcastUrl = podcastUrl,
                    onClick = { onToggle(podcastUrl) },
                )
                IconButton(onClick = { if (currentPositionMs != null) onSeek(currentPositionMs + 30_000) }) {
                    Icon(
                        imageVector = Icons.Rounded.Forward30,
                        contentDescription = stringResource(R.string.podcast_forward_30_seconds),
                    )
                }
            }
            if (currentPositionMs != null && currentDurationMs != null) {
                PodcastSeekBar(
                    positionMs = currentPositionMs,
                    durationMs = currentDurationMs,
                    onSeek = onSeek,
                )
            }
        }
    }
}
