package com.mrndtvndv.term.ui.prefs

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserPrefs {
    private val _customFontName = MutableStateFlow<String?>(null)
    val customFontName: StateFlow<String?> = _customFontName.asStateFlow()

    private val _useCustomFontForWholeUi = MutableStateFlow(false)
    val useCustomFontForWholeUi: StateFlow<Boolean> = _useCustomFontForWholeUi.asStateFlow()

    fun init(prefs: SharedPreferences) {
        _customFontName.value = prefs.getString("custom_font_name", null)
        _useCustomFontForWholeUi.value = prefs.getBoolean("use_custom_font_for_whole_ui", false)
    }

    fun setCustomFontName(name: String?, prefs: SharedPreferences) {
        _customFontName.value = name
        prefs.edit().putString("custom_font_name", name).apply()
    }

    fun setUseCustomFontForWholeUi(enabled: Boolean, prefs: SharedPreferences) {
        _useCustomFontForWholeUi.value = enabled
        prefs.edit().putBoolean("use_custom_font_for_whole_ui", enabled).apply()
    }
}
