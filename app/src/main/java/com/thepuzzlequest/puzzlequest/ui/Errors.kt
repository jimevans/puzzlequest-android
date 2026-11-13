package com.thepuzzlequest.puzzlequest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thepuzzlequest.puzzlequest.R

/**
 * Something that went wrong, or a result: an icon as well as color, so it
 * doesn't rely on color alone, and read out by TalkBack as soon as it's shown
 * (a live region).
 */
@Composable
fun StatusMessage(
    text: String,
    icon: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalContentColor provides color) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Icon(painterResource(icon), contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ErrorMessage(
    text: String,
    modifier: Modifier = Modifier,
) = StatusMessage(text, R.drawable.ic_error, MaterialTheme.colorScheme.error, modifier)
