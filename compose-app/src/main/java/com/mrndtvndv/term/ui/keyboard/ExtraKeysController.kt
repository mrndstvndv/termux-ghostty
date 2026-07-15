package com.mrndtvndv.term.ui.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
