package com.thepuzzlequest.puzzlequest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.thepuzzlequest.puzzlequest.AppViewModel
import com.thepuzzlequest.puzzlequest.HomeViewModel
import com.thepuzzlequest.puzzlequest.PreferencesMessagesSeen
import com.thepuzzlequest.puzzlequest.Push
import com.thepuzzlequest.puzzlequest.PuzzleViewModel
import com.thepuzzlequest.puzzlequest.QuestViewModel
import com.thepuzzlequest.puzzlequest.R
import com.thepuzzlequest.puzzlequest.core.SignedInUser
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.time.Instant

/** The list of quests, the home screen's first page. */
@Serializable
object QuestsRoute

/** A quest, opened from the list. */
@Serializable
data class QuestRoute(
    val questName: String,
    val displayName: String,
)

/** The puzzle of the stop being played, opened from its quest. */
@Serializable
data class PuzzleRoute(
    val questName: String,
    val puzzleName: String,
    val displayName: String,
)

/** Everything the puzzle master has sent, opened from the quest. */
@Serializable
data class MessagesRoute(
    val questName: String,
)

/** One of the puzzle master's challenges, by when it was sent (milliseconds since 1970), opened from the messages. */
@Serializable
data class ChallengeRoute(
    val questName: String,
    val sentAt: Long,
)

/** A solved stop's details, opened from its quest. */
@Serializable
data class StopRoute(
    val puzzleName: String,
)

/**
 * A player's home: the quests they play, kept up to date as they change, and
 * a reminder to verify their email address until they have. Tapping a quest
 * opens it; Back returns to the list.
 */
@Composable
fun HomeScreen(
    app: AppViewModel,
    server: AppViewModel.Server,
    user: SignedInUser,
    uiTesting: Boolean,
) {
    // One HomeViewModel for each player signed in to each server; `key` keeps
    // a new player's, or a new server's, from reusing the last one's.
    val home = viewModel(key = "home-${server.switches}-${user.userName}") { HomeViewModel(user, server.session) }
    val navigation = rememberNavController()

    // Notifications: once allowed and Firebase has given the phone's token,
    // it's registered with this player's session.
    val token by Push.registrar.token.collectAsStateWithLifecycle()
    val permission by Push.registrar.permission.collectAsStateWithLifecycle()
    LaunchedEffect(token, permission, server) { Push.registrar.register(server.session.api) }

    // A tapped notification opens its quest, over whatever was open, named as
    // in the list if it's there.
    val questToOpen by Push.registrar.questToOpen.collectAsStateWithLifecycle()
    LaunchedEffect(questToOpen) {
        val questName = questToOpen ?: return@LaunchedEffect
        val listed =
            home.model.state.value.quests
                ?.firstOrNull { it.questName == questName }
        navigation.navigate(QuestRoute(questName, listed?.displayName ?: questName)) {
            popUpTo<QuestsRoute>()
        }
        Push.registrar.questOpened()
    }

    NavHost(navigation, startDestination = QuestsRoute) {
        composable<QuestsRoute> {
            QuestList(app, home, user, uiTesting, onOpen = { navigation.navigate(it) })
        }
        composable<QuestRoute> { entry ->
            val route = entry.toRoute<QuestRoute>()
            // Inside a destination, viewModel() gives the screen one of its
            // own, kept until the player goes back from it.
            val context = LocalContext.current.applicationContext
            val quest =
                viewModel { QuestViewModel(route.questName, server.session.api, PreferencesMessagesSeen(context)) }
            QuestScreen(
                route,
                quest,
                uiTesting,
                onBack = { navigation.popBackStack() },
                onOpenStop = { navigation.navigate(it) },
                onOpenPuzzle = { navigation.navigate(it) },
                onOpenMessages = { navigation.navigate(it) },
            )
        }
        // The messages and a challenge use the quest's own ViewModel, from
        // its place in the back stack beneath them, so they follow its
        // updates.
        composable<MessagesRoute> { entry ->
            val questEntry = remember(entry) { navigation.getBackStackEntry<QuestRoute>() }
            MessagesHistoryScreen(
                viewModel<QuestViewModel>(questEntry),
                onOpenChallenge = { navigation.navigate(it) },
                onBack = { navigation.popBackStack() },
            )
        }
        composable<ChallengeRoute> { entry ->
            val questEntry = remember(entry) { navigation.getBackStackEntry<QuestRoute>() }
            ChallengeDetailScreen(
                viewModel<QuestViewModel>(questEntry),
                Instant.ofEpochMilli(entry.toRoute<ChallengeRoute>().sentAt),
                onBack = { navigation.popBackStack() },
            )
        }
        composable<PuzzleRoute> { entry ->
            val route = entry.toRoute<PuzzleRoute>()
            val puzzle = viewModel { PuzzleViewModel(route.questName, route.puzzleName, server.session.api) }
            PuzzleScreen(route, puzzle, server.origin.url, uiTesting, onBack = { navigation.popBackStack() })
        }
        composable<StopRoute> { entry ->
            // The quest's own ViewModel, from the quest's place in the back
            // stack beneath this one, so the details follow the quest's
            // updates.
            val questEntry = remember(entry) { navigation.getBackStackEntry<QuestRoute>() }
            val quest = viewModel<QuestViewModel>(questEntry)
            StopDetailScreen(quest.model, entry.toRoute<StopRoute>().puzzleName, onBack = { navigation.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuestList(
    app: AppViewModel,
    home: HomeViewModel,
    user: SignedInUser,
    uiTesting: Boolean,
    onOpen: (QuestRoute) -> Unit,
) {
    val state by home.model.state.collectAsStateWithLifecycle()
    val refreshing by home.refreshing.collectAsStateWithLifecycle()

    // Follows live updates while the app is in the foreground, and stops in
    // the background, where the connection would be dropped anyway. Each
    // return connects again, which fetches the list, since changes may have
    // been missed.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(home) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { home.model.followLiveUpdates() }
    }
    // Back in the foreground, who is signed in is checked again too: an email
    // address may have been verified on the website meanwhile.
    var started by rememberSaveable { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        if (started) app.refreshUser()
        started = true
        onStopOrDispose {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.your_quests)) },
                actions = { AccountMenu(user, onSignOut = app::signOut, onOptions = app::openOptions) },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = home::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("home.list"),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (!user.emailVerified) {
                    item {
                        EmailReminder(home)
                        HorizontalDivider()
                    }
                }
                val quests = state.quests
                when {
                    quests == null && state.loadFailed -> {
                        item { LoadFailed(onRetry = home::retry) }
                    }

                    quests == null -> {
                        item { Loading() }
                    }

                    quests.isEmpty() -> {
                        item {
                            Text(
                                stringResource(R.string.no_quests),
                                modifier = Modifier.padding(16.dp).testTag("home.empty"),
                            )
                        }
                    }

                    else -> {
                        items(quests, key = { it.questName }) { quest ->
                            QuestRow(
                                quest,
                                onClick = { onOpen(QuestRoute(quest.questName, quest.displayName)) },
                                modifier = Modifier.testTag("home.quest.${quest.questName}"),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
            Announcement(stringResource(R.string.quests_updated), state.liveChanges)
            if (uiTesting) LiveUpdatesMarker("home.live-updates", state.isLive)
        }
    }
}

/** Signing out and Options, in a menu that says who is signed in. */
@Composable
private fun AccountMenu(
    user: SignedInUser,
    onSignOut: () -> Unit,
    onOptions: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var signingOut by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = !signingOut, modifier = Modifier.testTag("home.account")) {
            Icon(painterResource(R.drawable.ic_account), contentDescription = stringResource(R.string.account))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.signed_in_as, user.displayName),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("home.signed-in-as"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sign_out)) },
                onClick = {
                    open = false
                    signingOut = true
                    onSignOut()
                },
                modifier = Modifier.testTag("home.sign-out"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.options)) },
                onClick = {
                    open = false
                    onOptions()
                },
                modifier = Modifier.testTag("home.options"),
            )
        }
    }
}

@Composable
private fun Loading() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.loading_quests))
    }
}

/** The quests couldn't be fetched, and none are shown: why, and a way to try again. */
@Composable
private fun LoadFailed(onRetry: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ErrorMessage(stringResource(R.string.quests_load_failed), Modifier.testTag("home.error"))
        OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("home.retry")) {
            Text(stringResource(R.string.try_again))
        }
    }
}

/**
 * Tells TalkBack users something has changed on its own, each time [count]
 * goes up: a live region, which TalkBack reads when its words change, says
 * [message] for a few seconds, then nothing again.
 */
@Composable
fun Announcement(
    message: String,
    count: Int,
) {
    var saying by remember { mutableStateOf("") }
    LaunchedEffect(count) {
        if (count == 0) return@LaunchedEffect
        saying = message
        delay(3_000)
        saying = ""
    }
    Box(
        Modifier.size(1.dp).semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = saying
        },
    )
}
