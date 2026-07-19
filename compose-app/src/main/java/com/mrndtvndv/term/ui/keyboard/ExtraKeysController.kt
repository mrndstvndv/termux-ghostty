package com.mrndtvndv.term.ui.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

const val PresetDoubleRow = "[['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP']," +
    " ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]"
const val PresetSingleRow = "[[ESC, TAB, CTRL, ALT, {key: '-', popup: '|'}, DOWN, UP]]"
const val PresetArrowsOnly = "[[ESC, TAB, CTRL, ALT, UP, LEFT, DOWN, RIGHT]]"

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
    var altState by mutableStateOf(ModifierState.INACTIVE)
    var shiftState by mutableStateOf(ModifierState.INACTIVE)
    var fnState by mutableStateOf(ModifierState.INACTIVE)

    fun toggleControl() {
        ctrlState = when (ctrlState) {
            ModifierState.INACTIVE -> ModifierState.ACTIVE
            ModifierState.ACTIVE, ModifierState.LOCKED -> ModifierState.INACTIVE
        }
    }

    fun lockControl() {
        ctrlState = ModifierState.LOCKED
    }

    fun toggleAlt() {
        altState = when (altState) {
            ModifierState.INACTIVE -> ModifierState.ACTIVE
            ModifierState.ACTIVE, ModifierState.LOCKED -> ModifierState.INACTIVE
        }
    }

    fun lockAlt() {
        altState = ModifierState.LOCKED
    }

    fun toggleShift() {
        shiftState = when (shiftState) {
            ModifierState.INACTIVE -> ModifierState.ACTIVE
            ModifierState.ACTIVE, ModifierState.LOCKED -> ModifierState.INACTIVE
        }
    }

    fun lockShift() {
        shiftState = ModifierState.LOCKED
    }

    fun toggleFn() {
        fnState = when (fnState) {
            ModifierState.INACTIVE -> ModifierState.ACTIVE
            ModifierState.ACTIVE, ModifierState.LOCKED -> ModifierState.INACTIVE
        }
    }

    fun lockFn() {
        fnState = ModifierState.LOCKED
    }

    /**
     * Read Ctrl state and consume it if ACTIVE (one-shot). Writing to mutableStateOf here is safe
     * — we're on the main thread, outside a composition, so Compose just schedules a recompose
     * for the next frame via Choreographer; it does not recompose synchronously mid-dispatch.
     */
    fun readControl(): Boolean {
        val state = ctrlState
        if (state == ModifierState.ACTIVE) {
            ctrlState = ModifierState.INACTIVE
        }
        return state != ModifierState.INACTIVE
    }

    fun readAlt(): Boolean {
        val state = altState
        if (state == ModifierState.ACTIVE) {
            altState = ModifierState.INACTIVE
        }
        return state != ModifierState.INACTIVE
    }

    fun readShift(): Boolean {
        val state = shiftState
        if (state == ModifierState.ACTIVE) {
            shiftState = ModifierState.INACTIVE
        }
        return state != ModifierState.INACTIVE
    }

    fun readFn(): Boolean {
        val state = fnState
        if (state == ModifierState.ACTIVE) {
            fnState = ModifierState.INACTIVE
        }
        return state != ModifierState.INACTIVE
    }
}
