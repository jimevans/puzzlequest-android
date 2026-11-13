package com.thepuzzlequest.puzzlequest

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.thepuzzlequest.puzzlequest.core.ServerAddressPolicy
import com.thepuzzlequest.puzzlequest.core.ServerAddressRefused
import com.thepuzzlequest.puzzlequest.core.ServerConnection
import com.thepuzzlequest.puzzlequest.core.ServerOrigin
import com.thepuzzlequest.puzzlequest.core.ServerSetting
import com.thepuzzlequest.puzzlequest.core.SettingStore
import kotlinx.coroutines.CoroutineScope

/**
 * How this build chooses its server. Every build starts with Production
 * (https://thepuzzlequest.com), and the person can choose another in
 * Options: a release build only PuzzleQuest's own servers, a debug build
 * any, including one on the computer the phone is connected to.
 */
object AppServer {
    /** Debug builds only: the server to use for this run, as the UI tests do, without changing Options. */
    const val EXTRA_SERVER_URL = "PQ_SERVER_URL"

    /** Debug builds only: forget every server's session before starting. */
    const val EXTRA_SIGNED_OUT = "PQ_SIGNED_OUT"

    /** Debug builds only: start with Options as a new install has it. */
    const val EXTRA_RESET_SERVER = "PQ_RESET_SERVER"

    val policy: ServerAddressPolicy
        get() = if (BuildConfig.DEBUG) ServerAddressPolicy.ANY_ADDRESS else ServerAddressPolicy.PUZZLE_QUEST_DOMAIN

    /** Whether Options offers This computer (debug builds). */
    val offersThisComputer: Boolean get() = BuildConfig.DEBUG

    /**
     * The server and its session, as the app launches.
     *
     * @param scope where work that outlives a screen runs (the app's ViewModel's).
     */
    fun connect(
        context: Context,
        intent: Intent?,
        scope: CoroutineScope,
    ): ServerConnection {
        val setting = ServerSetting(PreferencesSettingStore(context), policy)
        val vault = KeystoreTokenVault(context)
        var fixed: ServerOrigin? = null
        if (BuildConfig.DEBUG && intent != null) {
            if (intent.getBooleanExtra(EXTRA_SIGNED_OUT, false)) vault.forgetAll()
            if (intent.getBooleanExtra(EXTRA_RESET_SERVER, false)) setting.reset()
            // It wins over the setting, without changing it.
            fixed =
                intent.getStringExtra(EXTRA_SERVER_URL)?.let { address ->
                    try {
                        policy.check(address)
                    } catch (_: ServerAddressRefused) {
                        error("$EXTRA_SERVER_URL isn't a server's address: $address")
                    }
                }
        }
        return ServerConnection(setting, vault, policy, fixed, scope)
    }
}

/** The chosen server, in the app's preferences (it isn't secret). */
private class PreferencesSettingStore(
    context: Context,
) : SettingStore {
    private val preferences =
        context.getSharedPreferences(
            "com.thepuzzlequest.puzzlequest.settings",
            Context.MODE_PRIVATE,
        )

    override fun read(): String? = preferences.getString(KEY, null)

    override fun write(value: String?) =
        preferences.edit(commit = true) {
            if (value == null) remove(KEY) else putString(KEY, value)
        }

    private companion object {
        const val KEY = "server"
    }
}
