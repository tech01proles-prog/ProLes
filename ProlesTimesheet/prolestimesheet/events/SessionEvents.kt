package com.example.prolestimesheet.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Глобальный event bus для событий сессии.
 * Используется для мгновенной реакции на 401 от сервера.
 */
object SessionEvents {
    private val _sessionExpired = MutableSharedFlow<Unit>(replay = 0)
    val sessionExpired = _sessionExpired.asSharedFlow()

    suspend fun emitSessionExpired() {
        _sessionExpired.emit(Unit)
    }
}