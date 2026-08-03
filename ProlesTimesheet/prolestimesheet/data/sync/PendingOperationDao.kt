package com.example.prolestimesheet.data.sync

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingOperationDao {

    /** Все операции в очереди (для UI индикатора) */
    @Query("SELECT * FROM pending_operations ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PendingOperation>>

    /** Количество pending операций (для бейджа) */
    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    /** Получить все pending операции для синхронизации */
    @Query("SELECT * FROM pending_operations WHERE status = 'PENDING' ORDER BY createdAt ASC LIMIT 50")
    suspend fun getPendingOperations(): List<PendingOperation>

    /** Вставить новую операцию */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(operation: PendingOperation)

    /** Отметить как "в процессе" */
    @Query("UPDATE pending_operations SET status = 'PROCESSING' WHERE id = :id")
    suspend fun markAsProcessing(id: String)

    /** Увеличить retry count и вернуть в pending */
    @Query("""
        UPDATE pending_operations 
        SET status = 'PENDING', 
            retryCount = retryCount + 1, 
            lastError = :error
        WHERE id = :id
    """)
    suspend fun markAsFailed(id: String, error: String)

    /** Удалить после успешной синхронизации */
    @Query("DELETE FROM pending_operations WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Удалить все успешно синхронизированные */
    @Query("DELETE FROM pending_operations WHERE status = 'PROCESSING'")
    suspend fun deleteProcessed()

    /** Очистить всю очередь (например, при logout) */
    @Query("DELETE FROM pending_operations")
    suspend fun clearAll()

    /** Удалить операции по entityId (например, при DELETE_ENTRY удаляем связанные UPDATE) */
    @Query("DELETE FROM pending_operations WHERE entityId = :entityId AND entityType = :entityType")
    suspend fun deleteByEntity(entityId: String, entityType: String)
}