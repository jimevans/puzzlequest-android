package com.thepuzzlequest.puzzlequest.core

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.rules.ExternalResource

/**
 * A local HTTP server answering with the responses each test queues, and
 * recording what was sent: the network, without the network.
 */
class FakeServer : ExternalResource() {
    private val server = MockWebServer()

    val url: String get() = server.url("/").toString()

    override fun before() = server.start()

    override fun after() = server.close()

    fun respond(
        status: Int,
        json: String,
        contentType: String = "application/json",
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(status)
            .addHeader("Content-Type", contentType)
            .body(json)
            .build(),
    )

    /**
     * Answers every request with what [answer] says, from its method and path
     * (such as "GET /api/v1/users/alice/quests"), instead of queued responses.
     */
    fun answer(answer: (request: String) -> Pair<Int, String>) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val (status, json) = answer("${request.method} ${request.url.encodedPath}")
                    return MockResponse
                        .Builder()
                        .code(status)
                        .addHeader("Content-Type", "application/json")
                        .body(json)
                        .build()
                }
            }
    }

    /** What was sent next, waiting briefly for it. */
    fun sent(): RecordedRequest =
        checkNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) {
            "nothing was sent"
        }

    val requestCount: Int get() = server.requestCount
}

const val ALICE_JSON =
    """{"id":"u1","userName":"alice","displayName":"Alice A.","email":"alice@example.com",""" +
        """"emailVerified":true,"phone":"","sms":false,"role":"player"}"""

val alice = SignedInUser(userName = "alice", displayName = "Alice A.", role = Role.Player, emailVerified = true)

/** A server address nothing listens on. */
fun nowhere(): String = java.net.ServerSocket(0).use { "http://localhost:${it.localPort}/" }

/** A quest summary as the API sends it. */
fun questJson(
    questName: String,
    status: String = "inProgress",
    paused: Boolean = false,
    running: Boolean = true,
    clockStart: String? = "2026-11-11T16:00:00.000Z",
    elapsedMs: Long = 60_000,
) = """{"questName":"$questName","displayName":"Quest $questName","status":"$status",""" +
    """"statusDescription":"In progress","paused":$paused,"progress":{"startTime":"2026-11-11T16:00:00.000Z",""" +
    """"endTime":null,"running":$running,"clockStart":${clockStart?.let { "\"$it\"" } ?: "null"},""" +
    """"elapsedMs":$elapsedMs,"penaltyMs":0,"totalMs":$elapsedMs}}"""

/** A page of the user's quests. */
fun questPage(
    vararg quests: String,
    next: String? = null,
) = """{"items":[${quests.joinToString(",")}],"next":${next?.let { "\"$it\"" } ?: "null"}}"""
