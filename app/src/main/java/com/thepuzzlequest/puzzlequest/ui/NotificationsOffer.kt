package com.thepuzzlequest.puzzlequest.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thepuzzlequest.puzzlequest.Push
import com.thepuzzlequest.puzzlequest.R

/**
 * Offers notifications on a quest's screen, until the player answers
 * Android: what they'd be told, with Turn on, which asks Android, and Not
 * now, which stops offering on this quest. The quest screen shows it only on
 * Android 13 and later, where apps ask; before that, notifications are on
 * unless turned off in the phone's settings.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun NotificationsOffer(questName: String) {
    val context = LocalContext.current
    // Android's question about notifications, which answers here.
    val ask =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { allowed -> Push.answered(context, allowed) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("quest.notifications"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_notification),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.notifications_offer_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(stringResource(R.string.notifications_offer_message), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    modifier = Modifier.testTag("quest.notifications.turn-on"),
                ) { Text(stringResource(R.string.turn_on)) }
                OutlinedButton(
                    onClick = { Push.registrar.notNow(questName) },
                    modifier = Modifier.testTag("quest.notifications.not-now"),
                ) { Text(stringResource(R.string.not_now)) }
            }
        }
    }
}
