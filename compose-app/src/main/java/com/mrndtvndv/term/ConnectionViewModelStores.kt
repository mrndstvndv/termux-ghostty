package com.mrndtvndv.term

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * ViewModel stores that live as long as a server connection, so workspace
 * state survives leaving and re-entering the workspace while connected.
 */
class ConnectionViewModelStores {
    private val owners = mutableMapOf<String, ViewModelStoreOwner>()

    fun owner(serverId: String): ViewModelStoreOwner =
        owners.getOrPut(serverId) {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }

    fun clear(serverId: String) {
        owners.remove(serverId)?.viewModelStore?.clear()
    }

    fun clearAll() {
        owners.values.forEach { it.viewModelStore.clear() }
        owners.clear()
    }
}
