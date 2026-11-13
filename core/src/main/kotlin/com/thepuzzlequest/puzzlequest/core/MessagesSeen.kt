package com.thepuzzlequest.puzzlequest.core

/**
 * Which of the puzzle master's messages this phone has shown, so each pops
 * up once: on arriving, or on opening the quest if it came while the app was
 * closed; and the answered challenge the player has hidden, if any.
 */
interface MessagesSeen {
    /** The IDs of the messages shown for an entry. */
    fun seen(entryId: String): Set<String>

    /** Records messages as shown. */
    fun markSeen(
        ids: Collection<String>,
        entryId: String,
    )

    /** The answered challenge the player hid from the quest screen, by its [Challenge.id]. */
    fun hiddenChallenge(entryId: String): String?

    /** Hides an answered challenge from the quest screen. */
    fun hideChallenge(
        id: String,
        entryId: String,
    )
}

/** Keeps what's been shown in memory, as tests and previews do. */
class InMemoryMessagesSeen : MessagesSeen {
    private val ids = mutableMapOf<String, Set<String>>()
    private val hidden = mutableMapOf<String, String>()

    @Synchronized
    override fun seen(entryId: String): Set<String> = ids[entryId].orEmpty()

    @Synchronized
    override fun markSeen(
        ids: Collection<String>,
        entryId: String,
    ) {
        this.ids[entryId] = seen(entryId) + ids
    }

    @Synchronized
    override fun hiddenChallenge(entryId: String): String? = hidden[entryId]

    @Synchronized
    override fun hideChallenge(
        id: String,
        entryId: String,
    ) {
        hidden[entryId] = id
    }
}
