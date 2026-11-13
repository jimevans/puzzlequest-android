package com.thepuzzlequest.puzzlequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thepuzzlequest.puzzlequest.core.OptionsModel
import com.thepuzzlequest.puzzlequest.core.PuzzleQuestApi
import com.thepuzzlequest.puzzlequest.core.ServerConnection
import com.thepuzzlequest.puzzlequest.core.ServerOrigin
import com.thepuzzlequest.puzzlequest.core.SessionStore
import com.thepuzzlequest.puzzlequest.core.SignInModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The app's server and its session, for as long as the app is open. A
 * ViewModel outlives the screen's being rebuilt (on rotating, say), and work
 * started in its `viewModelScope` isn't cut off by that.
 *
 * @param connect makes the connection to the server, given where its work runs.
 */
class AppViewModel(
    connect: (CoroutineScope) -> ServerConnection,
) : ViewModel() {
    val connection = connect(viewModelScope)

    /** A server in use: its session, and signing in to it. */
    class Server(
        connected: ServerConnection.Connected,
        /** How many times the app has switched servers since it opened. */
        val switches: Int,
    ) {
        val origin: ServerOrigin = connected.server
        val session: SessionStore = connected.session
        val signIn = SignInModel(session)
    }

    private val mutableServer = MutableStateFlow(Server(connection.current.value, switches = 0))

    /** The server in use; a new one, with a new session, each time it's switched. */
    val server: StateFlow<Server> = mutableServer.asStateFlow()

    private val mutableOptions = MutableStateFlow<OptionsModel?>(null)

    /** Options, while it's open; a new model each time it opens. */
    val options: StateFlow<OptionsModel?> = mutableOptions.asStateFlow()

    // The current server's; the screens are rebuilt for each server (see RootScreen).
    val session: SessionStore get() = server.value.session
    val api: PuzzleQuestApi get() = session.api
    val signIn: SignInModel get() = server.value.signIn

    init {
        viewModelScope.launch {
            // Each server's session is restored as it's switched to (the
            // first one as the app opens). collectLatest gives up restoring
            // one that's been switched away from.
            connection.current.collectLatest { connected ->
                val current = mutableServer.value
                if (connected.session !== current.session) {
                    mutableServer.value = Server(connected, current.switches + 1)
                    // Switching closes Options, as the screens are rebuilt.
                    mutableOptions.value = null
                }
                connected.session.restore()
            }
        }
    }

    fun restore() {
        val session = session
        viewModelScope.launch { session.restore() }
    }

    fun signIn(
        userName: String,
        password: String,
    ) {
        val signIn = signIn
        viewModelScope.launch {
            // A new session needs the phone registered for its notifications.
            if (signIn.signIn(userName, password)) Push.registrar.forgetRegistration()
        }
    }

    fun refreshUser() {
        val session = session
        viewModelScope.launch { session.refreshUser() }
    }

    fun signOut() {
        val session = session
        viewModelScope.launch {
            session.signOut()
            // Signing out ended the session's notifications.
            Push.registrar.forgetRegistration()
        }
    }

    fun openOptions() {
        mutableOptions.value = OptionsModel(connection, AppServer.offersThisComputer)
    }

    fun closeOptions() {
        mutableOptions.value = null
    }

    /**
     * Checks the address typed in Options, and switches to it. It runs here
     * rather than in the screen, so it carries on if the screen is rebuilt
     * meanwhile (on rotating, say).
     */
    fun useCustomAddress() {
        val options = options.value ?: return
        viewModelScope.launch { options.useCustomAddress() }
    }
}
