package com.mrndtvndv.term.ui.workspace

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.ModifierKeyReader
import com.termux.terminal.compose.TerminalCanvas as ComposeTerminalCanvas
import com.termux.terminal.compose.TerminalCanvasConfig
import com.termux.terminal.compose.TerminalBackend
import com.termux.terminal.compose.TerminalImeController
import com.termux.terminal.compose.session.TerminalSessionBackend

/** Default soft-keyboard resize debounce in milliseconds (0 = immediate). */
const val DefaultKeyboardResizeDebounceMillis = 0

/** Upper bound for the soft-keyboard resize debounce in milliseconds. */
const val MaxKeyboardResizeDebounceMillis = 100

/**
 * App integration for the reusable compose terminal library.
 *
 * Preferences, cursor effects, and the Ghostty session adapter stay in this
 * module. Rendering, input, IME, selection, and frame scheduling
 * are provided by [ComposeTerminalCanvas].
 */
@Composable
@Suppress("LongParameterList")
fun TerminalCanvas(
    session: TerminalSession,
    extraKeysController: ExtraKeysController,
    onUploadMedia: () -> Unit,
    onUploadFile: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onBackendCreated: (TerminalSession, TerminalBackend) -> Unit,
    onBackendReleased: (TerminalSession, TerminalBackend) -> Unit,
    isTerminalActive: Boolean,
    imeController: TerminalImeController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("ssh_prefs", Context.MODE_PRIVATE) }
    val resizeDebounceMillis = remember(session) {
        preferences.getInt("keyboard_resize_debounce_ms", DefaultKeyboardResizeDebounceMillis)
            .coerceIn(0, MaxKeyboardResizeDebounceMillis)
            .toLong()
    }
    val backend = rememberTerminalBackend(
        session = session,
        resizeDebounceMillis = resizeDebounceMillis,
        onBackendCreated = onBackendCreated,
        onBackendReleased = onBackendReleased
    )
    val modifierKeys = rememberModifierKeys(extraKeysController)
    var showContextMenu by remember { mutableStateOf(false) }
    val config = rememberTerminalCanvasConfig(
        session = session,
        preferences = preferences,
        onOpenUrl = onOpenUrl,
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
private fun rememberTerminalCanvasConfig(
    session: TerminalSession,
    preferences: SharedPreferences,
    onOpenUrl: (String) -> Unit,
    onOpenContextMenu: (String) -> Unit
): TerminalCanvasConfig {
    val context = LocalContext.current
    val fontSizes = remember(context) { TermuxAppSharedPreferences.getDefaultFontSizes(context) }
    val minimumFontSize = fontSizes.getOrElse(1) { 8 }
    val maximumFontSize = fontSizes.getOrElse(2) { 256 }
    val fontSize = remember(session) {
        mutableIntStateOf(
            preferences.getInt("font_size", fontSizes.firstOrNull() ?: minimumFontSize)
                .coerceIn(minimumFontSize, maximumFontSize)
        )
    }
    val typeface = remember(context) { loadTerminalTypeface(context) }
    val cursorTrail = CursorTrailEffect.fromPref(preferences.getString("cursor_trail_effect", null))
    val cursorEffect = remember(cursorTrail) { cursorTrail.toCursorEffect() }
    val frameRate = VisualEffectFrameRate.fromPref(
        preferences.getString("visual_effect_frame_rate", null)
    )
    val accessibilityEnabled by rememberAccessibilityEnabled(context)
    return createTerminalCanvasConfig(
        TerminalCanvasConfigInput(
            preferences = preferences,
            fontSize = fontSize,
            minimumFontSize = minimumFontSize,
            maximumFontSize = maximumFontSize,
            typeface = typeface,
            cursorEffect = cursorEffect,
            frameRate = frameRate,
            accessibilityEnabled = accessibilityEnabled,
            session = session,
            onOpenUrl = onOpenUrl,
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

@Suppress("LongParameterList")
@Composable
private fun rememberTerminalBackend(
    session: TerminalSession,
    resizeDebounceMillis: Long,
    onBackendCreated: (TerminalSession, TerminalBackend) -> Unit,
    onBackendReleased: (TerminalSession, TerminalBackend) -> Unit
): TerminalSessionBackend {
    val backend = remember(session) {
        TerminalSessionBackend(
            session = session,
            resizeDebounceMillis = resizeDebounceMillis
        )
    }
    DisposableEffect(backend) {
        onBackendCreated(session, backend)
        onDispose {
            // This effect is keyed to the session backend, not the UI controller. A
            // controller can be recreated while this session-scoped backend remains alive.
            backend.release()
            onBackendReleased(session, backend)
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
    val preferences: SharedPreferences,
    val fontSize: androidx.compose.runtime.MutableIntState,
    val minimumFontSize: Int,
    val maximumFontSize: Int,
    val typeface: Typeface,
    val cursorEffect: com.termux.terminal.compose.CursorEffect?,
    val frameRate: VisualEffectFrameRate,
    val accessibilityEnabled: Boolean,
    val session: TerminalSession,
    val onOpenUrl: (String) -> Unit,
    val onMoreSelectionRequest: (String) -> Unit,
    val onCodePoint: (Int, Boolean, Boolean) -> Boolean
)

private fun createTerminalCanvasConfig(input: TerminalCanvasConfigInput): TerminalCanvasConfig =
    TerminalCanvasConfig(
        fontSize = input.fontSize.intValue,
        minimumFontSize = input.minimumFontSize,
        maximumFontSize = input.maximumFontSize,
        typeface = input.typeface,
        cursorEffect = input.cursorEffect,
        preferredFrameRate = input.frameRate.framesPerSecond,
        unconditionalKeyboardOnTap = input.preferences.getBoolean(
            "unconditional_soft_keyboard_on_tap",
            true
        ),
        autoShowKeyboardOnTap = input.preferences.getBoolean(
            "auto_show_soft_keyboard_on_tap",
            true
        ),
        accessibilityEnabled = input.accessibilityEnabled,
        onFontSizeChange = { requestedSize ->
            val nextSize = requestedSize.coerceIn(input.minimumFontSize, input.maximumFontSize)
            input.fontSize.intValue = nextSize
            input.preferences.edit().putInt("font_size", nextSize).apply()
        },
        onOpenUrl = input.onOpenUrl,
        onCopyRequest = input.session::onCopyTextToClipboard,
        onPasteRequest = input.session::onPasteTextFromClipboard,
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


private fun loadTerminalTypeface(context: Context): Typeface {
    val customFontFile = context.getFileStreamPath("font.ttf")
    if (!customFontFile.isFile || customFontFile.length() <= 0L) return Typeface.MONOSPACE
    return try {
        Typeface.createFromFile(customFontFile)
    } catch (_: RuntimeException) {
        Typeface.MONOSPACE
    }
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
