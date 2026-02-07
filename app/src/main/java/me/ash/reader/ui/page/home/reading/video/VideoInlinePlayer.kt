package me.ash.reader.ui.page.home.reading.video

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun VideoInlinePlayer(videoUrl: String?) {
    if (videoUrl.isNullOrBlank()) return

    val context = LocalContext.current
    val mediaController = remember(context) { MediaController(context) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
        tonalElevation = 0.dp,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().padding(8.dp).aspectRatio(16f / 9f),
            factory = {
                VideoView(it).apply {
                    setMediaController(mediaController)
                    mediaController.setAnchorView(this)
                    tag = videoUrl
                    setVideoURI(Uri.parse(videoUrl))
                    requestFocus()
                }
            },
            update = { view ->
                if (view.tag != videoUrl) {
                    view.stopPlayback()
                    view.tag = videoUrl
                    view.setVideoURI(Uri.parse(videoUrl))
                }
            },
        )
    }
}
