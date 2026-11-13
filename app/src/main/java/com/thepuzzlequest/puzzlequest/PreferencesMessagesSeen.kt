package com.thepuzzlequest.puzzlequest

import android.content.Context
import androidx.core.content.edit
import com.thepuzzlequest.puzzlequest.core.MessagesSeen

/**
 * Which of the puzzle master's messages this phone has shown, and the
 * answered challenge the player hid, in the app's preferences. An entry
 * keeps only its latest messages on the server, so only their IDs are worth
 * keeping.
 */
class PreferencesMessagesSeen(
    context: Context,
    /** The preferences file's name; tests use their own. */
    name: String = "com.thepuzzlequest.puzzlequest.messages",
) : MessagesSeen {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun seen(entryId: String): Set<String> = preferences.getStringSet("seen $entryId", null).orEmpty()

    override fun markSeen(
        ids: Collection<String>,
        entryId: String,
    ) {
        // Messages beyond an entry's latest 50 are gone from the server.
        val all = (seen(entryId) + ids).sorted().takeLast(100).toSet()
        preferences.edit { putStringSet("seen $entryId", all) }
    }

    override fun hiddenChallenge(entryId: String): String? = preferences.getString("hidden $entryId", null)

    override fun hideChallenge(
        id: String,
        entryId: String,
    ) {
        // Only the latest is kept: a challenge replaces the one before.
        preferences.edit { putString("hidden $entryId", id) }
    }
}
