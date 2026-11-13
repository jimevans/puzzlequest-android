package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A push notification from PuzzleQuest, as the app receives it (the API's
 * `push-notifications.json`): which kind it is and the quest it's about,
 * beside its title and body.
 */
data class PuzzleQuestNotification(
    /** The kind, such as `questStarted` or `message`. Version 1 may add kinds; an unknown one is still about its quest. */
    val category: String,
    val questName: String,
    /** The puzzle it's about, where there is one. */
    val puzzleName: String? = null,
) {
    companion object {
        /** Reads a notification's data, as Firebase delivers it; null for one that isn't PuzzleQuest's. */
        fun from(data: Map<String, String>): PuzzleQuestNotification? {
            val category = data["category"] ?: return null
            val questName = data["questName"] ?: return null
            return PuzzleQuestNotification(category, questName, data["puzzleName"])
        }
    }
}

/** Whether the player has let the app show notifications. */
enum class NotificationPermission {
    /** Not asked yet: Android asks when the app first asks. */
    NOT_ASKED,
    ALLOWED,

    /** Refused; only the phone's settings can change it now. */
    REFUSED,
}

/** What the registrar keeps between launches: the quests the player said "Not now" on. */
interface PushPreferences {
    var declined: Set<String>
}

/** Kept only in memory, as tests do. */
class InMemoryPushPreferences : PushPreferences {
    @Volatile override var declined: Set<String> = emptySet()
}

/**
 * Gets the player's notifications to this phone: asks for permission when the
 * player chooses to, on a quest (never right after signing in), and registers
 * the phone's token with their session, since tokens change. Signing out ends
 * the session, and its notifications with it. Tracks the quest on screen,
 * whose notifications aren't shown, since live updates have already shown
 * what changed, and the quest a tapped notification should open.
 *
 * Only once notifications are allowed is the phone's token registered:
 * otherwise the puzzle master's messages would go to a phone that can't show
 * them, instead of by text.
 */
class PushRegistrar(
    private val preferences: PushPreferences,
) {
    private val mutablePermission = MutableStateFlow<NotificationPermission?>(null)
    val permission: StateFlow<NotificationPermission?> = mutablePermission.asStateFlow()

    private val mutableToken = MutableStateFlow<String?>(null)

    /** The phone's token with Firebase, once it's given and notifications are allowed. */
    val token: StateFlow<String?> = mutableToken.asStateFlow()

    private val mutableQuestToOpen = MutableStateFlow<String?>(null)

    /** The quest a tapped notification is about, for the home screen to open. */
    val questToOpen: StateFlow<String?> = mutableQuestToOpen.asStateFlow()

    private val mutableDeclined = MutableStateFlow(preferences.declined)

    /** The quests the player said "Not now" on. */
    val declined: StateFlow<Set<String>> = mutableDeclined.asStateFlow()

    /** The quest on screen, if any; its notifications aren't shown. */
    @Volatile var questOnScreen: String? = null

    /** The token and server last registered, so each is sent once. */
    private var registered: Pair<String, String>? = null

    /** Whether to offer notifications on a quest's screen: until the player answers Android, or says "Not now" there. */
    fun offers(
        questName: String,
        permission: NotificationPermission?,
        declined: Set<String>,
    ): Boolean = permission == NotificationPermission.NOT_ASKED && questName !in declined

    /** What Android says now, as on each launch and on coming back to the app (it may have changed in settings). */
    fun permissionIs(permission: NotificationPermission) {
        mutablePermission.value = permission
    }

    /** The player said "Not now" on a quest: it isn't offered there again. */
    fun notNow(questName: String) {
        val declined = mutableDeclined.value + questName
        preferences.declined = declined
        mutableDeclined.value = declined
    }

    /** Firebase gave the phone's token. */
    fun received(token: String) {
        mutableToken.value = token
    }

    /**
     * Registers the phone's token with the signed-in session, if notifications
     * are allowed, there's a token, and it isn't registered with this server
     * already. Failing is fine: it's tried again on the next launch.
     */
    suspend fun register(api: PuzzleQuestApi) {
        if (permission.value != NotificationPermission.ALLOWED) return
        val token = token.value ?: return
        if (registered == token to api.server) return
        try {
            api.setPushTarget(token)
            registered = token to api.server
        } catch (_: ApiError) {
        }
    }

    /** A new session (signing in again, or another server) needs the phone registered again. */
    fun forgetRegistration() {
        registered = null
    }

    /** Whether to show a notification that arrives while the app is open: not one about the quest on screen. */
    fun shows(notification: PuzzleQuestNotification): Boolean = notification.questName != questOnScreen

    /** The player tapped a notification: its quest should open. */
    fun opened(questName: String) {
        mutableQuestToOpen.value = questName
    }

    /** The home screen has opened the quest. */
    fun questOpened() {
        mutableQuestToOpen.value = null
    }
}
