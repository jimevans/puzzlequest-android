package com.thepuzzlequest.puzzlequest.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thepuzzlequest.puzzlequest.AppViewModel
import com.thepuzzlequest.puzzlequest.R
import com.thepuzzlequest.puzzlequest.core.SignInFailure

@Composable
fun SignInScreen(app: AppViewModel) {
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

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
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
            state.failure?.let { FailureMessage(it) }
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
        }
    }
}

/**
 * Why signing in failed: an icon as well as color, so it doesn't rely on color
 * alone, and read out by TalkBack as soon as it's shown (a live region).
 */
@Composable
private fun FailureMessage(failure: SignInFailure) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.error) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    .testTag("sign-in.error"),
        ) {
            Icon(painterResource(R.drawable.ic_error), contentDescription = null)
            Text(stringResource(failure.message), style = MaterialTheme.typography.bodyMedium)
        }
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
