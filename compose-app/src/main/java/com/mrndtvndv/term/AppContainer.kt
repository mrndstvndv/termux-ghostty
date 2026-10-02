package com.mrndtvndv.term

import android.content.Context
import com.mrndtvndv.term.data.prefs.AppPreferences
import com.mrndtvndv.term.data.prefs.CustomFontStore
import com.mrndtvndv.term.data.prefs.LastSessionStore
import com.mrndtvndv.term.server.AppSessionManager
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val sessionManager: AppSessionManager = AppSessionManager.run {
        init(appContext)
        checkNotNull(current)
    }

    val preferences: AppPreferences = createPreferences()

    val fontStore = CustomFontStore(appContext)

    val lastSessionStore = LastSessionStore(appContext.getSharedPreferences("ssh_prefs", Context.MODE_PRIVATE))

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            preferences.settings
                .map { it.nativeLogcatLoggingEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) NativeLogcatLogger.start(appContext) else NativeLogcatLogger.stop()
                }
        }
    }

    private fun createPreferences(): AppPreferences {
        val fontSizes = TermuxAppSharedPreferences.getDefaultFontSizes(appContext)
        val minimumFontSize = fontSizes.getOrElse(1) { 8 }
        val maximumFontSize = fontSizes.getOrElse(2) { 256 }
        return AppPreferences(
            prefs = appContext.getSharedPreferences("ssh_prefs", Context.MODE_PRIVATE),
            defaultFontSize = fontSizes.firstOrNull() ?: minimumFontSize,
            fontSizeRange = minimumFontSize..maximumFontSize,
        )
    }
}
