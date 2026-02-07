package me.ash.reader.ui.page.home.reading.podcast

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.android.PodcastPlayerManager

@Composable
fun PodcastButton(
    modifier: Modifier = Modifier,
    state: PodcastPlayerManager.State,
    podcastUrl: String?,
    iconSize: Dp = 20.dp,
    useOverlayStyle: Boolean = false,
    onClick: () -> Unit,
) {
    if (podcastUrl == null) return

    val hapticFeedback = LocalHapticFeedback.current
    val mode =
        when (state) {
            is PodcastPlayerManager.State.Preparing -> {
                if (state.url == podcastUrl) PodcastButtonMode.Preparing else PodcastButtonMode.Play
            }

            is PodcastPlayerManager.State.Playing -> {
                if (state.url == podcastUrl) PodcastButtonMode.Pause else PodcastButtonMode.Play
            }

            else -> PodcastButtonMode.Play
        }

    Box(
        modifier =
            modifier
                .size(40.dp)
                .shadow(if (useOverlayStyle) 6.dp else 0.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    if (useOverlayStyle) {
                        Color.Black.copy(alpha = .58f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .9f)
                    }
                )
                .border(
                    width = if (useOverlayStyle) 1.dp else 0.5.dp,
                    color =
                        if (useOverlayStyle) {
                            Color.White.copy(alpha = .28f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)
                        },
                    shape = CircleShape,
                )
                .clickable {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                fadeIn(tween(220, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(180, easing = FastOutSlowInEasing))
            },
            label = "podcast_button",
        ) { targetState ->
            when (targetState) {
                PodcastButtonMode.Pause -> {
                    Icon(
                        imageVector = Icons.Rounded.Pause,
                        contentDescription = stringResource(R.string.pause_podcast),
                        modifier = Modifier.size(iconSize),
                        tint = if (useOverlayStyle) Color.White else MaterialTheme.colorScheme.tertiary,
                    )
                }

                PodcastButtonMode.Preparing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(iconSize),
                        strokeWidth = 2.dp,
                        color = if (useOverlayStyle) Color.White else MaterialTheme.colorScheme.tertiary,
                    )
                }

                PodcastButtonMode.Play -> {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.play_podcast),
                        modifier = Modifier.size(iconSize),
                        tint = if (useOverlayStyle) Color.White else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

private enum class PodcastButtonMode {
    Play,
    Pause,
    Preparing,
}
