package com.termux.terminal.compose

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.termux.terminal.compose.internal.applyControlMapping
import com.termux.terminal.compose.TerminalCommand.Key

/**
 * XML/interoperability host for [TerminalCanvas].
 *
 * The host deliberately accepts only the backend-neutral [TerminalBackend]
 * contract. Session construction and frame publication stay in the app's
 * session-adapter layer; this class only translates View-host lifecycle and
 * callbacks into Compose configuration.
 */
@Suppress("TooManyFunctions") // View interoperability and autofill surface are intentionally broad.
class TerminalComposeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr), TerminalInputSink {

    interface Listener {
        fun onOpenUrl(url: String) = Unit

        fun onSelectionChanged(selectedText: String?, selecting: Boolean) = Unit

        fun onSingleTap(event: MotionEvent): Boolean = false

        fun onCopyRequest(selectedText: String) = Unit

        fun onPasteRequest() = Unit

        fun onMoreSelectionRequest(selectedText: String) = Unit

        fun shouldShowMoreSelectionAction(): Boolean = false

        fun onFontSizeChanged(fontSize: Int) = Unit

        fun onKeyDown(event: KeyEvent): Boolean = false

        fun onKeyUp(event: KeyEvent): Boolean = false

        fun onCodePoint(codePoint: Int, controlDown: Boolean, altDown: Boolean): Boolean = false

        fun onImeSessionClosed() = Unit

        fun onDiagnostics(diagnostic: TerminalDiagnostic) = Unit
    }

    companion object {
        const val TERMINAL_CURSOR_BLINK_RATE_MIN = 100
        const val TERMINAL_CURSOR_BLINK_RATE_MAX = 2000
    }

    private var backendState by mutableStateOf<TerminalBackend?>(null)
    private var modifierKeysState by mutableStateOf(ModifierKeyReader.NONE)
    private var fontSizeState by mutableIntStateOf(14)
    private var minimumFontSizeState by mutableIntStateOf(8)
    private var maximumFontSizeState by mutableIntStateOf(256)
    private var typefaceState by mutableStateOf<Typeface?>(Typeface.MONOSPACE)
    private var accessibilityEnabledState by mutableStateOf(false)
    private var unconditionalKeyboardOnTapState by mutableStateOf(true)
    private var selectionResetKeyState by mutableLongStateOf(0L)
    private var requestFocusKeyState by mutableLongStateOf(0L)
    private var requestImeKeyState by mutableLongStateOf(0L)
    private var listenerState by mutableStateOf<Listener?>(null)
    private var selectedTextState by mutableStateOf<String?>(null)
    private var storedSelectedTextState by mutableStateOf<String?>(null)
    private var selectingState by mutableStateOf(false)
    private val accessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    private val accessibilityStateChangeListener =
        AccessibilityManager.AccessibilityStateChangeListener { enabled ->
            setAccessibilityEnabled(enabled)
        }
    private var accessibilityListenerRegistered = false

    @Suppress("MemberVisibilityCanBePrivate")
    var listener: Listener?
        get() = listenerState
        set(value) {
            listenerState = value
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setAccessibilityEnabled(accessibilityManager?.isEnabled == true)
        if (accessibilityManager != null && !accessibilityListenerRegistered) {
            accessibilityManager.addAccessibilityStateChangeListener(accessibilityStateChangeListener)
            accessibilityListenerRegistered = true
        }
        backendState?.refresh()
    }

    override fun onDetachedFromWindow() {
        if (accessibilityManager != null && accessibilityListenerRegistered) {
            accessibilityManager.removeAccessibilityStateChangeListener(accessibilityStateChangeListener)
            accessibilityListenerRegistered = false
        }
        super.onDetachedFromWindow()
    }

    @Composable
    override fun Content() {
        val backend = backendState
        if (backend != null) {
            val moreSelectionCallback = listenerState
                ?.takeIf { it.shouldShowMoreSelectionAction() }
                ?.let { listener ->
                    { text: String ->
                        storedSelectedTextState = text
                        listener.onMoreSelectionRequest(text)
                    }
                }
            TerminalCanvas(
                backend = backend,
                modifierKeys = modifierKeysState,
                config = TerminalCanvasConfig(
                    fontSize = fontSizeState,
                    minimumFontSize = minimumFontSizeState,
                    maximumFontSize = maximumFontSizeState,
                    typeface = typefaceState,
                    unconditionalKeyboardOnTap = unconditionalKeyboardOnTapState,
                    accessibilityEnabled = accessibilityEnabledState,
                    selectionResetKey = selectionResetKeyState,
                    onFontSizeChange = { next ->
                        fontSizeState = next
                        listenerState?.onFontSizeChanged(next)
                    },
                    onSingleTap = { event -> listenerState?.onSingleTap(event) == true },
                    onOpenUrl = { url -> listenerState?.onOpenUrl(url) },
                    onSelectionChanged = { info ->
                        selectingState = info != null
                        selectedTextState = info?.let { backend.selectedText(it.selection) }
                        listenerState?.onSelectionChanged(selectedTextState, info != null)
                    },
                    onCopyRequest = { text -> listenerState?.onCopyRequest(text) },
                    onPasteRequest = { listenerState?.onPasteRequest() },
                    onKeyDown = { event -> listenerState?.onKeyDown(event) == true },
                    onKeyUp = { event -> listenerState?.onKeyUp(event) == true },
                    onCodePoint = { codePoint, controlDown, altDown ->
                        listenerState?.onCodePoint(codePoint, controlDown, altDown) == true
                    },
                    onImeSessionClosed = { listenerState?.onImeSessionClosed() },
                    onMoreSelectionRequest = moreSelectionCallback,
                    onDiagnostics = { diagnostic -> listenerState?.onDiagnostics(diagnostic) }
                ),
                requestFocus = requestFocusKeyState != 0L,
                requestFocusKey = requestFocusKeyState,
                requestImeKey = requestImeKeyState,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    /** Installs or replaces the backend; the canvas owns its lifecycle after this call. */
    fun setBackend(backend: TerminalBackend?) {
        if (backendState === backend) {
            backend?.refresh()
            return
        }
        selectedTextState = null
        storedSelectedTextState = null
        selectingState = false
        backendState = backend
        backend?.refresh()
    }

    fun getBackend(): TerminalBackend? = backendState

    fun setModifierKeyReader(reader: ModifierKeyReader?) {
        modifierKeysState = reader ?: ModifierKeyReader.NONE
    }

    fun setFontSize(fontSize: Int) {
        fontSizeState = fontSize.coerceIn(minimumFontSizeState, maximumFontSizeState)
    }

    fun getFontSize(): Int = fontSizeState

    fun setFontSizeBounds(minimum: Int, maximum: Int) {
        require(minimum >= 1) { "minimum font size must be positive" }
        require(maximum >= minimum) { "maximum font size must be >= minimum" }
        minimumFontSizeState = minimum
        maximumFontSizeState = maximum
        fontSizeState = fontSizeState.coerceIn(minimum, maximum)
    }

    fun setTypeface(typeface: Typeface?) {
        typefaceState = typeface ?: Typeface.MONOSPACE
    }

    fun setAccessibilityEnabled(enabled: Boolean) {
        accessibilityEnabledState = enabled
    }

    fun setUnconditionalKeyboardOnTap(enabled: Boolean) {
        unconditionalKeyboardOnTapState = enabled
    }

    fun requestTerminalFocus() {
        requestFocus()
    }

    /** Requests the Compose-owned platform input session and shows the soft keyboard. */
    fun showSoftKeyboard() {
        requestFocus()
        requestImeKeyState++
    }

    override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val focused = super.requestFocus(direction, previouslyFocusedRect)
        if (focused) requestFocusKeyState++
        return focused
    }

    fun onFrameAvailable() {
        backendState?.refresh()
    }

    fun onScreenUpdated() {
        backendState?.refresh()
    }

    fun getText(): CharSequence = backendState?.currentFrame()?.visibleText().orEmpty()

    fun getStoredSelectedText(): String? = storedSelectedTextState

    fun unsetStoredSelectedText() {
        storedSelectedTextState = null
        selectedTextState = null
    }

    fun isSelectingText(): Boolean = selectingState

    fun getTopRow(): Int = backendState?.currentFrame()?.topRow ?: 0

    fun setTopRow(topRow: Int) {
        backendState?.submit(TerminalCommand.SetViewportTopRow(topRow))
    }

    /** Returns the URL under a View-host pointer event using frame-time geometry. */
    fun getVisibleLinkUrl(event: MotionEvent): String? {
        val backend = backendState ?: return null
        val frame = backend.currentFrame() ?: return null
        val metrics = TerminalMetrics.from(
            fontSizePx = fontSizeState.toFloat(),
            typeface = typefaceState,
            viewportWidthPx = width,
            viewportHeightPx = height
        )
        val row = metrics.yToRow(event.y, frame.topRow)
        val column = metrics.xToColumn(event.x)
        return frame.linkLayout?.findAt(row, column)?.url
    }

    fun setSelectionResetKey(key: Long) {
        selectionResetKeyState = key
    }

    override fun submitKey(keyCode: Int, metaState: Int): Boolean =
        if (listenerState?.onKeyDown(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)) == true) {
            true
        } else {
            submit(
                Key(
                    keyCode = keyCode,
                    metaState = metaState,
                    down = true
                )
            )
        }

    override fun submitCodePoint(codePoint: Int, controlDown: Boolean, alt: Boolean): Boolean {
        if (listenerState?.onCodePoint(codePoint, controlDown, alt) == true) return true
        return submit(
            Key(
                keyCode = 0,
                metaState = if (alt) KeyEvent.META_ALT_ON else 0,
                down = true,
                codePoint = applyControlMapping(codePoint, controlDown)
            )
        )
    }

    override fun submitText(text: String): Boolean = submit(TerminalCommand.Text(text))

    private fun submit(command: TerminalCommand): Boolean =
        backendState?.submit(command) is TerminalCommandResult.Success

    @RequiresApi(Build.VERSION_CODES.O)
    override fun autofill(value: AutofillValue) {
        if (value.isText) submitText(value.textValue.toString())
        resetAutofillState()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getAutofillType(): Int = autofillType

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getAutofillHints(): Array<String> = autofillHints

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getAutofillValue(): AutofillValue = AutofillValue.forText("")

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getImportantForAutofill(): Int = autofillImportance

    fun isAutoFillEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            context.getSystemService(AutofillManager::class.java)?.isEnabled == true
        }.getOrDefault(false)
    }

    @SuppressLint("InlinedApi")
    fun requestAutoFillUsername() {
        requestAutoFill(arrayOf(View.AUTOFILL_HINT_USERNAME))
    }

    @SuppressLint("InlinedApi")
    fun requestAutoFillPassword() {
        requestAutoFill(arrayOf(View.AUTOFILL_HINT_PASSWORD))
    }

    fun requestAutoFill(hints: Array<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || hints.isEmpty()) return
        autofillType = View.AUTOFILL_TYPE_TEXT
        autofillImportance = View.IMPORTANT_FOR_AUTOFILL_YES
        autofillHints = hints
        runCatching { context.getSystemService(AutofillManager::class.java)?.requestAutofill(this) }
    }

    fun cancelRequestAutoFill() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || autofillType == View.AUTOFILL_TYPE_NONE) return
        resetAutofillState()
        runCatching { context.getSystemService(AutofillManager::class.java)?.cancel() }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private var autofillType: Int = View.AUTOFILL_TYPE_NONE

    @RequiresApi(Build.VERSION_CODES.O)
    private var autofillImportance: Int = View.IMPORTANT_FOR_AUTOFILL_NO

    private var autofillHints: Array<String> = emptyArray()

    private fun resetAutofillState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        autofillType = View.AUTOFILL_TYPE_NONE
        autofillImportance = View.IMPORTANT_FOR_AUTOFILL_NO
        autofillHints = emptyArray()
    }
}
