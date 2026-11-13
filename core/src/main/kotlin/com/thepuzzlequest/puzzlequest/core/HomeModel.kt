package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A player's home screen: the quests they play, kept up to date by live
 * updates, and a reminder to verify their email address.
 */
class HomeModel(
    user: SignedInUser,
    private val api: PuzzleQuestApi,
    connector: LiveSocketConnector = OkHttpLiveSocketConnector(),
    makeFollower: (LiveChannel, suspend () -> String) -> LiveUpdateFollower = { channel, ticket ->
        LiveUpdateFollower(api.liveUpdatesUrl, channel, connector, ticket)
    },
) {
    data class State(
        /** The quests, once fetched; null until the first fetch succeeds. */
        val quests: List<QuestSummary>? = null,
        /**
         * Whether the latest fetch failed. A screen that has quests keeps
         * showing them; one that hasn't explains, with a way to try again.
         */
        val loadFailed: Boolean = false,
        /**
         * How many times a live update has changed the list, so the screen can
         * tell TalkBack users. A clock counting up isn't a change.
         */
        val liveChanges: Int = 0,
        /**
         * Whether live updates are connected and the list has been fetched
         * since connecting, so from here on a change arrives as an update.
         */
        val isLive: Boolean = false,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    val emailReminder = EmailReminderModel(user.userName, api)

    private val userName = user.userName
    private val follower = makeFollower(LiveChannel.UserQuests(userName)) { api.userLiveTicket(userName) }

    /**
     * Fetches can overlap (pull to refresh while a live update arrives); only
     * the latest one's answer is kept.
     */
    private var latestFetch = 0

    /** Fetches the quests, as when the screen appears or is pulled down. */
    suspend fun load() = fetch(live = false)

    /**
     * Keeps the list up to date until cancelled (the screen goes away, or the
     * app to the background): fetches it again whenever the server says it has
     * changed, and each time the connection is made, since changes may have
     * been missed.
     */
    suspend fun followLiveUpdates() {
        try {
            follower.run { event ->
                when (event) {
                    is LiveEvent.Connected -> {
                        fetch(live = true)
                        // Only now: the fetch has caught up with anything missed.
                        mutableState.update { it.copy(isLive = true) }
                    }

                    is LiveEvent.Update -> {
                        if (event.update == LiveUpdate.QuestsChanged) fetch(live = true)
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

    private suspend fun fetch(live: Boolean) {
        val fetch = ++latestFetch
        try {
            val fetched = api.userQuests(userName)
            if (fetch != latestFetch) return
            mutableState.update { state ->
                val changed = live && state.quests != null && listing(state.quests) != listing(fetched)
                state.copy(
                    quests = fetched,
                    loadFailed = false,
                    liveChanges = state.liveChanges + if (changed) 1 else 0,
                )
            }
        } catch (_: ApiError) {
            if (fetch != latestFetch) return
            mutableState.update { it.copy(loadFailed = true) }
        }
    }

    /**
     * What the list says about each quest, leaving out its clock, whose
     * figures change with every fetch while it runs.
     */
    private fun listing(quests: List<QuestSummary>) =
        quests.map { it.copy(progress = QuestClock(false, null, 0, 0, 0)) }
}

/** The reminder to verify an email address, with a way to have the email sent again. */
class EmailReminderModel(
    private val userName: String,
    private val api: PuzzleQuestApi,
) {
    sealed interface State {
        data object Ready : State

        data object Sending : State

        /** Sent; it isn't offered again. */
        data object Sent : State

        /** It couldn't be sent; it can be tried again. */
        data class Failed(
            val failure: EmailReminderFailure,
        ) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Ready)
    val state: StateFlow<State> = mutableState.asStateFlow()

    val canSend: Boolean get() = state.value != State.Sending && state.value != State.Sent

    suspend fun send() {
        if (!canSend) return
        mutableState.value = State.Sending
        mutableState.value =
            try {
                api.sendVerificationEmail(userName)
                State.Sent
            } catch (error: ApiError) {
                State.Failed(EmailReminderFailure.of(error))
            }
    }
}

/** Why the verification email couldn't be sent again. */
enum class EmailReminderFailure {
    /** It has been sent several times lately; wait before asking again. */
    TOO_MANY_REQUESTS,

    /** The server couldn't be reached. */
    UNREACHABLE,

    /** Anything else, such as a fault on the server. */
    UNEXPECTED,
    ;

    companion object {
        fun of(error: ApiError): EmailReminderFailure =
            when {
                error is ApiError.Refused && error.problem.type == Problem.Types.TOO_MANY_REQUESTS -> TOO_MANY_REQUESTS
                error == ApiError.Unreachable -> UNREACHABLE
                else -> UNEXPECTED
            }
    }
}
