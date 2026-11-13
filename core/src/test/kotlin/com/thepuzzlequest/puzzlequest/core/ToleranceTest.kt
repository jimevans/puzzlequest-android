package com.thepuzzlequest.puzzlequest.core

import com.thepuzzlequest.puzzlequest.api.infrastructure.Serializer
import com.thepuzzlequest.puzzlequest.api.models.Clock
import com.thepuzzlequest.puzzlequest.api.models.QuestStatus
import com.thepuzzlequest.puzzlequest.api.models.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * Version 1 of the API only grows (decision 0023 in the server repository):
 * the generated client must read responses with things it doesn't know.
 */
class ToleranceTest {
    private val json = Serializer.kotlinxSerializationJson

    @Test
    fun `a field the app doesn't know is ignored, and an unknown role is Other`() {
        val user =
            json.decodeFromString<User>(
                """{"id":"u1","userName":"alice","displayName":"Alice A.","email":"a@example.com",""" +
                    """"emailVerified":true,"phone":"","sms":false,"role":"superuser","favouriteColour":"teal"}""",
            )
        assertEquals(SignedInUser("alice", "Alice A.", Role.Other), SignedInUser(user))
    }

    @Test
    fun `each known role is read as itself`() {
        for ((name, role) in listOf("player" to Role.Player, "staff" to Role.Staff, "admin" to Role.Admin)) {
            val user = json.decodeFromString<User>(ALICE_JSON.replace("\"player\"", "\"$name\""))
            assertEquals(role, SignedInUser(user).role)
        }
    }

    @Test
    fun `an unknown status decodes as unknown`() {
        assertEquals(QuestStatus.unknown_default_open_api, json.decodeFromString<QuestStatus>("\"abandoned\""))
        assertEquals(QuestStatus.inProgress, json.decodeFromString<QuestStatus>("\"inProgress\""))
    }

    @Test
    fun `a value that isn't set (null) is null, times may have milliseconds, and clocks run past 24 days`() {
        val clock =
            json.decodeFromString<Clock>(
                """{"startTime":"2026-11-10T12:05:34.120Z","endTime":null,"running":true,""" +
                    """"clockStart":"2026-11-10T12:00:00Z","elapsedMs":2500000000,"penaltyMs":0,"totalMs":2500000000}""",
            )
        assertEquals(Instant.parse("2026-11-10T12:05:34.120Z"), clock.startTime?.toInstant())
        assertNull(clock.endTime)
        assertEquals(Instant.parse("2026-11-10T12:00:00Z"), clock.clockStart?.toInstant())
        assertEquals(2_500_000_000L, clock.elapsedMs)
    }
}
