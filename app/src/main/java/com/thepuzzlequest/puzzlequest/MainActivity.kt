package com.thepuzzlequest.puzzlequest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.thepuzzlequest.puzzlequest.ui.RootScreen
import com.thepuzzlequest.puzzlequest.ui.theme.PuzzleQuestTheme

class MainActivity : ComponentActivity() {
    // `by viewModels` gives this activity the same AppViewModel back when it's
    // recreated (as on rotating), and a new one on a fresh launch.
    private val app: AppViewModel by viewModels {
        viewModelFactory {
            initializer {
                val tokens = KeystoreTokenStore(applicationContext)
                // The UI tests start signed out, against their own server.
                if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_SIGNED_OUT, false)) {
                    tokens.delete()
                }
                AppViewModel(ServerAddress.current(intent), tokens)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PuzzleQuestTheme {
                RootScreen(app, uiTesting = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_UI_TESTING, false))
            }
        }
    }

    companion object {
        /** Debug builds only: forget any session before starting. */
        const val EXTRA_SIGNED_OUT = "PQ_SIGNED_OUT"

        /** Debug builds only: show the UI tests what they wait on, such as whether live updates are connected. */
        const val EXTRA_UI_TESTING = "PQ_UI_TESTING"
    }
}
