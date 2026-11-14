package com.thepuzzlequest.puzzlequest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thepuzzlequest.puzzlequest.AppViewModel
import com.thepuzzlequest.puzzlequest.R
import com.thepuzzlequest.puzzlequest.core.HomeScreen
import com.thepuzzlequest.puzzlequest.core.SessionStore

/**
 * Shows sign-in or the signed-in app, once the session kept from before has
 * been checked: a player's quests, or for staff and administrators, a note
 * that their tools are on the website. Options, opened from any of them,
 * shows over them.
 *
 * @param uiTesting whether to show the UI tests what they wait on.
 */
@Composable
fun RootScreen(
    app: AppViewModel,
    uiTesting: Boolean = false,
) {
    // collectAsStateWithLifecycle turns a StateFlow into Compose state: this
    // screen is redrawn whenever it changes, while it's on screen.
    val server by app.server.collectAsStateWithLifecycle()
    val options by app.options.collectAsStateWithLifecycle()
    val signUp by app.signUp.collectAsStateWithLifecycle()
    val deleteAccount by app.deleteAccount.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize()) {
        // A new server brings a new session: `key` makes Compose build every
        // screen inside afresh for it, dropping the old session's screens and
        // everything they remembered (like SwiftUI's `.id`).
        key(server) {
            val state by server.session.state.collectAsStateWithLifecycle()
            when (val current = state) {
                SessionStore.State.Restoring -> {
                    Restoring()
                }

                SessionStore.State.SignedOut -> {
                    SignInScreen(app, server.origin)
                }

                is SessionStore.State.SignedIn -> {
                    when (current.user.homeScreen) {
                        HomeScreen.QUESTS -> HomeScreen(app, server, current.user, uiTesting)
                        HomeScreen.WEBSITE -> WebsiteNoteScreen(app, current.user, server.origin)
                    }
                }

                SessionStore.State.RestoreFailed -> {
                    RestoreFailed(onRetry = app::restore, onSignOut = app::signOut)
                }
            }
        }
        // TalkBack hears which server is now in use.
        Announcement(stringResource(R.string.now_using, server.origin.displayName), server.switches)
    }
    signUp?.let { model ->
        SignUpScreen(model, server.origin, onSignUp = app::submitSignUp, onClose = app::closeSignUp)
    }
    deleteAccount?.let { model ->
        DeleteAccountDialog(model, onDelete = app::confirmDeleteAccount, onKeep = app::closeDeleteAccount)
    }
    options?.let { model ->
        OptionsScreen(
            model,
            server.origin,
            isFixed = app.connection.isFixed,
            onUseCustomAddress = app::useCustomAddress,
            onClose = app::closeOptions,
        )
    }
}

@Composable
private fun Restoring() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.restoring))
    }
}

/** A session was kept, but the server couldn't confirm it. */
@Composable
private fun RestoreFailed(
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.restore_failed_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.restore_failed_message),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry, modifier = Modifier.testTag("restore.retry")) {
            Text(stringResource(R.string.try_again))
        }
        TextButton(onClick = onSignOut, modifier = Modifier.testTag("restore.sign-out")) {
            Text(stringResource(R.string.sign_out))
        }
    }
}
