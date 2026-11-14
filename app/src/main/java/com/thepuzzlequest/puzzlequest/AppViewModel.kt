package com.thepuzzlequest.puzzlequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thepuzzlequest.puzzlequest.core.DeleteAccountModel
import com.thepuzzlequest.puzzlequest.core.OptionsModel
import com.thepuzzlequest.puzzlequest.core.PuzzleQuestApi
import com.thepuzzlequest.puzzlequest.core.ServerConnection
import com.thepuzzlequest.puzzlequest.core.ServerOrigin
import com.thepuzzlequest.puzzlequest.core.SessionStore
import com.thepuzzlequest.puzzlequest.core.SignInModel
import com.thepuzzlequest.puzzlequest.core.SignUpModel
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

    private val mutableSignUp = MutableStateFlow<SignUpModel?>(null)

    /** Signing up, while its screen is open; a new model each time it opens. */
    val signUp: StateFlow<SignUpModel?> = mutableSignUp.asStateFlow()

    private val mutableDeleteAccount = MutableStateFlow<DeleteAccountModel?>(null)

    /** Deleting the account, while it's being confirmed; a new model each time. */
    val deleteAccount: StateFlow<DeleteAccountModel?> = mutableDeleteAccount.asStateFlow()

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
                    // Switching closes Options, as the screens are rebuilt,
                    // and anything open for the old session.
                    mutableOptions.value = null
                    mutableSignUp.value = null
                    mutableDeleteAccount.value = null
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

    fun openSignUp() {
        mutableSignUp.value = SignUpModel(session)
    }

    fun closeSignUp() {
        mutableSignUp.value = null
    }

    /** Signs up with what's typed, then signs in; it closes once signed in. */
    fun submitSignUp() {
        val signUp = signUp.value ?: return
        viewModelScope.launch {
            if (signUp.signUp()) {
                // A new session needs the phone registered for its notifications.
                Push.registrar.forgetRegistration()
                if (mutableSignUp.value === signUp) mutableSignUp.value = null
            }
        }
    }

    fun openDeleteAccount() {
        mutableDeleteAccount.value = DeleteAccountModel(session)
    }

    fun closeDeleteAccount() {
        mutableDeleteAccount.value = null
    }

    /** Deletes the account; once deleted, the app is signed out and this closes. */
    fun confirmDeleteAccount(password: String) {
        val delete = deleteAccount.value ?: return
        viewModelScope.launch {
            if (delete.delete(password)) {
                // The session, and its notifications, went with the account.
                Push.registrar.forgetRegistration()
                if (mutableDeleteAccount.value === delete) mutableDeleteAccount.value = null
            }
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
