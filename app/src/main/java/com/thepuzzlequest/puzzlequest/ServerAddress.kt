package com.thepuzzlequest.puzzlequest

import android.content.Intent

object ServerAddress {
    /** Debug builds only: the server to use instead, as the UI tests do. */
    const val EXTRA_SERVER_URL = "PQ_SERVER_URL"

    /**
     * The site the app uses: https://thepuzzlequest.com in a release build,
     * and in a debug build a server on the Mac running the emulator (build
     * setting SERVER_URL), or the one the launching intent names.
     */
    fun current(intent: Intent?): String {
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra(EXTRA_SERVER_URL)?.let { return it }
        }
        return BuildConfig.SERVER_URL
    }
}
