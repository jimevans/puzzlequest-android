package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A setting kept between launches, such as in the app's preferences. */
interface SettingStore {
    fun read(): String?

    /** Keeps the value, or forgets it for null. */
    fun write(value: String?)
}

/** A setting kept only in memory, for tests and previews. */
class InMemorySettingStore(
    @Volatile var value: String? = null,
) : SettingStore {
    override fun read() = value

    override fun write(value: String?) {
        this.value = value
    }
}

/**
 * The server the person has chosen. It isn't secret, so it's kept as a plain
 * setting; the session's token is kept apart (see [TokenVault]).
 *
 * @param policy which servers this build may use; a kept server it doesn't
 *   allow is ignored.
 */
class ServerSetting(
    private val store: SettingStore,
    private val policy: ServerAddressPolicy,
) {
    /** The chosen server, or Production if none has been chosen. */
    fun read(): ServerOrigin {
        val kept = store.read() ?: return ServerOrigin.PRODUCTION
        return try {
            policy.check(kept)
        } catch (_: ServerAddressRefused) {
            ServerOrigin.PRODUCTION
        }
    }

    fun save(server: ServerOrigin) = store.write(server.url.takeIf { server != ServerOrigin.PRODUCTION })

    /** Goes back to Production, as the UI tests do at launch. */
    fun reset() = store.write(null)
}

/**
 * The server the app talks to, and the session with it. Switching servers
 * signs out: the old server's token is forgotten, and a new session (with its
 * own API client) is made for the new one, so nothing of one server's is ever
 * sent to another.
 *
 * @param setting the chosen server.
 * @param vault each server's token.
 * @param policy which servers this build may use.
 * @param fixed a server to use for this run instead of the chosen one,
 *   without changing the setting.
 * @param scope where the old server is told its session has ended, after
 *   switching.
 */
class ServerConnection(
    private val setting: ServerSetting,
    private val vault: TokenVault,
    val policy: ServerAddressPolicy,
    fixed: ServerOrigin? = null,
    private val scope: CoroutineScope,
    private val makeApi: (ServerOrigin, TokenStore) -> PuzzleQuestApi = { server, tokens ->
        PuzzleQuestApi(server.url, tokens)
    },
) {
    /** A server, and the session with it. */
    class Connected(
        val server: ServerOrigin,
        val session: SessionStore,
    )

    private val mutableCurrent: MutableStateFlow<Connected>

    /** The server in use and its session; both change together, on switching. */
    val current: StateFlow<Connected>

    /**
     * Whether the server is fixed for this run (a debug build's
     * `PQ_SERVER_URL`, as the UI tests set), so it can't be chosen.
     */
    val isFixed = fixed != null

    /** Telling the old server its session has ended, after switching; tests wait for it. */
    var farewell: Job? = null
        private set

    init {
        val server = fixed ?: setting.read()
        mutableCurrent = MutableStateFlow(connect(server))
        current = mutableCurrent.asStateFlow()
    }

    val server: ServerOrigin get() = current.value.server

    val session: SessionStore get() = current.value.session

    /**
     * Whether switching away would sign someone out, so they're asked first:
     * someone is signed in, or a session is kept to try again.
     */
    val switchingSignsOut: Boolean
        get() = session.state.value is SessionStore.State.SignedIn || vault.tokens(server).read() != null

    /**
     * Uses another server from now on, and remembers the choice. Anyone signed
     * in is signed out: the token is forgotten at once, and the old server is
     * told the session has ended without waiting for it (it may be the one
     * that can't be reached). The new server's session starts out as one does
     * at launch, needing `restore()`. Switching to the same server, or when
     * the server is fixed, does nothing.
     */
    fun switchTo(newServer: ServerOrigin) {
        if (newServer == server || isFixed) return
        val oldServer = server
        val oldTokens = vault.tokens(oldServer)
        oldTokens.read()?.let { token ->
            oldTokens.delete()
            val oldApi = makeApi(oldServer, InMemoryTokenStore(token))
            farewell =
                scope.launch {
                    try {
                        oldApi.signOut()
                    } catch (_: ApiError) {
                    }
                }
        }
        setting.save(newServer)
        mutableCurrent.value = connect(newServer)
    }

    private fun connect(server: ServerOrigin): Connected {
        val tokens = vault.tokens(server)
        return Connected(server, SessionStore(makeApi(server, tokens), tokens))
    }
}
