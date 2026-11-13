package com.thepuzzlequest.puzzlequest

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/**
 * The real server started by scripts/start-test-server.sh. The build passes
 * its details from the environment (.test-server/env) as instrumentation
 * arguments (app/build.gradle.kts); without them, tests that need it are
 * skipped.
 */
object TestServer {
    private val arguments get() = InstrumentationRegistry.getArguments()

    /** Skips the test unless the test server is named. */
    fun require() {
        assumeTrue(
            "Load .test-server/env into the environment (see scripts/start-test-server.sh)",
            arguments.getString("PQ_TEST_SERVER_URL") != null,
        )
    }

    val url: String get() = arguments.getString("PQ_TEST_SERVER_URL")!!
    val userName: String get() = arguments.getString("PQ_TEST_USER_NAME")!!
    val password: String get() = arguments.getString("PQ_TEST_PASSWORD")!!
    val displayName: String get() = arguments.getString("PQ_TEST_DISPLAY_NAME") ?: userName
}
