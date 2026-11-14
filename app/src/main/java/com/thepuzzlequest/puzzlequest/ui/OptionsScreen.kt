package com.thepuzzlequest.puzzlequest.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thepuzzlequest.puzzlequest.BuildConfig
import com.thepuzzlequest.puzzlequest.R
import com.thepuzzlequest.puzzlequest.core.CustomServerFailure
import com.thepuzzlequest.puzzlequest.core.OptionsModel
import com.thepuzzlequest.puzzlequest.core.ServerAddressPolicy
import com.thepuzzlequest.puzzlequest.core.ServerAddressRefusal
import com.thepuzzlequest.puzzlequest.core.ServerCheckFailure
import com.thepuzzlequest.puzzlequest.core.ServerChoice
import com.thepuzzlequest.puzzlequest.core.ServerOrigin

/**
 * Options, over whichever screen opened it: which PuzzleQuest server the app
 * uses. Choosing another one signs out, after asking, if someone is signed
 * in. Once it switches, every screen is rebuilt for the new server (see
 * RootScreen), which closes this.
 *
 * It's Material's full-screen dialog: a window of its own over the app,
 * closed with its X or Back, so the screen beneath keeps its place.
 *
 * @param server the server in use.
 * @param isFixed whether the server is set for this run (a debug build's
 *   PQ_SERVER_URL), so it can't be chosen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsScreen(
    model: OptionsModel,
    server: ServerOrigin,
    isFixed: Boolean,
    onUseCustomAddress: () -> Unit,
    onClose: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    // The whole screen, drawn under the status and navigation bars as the
    // app's own screens are (the Scaffold keeps its content clear of them),
    // rather than leaving them dimmed.
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DialogBarIcons()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.options)) },
                    navigationIcon = {
                        IconButton(onClick = onClose, modifier = Modifier.testTag("options.close")) {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.close),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .imePadding()
                        .verticalScroll(rememberScrollState()),
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.server_in_use)) },
                    supportingContent = { Text(server.displayName) },
                    modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("options.current"),
                )
                SectionHeading(stringResource(R.string.server))
                val canChoose = !isFixed && !state.isChecking
                // A group of radio buttons: TalkBack reads each row as one,
                // with whether it's selected, and as one of the group.
                Column(Modifier.selectableGroup()) {
                    ChoiceRow(
                        state.choice == ServerChoice.PRODUCTION,
                        stringResource(R.string.production),
                        ServerOrigin.PRODUCTION.displayName,
                        "production",
                        canChoose,
                    ) { model.choose(ServerChoice.PRODUCTION) }
                    if (model.offersThisComputer) {
                        ChoiceRow(
                            state.choice == ServerChoice.THIS_COMPUTER,
                            stringResource(R.string.this_computer),
                            ServerOrigin.THIS_COMPUTER.displayName,
                            "this-computer",
                            canChoose,
                        ) { model.choose(ServerChoice.THIS_COMPUTER) }
                    }
                    ChoiceRow(
                        state.choice == ServerChoice.CUSTOM,
                        stringResource(R.string.custom),
                        stringResource(R.string.another_server),
                        "custom",
                        canChoose,
                    ) { model.choose(ServerChoice.CUSTOM) }
                }
                if (BuildConfig.DEBUG && isFixed) {
                    Note(stringResource(R.string.server_fixed))
                }
                if (state.choice == ServerChoice.CUSTOM) {
                    CustomServer(model, state, isFixed, onUseCustomAddress)
                }
            }
        }
        state.pendingSwitch?.let { newServer ->
            AlertDialog(
                // Dismissing it (with Back, or by tapping outside it) is the same as Cancel.
                onDismissRequest = model::cancelSwitch,
                title = { Text(stringResource(R.string.switch_servers)) },
                text = {
                    Text(
                        stringResource(R.string.switch_servers_message, newServer.displayName, server.displayName),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { model.confirmSwitch(newServer) },
                        modifier = Modifier.testTag("options.confirm-switch"),
                    ) { Text(stringResource(R.string.switch_and_sign_out)) }
                },
                dismissButton = {
                    TextButton(onClick = model::cancelSwitch, modifier = Modifier.testTag("options.cancel-switch")) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

/**
 * A dialog is a window of its own, which `enableEdgeToEdge()` in MainActivity
 * didn't set up: this gives its status and navigation bars dark icons in
 * light mode and light ones in dark mode, as the app's own window has.
 * SideEffect runs after each time this is drawn, to keep the window in step.
 */
@Composable
fun DialogBarIcons() {
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

/** The gear that opens Options, for screens without the account menu. */
@Composable
fun OptionsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.options))
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * One server to choose. The whole row is the control (`selectable`, with the
 * radio button's role), so it can be tapped anywhere; the radio button
 * itself only shows the choice.
 */
@Composable
private fun ChoiceRow(
    selected: Boolean,
    title: String,
    detail: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        modifier =
            Modifier
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .testTag("options.server.$tag"),
    )
}

/** Typing another server's address, which is checked before it's used. */
@Composable
private fun CustomServer(
    model: OptionsModel,
    state: OptionsModel.State,
    isFixed: Boolean,
    onUseCustomAddress: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    // Choosing Custom puts the cursor in the address, unless an address is
    // already there (the server in use).
    LaunchedEffect(Unit) { if (state.customAddress.isEmpty() && !isFixed) focus.requestFocus() }
    val canUse = state.canUseCustomAddress && !isFixed
    // Using it closes the keyboard, as signing in does, so the result (or why
    // it was refused) can be seen.
    val focusManager = LocalFocusManager.current
    val use = {
        if (canUse) {
            focusManager.clearFocus()
            onUseCustomAddress()
        }
    }
    SectionHeading(stringResource(R.string.custom_server))
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.customAddress,
            onValueChange = model::editCustomAddress,
            label = { Text(stringResource(R.string.server_address)) },
            placeholder = { Text("https://staging.thepuzzlequest.com") },
            singleLine = true,
            enabled = !isFixed,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
            keyboardActions = KeyboardActions(onGo = { use() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("options.custom-address"),
        )
        val checking = stringResource(R.string.checking_server)
        Button(onClick = use, enabled = canUse, modifier = Modifier.testTag("options.use-custom")) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.use_this_server))
                if (state.isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).semantics { contentDescription = checking },
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                }
            }
        }
        val failure = state.failure
        if (failure != null) {
            ErrorMessage(stringResource(failure.message), Modifier.testTag("options.error"))
        } else {
            Text(
                stringResource(
                    if (model.policy == ServerAddressPolicy.PUZZLE_QUEST_DOMAIN) {
                        R.string.only_puzzlequest_servers
                    } else {
                        R.string.server_is_checked
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What the person is told. */
private val CustomServerFailure.message: Int
    get() =
        when (this) {
            is CustomServerFailure.Refused -> {
                when (reason) {
                    ServerAddressRefusal.NOT_AN_ADDRESS -> R.string.server_not_an_address
                    ServerAddressRefusal.NOT_A_WEB_ADDRESS -> R.string.server_not_a_web_address
                    ServerAddressRefusal.HAS_USER_INFO -> R.string.server_has_user_info
                    ServerAddressRefusal.NOT_JUST_THE_SERVER -> R.string.server_not_just_the_server
                    ServerAddressRefusal.NOT_SECURE -> R.string.server_not_secure
                    ServerAddressRefusal.HAS_PORT -> R.string.server_has_port
                    ServerAddressRefusal.NOT_PUZZLE_QUEST_SERVER -> R.string.only_puzzlequest_servers
                }
            }

            is CustomServerFailure.CheckFailed -> {
                when (reason) {
                    ServerCheckFailure.UNREACHABLE -> R.string.server_unreachable
                    ServerCheckFailure.NOT_PUZZLE_QUEST -> R.string.server_not_puzzlequest
                    ServerCheckFailure.UNSUPPORTED_VERSION -> R.string.server_unsupported_version
                }
            }
        }
