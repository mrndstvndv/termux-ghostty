package com.termux.terminal.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Visibility policy for the terminal scrollbar.
 */
sealed interface ScrollbarVisibility {
    /** Scrollbar remains permanently visible whenever scrollable history exists. */
    data object Always : ScrollbarVisibility

    /**
     * Scrollbar appears when scrolling or when new output arrives,
     * and automatically fades out after [hideDelayMillis] of inactivity.
     *
     * @param hideDelayMillis Duration in ms before the fade-out begins.
     * @param fadeDurationMillis Animation duration in ms for the fade-out.
     */
    data class AutoFade(
        val hideDelayMillis: Long = 1500L,
        val fadeDurationMillis: Int = 300
    ) : ScrollbarVisibility

    /** Scrollbar is completely disabled and never rendered. */
    data object Hidden : ScrollbarVisibility
}

/**
 * Styling and behavioral configuration for the terminal scrollbar.
 *
 * Inspired by official Jetpack Compose Foundation scrollbar and scroll indicator patterns.
 */
@Immutable
data class TerminalScrollbarConfig(
    val enabled: Boolean = true,
    val visibility: ScrollbarVisibility = ScrollbarVisibility.AutoFade(),
    val thickness: Dp = 4.dp,
    val padding: PaddingValues = PaddingValues(end = 2.dp, top = 2.dp, bottom = 2.dp),
    val trackColor: Color = Color.Transparent,
    val thumbColor: Color = Color(0x80FFFFFF),
    val thumbShape: Shape = RoundedCornerShape(2.dp),
    val minThumbLength: Dp = 24.dp,
    val interactive: Boolean = true
)
