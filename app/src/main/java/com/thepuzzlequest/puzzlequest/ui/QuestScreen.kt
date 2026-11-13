package com.thepuzzlequest.puzzlequest.ui

import android.icu.text.ListFormatter
import android.icu.text.MessageFormat
import android.icu.text.NumberFormat
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.thepuzzlequest.puzzlequest.Push
import com.thepuzzlequest.puzzlequest.QuestViewModel
import com.thepuzzlequest.puzzlequest.R
import com.thepuzzlequest.puzzlequest.core.Challenge
import com.thepuzzlequest.puzzlequest.core.PlayerQuest
import com.thepuzzlequest.puzzlequest.core.QuestChange
import com.thepuzzlequest.puzzlequest.core.QuestClock
import com.thepuzzlequest.puzzlequest.core.QuestEntryProgress
import com.thepuzzlequest.puzzlequest.core.QuestModel
import com.thepuzzlequest.puzzlequest.core.Standing
import com.thepuzzlequest.puzzlequest.core.Standings
import com.thepuzzlequest.puzzlequest.core.Stop
import com.thepuzzlequest.puzzlequest.ui.theme.Brand
import com.thepuzzlequest.puzzlequest.ui.theme.BrandType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * A player's quest: the stop they're on, first and largest, then the stops
 * they've solved, each opening to its details. It never says how many stops
 * the quest has. Once their entry has finished: the finish, and in a race,
 * the standings. It keeps itself up to date through the quest's live
 * updates, and tells TalkBack users what changes, but not the clocks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestScreen(
    route: QuestRoute,
    quest: QuestViewModel,
    uiTesting: Boolean,
    onBack: () -> Unit,
    onOpenStop: (StopRoute) -> Unit,
    onOpenPuzzle: (PuzzleRoute) -> Unit,
    onOpenMessages: (MessagesRoute) -> Unit,
) {
    val state by quest.model.state.collectAsStateWithLifecycle()
    val refreshing by quest.refreshing.collectAsStateWithLifecycle()

    // While it's on screen, its notifications aren't shown: its live updates
    // show what changed.
    val permission by Push.registrar.permission.collectAsStateWithLifecycle()
    val declined by Push.registrar.declined.collectAsStateWithLifecycle()
    val offersNotifications =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            Push.registrar.offers(route.questName, permission, declined)
    DisposableEffect(route.questName) {
        Push.registrar.questOnScreen = route.questName
        onDispose { if (Push.registrar.questOnScreen == route.questName) Push.registrar.questOnScreen = null }
    }

    // Follows live updates while the app is in the foreground; each return
    // connects again, which fetches the quest, since changes may have been
    // missed.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(quest) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { quest.model.followLiveUpdates() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.quest?.displayName ?: route.displayName, modifier = Modifier.testTag("quest.title"))
                },
                navigationIcon = { BackButton(onBack, Modifier.testTag("quest.back")) },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val shown = state.quest
            when {
                shown != null -> {
                    PullToRefreshBox(isRefreshing = refreshing, onRefresh = quest::refresh) {
                        QuestProgressList(
                            shown,
                            offersNotifications,
                            state.standings,
                            state.challengeAtTop,
                            onOpenStop,
                            onOpenPuzzle,
                            onOpenMessages,
                            onShowChallenge = quest.model::showChallenge,
                            onHideChallenge = quest.model::hideAnsweredChallenge,
                        )
                    }
                    state.messageSheet?.let { sheet ->
                        MessageSheetView(sheet, shown.entry, quest)
                    }
                }

                state.notAvailable -> {
                    Unavailable(
                        stringResource(R.string.quest_not_available),
                        stringResource(R.string.quest_not_available_message),
                        stringResource(R.string.back_to_quests),
                        onBack,
                        Modifier.testTag("quest.not-available"),
                    )
                }

                state.loadFailed -> {
                    Unavailable(
                        stringResource(R.string.quest_load_failed),
                        stringResource(R.string.check_connection),
                        stringResource(R.string.try_again),
                        quest::retry,
                        Modifier.testTag("quest.error"),
                        buttonModifier = Modifier.testTag("quest.retry"),
                    )
                }

                else -> {
                    Loading(stringResource(R.string.loading_quest))
                }
            }
            Announcement(announcement(state.changes), state.changeCount)
            Announcement(stringResource(R.string.quest_no_longer_available), if (state.notAvailable) 1 else 0)
            Announcement(state.challengeError?.let { stringResource(it.message) }.orEmpty(), state.challengeErrorCount)
            if (uiTesting) LiveUpdatesMarker("quest.live-updates", state.isLive)
        }
    }
}

/** The quest's progress, as a list: the pause, the total time, the stop being played, the finish and standings, and the stops solved. */
@Composable
private fun QuestProgressList(
    quest: PlayerQuest,
    offersNotifications: Boolean,
    standings: Standings?,
    challengeAtTop: Challenge?,
    onOpenStop: (StopRoute) -> Unit,
    onOpenPuzzle: (PuzzleRoute) -> Unit,
    onOpenMessages: (MessagesRoute) -> Unit,
    onShowChallenge: () -> Unit,
    onHideChallenge: () -> Unit,
) {
    val entry = quest.entry
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("quest.list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Android 13 and later ask about notifications; earlier ones don't.
        if (offersNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            item(key = "notifications") { NotificationsOffer(quest.questName) }
        }
        // The puzzle master's challenge comes first: the players may be at a
        // venue, with its staff, waiting on it.
        challengeAtTop?.let { challenge ->
            item(key = "challenge") { ChallengeRow(challenge, onOpen = onShowChallenge, onHide = onHideChallenge) }
        }
        if (entry.messages.isNotEmpty()) {
            item(
                key = "messages",
            ) { MessagesRow(entry.messages.size) { onOpenMessages(MessagesRoute(quest.questName)) } }
        }
        if (entry.paused) {
            item {
                StatusMessage(
                    stringResource(R.string.quest_paused_banner),
                    R.drawable.ic_paused,
                    MaterialTheme.colorScheme.tertiary,
                    Modifier.testTag("quest.paused"),
                )
            }
        }
        item { Summary(entry) }
        entry.currentStop?.let { stop ->
            item {
                CurrentStopCard(
                    stop,
                    onOpen = { onOpenPuzzle(PuzzleRoute(quest.questName, stop.puzzleName, stop.displayName)) },
                )
            }
        }
        if (entry.isFinished) {
            item { Finish(quest) }
            standings?.let { standingsSection(it, entry) }
        }
        if (entry.finishedStops.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.solved)) }
            items(entry.finishedStops, key = { it.puzzleName }) { stop ->
                SolvedStopRow(
                    stop,
                    onClick = { onOpenStop(StopRoute(stop.puzzleName)) },
                    modifier = Modifier.testTag("quest.solved.${stop.puzzleName}"),
                )
                HorizontalDivider()
            }
        }
    }
}

/** The total time, with hint penalties, and where the quest stands. */
@Composable
private fun Summary(entry: QuestEntryProgress) {
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("quest.summary"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(R.string.total_time),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ClockText(entry.clock, ClockPart.TOTAL, BrandType.Large)
        Text(
            if (entry.clock.penaltyMs > 0) {
                stringResource(
                    R.string.status_with_penalties,
                    entry.statusDescription,
                    QuestClock.format(entry.clock.penaltyMs),
                )
            } else {
                entry.statusDescription
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The stop being played, large, on the brand's aubergine, as on the website:
 * its number, name, where it stands, and its time. Tapping it opens its
 * puzzle.
 */
@Composable
private fun CurrentStopCard(
    stop: Stop,
    onOpen: () -> Unit,
) {
    val open = stringResource(R.string.open_the_puzzle)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brand.Aubergine)
                // TalkBack says what tapping does: "Double-tap to open the puzzle".
                .clickable(onClickLabel = open, onClick = onOpen)
                .semantics(mergeDescendants = true) {}
                .testTag("quest.current-stop")
                .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.stop_number, stop.number).uppercase(currentLocale()),
            style = MaterialTheme.typography.labelLarge,
            color = Brand.AubergineSoft,
        )
        Text(stop.displayName, style = BrandType.Title, color = Color.White)
        Text(stop.statusDescription, style = MaterialTheme.typography.bodyLarge, color = Brand.AubergineSoft)
        stop.clock?.let {
            ClockText(
                it,
                ClockPart.TOTAL,
                MaterialTheme.typography.titleLarge.copy(color = Color.White),
            )
        }
        // What tapping does, in gold; TalkBack hears it as the action instead.
        Text(
            open,
            style = MaterialTheme.typography.titleSmall,
            color = Brand.Aubergine,
            modifier =
                Modifier
                    .padding(top = 6.dp)
                    .clip(CircleShape)
                    .background(Brand.Gold)
                    .clearAndSetSemantics {}
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Finish(quest: PlayerQuest) =
    EndOfTheLine(quest.displayName, quest.entry.clock.totalMs, Modifier.testTag("quest.finished"))

/** A quest's finish, in gold on aubergine, as on the website: on the quest's screen, and its last puzzle's. */
@Composable
fun EndOfTheLine(
    questDisplayName: String,
    totalMs: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brand.Aubergine)
                .semantics(mergeDescendants = true) {}
                .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The line ending at its last stop; it's decoration, so TalkBack skips it.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clearAndSetSemantics {}) {
            Box(Modifier.weight(1f).height(6.dp).background(Brand.Gold))
            Box(Modifier.size(24.dp).clip(CircleShape).background(Brand.Gold))
        }
        Text(
            stringResource(R.string.end_of_the_line),
            style = BrandType.Title,
            color = Color.White,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.quest_finished, questDisplayName, QuestClock.format(totalMs)),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
        )
    }
}

/** The row that opens everything the puzzle master has sent, with how many. */
@Composable
private fun MessagesRow(
    count: Int,
    onOpen: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .semantics(mergeDescendants = true) {}
                .testTag("quest.messages-row")
                .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_chat),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.from_the_puzzle_master),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            NumberFormat.getInstance(currentLocale()).format(count),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

/** A solved stop, short: a check mark, its number and name, and its time. Tapping it opens its details. */
@Composable
private fun SolvedStopRow(
    stop: Stop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val how = stringResource(if (stop.skipped) R.string.skipped else R.string.solved)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) {}
                .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // How it ended in words for TalkBack, not just by icon.
        Icon(
            painterResource(if (stop.skipped) R.drawable.ic_skip_next else R.drawable.ic_check_circle),
            contentDescription = how,
            tint = if (stop.skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
        // Side by side, wrapping under each other when the text is large.
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(min = 120.dp).weight(1f)) {
                Text(
                    stringResource(R.string.stop_number, stop.number),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stop.displayName, style = MaterialTheme.typography.bodyLarge)
            }
            stop.clock?.let {
                ClockText(
                    it,
                    ClockPart.TOTAL,
                    MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    Modifier.align(Alignment.CenterVertically),
                )
            }
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A race's standings, once the player's entry has finished: each entry's place, name, status, and time, with their own marked. */
private fun LazyListScope.standingsSection(
    standings: Standings,
    ownEntry: QuestEntryProgress,
) {
    item {
        SectionTitle(stringResource(if (standings.isFinal) R.string.final_standings else R.string.standings))
    }
    items(standings.entries, key = { "standing-${it.id}" }) { entry ->
        StandingRow(entry, standings, ownEntry)
        HorizontalDivider()
    }
    val note =
        when {
            standings.provisional -> R.string.standings_provisional
            standings.rivalsHidden -> R.string.standings_hidden
            else -> null
        }
    note?.let {
        item {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StandingRow(
    entry: Standing,
    standings: Standings,
    ownEntry: QuestEntryProgress,
) {
    val own = entry.id == ownEntry.id
    val winner = standings.isFinal && entry.rank == 1
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .testTag("quest.standing.${entry.id}")
                .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val notRanked = stringResource(R.string.not_ranked)
        Text(
            entry.rank?.let { ordinal(it, currentLocale()) } ?: "–",
            style = MaterialTheme.typography.titleMedium,
            // A dash on screen; words for TalkBack.
            modifier =
                Modifier.widthIn(min = 40.dp).then(
                    if (entry.rank == null) Modifier.semantics { contentDescription = notRanked } else Modifier,
                ),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name(entry),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (own) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (winner || own) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (winner) Badge(R.drawable.ic_star, stringResource(R.string.winner))
                    if (own) {
                        Badge(
                            R.drawable.ic_person,
                            stringResource(if (ownEntry.isTeam) R.string.your_team else R.string.you),
                        )
                    }
                }
            }
            Text(
                if (entry.paused) {
                    stringResource(
                        R.string.standing_paused,
                        entry.statusDescription,
                    )
                } else {
                    entry.statusDescription
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.started) {
            ClockText(entry.clock, ClockPart.TOTAL, MaterialTheme.typography.bodyLarge)
        } else {
            val notStarted = stringResource(R.string.not_started)
            Text("–", modifier = Modifier.semantics { contentDescription = notStarted })
        }
    }
}

@Composable
private fun Badge(
    icon: Int,
    text: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * A solved stop's details: when it was reached, found, and solved; its
 * times; and its solution. It stays up to date with the quest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopDetailScreen(
    model: QuestModel,
    puzzleName: String,
    onBack: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val stop =
        state.quest
            ?.entry
            ?.stops
            ?.firstOrNull { it.puzzleName == puzzleName }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stop?.let { stringResource(R.string.stop_number, it.number) }.orEmpty()) },
                navigationIcon = { BackButton(onBack, Modifier.testTag("stop.back")) },
            )
        },
    ) { padding ->
        if (stop == null) {
            // Gone, as when the quest was reset while this was open.
            Unavailable(
                stringResource(R.string.stop_not_available),
                null,
                stringResource(R.string.back),
                onBack,
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(
                        stringResource(R.string.stop_number, stop.number),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stop.displayName, style = BrandType.Title)
                    Text(stop.statusDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            stop.solutionText?.let { solution ->
                item { SectionTitle(stringResource(R.string.solution)) }
                item {
                    Text(
                        solution,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("stop.solution"),
                    )
                }
            }
            item { SectionTitle(stringResource(R.string.times)) }
            item {
                Column {
                    Moment(stringResource(R.string.started), stop.startTime)
                    Moment(stringResource(R.string.activated), stop.activationTime)
                    Moment(stringResource(R.string.finished), stop.endTime)
                    stop.clock?.let { clock ->
                        Labeled(stringResource(R.string.time)) { ClockText(clock, ClockPart.ELAPSED, it) }
                        if (clock.penaltyMs > 0) {
                            Labeled(stringResource(R.string.hint_penalties)) {
                                ClockText(
                                    QuestClock(false, null, clock.penaltyMs, 0, clock.penaltyMs),
                                    ClockPart.ELAPSED,
                                    it,
                                )
                            }
                            Labeled(stringResource(R.string.total)) { ClockText(clock, ClockPart.TOTAL, it) }
                        }
                    }
                }
            }
        }
    }
}

/** A label and its value on one line, read by TalkBack as one. */
@Composable
private fun Labeled(
    label: String,
    value: @Composable (androidx.compose.ui.text.TextStyle) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(16.dp))
        value(MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
    }
}

/** When something happened, as a time of day in the phone's time zone and language. */
@Composable
private fun Moment(
    label: String,
    time: Instant?,
) {
    if (time == null) return
    val locale = currentLocale()
    Labeled(label) { style ->
        Text(
            DateTimeFormatter
                .ofLocalizedTime(FormatStyle.MEDIUM)
                .withLocale(locale)
                .withZone(ZoneId.systemDefault())
                .format(time),
            style = style,
        )
    }
}

@Composable
private fun BackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onBack, modifier = modifier) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
    }
}

@Composable
internal fun Loading(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(text)
    }
}

/** Something that can't be shown: why, and what to do. */
@Composable
internal fun Unavailable(
    title: String,
    message: String?,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        message?.let {
            Text(it, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onAction, modifier = buttonModifier) { Text(action) }
    }
}

/** What TalkBack says about the latest changes, as the web app says them. */
@Composable
private fun announcement(changes: List<QuestChange>): String {
    val locale = currentLocale()
    return changes
        .map { change ->
            when (change) {
                QuestChange.Paused -> {
                    stringResource(R.string.announce_paused)
                }

                QuestChange.Resumed -> {
                    stringResource(R.string.announce_resumed)
                }

                is QuestChange.StopReached -> {
                    stringResource(R.string.announce_stop_reached, change.number, change.displayName)
                }

                is QuestChange.StopSolved -> {
                    stringResource(R.string.announce_stop_solved, change.displayName)
                }

                QuestChange.Finished -> {
                    stringResource(R.string.announce_finished)
                }

                is QuestChange.RivalFinished -> {
                    stringResource(R.string.announce_rival_finished, name(change.entry), ordinal(change.rank, locale))
                }

                is QuestChange.MessageReceived -> {
                    stringResource(
                        if (change.isChallenge) R.string.announce_challenge else R.string.announce_message,
                        change.text,
                    )
                }

                QuestChange.ChallengeAnswered -> {
                    stringResource(R.string.announce_challenge_answered)
                }

                is QuestChange.StandingsFinal -> {
                    stringResource(
                        R.string.announce_final_standings,
                        ListFormatter.getInstance(locale).format(change.winners.map { name(it) }),
                    )
                }
            }
        }.joinToString(" ")
}

/**
 * The language the app is shown in. Read through Compose, rather than with
 * Locale.getDefault(), the screen is redrawn if the phone's language changes.
 */
@Composable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** An entry's name, or what it was if it has been deleted. */
@Composable
private fun name(entry: Standing): String =
    entry.displayName ?: stringResource(if (entry.isTeam) R.string.deleted_team else R.string.deleted_player)

/** A place in the standings, such as "1st", in the phone's language. */
private fun ordinal(
    rank: Int,
    locale: Locale,
): String = MessageFormat("{0,ordinal}", locale).format(arrayOf(rank))

/**
 * For the UI tests only (debug builds started with PQ_UI_TESTING): whether
 * live updates are connected, so a test can wait for it before changing
 * something.
 */
@Composable
fun LiveUpdatesMarker(
    tag: String,
    isLive: Boolean,
) {
    Box(
        Modifier.size(1.dp).testTag(tag).semantics {
            stateDescription = if (isLive) "connected" else "connecting"
        },
    )
}
