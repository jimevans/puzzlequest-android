package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.channels.Channel
import java.io.IOException

/** Stands in for the websocket: each connection made is kept, for a test to drive. */
class FakeLiveSockets : LiveSocketConnector {
    val opened = mutableListOf<FakeLiveSocket>()

    /** The latest connection. */
    val current: FakeLiveSocket get() = opened.last()

    override fun connect(url: String): LiveSocket = FakeLiveSocket(url).also { opened += it }
}

class FakeLiveSocket(
    val url: String,
) : LiveSocket {
    val sent = mutableListOf<String>()
    var closed = false
        private set
    private val incoming = Channel<String>(Channel.UNLIMITED)

    /** The server sends a message. */
    fun deliver(text: String) {
        incoming.trySend(text)
    }

    /** The server accepts the subscription. */
    fun accept(connectionId: String = "c1") = deliver("""{"message":"connection","connectionId":"$connectionId"}""")

    /** The connection drops. */
    fun drop() {
        incoming.close(IOException("dropped"))
    }

    override suspend fun send(text: String) {
        if (closed) throw IOException("closed")
        sent += text
    }

    override suspend fun receive(): String = incoming.receive()

    override fun close() {
        closed = true
        incoming.close(IOException("closed"))
    }
}

const val QUESTS_CHANGED = """{"message":"questsChanged"}"""
