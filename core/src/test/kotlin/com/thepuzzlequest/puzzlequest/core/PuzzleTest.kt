package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** A puzzle as the API sends it to its player. */
fun playerPuzzleJson(
    name: String = "quay",
    status: String = "inProgress",
    type: String? = "text",
    text: String? = "What has keys?",
    hints: List<Pair<String, Int>> = emptyList(),
    nextHint: Pair<Int, Boolean>? = null,
    solution: String? = null,
    skipped: Boolean = false,
    next: String? = null,
    paused: Boolean = false,
    questStatus: String = "inProgress",
): String {
    val activated = status != "awaitingActivation"
    val typeField = if (activated) type?.let { "\"$it\"" } ?: "null" else "null"
    val textField = if (activated) text?.let { "\"$it\"" } ?: "null" else "null"
    val hintsField = hints.joinToString(",") { """{"text":"${it.first}","timePenaltySeconds":${it.second}}""" }
    val nextHintField =
        nextHint?.let { """{"timePenaltySeconds":${it.first},"solutionWarning":${it.second}}""" } ?: "null"
    val nextField = next?.let { """{"puzzleName":"$it","displayName":"The $it"}""" } ?: "null"
    val progress = clockJson(elapsedMs = 600_000, penaltyMs = 60_000).dropLast(1) + ""","puzzles":[]}"""
    return """{"quest":{"questName":"harbor","displayName":"Harbor walk","status":"$questStatus",""" +
        """"statusDescription":"In progress","paused":$paused,"progress":$progress},""" +
        """"puzzle":{"puzzleName":"$name","displayName":"The $name","status":"$status",""" +
        """"statusDescription":"In progress","skipped":$skipped,"stopNumber":2,""" +
        """"type":$typeField,"text":$textField,"hints":[$hintsField],""" +
        """"nextHint":$nextHintField,"solutionText":${solution?.let { "\"$it\"" } ?: "null"}},""" +
        """"next":$nextField}"""
}

/**
 * Answers the puzzle screen's calls: each fetch of the puzzle with the next of
 * [puzzles] (the last one over and over), and each action as [actions] says,
 * in turn: "ok", or a problem type.
 */
class PuzzleServer(
    private val server: FakeServer,
    puzzles: List<String>,
    actions: Map<String, List<String>> = emptyMap(),
) {
    private val puzzles = puzzles.toMutableList()
    private val actions = actions.mapValues { it.value.toMutableList() }
    private val counts = mutableMapOf<String, Int>()

    init {
        server.answer { request ->
            val path = request.substringAfter(" /api/v1/quests/harbor/")
            when {
                request.startsWith("POST") && path == "live-tickets" -> {
                    201 to """{"ticket":"ticket","expiresAt":"2026-11-11T16:00:00.000Z"}"""
                }

                request.startsWith("GET") && path.startsWith("puzzles/") -> {
                    increment("get")
                    val answer = if (this.puzzles.size > 1) this.puzzles.removeAt(0) else this.puzzles[0]
                    if (answer ==
                        "404"
                    ) {
                        404 to """{"type":"/problems/not-found","status":404,"title":"Not found"}"""
                    } else {
                        200 to
                            answer
                    }
                }

                else -> {
                    val action = path.substringAfterLast('/')
                    increment(action)
                    act(action)
                }
            }
        }
    }

    @Synchronized
    private fun increment(name: String) {
        counts[name] = (counts[name] ?: 0) + 1
    }

    /** How many times an action (`activation`, `solution-attempts`, `hints`) or `get` was called. */
    @Synchronized
    fun count(name: String): Int = counts[name] ?: 0

    private fun act(action: String): Pair<Int, String> {
        val queue = actions[action]
        val answer = queue?.let { if (it.size > 1) it.removeAt(0) else it[0] } ?: "ok"
        return when (answer) {
            "ok" -> {
                200 to
                    when (action) {
                        "activation" -> """{"activationTime":"2026-11-11T16:05:00.000Z"}"""
                        "solution-attempts" -> """{"solutionText":"A piano","nextPuzzle":null,"questComplete":false}"""
                        else -> """{"text":"Look up","timePenaltySeconds":60,"nextHint":null}"""
                    }
            }

            else -> {
                val status =
                    when (answer) {
                        "/problems/wrong-answer", "/problems/wrong-activation-code" -> 422
                        "/problems/too-many-requests" -> 429
                        else -> 409
                    }
                status to """{"type":"$answer","status":$status,"title":"No"}"""
            }
        }
    }

    /** The fields sent to an action, in turn. */
    fun sent(action: String): List<JsonObject> =
        server.bodies
            .filter { it.first.endsWith("/$action") }
            .map { Json.decodeFromString<JsonObject>(it.second) }
}

class PlayerPuzzleTest {
    @get:Rule val server = FakeServer()

    private fun api() = PuzzleQuestApi(server.url, InMemoryTokenStore("kept"))

    private suspend fun read(json: String): PlayerPuzzle {
        server.respond(200, json)
        return api().playerPuzzle("harbor", "quay")!!
    }

    @Test
    fun `a puzzle being solved, with its text, hints, and what the next hint costs`() =
        runTest {
            val puzzle = read(playerPuzzleJson(hints = listOf("Look up" to 60), nextHint = 300 to true))
            assertEquals("/api/v1/quests/harbor/puzzles/quay", server.sent().url.encodedPath)
            assertEquals("Harbor walk", puzzle.questDisplayName)
            assertEquals(2, puzzle.stopNumber)
            assertEquals(StopStatus.IN_PROGRESS, puzzle.status)
            assertEquals(PuzzleContent.Text("What has keys?"), puzzle.content)
            assertEquals(listOf(RevealedHint("Look up", 60)), puzzle.hints)
            assertEquals(NextHint(300, givesSolutionAway = true), puzzle.nextHint)
            assertEquals(660_000L, puzzle.clock.totalMs)
            assertFalse(puzzle.questFinished)
        }

    @Test
    fun `before it's activated it shows nothing of the puzzle`() =
        runTest {
            val puzzle = read(playerPuzzleJson(status = "awaitingActivation"))
            assertEquals(StopStatus.AWAITING_ACTIVATION, puzzle.status)
            assertNull(puzzle.content)
        }

    @Test
    fun `media is on the site, at the path the puzzle names`() =
        runTest {
            val puzzle = read(playerPuzzleJson(type = "image", text = "/puzzleData/map.png"))
            assertEquals(PuzzleContent.Image("${server.url}puzzleData/map.png"), puzzle.content)
        }

    @Test
    fun `a puzzle the player can't see is null - not reached, or the quest was reset`() =
        runTest {
            server.respond(404, """{"type":"/problems/not-found","status":404,"title":"Not found"}""")
            assertNull(api().playerPuzzle("harbor", "quay"))
        }

    @Test
    fun `changes are what a player would see happen`() =
        runTest {
            val waiting = read(playerPuzzleJson(status = "awaitingActivation"))
            val started = read(playerPuzzleJson())
            val hinted = read(playerPuzzleJson(hints = listOf("Look up" to 60), paused = true))
            val solved =
                read(
                    playerPuzzleJson(
                        status = "completed",
                        hints = listOf("Look up" to 60),
                        solution = "A piano",
                        questStatus = "completed",
                    ),
                )
            val skipped = read(playerPuzzleJson(status = "completed", skipped = true))
            assertEquals(listOf(PuzzleChange.Started), PuzzleChange.between(waiting, started))
            assertEquals(
                listOf(PuzzleChange.Paused, PuzzleChange.HintRevealed("Look up")),
                PuzzleChange.between(started, hinted),
            )
            assertEquals(
                listOf(PuzzleChange.Resumed, PuzzleChange.Solved("A piano"), PuzzleChange.QuestFinished),
                PuzzleChange.between(hinted, solved),
            )
            assertEquals(listOf(PuzzleChange.Skipped), PuzzleChange.between(started, skipped))
            assertEquals(emptyList<PuzzleChange>(), PuzzleChange.between(started, started))
        }

    @Test
    fun `refusals are told apart by their problem types`() {
        fun error(
            type: String,
            status: Int = 409,
        ) = PuzzleActionError.of(ApiError.Refused(Problem(type, status, "")))
        assertEquals(PuzzleActionError.WRONG_ANSWER, error("/problems/wrong-answer", 422))
        assertEquals(PuzzleActionError.WRONG_CODE, error("/problems/wrong-activation-code", 422))
        assertEquals(PuzzleActionError.PAUSED, error("/problems/quest-paused"))
        assertEquals(PuzzleActionError.TOO_MANY_ATTEMPTS, error("/problems/too-many-requests", 429))
        assertEquals(PuzzleActionError.NO_MORE_HINTS, error("/problems/no-more-hints"))
        assertEquals(PuzzleActionError.CHANGED, error("/problems/wrong-state"))
        assertEquals(PuzzleActionError.CHANGED, error("/problems/not-found", 404))
        assertEquals(PuzzleActionError.TRY_AGAIN, error("/problems/try-again"))
        assertEquals(PuzzleActionError.UNEXPECTED, error("/problems/something-new"))
        assertEquals(PuzzleActionError.UNREACHABLE, PuzzleActionError.of(ApiError.Unreachable))
    }
}

class PuzzleModelTest {
    @get:Rule val server = FakeServer()

    private val sockets = FakeLiveSockets()
    private val clock = TestTimeSource()

    private fun TestScope.model(retryAfterNoAnswer: Duration = 5.seconds) =
        PuzzleModel(
            "harbor",
            "quay",
            PuzzleQuestApi(server.url, InMemoryTokenStore("kept"), io = StandardTestDispatcher(testScheduler)),
            retryAfterNoAnswer = retryAfterNoAnswer,
            timeSource = clock,
        ) { channel, ticket -> LiveUpdateFollower("ws://test", channel, sockets, ticket, wait = { delay(it) }) }

    @Test
    fun `activating sends the code, then fetches the puzzle again, and what changed is reported`() =
        runTest {
            val answers =
                PuzzleServer(server, listOf(playerPuzzleJson(status = "awaitingActivation"), playerPuzzleJson()))
            val puzzle = model()
            puzzle.load()
            assertEquals(
                StopStatus.AWAITING_ACTIVATION,
                puzzle.state.value.puzzle
                    ?.status,
            )

            puzzle.activate("abc123")
            assertEquals(
                "abc123",
                answers
                    .sent("activation")
                    .first()["activationCode"]
                    ?.jsonPrimitive
                    ?.content,
            )
            val state = puzzle.state.value
            assertEquals(StopStatus.IN_PROGRESS, state.puzzle?.status)
            assertNull(state.pending)
            assertNull(state.actionError)
            assertEquals(listOf(PuzzleChange.Started), state.changes)
        }

    @Test
    fun `a wrong code or answer says so, and the puzzle is fetched again anyway`() =
        runTest {
            val answers =
                PuzzleServer(
                    server,
                    listOf(playerPuzzleJson()),
                    mapOf("solution-attempts" to listOf("/problems/wrong-answer", "ok")),
                )
            val puzzle = model()
            puzzle.load()
            assertFalse(puzzle.solve("organ"))
            assertEquals(PuzzleActionError.WRONG_ANSWER, puzzle.state.value.actionError)
            assertEquals(1, puzzle.state.value.actionErrorCount)
            assertEquals(2, answers.count("get"))

            // The next try starts afresh.
            assertTrue(puzzle.solve("piano"))
            assertNull(puzzle.state.value.actionError)
        }

    @Test
    fun `a scanned code is sent once, but another code is sent`() =
        runTest {
            val answers =
                PuzzleServer(
                    server,
                    listOf(playerPuzzleJson(status = "awaitingActivation")),
                    mapOf("activation" to listOf("/problems/wrong-activation-code", "ok")),
                )
            val puzzle = model()
            puzzle.load()

            puzzle.scanned("WRONG")
            puzzle.scanned("WRONG")
            assertEquals(1, answers.count("activation"))
            assertEquals(PuzzleActionError.WRONG_CODE, puzzle.state.value.actionError)

            puzzle.scanned("RIGHT")
            assertEquals(2, answers.count("activation"))
            assertNull(puzzle.state.value.actionError)
        }

    @Test
    fun `a scanned code the server didn't answer is sent again, once the wait is over`() =
        runTest {
            // A server that isn't there.
            val puzzle =
                PuzzleModel(
                    "harbor",
                    "quay",
                    PuzzleQuestApi(nowhere(), InMemoryTokenStore("kept")),
                    retryAfterNoAnswer = 5.seconds,
                    timeSource = clock,
                )
            puzzle.scanned("RIGHT")
            assertEquals(PuzzleActionError.UNREACHABLE, puzzle.state.value.actionError)
            assertEquals(1, puzzle.state.value.actionErrorCount)

            // Too soon: not sent again.
            puzzle.scanned("RIGHT")
            assertEquals(1, puzzle.state.value.actionErrorCount)

            clock += 6.seconds
            puzzle.scanned("RIGHT")
            assertEquals(2, puzzle.state.value.actionErrorCount)
        }

    @Test
    fun `revealing a hint asks the server, and the hint is reported`() =
        runTest {
            val answers =
                PuzzleServer(
                    server,
                    listOf(playerPuzzleJson(nextHint = 60 to false), playerPuzzleJson(hints = listOf("Look up" to 60))),
                )
            val puzzle = model()
            puzzle.load()
            puzzle.revealHint()
            assertEquals(1, answers.count("hints"))
            assertEquals(
                1,
                puzzle.state.value.puzzle
                    ?.hints
                    ?.size,
            )
            assertEquals(listOf(PuzzleChange.HintRevealed("Look up")), puzzle.state.value.changes)
        }

    @Test
    fun `actions name the live-update connection, so their updates aren't sent back`() =
        runTest {
            val answers = PuzzleServer(server, listOf(playerPuzzleJson(status = "awaitingActivation")))
            val puzzle = model()
            backgroundScope.launch { puzzle.followLiveUpdates() }
            runCurrent()
            sockets.current.accept("c1")
            runCurrent()
            assertTrue(puzzle.state.value.isLive)

            puzzle.activate("abc123")
            assertEquals(
                "c1",
                answers
                    .sent("activation")
                    .first()["connectionId"]
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `live updates about the quest fetch the puzzle again`() =
        runTest {
            PuzzleServer(
                server,
                listOf(
                    playerPuzzleJson(),
                    playerPuzzleJson(status = "completed", solution = "A piano", next = "customs"),
                ),
            )
            val puzzle = model()
            backgroundScope.launch { puzzle.followLiveUpdates() }
            runCurrent()
            sockets.current.accept("c1")
            runCurrent()
            assertEquals(0, puzzle.state.value.changeCount)

            sockets.current.deliver(QUEST_UPDATED)
            runCurrent()
            assertEquals(1, puzzle.state.value.changeCount)
            assertEquals(listOf(PuzzleChange.Solved("A piano")), puzzle.state.value.changes)
            assertEquals(
                "customs",
                puzzle.state.value.puzzle
                    ?.next
                    ?.puzzleName,
            )
        }

    @Test
    fun `going to the next stop shows its puzzle, without the last one's message`() =
        runTest {
            PuzzleServer(
                server,
                listOf(
                    playerPuzzleJson(),
                    playerPuzzleJson(status = "completed", solution = "A piano", next = "customs"),
                    playerPuzzleJson("customs", status = "awaitingActivation"),
                ),
                mapOf("solution-attempts" to listOf("/problems/wrong-state")),
            )
            val puzzle = model()
            puzzle.load()
            // A teammate solved it first.
            puzzle.solve("piano")
            assertEquals(PuzzleActionError.CHANGED, puzzle.state.value.actionError)
            assertEquals(
                StopStatus.COMPLETED,
                puzzle.state.value.puzzle
                    ?.status,
            )

            puzzle.goToNextStop()
            val state = puzzle.state.value
            assertEquals("customs", state.puzzleName)
            assertEquals("customs", state.puzzle?.puzzleName)
            assertNull(state.actionError)
            // A new puzzle: nothing changed on screen to announce.
            assertEquals(1, state.changeCount)
        }

    @Test
    fun `a puzzle reset while on screen becomes not available`() =
        runTest {
            PuzzleServer(server, listOf(playerPuzzleJson(), "404"))
            val puzzle = model()
            puzzle.load()
            puzzle.load()
            assertTrue(puzzle.state.value.notAvailable)
            assertNull(puzzle.state.value.puzzle)
        }
}

class PuzzleMarkdownTest {
    private fun blocks(markdown: String) = MarkdownBlock.blocks(markdown, "https://example.com")

    private fun text(text: String) = StyledText.plain(text)

    @Test
    fun `headings, paragraphs, and emphasis`() {
        val read = blocks("# The quay\n\nFind the *red* **door**,\nthen `knock`.")
        assertEquals(2, read.size)
        assertEquals(MarkdownBlock.Heading(1, text("The quay")), read[0])
        val paragraph = (read[1] as MarkdownBlock.Paragraph).text
        // A line break within a paragraph is a space, as on the web.
        assertEquals("Find the red door, then knock.", paragraph.plain)
        assertEquals(StyledText.Run("red", italic = true), paragraph.runs.single { it.text == "red" })
        assertEquals(StyledText.Run("door", bold = true), paragraph.runs.single { it.text == "door" })
        assertEquals(StyledText.Run("knock", code = true), paragraph.runs.single { it.text == "knock" })
    }

    @Test
    fun `lists, quotes, code, and rules`() {
        val read = blocks("3. one\n4. two\n\n- [x] done\n\n> said\n\n```\nlet x\n```\n\n---")
        assertEquals(
            listOf(
                MarkdownBlock.ListBlock(
                    ordered = true,
                    start = 3,
                    items =
                        listOf(
                            listOf(MarkdownBlock.Paragraph(text("one"))),
                            listOf(MarkdownBlock.Paragraph(text("two"))),
                        ),
                ),
                MarkdownBlock.ListBlock(
                    ordered = false,
                    start = 1,
                    items =
                        listOf(
                            listOf(
                                MarkdownBlock.Paragraph(text("☑ ") + text("done")),
                            ),
                        ),
                ),
                MarkdownBlock.Quote(listOf(MarkdownBlock.Paragraph(text("said")))),
                MarkdownBlock.CodeBlock("let x"),
                MarkdownBlock.Rule,
            ),
            read,
        )
    }

    @Test
    fun `tables, with their columns' alignment`() {
        assertEquals(
            listOf(
                MarkdownBlock.Table(
                    MarkdownTable(
                        alignments = listOf(MarkdownTable.Alignment.START, MarkdownTable.Alignment.END),
                        header = listOf(text("Clue"), text("Answer")),
                        rows = listOf(listOf(text("A"), text("1"))),
                    ),
                ),
            ),
            blocks("| Clue | Answer |\n|:--|--:|\n| A | 1 |"),
        )
    }

    @Test
    fun `links and pictures are on the site unless they say otherwise, and scripts aren't links`() {
        val read =
            blocks(
                "![A map](/puzzleData/map.png)\n\nSee [the site](https://example.org) or [this](javascript:alert(1)).",
            )
        assertEquals(
            MarkdownBlock.Images(listOf(MarkdownImage("https://example.com/puzzleData/map.png", "A map"))),
            read.first(),
        )
        val paragraph = (read.last() as MarkdownBlock.Paragraph).text
        assertEquals("https://example.org", paragraph.runs.single { it.text == "the site" }.link)
        assertNull(paragraph.runs.single { it.text == "this" }.link)
    }

    @Test
    fun `HTML shows as plain text`() {
        val read = blocks("<script>alert(1)</script>\n\nA <b>bold</b> claim")
        assertEquals(listOf(MarkdownBlock.Paragraph(text("<script>alert(1)</script>"))), read.take(1))
        assertEquals("A <b>bold</b> claim", (read[1] as MarkdownBlock.Paragraph).text.plain)
    }
}
