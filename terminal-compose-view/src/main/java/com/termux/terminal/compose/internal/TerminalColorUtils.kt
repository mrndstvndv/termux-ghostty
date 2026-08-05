package com.termux.terminal.compose.internal

import com.termux.terminal.TextStyle
import com.termux.terminal.compose.TerminalPalette

/** Resolves the packed foreground/background colors for a style. */
internal fun resolveEffectiveColors(
    palette: TerminalPalette,
    textStyle: Long,
    reverseVideo: Boolean
): Long {
    var foreColor = TextStyle.decodeForeColor(textStyle)
    var backColor = TextStyle.decodeBackColor(textStyle)
    val effect = TextStyle.decodeEffect(textStyle)
    val bold = (effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0
    val dim = (effect and TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0

    foreColor = resolvePaletteColor(palette, foreColor, bold)
    backColor = resolvePaletteColor(palette, backColor, false)

    val reverseHere = reverseVideo xor ((effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0)
    if (reverseHere) {
        val swap = foreColor
        foreColor = backColor
        backColor = swap
    }
    if (dim) {
        foreColor = applyDim(foreColor)
    }
    return (foreColor.toLong() shl 32) or (backColor.toLong() and 0xffffffffL)
}

/** Resolves just the effective foreground color for a style. */
internal fun resolveEffectiveForegroundColor(
    palette: TerminalPalette,
    textStyle: Long,
    reverseVideo: Boolean
): Int {
    val packed = resolveEffectiveColors(palette, textStyle, reverseVideo)
    return (packed ushr 32).toInt()
}

internal fun resolvePaletteColor(palette: TerminalPalette, color: Int, bold: Boolean): Int {
    if ((color and 0xff000000.toInt()) == 0xff000000.toInt()) {
        return color
    }
    var resolved = color
    if (bold && resolved in 0..7) {
        resolved += 8
    }
    return palette.color(resolved)
}

internal fun applyDim(color: Int): Int {
    val red = (color shr 16) and 0xFF
    val green = (color shr 8) and 0xFF
    val blue = color and 0xFF
    return 0xFF000000.toInt() or ((red * 2 / 3) shl 16) or ((green * 2 / 3) shl 8) or (blue * 2 / 3)
}
