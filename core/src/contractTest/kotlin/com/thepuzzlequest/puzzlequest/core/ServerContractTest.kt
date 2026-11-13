package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.URI

/** Choosing a server, against the real test server. */
class ServerContractTest {
    @Before
    fun needsTheTestServer() {
        assumeTrue("Set PQ_TEST_SERVER_URL (see scripts/start-test-server.sh)", TestSite.url != null)
    }

    @Test
    fun `the check before switching accepts a real PuzzleQuest server`() =
        runTest {
            ServerCheck().check(ServerAddressPolicy.ANY_ADDRESS.check(TestSite.url!!))
        }

    @Test
    fun `the check finds nothing where nothing listens`() =
        runTest {
            // The test server's host, on a port nothing listens on.
            val server = ServerOrigin.parse("http://${URI(TestSite.url!!).host}:9")
            val failure =
                try {
                    ServerCheck().check(server)
                    null
                } catch (failed: ServerCheckFailed) {
                    failed.reason
                }
            assertEquals(ServerCheckFailure.UNREACHABLE, failure)
        }
}
