package com.mrndtvndv.term.server

import android.content.SharedPreferences
import com.mrndtvndv.term.domain.ServerConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ServerRepository(
    private val prefs: SharedPreferences,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val KEY_SAVED_SERVERS = "saved_servers_v2"
    }

    fun loadAll(): List<ServerConfig> {
        val raw = prefs.getString(KEY_SAVED_SERVERS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<ServerConfig>>(raw)
        } catch (e: kotlinx.serialization.SerializationException) {
            android.util.Log.w("ServerRepository", "Corrupted server config data, starting fresh", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            android.util.Log.w("ServerRepository", "Illegal argument decoding server config", e)
            emptyList()
        }
    }

    fun saveAll(servers: List<ServerConfig>) {
        prefs.edit().putString(KEY_SAVED_SERVERS, json.encodeToString(servers)).apply()
    }

    fun add(config: ServerConfig) {
        val list = loadAll().toMutableList()
        list.add(config)
        saveAll(list)
    }

    fun remove(id: String) {
        val list = loadAll().filter { it.id != id }
        saveAll(list)
    }

    fun update(config: ServerConfig) {
        val list = loadAll().map { if (it.id == config.id) config else it }
        saveAll(list)
    }

    fun get(id: String): ServerConfig? = loadAll().find { it.id == id }
}
