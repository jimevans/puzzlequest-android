package com.thepuzzlequest.puzzlequest.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thepuzzlequest.puzzlequest.R
import com.thepuzzlequest.puzzlequest.ui.theme.BrandType

/** The logo beside the name, as at the top of the website. */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    // mergeDescendants makes the row one thing to TalkBack, read as a heading.
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = null,
            modifier = Modifier.height(52.dp),
        )
        Text(text = "PuzzleQuest", style = BrandType.Large)
    }
}
