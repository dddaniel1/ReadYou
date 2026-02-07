package me.ash.reader.infrastructure.android

import android.media.AudioAttributes
import android.media.MediaPlayer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.di.ApplicationScope
import timber.log.Timber

@Singleton
class PodcastPlayerManager
@Inject
constructor(@ApplicationScope private val coroutineScope: CoroutineScope) {

    sealed interface State {
        data object Idle : State

        data class Preparing(val url: String) : State

        data class Playing(
            val url: String,
            val positionMs: Int,
            val durationMs: Int,
        ) : State

        data class Paused(
            val url: String,
            val positionMs: Int,
            val durationMs: Int,
        ) : State

        data class Error(val message: String) : State
    }

    private val _stateFlow = MutableStateFlow<State>(State.Idle)
    val stateFlow = _stateFlow.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    var state: State
        get() = stateFlow.value
        private set(value) {
            _stateFlow.value = value
        }

    fun toggle(url: String) {
        when (val currentState = state) {
            is State.Playing -> {
                if (currentState.url == url) {
                    pause()
                } else {
                    play(url)
                }
            }

            is State.Paused -> {
                if (currentState.url == url) {
                    resume()
                } else {
                    play(url)
                }
            }

            is State.Preparing,
            is State.Error,
            State.Idle,
            -> play(url)
        }
    }

    fun play(url: String) {
        stop()

        val player = MediaPlayer()
        mediaPlayer = player
        state = State.Preparing(url)

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        )
        player.setOnPreparedListener {
            it.start()
            updatePlayingState(url)
            startProgressUpdates(url)
        }
        player.setOnCompletionListener {
            stopPlayer(resetState = true)
        }
        player.setOnErrorListener { _, what, extra ->
            stopPlayer(resetState = false)
            state = State.Error("MediaPlayer error: $what/$extra")
            true
        }

        runCatching {
                player.setDataSource(url)
                player.prepareAsync()
            }
            .onFailure {
                stopPlayer(resetState = false)
                state = State.Error(it.message ?: "Failed to play podcast")
            }
    }

    fun pause() {
        val player = mediaPlayer ?: return
        if (!player.isPlaying) return

        player.pause()
        progressJob?.cancel()
        val current = player.currentPosition
        val duration = max(player.duration, 0)
        val currentUrl = currentPodcastUrl() ?: return
        state = State.Paused(currentUrl, current, duration)
    }

    fun resume() {
        val player = mediaPlayer ?: return
        val currentUrl = currentPodcastUrl() ?: return
        if (player.isPlaying) return

        player.start()
        updatePlayingState(currentUrl)
        startProgressUpdates(currentUrl)
    }

    fun stop() {
        stopPlayer(resetState = true)
    }

    fun seekTo(positionMs: Int) {
        val player = mediaPlayer ?: return
        val currentUrl = currentPodcastUrl() ?: return
        val duration = max(player.duration, 0)
        val target = positionMs.coerceIn(0, duration)
        runCatching { player.seekTo(target) }
            .onFailure {
                state = State.Error(it.message ?: "Failed to seek podcast")
            }
            .onSuccess {
                state =
                    if (player.isPlaying) {
                        State.Playing(currentUrl, target, duration)
                    } else {
                        State.Paused(currentUrl, target, duration)
                    }
            }
    }

    private fun updatePlayingState(url: String) {
        val player = mediaPlayer ?: return
        val duration = max(player.duration, 0)
        val position = player.currentPosition
        state = State.Playing(url = url, positionMs = position, durationMs = duration)
    }

    private fun startProgressUpdates(url: String) {
        progressJob?.cancel()
        progressJob = coroutineScope.launch {
            while (isActive) {
                val player = mediaPlayer
                if (player == null || !player.isPlaying) break
                state =
                    State.Playing(
                        url = url,
                        positionMs = player.currentPosition,
                        durationMs = max(player.duration, 0),
                    )
                delay(400L)
            }
        }
    }

    private fun currentPodcastUrl(): String? {
        return when (val current = state) {
            is State.Playing -> current.url
            is State.Paused -> current.url
            is State.Preparing -> current.url
            is State.Error,
            State.Idle,
            -> null
        }
    }

    private fun stopPlayer(resetState: Boolean) {
        progressJob?.cancel()
        progressJob = null
        mediaPlayer
            ?.runCatching {
                stop()
                reset()
                release()
            }
            ?.onFailure { Timber.d(it) }
        mediaPlayer = null
        if (resetState) {
            state = State.Idle
        }
    }
}
