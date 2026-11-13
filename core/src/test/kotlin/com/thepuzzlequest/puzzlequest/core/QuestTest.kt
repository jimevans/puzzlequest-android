package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** A clock as the API sends one, stopped unless running. */
fun clockJson(
    elapsedMs: Long = 0,
    penaltyMs: Long = 0,
    running: Boolean = false,
    started: Boolean = true,
    puzzleName: String? = null,
): String {
    val start = if (started) "\"2026-11-11T16:00:00.000Z\"" else "null"
    val clockStart = if (running) "\"2026-11-11T16:00:00.000Z\"" else "null"
    val name = puzzleName?.let { "\"puzzleName\":\"$it\"," } ?: ""
    return """{$name"startTime":$start,"endTime":null,"running":$running,"clockStart":$clockStart,""" +
        """"elapsedMs":$elapsedMs,"penaltyMs":$penaltyMs,"totalMs":${elapsedMs + penaltyMs}}"""
}

/** A stop as the API sends one to its player. */
fun stopJson(
    name: String,
    status: String = "inProgress",
    solution: String? = null,
): String {
    val description =
        when (status) {
            "awaitingActivation" -> "Find the puzzle"
            "completed" -> "Solved"
            else -> "In progress"
        }
    val solutionField = solution?.let { ""","solutionText":"$it"""" } ?: ""
    return """{"puzzleName":"$name","displayName":"The $name","status":"$status",""" +
        """"statusDescription":"$description","hintsRevealed":0,"activationTime":null,""" +
        """"startTime":"2026-11-11T16:00:00.000Z","endTime":null,"skipped":false,"hints":[]$solutionField}"""
}

/** A player's quest as the API sends it, with their entry's stops. */
fun playerQuestJson(
    stops: List<String>,
    status: String = "inProgress",
    paused: Boolean = false,
    clocks: List<String> = emptyList(),
    kind: String = "player",
    entryId: String = "e1",
): String {
    val running = !paused && status != "completed"
    val clockStart = if (running) "\"2026-11-11T16:00:00.000Z\"" else "null"
    val description = if (status == "completed") "Finished" else "In progress"
    return """{"questName":"harbor","displayName":"Harbor walk","entries":[{"id":"$entryId",""" +
        """"entrant":{"kind":"$kind","name":"pat","displayName":"Pat"},""" +
        """"status":"$status","statusDescription":"$description","paused":$paused,""" +
        """"messages":[],"challenge":null,"puzzles":[${stops.joinToString(",")}],""" +
        """"progress":{"startTime":"2026-11-11T16:00:00.000Z","endTime":null,"running":$running,""" +
        """"clockStart":$clockStart,"elapsedMs":600000,"penaltyMs":60000,"totalMs":660000,""" +
        """"puzzles":[${clocks.joinToString(",")}]}}]}"""
}

/** A quest with no entry of the player's, as after a reset. */
const val EMPTY_QUEST_JSON = """{"questName":"harbor","displayName":"Harbor walk","entries":[]}"""

/** One entry in the standings, as the spectator view sends it. */
fun standingJson(
    id: String,
    name: String?,
    rank: Int?,
    status: String = "completed",
): String {
    val description = if (status == "completed") "Finished" else "In progress"
    val progress = clockJson(elapsedMs = 1000).dropLast(1) + ""","puzzles":[]}"""
    return """{"id":"$id","entrant":{"kind":"team","displayName":${name?.let { "\"$it\"" } ?: "null"}},""" +
        """"status":"$status","statusDescription":"$description","paused":false,"rank":${rank ?: "null"},""" +
        """"progress":$progress,"puzzles":[]}"""
}

fun standingsJson(
    entries: List<String>,
    provisional: Boolean,
) = """{"questName":"harbor","displayName":"Harbor walk","racing":$provisional,"stopsHidden":false,""" +
    """"rivalsHidden":false,"provisional":$provisional,"entries":[${entries.joinToString(",")}]}"""

const val QUEST_UPDATED = """{"message":"questUpdated","questName":"harbor","entryId":"e1"}"""

class PlayerQuestTest {
    @get:Rule val server = FakeServer()

    private fun api() = PuzzleQuestApi(server.url, InMemoryTokenStore("kept"))

    @Test
    fun `stops are numbered as reached, with their clocks, and the current one is the unsolved one`() =
        runTest {
            server.respond(
                200,
                playerQuestJson(
                    listOf(
                        stopJson("quay", "completed", solution = "ANCHOR"),
                        stopJson("customs", "awaitingActivation"),
                    ),
                    clocks = listOf(clockJson(elapsedMs = 5_000, puzzleName = "quay")),
                    kind = "team",
                ),
            )
            val quest = api().playerQuest("harbor")!!
            assertEquals("GET /api/v1/quests/harbor", server.sent().let { "${it.method} ${it.url.encodedPath}" })
            assertEquals("Harbor walk", quest.displayName)
            assertTrue(quest.entry.isTeam)
            assertEquals(listOf(1, 2), quest.entry.stops.map { it.number })
            assertEquals("customs", quest.entry.currentStop?.puzzleName)
            assertEquals(StopStatus.AWAITING_ACTIVATION, quest.entry.currentStop?.status)
            assertEquals(listOf("quay"), quest.entry.finishedStops.map { it.puzzleName })
            val quay = quest.entry.stops.first()
            assertEquals("ANCHOR", quay.solutionText)
            assertEquals(5_000L, quay.clock?.elapsedMs)
            assertEquals(Instant.parse("2026-11-11T16:00:00Z"), quay.startTime)
            assertNull(quest.entry.stops[1].clock)
            assertEquals(660_000L, quest.entry.clock.totalMs)
            assertEquals(60_000L, quest.entry.clock.penaltyMs)
        }

    @Test
    fun `a finished quest has no current stop, and the latest solved stop comes first`() =
        runTest {
            server.respond(
                200,
                playerQuestJson(
                    listOf(stopJson("quay", "completed"), stopJson("customs", "completed")),
                    status = "completed",
                ),
            )
            val quest = api().playerQuest("harbor")!!
            assertTrue(quest.entry.isFinished)
            assertNull(quest.entry.currentStop)
            assertEquals(listOf("customs", "quay"), quest.entry.finishedStops.map { it.puzzleName })
        }

    @Test
    fun `a quest that isn't found, or has no entry of the player's, isn't available`() =
        runTest {
            server.respond(404, """{"type":"/problems/not-found","status":404,"title":"Not found"}""")
            assertNull(api().playerQuest("harbor"))
            server.respond(200, EMPTY_QUEST_JSON)
            assertNull(api().playerQuest("harbor"))
        }

    @Test
    fun `other refusals are errors`() =
        runTest {
            server.respond(403, """{"type":"/problems/forbidden","status":403,"title":"Forbidden"}""")
            val error =
                try {
                    api().playerQuest("harbor")
                    null
                } catch (refused: ApiError.Refused) {
                    refused
                }
            assertEquals(403, error?.problem?.status)
        }

    @Test
    fun `standings keep the order sent, and a deleted team has no name`() =
        runTest {
            server.respond(
                200,
                standingsJson(
                    listOf(
                        standingJson("e2", "Gulls", 1),
                        standingJson("e1", null, 2),
                        standingJson("e3", "Crabs", null, "inProgress"),
                    ),
                    provisional = true,
                ),
            )
            val standings = api().standings("harbor")
            assertEquals("/api/v1/quests/harbor/spectator-view", server.sent().url.encodedPath)
            assertTrue(standings.provisional)
            assertFalse(standings.isFinal)
            assertEquals(listOf("e2", "e1", "e3"), standings.entries.map { it.id })
            assertEquals(listOf(1, 2, null), standings.entries.map { it.rank })
            assertNull(standings.entries[1].displayName)
            assertTrue(standings.entries[0].isTeam)
            assertTrue(standings.entries[0].started)
        }
}

class QuestChangeTest {
    private fun quest(
        stops: List<Stop>,
        paused: Boolean = false,
        finished: Boolean = false,
    ) = PlayerQuest(
        "harbor",
        "Harbor walk",
        QuestEntryProgress(
            id = "e1",
            isTeam = false,
            status = if (finished) QuestStatus.COMPLETED else QuestStatus.IN_PROGRESS,
            statusDescription = "",
            paused = paused,
            startTime = null,
            endTime = null,
            clock = QuestClock(false, null, 0, 0, 0),
            stops = stops,
        ),
    )

    private fun stop(
        number: Int,
        name: String,
        status: StopStatus,
    ) = Stop(number, name, "The $name", status, "")

    private fun standing(
        id: String,
        rank: Int?,
    ) = Standing(id, "Team $id", true, QuestStatus.COMPLETED, "", false, rank, true, QuestClock(false, null, 0, 0, 0))

    @Test
    fun `pausing, resuming, solving, reaching a stop, and finishing are announced, in that order`() {
        val before = quest(listOf(stop(1, "quay", StopStatus.IN_PROGRESS)))
        assertEquals(
            listOf(QuestChange.Paused),
            QuestChange.between(before, quest(before.entry.stops, paused = true)),
        )
        assertEquals(
            listOf(QuestChange.Resumed),
            QuestChange.between(quest(before.entry.stops, paused = true), before),
        )
        val after =
            quest(listOf(stop(1, "quay", StopStatus.COMPLETED), stop(2, "customs", StopStatus.AWAITING_ACTIVATION)))
        assertEquals(
            listOf(QuestChange.StopSolved("The quay"), QuestChange.StopReached(2, "The customs")),
            QuestChange.between(before, after),
        )
        assertEquals(
            listOf(QuestChange.StopSolved("The quay"), QuestChange.Finished),
            QuestChange.between(before, quest(listOf(stop(1, "quay", StopStatus.COMPLETED)), finished = true)),
        )
        // The clocks moving aren't changes.
        assertEquals(emptyList<QuestChange>(), QuestChange.between(before, before))
    }

    @Test
    fun `rivals finishing and the final result are announced, but not the player's own finish`() {
        val before = Standings(true, false, listOf(standing("e1", 1), standing("e2", null), standing("e3", null)))
        val after = Standings(true, false, listOf(standing("e1", 1), standing("e2", 2), standing("e3", null)))
        assertEquals(
            listOf(QuestChange.RivalFinished(standing("e2", 2), 2)),
            QuestChange.between(before, after, ownEntryId = "e1"),
        )
        val final = Standings(false, false, listOf(standing("e3", 1), standing("e1", 2), standing("e2", 3)))
        assertEquals(
            listOf(
                QuestChange.RivalFinished(standing("e3", 1), 1),
                QuestChange.StandingsFinal(listOf(standing("e3", 1))),
            ),
            QuestChange.between(after, final, ownEntryId = "e1"),
        )
        // Its own entry finishing isn't a rival's.
        assertEquals(
            emptyList<QuestChange>(),
            QuestChange.between(
                Standings(true, false, listOf(standing("e1", null), standing("e2", null))),
                Standings(true, false, listOf(standing("e1", 1), standing("e2", null))),
                ownEntryId = "e1",
            ),
        )
    }
}

class QuestModelTest {
    @get:Rule val server = FakeServer()

    private val sockets = FakeLiveSockets()

    /** What the server answers for the quest and its standings, and how often the quest was asked for. */
    private var quest = playerQuestJson(listOf(stopJson("quay")))
    private var questStatus = 200
    private var standings: String? = null
    private var fetches = 0

    private fun answer() =
        server.answer { request ->
            when (request) {
                "GET /api/v1/quests/harbor" -> {
                    fetches++
                    questStatus to quest
                }

                "GET /api/v1/quests/harbor/spectator-view" -> {
                    standings?.let { 200 to it } ?: (500 to "{}")
                }

                "POST /api/v1/quests/harbor/live-tickets" -> {
                    201 to """{"ticket":"t$fetches","expiresAt":"2026-11-11T16:00:00.000Z"}"""
                }

                else -> {
                    404 to "{}"
                }
            }
        }

    private fun TestScope.model() =
        QuestModel(
            "harbor",
            PuzzleQuestApi(server.url, InMemoryTokenStore("kept"), io = StandardTestDispatcher(testScheduler)),
        ) {
            channel,
            ticket,
            ->
            LiveUpdateFollower("ws://test", channel, sockets, ticket, wait = { delay(it) })
        }

    private fun CoroutineScope.following(model: QuestModel) = launch { model.followLiveUpdates() }

    @Test
    fun `it loads the quest, a failed first fetch is reported, and a failed refresh keeps what's shown`() =
        runTest {
            answer()
            questStatus = 500
            val model = model()
            model.load()
            assertTrue(model.state.value.loadFailed)
            assertNull(model.state.value.quest)

            questStatus = 200
            model.load()
            assertFalse(model.state.value.loadFailed)
            assertEquals(
                "quay",
                model.state.value.quest
                    ?.entry
                    ?.currentStop
                    ?.puzzleName,
            )

            questStatus = 500
            model.load()
            assertTrue(model.state.value.loadFailed)
            assertEquals(
                "quay",
                model.state.value.quest
                    ?.entry
                    ?.currentStop
                    ?.puzzleName,
            )
        }

    @Test
    fun `a quest reset while on screen becomes not available`() =
        runTest {
            answer()
            val model = model()
            model.load()
            quest = EMPTY_QUEST_JSON
            model.load()
            assertTrue(model.state.value.notAvailable)
            assertNull(model.state.value.quest)
        }

    @Test
    fun `live updates about the quest fetch it again, and what changed is reported once`() =
        runTest {
            answer()
            val model = model()
            backgroundScope.following(model)
            runCurrent()
            // It subscribes to this quest's updates.
            assertEquals(
                LiveUpdateFollower.subscription(LiveChannel.Quest("harbor"), "t0"),
                sockets.current.sent.single(),
            )
            sockets.current.accept()
            runCurrent()
            assertEquals(1, fetches)
            assertTrue(model.state.value.isLive)
            assertEquals(0, model.state.value.changeCount)

            // Another quest's update is ignored.
            sockets.current.deliver("""{"message":"questUpdated","questName":"other","entryId":"e9"}""")
            runCurrent()
            assertEquals(1, fetches)

            quest = playerQuestJson(listOf(stopJson("quay", "completed"), stopJson("customs", "awaitingActivation")))
            sockets.current.deliver(QUEST_UPDATED)
            runCurrent()
            assertEquals(2, fetches)
            assertEquals(1, model.state.value.changeCount)
            assertEquals(
                listOf(QuestChange.StopSolved("The quay"), QuestChange.StopReached(2, "The customs")),
                model.state.value.changes,
            )

            // Nothing new: fetched, but no change.
            sockets.current.deliver(QUEST_UPDATED)
            runCurrent()
            assertEquals(3, fetches)
            assertEquals(1, model.state.value.changeCount)
        }

    @Test
    fun `once finished, a race's standings are shown, and rivals finishing are announced`() =
        runTest {
            answer()
            val model = model()
            model.load()
            assertNull(model.state.value.standings)

            quest = playerQuestJson(listOf(stopJson("quay", "completed")), status = "completed")
            standings =
                standingsJson(
                    listOf(standingJson("e1", "Pat", 1), standingJson("e2", "Gulls", null, "inProgress")),
                    provisional = true,
                )
            model.load()
            assertEquals(
                listOf("e1", "e2"),
                model.state.value.standings
                    ?.entries
                    ?.map { it.id },
            )
            assertEquals(listOf(QuestChange.StopSolved("The quay"), QuestChange.Finished), model.state.value.changes)

            standings =
                standingsJson(listOf(standingJson("e1", "Pat", 1), standingJson("e2", "Gulls", 2)), provisional = false)
            model.load()
            val gulls =
                model.state.value.standings!!
                    .entries[1]
            assertEquals(
                listOf(
                    QuestChange.RivalFinished(gulls, 2),
                    QuestChange.StandingsFinal(
                        listOf(
                            model.state.value.standings!!
                                .entries[0],
                        ),
                    ),
                ),
                model.state.value.changes,
            )

            // The standings failing to load keeps those shown.
            standings = null
            model.load()
            assertEquals(
                2,
                model.state.value.standings
                    ?.entries
                    ?.size,
            )
        }

    @Test
    fun `a quest played alone has no standings`() =
        runTest {
            answer()
            quest = playerQuestJson(listOf(stopJson("quay", "completed")), status = "completed")
            standings = standingsJson(listOf(standingJson("e1", "Pat", 1)), provisional = false)
            val model = model()
            model.load()
            assertTrue(
                model.state.value.quest!!
                    .entry.isFinished,
            )
            assertNull(model.state.value.standings)
        }
}
