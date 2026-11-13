package com.thepuzzlequest.puzzlequest.core

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

val alice = SignedInUser(userName = "alice", displayName = "Alice A.", role = Role.Player)

/** A server address nothing listens on. */
fun nowhere(): String = java.net.ServerSocket(0).use { "http://localhost:${it.localPort}/" }
