package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A player's quest screen: their entry's progress, kept up to date by the
 * quest's live updates, and once it has finished, the race's standings; and
 * the puzzle master's messages, each shown over the screen once as it
 * arrives, with any challenge to answer.
 *
 * @param messagesSeen which messages this phone has shown, and the answered
 *   challenge the player hid.
 */
class QuestModel(
    val questName: String,
    private val api: PuzzleQuestApi,
    private val messagesSeen: MessagesSeen = InMemoryMessagesSeen(),
    connector: LiveSocketConnector = OkHttpLiveSocketConnector(),
    makeFollower: (LiveChannel, suspend () -> String) -> LiveUpdateFollower = { channel, ticket ->
        LiveUpdateFollower(api.liveUpdatesUrl, channel, connector, ticket)
    },
) {
    data class State(
        /** The quest, once fetched; null until then, or once it isn't available. */
        val quest: PlayerQuest? = null,
        /**
         * True once the server says the player has no entry to see, as after
         * the puzzle master resets the quest.
         */
        val notAvailable: Boolean = false,
        /**
         * Whether the latest fetch failed. A screen showing the quest keeps
         * it; one that hasn't explains, with a way to try again.
         */
        val loadFailed: Boolean = false,
        /**
         * The race's standings, once the player's entry has finished and if
         * others played it; null otherwise.
         */
        val standings: Standings? = null,
        /** What changed in the latest fetch that changed anything, for TalkBack. */
        val changes: List<QuestChange> = emptyList(),
        /**
         * Counts the fetches that changed something, so the screen can tell
         * when [changes] is new, even if it's the same as before.
         */
        val changeCount: Int = 0,
        /**
         * Whether live updates are connected and the quest fetched since, so
         * from here on a change arrives as an update.
         */
        val isLive: Boolean = false,
        /** The puzzle master's messages to show over the quest screen, if any: those this phone hasn't shown, or the challenge reopened. */
        val messageSheet: MessageSheet? = null,
        /** True while the player's answer to the challenge is being sent. */
        val answeringChallenge: Boolean = false,
        /** Why the player's last answer to the challenge didn't succeed, if it didn't. */
        val challengeError: ChallengeAnswerError? = null,
        /** Counts the answers that didn't succeed, so the screen can tell TalkBack each time. */
        val challengeErrorCount: Int = 0,
        /** The answered challenge the player hid from the quest screen, by its [Challenge.id]. */
        val hiddenChallenge: String? = null,
    ) {
        /** The challenge to keep at the top of the quest screen: one waiting for its answer, or answered and not hidden. */
        val challengeAtTop: Challenge?
            get() = quest?.entry?.challenge?.takeUnless { it.answer != null && it.id == hiddenChallenge }
    }

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val follower = makeFollower(LiveChannel.Quest(questName)) { api.questLiveTicket(questName) }

    /** Fetches can overlap; only the latest one's answer is kept. */
    private var latestFetch = 0

    /** Fetches the quest, as when the screen appears or is pulled down. */
    suspend fun load() = fetch()

    /**
     * Keeps the quest up to date until cancelled (the screen goes away, or the
     * app to the background): fetches it again on every update about it (the
     * web app does the same), and each time the connection is made, since
     * changes may have been missed, as they would be in the background.
     */
    suspend fun followLiveUpdates() {
        try {
            follower.run { event ->
                when (event) {
                    is LiveEvent.Connected -> {
                        fetch()
                        mutableState.update { it.copy(isLive = true) }
                    }

                    is LiveEvent.Update -> {
                        val update = event.update
                        if (update is LiveUpdate.QuestChanged && update.questName == questName) fetch()
                    }

                    LiveEvent.Disconnected -> {
                        mutableState.update { it.copy(isLive = false) }
                    }
                }
            }
        } finally {
            mutableState.update { it.copy(isLive = false) }
        }
    }

    /**
     * Answers the challenge the player's entry is waiting for. However it
     * turns out, the quest is fetched again: a right answer shows the
     * confirmation, and a refusal often means a teammate answered first.
     *
     * @return whether the answer was right.
     */
    suspend fun answerChallenge(answer: String): Boolean {
        if (state.value.answeringChallenge) return false
        mutableState.update { it.copy(answeringChallenge = true, challengeError = null) }
        val error =
            try {
                api.answerChallenge(questName, answer)
                null
            } catch (error: ApiError) {
                ChallengeAnswerError.of(error)
            }
        if (error != null) {
            mutableState.update { it.copy(challengeError = error, challengeErrorCount = it.challengeErrorCount + 1) }
        }
        try {
            fetch()
        } finally {
            mutableState.update { it.copy(answeringChallenge = false) }
        }
        return error == null
    }

    /** Shows the challenge again, waiting or answered, as from the row at the top of the quest screen. */
    fun showChallenge() {
        if (state.value.quest
                ?.entry
                ?.challenge == null
        ) {
            return
        }
        mutableState.update { it.copy(messageSheet = MessageSheet(emptyList(), showsChallenge = true)) }
    }

    /** Takes an answered challenge off the quest screen for good on this phone; it's still in the messages. A waiting one stays. */
    fun hideAnsweredChallenge() {
        val entry = state.value.quest?.entry ?: return
        val challenge = entry.challenge?.takeIf { it.answer != null } ?: return
        messagesSeen.hideChallenge(challenge.id, entry.id)
        mutableState.update { it.copy(hiddenChallenge = challenge.id) }
    }

    /** Puts the messages away, recording them as shown on this phone. */
    fun dismissMessages() {
        val state = state.value
        val entryId = state.quest?.entry?.id
        val sheet = state.messageSheet
        if (sheet != null && entryId != null) messagesSeen.markSeen(sheet.messages.map { it.id }, entryId)
        mutableState.update { it.copy(messageSheet = null, challengeError = null) }
    }

    /** Clears the message about the last answer, as when the player edits it. */
    fun clearChallengeError() = mutableState.update { it.copy(challengeError = null) }

    /**
     * The messages to show: those this phone hasn't shown, newest first,
     * adding any that arrive while they're shown. A challenge among them is
     * shown first, to answer. Null if there's nothing new to show.
     */
    private fun unseenMessages(
        quest: PlayerQuest,
        showing: MessageSheet?,
    ): MessageSheet? {
        val seen = messagesSeen.seen(quest.entry.id)
        val shown =
            showing
                ?.messages
                .orEmpty()
                .map { it.id }
                .toSet()
        val unseen = quest.entry.messages.filter { it.id !in seen }
        if (unseen.none { it.id !in shown }) return null
        val challengeIsNew = quest.entry.challenge?.let { challenge -> unseen.any(challenge::isSetBy) } ?: false
        return MessageSheet(unseen.reversed(), showsChallenge = challengeIsNew || showing?.showsChallenge == true)
    }

    private suspend fun fetch() {
        val fetch = ++latestFetch
        val fetched: PlayerQuest?
        var fetchedStandings: Standings? = null
        try {
            fetched = api.playerQuest(questName)
            // Standings only for a finished entry: during a race, a player
            // still racing isn't shown the others.
            if (fetched != null && fetched.entry.isFinished) {
                fetchedStandings =
                    try {
                        api.standings(questName)
                    } catch (_: ApiError) {
                        null
                    }
            }
        } catch (_: ApiError) {
            if (fetch == latestFetch) mutableState.update { it.copy(loadFailed = true) }
            return
        }
        if (fetch != latestFetch) return
        mutableState.update { state ->
            if (fetched == null) {
                return@update state.copy(
                    quest = null,
                    standings = null,
                    notAvailable = true,
                    loadFailed = false,
                    messageSheet = null,
                )
            }
            val changes = mutableListOf<QuestChange>()
            state.quest?.let { changes += QuestChange.between(it, fetched) }
            val standings =
                when {
                    // A quest one player or team played has no standings to show.
                    fetchedStandings != null -> {
                        fetchedStandings.takeIf { it.entries.size >= 2 }?.also { shown ->
                            state.standings?.let { changes += QuestChange.between(it, shown, fetched.entry.id) }
                        }
                    }

                    // Keep what was shown if only the standings couldn't be fetched.
                    fetched.entry.isFinished -> {
                        state.standings
                    }

                    else -> {
                        null
                    }
                }
            state.copy(
                quest = fetched,
                notAvailable = false,
                loadFailed = false,
                standings = standings,
                hiddenChallenge = messagesSeen.hiddenChallenge(fetched.entry.id),
                messageSheet = unseenMessages(fetched, state.messageSheet) ?: state.messageSheet,
                changes = if (changes.isEmpty()) state.changes else changes,
                changeCount = state.changeCount + if (changes.isEmpty()) 0 else 1,
            )
        }
    }
}

/** The puzzle master's messages shown over the quest screen. */
data class MessageSheet(
    /** The messages to show, newest first: those not shown before, or none when the challenge is reopened. */
    val messages: List<PuzzleMasterMessage>,
    /** Whether the entry's challenge is shown, to answer or, once answered, with its confirmation. */
    val showsChallenge: Boolean,
)

/** Why a player's answer to a challenge didn't succeed. */
enum class ChallengeAnswerError {
    WRONG_ANSWER,

    /** Too many tries in a short time. */
    TOO_MANY_ATTEMPTS,

    /** No challenge is waiting any more: a teammate answered it first, or the quest changed; the screen now shows where it stands. */
    CHANGED,
    UNREACHABLE,
    UNEXPECTED,
    ;

    companion object {
        fun of(error: ApiError): ChallengeAnswerError =
            when (error) {
                is ApiError.Refused -> {
                    when (error.problem.type) {
                        Problem.Types.WRONG_ANSWER -> WRONG_ANSWER
                        Problem.Types.TOO_MANY_REQUESTS -> TOO_MANY_ATTEMPTS
                        Problem.Types.WRONG_STATE -> CHANGED
                        else -> if (error.problem.status in setOf(403, 404)) CHANGED else UNEXPECTED
                    }
                }

                ApiError.Unreachable -> {
                    UNREACHABLE
                }

                is ApiError.UnexpectedResponse -> {
                    UNEXPECTED
                }
            }
    }
}
