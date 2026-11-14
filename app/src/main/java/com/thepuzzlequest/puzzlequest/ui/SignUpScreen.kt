package com.thepuzzlequest.puzzlequest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thepuzzlequest.puzzlequest.R
import com.thepuzzlequest.puzzlequest.core.FieldProblem
import com.thepuzzlequest.puzzlequest.core.ServerOrigin
import com.thepuzzlequest.puzzlequest.core.SignUpFailure
import com.thepuzzlequest.puzzlequest.core.SignUpField
import com.thepuzzlequest.puzzlequest.core.SignUpModel

/**
 * Signing up, with the fields the website's form has, over the sign-in
 * screen. Signing up signs the new player straight in, and the app shows
 * their home screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignUpScreen(
    model: SignUpModel,
    server: ServerOrigin,
    onSignUp: () -> Unit,
    onClose: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val form = state.form
    val focus = LocalFocusManager.current
    // One FocusRequester per field, so the first field that's wrong can be
    // given the keyboard.
    val requesters = remember { SignUpField.entries.associateWith { FocusRequester() } }
    val submit = {
        focus.clearFocus()
        onSignUp()
    }
    LaunchedEffect(state.fieldProblems) {
        SignUpField.entries.firstOrNull { it in state.fieldProblems }?.let { requesters.getValue(it).requestFocus() }
    }

    // The whole screen, as Options is.
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DialogBarIcons()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.sign_up)) },
                    navigationIcon = {
                        IconButton(onClick = onClose, modifier = Modifier.testTag("sign-up.close")) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    @Composable
                    fun field(
                        name: SignUpField,
                        value: String,
                        label: Int,
                        onChange: (String) -> Unit,
                        contentType: ContentType,
                        keyboard: KeyboardOptions,
                        secret: Boolean = false,
                        supporting: String? = null,
                    ) {
                        val problem = state.fieldProblems[name]?.let { fieldProblemMessage(it) }
                        OutlinedTextField(
                            value = value,
                            onValueChange = onChange,
                            label = { Text(stringResource(label)) },
                            singleLine = true,
                            isError = problem != null,
                            supportingText = (problem ?: supporting)?.let { text -> { Text(text) } },
                            visualTransformation =
                                if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = keyboard,
                            keyboardActions =
                                KeyboardActions(
                                    onNext = { focus.moveFocus(FocusDirection.Down) },
                                    onDone = { submit() },
                                ),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(requesters.getValue(name))
                                    .semantics {
                                        this.contentType = contentType
                                        // TalkBack says what's wrong as it reads the field.
                                        if (problem != null) error(problem)
                                    }.testTag("sign-up.${name.apiName}"),
                        )
                    }

                    val next = ImeAction.Next
                    field(
                        SignUpField.USER_NAME,
                        form.userName,
                        R.string.user_name,
                        { text -> model.edit { it.copy(userName = text) } },
                        ContentType.NewUsername,
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = next,
                        ),
                    )
                    field(
                        SignUpField.PASSWORD,
                        form.password,
                        R.string.password,
                        { text -> model.edit { it.copy(password = text) } },
                        ContentType.NewPassword,
                        KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = next),
                        secret = true,
                        supporting =
                            pluralStringResource(
                                R.plurals.password_length,
                                SignUpModel.MINIMUM_PASSWORD_LENGTH,
                                SignUpModel.MINIMUM_PASSWORD_LENGTH,
                            ),
                    )
                    field(
                        SignUpField.CONFIRM_PASSWORD,
                        form.confirmPassword,
                        R.string.confirm_password,
                        { text -> model.edit { it.copy(confirmPassword = text) } },
                        ContentType.NewPassword,
                        KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = next),
                        secret = true,
                    )
                    field(
                        SignUpField.DISPLAY_NAME,
                        form.displayName,
                        R.string.display_name,
                        { text -> model.edit { it.copy(displayName = text) } },
                        ContentType.PersonFullName,
                        KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = next),
                    )
                    field(
                        SignUpField.EMAIL,
                        form.email,
                        R.string.email,
                        { text -> model.edit { it.copy(email = text) } },
                        ContentType.EmailAddress,
                        KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = next,
                        ),
                        supporting = stringResource(R.string.email_will_be_checked),
                    )
                    field(
                        SignUpField.PHONE,
                        form.phone,
                        R.string.phone_optional,
                        { text -> model.edit { it.copy(phone = text) } },
                        ContentType.PhoneNumber,
                        KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                        supporting = stringResource(R.string.phone_note),
                    )
                    SmsConsent(
                        checked = form.sms,
                        onChange = { checked -> model.edit { it.copy(sms = checked) } },
                        server = server,
                    )
                    state.failure?.let { ErrorMessage(failureMessage(it), Modifier.testTag("sign-up.error")) }
                    val signingUp = stringResource(R.string.signing_up)
                    Button(
                        onClick = submit,
                        enabled = !state.isSigningUp,
                        modifier = Modifier.fillMaxWidth().testTag("sign-up.submit"),
                    ) {
                        if (state.isSigningUp) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).semantics { contentDescription = signingUp },
                                strokeWidth = 2.dp,
                                color = LocalContentColor.current,
                            )
                        } else {
                            Text(stringResource(R.string.sign_up))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The checkbox agreeing to text messages, with the consent the website shows,
 * word for word, as the SMS provider requires, and the privacy policy.
 */
@Composable
private fun SmsConsent(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    server: ServerOrigin,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The whole row toggles it, and TalkBack reads it as one checkbox.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
                    .testTag("sign-up.sms"),
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(stringResource(R.string.allow_sms), modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            stringResource(R.string.sms_consent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("sign-up.sms-consent"),
        )
        val uriHandler = LocalUriHandler.current
        TextButton(
            onClick = { uriHandler.openUri(server.privacyPolicy) },
            modifier = Modifier.testTag("sign-up.privacy"),
        ) {
            Text(stringResource(R.string.privacy_policy))
        }
    }
}

/** What the person is told, beneath the field. */
@Composable
private fun fieldProblemMessage(problem: FieldProblem): String =
    when (problem) {
        FieldProblem.Required -> {
            stringResource(R.string.field_required)
        }

        FieldProblem.PasswordTooShort -> {
            pluralStringResource(
                R.plurals.password_too_short,
                SignUpModel.MINIMUM_PASSWORD_LENGTH,
                SignUpModel.MINIMUM_PASSWORD_LENGTH,
            )
        }

        FieldProblem.PasswordsDiffer -> {
            stringResource(R.string.passwords_differ)
        }

        is FieldProblem.Server -> {
            problem.message
        }
    }

/** What the person is told, above the button. */
@Composable
private fun failureMessage(failure: SignUpFailure): String =
    when (failure) {
        is SignUpFailure.AlreadyInUse -> stringResource(R.string.sign_up_already_in_use, failure.message)
        SignUpFailure.TooManyAttempts -> stringResource(R.string.sign_up_too_many_attempts)
        SignUpFailure.Unreachable -> stringResource(R.string.sign_in_unreachable)
        SignUpFailure.SignedUpButNotSignedIn -> stringResource(R.string.sign_up_but_not_signed_in)
        SignUpFailure.Unexpected -> stringResource(R.string.sign_in_unexpected)
    }
