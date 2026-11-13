package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** A player's home screen, against the test server (see TestSite). */
class HomeContractTest {
    @Before
    fun needsTheTestServer() {
        assumeTrue("Set PQ_TEST_SERVER_URL (see scripts/start-test-server.sh)", TestSite.url != null)
    }

    // runBlocking, not runTest: these wait on a real server, in real time.

    @Test
    fun `a new player has no quests, and one made for them appears once it starts`() =
        runBlocking {
            val (player, api) = TestSite.newPlayer()
            assertEquals(emptyList<QuestSummary>(), api.userQuests(player))

            val admin = TestSite.adminToken()
            val questName = TestSite.makeQuest(player, admin)
            // Not started: not on the list yet.
            assertEquals(emptyList<QuestSummary>(), api.userQuests(player))

            TestSite.start(questName, admin)
            val quest = api.userQuests(player).single()
            assertEquals(questName, quest.questName)
            assertEquals("Quest $questName", quest.displayName)
            assertEquals(QuestStatus.IN_PROGRESS, quest.status)
            assertTrue(quest.statusDescription.isNotEmpty())
            assertFalse(quest.paused)
            assertTrue(quest.progress.isCounting)
            assertTrue(quest.progress.totalMs(Instant.now()) >= 0)
        }

    @Test
    fun `a player can have live tickets for their own quests, one for each connection`() =
        runBlocking {
            val (player, api) = TestSite.newPlayer()
            val first = api.userLiveTicket(player)
            val second = api.userLiveTicket(player)
            assertTrue(first.isNotEmpty())
            assertNotEquals(first, second)
        }

    @Test
    fun `following the player's quests hears of a quest starting`() =
        runBlocking {
            val (player, api) = TestSite.newPlayer()
            val admin = TestSite.adminToken()
            val questName = TestSite.makeQuest(player, admin)

            val connected = CompletableDeferred<Unit>()
            val changed = CompletableDeferred<Unit>()
            val follower =
                LiveUpdateFollower(api.liveUpdatesUrl, LiveChannel.UserQuests(player), ticket = {
                    api.userLiveTicket(player)
                })
            val following =
                launch(Dispatchers.IO) {
                    follower.run { event ->
                        when (event) {
                            is LiveEvent.Connected -> {
                                connected.complete(Unit)
                            }

                            LiveEvent.Update(LiveUpdate.QuestsChanged) -> {
                                changed.complete(Unit)
                            }

                            else -> {}
                        }
                    }
                }
            try {
                withTimeout(15_000) { connected.await() }
                TestSite.start(questName, admin)
                withTimeout(15_000) { changed.await() }
                assertEquals(listOf(questName), api.userQuests(player).map { it.questName })
            } finally {
                following.cancel()
            }
        }

    @Test
    fun `the verification email can be sent again, a few times in a while`() =
        runBlocking {
            val (player, api) = TestSite.newPlayer()
            // The server sends it at most three times in 15 minutes.
            repeat(3) { api.sendVerificationEmail(player) }
            try {
                api.sendVerificationEmail(player)
                fail("the fourth should have been refused")
            } catch (error: ApiError) {
                assertEquals(EmailReminderFailure.TOO_MANY_REQUESTS, EmailReminderFailure.of(error))
            }
        }

    @Test
    fun `a new player hasn't verified their email address`() =
        runBlocking {
            val (_, api) = TestSite.newPlayer()
            assertFalse(api.currentUser().emailVerified)
        }
}
