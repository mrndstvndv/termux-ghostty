package com.mrndtvndv.term.ui.prefs

import android.content.SharedPreferences
import com.mrndtvndv.term.ui.keyboard.SoftKeyboardState
import com.termux.terminal.compose.TerminalWallpaperConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val HerdrAgentFabOpacityKey = "herdr_agent_fab_opacity"
private const val DefaultHerdrAgentFabOpacity = 0.7f
private const val MinHerdrAgentFabOpacity = 0.25f
private const val DefaultDebugHudEnabled = true
private const val RememberSoftKeyboardStateKey = "remember_soft_keyboard_state"
private const val LastSoftKeyboardStateKey = "last_soft_keyboard_state"
private const val WallpaperUriKey = "terminal_wallpaper_uri"
private const val WallpaperNameKey = "terminal_wallpaper_name"
private const val WallpaperEnabledKey = "terminal_wallpaper_enabled"
private const val WallpaperOpacityKey = "terminal_wallpaper_opacity"
private const val WallpaperScalingKey = "terminal_wallpaper_scaling"
private const val WallpaperIdKey = "terminal_wallpaper_id"

/** Default wallpaper scrim, shared with the terminal library's rendering default. */
const val DefaultWallpaperBackgroundOpacity = TerminalWallpaperConfig.DefaultBackgroundOpacity

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

    private val _wallpaperUri = MutableStateFlow<String?>(null)
    val wallpaperUri: StateFlow<String?> = _wallpaperUri.asStateFlow()

    private val _wallpaperName = MutableStateFlow<String?>(null)
    val wallpaperName: StateFlow<String?> = _wallpaperName.asStateFlow()

    private val _wallpaperEnabled = MutableStateFlow(true)
    val wallpaperEnabled: StateFlow<Boolean> = _wallpaperEnabled.asStateFlow()

    private val _wallpaperBackgroundOpacity = MutableStateFlow(DefaultWallpaperBackgroundOpacity)
    val wallpaperBackgroundOpacity: StateFlow<Float> = _wallpaperBackgroundOpacity.asStateFlow()

    private val _wallpaperScaling = MutableStateFlow("CENTER_CROP")
    val wallpaperScaling: StateFlow<String> = _wallpaperScaling.asStateFlow()

    private val _wallpaperId = MutableStateFlow(0L)
    val wallpaperId: StateFlow<Long> = _wallpaperId.asStateFlow()

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
        _wallpaperUri.value = prefs.getString(WallpaperUriKey, null)
        _wallpaperName.value = prefs.getString(WallpaperNameKey, null)
        _wallpaperEnabled.value = prefs.getBoolean(WallpaperEnabledKey, true)
        _wallpaperBackgroundOpacity.value = prefs.getFloat(
            WallpaperOpacityKey,
            DefaultWallpaperBackgroundOpacity,
        ).coerceIn(0f, 1f)
        _wallpaperScaling.value = prefs.getString(WallpaperScalingKey, "CENTER_CROP") ?: "CENTER_CROP"
        _wallpaperId.value = prefs.getLong(WallpaperIdKey, 0L)
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

    /** Persists a newly picked wallpaper. [id] must change when the pixels change. */
    fun setWallpaper(uri: String, name: String?, id: Long, prefs: SharedPreferences) {
        _wallpaperUri.value = uri
        _wallpaperName.value = name
        _wallpaperId.value = id
        prefs.edit()
            .putString(WallpaperUriKey, uri)
            .putString(WallpaperNameKey, name)
            .putLong(WallpaperIdKey, id)
            .apply()
    }

    fun clearWallpaper(prefs: SharedPreferences) {
        _wallpaperUri.value = null
        _wallpaperName.value = null
        _wallpaperId.value = 0L
        prefs.edit()
            .remove(WallpaperUriKey)
            .remove(WallpaperNameKey)
            .remove(WallpaperIdKey)
            .apply()
    }

    fun setWallpaperEnabled(enabled: Boolean, prefs: SharedPreferences) {
        _wallpaperEnabled.value = enabled
        prefs.edit().putBoolean(WallpaperEnabledKey, enabled).apply()
    }

    fun setWallpaperBackgroundOpacity(opacity: Float, prefs: SharedPreferences) {
        val normalized = opacity.coerceIn(0f, 1f)
        _wallpaperBackgroundOpacity.value = normalized
        prefs.edit().putFloat(WallpaperOpacityKey, normalized).apply()
    }

    fun setWallpaperScaling(scaling: String, prefs: SharedPreferences) {
        _wallpaperScaling.value = scaling
        prefs.edit().putString(WallpaperScalingKey, scaling).apply()
    }
}
