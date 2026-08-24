package com.mrndtvndv.term.ui.workspace

import com.termux.terminal.compose.CursorEffect

/** Consumer preference for the reusable canvas' neutral frame-rate request. */
enum class VisualEffectFrameRate(
    val key: String,
    val label: String,
    val framesPerSecond: Float?
) {
    VSYNC("vsync", "VSync (display rate)", null),
    FPS_30("30", "30 FPS", 30f),
    FPS_60("60", "60 FPS", 60f),
    FPS_90("90", "90 FPS", 90f),
    FPS_120("120", "120 FPS", 120f);

    companion object {
        fun fromPref(value: String?): VisualEffectFrameRate {
            if (value == "display") return VSYNC
            return entries.firstOrNull { it.key == value } ?: VSYNC
        }
    }
}

/** Cursor trails adapted from sahaj-b/ghostty-cursor-shaders (MIT License). */
enum class CursorTrailEffect(val key: String, val label: String) {
    NONE("none", "None"),
    WARP("warp", "Warp"),
    SWEEP("sweep", "Sweep"),
    TAIL("tail", "Tail");

    companion object {
        fun fromPref(value: String?): CursorTrailEffect {
            if (value != null) return entries.firstOrNull { it.key == value } ?: NONE
            // Warp trail on by default for fresh installs.
            return WARP
        }
    }
}

internal fun CursorTrailEffect.toCursorEffect(): CursorEffect? = when (this) {
    CursorTrailEffect.NONE -> null
    CursorTrailEffect.WARP -> CursorEffect.WARP
    CursorTrailEffect.SWEEP -> CursorEffect.SWEEP
    CursorTrailEffect.TAIL -> CursorEffect.TAIL
}
