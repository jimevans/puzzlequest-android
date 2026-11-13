package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class QuestClockTest {
    private val start = Instant.parse("2026-11-11T16:00:00Z")

    @Test
    fun `a running clock counts up from when it started, and adds penalties to the total`() {
        val clock = QuestClock(running = true, clockStart = start, elapsedMs = 0, penaltyMs = 60_000, totalMs = 0)
        assertTrue(clock.isCounting)
        assertEquals(65_000, clock.elapsedMs(start.plusSeconds(65)))
        assertEquals(125_000, clock.totalMs(start.plusSeconds(65)))
    }

    @Test
    fun `a stopped clock keeps its time, whatever the time is now`() {
        val clock =
            QuestClock(running = false, clockStart = null, elapsedMs = 90_000, penaltyMs = 30_000, totalMs = 120_000)
        assertFalse(clock.isCounting)
        assertEquals(90_000, clock.elapsedMs(start.plusSeconds(9_999)))
        assertEquals(120_000, clock.totalMs(start.plusSeconds(9_999)))
    }

    @Test
    fun `times show as a clock does, with hours only when there are some`() {
        val cases =
            mapOf(
                0L to "0:00",
                5_000L to "0:05",
                245_999L to "4:05",
                3_845_000L to "1:04:05",
                36_000_000L to "10:00:00",
                // A phone whose clock is behind the server's.
                -5_000L to "0:00",
            )
        for ((ms, shown) in cases) assertEquals(shown, QuestClock.format(ms))
    }
}

class WhoSeesWhatTest {
    @Test
    fun `players see their quests, staff, administrators and roles added later see the website note`() {
        assertEquals(HomeScreen.QUESTS, HomeScreen.of(Role.Player))
        for (role in listOf(Role.Staff, Role.Admin, Role.Other)) assertEquals(HomeScreen.WEBSITE, HomeScreen.of(role))
    }
}

class HomeTest {
    @get:Rule val server = FakeServer()

    private val sockets = FakeLiveSockets()
    private val player = alice.copy(emailVerified = false)

    /** The API, its blocking calls run by the test's scheduler, so the test controls when they happen. */
    private fun TestScope.api(tokens: TokenStore = InMemoryTokenStore("kept")) =
        PuzzleQuestApi(server.url, tokens, io = StandardTestDispatcher(testScheduler))

    private fun TestScope.home() =
        HomeModel(player, api()) { channel, ticket ->
            LiveUpdateFollower("ws://test", channel, sockets, ticket, wait = { delay(it) })
        }

    /** The server's list of quests, and how many times it was asked. */
    private var quests = questPage()
    private var fetches = 0

    private fun answerQuests() =
        server.answer { request ->
            when (request) {
                "GET /api/v1/users/alice/quests" -> {
                    fetches++
                    200 to quests
                }

                "POST /api/v1/users/alice/live-tickets" -> {
                    201 to """{"ticket":"t$fetches","expiresAt":"2026-11-11T16:00:00.000Z"}"""
                }

                else -> {
                    404 to "{}"
                }
            }
        }

    private fun CoroutineScope.following(home: HomeModel) = launch { home.followLiveUpdates() }

    @Test
    fun `every page is fetched, each after the one before`() =
        runTest {
            server.respond(200, questPage(questJson("a"), questJson("b"), next = "cursor-1"))
            server.respond(200, questPage(questJson("c")))
            assertEquals(listOf("a", "b", "c"), api().userQuests("alice").map { it.questName })
            assertEquals(null, server.sent().url.queryParameter("after"))
            assertEquals("cursor-1", server.sent().url.queryParameter("after"))
        }

    @Test
    fun `a quest is read with its status, pause, and clock`() =
        runTest {
            server.respond(
                200,
                questPage(questJson("a", paused = true, running = false, clockStart = null, elapsedMs = 5_000)),
            )
            val quest = api().userQuests("alice").single()
            assertEquals(
                QuestSummary(
                    questName = "a",
                    displayName = "Quest a",
                    status = QuestStatus.IN_PROGRESS,
                    statusDescription = "In progress",
                    paused = true,
                    progress =
                        QuestClock(
                            running = false,
                            clockStart = null,
                            elapsedMs = 5_000,
                            penaltyMs = 0,
                            totalMs = 5_000,
                        ),
                ),
                quest,
            )
        }

    @Test
    fun `before fetching it has no quests, then it has them, or none`() =
        runTest {
            answerQuests()
            val home = home()
            assertNull(home.state.value.quests)
            home.load()
            assertEquals(emptyList<QuestSummary>(), home.state.value.quests)
            quests = questPage(questJson("a"))
            home.load()
            assertEquals(
                listOf("a"),
                home.state.value.quests
                    ?.map { it.questName },
            )
        }

    @Test
    fun `a failed first fetch is reported, with no quests`() =
        runTest {
            server.respond(500, """{"type":"/problems/unexpected","status":500,"title":"Oops"}""")
            val home = home()
            home.load()
            assertTrue(home.state.value.loadFailed)
            assertNull(home.state.value.quests)
        }

    @Test
    fun `a failed refresh keeps the quests already shown`() =
        runTest {
            server.respond(200, questPage(questJson("a")))
            server.respond(500, "{}")
            val home = home()
            home.load()
            home.load()
            assertTrue(home.state.value.loadFailed)
            assertEquals(
                listOf("a"),
                home.state.value.quests
                    ?.map { it.questName },
            )
            // And a later success clears the failure.
            server.respond(200, questPage(questJson("a")))
            home.load()
            assertFalse(home.state.value.loadFailed)
        }

    @Test
    fun `live updates fetch the list on connecting and on each change, and count only real changes`() =
        runTest {
            answerQuests()
            quests = questPage(questJson("a", elapsedMs = 1_000))
            val home = home()
            backgroundScope.following(home)
            runCurrent()
            sockets.current.accept()
            runCurrent()
            assertEquals(1, fetches)
            assertEquals(
                listOf("a"),
                home.state.value.quests
                    ?.map { it.questName },
            )
            assertEquals(0, home.state.value.liveChanges)

            // Only the clock has moved: fetched, but not a change.
            quests = questPage(questJson("a", elapsedMs = 9_000))
            sockets.current.deliver(QUESTS_CHANGED)
            runCurrent()
            assertEquals(2, fetches)
            assertEquals(0, home.state.value.liveChanges)

            quests = questPage(questJson("a"), questJson("b"))
            sockets.current.deliver(QUESTS_CHANGED)
            runCurrent()
            assertEquals(
                listOf("a", "b"),
                home.state.value.quests
                    ?.map { it.questName },
            )
            assertEquals(1, home.state.value.liveChanges)

            // Another quest's progress isn't the list's business.
            sockets.current.deliver("""{"message":"questUpdated","questName":"a","entryId":"e"}""")
            runCurrent()
            assertEquals(3, fetches)
        }

    @Test
    fun `after a drop it reconnects and fetches again, since changes may have been missed`() =
        runTest {
            answerQuests()
            val home = home()
            backgroundScope.following(home)
            runCurrent()
            sockets.current.accept()
            runCurrent()
            sockets.current.drop()
            runCurrent()
            assertFalse(home.state.value.isLive)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(2, sockets.opened.size)
            sockets.current.accept()
            runCurrent()
            assertEquals(2, fetches)
            assertTrue(home.state.value.isLive)
        }

    @Test
    fun `it's live only once the fetch on connecting is done, and not after it stops`() =
        runTest {
            answerQuests()
            val home = home()
            val following = backgroundScope.following(home)
            runCurrent()
            assertFalse(home.state.value.isLive)
            sockets.current.accept()
            runCurrent()
            assertTrue(home.state.value.isLive)
            following.cancel()
            runCurrent()
            assertFalse(home.state.value.isLive)
            assertTrue(sockets.current.closed)
        }

    @Test
    fun `the verification email is sent again, once`() =
        runTest {
            server.respond(204, "")
            val reminder = home().emailReminder
            assertTrue(reminder.canSend)
            reminder.send()
            assertEquals(EmailReminderModel.State.Sent, reminder.state.value)
            assertFalse(reminder.canSend)
            reminder.send()
            assertEquals(1, server.requestCount)
            assertEquals("/api/v1/users/alice/email-verification-requests", server.sent().url.encodedPath)
        }

    @Test
    fun `sending it too often is explained, and can be tried again`() =
        runTest {
            server.respond(429, """{"type":"/problems/too-many-requests","status":429,"title":"Slow down"}""")
            val reminder = home().emailReminder
            reminder.send()
            assertEquals(EmailReminderModel.State.Failed(EmailReminderFailure.TOO_MANY_REQUESTS), reminder.state.value)
            assertTrue(reminder.canSend)
        }

    @Test
    fun `without a connection the reminder says so`() =
        runTest {
            val reminder = EmailReminderModel("alice", PuzzleQuestApi(nowhere(), InMemoryTokenStore()))
            reminder.send()
            assertEquals(EmailReminderModel.State.Failed(EmailReminderFailure.UNREACHABLE), reminder.state.value)
        }

    @Test
    fun `refreshing who is signed in picks up a change made elsewhere, such as a verified email address`() =
        runTest {
            val tokens = InMemoryTokenStore()
            server.respond(201, """{"user":${ALICE_JSON.replace("true", "false")},"token":"t"}""")
            server.respond(200, """{"user":$ALICE_JSON}""")
            val session = SessionStore(api(tokens), tokens)
            session.signIn("alice", "secret")
            assertEquals(false, (session.state.value as SessionStore.State.SignedIn).user.emailVerified)
            session.refreshUser()
            assertEquals(SessionStore.State.SignedIn(alice), session.state.value)
        }

    @Test
    fun `refreshing forgets an ended session, an unreachable server changes nothing`() =
        runTest {
            val tokens = InMemoryTokenStore()
            server.respond(201, """{"user":$ALICE_JSON,"token":"t"}""")
            server.respond(401, """{"type":"/problems/sign-in-required","status":401,"title":"Sign in"}""")
            val session = SessionStore(api(tokens), tokens)
            session.signIn("alice", "secret")
            session.refreshUser()
            assertEquals(SessionStore.State.SignedOut, session.state.value)
            assertNull(tokens.read())

            val kept = InMemoryTokenStore("kept")
            val offline = SessionStore(PuzzleQuestApi(nowhere(), kept), kept)
            offline.refreshUser()
            // Not signed in: nothing to refresh, and nothing asked.
            assertEquals(SessionStore.State.Restoring, offline.state.value)
        }
}
