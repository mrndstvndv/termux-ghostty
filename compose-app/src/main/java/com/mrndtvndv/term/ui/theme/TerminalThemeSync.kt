package com.mrndtvndv.term.ui.theme

import android.annotation.SuppressLint
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import java.util.Locale
import java.util.Properties

@Composable
fun TerminalThemeSync(
    termSession: TerminalSession?,
    appTheme: String
) {
    val currentThemeScheme = MaterialTheme.colorScheme
    val isThemeDark = !appTheme.equals("Light", ignoreCase = true)

    LaunchedEffect(currentThemeScheme, termSession) {
        val properties = Properties()
        
        val primary = currentThemeScheme.primary.toArgb()
        val primaryContainer = currentThemeScheme.primaryContainer.toArgb()
        val secondary = currentThemeScheme.secondary.toArgb()
        val secondaryContainer = currentThemeScheme.secondaryContainer.toArgb()
        val tertiary = currentThemeScheme.tertiary.toArgb()
        val tertiaryContainer = currentThemeScheme.tertiaryContainer.toArgb()
        val surface = currentThemeScheme.surface.toArgb()
        val onSurface = currentThemeScheme.onSurface.toArgb()
        val onSurfaceVariant = currentThemeScheme.onSurfaceVariant.toArgb()
        val outline = currentThemeScheme.outline.toArgb()
        val error = currentThemeScheme.error.toArgb()
        val errorContainer = currentThemeScheme.errorContainer.toArgb()
        val surfaceContainerHighest = currentThemeScheme.surfaceVariant.toArgb()

        @SuppressLint("RestrictedApi")
        fun shiftTone(colorVal: Int, toneVal: Double): Int {
            val hct = com.google.android.material.color.utilities.Hct.fromInt(colorVal)
            hct.setTone(toneVal)
            return hct.toInt()
        }

        fun toTerminalColor(color: Int): String {
            return String.format(Locale.US, "#%02x%02x%02x", android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
        }

        properties.setProperty("foreground", toTerminalColor(onSurface))
        properties.setProperty("background", toTerminalColor(surface))
        properties.setProperty("cursor", toTerminalColor(primary))
        properties.setProperty("color0", toTerminalColor(surfaceContainerHighest))
        properties.setProperty("color1", toTerminalColor(error))
        properties.setProperty("color2", toTerminalColor(tertiary))
        properties.setProperty("color3", toTerminalColor(primaryContainer))
        properties.setProperty("color4", toTerminalColor(primary))
        properties.setProperty("color5", toTerminalColor(secondary))
        properties.setProperty("color6", toTerminalColor(tertiaryContainer))
        properties.setProperty("color7", toTerminalColor(onSurfaceVariant))
        properties.setProperty("color8", toTerminalColor(outline))
        properties.setProperty("color9", toTerminalColor(errorContainer))
        properties.setProperty("color10", toTerminalColor(shiftTone(tertiary, if (isThemeDark) 88.0 else 28.0)))
        properties.setProperty("color11", toTerminalColor(shiftTone(primary, if (isThemeDark) 88.0 else 28.0)))
        properties.setProperty("color12", toTerminalColor(primaryContainer))
        properties.setProperty("color13", toTerminalColor(secondaryContainer))
        properties.setProperty("color14", toTerminalColor(shiftTone(tertiaryContainer, if (isThemeDark) 92.0 else 24.0)))
        properties.setProperty("color15", toTerminalColor(onSurface))

        TerminalColors.COLOR_SCHEME.updateWith(properties)
        termSession?.reloadColorScheme()
    }
}
