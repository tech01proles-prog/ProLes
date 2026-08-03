package com.example.prolestimesheet.navigation

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton с реактивным StateFlow — уведомляет слушателей при установке данных
 */
object PendingNavigationHolder {
    private val _pending = MutableStateFlow<Pair<String, String>?>(null)
    val pending: StateFlow<Pair<String, String>?> = _pending.asStateFlow()

    fun set(type: String, payload: String) {
        _pending.value = type to payload
        Log.d("PendingNav", "📌 Установлено: type=$type")
    }

    /** Прочитать текущее значение (без очистки) */
    fun get(): Pair<String, String>? = _pending.value

    /** Прочитать и сразу очистить (используется при обработке) */
    fun consume(): Pair<String, String>? {
        val value = _pending.value
        _pending.value = null
        Log.d("PendingNav", "🧹 Потреблено и очищено")
        return value
    }

    fun clear() {
        _pending.value = null
        Log.d("PendingNav", "🧹 Очищено")
    }
}