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
            initializer { AppViewModel { scope -> AppServer.connect(applicationContext, intent, scope) } }
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
        /** Debug builds only: show the UI tests what they wait on, such as whether live updates are connected. */
        const val EXTRA_UI_TESTING = "PQ_UI_TESTING"
    }
}
