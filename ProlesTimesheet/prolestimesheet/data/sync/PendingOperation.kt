package com.example.prolestimesheet.data.sync

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Операция, ожидающая синхронизации с сервером.
 * Сохраняется в Room — переживёт перезапуск приложения.
 */
@Entity(tableName = "pending_operations")
data class PendingOperation(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    /** Тип операции: CREATE_ENTRY, UPDATE_ENTRY, DELETE_ENTRY, etc. */
    val operationType: String,

    /** Тип сущности: ENTRY, EXPENSE, TRIP, PROJECT, etc. */
    val entityType: String,

    /** ID сущности (для UPDATE/DELETE) */
    val entityId: String? = null,

    /** JSON с данными операции */
    val payload: String,

    /** Время создания (для сортировки FIFO) */
    val createdAt: Long = System.currentTimeMillis(),

    /** Количество попыток отправки */
    val retryCount: Int = 0,

    /** Последняя ошибка (для отладки) */
    val lastError: String? = null,

    /** Статус: PENDING, PROCESSING, FAILED */
    val status: String = STATUS_PENDING
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_PROCESSING = "PROCESSING"
        const val STATUS_FAILED = "FAILED"

        // Типы операций
        const val OP_CREATE_ENTRY = "CREATE_ENTRY"
        const val OP_UPDATE_ENTRY = "UPDATE_ENTRY"
        const val OP_DELETE_ENTRY = "DELETE_ENTRY"
        const val OP_CREATE_EXPENSE = "CREATE_EXPENSE"
        const val OP_UPDATE_EXPENSE = "UPDATE_EXPENSE"
        const val OP_DELETE_EXPENSE = "DELETE_EXPENSE"
        const val OP_UPLOAD_RECEIPT = "UPLOAD_RECEIPT"
        const val OP_CREATE_TRIP = "CREATE_TRIP"
        const val OP_UPDATE_TRIP = "UPDATE_TRIP"
        const val OP_DELETE_TRIP = "DELETE_TRIP"
        const val OP_CREATE_VACATION = "CREATE_VACATION"
        const val OP_DELETE_VACATIONS = "DELETE_VACATIONS"
        const val OP_CREATE_DAYOFF = "CREATE_DAYOFF"
        const val OP_DELETE_DAYOFF = "DELETE_DAYOFF"
        const val OP_CREATE_PROJECT = "CREATE_PROJECT"
        const val OP_UPDATE_PROJECT = "UPDATE_PROJECT"
        const val OP_DELETE_PROJECT = "DELETE_PROJECT"
    }
}