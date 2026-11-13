package com.thepuzzlequest.puzzlequest

import android.content.Intent
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
        if (BuildConfig.DEBUG) intent.getStringExtra(Push.EXTRA_FAKE_TOKEN)?.let { Push.fakeToken = it }
        // Not again when the screen is rebuilt (as on rotating), which keeps
        // the intent it was opened with.
        if (savedInstanceState == null) openTappedQuest(intent)
        enableEdgeToEdge()
        setContent {
            PuzzleQuestTheme {
                RootScreen(app, uiTesting = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_UI_TESTING, false))
            }
        }
    }

    /** A notification tapped while the app is open: its quest opens over whatever was open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openTappedQuest(intent)
    }

    /** Back in the foreground, the notification permission is checked again: it may have changed in the phone's settings. */
    override fun onResume() {
        super.onResume()
        Push.refresh(this)
    }

    /**
     * A tapped notification's quest, named by its data, which Firebase puts
     * on the intent that opens the app (and Push.show on its own).
     */
    private fun openTappedQuest(intent: Intent) {
        intent.getStringExtra(Push.EXTRA_QUEST_NAME)?.let(Push.registrar::opened)
    }

    companion object {
        /** Debug builds only: show the UI tests what they wait on, such as whether live updates are connected. */
        const val EXTRA_UI_TESTING = "PQ_UI_TESTING"
    }
}
