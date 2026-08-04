package com.mrndtvndv.term.ui.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveNotification(
    val title: String,
    val body: String,
    val serverId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

class NotificationState {
    private val _notification = MutableStateFlow<ActiveNotification?>(null)
    val notification: StateFlow<ActiveNotification?> = _notification.asStateFlow()

    fun post(title: String?, body: String?, serverId: String? = null) {
        _notification.value = ActiveNotification(
            title = title ?: "Terminal Notification",
            body = body ?: "",
            serverId = serverId,
        )
    }

    fun dismiss() {
        _notification.value = null
    }
}
