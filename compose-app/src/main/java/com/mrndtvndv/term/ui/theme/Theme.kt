package com.mrndtvndv.term.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    secondary = Secondary,
    tertiary = Tertiary,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = DarkBackground,
    onSecondary = DarkBackground,
    onTertiary = DarkBackground,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = Border,
    error = ErrorColor
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF5E81AC), // Nord Blue accent (Nord 10)
    secondary = Color(0xFF81A1C1), // Nord 9
    tertiary = Color(0xFF88C0D0), // Nord 8
    background = Color(0xFFECEFF4), // Nord 6
    surface = Color(0xFFE5E9F0), // Nord 5
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF2E3440), // Nord 0
    onSurface = Color(0xFF2E3440), // Nord 0
    surfaceVariant = Color(0xFFD8DEE9), // Nord 4
    onSurfaceVariant = Color(0xFF3B4252), // Nord 1
    outline = Color(0xFFD8DEE9),
    error = Color(0xFFBF616A) // Nord 11
)

private val BlackColorScheme = darkColorScheme(
    primary = Primary,
    secondary = Secondary,
    tertiary = Tertiary,
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF121212),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = Border,
    error = ErrorColor
)

@Composable
fun TermuxGhosttyTheme(
    theme: String = "Dark",
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = when (theme) {
        "Light" -> {
            if (supportsDynamic) {
                dynamicLightColorScheme(context)
            } else {
                LightColorScheme
            }
        }
        "Black" -> {
            val baseDark = if (supportsDynamic) {
                dynamicDarkColorScheme(context)
            } else {
                BlackColorScheme
            }
            baseDark.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceVariant = Color(0xFF121212),
                onBackground = Color.White,
                onSurface = Color.White
            )
        }
        else -> { // "Dark"
            if (supportsDynamic) {
                dynamicDarkColorScheme(context)
            } else {
                DarkColorScheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
