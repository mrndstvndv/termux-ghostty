package com.mrndtvndv.term.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrndtvndv.term.data.prefs.AppPreferences
import com.mrndtvndv.term.data.prefs.AppSettings
import com.mrndtvndv.term.data.prefs.CustomFontStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferences: AppPreferences,
    private val fontStore: CustomFontStore,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = preferences.settings

    fun update(transform: (AppSettings) -> AppSettings) = preferences.update(transform)

    fun importFont(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            fontStore.import(uri)?.let { name -> update { it.copy(customFontName = name) } }
        }
    }

    fun clearFont() {
        fontStore.clear()
        update { it.copy(customFontName = null) }
    }
}
