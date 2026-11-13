// Following live updates over the API's websocket, /api/v1/updates, as the
// web app's packages/api-client/src/live.ts and the iOS app do: a new ticket
// for each connection (tickets can be used once), a subscription naming
// what's followed, and reconnecting with a growing wait whenever the
// connection drops. Updates sent while disconnected are gone, so whoever
// follows fetches what they show again each time it connects, as well as on
// each update.
package com.thepuzzlequest.puzzlequest.core

import com.thepuzzlequest.puzzlequest.api.models.LiveUpdateChallengeAnswered
import com.thepuzzlequest.puzzlequest.api.models.LiveUpdateConnection
import com.thepuzzlequest.puzzlequest.api.models.LiveUpdateHintRevealed
import com.thepuzzlequest.puzzlequest.api.models.LiveUpdateMessageSent
import com.thepuzzlequest.puzzlequest.api.models.LiveUpdatePuzzleActivated
import com.thepuzzlequest.puzzlequest.api.models.LiveUpdatePuzzleSolved
import com.thepuzzlequest.puzzlequest.api.models.LiveUpdatePuzzleStarted
import com.thepuzzlequest.puzzlequest.api.models.LiveUpdateQuestSubscription
import com.thepuzzlequest.puzzlequest.api.models.LiveUpdateQuestUpdated
import com.thepuzzlequest.puzzlequest.api.models.LiveUpdateUserSubscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What a connection follows. */
sealed interface LiveChannel {
    /**
     * The list of quests a user plays (a player's home screen), with tickets
     * from `POST /users/{userName}/live-tickets`.
     */
    data class UserQuests(
        val userName: String,
    ) : LiveChannel

    /** Every update of a quest, with tickets from `POST /quests/{questName}/live-tickets`. */
    data class Quest(
        val questName: String,
    ) : LiveChannel
}

/** A message from the server about what's followed. Version 1 may add others, which are ignored. */
sealed interface LiveUpdate {
    /** The user's list of quests should be fetched again, as when one of them starts. */
    data object QuestsChanged : LiveUpdate

    /**
     * An entry's progress in a quest changed (a stop was activated, started or
     * solved, a hint revealed, a message sent, or something the follower may
     * not be told about while racing); [message] is which, as the API names
     * it.
     */
    data class QuestChanged(
        val message: String,
        val questName: String,
        val entryId: String,
    ) : LiveUpdate
}

/** What happens to a connection, as a follower reports it. */
sealed interface LiveEvent {
    /**
     * The server accepted the subscription. Fetch what's followed again, since
     * updates may have been missed; send [connectionId] with your own actions
     * so that their updates aren't sent back.
     */
    data class Connected(
        val connectionId: String,
    ) : LiveEvent

    data class Update(
        val update: LiveUpdate,
    ) : LiveEvent

    /** The connection dropped after connecting; the follower tries again. */
    data object Disconnected : LiveEvent
}

/** A websocket connection: what the follower needs of one, so tests can stand in for the network. */
interface LiveSocket {
    /** Sends a message; throws if the connection has closed. */
    suspend fun send(text: String)

    /** The next message, as text. Throws once the connection has closed. */
    suspend fun receive(): String

    /** Closes the connection; a [receive] waiting for a message throws. */
    fun close()
}

/** Opens websocket connections. */
fun interface LiveSocketConnector {
    fun connect(url: String): LiveSocket
}

/**
 * Follows a channel for as long as [run] runs: connects, subscribes with a new
 * ticket, reports each event, and after the connection drops (or can't be
 * made) tries again after a wait that doubles each time, up to
 * [LONGEST_WAIT]. It stops when the coroutine running it is cancelled, as
 * Compose does to a screen's effects when the screen goes away.
 *
 * @param url the websocket's address, [PuzzleQuestApi.liveUpdatesUrl].
 * @param ticket gets a new ticket for the channel, for each connection.
 * @param wait how long to wait before trying again; tests make it instant.
 */
class LiveUpdateFollower(
    val url: String,
    val channel: LiveChannel,
    private val connector: LiveSocketConnector = OkHttpLiveSocketConnector(),
    private val ticket: suspend () -> String,
    private val wait: suspend (Duration) -> Unit = { delay(it) },
) {
    /**
     * Follows the channel until cancelled, reporting each event. Events are
     * reported one at a time, each after the last has been handled.
     */
    suspend fun run(onEvent: suspend (LiveEvent) -> Unit) {
        var attempts = 0
        while (currentCoroutineContext().isActive) {
            if (connectOnce(onEvent)) {
                // It worked, so a drop is likely brief: try again soon.
                attempts = 0
                onEvent(LiveEvent.Disconnected)
            }
            wait(minOf((1L shl minOf(attempts, 10)).seconds, LONGEST_WAIT))
            attempts++
        }
    }

    /**
     * Connects once and reports events until the connection drops.
     *
     * @return whether the server accepted the subscription.
     */
    private suspend fun connectOnce(onEvent: suspend (LiveEvent) -> Unit): Boolean {
        val subscription =
            try {
                subscription(channel, ticket())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return false
            }
        val socket = connector.connect(url)
        var connected = false
        try {
            socket.send(subscription)
            while (true) {
                when (val message = message(socket.receive())) {
                    is Message.Connection -> {
                        connected = true
                        onEvent(LiveEvent.Connected(message.connectionId))
                    }

                    is Message.Update -> {
                        onEvent(LiveEvent.Update(message.update))
                    }

                    null -> {}
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return connected
        } finally {
            socket.close()
        }
    }

    /** A message from the server, as far as following goes. */
    internal sealed interface Message {
        data class Connection(
            val connectionId: String,
        ) : Message

        data class Update(
            val update: LiveUpdate,
        ) : Message
    }

    companion object {
        /** The longest wait between attempts to connect. */
        val LONGEST_WAIT = 15.seconds

        private val json = ApiJson.json

        /** The subscription message for a channel, as JSON text. */
        internal fun subscription(
            channel: LiveChannel,
            ticket: String,
        ): String =
            when (channel) {
                is LiveChannel.UserQuests -> {
                    json.encodeToString(
                        LiveUpdateUserSubscription(
                            LiveUpdateUserSubscription.Message.subscribe,
                            channel.userName,
                            ticket,
                        ),
                    )
                }

                is LiveChannel.Quest -> {
                    json.encodeToString(
                        LiveUpdateQuestSubscription(
                            LiveUpdateQuestSubscription.Message.subscribe,
                            channel.questName,
                            ticket,
                        ),
                    )
                }
            }

        /**
         * Reads a message from the server, or null for one this version of the
         * app doesn't know (version 1 may add messages) or can't read. The
         * generated `LiveUpdate` can't tell the API's messages apart, so its
         * `message` names the generated class to read it as.
         */
        internal fun message(text: String): Message? =
            try {
                val fields = json.decodeFromString<JsonObject>(text)

                fun changed(
                    questName: String,
                    entryId: String,
                ) = Message.Update(
                    LiveUpdate.QuestChanged(fields["message"]!!.jsonPrimitive.content, questName, entryId),
                )
                when (fields["message"]?.jsonPrimitive?.content) {
                    "connection" -> {
                        Message.Connection(json.decodeFromString<LiveUpdateConnection>(text).connectionId)
                    }

                    "questsChanged" -> {
                        Message.Update(LiveUpdate.QuestsChanged)
                    }

                    "puzzleActivated" -> {
                        json.decodeFromString<LiveUpdatePuzzleActivated>(text).let { changed(it.questName, it.entryId) }
                    }

                    "puzzleSolved" -> {
                        json.decodeFromString<LiveUpdatePuzzleSolved>(text).let { changed(it.questName, it.entryId) }
                    }

                    "puzzleStarted" -> {
                        json.decodeFromString<LiveUpdatePuzzleStarted>(text).let { changed(it.questName, it.entryId) }
                    }

                    "hintRevealed" -> {
                        json.decodeFromString<LiveUpdateHintRevealed>(text).let { changed(it.questName, it.entryId) }
                    }

                    "questUpdated" -> {
                        json.decodeFromString<LiveUpdateQuestUpdated>(text).let { changed(it.questName, it.entryId) }
                    }

                    "messageSent" -> {
                        json.decodeFromString<LiveUpdateMessageSent>(text).let { changed(it.questName, it.entryId) }
                    }

                    "challengeAnswered" -> {
                        json
                            .decodeFromString<LiveUpdateChallengeAnswered>(
                                text,
                            ).let { changed(it.questName, it.entryId) }
                    }

                    else -> {
                        null
                    }
                }
            } catch (_: IllegalArgumentException) {
                // Not JSON, or not the message it says it is.
                null
            }
    }
}

/**
 * Opens websocket connections with OkHttp, which checks a quiet connection is
 * still alive: a phone can lose its connection without being told (a dead
 * zone, a locked phone), so a ping that goes unanswered closes it as failed.
 */
class OkHttpLiveSocketConnector(
    private val http: OkHttpClient = defaultClient,
) : LiveSocketConnector {
    override fun connect(url: String): LiveSocket {
        val socket = OkHttpLiveSocket()
        socket.webSocket = http.newWebSocket(Request.Builder().url(url).build(), socket.listener)
        return socket
    }

    private companion object {
        val defaultClient: OkHttpClient by lazy {
            PuzzleQuestApi.defaultHttpClient
                .newBuilder()
                .pingInterval(25, TimeUnit.SECONDS)
                // A websocket waits for messages for as long as it's open.
                .readTimeout(0, TimeUnit.SECONDS)
                .build()
        }
    }
}

private class OkHttpLiveSocket : LiveSocket {
    lateinit var webSocket: WebSocket

    /** Messages as they arrive, closed (with the reason) when the connection is. */
    private val messages = Channel<String>(Channel.UNLIMITED)

    val listener =
        object : WebSocketListener() {
            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                messages.trySend(text)
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                webSocket.close(1000, null)
                messages.close(IOException("closed by the server ($code)"))
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                messages.close(IOException("closed ($code)"))
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) {
                messages.close(t)
            }
        }

    // OkHttp queues what's sent before the connection opens.
    override suspend fun send(text: String) {
        if (!webSocket.send(text)) throw IOException("the connection has closed")
    }

    override suspend fun receive(): String = messages.receive()

    override fun close() {
        webSocket.cancel()
        messages.close(IOException("closed"))
    }
}
