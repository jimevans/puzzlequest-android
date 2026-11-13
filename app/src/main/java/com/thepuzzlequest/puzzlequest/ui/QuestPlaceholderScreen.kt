package com.thepuzzlequest.puzzlequest.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.thepuzzlequest.puzzlequest.R

/** Stands in for the quest's own screen, which comes next. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestPlaceholderScreen(
    route: QuestRoute,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(route.displayName, modifier = Modifier.testTag("quest.title")) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("quest.back")) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.coming_soon),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("quest.placeholder"),
            )
        }
    }
}
