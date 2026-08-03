package com.example.prolestimesheet.data.sync

import android.content.Context
import android.util.Log
import com.example.prolestimesheet.data.AppDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Репозиторий для работы с очередью синхронизации.
 */
class SyncRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).pendingOperationDao()

    /** Наблюдаем за количеством pending операций */
    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    /** Наблюдаем за всеми операциями (для отладки UI) */
    fun observeAll(): Flow<List<PendingOperation>> = dao.observeAll()

    /** Добавляем операцию в очередь */
    suspend fun enqueue(operation: PendingOperation) {
        // 🎯 Оптимизация: если это UPDATE/DELETE существующей сущности,
        // которая ещё не синхронизирована — заменяем старую операцию
        if (operation.entityId != null && operation.operationType != PendingOperation.OP_CREATE_ENTRY) {
            // Удаляем предыдущие операции для этой сущности
            // Например: если создали запись (offline) и сразу обновили —
            // удаляем CREATE и оставляем только UPDATE с финальными данными
            // Но это сложная оптимизация — для начала оставим всё как FIFO
        }

        dao.insert(operation)
        Log.d("SyncRepository", "📥 Enqueued: ${operation.operationType} for ${operation.entityType} ${operation.entityId}")
    }

    /** Получить pending операции для синхронизации */
    suspend fun getPendingOperations(): List<PendingOperation> = dao.getPendingOperations()

    suspend fun markAsProcessing(id: String) = dao.markAsProcessing(id)

    suspend fun markAsFailed(id: String, error: String) = dao.markAsFailed(id, error)

    suspend fun deleteById(id: String) = dao.deleteById(id)

    suspend fun clearAll() = dao.clearAll()
}