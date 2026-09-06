package com.mrndtvndv.term.ui.prefs

import android.content.SharedPreferences
import com.mrndtvndv.term.ui.keyboard.SoftKeyboardState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val HerdrAgentFabOpacityKey = "herdr_agent_fab_opacity"
private const val DefaultHerdrAgentFabOpacity = 0.7f
private const val MinHerdrAgentFabOpacity = 0.25f
private const val DefaultDebugHudEnabled = true
private const val RememberSoftKeyboardStateKey = "remember_soft_keyboard_state"
private const val LastSoftKeyboardStateKey = "last_soft_keyboard_state"

class UserPrefs {
    private val _customFontName = MutableStateFlow<String?>(null)
    val customFontName: StateFlow<String?> = _customFontName.asStateFlow()

    private val _useCustomFontForWholeUi = MutableStateFlow(false)
    val useCustomFontForWholeUi: StateFlow<Boolean> = _useCustomFontForWholeUi.asStateFlow()

    private val _nativeLogcatLoggingEnabled = MutableStateFlow(false)
    val nativeLogcatLoggingEnabled: StateFlow<Boolean> = _nativeLogcatLoggingEnabled.asStateFlow()

    private val _debugHudEnabled = MutableStateFlow(DefaultDebugHudEnabled)
    val debugHudEnabled: StateFlow<Boolean> = _debugHudEnabled.asStateFlow()

    private val _hideWorkspaceTabs = MutableStateFlow(false)
    val hideWorkspaceTabs: StateFlow<Boolean> = _hideWorkspaceTabs.asStateFlow()

    private val _rememberSoftKeyboardState = MutableStateFlow(false)
    val rememberSoftKeyboardState: StateFlow<Boolean> = _rememberSoftKeyboardState.asStateFlow()

    private val _lastSoftKeyboardState = MutableStateFlow(SoftKeyboardState.UNKNOWN)
    val lastSoftKeyboardState: StateFlow<SoftKeyboardState> = _lastSoftKeyboardState.asStateFlow()

    private val _showKeyboardFab = MutableStateFlow(false)
    val showKeyboardFab: StateFlow<Boolean> = _showKeyboardFab.asStateFlow()

    private val _hideKeyboardFabWhileTyping = MutableStateFlow(true)
    val hideKeyboardFabWhileTyping: StateFlow<Boolean> = _hideKeyboardFabWhileTyping.asStateFlow()

    private val _herdrAgentFabOpacity = MutableStateFlow(DefaultHerdrAgentFabOpacity)
    val herdrAgentFabOpacity: StateFlow<Float> = _herdrAgentFabOpacity.asStateFlow()

    fun init(prefs: SharedPreferences) {
        _customFontName.value = prefs.getString("custom_font_name", null)
        _useCustomFontForWholeUi.value = prefs.getBoolean("use_custom_font_for_whole_ui", false)
        _nativeLogcatLoggingEnabled.value = prefs.getBoolean("native_logcat_logging_enabled", false)
        _debugHudEnabled.value = prefs.getBoolean("debug_hud_enabled", DefaultDebugHudEnabled)
        _hideWorkspaceTabs.value = prefs.getBoolean("hide_workspace_tabs", false)
        _rememberSoftKeyboardState.value = prefs.getBoolean(RememberSoftKeyboardStateKey, false)
        _lastSoftKeyboardState.value = if (_rememberSoftKeyboardState.value) {
            SoftKeyboardState.fromPreference(prefs.getString(LastSoftKeyboardStateKey, null))
        } else {
            SoftKeyboardState.UNKNOWN
        }
        _showKeyboardFab.value = prefs.getBoolean("show_keyboard_fab", false)
        _hideKeyboardFabWhileTyping.value = prefs.getBoolean("hide_keyboard_fab_while_typing", true)
        _herdrAgentFabOpacity.value = prefs.getFloat(
            HerdrAgentFabOpacityKey,
            DefaultHerdrAgentFabOpacity,
        ).coerceIn(MinHerdrAgentFabOpacity, 1f)
    }

    fun setCustomFontName(name: String?, prefs: SharedPreferences) {
        _customFontName.value = name
        prefs.edit().putString("custom_font_name", name).apply()
    }

    fun setUseCustomFontForWholeUi(enabled: Boolean, prefs: SharedPreferences) {
        _useCustomFontForWholeUi.value = enabled
        prefs.edit().putBoolean("use_custom_font_for_whole_ui", enabled).apply()
    }

    fun setNativeLogcatLoggingEnabled(enabled: Boolean, prefs: SharedPreferences) {
        _nativeLogcatLoggingEnabled.value = enabled
        prefs.edit().putBoolean("native_logcat_logging_enabled", enabled).apply()
    }

    fun setDebugHudEnabled(enabled: Boolean, prefs: SharedPreferences) {
        _debugHudEnabled.value = enabled
        prefs.edit().putBoolean("debug_hud_enabled", enabled).apply()
    }

    fun setHideWorkspaceTabs(enabled: Boolean, prefs: SharedPreferences) {
        _hideWorkspaceTabs.value = enabled
        prefs.edit().putBoolean("hide_workspace_tabs", enabled).apply()
    }

    fun setRememberSoftKeyboardState(enabled: Boolean, prefs: SharedPreferences) {
        _rememberSoftKeyboardState.value = enabled
        if (!enabled) {
            _lastSoftKeyboardState.value = SoftKeyboardState.UNKNOWN
            prefs.edit()
                .putBoolean(RememberSoftKeyboardStateKey, false)
                .remove(LastSoftKeyboardStateKey)
                .apply()
            return
        }
        prefs.edit().putBoolean(RememberSoftKeyboardStateKey, true).apply()
    }

    fun setLastSoftKeyboardVisibility(isVisible: Boolean, prefs: SharedPreferences) {
        if (!_rememberSoftKeyboardState.value) return
        val state = if (isVisible) SoftKeyboardState.VISIBLE else SoftKeyboardState.HIDDEN
        if (_lastSoftKeyboardState.value == state) return
        _lastSoftKeyboardState.value = state
        prefs.edit().putString(LastSoftKeyboardStateKey, state.preferenceValue).apply()
    }

    fun setShowKeyboardFab(enabled: Boolean, prefs: SharedPreferences) {
        _showKeyboardFab.value = enabled
        prefs.edit().putBoolean("show_keyboard_fab", enabled).apply()
    }

    fun setHideKeyboardFabWhileTyping(enabled: Boolean, prefs: SharedPreferences) {
        _hideKeyboardFabWhileTyping.value = enabled
        prefs.edit().putBoolean("hide_keyboard_fab_while_typing", enabled).apply()
    }

    fun setHerdrAgentFabOpacity(opacity: Float, prefs: SharedPreferences) {
        val normalizedOpacity = opacity.coerceIn(MinHerdrAgentFabOpacity, 1f)
        _herdrAgentFabOpacity.value = normalizedOpacity
        prefs.edit().putFloat(HerdrAgentFabOpacityKey, normalizedOpacity).apply()
    }
}
