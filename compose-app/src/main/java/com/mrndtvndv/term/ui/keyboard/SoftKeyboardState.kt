package com.mrndtvndv.term.ui.keyboard

enum class SoftKeyboardState(val preferenceValue: String) {
    UNKNOWN("unknown"),
    VISIBLE("visible"),
    HIDDEN("hidden");

    companion object {
        fun fromPreference(value: String?): SoftKeyboardState =
            entries.firstOrNull { it.preferenceValue == value } ?: UNKNOWN
    }
}

/** Filters lifecycle and tab transitions from user-visible IME changes. */
internal class SoftKeyboardVisibilityTracker {
    private var lastObservedVisibility: Boolean? = null

    /** Returns a visibility state to persist, or null when the change is not user-visible. */
    @Suppress("ReturnCount")
    fun observe(
        isVisible: Boolean,
        isTerminalActive: Boolean,
        isLifecycleResumed: Boolean
    ): Boolean? {
        if (!isTerminalActive || !isLifecycleResumed) {
            lastObservedVisibility = isVisible
            return null
        }

        val previousVisibility = lastObservedVisibility
        lastObservedVisibility = isVisible
        if (previousVisibility == null) return isVisible.takeIf { it }
        if (previousVisibility == isVisible) return null
        return isVisible
    }
}
