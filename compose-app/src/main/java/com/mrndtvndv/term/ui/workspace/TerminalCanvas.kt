package com.mrndtvndv.term.ui.workspace

import android.content.ClipData
import android.content.Context
import android.graphics.Typeface
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mrndtvndv.term.data.prefs.AppSettings
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.ModifierKeyReader
import com.termux.terminal.compose.TerminalCanvas as ComposeTerminalCanvas
import com.termux.terminal.compose.TerminalCanvasConfig
import com.termux.terminal.compose.TerminalImeController
import com.termux.terminal.compose.TerminalWallpaperConfig
import com.termux.terminal.compose.session.TerminalSessionBackend

/**
 * Default soft-keyboard resize debounce in milliseconds.
 *
 * Matches the session backend default: keyboard animations deliver dozens of viewport
 * sizes per second, and reflowing for every frame stalls composition. Bursts coalesce
 * to a trailing apply; idle resizes stay immediate. Zero restores immediate resize.
 */
const val DefaultKeyboardResizeDebounceMillis = 50

/** Upper bound for the soft-keyboard resize debounce in milliseconds. */
const val MaxKeyboardResizeDebounceMillis = 100

/**
 * App integration for the reusable compose terminal library.
 *
 * Settings, cursor effects, and the Ghostty session adapter stay in this
 * module. Rendering, input, IME, selection, and frame scheduling
 * are provided by [ComposeTerminalCanvas].
 */
@Composable
@Suppress("LongParameterList")
fun TerminalCanvas(
    session: TerminalSession,
    settings: AppSettings,
    typeface: Typeface,
    fontSizeRange: IntRange,
    onFontSizeChange: (Int) -> Unit,
    extraKeysController: ExtraKeysController,
    onUploadMedia: () -> Unit,
    onUploadFile: () -> Unit,
    onCommitContent: (ClipData) -> Boolean = { false },
    onOpenUrl: (String) -> Unit,
    isTerminalActive: Boolean,
    imeController: TerminalImeController,
    wallpaperConfig: TerminalWallpaperConfig = TerminalWallpaperConfig(),
    modifier: Modifier = Modifier
) {
    val backend = rememberTerminalBackend(
        session = session,
        resizeDebounceMillis = settings.keyboardResizeDebounceMs.toLong()
    )
    val modifierKeys = rememberModifierKeys(extraKeysController)
    var showContextMenu by remember { mutableStateOf(false) }
    val config = rememberTerminalCanvasConfig(
        session = session,
        settings = settings,
        typeface = typeface,
        fontSizeRange = fontSizeRange,
        onFontSizeChange = onFontSizeChange,
        wallpaper = wallpaperConfig,
        onOpenUrl = onOpenUrl,
        onCommitContent = onCommitContent,
        onOpenContextMenu = {
            showContextMenu = true
        }
    )

    TerminalCanvasSurface(
        backend = backend,
        modifierKeys = modifierKeys,
        config = config,
        imeController = imeController,
        requestFocus = isTerminalActive,
        modifier = modifier
    )

    if (showContextMenu) {
        TerminalContextMenu(
            session = session,
            onOpenUrl = onOpenUrl,
            onUploadMedia = onUploadMedia,
            onUploadFile = onUploadFile,
            onDismiss = {
                showContextMenu = false
            }
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun rememberTerminalCanvasConfig(
    session: TerminalSession,
    settings: AppSettings,
    typeface: Typeface,
    fontSizeRange: IntRange,
    onFontSizeChange: (Int) -> Unit,
    wallpaper: TerminalWallpaperConfig,
    onOpenUrl: (String) -> Unit,
    onCommitContent: (ClipData) -> Boolean,
    onOpenContextMenu: (String) -> Unit
): TerminalCanvasConfig {
    val context = LocalContext.current
    val cursorEffect = remember(settings.cursorTrail) { settings.cursorTrail.toCursorEffect() }
    val accessibilityEnabled by rememberAccessibilityEnabled(context)
    return createTerminalCanvasConfig(
        TerminalCanvasConfigInput(
            settings = settings,
            fontSizeRange = fontSizeRange,
            typeface = typeface,
            cursorEffect = cursorEffect,
            wallpaper = wallpaper,
            onFontSizeChange = onFontSizeChange,
            accessibilityEnabled = accessibilityEnabled,
            session = session,
            onOpenUrl = onOpenUrl,
            onCommitContent = onCommitContent,
            onMoreSelectionRequest = onOpenContextMenu,
            onCodePoint = { codePoint, controlDown, altDown ->
                handleTerminalCodePoint(
                    codePoint = codePoint,
                    controlDown = controlDown,
                    altDown = altDown,
                    session = session,
                    onOpenContextMenu = { onOpenContextMenu("") }
                )
            }
        )
    )
}

@Composable
private fun TerminalCanvasSurface(
    backend: TerminalSessionBackend,
    modifierKeys: ModifierKeyReader,
    config: TerminalCanvasConfig,
    imeController: TerminalImeController,
    requestFocus: Boolean,
    modifier: Modifier
) {
    ComposeTerminalCanvas(
        backend = backend,
        modifierKeys = modifierKeys,
        config = config,
        imeController = imeController,
        requestFocus = requestFocus,
        modifier = modifier.fillMaxSize()
    )
}

@Composable
private fun rememberTerminalBackend(
    session: TerminalSession,
    resizeDebounceMillis: Long
): TerminalSessionBackend {
    val backend = remember(session) {
        TerminalSessionBackend(
            session = session,
            resizeDebounceMillis = resizeDebounceMillis
        )
    }
    DisposableEffect(backend) {
        onDispose {
            // This effect is keyed to the session backend, not the UI controller. A
            // controller can be recreated while this session-scoped backend remains alive.
            backend.release()
        }
    }
    LaunchedEffect(backend, resizeDebounceMillis) {
        backend.setResizeDebounceMillis(resizeDebounceMillis)
    }
    return backend
}


@Composable
private fun rememberModifierKeys(controller: ExtraKeysController): ModifierKeyReader =
    remember(controller) {
        object : ModifierKeyReader {
            override fun readControl(): Boolean = controller.readControl()

            override fun readAlt(): Boolean = controller.readAlt()

            override fun readShift(): Boolean = controller.readShift()

            override fun readFn(): Boolean = controller.readFn()

            override fun clearConsumedModifiers() = controller.clearConsumedModifiers()
        }
    }

@Suppress("LongParameterList")
private data class TerminalCanvasConfigInput(
    val settings: AppSettings,
    val fontSizeRange: IntRange,
    val typeface: Typeface,
    val cursorEffect: com.termux.terminal.compose.CursorEffect?,
    val wallpaper: TerminalWallpaperConfig,
    val onFontSizeChange: (Int) -> Unit,
    val accessibilityEnabled: Boolean,
    val session: TerminalSession,
    val onOpenUrl: (String) -> Unit,
    val onCommitContent: (ClipData) -> Boolean,
    val onMoreSelectionRequest: (String) -> Unit,
    val onCodePoint: (Int, Boolean, Boolean) -> Boolean
)

private fun createTerminalCanvasConfig(input: TerminalCanvasConfigInput): TerminalCanvasConfig =
    TerminalCanvasConfig(
        fontSize = input.settings.fontSize,
        minimumFontSize = input.fontSizeRange.first,
        maximumFontSize = input.fontSizeRange.last,
        typeface = input.typeface,
        cursorEffect = input.cursorEffect,
        wallpaper = input.wallpaper,
        preferredFrameRate = input.settings.visualEffectFrameRate.framesPerSecond,
        unconditionalKeyboardOnTap = input.settings.unconditionalSoftKeyboardOnTap,
        autoShowKeyboardOnTap = input.settings.autoShowKeyboardOnTap,
        accessibilityEnabled = input.accessibilityEnabled,
        onFontSizeChange = input.onFontSizeChange,
        onOpenUrl = input.onOpenUrl,
        onCopyRequest = input.session::onCopyTextToClipboard,
        onPasteRequest = input.session::onPasteTextFromClipboard,
        onCommitContent = input.onCommitContent,
        onMoreSelectionRequest = input.onMoreSelectionRequest,
        onCodePoint = input.onCodePoint
    )

private fun handleTerminalCodePoint(
    codePoint: Int,
    controlDown: Boolean,
    altDown: Boolean,
    session: TerminalSession,
    onOpenContextMenu: () -> Unit
): Boolean {
    if (controlDown && altDown) {
        when (codePoint) {
            'm'.code, 'M'.code -> {
                onOpenContextMenu()
                return true
            }
            'v'.code, 'V'.code -> {
                session.onPasteTextFromClipboard()
                return true
            }
        }
    }
    return false
}


@Composable
private fun rememberAccessibilityEnabled(context: Context): State<Boolean> {
    val manager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    val enabled = remember(manager) { mutableStateOf(manager?.isEnabled == true) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose { }
        val listener = AccessibilityManager.AccessibilityStateChangeListener { isEnabled ->
            enabled.value = isEnabled
        }
        manager.addAccessibilityStateChangeListener(listener)
        onDispose { manager.removeAccessibilityStateChangeListener(listener) }
    }
    return enabled
}
