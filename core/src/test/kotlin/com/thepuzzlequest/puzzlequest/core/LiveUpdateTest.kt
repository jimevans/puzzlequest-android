package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LiveUpdateTest {
    private val sockets = FakeLiveSockets()
    private val events = mutableListOf<LiveEvent>()
    private val waits = mutableListOf<Duration>()
    private var tickets = 0

    /**
     * A follower following in the background, whose waits are recorded and
     * pass on the test's virtual clock (advanceTimeBy moves it on).
     */
    private fun CoroutineScope.follow(
        channel: LiveChannel = LiveChannel.UserQuests("alice"),
        ticket: suspend () -> String = { "ticket-${++tickets}" },
    ) = launch {
        LiveUpdateFollower("ws://localhost/api/v1/updates", channel, sockets, ticket, wait = {
            waits += it
            delay(it)
        })
            .run { events += it }
    }

    private fun sentJson(socket: FakeLiveSocket) =
        socket.sent.map { Json.decodeFromString<JsonObject>(it).mapValues { (_, v) -> v.jsonPrimitive.content } }

    @Test
    fun `it connects to the websocket and subscribes with a new ticket`() =
        runTest {
            val following = backgroundScope.follow()
            runCurrent()
            assertEquals("ws://localhost/api/v1/updates", sockets.current.url)
            assertEquals(
                listOf(mapOf("message" to "subscribe", "userName" to "alice", "ticket" to "ticket-1")),
                sentJson(sockets.current),
            )
            following.cancel()
        }

    @Test
    fun `it subscribes to a quest the same way, for the quest screen`() =
        runTest {
            backgroundScope.follow(LiveChannel.Quest("harbor-walk"))
            runCurrent()
            assertEquals(
                listOf(mapOf("message" to "subscribe", "questName" to "harbor-walk", "ticket" to "ticket-1")),
                sentJson(sockets.current),
            )
        }

    @Test
    fun `it reports the connection and each update, and ignores messages it doesn't know`() =
        runTest {
            backgroundScope.follow()
            runCurrent()
            sockets.current.accept("c1")
            sockets.current.deliver("""{"message":"somethingNew","questName":"q"}""")
            sockets.current.deliver("not JSON")
            sockets.current.deliver(QUESTS_CHANGED)
            runCurrent()
            assertEquals(
                listOf(LiveEvent.Connected("c1"), LiveEvent.Update(LiveUpdate.QuestsChanged)),
                events,
            )
        }

    @Test
    fun `after a drop it reconnects with a new ticket, waiting longer after each failure`() =
        runTest {
            backgroundScope.follow(ticket = {
                tickets++
                // The second and third tickets can't be had.
                if (tickets in 2..3) throw ApiError.Unreachable
                "ticket-$tickets"
            })
            runCurrent()
            sockets.current.accept()
            runCurrent()
            sockets.current.drop()
            runCurrent()
            assertEquals(LiveEvent.Disconnected, events.last())
            assertTrue(sockets.opened[0].closed)
            // It had connected, so the first wait is short; then each failure
            // waits longer.
            advanceTimeBy(1.seconds + 2.seconds + 4.seconds + 1.milliseconds)
            runCurrent()
            assertEquals(listOf(1.seconds, 2.seconds, 4.seconds), waits)
            assertEquals(2, sockets.opened.size)
            assertTrue(
                sockets.current.sent
                    .single()
                    .contains("ticket-4"),
            )
            // Never accepted, then dropped: no Disconnected event.
            sockets.current.drop()
            runCurrent()
            assertEquals(1, events.count { it == LiveEvent.Disconnected })
            assertEquals(listOf(1.seconds, 2.seconds, 4.seconds, 8.seconds), waits)
        }

    @Test
    fun `the wait between attempts grows to 15 seconds at most`() =
        runTest {
            backgroundScope.follow(ticket = { throw ApiError.Unreachable })
            advanceTimeBy(60.seconds)
            assertEquals(listOf(1, 2, 4, 8, 15, 15).map { it.seconds }, waits.take(6))
        }

    @Test
    fun `it stops, and closes the connection, when cancelled`() =
        runTest {
            val following = backgroundScope.follow()
            runCurrent()
            sockets.current.accept()
            runCurrent()
            following.cancel()
            runCurrent()
            assertTrue(sockets.current.closed)
            assertEquals(1, sockets.opened.size)
            assertEquals(listOf(LiveEvent.Connected("c1")), events)
        }

    @Test
    fun `each message the API describes is read`() {
        val read = LiveUpdateFollower::message
        assertEquals(
            LiveUpdateFollower.Message.Connection("c1"),
            read("""{"message":"connection","connectionId":"c1"}"""),
        )
        assertEquals(LiveUpdateFollower.Message.Update(LiveUpdate.QuestsChanged), read(QUESTS_CHANGED))
        val changes =
            mapOf(
                "puzzleActivated" to """"puzzleName":"p","activationTime":"2026-11-11T16:00:00.000Z"""",
                "puzzleSolved" to
                    """"puzzleName":"p","solutionText":"s","endTime":"2026-11-11T16:00:00Z","questComplete":false""",
                "puzzleStarted" to """"puzzleName":"p","displayName":"P","startTime":"2026-11-11T16:00:00.000Z"""",
                "hintRevealed" to """"puzzleName":"p","hintText":"h","timePenaltySeconds":60,"nextHint":null""",
                "questUpdated" to null,
                "messageSent" to null,
                "challengeAnswered" to null,
            )
        for ((kind, fields) in changes) {
            val text = """{"message":"$kind","questName":"q","entryId":"e"${fields?.let { ",$it" } ?: ""}}"""
            assertEquals(
                text,
                LiveUpdateFollower.Message.Update(LiveUpdate.QuestChanged(kind, "q", "e")),
                read(text),
            )
        }
    }

    @Test
    fun `a field the app doesn't know is ignored`() {
        assertEquals(
            LiveUpdateFollower.Message.Update(LiveUpdate.QuestChanged("questUpdated", "q", "e")),
            LiveUpdateFollower.message("""{"message":"questUpdated","questName":"q","entryId":"e","colour":"teal"}"""),
        )
    }

    @Test
    fun `a message the app doesn't know, or can't read, is nothing`() {
        assertNull(LiveUpdateFollower.message("""{"message":"somethingNew"}"""))
        assertNull(LiveUpdateFollower.message("""{"message":"puzzleSolved","questName":"q"}"""))
        assertNull(LiveUpdateFollower.message("[1, 2]"))
        assertNull(LiveUpdateFollower.message("not JSON"))
    }

    @Test
    fun `the websocket is on the API's site, encrypted when the site is`() {
        val tokens = InMemoryTokenStore()
        assertEquals(
            "wss://thepuzzlequest.com/api/v1/updates",
            PuzzleQuestApi("https://thepuzzlequest.com", tokens).liveUpdatesUrl,
        )
        assertEquals(
            "ws://localhost:3000/api/v1/updates",
            PuzzleQuestApi("http://localhost:3000/", tokens).liveUpdatesUrl,
        )
    }
}
