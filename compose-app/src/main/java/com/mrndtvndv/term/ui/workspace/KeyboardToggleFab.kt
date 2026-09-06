package com.mrndtvndv.term.ui.workspace

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Pure FAB visibility: the button shows when enabled, unless it auto-hides
 * while the soft keyboard is open.
 */
fun isKeyboardFabVisible(
    showKeyboardFab: Boolean,
    hideWhileTyping: Boolean,
    isKeyboardVisible: Boolean
): Boolean = showKeyboardFab && !(hideWhileTyping && isKeyboardVisible)

/**
 * Small keyboard toggle stacked above the Herdr agents button. The icon
 * reflects live IME visibility so the action reads as show or hide.
 */
@Composable
fun KeyboardToggleFab(
    isKeyboardVisible: Boolean,
    fabOpacity: Float,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    SmallFloatingActionButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onToggle()
        },
        modifier = modifier.alpha(fabOpacity.coerceIn(0f, 1f)),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Icon(
            imageVector = if (isKeyboardVisible) Icons.Filled.KeyboardHide else Icons.Filled.Keyboard,
            contentDescription = if (isKeyboardVisible) "Hide keyboard" else "Show keyboard"
        )
    }
}
