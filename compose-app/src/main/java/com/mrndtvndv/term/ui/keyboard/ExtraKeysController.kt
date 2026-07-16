package com.mrndtvndv.term.ui.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ModifierState {
    INACTIVE,
    ACTIVE,
    LOCKED
}

/**
 * Tracks modifier key (Ctrl/Alt/Shift/Fn) toggle state for the extra keys toolbar.
 *
 * Each modifier has two representations:
 *  - A Compose [mutableStateOf] (`*State`) that drives the UI — written only from the UI thread
 *    when the user taps the modifier button.
 *  - A plain [@Volatile] boolean (`*Consumed`) that records whether a ACTIVE->INACTIVE transition
 *    has already been "read" by a key event handler. This is written from [readControl] / [readAlt]
 *    etc. which are called from [com.termux.view.TerminalView.onKeyDown] on the main thread.
 *
 * The key insight is that the old implementation wrote directly back to the [mutableStateOf] inside
 * [readControl]/[readAlt]/[readShift]/[readFn], which triggered a Compose recomposition of the
 * entire ExtraKeysToolbar synchronously in the middle of key-event dispatch. The recomposition is
 * deferred to the next frame via a separate [markConsumed] path that only writes the visual state
 * after the key event has been fully processed.
 */
class ExtraKeysController {
    var ctrlState by mutableStateOf(ModifierState.INACTIVE)
        private set
    var altState by mutableStateOf(ModifierState.INACTIVE)
        private set
    var shiftState by mutableStateOf(ModifierState.INACTIVE)
        private set
    var fnState by mutableStateOf(ModifierState.INACTIVE)
        private set

    // Consumed flags: set to true by read*() when ACTIVE -> will be cleared to INACTIVE on
    // the next UI-thread frame via clearConsumedModifiers().
    @Volatile private var ctrlConsumed = false
    @Volatile private var altConsumed = false
    @Volatile private var shiftConsumed = false
    @Volatile private var fnConsumed = false

    fun toggleControl() {
        ctrlConsumed = false
        ctrlState = when (ctrlState) {
            ModifierState.INACTIVE -> ModifierState.ACTIVE
            ModifierState.ACTIVE, ModifierState.LOCKED -> ModifierState.INACTIVE
        }
    }

    fun lockControl() {
        ctrlConsumed = false
        ctrlState = ModifierState.LOCKED
    }

    fun toggleAlt() {
        altConsumed = false
        altState = when (altState) {
            ModifierState.INACTIVE -> ModifierState.ACTIVE
            ModifierState.ACTIVE, ModifierState.LOCKED -> ModifierState.INACTIVE
        }
    }

    fun lockAlt() {
        altConsumed = false
        altState = ModifierState.LOCKED
    }

    fun toggleShift() {
        shiftConsumed = false
        shiftState = when (shiftState) {
            ModifierState.INACTIVE -> ModifierState.ACTIVE
            ModifierState.ACTIVE, ModifierState.LOCKED -> ModifierState.INACTIVE
        }
    }

    fun lockShift() {
        shiftConsumed = false
        shiftState = ModifierState.LOCKED
    }

    fun toggleFn() {
        fnConsumed = false
        fnState = when (fnState) {
            ModifierState.INACTIVE -> ModifierState.ACTIVE
            ModifierState.ACTIVE, ModifierState.LOCKED -> ModifierState.INACTIVE
        }
    }

    fun lockFn() {
        fnConsumed = false
        fnState = ModifierState.LOCKED
    }

    /**
     * Called from [com.termux.view.TerminalView.onKeyDown] — i.e. during key dispatch on the
     * main thread. Only reads the current state and marks it as consumed; does NOT write back
     * to [mutableStateOf] to avoid triggering a Compose recomposition mid-dispatch.
     *
     * The Compose state is updated lazily via [clearConsumedModifiers], which is called from the
     * toolbar's [androidx.compose.runtime.LaunchedEffect] on the next frame.
     */
    fun readControl(): Boolean {
        val state = ctrlState
        if (state == ModifierState.ACTIVE && !ctrlConsumed) {
            ctrlConsumed = true
        }
        return state != ModifierState.INACTIVE
    }

    fun readAlt(): Boolean {
        val state = altState
        if (state == ModifierState.ACTIVE && !altConsumed) {
            altConsumed = true
        }
        return state != ModifierState.INACTIVE
    }

    fun readShift(): Boolean {
        val state = shiftState
        if (state == ModifierState.ACTIVE && !shiftConsumed) {
            shiftConsumed = true
        }
        return state != ModifierState.INACTIVE
    }

    fun readFn(): Boolean {
        val state = fnState
        if (state == ModifierState.ACTIVE && !fnConsumed) {
            fnConsumed = true
        }
        return state != ModifierState.INACTIVE
    }

    /**
     * Applies any pending consumed-modifier state changes to the Compose observable state.
     * Must be called from the UI thread (e.g. in a LaunchedEffect or recomposition scope).
     * This defers the [mutableStateOf] write — and the resulting recomposition — out of the
     * hot key-dispatch path.
     */
    fun clearConsumedModifiers() {
        if (ctrlConsumed) { ctrlConsumed = false; ctrlState = ModifierState.INACTIVE }
        if (altConsumed) { altConsumed = false; altState = ModifierState.INACTIVE }
        if (shiftConsumed) { shiftConsumed = false; shiftState = ModifierState.INACTIVE }
        if (fnConsumed) { fnConsumed = false; fnState = ModifierState.INACTIVE }
    }
}
