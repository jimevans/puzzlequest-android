package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** A message from the puzzle master, as the API sends it. */
fun messageJson(
    text: String,
    minute: Int = 5,
    challenge: Boolean = false,
) = """{"text":"$text","sentAt":"2026-11-11T16:${"%02d".format(minute)}:00.000Z","challenge":$challenge}"""

/** The entry's challenge as the API sends it to a player: the confirmation only once it's answered. */
fun challengeJson(
    message: String = "What lights the harbour?",
    answeredVia: String? = null,
    sentMinute: Int = 10,
): String {
    val answered =
        answeredVia?.let {
            """"answeredAt":"2026-11-11T16:20:00.000Z","answeredVia":"$it","confirmation":"Show this to the bartender""""
        } ?: """"answeredAt":null,"answeredVia":null"""
    return """{"challengeId":"c1","message":"$message","sentAt":"2026-11-11T16:$sentMinute:00.000Z",$answered}"""
}

const val ANSWERED_JSON = """{"confirmation":"Show this to the bartender","answeredAt":"2026-11-11T16:20:00.000Z"}"""

class MessagesReadingTest {
    @get:Rule val server = FakeServer()

    private fun api() = PuzzleQuestApi(server.url, InMemoryTokenStore("kept"))

    @Test
    fun `the puzzle master's messages and challenge are read, and the confirmation only once answered`() =
        runTest {
            server.respond(
                200,
                playerQuestJson(
                    listOf(stopJson("quay")),
                    kind = "team",
                    messages =
                        listOf(
                            messageJson("Welcome!", 1),
                            messageJson("What lights the harbour?", 10, challenge = true),
                        ),
                    challenge = challengeJson(),
                ),
            )
            val quest = api().playerQuest("harbor")!!
            assertEquals("Pat", quest.entry.entrantName)
            assertEquals(listOf("Welcome!", "What lights the harbour?"), quest.entry.messages.map { it.text })
            assertEquals(listOf(false, true), quest.entry.messages.map { it.isChallenge })
            val challenge = quest.entry.challenge!!
            assertEquals("What lights the harbour?", challenge.message)
            assertNull(challenge.answer)
            assertEquals(challenge, quest.entry.waitingChallenge)
            assertTrue(challenge.isSetBy(quest.entry.messages[1]))

            server.respond(
                200,
                playerQuestJson(listOf(stopJson("quay")), challenge = challengeJson(answeredVia = "sms")),
            )
            val after = api().playerQuest("harbor")!!
            val answer = after.entry.challenge?.answer!!
            assertEquals("Show this to the bartender", answer.confirmation)
            assertTrue(answer.byText)
            assertNull(after.entry.waitingChallenge)
        }

    @Test
    fun `a new message, and the challenge being answered, are announced`() {
        val sent = Instant.ofEpochSecond(1000)
        val before =
            PlayerQuest(
                "harbor",
                "Harbor walk",
                QuestEntryProgress(
                    id = "e1",
                    isTeam = false,
                    status = QuestStatus.IN_PROGRESS,
                    statusDescription = "",
                    paused = false,
                    startTime = null,
                    endTime = null,
                    clock = QuestClock(false, null, 0, 0, 0),
                    stops = emptyList(),
                    messages = listOf(PuzzleMasterMessage("Welcome!", sent)),
                    challenge = Challenge("c1", "Riddle?", sent),
                ),
            )
        val after =
            before.copy(
                entry =
                    before.entry.copy(
                        messages = before.entry.messages + PuzzleMasterMessage("Hurry!", sent.plusSeconds(60)),
                        challenge = before.entry.challenge!!.copy(answer = ChallengeAnswer(sent, "Yes")),
                    ),
            )
        assertEquals(
            listOf(QuestChange.MessageReceived("Hurry!", isChallenge = false), QuestChange.ChallengeAnswered),
            QuestChange.between(before, after),
        )
        // A new challenge arriving already answered isn't announced as answered.
        val replaced =
            after.copy(
                entry =
                    after.entry.copy(
                        challenge = Challenge("c2", "Another?", sent.plusSeconds(120), ChallengeAnswer(sent, "Yes")),
                    ),
            )
        assertFalse(QuestChange.ChallengeAnswered in QuestChange.between(before, replaced))
    }
}

class MessagesModelTest {
    @get:Rule val server = FakeServer()

    private val sockets = FakeLiveSockets()

    /** The quest's answers, one for each fetch (the last over and over), and the challenge's answers, in turn. */
    private var quests = mutableListOf<String>()
    private var answers = mutableListOf<Pair<Int, String>>()
    private var fetches = 0

    private fun answer() =
        server.answer { request ->
            when (request) {
                "GET /api/v1/quests/harbor" -> {
                    fetches++
                    200 to if (quests.size > 1) quests.removeAt(0) else quests[0]
                }

                "POST /api/v1/quests/harbor/challenge/answers" -> {
                    answers.removeAt(0)
                }

                "POST /api/v1/quests/harbor/live-tickets" -> {
                    201 to """{"ticket":"t","expiresAt":"2026-11-11T16:00:00.000Z"}"""
                }

                else -> {
                    404 to "{}"
                }
            }
        }

    private fun TestScope.model(seen: MessagesSeen = InMemoryMessagesSeen()) =
        QuestModel(
            "harbor",
            PuzzleQuestApi(server.url, InMemoryTokenStore("kept"), io = StandardTestDispatcher(testScheduler)),
            seen,
        ) { channel, ticket -> LiveUpdateFollower("ws://test", channel, sockets, ticket, wait = { delay(it) }) }

    private fun quest(
        messages: List<String> = emptyList(),
        challenge: String = "null",
    ) = playerQuestJson(listOf(stopJson("quay")), messages = messages, challenge = challenge)

    @Test
    fun `new messages are shown once on this phone, newest first, and put away when dismissed`() =
        runTest {
            answer()
            val messages = listOf(messageJson("Welcome!", 1), messageJson("Hurry!", 2))
            quests = mutableListOf(quest(), quest(messages), quest(messages))
            val seen = InMemoryMessagesSeen()
            val model = model(seen)
            model.load()
            assertNull(model.state.value.messageSheet)

            model.load()
            assertEquals(
                listOf("Hurry!", "Welcome!"),
                model.state.value.messageSheet
                    ?.messages
                    ?.map { it.text },
            )
            assertEquals(
                false,
                model.state.value.messageSheet
                    ?.showsChallenge,
            )
            model.dismissMessages()
            assertNull(model.state.value.messageSheet)

            model.load()
            assertNull(model.state.value.messageSheet)
            // Nor on opening the quest again.
            val again = model(seen)
            again.load()
            assertNull(again.state.value.messageSheet)
        }

    @Test
    fun `a message arriving while others are shown joins them`() =
        runTest {
            answer()
            quests =
                mutableListOf(
                    quest(listOf(messageJson("Welcome!", 1))),
                    quest(listOf(messageJson("Welcome!", 1), messageJson("Hurry!", 2))),
                )
            val model = model()
            model.load()
            model.load()
            assertEquals(
                listOf("Hurry!", "Welcome!"),
                model.state.value.messageSheet
                    ?.messages
                    ?.map { it.text },
            )
        }

    @Test
    fun `a new challenge is shown to answer, and once put away it can be reopened, answered or not`() =
        runTest {
            answer()
            val messages = listOf(messageJson("Riddle?", 10, challenge = true))
            quests =
                mutableListOf(
                    quest(messages, challengeJson("Riddle?")),
                    quest(messages, challengeJson("Riddle?", "app")),
                )
            answers = mutableListOf(200 to ANSWERED_JSON)
            val model = model()
            model.load()
            assertEquals(
                true,
                model.state.value.messageSheet
                    ?.showsChallenge,
            )

            // Answered in the sheet, which stays, showing the confirmation.
            assertTrue(model.answerChallenge("lighthouse"))
            assertEquals(
                true,
                model.state.value.messageSheet
                    ?.showsChallenge,
            )
            assertNotNull(
                model.state.value.quest
                    ?.entry
                    ?.challenge
                    ?.answer,
            )

            model.dismissMessages()
            assertNull(model.state.value.messageSheet)
            model.showChallenge()
            assertEquals(MessageSheet(emptyList(), showsChallenge = true), model.state.value.messageSheet)
        }

    @Test
    fun `an answered challenge can be hidden from the top for good, a waiting one can't, and a new one shows`() =
        runTest {
            answer()
            val messages = listOf(messageJson("Riddle?", 10, challenge = true))
            val waiting = quest(messages, challengeJson("Riddle?"))
            val answered = quest(messages, challengeJson("Riddle?", "app"))
            val another =
                quest(
                    messages + messageJson("Again?", 30, challenge = true),
                    challengeJson("Again?", sentMinute = 30),
                )
            quests = mutableListOf(waiting, answered, answered, another)
            val seen = InMemoryMessagesSeen()
            val model = model(seen)
            model.load()
            model.hideAnsweredChallenge()
            assertEquals(
                "Riddle?",
                model.state.value.challengeAtTop
                    ?.message,
            )

            model.load()
            assertNotNull(
                model.state.value.challengeAtTop
                    ?.answer,
            )
            model.hideAnsweredChallenge()
            assertNull(model.state.value.challengeAtTop)
            // It stays hidden, here and on opening the quest again.
            model.load()
            assertNull(model.state.value.challengeAtTop)
            quests = mutableListOf(answered, another)
            val again = model(seen)
            again.load()
            assertNull(again.state.value.challengeAtTop)

            // The same kept challenge sent again is a new one, so it shows.
            model.load()
            assertEquals(
                "Again?",
                model.state.value.challengeAtTop
                    ?.message,
            )
        }

    @Test
    fun `nothing reopens when there's no challenge`() =
        runTest {
            answer()
            quests = mutableListOf(quest())
            val model = model()
            model.load()
            model.showChallenge()
            assertNull(model.state.value.messageSheet)
        }

    @Test
    fun `a wrong answer to the challenge is explained, and a right one shows the confirmation`() =
        runTest {
            answer()
            val waiting = quest(challenge = challengeJson())
            quests = mutableListOf(waiting, waiting, quest(challenge = challengeJson(answeredVia = "app")))
            answers =
                mutableListOf(
                    422 to """{"type":"/problems/wrong-answer","status":422,"title":"No"}""",
                    200 to ANSWERED_JSON,
                )
            val model = model()
            model.load()

            assertFalse(model.answerChallenge("an organ"))
            assertEquals(ChallengeAnswerError.WRONG_ANSWER, model.state.value.challengeError)
            assertEquals(1, model.state.value.challengeErrorCount)
            assertFalse(model.state.value.answeringChallenge)

            assertTrue(model.answerChallenge("the lighthouse"))
            assertNull(model.state.value.challengeError)
            assertEquals(
                "Show this to the bartender",
                model.state.value.quest
                    ?.entry
                    ?.challenge
                    ?.answer
                    ?.confirmation,
            )
            // The quest is fetched again after each answer.
            assertEquals(3, fetches)
            val sent = server.bodies.filter { it.first == "POST /api/v1/quests/harbor/challenge/answers" }
            assertEquals(2, sent.size)
            assertTrue(sent.last().second.contains("the lighthouse"))
        }

    @Test
    fun `a challenge a teammate answered first is explained, and so are other refusals`() =
        runTest {
            answer()
            quests = mutableListOf(quest())
            listOf(
                (409 to """{"type":"/problems/wrong-state","status":409,"title":"No"}""") to
                    ChallengeAnswerError.CHANGED,
                (403 to """{"type":"/problems/forbidden","status":403,"title":"No"}""") to ChallengeAnswerError.CHANGED,
                (429 to """{"type":"/problems/too-many-requests","status":429,"title":"No"}""") to
                    ChallengeAnswerError.TOO_MANY_ATTEMPTS,
                (500 to """{"type":"/problems/unexpected","status":500,"title":"No"}""") to
                    ChallengeAnswerError.UNEXPECTED,
            ).forEach { (refusal, expected) ->
                answers = mutableListOf(refusal)
                val model = model()
                assertFalse(model.answerChallenge("x"))
                assertEquals(expected, model.state.value.challengeError)
                model.clearChallengeError()
                assertNull(model.state.value.challengeError)
            }
        }

    @Test
    fun `the puzzle master's messages and answered challenges arrive as live updates`() =
        runTest {
            answer()
            val messages = listOf(messageJson("Riddle?", 10, challenge = true))
            quests =
                mutableListOf(
                    quest(),
                    quest(messages, challengeJson("Riddle?")),
                    quest(messages, challengeJson("Riddle?", "sms")),
                )
            val model = model()
            backgroundScope.launch { model.followLiveUpdates() }
            runCurrent()
            sockets.current.accept()
            runCurrent()
            assertTrue(model.state.value.isLive)

            sockets.current.deliver("""{"message":"messageSent","questName":"harbor","entryId":"e1"}""")
            runCurrent()
            assertEquals(1, model.state.value.changeCount)
            assertEquals(listOf(QuestChange.MessageReceived("Riddle?", isChallenge = true)), model.state.value.changes)

            sockets.current.deliver("""{"message":"challengeAnswered","questName":"harbor","entryId":"e1"}""")
            runCurrent()
            assertEquals(2, model.state.value.changeCount)
            assertEquals(listOf(QuestChange.ChallengeAnswered), model.state.value.changes)
            assertEquals(
                true,
                model.state.value.quest
                    ?.entry
                    ?.challenge
                    ?.answer
                    ?.byText,
            )
        }
}
