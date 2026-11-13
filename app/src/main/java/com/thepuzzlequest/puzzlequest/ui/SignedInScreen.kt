package com.thepuzzlequest.puzzlequest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.thepuzzlequest.puzzlequest.R
import com.thepuzzlequest.puzzlequest.core.Role
import com.thepuzzlequest.puzzlequest.core.SignedInUser
import com.thepuzzlequest.puzzlequest.ui.theme.PuzzleQuestTheme

/** A placeholder for the signed-in app. */
@Composable
fun SignedInScreen(
    user: SignedInUser,
    onSignOut: () -> Unit,
) {
    var isSigningOut by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.signed_in_as, user.displayName),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("signed-in.name"),
        )
        OutlinedButton(
            onClick = {
                isSigningOut = true
                onSignOut()
            },
            enabled = !isSigningOut,
            modifier = Modifier.testTag("signed-in.sign-out"),
        ) {
            Text(stringResource(R.string.sign_out))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SignedInPreview() {
    PuzzleQuestTheme {
        SignedInScreen(SignedInUser("alex", "Alex", Role.Player), onSignOut = {})
    }
}
