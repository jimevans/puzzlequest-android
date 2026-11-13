package com.thepuzzlequest.puzzlequest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thepuzzlequest.puzzlequest.AppViewModel
import com.thepuzzlequest.puzzlequest.R
import com.thepuzzlequest.puzzlequest.core.ServerOrigin
import com.thepuzzlequest.puzzlequest.core.SignInFailure

/**
 * Signing in, to the server chosen in Options, which it names unless it's
 * Production.
 */
@Composable
fun SignInScreen(
    app: AppViewModel,
    server: ServerOrigin,
) {
    val state by app.signIn.state.collectAsStateWithLifecycle()
    // What's typed is the screen's own; rememberTextFieldState keeps it when
    // the screen is rebuilt, as on rotating.
    val userName = rememberTextFieldState()
    val password = rememberTextFieldState()
    val focus = LocalFocusManager.current
    val canSignIn = app.signIn.canSignIn(userName.text.toString(), password.text.toString())
    val signIn = {
        if (canSignIn) {
            focus.clearFocus()
            app.signIn(userName.text.toString(), password.text.toString())
        }
    }

    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandMark(modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.sign_in_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                if (server != ServerOrigin.PRODUCTION) ServerNote(server)
                OutlinedTextField(
                    state = userName,
                    label = { Text(stringResource(R.string.user_name)) },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next,
                        ),
                    onKeyboardAction = { focus.moveFocus(FocusDirection.Down) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // Lets password managers fill it in.
                            .semantics { contentType = ContentType.Username }
                            .testTag("sign-in.user-name"),
                )
                OutlinedSecureTextField(
                    state = password,
                    label = { Text(stringResource(R.string.password)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    onKeyboardAction = { signIn() },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentType = ContentType.Password }
                            .testTag("sign-in.password"),
                )
                state.failure?.let { ErrorMessage(stringResource(it.message), Modifier.testTag("sign-in.error")) }
                val signingIn = stringResource(R.string.signing_in)
                Button(
                    onClick = signIn,
                    enabled = canSignIn,
                    modifier = Modifier.fillMaxWidth().testTag("sign-in.submit"),
                ) {
                    if (state.isSigningIn) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).semantics { contentDescription = signingIn },
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                    } else {
                        Text(stringResource(R.string.sign_in))
                    }
                }
                // Signing up is on the website until the app has it.
                val uriHandler = LocalUriHandler.current
                TextButton(
                    onClick = { uriHandler.openUri(server.url) },
                    modifier = Modifier.align(Alignment.CenterHorizontally).testTag("sign-in.sign-up"),
                ) {
                    Text(stringResource(R.string.sign_up_on_website))
                }
            }
        }
        OptionsButton(
            onClick = app::openOptions,
            modifier = Modifier.align(Alignment.TopEnd).testTag("sign-in.options"),
        )
    }
}

/** Which server this is, when it isn't Production: worth knowing, but not a warning. */
@Composable
private fun ServerNote(server: ServerOrigin) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("sign-in.server"),
    ) {
        Icon(
            painterResource(R.drawable.ic_dns),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(R.string.signing_in_to, server.displayName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the person is told. */
private val SignInFailure.message: Int
    get() =
        when (this) {
            SignInFailure.WRONG_USER_NAME_OR_PASSWORD -> R.string.sign_in_wrong_user_name_or_password
            SignInFailure.TOO_MANY_ATTEMPTS -> R.string.sign_in_too_many_attempts
            SignInFailure.INVALID_REQUEST -> R.string.sign_in_invalid_request
            SignInFailure.UNREACHABLE -> R.string.sign_in_unreachable
            SignInFailure.UNEXPECTED -> R.string.sign_in_unexpected
        }
