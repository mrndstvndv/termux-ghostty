package com.mrndtvndv.term.ui.workspace

import android.content.ClipData
import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mrndtvndv.term.data.prefs.AppSettings
import com.mrndtvndv.term.server.HerdrWorkspaceResolver
import com.mrndtvndv.term.server.TerminalProgress
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.mrndtvndv.term.ui.keyboard.ExtraKeysToolbar
import com.mrndtvndv.term.ui.keyboard.SoftKeyboardState
import com.mrndtvndv.term.ui.keyboard.SoftKeyboardVisibilityTracker
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalImeController
import com.termux.terminal.compose.TerminalImeVisibility
import com.termux.terminal.compose.TerminalWallpaperConfig

class TerminalAppearance(
    val typeface: Typeface,
    val fontSizeRange: IntRange,
    val wallpaper: TerminalWallpaperConfig,
)

class TerminalPaneCallbacks(
    val onUploadMedia: () -> Unit,
    val onUploadFile: () -> Unit,
    val onCommitContent: (ClipData) -> Boolean,
    val onOpenUrl: (String) -> Unit,
    val onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
)

class HerdrBinding(
    val state: HerdrUiState,
    val onRefresh: () -> Unit,
    val onFocusTab: (HerdrWorkspaceResolver.HerdrTabNode) -> Unit,
    val onFocusPane: (HerdrWorkspaceResolver.HerdrPaneNode) -> Unit,
    val onClosePane: (HerdrWorkspaceResolver.HerdrPaneNode) -> Unit,
)

@Composable
fun TerminalPane(
    session: TerminalSession,
    progress: TerminalProgress?,
    settings: AppSettings,
    appearance: TerminalAppearance,
    isActive: Boolean,
    herdr: HerdrBinding?,
    callbacks: TerminalPaneCallbacks,
) {
    val extraKeysController = remember { ExtraKeysController() }
    val imeController = remember(session) { TerminalImeController() }

    SoftKeyboardStateEffects(
        session = session,
        settings = settings,
        imeController = imeController,
        isTerminalActive = isActive,
        onUpdateSettings = callbacks.onUpdateSettings,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TerminalProgressStrip(progress = progress)
        Box(modifier = Modifier.weight(1f)) {
            TerminalCanvas(
                session = session,
                settings = settings,
                typeface = appearance.typeface,
                fontSizeRange = appearance.fontSizeRange,
                onFontSizeChange = { size -> callbacks.onUpdateSettings { it.copy(fontSize = size) } },
                extraKeysController = extraKeysController,
                onUploadMedia = callbacks.onUploadMedia,
                onUploadFile = callbacks.onUploadFile,
                onCommitContent = callbacks.onCommitContent,
                onOpenUrl = callbacks.onOpenUrl,
                isTerminalActive = isActive,
                imeController = imeController,
                wallpaperConfig = appearance.wallpaper,
            )
            if (settings.showKeyboardFab || herdr != null) {
                TerminalFabs(
                    settings = settings,
                    herdr = herdr,
                    imeController = imeController,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
        if (settings.extraKeysEnabled) {
            ExtraKeysToolbar(
                extraKeysController = extraKeysController,
                session = session,
                extraKeysJson = settings.extraKeysJson,
                onToggleKeyboard = imeController::toggle,
            )
        }
    }
}

@Composable
private fun TerminalFabs(
    settings: AppSettings,
    herdr: HerdrBinding?,
    imeController: TerminalImeController,
    modifier: Modifier = Modifier,
) {
    val imeVisible = imeController.visibility == TerminalImeVisibility.VISIBLE
    Column(
        modifier = modifier
            .then(if (!settings.extraKeysEnabled) Modifier.navigationBarsPadding() else Modifier)
            .padding(end = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End
    ) {
        val showKeyboardFab = isKeyboardFabVisible(
            settings.showKeyboardFab,
            settings.hideKeyboardFabWhileTyping,
            imeVisible,
        )
        if (showKeyboardFab) {
            KeyboardToggleFab(
                isKeyboardVisible = imeVisible,
                fabOpacity = settings.herdrAgentFabOpacity,
                onToggle = imeController::toggle
            )
        }
        if (herdr != null) {
            HerdrAgentButton(
                workspaces = herdr.state.workspaces,
                isLoading = herdr.state.isLoading,
                error = herdr.state.error,
                onRefresh = herdr.onRefresh,
                onFocusTab = herdr.onFocusTab,
                onFocusPane = herdr.onFocusPane,
                onClosePane = herdr.onClosePane,
                fabOpacity = settings.herdrAgentFabOpacity
            )
        }
    }
}

@Composable
private fun SoftKeyboardStateEffects(
    session: TerminalSession,
    settings: AppSettings,
    imeController: TerminalImeController,
    isTerminalActive: Boolean,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
) {
    val isLifecycleResumed by rememberLifecycleResumed()
    val imeVisibility = imeController.visibility
    val keyboardVisibilityTracker = remember(session) { SoftKeyboardVisibilityTracker() }
    val currentRememberKeyboardState = rememberUpdatedState(settings.rememberSoftKeyboardState)
    val currentLastSoftKeyboardState = rememberUpdatedState(settings.lastSoftKeyboardState)
    val currentOnUpdateSettings = rememberUpdatedState(onUpdateSettings)

    LaunchedEffect(imeController, imeVisibility, isTerminalActive, isLifecycleResumed) {
        if (imeVisibility == TerminalImeVisibility.UNKNOWN) return@LaunchedEffect
        val visibilityToPersist = keyboardVisibilityTracker.observe(
            isVisible = imeVisibility == TerminalImeVisibility.VISIBLE,
            isTerminalActive = isTerminalActive,
            isLifecycleResumed = isLifecycleResumed
        )
        if (visibilityToPersist != null && currentRememberKeyboardState.value) {
            val state = if (visibilityToPersist) SoftKeyboardState.VISIBLE else SoftKeyboardState.HIDDEN
            currentOnUpdateSettings.value { it.copy(lastSoftKeyboardState = state) }
        }
    }
    LaunchedEffect(session, isTerminalActive, settings.rememberSoftKeyboardState, isLifecycleResumed) {
        if (!settings.rememberSoftKeyboardState) return@LaunchedEffect
        if (!isTerminalActive || !isLifecycleResumed) return@LaunchedEffect
        val stateToRestore = currentLastSoftKeyboardState.value
        if (stateToRestore == SoftKeyboardState.UNKNOWN) return@LaunchedEffect
        withFrameNanos { }
        if (stateToRestore == SoftKeyboardState.VISIBLE) {
            imeController.show()
        } else {
            imeController.hide()
        }
    }
}

@Composable
private fun rememberLifecycleResumed(): State<Boolean> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle
    val isResumed = remember(lifecycleOwner) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isResumed.value = when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> false
                else -> lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return isResumed
}
