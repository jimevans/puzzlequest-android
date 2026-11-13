package com.thepuzzlequest.puzzlequest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.thepuzzlequest.puzzlequest.AppViewModel
import com.thepuzzlequest.puzzlequest.R
import com.thepuzzlequest.puzzlequest.core.SignedInUser

/**
 * What staff and administrators see: the app is for players, and their tools
 * are on the website.
 */
@Composable
fun WebsiteNoteScreen(
    app: AppViewModel,
    user: SignedInUser,
) {
    val website = app.api.server
    val uriHandler = LocalUriHandler.current
    var signingOut by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark()
        Text(
            stringResource(R.string.website_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.website_note, user.displayName),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("website.note"),
        )
        Button(onClick = { uriHandler.openUri(website) }, modifier = Modifier.testTag("website.open")) {
            Text(stringResource(R.string.open_website, website.toUri().host ?: website))
        }
        TextButton(
            onClick = {
                signingOut = true
                app.signOut()
            },
            enabled = !signingOut,
            modifier = Modifier.testTag("website.sign-out"),
        ) {
            Text(stringResource(R.string.sign_out))
        }
    }
}
