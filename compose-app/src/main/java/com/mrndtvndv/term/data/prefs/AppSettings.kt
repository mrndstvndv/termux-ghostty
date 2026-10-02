package com.mrndtvndv.term.data.prefs

import com.mrndtvndv.term.ui.keyboard.PresetArrowsOnly
import com.mrndtvndv.term.ui.keyboard.PresetDoubleRow
import com.mrndtvndv.term.ui.keyboard.PresetSingleRow
import com.mrndtvndv.term.ui.keyboard.PresetTmux
import com.mrndtvndv.term.ui.keyboard.SoftKeyboardState
import com.mrndtvndv.term.ui.workspace.CursorTrailEffect
import com.mrndtvndv.term.ui.workspace.VisualEffectFrameRate
import com.termux.terminal.compose.WallpaperScaling

@Suppress("LongParameterList")
data class AppSettings(
    val theme: String,
    val extraKeysEnabled: Boolean,
    val extraKeysPreset: String,
    val extraKeysCustomJson: String,
    val fontSize: Int,
    val keyboardResizeDebounceMs: Int,
    val unconditionalSoftKeyboardOnTap: Boolean,
    val autoShowKeyboardOnTap: Boolean,
    val cursorTrail: CursorTrailEffect,
    val visualEffectFrameRate: VisualEffectFrameRate,
    val customFontName: String?,
    val useCustomFontForWholeUi: Boolean,
    val nativeLogcatLoggingEnabled: Boolean,
    val debugHudEnabled: Boolean,
    val hideWorkspaceTabs: Boolean,
    val rememberSoftKeyboardState: Boolean,
    val lastSoftKeyboardState: SoftKeyboardState,
    val showKeyboardFab: Boolean,
    val hideKeyboardFabWhileTyping: Boolean,
    val herdrAgentFabOpacity: Float,
    val wallpaperUri: String?,
    val wallpaperName: String?,
    val wallpaperEnabled: Boolean,
    val wallpaperOpacity: Float,
    val wallpaperScaling: WallpaperScaling,
    val wallpaperId: Long,
) {
    val extraKeysJson: String
        get() = when (extraKeysPreset) {
            "Double Row" -> PresetDoubleRow
            "Tmux" -> PresetTmux
            "Single Row" -> PresetSingleRow
            "Arrows Only" -> PresetArrowsOnly
            else -> extraKeysCustomJson
        }
}
