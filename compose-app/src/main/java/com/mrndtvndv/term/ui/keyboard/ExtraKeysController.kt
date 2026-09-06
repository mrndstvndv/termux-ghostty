package com.mrndtvndv.term.ui.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Extra-keys name that toggles the soft keyboard instead of writing to the terminal. */
const val ExtraKeyKeyboardToggleName = "KEYBOARD"

/** Matches the [ExtraKeyKeyboardToggleName] toggle key, case-insensitively. */
fun isKeyboardToggleKey(key: String): Boolean =
    key.equals(ExtraKeyKeyboardToggleName, ignoreCase = true)

const val PresetDoubleRow = "[['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP']," +
    " [{key: 'TAB', popup: {key: 'PASTE', display: 'PASTE'}},'CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]"
const val PresetTmux = "[['ESC','CTRL',{key: 'TAB', popup: {key: 'PASTE', display: 'PASTE'}}," +
    "{display: 'EXIT', macro: 'CTRL d'}," +
    "{display: 'PREV', macro: 'CTRL b p', " +
    "popup: {macro: 'CTRL SHIFT J', display: '('}}," +
    "{key: 'UP', popup: 'DOWN'}," +
    "{display: 'NEXT', macro: 'CTRL b n', " +
    "popup: {macro: 'CTRL SHIFT K', display: ')'}}," +
    "{display: 'NEW', macro: 'CTRL b c', " +
    "popup: {macro: 'CTRL b N', display: 'N'}}]]"
const val PresetSingleRow =
    "[[ESC, {key: 'TAB', popup: {key: 'PASTE', display: 'PASTE'}}, CTRL, ALT, {key: '-', popup: '|'}, DOWN, UP]]"
const val PresetArrowsOnly =
    "[[ESC, {key: 'TAB', popup: {key: 'PASTE', display: 'PASTE'}}, CTRL, ALT, UP, LEFT, DOWN, RIGHT]]"

@Suppress("ReturnCount") // guard clauses intentionally use early returns per project conventions
private fun checkExtraKeysElement(i: Int, j: Int, element: Any): String? {
    if (element !is String && element !is org.json.JSONObject) {
        return "Element at [$i][$j] must be a string or object"
    }
    if (element is org.json.JSONObject) {
        if (!element.has("key") && !element.has("macro")) {
            return "Object at [$i][$j] must specify 'key' or 'macro'"
        }
        if (element.has("key") && element.has("macro")) {
            return "Object at [$i][$j] cannot specify both 'key' and 'macro'"
        }
    }
    return null
}

fun validateExtraKeysJson(json: String): String? {
    if (json.isBlank()) return "JSON layout cannot be empty"
    return try {
        val outer = org.json.JSONArray(json)
        val error = checkAllExtraKeysRows(outer)
        error
    } catch (e: org.json.JSONException) {
        "Invalid JSON format: ${e.localizedMessage}"
    }
}

private fun checkAllExtraKeysRows(outer: org.json.JSONArray): String? {
    for (i in 0 until outer.length()) {
        val inner = outer.getJSONArray(i)
        for (j in 0 until inner.length()) {
            val error = checkExtraKeysElement(i, j, inner.get(j))
            if (error != null) return error
        }
    }
    return null
}

enum class ModifierState {
    INACTIVE,
    ACTIVE,
    LOCKED
}

class ExtraKeysController {
    var ctrlState by mutableStateOf(ModifierState.INACTIVE)
        private set
    var altState by mutableStateOf(ModifierState.INACTIVE)
        private set
    var shiftState by mutableStateOf(ModifierState.INACTIVE)
        private set
    var fnState by mutableStateOf(ModifierState.INACTIVE)
        private set

    @Volatile private var ctrlConsumed = false
    @Volatile private var altConsumed = false
    @Volatile private var shiftConsumed = false
    @Volatile private var fnConsumed = false

    fun toggleControl() {
        ctrlConsumed = false
        ctrlState = nextToggleState(ctrlState)
    }

    fun lockControl() {
        ctrlConsumed = false
        ctrlState = ModifierState.LOCKED
    }

    fun toggleAlt() {
        altConsumed = false
        altState = nextToggleState(altState)
    }

    fun lockAlt() {
        altConsumed = false
        altState = ModifierState.LOCKED
    }

    fun toggleShift() {
        shiftConsumed = false
        shiftState = nextToggleState(shiftState)
    }

    fun lockShift() {
        shiftConsumed = false
        shiftState = ModifierState.LOCKED
    }

    fun toggleFn() {
        fnConsumed = false
        fnState = nextToggleState(fnState)
    }
    fun lockFn() {
        fnConsumed = false
        fnState = ModifierState.LOCKED
    }

    /**
     * Reads the dispatch state without mutating Compose state in the input hot path.
     * The visual state is cleared by [clearConsumedModifiers] after dispatch.
     */
    fun readControl(): Boolean = readModifier(ctrlState) { ctrlConsumed = true }

    fun readAlt(): Boolean = readModifier(altState) { altConsumed = true }

    fun readShift(): Boolean = readModifier(shiftState) { shiftConsumed = true }

    fun readFn(): Boolean = readModifier(fnState) { fnConsumed = true }

    /** Applies one-shot modifier changes after terminal input dispatch has returned. */
    fun clearConsumedModifiers() {
        if (ctrlConsumed) {
            ctrlConsumed = false
            if (ctrlState == ModifierState.ACTIVE) ctrlState = ModifierState.INACTIVE
        }
        if (altConsumed) {
            altConsumed = false
            if (altState == ModifierState.ACTIVE) altState = ModifierState.INACTIVE
        }
        if (shiftConsumed) {
            shiftConsumed = false
            if (shiftState == ModifierState.ACTIVE) shiftState = ModifierState.INACTIVE
        }
        if (fnConsumed) {
            fnConsumed = false
            if (fnState == ModifierState.ACTIVE) fnState = ModifierState.INACTIVE
        }
    }

    private fun readModifier(state: ModifierState, markConsumed: () -> Unit): Boolean {
        if (state == ModifierState.ACTIVE) markConsumed()
        return state != ModifierState.INACTIVE
    }

    private fun nextToggleState(state: ModifierState): ModifierState = when (state) {
        ModifierState.INACTIVE -> ModifierState.ACTIVE
        ModifierState.ACTIVE, ModifierState.LOCKED -> ModifierState.INACTIVE
    }
}
