package com.mrndtvndv.term.ui.workspace

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.compose.TerminalWallpaper

/** Hands a freshly picked wallpaper from Settings to the terminal so it is not decoded twice. */
@Stable
class WallpaperController {
    var predecoded: TerminalWallpaper? by mutableStateOf(null)
}
