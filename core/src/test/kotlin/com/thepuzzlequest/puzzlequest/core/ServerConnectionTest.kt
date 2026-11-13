package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

val staging = ServerOrigin.parse("https://staging.thepuzzlequest.com")

/** A description such as a server's `/api/v1/openapi.json`, cut down to what the check reads. */
fun apiDescription(
    title: String = "PuzzleQuest API",
    version: String = "1.0.1",
) = """{"openapi":"3.1.0","info":{"title":"$title","version":"$version"},"paths":{}}"""

class ServerCheckTest {
    @get:Rule val server = FakeServer()

    private val origin get() = ServerOrigin.parse(server.url)

    private suspend fun failure(server: ServerOrigin = origin): ServerCheckFailure =
        try {
            ServerCheck().check(server)
            error("the check passed")
        } catch (failed: ServerCheckFailed) {
            failed.reason
        }

    @Test
    fun `PuzzleQuest's API, version 1, is accepted, its description fetched from the server`() =
        runTest {
            server.respond(200, apiDescription())
            ServerCheck().check(origin)
            val sent = server.sent()
            assertEquals("GET /api/v1/openapi.json", "${sent.method} ${sent.url.encodedPath}")
            assertNull(sent.headers["Authorization"])
        }

    @Test
    fun `any version 1 will do`() =
        runTest {
            server.respond(200, apiDescription(version = "1.7.0"))
            ServerCheck().check(origin)
        }

    @Test
    fun `anything else isn't PuzzleQuest`() =
        runTest {
            listOf(
                Triple(200, apiDescription(title = "Pet Store"), "application/json"),
                Triple(404, """{"error":"not found"}""", "application/json"),
                Triple(200, "<html><body>Hello</body></html>", "text/html"),
                Triple(200, """{"info":{}}""", "application/json"),
                Triple(500, "{}", "application/json"),
            ).forEach { (status, body, type) ->
                server.respond(status, body, type)
                assertEquals(body, ServerCheckFailure.NOT_PUZZLE_QUEST, failure())
            }
        }

    @Test
    fun `a later version of the API can't be used`() =
        runTest {
            server.respond(200, apiDescription(version = "2.0.0"))
            assertEquals(ServerCheckFailure.UNSUPPORTED_VERSION, failure())
        }

    @Test
    fun `a server that doesn't answer is unreachable`() =
        runTest {
            assertEquals(ServerCheckFailure.UNREACHABLE, failure(ServerOrigin.parse(nowhere())))
        }
}

/**
 * Each server's own fake: switching between them shows which one the app
 * talks to, and with which token.
 */
abstract class ServersTest {
    @get:Rule val production = FakeServer()

    @get:Rule val stagingServer = FakeServer()

    @get:Rule val computer = FakeServer()

    val setting = InMemorySettingStore()
    val vault = InMemoryTokenVault()

    private fun fakeFor(server: ServerOrigin) =
        when (server) {
            ServerOrigin.PRODUCTION -> production
            staging -> stagingServer
            else -> computer
        }

    fun TestScope.connection(
        policy: ServerAddressPolicy = ServerAddressPolicy.ANY_ADDRESS,
        fixed: ServerOrigin? = null,
    ) = ServerConnection(
        ServerSetting(setting, policy),
        vault,
        policy,
        fixed,
        scope = backgroundScope,
        makeApi = { server, tokens -> PuzzleQuestApi(fakeFor(server).url, tokens) },
    )
}

class ServerConnectionTest : ServersTest() {
    @Test
    fun `every build starts with Production`() =
        runTest {
            assertEquals(ServerOrigin.PRODUCTION, connection(ServerAddressPolicy.ANY_ADDRESS).server)
            assertEquals(ServerOrigin.PRODUCTION, connection(ServerAddressPolicy.PUZZLE_QUEST_DOMAIN).server)
        }

    @Test
    fun `the chosen server is remembered across launches`() =
        runTest {
            connection().switchTo(staging)
            assertEquals(staging, connection().server)
            connection().switchTo(ServerOrigin.PRODUCTION)
            assertEquals(ServerOrigin.PRODUCTION, connection().server)
            // Production is what's chosen when nothing is.
            assertNull(setting.value)
        }

    @Test
    fun `a remembered server the build may not use is ignored`() =
        runTest {
            connection(ServerAddressPolicy.ANY_ADDRESS).switchTo(ServerOrigin.THIS_COMPUTER)
            assertEquals(ServerOrigin.PRODUCTION, connection(ServerAddressPolicy.PUZZLE_QUEST_DOMAIN).server)
        }

    @Test
    fun `resetting the setting goes back to Production`() =
        runTest {
            connection().switchTo(staging)
            ServerSetting(setting, ServerAddressPolicy.ANY_ADDRESS).reset()
            assertEquals(ServerOrigin.PRODUCTION, connection().server)
        }

    @Test
    fun `a fixed server wins over the setting, without changing it, and can't be switched`() =
        runTest {
            connection().switchTo(staging)
            val fixed = connection(fixed = ServerOrigin.THIS_COMPUTER)
            assertEquals(ServerOrigin.THIS_COMPUTER, fixed.server)
            assertTrue(fixed.isFixed)
            fixed.switchTo(ServerOrigin.PRODUCTION)
            assertEquals(ServerOrigin.THIS_COMPUTER, fixed.server)
            assertEquals(staging, connection().server)
        }

    @Test
    fun `the session talks to the server, with that server's token`() =
        runTest {
            vault.tokens(staging).save("staging's")
            vault.tokens(ServerOrigin.PRODUCTION).save("production's")
            val connection = connection()
            connection.switchTo(staging)
            // Switching from Production forgot its token.
            assertNull(vault.tokens(ServerOrigin.PRODUCTION).read())
            stagingServer.respond(200, """{"user":$ALICE_JSON}""")
            connection.session.restore()
            val sent = stagingServer.sent()
            assertEquals("GET /api/v1/sessions/current", "${sent.method} ${sent.url.encodedPath}")
            assertEquals("Bearer staging's", sent.headers["Authorization"])
        }

    @Test
    fun `switching signs out - the old server's token is forgotten, and that server told`() =
        runTest {
            val connection = connection()
            connection.session.restore()
            production.respond(201, """{"user":$ALICE_JSON,"token":"production's"}""")
            connection.session.signIn("alice", "secret")
            production.sent()
            assertEquals("production's", vault.tokens(ServerOrigin.PRODUCTION).read())
            vault.tokens(staging).save("staging's")
            assertTrue(connection.switchingSignsOut)
            val oldSession = connection.session

            production.respond(204, "")
            connection.switchTo(ServerOrigin.THIS_COMPUTER)
            assertEquals(ServerOrigin.THIS_COMPUTER, connection.server)
            assertNotSame(oldSession, connection.session)
            assertEquals(SessionStore.State.Restoring, connection.session.state.value)
            assertNull(vault.tokens(ServerOrigin.PRODUCTION).read())
            // Only that server's.
            assertEquals("staging's", vault.tokens(staging).read())

            connection.farewell!!.join()
            val farewell = production.sent()
            assertEquals("DELETE /api/v1/sessions/current", "${farewell.method} ${farewell.url.encodedPath}")
            assertEquals("Bearer production's", farewell.headers["Authorization"])

            connection.session.restore()
            assertEquals(SessionStore.State.SignedOut, connection.session.state.value)
            assertFalse(connection.switchingSignsOut)
            assertEquals(0, computer.requestCount)
        }

    @Test
    fun `switching while signed out tells no server anything`() =
        runTest {
            val connection = connection()
            connection.session.restore()
            assertFalse(connection.switchingSignsOut)
            connection.switchTo(staging)
            assertNull(connection.farewell)
            assertEquals(0, production.requestCount)
        }

    @Test
    fun `switching to the same server does nothing`() =
        runTest {
            vault.tokens(ServerOrigin.PRODUCTION).save("kept")
            val connection = connection()
            val session = connection.session
            connection.switchTo(ServerOrigin.PRODUCTION)
            assertSame(session, connection.session)
            assertEquals("kept", vault.tokens(ServerOrigin.PRODUCTION).read())
            assertEquals(0, production.requestCount)
        }
}

class OptionsModelTest : ServersTest() {
    /** Where custom servers are checked: answers with an API description unless told otherwise. */
    @get:Rule val checked = FakeServer()

    /** The checked server's address. */
    private val custom get() = ServerOrigin.parse(checked.url)

    private fun TestScope.model(
        policy: ServerAddressPolicy = ServerAddressPolicy.ANY_ADDRESS,
        offersThisComputer: Boolean = true,
    ): Pair<OptionsModel, ServerConnection> {
        val connection = connection(policy)
        return OptionsModel(connection, offersThisComputer) to connection
    }

    @Test
    fun `it starts with the server in use chosen`() =
        runTest {
            assertEquals(
                ServerChoice.PRODUCTION,
                model()
                    .first.state.value.choice,
            )
            connection().switchTo(staging)
            val (model, _) = model()
            assertEquals(ServerChoice.CUSTOM, model.state.value.choice)
            assertEquals("https://staging.thepuzzlequest.com", model.state.value.customAddress)
        }

    @Test
    fun `signed out, choosing This computer switches at once, without checking it`() =
        runTest {
            val (model, connection) = model()
            model.choose(ServerChoice.THIS_COMPUTER)
            assertEquals(ServerOrigin.THIS_COMPUTER, connection.server)
            assertEquals(ServerChoice.THIS_COMPUTER, model.state.value.choice)
            assertNull(model.state.value.pendingSwitch)
            assertEquals(0, computer.requestCount)
        }

    @Test
    fun `choosing Custom waits for an address`() =
        runTest {
            val (model, connection) = model()
            model.choose(ServerChoice.CUSTOM)
            assertEquals(ServerChoice.CUSTOM, model.state.value.choice)
            assertEquals(ServerOrigin.PRODUCTION, connection.server)
            assertFalse(model.state.value.canUseCustomAddress)
        }

    @Test
    fun `a custom address is checked, then used`() =
        runTest {
            checked.respond(200, apiDescription())
            val (model, connection) = model()
            model.choose(ServerChoice.CUSTOM)
            // As typed, in capitals with a final slash.
            model.editCustomAddress(" ${checked.url.uppercase()} ")
            model.useCustomAddress()
            assertNull(model.state.value.failure)
            assertEquals(custom, connection.server)
            assertEquals(custom.url, model.state.value.customAddress)
            assertEquals("/api/v1/openapi.json", checked.sent().url.encodedPath)
        }

    @Test
    fun `an address the build may not use is refused without asking it anything`() =
        runTest {
            val (model, connection) = model(ServerAddressPolicy.PUZZLE_QUEST_DOMAIN, offersThisComputer = false)
            model.choose(ServerChoice.CUSTOM)
            model.editCustomAddress(checked.url)
            model.useCustomAddress()
            assertEquals(
                CustomServerFailure.Refused(ServerAddressRefusal.NOT_SECURE),
                model.state.value.failure,
            )
            assertEquals(ServerOrigin.PRODUCTION, connection.server)
            assertEquals(0, checked.requestCount)
        }

    @Test
    fun `a server that isn't PuzzleQuest's is refused, and can be tried again`() =
        runTest {
            checked.respond(404, "{}")
            val (model, connection) = model()
            model.choose(ServerChoice.CUSTOM)
            model.editCustomAddress(checked.url)
            model.useCustomAddress()
            assertEquals(
                CustomServerFailure.CheckFailed(ServerCheckFailure.NOT_PUZZLE_QUEST),
                model.state.value.failure,
            )
            assertEquals(ServerOrigin.PRODUCTION, connection.server)
            assertTrue(model.state.value.canUseCustomAddress)
            assertFalse(model.state.value.isChecking)
        }

    @Test
    fun `a server that can't be reached is refused`() =
        runTest {
            val (model, connection) = model()
            model.choose(ServerChoice.CUSTOM)
            model.editCustomAddress(nowhere())
            model.useCustomAddress()
            assertEquals(
                CustomServerFailure.CheckFailed(ServerCheckFailure.UNREACHABLE),
                model.state.value.failure,
            )
            assertEquals(ServerOrigin.PRODUCTION, connection.server)
        }

    @Test
    fun `typing the server in use does nothing`() =
        runTest {
            val (model, connection) = model()
            model.choose(ServerChoice.CUSTOM)
            model.editCustomAddress("thepuzzlequest.com")
            val session = connection.session
            model.useCustomAddress()
            assertSame(session, connection.session)
            assertNull(model.state.value.failure)
        }

    @Test
    fun `signed in, switching waits for confirmation`() =
        runTest {
            vault.tokens(ServerOrigin.PRODUCTION).save("kept")
            production.respond(204, "")
            val (model, connection) = model()
            model.choose(ServerChoice.THIS_COMPUTER)
            assertEquals(ServerOrigin.THIS_COMPUTER, model.state.value.pendingSwitch)
            assertEquals(ServerOrigin.PRODUCTION, connection.server)
            // The server in use stays selected until the switch.
            assertEquals(ServerChoice.PRODUCTION, model.state.value.choice)
            model.confirmSwitch(ServerOrigin.THIS_COMPUTER)
            assertEquals(ServerChoice.THIS_COMPUTER, model.state.value.choice)
            assertNull(model.state.value.pendingSwitch)
            assertEquals(ServerOrigin.THIS_COMPUTER, connection.server)
            assertNull(vault.tokens(ServerOrigin.PRODUCTION).read())
        }

    @Test
    fun `signed in, not confirming changes nothing`() =
        runTest {
            vault.tokens(ServerOrigin.PRODUCTION).save("kept")
            val (model, connection) = model()
            model.choose(ServerChoice.THIS_COMPUTER)
            model.cancelSwitch()
            assertNull(model.state.value.pendingSwitch)
            assertEquals(ServerChoice.PRODUCTION, model.state.value.choice)
            assertEquals(ServerOrigin.PRODUCTION, connection.server)
            assertEquals("kept", vault.tokens(ServerOrigin.PRODUCTION).read())
        }

    @Test
    fun `release builds don't offer This computer`() =
        runTest {
            val (model, connection) = model(ServerAddressPolicy.PUZZLE_QUEST_DOMAIN, offersThisComputer = false)
            model.choose(ServerChoice.THIS_COMPUTER)
            assertEquals(ServerOrigin.PRODUCTION, connection.server)
            assertEquals(ServerChoice.PRODUCTION, model.state.value.choice)
        }
}
