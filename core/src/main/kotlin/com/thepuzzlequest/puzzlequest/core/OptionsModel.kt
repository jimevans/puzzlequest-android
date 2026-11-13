package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The servers the Options screen offers. */
enum class ServerChoice {
    /** https://thepuzzlequest.com. */
    PRODUCTION,

    /** http://localhost:3000, in debug builds. */
    THIS_COMPUTER,

    /** An address the person types. */
    CUSTOM,
}

/**
 * The Options screen's state: which server is chosen, a custom address being
 * typed and checked, and a switch waiting for the person to confirm it.
 *
 * @param offersThisComputer whether This computer is offered (debug builds).
 */
class OptionsModel(
    private val connection: ServerConnection,
    val offersThisComputer: Boolean,
    private val serverCheck: ServerCheck = ServerCheck(),
) {
    data class State(
        /**
         * The choice shown as selected. Picking Custom selects it before any
         * address is used; otherwise it's the server in use (not one waiting
         * for confirmation).
         */
        val choice: ServerChoice,
        /** The custom address as typed. */
        val customAddress: String,
        /** Whether a custom server is being checked. */
        val isChecking: Boolean = false,
        /** Why the custom address was refused, until it's tried again. */
        val failure: CustomServerFailure? = null,
        /** A server to switch to once the person confirms that it signs them out. */
        val pendingSwitch: ServerOrigin? = null,
    ) {
        val canUseCustomAddress: Boolean get() = customAddress.isNotBlank() && !isChecking
    }

    /** Which servers this build may use. */
    val policy: ServerAddressPolicy get() = connection.policy

    private val mutableState: MutableStateFlow<State>
    val state: StateFlow<State>

    init {
        val choice = currentChoice
        mutableState =
            MutableStateFlow(
                State(choice, customAddress = if (choice == ServerChoice.CUSTOM) connection.server.url else ""),
            )
        state = mutableState.asStateFlow()
    }

    /** The choice that matches the server in use. */
    private val currentChoice: ServerChoice
        get() =
            when {
                connection.server == ServerOrigin.PRODUCTION -> ServerChoice.PRODUCTION
                connection.server == ServerOrigin.THIS_COMPUTER && offersThisComputer -> ServerChoice.THIS_COMPUTER
                else -> ServerChoice.CUSTOM
            }

    fun editCustomAddress(text: String) = mutableState.update { it.copy(customAddress = text) }

    /**
     * Picks a server: Production and This computer are used at once (after
     * confirming, if it signs someone out); Custom waits for an address.
     */
    fun choose(choice: ServerChoice) {
        mutableState.update { it.copy(failure = null) }
        when (choice) {
            ServerChoice.PRODUCTION -> requestSwitch(ServerOrigin.PRODUCTION)
            ServerChoice.THIS_COMPUTER -> if (offersThisComputer) requestSwitch(ServerOrigin.THIS_COMPUTER)
            ServerChoice.CUSTOM -> mutableState.update { it.copy(choice = ServerChoice.CUSTOM) }
        }
    }

    /**
     * Uses the typed address: checks this build may use it, then that it's a
     * PuzzleQuest server, then switches (after confirming, if it signs
     * someone out). A refusal is kept to explain, and the address can be
     * tried again.
     */
    suspend fun useCustomAddress() {
        if (!state.value.canUseCustomAddress) return
        mutableState.update { it.copy(failure = null) }
        val server =
            try {
                connection.policy.check(state.value.customAddress)
            } catch (refused: ServerAddressRefused) {
                mutableState.update { it.copy(failure = CustomServerFailure.Refused(refused.reason)) }
                return
            }
        mutableState.update { it.copy(customAddress = server.url) }
        if (server == connection.server) return
        // PuzzleQuest's own servers need no checking.
        if (server != ServerOrigin.PRODUCTION && server != ServerOrigin.THIS_COMPUTER) {
            mutableState.update { it.copy(isChecking = true) }
            try {
                serverCheck.check(server)
            } catch (failed: ServerCheckFailed) {
                mutableState.update { it.copy(failure = CustomServerFailure.CheckFailed(failed.reason)) }
                return
            } finally {
                mutableState.update { it.copy(isChecking = false) }
            }
        }
        requestSwitch(server)
    }

    /** Switches now if no one would be signed out; otherwise asks first. Choosing the server in use does nothing. */
    private fun requestSwitch(server: ServerOrigin) {
        when {
            server == connection.server -> mutableState.update { it.copy(choice = currentChoice) }
            connection.switchingSignsOut -> mutableState.update { it.copy(pendingSwitch = server) }
            else -> switchTo(server)
        }
    }

    /** The person agreed to be signed out. */
    fun confirmSwitch(server: ServerOrigin) {
        mutableState.update { it.copy(pendingSwitch = null) }
        switchTo(server)
    }

    /** The person chose to stay: nothing changes. */
    fun cancelSwitch() = mutableState.update { it.copy(pendingSwitch = null) }

    private fun switchTo(server: ServerOrigin) {
        connection.switchTo(server)
        mutableState.update { it.copy(choice = currentChoice) }
    }
}

/** Why a custom server wasn't used. */
sealed interface CustomServerFailure {
    /** This build may not use that address. */
    data class Refused(
        val reason: ServerAddressRefusal,
    ) : CustomServerFailure

    /** It couldn't be confirmed as a PuzzleQuest server. */
    data class CheckFailed(
        val reason: ServerCheckFailure,
    ) : CustomServerFailure
}
