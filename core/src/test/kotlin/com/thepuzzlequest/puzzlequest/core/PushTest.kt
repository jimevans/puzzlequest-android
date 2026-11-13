package com.thepuzzlequest.puzzlequest.core

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

class PushTest {
    @get:Rule val server = FakeServer()

    private val preferences = InMemoryPushPreferences()
    private val registrar = PushRegistrar(preferences)
    private var registrations = 0

    private fun api() = PuzzleQuestApi(server.url, InMemoryTokenStore("kept"))

    private fun answer() =
        server.answer { request ->
            if (request == "PUT /api/v1/sessions/current/push-target") registrations++
            204 to ""
        }

    @Test
    fun `a notification's data says what it is and which quest it's about, or it isn't PuzzleQuest's`() {
        assertEquals(
            PuzzleQuestNotification("stopSolved", "harbor", "quay"),
            PuzzleQuestNotification.from(
                mapOf(
                    "category" to "stopSolved",
                    "questName" to "harbor",
                    "puzzleName" to "quay",
                ),
            ),
        )
        assertEquals(
            PuzzleQuestNotification("somethingNew", "harbor"),
            PuzzleQuestNotification.from(mapOf("category" to "somethingNew", "questName" to "harbor")),
        )
        assertNull(PuzzleQuestNotification.from(mapOf("category" to "message")))
        assertNull(PuzzleQuestNotification.from(emptyMap()))
    }

    @Test
    fun `notifications are offered on a quest until Android is asked, or the player says not now there`() {
        registrar.permissionIs(NotificationPermission.NOT_ASKED)
        assertTrue(registrar.offers("harbor", registrar.permission.value, registrar.declined.value))
        registrar.notNow("harbor")
        assertFalse(registrar.offers("harbor", registrar.permission.value, registrar.declined.value))
        assertTrue(registrar.offers("museum", registrar.permission.value, registrar.declined.value))
        // Remembered across launches.
        assertEquals(setOf("harbor"), PushRegistrar(preferences).declined.value)

        registrar.permissionIs(NotificationPermission.ALLOWED)
        assertFalse(registrar.offers("museum", registrar.permission.value, registrar.declined.value))
        registrar.permissionIs(NotificationPermission.REFUSED)
        assertFalse(registrar.offers("museum", registrar.permission.value, registrar.declined.value))
    }

    @Test
    fun `the phone is registered as an Android phone, once notifications are allowed and there's a token`() =
        runTest {
            answer()
            registrar.permissionIs(NotificationPermission.NOT_ASKED)
            registrar.received("phone-token")
            registrar.register(api())
            assertEquals(0, registrations)

            registrar.permissionIs(NotificationPermission.ALLOWED)
            registrar.register(api())
            assertEquals(1, registrations)
            val sent = Json.decodeFromString<JsonObject>(server.bodies.single().second)
            assertEquals("android", sent["platform"]?.jsonPrimitive?.content)
            assertEquals("phone-token", sent["token"]?.jsonPrimitive?.content)
            assertEquals("production", sent["environment"]?.jsonPrimitive?.content)
        }

    @Test
    fun `the phone is registered once for each token and server, and again for a new session`() =
        runTest {
            answer()
            registrar.permissionIs(NotificationPermission.ALLOWED)
            registrar.register(api())
            // No token yet.
            assertEquals(0, registrations)

            registrar.received("first")
            registrar.register(api())
            registrar.register(api())
            assertEquals(1, registrations)

            // A new token, as Firebase sometimes gives.
            registrar.received("second")
            registrar.register(api())
            assertEquals(2, registrations)

            // Signing in again: the new session needs it too.
            registrar.forgetRegistration()
            registrar.register(api())
            assertEquals(3, registrations)
        }

    @Test
    fun `a refusal to register is tried again next time`() =
        runTest {
            server.respond(500, """{"type":"/problems/unexpected","status":500,"title":"Oops"}""")
            server.respond(204, "")
            registrar.permissionIs(NotificationPermission.ALLOWED)
            registrar.received("token")
            registrar.register(api())
            registrar.register(api())
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `a notification about the quest on screen isn't shown, and a tapped one opens its quest`() {
        val harbor = PuzzleQuestNotification("stopSolved", "harbor")
        assertTrue(registrar.shows(harbor))
        registrar.questOnScreen = "harbor"
        assertFalse(registrar.shows(harbor))
        assertTrue(registrar.shows(PuzzleQuestNotification("message", "museum")))

        registrar.opened("museum")
        assertEquals("museum", registrar.questToOpen.value)
        registrar.questOpened()
        assertNull(registrar.questToOpen.value)
    }
}
