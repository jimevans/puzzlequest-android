package com.thepuzzlequest.puzzlequest.ui

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri

/**
 * A recording or film, with Android's own playback controls (VideoView and
 * MediaController, which play sound-only files too). It's an Android view
 * inside Compose: AndroidView makes it once, in its factory, and keeps it
 * while it's on screen. Playback stops when it leaves the screen.
 *
 * @param video whether there's a picture to show; a recording gets a strip
 *   just tall enough for its controls.
 */
@Composable
fun MediaPlayerView(
    url: String,
    video: Boolean,
    description: String,
    modifier: Modifier = Modifier,
) {
    // A new address is a new player: key throws the old one away.
    key(url) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    contentDescription = description
                    setVideoURI(url.toUri())
                    val controls = MediaController(context)
                    controls.setAnchorView(this)
                    setMediaController(controls)
                    // Shows the first frame and the controls once it's ready, without playing.
                    setOnPreparedListener {
                        seekTo(1)
                        controls.show(0)
                    }
                }
            },
            // When it leaves the screen.
            onRelease = { it.stopPlayback() },
            modifier =
                modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .then(if (video) Modifier.aspectRatio(16f / 9f) else Modifier.height(96.dp)),
        )
    }
}
