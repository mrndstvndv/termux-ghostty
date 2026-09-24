package com.mrndtvndv.term.testutil

import android.content.SharedPreferences

/** In-memory [SharedPreferences] for unit tests that do not need Android. */
internal class MemorySharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    fun value(key: String): Any? = values[key]

    override fun getAll(): Map<String, *> = values.toMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = applyValue(key, value)

        override fun putStringSet(
            key: String?,
            values: Set<String>?,
        ): SharedPreferences.Editor = applyValue(key, values)

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = applyValue(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = applyValue(key, value)

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = applyValue(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = applyValue(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            values.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            values.clear()
            return this
        }

        override fun commit(): Boolean = true

        override fun apply() = Unit

        private fun applyValue(key: String?, value: Any?): SharedPreferences.Editor {
            if (value == null) {
                values.remove(key)
            } else {
                values[key.orEmpty()] = value
            }
            return this
        }
    }
}
