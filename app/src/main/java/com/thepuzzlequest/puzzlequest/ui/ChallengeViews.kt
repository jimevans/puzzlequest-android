package com.thepuzzlequest.puzzlequest.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thepuzzlequest.puzzlequest.QuestViewModel
import com.thepuzzlequest.puzzlequest.R
import com.thepuzzlequest.puzzlequest.core.Challenge
import com.thepuzzlequest.puzzlequest.core.ChallengeAnswer
import com.thepuzzlequest.puzzlequest.core.ChallengeAnswerError
import com.thepuzzlequest.puzzlequest.core.MessageSheet
import com.thepuzzlequest.puzzlequest.core.PuzzleMasterMessage
import com.thepuzzlequest.puzzlequest.core.QuestEntryProgress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The puzzle master's messages, in a sheet over the quest screen: those this
 * phone hasn't shown yet, newest first, or the challenge reopened. A
 * challenge is answered here, and a right answer shows the confirmation,
 * large, to show the venue's staff. Done, Back, or swiping it down puts it
 * away; the messages stay in the quest's history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageSheetView(
    sheet: MessageSheet,
    entry: QuestEntryProgress,
    quest: QuestViewModel,
) {
    val state by quest.model.state.collectAsStateWithLifecycle()
    // Opened all the way, not half: a challenge needs room for its question,
    // its answer and the keyboard, and on a small phone half a sheet would
    // leave the button below the screen.
    ModalBottomSheet(
        onDismissRequest = quest.model::dismissMessages,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.from_the_puzzle_master),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = quest.model::dismissMessages, modifier = Modifier.testTag("quest.messages.done")) {
                    Text(stringResource(R.string.done))
                }
            }
            val challenge = entry.challenge
            if (sheet.showsChallenge && challenge != null) {
                val answer = challenge.answer
                if (answer != null) {
                    ConfirmationCard(answer, entry)
                } else {
                    ChallengeQuestion(challenge, state.answeringChallenge, state.challengeError, quest::answerChallenge)
                }
            }
            sheet.messages
                .filterNot { sheet.showsChallenge && challenge?.isSetBy(it) == true }
                .forEach { MessageRow(it, large = true) }
            // Room above the phone's navigation bar.
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A challenge waiting for its answer: the question, why the last answer didn't succeed, and the field to answer it. */
@Composable
private fun ChallengeQuestion(
    challenge: Challenge,
    pending: Boolean,
    error: ChallengeAnswerError?,
    onAnswer: (String, (Boolean) -> Unit) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_chat),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.challenge_from_puzzle_master),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            challenge.message,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("quest.challenge.message"),
        )
        if (error != null) ErrorMessage(stringResource(error.message), Modifier.testTag("quest.challenge.error"))
        AnswerForm(
            label = stringResource(R.string.your_answer),
            buttonLabel = stringResource(R.string.send_the_answer),
            emptyMessage = stringResource(R.string.type_your_answer_first),
            tag = "quest.challenge.answer",
            disabled = false,
            pending = pending,
            capitalization = KeyboardCapitalization.None,
            onSubmit = onAnswer,
        )
    }
}

/** The confirmation of a challenge answered rightly, made to be turned toward the venue's staff: large, with who answered it and when. */
@Composable
private fun ConfirmationCard(
    answer: ChallengeAnswer,
    entry: QuestEntryProgress,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("quest.challenge.answered"),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null)
                Text(stringResource(R.string.show_this_to_the_staff), style = MaterialTheme.typography.titleSmall)
            }
            Text(
                answer.confirmation,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("quest.challenge.confirmation"),
            )
            entry.entrantName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            val time = timeOfDay(answer.answeredAt)
            Text(
                stringResource(if (answer.byText) R.string.answered_by_text_at else R.string.answered_at, time),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The row at the top of the quest screen that reopens the challenge: to
 * answer it while it waits, or, once answered, to show the confirmation to the
 * staff. An answered one can be hidden for good with its ✕; it's still in the
 * puzzle master's messages.
 */
@Composable
fun ChallengeRow(
    challenge: Challenge,
    onOpen: () -> Unit,
    onHide: () -> Unit,
) {
    val answered = challenge.answer != null
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Two controls in one row: each answers only its own taps.
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onOpen)
                        .semantics(mergeDescendants = true) {}
                        .testTag("quest.challenge-row")
                        .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(if (answered) R.drawable.ic_check_circle else R.drawable.ic_chat),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(if (answered) R.string.challenge_answered else R.string.challenge_waiting),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(if (answered) R.string.show_confirmation else R.string.answer),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (answered) {
                IconButton(onClick = onHide, modifier = Modifier.testTag("quest.challenge-row.hide")) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.hide_answered_challenge),
                    )
                }
            }
        }
    }
}

/** One of the puzzle master's messages: when it came, whether it set a challenge, and what it says. */
@Composable
fun MessageRow(
    message: PuzzleMasterMessage,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val style = MaterialTheme.typography.labelMedium
            Text(timeOfDay(message.sentAt), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.isChallenge) {
                Text(
                    stringResource(R.string.challenge),
                    style = style,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            message.text,
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
        )
    }
}

/** Everything the puzzle master has sent the players, newest first. A challenge's entry opens it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesHistoryScreen(
    quest: QuestViewModel,
    onOpenChallenge: (ChallengeRoute) -> Unit,
    onBack: () -> Unit,
) {
    val state by quest.model.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.from_the_puzzle_master)) },
                navigationIcon = { BackArrow(onBack, Modifier.testTag("messages.back")) },
            )
        },
    ) { padding ->
        val messages =
            state.quest
                ?.entry
                ?.messages
                .orEmpty()
                .reversed()
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("quest.messages")) {
            items(messages, key = { it.id }) { message ->
                val route = ChallengeRoute(state.quest!!.questName, message.sentAt.toEpochMilli())
                if (message.isChallenge) {
                    // A challenge opens, so it has a chevron, as the quest screen's rows do.
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(
                                    onClickLabel = stringResource(R.string.open_the_challenge),
                                ) { onOpenChallenge(route) }
                                .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MessageRow(message, modifier = Modifier.weight(1f))
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    MessageRow(message, modifier = Modifier.fillMaxWidth().padding(16.dp))
                }
                HorizontalDivider()
            }
        }
    }
}

/**
 * One of the puzzle master's challenges, from the messages: the latest
 * waiting for its answer, or answered with its confirmation for the staff;
 * an earlier one only as it was asked, since a challenge replaces the one
 * before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChallengeDetailScreen(
    quest: QuestViewModel,
    sentAt: Instant,
    onBack: () -> Unit,
) {
    val state by quest.model.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.challenge)) },
                navigationIcon = { BackArrow(onBack, Modifier.testTag("challenge.back")) },
            )
        },
    ) { padding ->
        val entry = state.quest?.entry ?: return@Scaffold
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val challenge = entry.challenge
            if (challenge != null && challenge.sentAt == sentAt) {
                val answer = challenge.answer
                if (answer != null) {
                    ConfirmationCard(answer, entry)
                    Text(stringResource(R.string.the_challenge), style = MaterialTheme.typography.titleSmall)
                    Text(challenge.message, style = MaterialTheme.typography.bodyLarge)
                } else {
                    ChallengeQuestion(challenge, state.answeringChallenge, state.challengeError, quest::answerChallenge)
                }
            } else {
                entry.messages.firstOrNull { it.isChallenge && it.sentAt == sentAt }?.let { message ->
                    MessageRow(message, large = true)
                    Text(
                        stringResource(R.string.another_challenge_since),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BackArrow(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onBack, modifier = modifier) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
    }
}

/** A time of day, as the phone shows one, in its time zone and language. */
@Composable
private fun timeOfDay(time: Instant): String =
    DateTimeFormatter
        .ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(currentLocale())
        .withZone(ZoneId.systemDefault())
        .format(time)

/** What to say about an answer that didn't succeed. */
internal val ChallengeAnswerError.message: Int
    get() =
        when (this) {
            ChallengeAnswerError.WRONG_ANSWER -> R.string.puzzle_wrong_answer
            ChallengeAnswerError.TOO_MANY_ATTEMPTS -> R.string.puzzle_too_many_attempts
            ChallengeAnswerError.CHANGED -> R.string.challenge_changed
            ChallengeAnswerError.UNREACHABLE -> R.string.puzzle_unreachable
            ChallengeAnswerError.UNEXPECTED -> R.string.puzzle_unexpected
        }
