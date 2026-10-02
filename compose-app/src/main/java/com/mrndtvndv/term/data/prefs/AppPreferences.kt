package com.mrndtvndv.term.data.prefs

import android.content.SharedPreferences
import com.mrndtvndv.term.ui.keyboard.SoftKeyboardState
import com.mrndtvndv.term.ui.workspace.CursorTrailEffect
import com.mrndtvndv.term.ui.workspace.DefaultKeyboardResizeDebounceMillis
import com.mrndtvndv.term.ui.workspace.MaxKeyboardResizeDebounceMillis
import com.mrndtvndv.term.ui.workspace.VisualEffectFrameRate
import com.mrndtvndv.term.ui.workspace.wallpaperScalingFromPref
import com.termux.terminal.compose.TerminalWallpaperConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val DefaultHerdrAgentFabOpacity = 0.7f
private const val MinHerdrAgentFabOpacity = 0.25f

private object Key {
    const val Theme = "app_theme"
    const val ExtraKeysEnabled = "extra_keys_enabled"
    const val ExtraKeysPreset = "extra_keys_preset"
    const val ExtraKeysCustomJson = "extra_keys_custom_json"
    const val FontSize = "font_size"
    const val KeyboardResizeDebounceMs = "keyboard_resize_debounce_ms"
    const val UnconditionalSoftKeyboardOnTap = "unconditional_soft_keyboard_on_tap"
    const val AutoShowKeyboardOnTap = "auto_show_soft_keyboard_on_tap"
    const val CursorTrail = "cursor_trail_effect"
    const val VisualEffectFrameRate = "visual_effect_frame_rate"
    const val CustomFontName = "custom_font_name"
    const val UseCustomFontForWholeUi = "use_custom_font_for_whole_ui"
    const val NativeLogcatLoggingEnabled = "native_logcat_logging_enabled"
    const val DebugHudEnabled = "debug_hud_enabled"
    const val HideWorkspaceTabs = "hide_workspace_tabs"
    const val AnimationsEnabled = "animations_enabled"
    const val RememberSoftKeyboardState = "remember_soft_keyboard_state"
    const val LastSoftKeyboardState = "last_soft_keyboard_state"
    const val ShowKeyboardFab = "show_keyboard_fab"
    const val HideKeyboardFabWhileTyping = "hide_keyboard_fab_while_typing"
    const val HerdrAgentFabOpacity = "herdr_agent_fab_opacity"
    const val WallpaperUri = "terminal_wallpaper_uri"
    const val WallpaperName = "terminal_wallpaper_name"
    const val WallpaperEnabled = "terminal_wallpaper_enabled"
    const val WallpaperOpacity = "terminal_wallpaper_opacity"
    const val WallpaperScaling = "terminal_wallpaper_scaling"
    const val WallpaperId = "terminal_wallpaper_id"
}

private fun SharedPreferences.Editor.write(settings: AppSettings) {
    putString(Key.Theme, settings.theme)
    putBoolean(Key.ExtraKeysEnabled, settings.extraKeysEnabled)
    putString(Key.ExtraKeysPreset, settings.extraKeysPreset)
    putString(Key.ExtraKeysCustomJson, settings.extraKeysCustomJson)
    putInt(Key.FontSize, settings.fontSize)
    putInt(Key.KeyboardResizeDebounceMs, settings.keyboardResizeDebounceMs)
    putBoolean(Key.UnconditionalSoftKeyboardOnTap, settings.unconditionalSoftKeyboardOnTap)
    putBoolean(Key.AutoShowKeyboardOnTap, settings.autoShowKeyboardOnTap)
    putString(Key.CursorTrail, settings.cursorTrail.key)
    putString(Key.VisualEffectFrameRate, settings.visualEffectFrameRate.key)
    putString(Key.CustomFontName, settings.customFontName)
    putBoolean(Key.UseCustomFontForWholeUi, settings.useCustomFontForWholeUi)
    putBoolean(Key.NativeLogcatLoggingEnabled, settings.nativeLogcatLoggingEnabled)
    putBoolean(Key.DebugHudEnabled, settings.debugHudEnabled)
    putBoolean(Key.HideWorkspaceTabs, settings.hideWorkspaceTabs)
    putBoolean(Key.AnimationsEnabled, settings.animationsEnabled)
    putBoolean(Key.RememberSoftKeyboardState, settings.rememberSoftKeyboardState)
    if (settings.lastSoftKeyboardState == SoftKeyboardState.UNKNOWN) {
        remove(Key.LastSoftKeyboardState)
    } else {
        putString(Key.LastSoftKeyboardState, settings.lastSoftKeyboardState.preferenceValue)
    }
    putBoolean(Key.ShowKeyboardFab, settings.showKeyboardFab)
    putBoolean(Key.HideKeyboardFabWhileTyping, settings.hideKeyboardFabWhileTyping)
    putFloat(Key.HerdrAgentFabOpacity, settings.herdrAgentFabOpacity)
    putString(Key.WallpaperUri, settings.wallpaperUri)
    putString(Key.WallpaperName, settings.wallpaperName)
    putBoolean(Key.WallpaperEnabled, settings.wallpaperEnabled)
    putFloat(Key.WallpaperOpacity, settings.wallpaperOpacity)
    putString(Key.WallpaperScaling, settings.wallpaperScaling.name)
    putLong(Key.WallpaperId, settings.wallpaperId)
}

class AppPreferences(
    private val prefs: SharedPreferences,
    private val defaultFontSize: Int,
    val fontSizeRange: IntRange,
) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val old = _settings.value
        val new = normalize(transform(old))
        if (new == old) return
        _settings.value = new
        prefs.edit().apply { write(new) }.apply()
    }

    private fun load(): AppSettings = normalize(
        AppSettings(
            theme = prefs.getString(Key.Theme, "Black") ?: "Black",
            extraKeysEnabled = prefs.getBoolean(Key.ExtraKeysEnabled, true),
            extraKeysPreset = prefs.getString(Key.ExtraKeysPreset, "Double Row") ?: "Double Row",
            extraKeysCustomJson = prefs.getString(Key.ExtraKeysCustomJson, "[]") ?: "[]",
            fontSize = prefs.getInt(Key.FontSize, defaultFontSize),
            keyboardResizeDebounceMs = prefs.getInt(
                Key.KeyboardResizeDebounceMs,
                DefaultKeyboardResizeDebounceMillis,
            ),
            unconditionalSoftKeyboardOnTap = prefs.getBoolean(Key.UnconditionalSoftKeyboardOnTap, true),
            autoShowKeyboardOnTap = prefs.getBoolean(Key.AutoShowKeyboardOnTap, true),
            cursorTrail = CursorTrailEffect.fromPref(prefs.getString(Key.CursorTrail, null)),
            visualEffectFrameRate = VisualEffectFrameRate.fromPref(prefs.getString(Key.VisualEffectFrameRate, null)),
            customFontName = prefs.getString(Key.CustomFontName, null),
            useCustomFontForWholeUi = prefs.getBoolean(Key.UseCustomFontForWholeUi, false),
            nativeLogcatLoggingEnabled = prefs.getBoolean(Key.NativeLogcatLoggingEnabled, false),
            debugHudEnabled = prefs.getBoolean(Key.DebugHudEnabled, true),
            hideWorkspaceTabs = prefs.getBoolean(Key.HideWorkspaceTabs, false),
            animationsEnabled = prefs.getBoolean(Key.AnimationsEnabled, true),
            rememberSoftKeyboardState = prefs.getBoolean(Key.RememberSoftKeyboardState, false),
            lastSoftKeyboardState = SoftKeyboardState.fromPreference(prefs.getString(Key.LastSoftKeyboardState, null)),
            showKeyboardFab = prefs.getBoolean(Key.ShowKeyboardFab, false),
            hideKeyboardFabWhileTyping = prefs.getBoolean(Key.HideKeyboardFabWhileTyping, true),
            herdrAgentFabOpacity = prefs.getFloat(Key.HerdrAgentFabOpacity, DefaultHerdrAgentFabOpacity),
            wallpaperUri = prefs.getString(Key.WallpaperUri, null),
            wallpaperName = prefs.getString(Key.WallpaperName, null),
            wallpaperEnabled = prefs.getBoolean(Key.WallpaperEnabled, true),
            wallpaperOpacity = prefs.getFloat(Key.WallpaperOpacity, TerminalWallpaperConfig.DefaultBackgroundOpacity),
            wallpaperScaling = wallpaperScalingFromPref(prefs.getString(Key.WallpaperScaling, null)),
            wallpaperId = prefs.getLong(Key.WallpaperId, 0L),
        ),
    )

    private fun normalize(settings: AppSettings): AppSettings = settings.copy(
        fontSize = settings.fontSize.coerceIn(fontSizeRange),
        keyboardResizeDebounceMs = settings.keyboardResizeDebounceMs.coerceIn(0, MaxKeyboardResizeDebounceMillis),
        lastSoftKeyboardState = settings.lastSoftKeyboardState.takeIf { settings.rememberSoftKeyboardState }
            ?: SoftKeyboardState.UNKNOWN,
        herdrAgentFabOpacity = settings.herdrAgentFabOpacity.coerceIn(MinHerdrAgentFabOpacity, 1f),
        wallpaperOpacity = settings.wallpaperOpacity.coerceIn(0f, 1f),
    )
}
