package com.mrndtvndv.term.ui.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveNotification(
    val title: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
)

class NotificationState {
    private val _notification = MutableStateFlow<ActiveNotification?>(null)
    val notification: StateFlow<ActiveNotification?> = _notification.asStateFlow()

    fun post(title: String?, body: String?) {
        _notification.value = ActiveNotification(
            title = title ?: "Terminal Notification",
            body = body ?: "",
        )
    }

    fun dismiss() {
        _notification.value = null
    }
}
