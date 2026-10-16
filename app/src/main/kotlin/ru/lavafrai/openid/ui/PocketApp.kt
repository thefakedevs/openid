package ru.lavafrai.openid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.composables.icons.materialsymbols.rounded.R as Symbols
import ru.lavafrai.openid.R
import ru.lavafrai.openid.models.pin.SavedPinState
import ru.lavafrai.openid.navigation.PocketDestination
import ru.lavafrai.openid.navigation.PocketNavigation
import ru.lavafrai.openid.ui.components.ErrorCard
import ru.lavafrai.openid.ui.components.InfoCard
import ru.lavafrai.openid.ui.components.LoadingStatus
import ru.lavafrai.openid.ui.components.PocketIcon
import ru.lavafrai.openid.ui.unlock.UnlockMenu

@Composable
fun PocketApp(viewModel: PocketViewModel) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.destination) {
        focus.clearFocus()
        keyboard?.hide()
    }
    LaunchedEffect(state.busy) {
        if (state.busy) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    LaunchedEffect(owner, controller) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            controller.state.collect { current ->
                if (current.destination == PocketDestination.UNLOCK && current.autoUnlockPending && !current.busy) {
                    controller.dispatch(PocketAction.AutoUnlock)
                }
            }
        }
    }
    DisposableEffect(owner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) controller.onForeground()
            if (event == Lifecycle.Event.ON_STOP && controller.state.value.authPrompt == null) controller.onBackground()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler(state.hasBack || state.busy) {
        if (!state.busy) controller.dispatch(PocketAction.Back(state.destination))
    }
    PocketContent(state, controller::dispatch)
    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = { controller.dispatch(PocketAction.DismissDelete) },
            icon = {
                PocketIcon(Symbols.drawable.materialsymbols_ic_delete_rounded, tint = MaterialTheme.colorScheme.error)
            },
            title = { Text(stringResource(R.string.delete_profile_title)) },
            text = {
                Text(
                    stringResource(R.string.delete_profile_description),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.dispatch(PocketAction.ConfirmDelete)
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    controller.dispatch(PocketAction.DismissDelete)
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    } else {
        state.authPrompt?.let { request -> SystemAuthentication(request, controller::dispatch) }
    }
}

@Composable
internal fun PocketContent(
    state: PocketUiState,
    dispatch: (PocketAction) -> Unit,
) {
    val savedPinAccountId = (state.savedPin as? SavedPinState.Saved)
        ?.takeIf { it.accountId == state.account?.accountId }?.accountId
    var manualPinEntry by remember(state.destination, savedPinAccountId) {
        mutableStateOf(savedPinAccountId == null)
    }
    val alignUnlockToBottom = state.destination == PocketDestination.UNLOCK && !state.accountMigrationRequired
    val pinEntry = alignUnlockToBottom && manualPinEntry
    val snackbarHostState = remember { SnackbarHostState() }
    val noticeMessage = state.notice?.message?.resolve()
    LaunchedEffect(state.notice, noticeMessage) {
        state.notice?.let { notice ->
            snackbarHostState.showSnackbar(
                message = noticeMessage ?: return@let,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            dispatch(PocketAction.DismissNotice(notice))
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { PocketTopBar(state, manualPinEntry, dispatch) },
        snackbarHost = {
            SnackbarHost(snackbarHostState, modifier = Modifier.widthIn(max = 520.dp).imePadding()) { data ->
                Snackbar(snackbarData = data, shape = RoundedCornerShape(20.dp))
            }
        },
    ) { insets ->
        Box(
            Modifier.fillMaxSize().padding(insets).imePadding(),
            contentAlignment = if (alignUnlockToBottom) Alignment.BottomCenter else Alignment.TopCenter,
        ) {
            // Dispose the previous screen immediately: outgoing QR/PIN content must not linger in an animation.
            key(state.destination) {
                val entry = remember { Animatable(0f) }
                val screenScroll = rememberScrollState()
                LaunchedEffect(state.error) {
                    if (state.error != null) screenScroll.animateScrollTo(0)
                }
                val entrySpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                LaunchedEffect(Unit) { entry.animateTo(1f, entrySpec) }
                Column(
                    modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
                        .then(if (pinEntry) Modifier.fillMaxSize() else Modifier.verticalScroll(screenScroll))
                        .graphicsLayer {
                            alpha = entry.value
                            translationY = (1f - entry.value) * 16.dp.toPx()
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (state.destination !in setOf(PocketDestination.UNLOCK, PocketDestination.QR)) {
                        state.error?.let { error ->
                            ErrorCard(error, { dispatch(PocketAction.RecoverError(error)) }, enabled = !state.busy)
                        }
                    }
                    state.message?.takeUnless { state.destination == PocketDestination.UNLOCK }?.let { message ->
                        InfoCard(
                            message.resolve(),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            icon = Symbols.drawable.materialsymbols_ic_info_rounded,
                        )
                    }
                    if (state.busy && state.destination != PocketDestination.QR && !pinEntry) LoadingStatus(state)
                    PocketNavigation(state, manualPinEntry, { manualPinEntry = it }, dispatch)
                    if (!pinEntry) Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun PocketTopBar(
    state: PocketUiState,
    manualPinEntry: Boolean,
    dispatch: (PocketAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.destination != PocketDestination.QR) {
            if (state.hasBack) {
                IconButton(onClick = { dispatch(PocketAction.Back(state.destination)) }, enabled = !state.busy) {
                    PocketIcon(
                        R.drawable.materialsymbols_ic_arrow_back_rounded,
                        description = stringResource(R.string.action_back),
                    )
                }
            } else {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        PocketIcon(
                            Symbols.drawable.materialsymbols_ic_fingerprint_rounded,
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
        Text(
            stringResource(
                if (state.destination == PocketDestination.SETTINGS) R.string.settings_title else R.string.app_name,
            ),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        when (state.destination) {
            PocketDestination.IMPORT, PocketDestination.SETUP -> Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    stringResource(
                        R.string.import_step,
                        if (state.destination == PocketDestination.IMPORT) 1 else 2,
                        2,
                    ),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            PocketDestination.UNLOCK -> UnlockMenu(
                state = state,
                manualEntry = manualPinEntry,
                onSubmitWithoutSaving = { dispatch(PocketAction.SubmitPinWithoutSaving) },
                onDelete = { dispatch(PocketAction.AskDelete) },
            )

            PocketDestination.QR -> IconButton(onClick = {
                dispatch(PocketAction.OpenSettings)
            }, enabled = !state.busy) {
                PocketIcon(
                    Symbols.drawable.materialsymbols_ic_settings_rounded,
                    description = stringResource(R.string.settings_title),
                )
            }

            else -> Unit
        }
    }
}
