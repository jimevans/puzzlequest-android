package com.thepuzzlequest.puzzlequest

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.thepuzzlequest.puzzlequest.core.NotificationPermission
import com.thepuzzlequest.puzzlequest.core.PushPreferences
import com.thepuzzlequest.puzzlequest.core.PushRegistrar
import com.thepuzzlequest.puzzlequest.core.PuzzleQuestNotification

/**
 * Push notifications, for the whole app: one registrar (see PushRegistrar),
 * shared by the screens and the messaging service, which runs in the same
 * process.
 */
object Push {
    /** The channel PuzzleQuest's notifications are in, which the player can adjust in the phone's settings. */
    const val CHANNEL = "quests"

    /** The intent extra naming a tapped notification's quest, as Firebase puts it on the launch intent. */
    const val EXTRA_QUEST_NAME = "questName"

    /** Debug builds only: a token to use instead of Firebase's, as the UI tests give one. */
    const val EXTRA_FAKE_TOKEN = "PQ_FAKE_PUSH_TOKEN"

    private const val PREFERENCES = "com.thepuzzlequest.puzzlequest.push"

    lateinit var registrar: PushRegistrar
        private set

    /** Debug builds only: the token a UI test gave, if any. */
    @Volatile var fakeToken: String? = null

    /** Sets up the registrar and the notification channel, as the app starts. */
    fun start(application: Application) {
        registrar =
            PushRegistrar(
                PreferencesPushPreferences(application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)),
            )
        val channel =
            NotificationChannel(
                CHANNEL,
                application.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = application.getString(R.string.notification_channel_description) }
        application.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Checks the permission, as on each launch and on coming back to the app,
     * and once allowed, asks Firebase for the phone's token.
     */
    fun refresh(context: Context) {
        val permission = permission(context)
        registrar.permissionIs(permission)
        if (permission == NotificationPermission.ALLOWED) requestToken()
    }

    /** The player answered Android's question. */
    fun answered(
        context: Context,
        allowed: Boolean,
    ) {
        markAsked(context)
        refresh(context)
        if (!allowed) registrar.permissionIs(NotificationPermission.REFUSED)
    }

    /**
     * What Android says: notifications allowed; on Android 13 and later, not
     * yet asked (the app keeps whether it has asked); or refused.
     */
    private fun permission(context: Context): NotificationPermission {
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) return NotificationPermission.ALLOWED
        val asks = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val granted =
            asks &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        return if (asks && !granted &&
            !asked(context)
        ) {
            NotificationPermission.NOT_ASKED
        } else {
            NotificationPermission.REFUSED
        }
    }

    private fun asked(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean("asked", false)

    private fun markAsked(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putBoolean("asked", true) }

    private fun requestToken() {
        fakeToken?.let {
            registrar.received(it)
            return
        }
        // Without a token there's nothing to register; the next launch
        // tries again, and the puzzle master's messages are texted meanwhile.
        FirebaseMessaging.getInstance().token.addOnSuccessListener { registrar.received(it) }
    }

    /**
     * Shows a notification that arrived while the app is open (Firebase shows
     * those that arrive while it's closed by itself), unless it's about the
     * quest on screen, which live updates have already shown. Tapping it
     * opens its quest.
     */
    fun show(
        context: Context,
        message: RemoteMessage,
    ) {
        val notification = PuzzleQuestNotification.from(message.data) ?: return
        if (!registrar.shows(notification)) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val open =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_QUEST_NAME, notification.questName)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val shown =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.brand_purple))
                .setContentTitle(message.notification?.title)
                .setContentText(message.notification?.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message.notification?.body))
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        notification.questName.hashCode(),
                        open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build()
        try {
            // One notification for each quest, as the server tags them: a
            // newer one replaces the last.
            NotificationManagerCompat.from(context).notify(notification.questName, 0, shown)
        } catch (_: SecurityException) {
            // The permission was taken away meanwhile.
        }
    }
}

/** The quests the player said "Not now" on, in the app's preferences. */
private class PreferencesPushPreferences(
    private val preferences: android.content.SharedPreferences,
) : PushPreferences {
    override var declined: Set<String>
        get() = preferences.getStringSet("declined", null).orEmpty()
        set(value) = preferences.edit { putStringSet("declined", value) }
}

/** The app itself: sets up push notifications as it starts. */
class PuzzleQuestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Push.start(this)
    }
}

/**
 * Firebase's service: a new token for the phone, and notifications that
 * arrive while the app is open.
 */
class PuzzleQuestMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (Push.registrar.permission.value == NotificationPermission.ALLOWED && Push.fakeToken == null) {
            Push.registrar.received(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Push.show(this, message)
    }
}
