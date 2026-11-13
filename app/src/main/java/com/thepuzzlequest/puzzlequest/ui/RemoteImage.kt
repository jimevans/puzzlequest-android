package com.thepuzzlequest.puzzlequest.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thepuzzlequest.puzzlequest.R
import com.thepuzzlequest.puzzlequest.core.PuzzleQuestApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * A picture from the server, as wide as the screen at most, loaded with the
 * app's own HTTP client: a spinner while it loads, and a note if it can't be.
 *
 * @param description what TalkBack says it is; "Picture" if there's none.
 */
@Composable
fun RemoteImage(
    url: String,
    description: String?,
    modifier: Modifier = Modifier,
) {
    // produceState runs the loading while the picture is on screen, and
    // starts again for a new address.
    val picture by produceState<Result<Bitmap>?>(null, url) { value = load(url) }
    val spoken = description ?: stringResource(R.string.picture)
    val shown = picture
    when {
        shown == null -> {
            Box(modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(32.dp))
            }
        }

        shown.isSuccess -> {
            Image(
                shown.getOrThrow().asImageBitmap(),
                contentDescription = spoken,
                contentScale = ContentScale.FillWidth,
                modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
            )
        }

        else -> {
            StatusMessage(
                stringResource(R.string.picture_failed),
                R.drawable.ic_error,
                MaterialTheme.colorScheme.onSurfaceVariant,
                modifier.semantics {
                    contentDescription = spoken
                    role = Role.Image
                },
            )
        }
    }
}

/** The largest a picture is decoded, in pixels on its longer side: plenty for a phone's screen. */
private const val LARGEST_SIDE = 2048

/** Fetches and decodes a picture, shrinking a large one as it's decoded so it can't use up the phone's memory. */
private suspend fun load(url: String): Result<Bitmap> =
    withContext(Dispatchers.IO) {
        try {
            val bytes =
                PuzzleQuestApi.defaultHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body.bytes()
                }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > LARGEST_SIDE) sample *= 2
            val bitmap =
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply {
                        inSampleSize =
                            sample
                    },
                )
                    ?: throw IOException("not a picture")
            Result.success(bitmap)
        } catch (error: IOException) {
            Result.failure(error)
        } catch (error: IllegalArgumentException) {
            // OkHttp's refusal of an address it can't use.
            Result.failure(error)
        }
    }
