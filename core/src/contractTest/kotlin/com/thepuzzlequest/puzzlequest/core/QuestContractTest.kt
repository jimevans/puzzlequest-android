package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** A player's quest screen, against the test server (see TestSite). */
class QuestContractTest {
    @Before
    fun needsTheTestServer() {
        assumeTrue("Set PQ_TEST_SERVER_URL (see scripts/start-test-server.sh)", TestSite.url != null)
    }

    // runBlocking, not runTest: these wait on a real server, in real time.

    @Test
    fun `a quest isn't available until it starts, then it has one stop, waiting to be found`() =
        runBlocking {
            val player = TestSite.newPlayer()
            val admin = TestSite.adminToken()
            val questName = TestSite.makeQuest(player.userName, admin)
            assertNull(player.api.playerQuest(questName))

            TestSite.start(questName, admin)
            val quest = player.api.playerQuest(questName)!!
            assertEquals(questName, quest.questName)
            assertEquals("Quest $questName", quest.displayName)
            assertFalse(quest.entry.isTeam)
            assertEquals(QuestStatus.IN_PROGRESS, quest.entry.status)
            assertFalse(quest.entry.paused)
            assertTrue(quest.entry.clock.isCounting)
            assertEquals(1, quest.entry.stops.size)
            val stop = quest.entry.currentStop!!
            assertEquals(1, stop.number)
            assertEquals("A puzzle", stop.displayName)
            assertEquals(StopStatus.AWAITING_ACTIVATION, stop.status)
            assertNull(stop.solutionText)
            assertTrue(quest.entry.finishedStops.isEmpty())
        }

    @Test
    fun `solving the last stop finishes the quest, with the solution shown`() =
        runBlocking {
            val player = TestSite.newPlayer()
            val admin = TestSite.adminToken()
            val questName = TestSite.makeQuest(player.userName, admin)
            TestSite.start(questName, admin)

            TestSite.solveCurrentStop(questName, player, admin)
            val quest = player.api.playerQuest(questName)!!
            assertTrue(quest.entry.isFinished)
            assertFalse(quest.entry.clock.isCounting)
            assertNull(quest.entry.currentStop)
            val stop = quest.entry.finishedStops.first()
            assertEquals(StopStatus.COMPLETED, stop.status)
            assertEquals("A piano", stop.solutionText)
            assertNotNull(stop.endTime)
            assertNotNull(stop.clock)
        }

    @Test
    fun `in a race, a finished player sees the standings, provisional until all finish`() =
        runBlocking {
            val first = TestSite.newPlayer()
            val second = TestSite.newPlayer()
            val admin = TestSite.adminToken()
            val questName = TestSite.makeQuest(listOf(first.userName, second.userName), admin)
            TestSite.start(questName, admin)

            TestSite.solveCurrentStop(questName, first, admin)
            val own = first.api.playerQuest(questName)!!
            val standings = first.api.standings(questName)
            assertTrue(standings.provisional)
            assertFalse(standings.rivalsHidden)
            assertEquals(2, standings.entries.size)
            assertEquals(own.entry.id, standings.entries.first().id)
            assertEquals(1, standings.entries.first().rank)
            assertNull(standings.entries.last().rank)

            TestSite.solveCurrentStop(questName, second, admin)
            val final = first.api.standings(questName)
            assertTrue(final.isFinal)
            assertEquals(listOf(1, 2), final.entries.map { it.rank })
        }

    @Test
    fun `a quest reset by the puzzle master isn't available to its player`() =
        runBlocking {
            val player = TestSite.newPlayer()
            val admin = TestSite.adminToken()
            val questName = TestSite.makeQuest(player.userName, admin)
            TestSite.start(questName, admin)
            TestSite.reset(questName, admin)
            assertNull(player.api.playerQuest(questName))
        }

    @Test
    fun `following the quest hears of the player's own progress`() =
        runBlocking {
            val player = TestSite.newPlayer()
            val admin = TestSite.adminToken()
            val questName = TestSite.makeQuest(player.userName, admin)
            TestSite.start(questName, admin)

            val connected = CompletableDeferred<Unit>()
            val changed = CompletableDeferred<Unit>()
            val follower =
                LiveUpdateFollower(player.api.liveUpdatesUrl, LiveChannel.Quest(questName), ticket = {
                    player.api.questLiveTicket(questName)
                })
            val following =
                launch(Dispatchers.IO) {
                    follower.run { event ->
                        when {
                            event is LiveEvent.Connected -> {
                                connected.complete(Unit)
                            }

                            (event as? LiveEvent.Update)?.update.let {
                                it is LiveUpdate.QuestChanged &&
                                    it.questName == questName
                            } -> {
                                changed.complete(Unit)
                            }
                        }
                    }
                }
            try {
                withTimeout(15_000) { connected.await() }
                TestSite.solveCurrentStop(questName, player, admin)
                withTimeout(15_000) { changed.await() }
            } finally {
                following.cancel()
            }
        }
}
