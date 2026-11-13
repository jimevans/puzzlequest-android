package com.thepuzzlequest.puzzlequest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thepuzzlequest.puzzlequest.HomeViewModel
import com.thepuzzlequest.puzzlequest.R
import com.thepuzzlequest.puzzlequest.core.EmailReminderFailure
import com.thepuzzlequest.puzzlequest.core.EmailReminderModel

/** Asks the player to verify their email address, with a way to have the email sent again. */
@Composable
fun EmailReminder(home: HomeViewModel) {
    val state by home.model.emailReminder.state
        .collectAsStateWithLifecycle()
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Icon(
                painterResource(R.drawable.ic_mail),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.verify_email))
        }
        OutlinedButton(
            onClick = home::sendVerificationEmail,
            enabled = home.model.emailReminder.canSend,
            modifier = Modifier.testTag("email-reminder.send"),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.send_email_again))
                if (state == EmailReminderModel.State.Sending) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        }
        val tag = Modifier.testTag("email-reminder.result")
        when (val current = state) {
            EmailReminderModel.State.Sent -> {
                StatusMessage(
                    stringResource(R.string.email_sent),
                    R.drawable.ic_done,
                    MaterialTheme.colorScheme.primary,
                    tag,
                )
            }

            is EmailReminderModel.State.Failed -> {
                ErrorMessage(stringResource(current.failure.message), tag)
            }

            else -> {}
        }
    }
}

/** What the player is told. */
private val EmailReminderFailure.message: Int
    get() =
        when (this) {
            EmailReminderFailure.TOO_MANY_REQUESTS -> R.string.email_too_many
            EmailReminderFailure.UNREACHABLE -> R.string.sign_in_unreachable
            EmailReminderFailure.UNEXPECTED -> R.string.sign_in_unexpected
        }
