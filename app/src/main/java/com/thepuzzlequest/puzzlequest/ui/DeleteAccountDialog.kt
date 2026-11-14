package com.thepuzzlequest.puzzlequest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thepuzzlequest.puzzlequest.R
import com.thepuzzlequest.puzzlequest.core.DeleteAccountFailure
import com.thepuzzlequest.puzzlequest.core.DeleteAccountModel

/**
 * Deleting the player's own account, as the website's Delete account page
 * does: what goes, then their password to confirm. Once it's done, the app
 * is signed out and shows the sign-in screen.
 */
@Composable
fun DeleteAccountDialog(
    model: DeleteAccountModel,
    onDelete: (password: String) -> Unit,
    onKeep: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val password = rememberTextFieldState()
    val canDelete = model.canDelete(password.text.toString())
    val delete = { if (canDelete) onDelete(password.text.toString()) }
    AlertDialog(
        // Not dismissed halfway through deleting.
        onDismissRequest = { if (!state.isDeleting) onKeep() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.delete_account_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.delete_account_explanation),
                    modifier = Modifier.testTag("delete-account.explanation"),
                )
                OutlinedSecureTextField(
                    state = password,
                    label = { Text(stringResource(R.string.delete_account_password)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    onKeyboardAction = { delete() },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentType = ContentType.Password }
                            .testTag("delete-account.password"),
                )
                state.failure?.let {
                    ErrorMessage(stringResource(it.message), Modifier.testTag("delete-account.error"))
                }
            }
        },
        confirmButton = {
            val deleting = stringResource(R.string.deleting_account)
            TextButton(
                onClick = delete,
                enabled = canDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("delete-account.delete"),
            ) {
                if (state.isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).semantics { contentDescription = deleting },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.delete_my_account))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onKeep,
                enabled = !state.isDeleting,
                modifier = Modifier.testTag("delete-account.keep"),
            ) {
                Text(stringResource(R.string.keep_my_account))
            }
        },
    )
}

/** What the person is told. */
private val DeleteAccountFailure.message: Int
    get() =
        when (this) {
            DeleteAccountFailure.INCORRECT_PASSWORD -> R.string.delete_account_incorrect_password
            DeleteAccountFailure.TOO_MANY_ATTEMPTS -> R.string.delete_account_too_many_attempts
            DeleteAccountFailure.NOT_POSSIBLE -> R.string.delete_account_not_possible
            DeleteAccountFailure.UNREACHABLE -> R.string.sign_in_unreachable
            DeleteAccountFailure.UNEXPECTED -> R.string.sign_in_unexpected
        }
