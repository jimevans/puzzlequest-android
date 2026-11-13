package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A player's puzzle screen, as the web app's: the stop's puzzle, kept up to
 * date by the quest's live updates, and the player's actions on it:
 * activating it with its code, guessing, and revealing hints. "On to the next
 * stop" moves the same screen on to the next puzzle.
 *
 * @param retryAfterNoAnswer how long to wait before sending a scanned code
 *   again after the server didn't answer.
 * @param timeSource measures that wait; tests use one of their own.
 */
class PuzzleModel(
    val questName: String,
    puzzleName: String,
    private val api: PuzzleQuestApi,
    connector: LiveSocketConnector = OkHttpLiveSocketConnector(),
    private val retryAfterNoAnswer: Duration = 5.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    makeFollower: (LiveChannel, suspend () -> String) -> LiveUpdateFollower = { channel, ticket ->
        LiveUpdateFollower(api.liveUpdatesUrl, channel, connector, ticket)
    },
) {
    data class State(
        /** The puzzle on screen; it changes on going to the next stop. */
        val puzzleName: String,
        /** The puzzle, once fetched; null until then, or once it isn't available. */
        val puzzle: PlayerPuzzle? = null,
        /** True once the server says the player can't see the puzzle, as after the puzzle master resets the quest. */
        val notAvailable: Boolean = false,
        /**
         * Whether the latest fetch failed. A screen showing the puzzle keeps
         * it; one that hasn't explains, with a way to try again.
         */
        val loadFailed: Boolean = false,
        /**
         * The action being sent, if any. It stays pending until the puzzle has
         * been fetched again, so the old state can't be acted on meanwhile.
         */
        val pending: PuzzleAction? = null,
        /** Why the latest action didn't succeed, until the next one. */
        val actionError: PuzzleActionError? = null,
        /** Counts the actions that didn't succeed, so the screen can tell TalkBack each time, even the same one again. */
        val actionErrorCount: Int = 0,
        /** What changed in the latest fetch that changed anything, for TalkBack. */
        val changes: List<PuzzleChange> = emptyList(),
        /** Counts the fetches that changed something, so the screen can tell when [changes] is new. */
        val changeCount: Int = 0,
        /** Whether live updates are connected and the puzzle fetched since. */
        val isLive: Boolean = false,
    )

    private val mutableState = MutableStateFlow(State(puzzleName))
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val follower = makeFollower(LiveChannel.Quest(questName)) { api.questLiveTicket(questName) }

    /**
     * The live-update connection, sent with the player's own actions so that
     * their updates aren't sent back: the screen shows the result itself.
     */
    @Volatile private var connectionId: String? = null

    /** Fetches can overlap; only the latest one's answer is kept. */
    private var latestFetch = 0

    /**
     * The scanner may hand over the same code again, and wrong codes are
     * limited, so a code is sent once, or again only after the server didn't
     * answer (and then not until [retryAfterNoAnswer] has passed).
     */
    private var lastScan: Pair<String, TimeMark?>? = null

    /** Fetches the puzzle, as when the screen appears or is pulled down. */
    suspend fun load() = fetch()

    /**
     * Keeps the puzzle up to date until cancelled: fetches it again on every
     * update about the quest, and each time the connection is made, since
     * changes may have been missed.
     */
    suspend fun followLiveUpdates() {
        try {
            follower.run { event ->
                when (event) {
                    is LiveEvent.Connected -> {
                        connectionId = event.connectionId
                        fetch()
                        mutableState.update { it.copy(isLive = true) }
                    }

                    is LiveEvent.Update -> {
                        val update = event.update
                        if (update is LiveUpdate.QuestChanged && update.questName == questName) fetch()
                    }

                    LiveEvent.Disconnected -> {
                        connectionId = null
                        mutableState.update { it.copy(isLive = false) }
                    }
                }
            }
        } finally {
            connectionId = null
            mutableState.update { it.copy(isLive = false) }
        }
    }

    /** Moves on to the stop after this one, once it's solved. */
    suspend fun goToNextStop() {
        val next = state.value.puzzle?.next ?: return
        lastScan = null
        mutableState.update { State(next.puzzleName, changeCount = it.changeCount, isLive = it.isLive) }
        fetch()
    }

    /** Activates the puzzle with the code the player typed. */
    suspend fun activate(code: String) {
        act(PuzzleAction.ACTIVATING) { puzzleName, connection ->
            api.activatePuzzle(questName, puzzleName, code, connection)
        }
    }

    /** Activates the puzzle with a code read from its QR code, unless it was just sent. */
    suspend fun scanned(code: String) {
        if (state.value.pending != null) return
        lastScan?.let { (lastCode, tryAgainAt) ->
            if (lastCode == code && (tryAgainAt == null || tryAgainAt.hasNotPassedNow())) return
        }
        lastScan = code to null
        activate(code)
        if (state.value.actionError == PuzzleActionError.UNREACHABLE) {
            // The server never answered, so this says nothing about the code.
            lastScan = code to timeSource.markNow() + retryAfterNoAnswer
        }
    }

    /**
     * Guesses the solution.
     *
     * @return whether the guess was right.
     */
    suspend fun solve(guess: String): Boolean =
        act(PuzzleAction.SOLVING) { puzzleName, connection ->
            api.attemptSolution(questName, puzzleName, guess, connection)
        }

    /** Reveals the next hint, adding its penalty to the players' time. The screen asks first if it gives the solution away. */
    suspend fun revealHint() {
        act(PuzzleAction.REVEALING_HINT) { puzzleName, connection ->
            api.revealHint(questName, puzzleName, connection)
        }
    }

    /** Clears the message about the last action, as when the player starts another. */
    fun clearActionError() = mutableState.update { it.copy(actionError = null) }

    /**
     * Sends one of the player's actions. However it turns out, the puzzle is
     * fetched again, since a refusal often means it has changed (a teammate
     * solved it, or the quest was paused), and the action stays pending until
     * that's back.
     *
     * @return whether it succeeded.
     */
    private suspend fun act(
        action: PuzzleAction,
        send: suspend (puzzleName: String, connectionId: String?) -> Unit,
    ): Boolean {
        if (state.value.pending != null) return false
        mutableState.update { it.copy(pending = action, actionError = null) }
        val error =
            try {
                send(state.value.puzzleName, connectionId)
                null
            } catch (error: ApiError) {
                PuzzleActionError.of(error)
            }
        if (error != null) {
            mutableState.update { it.copy(actionError = error, actionErrorCount = it.actionErrorCount + 1) }
        }
        try {
            fetch()
        } finally {
            mutableState.update { it.copy(pending = null) }
        }
        return error == null
    }

    private suspend fun fetch() {
        val fetch = ++latestFetch
        val puzzleName = state.value.puzzleName
        val fetched =
            try {
                api.playerPuzzle(questName, puzzleName)
            } catch (_: ApiError) {
                if (fetch == latestFetch && puzzleName == state.value.puzzleName) {
                    mutableState.update { it.copy(loadFailed = true) }
                }
                return
            }
        if (fetch != latestFetch || puzzleName != state.value.puzzleName) return
        mutableState.update { state ->
            if (fetched == null) return@update state.copy(puzzle = null, notAvailable = true, loadFailed = false)
            val changes = state.puzzle?.let { PuzzleChange.between(it, fetched) }.orEmpty()
            state.copy(
                puzzle = fetched,
                notAvailable = false,
                loadFailed = false,
                changes = changes.ifEmpty { state.changes },
                changeCount = state.changeCount + if (changes.isEmpty()) 0 else 1,
            )
        }
    }
}

/** One of the player's actions on a puzzle. */
enum class PuzzleAction { ACTIVATING, SOLVING, REVEALING_HINT }

/** Why a player's action on a puzzle didn't succeed. */
enum class PuzzleActionError {
    WRONG_CODE,
    WRONG_ANSWER,

    /** The puzzle master has paused the quest. */
    PAUSED,

    /** Too many tries in a short time. */
    TOO_MANY_ATTEMPTS,
    NO_MORE_HINTS,

    /** The puzzle changed meanwhile, as when a teammate solved it; the screen now shows where it stands. */
    CHANGED,

    /** Someone else acted at the same moment; trying again should work. */
    TRY_AGAIN,
    UNREACHABLE,
    UNEXPECTED,
    ;

    companion object {
        fun of(error: ApiError): PuzzleActionError =
            when (error) {
                is ApiError.Refused -> {
                    when (error.problem.type) {
                        Problem.Types.WRONG_ACTIVATION_CODE -> WRONG_CODE
                        Problem.Types.WRONG_ANSWER -> WRONG_ANSWER
                        Problem.Types.QUEST_PAUSED -> PAUSED
                        Problem.Types.TOO_MANY_REQUESTS -> TOO_MANY_ATTEMPTS
                        Problem.Types.NO_MORE_HINTS -> NO_MORE_HINTS
                        Problem.Types.WRONG_STATE -> CHANGED
                        Problem.Types.TRY_AGAIN -> TRY_AGAIN
                        else -> if (error.problem.status == 404) CHANGED else UNEXPECTED
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
