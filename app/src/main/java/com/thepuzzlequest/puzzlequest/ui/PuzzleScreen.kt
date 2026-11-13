package com.thepuzzlequest.puzzlequest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.thepuzzlequest.puzzlequest.PuzzleViewModel
import com.thepuzzlequest.puzzlequest.R
import com.thepuzzlequest.puzzlequest.core.MarkdownBlock
import com.thepuzzlequest.puzzlequest.core.NextHint
import com.thepuzzlequest.puzzlequest.core.PlayerPuzzle
import com.thepuzzlequest.puzzlequest.core.PuzzleAction
import com.thepuzzlequest.puzzlequest.core.PuzzleActionError
import com.thepuzzlequest.puzzlequest.core.PuzzleChange
import com.thepuzzlequest.puzzlequest.core.PuzzleContent
import com.thepuzzlequest.puzzlequest.core.PuzzleModel
import com.thepuzzlequest.puzzlequest.core.QuestClock
import com.thepuzzlequest.puzzlequest.core.RevealedHint
import com.thepuzzlequest.puzzlequest.core.StopStatus

/**
 * A stop's puzzle, as the web app's puzzle screen: waiting to be found and
 * activated (by scanning its QR code or typing its code), being solved (what
 * it asks, its hints, and a place to answer), or solved (the solution, and on
 * to the next stop, or the finish). Like the quest screen, it keeps itself up
 * to date through live updates, tells TalkBack users what changes, and never
 * says how many stops there are.
 *
 * @param server the site, which a text puzzle's links and pictures are on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PuzzleScreen(
    route: PuzzleRoute,
    puzzle: PuzzleViewModel,
    server: String,
    uiTesting: Boolean,
    onBack: () -> Unit,
) {
    val state by puzzle.model.state.collectAsStateWithLifecycle()
    val refreshing by puzzle.refreshing.collectAsStateWithLifecycle()

    // Follows live updates while the app is in the foreground; each return
    // connects again, which fetches the puzzle.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(puzzle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { puzzle.model.followLiveUpdates() }
    }

    val title = state.puzzle?.displayName ?: if (state.puzzleName == route.puzzleName) route.displayName else ""
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("puzzle.back")) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
            val shown = state.puzzle
            when {
                shown != null -> {
                    PullToRefreshBox(isRefreshing = refreshing, onRefresh = puzzle::refresh) {
                        // A new puzzle starts afresh, without the last one's typing.
                        key(shown.puzzleName) {
                            PuzzleDetails(shown, state, puzzle, server, onBack)
                        }
                    }
                }

                state.notAvailable -> {
                    Unavailable(
                        stringResource(R.string.puzzle_not_available),
                        stringResource(R.string.puzzle_not_available_message),
                        stringResource(R.string.back_to_quest),
                        onBack,
                        Modifier.testTag("puzzle.not-available"),
                    )
                }

                state.loadFailed -> {
                    Unavailable(
                        stringResource(R.string.puzzle_load_failed),
                        stringResource(R.string.check_connection),
                        stringResource(R.string.try_again),
                        puzzle::retry,
                        Modifier.testTag("puzzle.error"),
                        buttonModifier = Modifier.testTag("puzzle.retry"),
                    )
                }

                else -> {
                    Loading(stringResource(R.string.loading_puzzle))
                }
            }
            Announcement(changeAnnouncement(state.changes), state.changeCount)
            Announcement(state.actionError?.let { stringResource(it.message) }.orEmpty(), state.actionErrorCount)
            Announcement(stringResource(R.string.puzzle_no_longer_available), if (state.notAvailable) 1 else 0)
            if (uiTesting) LiveUpdatesMarker("puzzle.live-updates", state.isLive)
        }
    }
}

/** The puzzle, by where it stands. */
@Composable
private fun PuzzleDetails(
    puzzle: PlayerPuzzle,
    state: PuzzleModel.State,
    actions: PuzzleViewModel,
    server: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Header(puzzle)
        if (puzzle.paused) {
            StatusMessage(
                stringResource(R.string.quest_paused_banner),
                R.drawable.ic_paused,
                MaterialTheme.colorScheme.tertiary,
                Modifier.testTag("puzzle.paused"),
            )
        }
        if (puzzle.status == StopStatus.AWAITING_ACTIVATION) Activation(puzzle, state, actions)
        puzzle.content?.let { PuzzleContentView(it, puzzle.displayName, server) }
        if (puzzle.hints.isNotEmpty()) Hints(puzzle.hints)
        if (puzzle.status == StopStatus.IN_PROGRESS) Solving(puzzle, state, actions)
        if (puzzle.status == StopStatus.COMPLETED) Solved(puzzle, state, actions, onBack)
    }
}

@Composable
private fun Header(puzzle: PlayerPuzzle) {
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("puzzle.header"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.stop_number, puzzle.stopNumber).uppercase(currentLocale()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            puzzle.displayName,
            style = com.thepuzzlequest.puzzlequest.ui.theme.BrandType.Title,
            modifier = Modifier.semantics { heading() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val style = MaterialTheme.typography.bodyMedium
            Text(stringResource(R.string.total_time), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ClockText(puzzle.clock, ClockPart.TOTAL, style)
        }
    }
}

/** Why the player's last action didn't succeed. */
@Composable
private fun ActionError(error: PuzzleActionError?) {
    if (error != null) ErrorMessage(stringResource(error.message), Modifier.testTag("puzzle.action-error"))
}

/**
 * Starting a puzzle the players have found: scanning its QR code where the
 * phone can (Google's scanner opens over the app), or typing its code.
 */
@Composable
private fun Activation(
    puzzle: PlayerPuzzle,
    state: PuzzleModel.State,
    actions: PuzzleViewModel,
) {
    val context = LocalContext.current
    val canScan = remember { CodeScanner.isAvailable(context) }
    var scanFailed by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(
                when {
                    scanFailed -> R.string.activate_scan_failed
                    canScan -> R.string.activate_scan_or_type
                    else -> R.string.activate_type
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        ActionError(state.actionError)
        if (canScan) {
            Button(
                onClick = {
                    actions.model.clearActionError()
                    scanFailed = false
                    CodeScanner.scan(context, onCode = actions::scanned, onFailed = { scanFailed = true })
                },
                enabled = !puzzle.paused && state.pending == null,
                modifier = Modifier.fillMaxWidth().testTag("puzzle.scan-code"),
            ) {
                Icon(painterResource(R.drawable.ic_qr_code_scanner), contentDescription = null)
                Text(stringResource(R.string.scan_qr_code), modifier = Modifier.padding(start = 8.dp))
            }
        }
        AnswerForm(
            label = stringResource(R.string.activation_code),
            buttonLabel = stringResource(R.string.start_this_puzzle),
            emptyMessage = stringResource(R.string.enter_the_code),
            tag = "puzzle.activation-code",
            disabled = puzzle.paused,
            pending = state.pending == PuzzleAction.ACTIVATING,
            capitalization = KeyboardCapitalization.Characters,
            onSubmit = actions::activate,
        )
    }
}

/** Solving: a place to answer, and the next hint, if there is one. */
@Composable
private fun Solving(
    puzzle: PlayerPuzzle,
    state: PuzzleModel.State,
    actions: PuzzleViewModel,
) {
    var askingToGiveAway by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ActionError(state.actionError)
        AnswerForm(
            label = stringResource(R.string.your_answer),
            buttonLabel = stringResource(R.string.check_answer),
            emptyMessage = stringResource(R.string.enter_your_answer),
            tag = "puzzle.answer",
            disabled = puzzle.paused,
            pending = state.pending == PuzzleAction.SOLVING,
            capitalization = KeyboardCapitalization.None,
            onSubmit = actions::solve,
        )
        puzzle.nextHint?.let { next ->
            val spoken = hintButtonSpoken(next)
            OutlinedButton(
                onClick = {
                    actions.model.clearActionError()
                    if (next.givesSolutionAway) askingToGiveAway = true else actions.revealHint()
                },
                enabled = !puzzle.paused && state.pending == null,
                modifier = Modifier.semantics { contentDescription = spoken }.testTag("puzzle.reveal-hint"),
            ) {
                Text(
                    if (next.penaltySeconds > 0) {
                        stringResource(R.string.reveal_hint_cost, QuestClock.format(next.penaltySeconds * 1000))
                    } else {
                        stringResource(R.string.reveal_hint)
                    },
                )
            }
        }
    }
    if (askingToGiveAway) {
        AlertDialog(
            onDismissRequest = { askingToGiveAway = false },
            title = { Text(stringResource(R.string.give_away_solution)) },
            text = { Text(stringResource(R.string.give_away_solution_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askingToGiveAway = false
                        actions.revealHint()
                    },
                    modifier = Modifier.testTag("puzzle.show-solution-hint"),
                ) { Text(stringResource(R.string.show_it)) }
            },
            dismissButton = {
                TextButton(onClick = { askingToGiveAway = false }, modifier = Modifier.testTag("puzzle.keep-trying")) {
                    Text(stringResource(R.string.keep_trying))
                }
            },
        )
    }
}

/** What TalkBack says for the hint button: its cost in words, such as "adding 1 minute to your time". */
@Composable
private fun hintButtonSpoken(next: NextHint): String =
    if (next.penaltySeconds > 0) {
        stringResource(R.string.reveal_hint_cost_spoken, spoken(next.penaltySeconds * 1000))
    } else {
        stringResource(R.string.reveal_hint)
    }

/** Solved, or skipped: the solution, then on to the next stop, or the finish. */
@Composable
private fun Solved(
    puzzle: PlayerPuzzle,
    state: PuzzleModel.State,
    actions: PuzzleViewModel,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ActionError(state.actionError)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(if (puzzle.skipped) R.drawable.ic_skip_next else R.drawable.ic_check_circle),
                    contentDescription = null,
                    tint =
                        if (puzzle.skipped) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                )
                Text(
                    stringResource(if (puzzle.skipped) R.string.skipped else R.string.solved),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            puzzle.solutionText?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("puzzle.solution"))
            }
        }
        val next = puzzle.next
        if (next != null) {
            Button(onClick = actions::goToNextStop, modifier = Modifier.fillMaxWidth().testTag("puzzle.next-stop")) {
                Text(stringResource(R.string.on_to, next.displayName))
            }
        } else if (puzzle.questFinished) {
            EndOfTheLine(puzzle.questDisplayName, puzzle.clock.totalMs, Modifier.testTag("puzzle.finished"))
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag("puzzle.back-to-quest")) {
                Text(stringResource(R.string.back_to_quest))
            }
        }
    }
}

/** The hints revealed so far, and what each cost. */
@Composable
private fun Hints(hints: List<RevealedHint>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.hints),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        hints.forEachIndexed { index, hint ->
            Column(Modifier.semantics(mergeDescendants = true) {}.testTag("puzzle.hint.${index + 1}")) {
                Text(
                    if (hint.penaltySeconds > 0) {
                        val cost = QuestClock.format(hint.penaltySeconds * 1000)
                        stringResource(R.string.hint_number_cost, index + 1, cost)
                    } else {
                        stringResource(R.string.hint_number, index + 1)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(hint.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * One line to send: an activation code, or a guess at the solution. The
 * field empties once what was sent is accepted.
 *
 * @param tag the field's test tag; its button's adds ".send".
 * @param onSubmit sends it, and calls back with whether it was accepted.
 */
@Composable
internal fun AnswerForm(
    label: String,
    buttonLabel: String,
    emptyMessage: String,
    tag: String,
    disabled: Boolean,
    pending: Boolean,
    capitalization: KeyboardCapitalization,
    onSubmit: (String, (Boolean) -> Unit) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    var empty by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val submit = {
        val trimmed = value.trim()
        empty = trimmed.isEmpty()
        if (!empty && !pending && !disabled) {
            // The keyboard closes, so the result can be seen.
            focus.clearFocus()
            onSubmit(trimmed) { accepted -> if (accepted) value = "" }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                value = it
                empty = false
            },
            label = { Text(label) },
            singleLine = true,
            enabled = !disabled,
            isError = empty,
            supportingText = if (empty) ({ Text(emptyMessage) }) else null,
            // An answer or code is typed exactly as meant.
            keyboardOptions =
                KeyboardOptions(
                    capitalization = capitalization,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send,
                ),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
        val sending = stringResource(R.string.sending)
        Button(
            onClick = submit,
            enabled = !disabled && !pending,
            modifier = Modifier.fillMaxWidth().testTag("$tag.send"),
        ) {
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).semantics { contentDescription = sending },
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
            } else {
                Text(buttonLabel)
            }
        }
    }
}

/** What a puzzle asks: its text, picture, recording, or film. */
@Composable
private fun PuzzleContentView(
    content: PuzzleContent,
    displayName: String,
    server: String,
) {
    val modifier = Modifier.testTag("puzzle.content")
    when (content) {
        is PuzzleContent.Text -> {
            // Read once for each text, not each time the screen is drawn.
            val blocks = remember(content.markdown, server) { MarkdownBlock.blocks(content.markdown, server) }
            MarkdownView(blocks, modifier)
        }

        is PuzzleContent.Image -> {
            RemoteImage(content.url, displayName, modifier)
        }

        is PuzzleContent.Audio -> {
            MediaPlayerView(content.url, video = false, description = displayName, modifier = modifier)
        }

        is PuzzleContent.Video -> {
            MediaPlayerView(content.url, video = true, description = displayName, modifier = modifier)
        }
    }
}

/** What TalkBack says about the latest changes, as the web app says them. */
@Composable
private fun changeAnnouncement(changes: List<PuzzleChange>): String =
    changes
        .map { change ->
            when (change) {
                PuzzleChange.Paused -> {
                    stringResource(R.string.announce_paused)
                }

                PuzzleChange.Resumed -> {
                    stringResource(R.string.announce_resumed)
                }

                PuzzleChange.Started -> {
                    stringResource(R.string.announce_puzzle_started)
                }

                is PuzzleChange.Solved -> {
                    val solution = change.solution
                    if (solution != null) {
                        stringResource(R.string.announce_solved_with, solution)
                    } else {
                        stringResource(R.string.announce_solved)
                    }
                }

                PuzzleChange.Skipped -> {
                    stringResource(R.string.announce_skipped)
                }

                is PuzzleChange.HintRevealed -> {
                    stringResource(R.string.announce_hint, change.text)
                }

                PuzzleChange.QuestFinished -> {
                    stringResource(R.string.announce_finished)
                }
            }
        }.joinToString(" ")

/** Why an action didn't succeed, in words for the player. */
private val PuzzleActionError.message: Int
    get() =
        when (this) {
            PuzzleActionError.WRONG_CODE -> R.string.puzzle_wrong_code
            PuzzleActionError.WRONG_ANSWER -> R.string.puzzle_wrong_answer
            PuzzleActionError.PAUSED -> R.string.puzzle_paused
            PuzzleActionError.TOO_MANY_ATTEMPTS -> R.string.puzzle_too_many_attempts
            PuzzleActionError.NO_MORE_HINTS -> R.string.puzzle_no_more_hints
            PuzzleActionError.CHANGED -> R.string.puzzle_changed
            PuzzleActionError.TRY_AGAIN -> R.string.puzzle_try_again
            PuzzleActionError.UNREACHABLE -> R.string.puzzle_unreachable
            PuzzleActionError.UNEXPECTED -> R.string.puzzle_unexpected
        }
