package com.example.prolestimesheet.data.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * 🎯 Центральная точка добавления операций в очередь синхронизации.
 *
 * Использование:
 * ```
 * SyncQueue.enqueueCreateEntry(context, entry)
 * SyncQueue.enqueueDeleteEntry(context, entryId)
 * ```
 */
object SyncQueue {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun getRepository(context: Context): SyncRepository {
        return SyncRepository(context)
    }

    /**
     * Добавляет операцию в очередь и запускает синхронизацию.
     */
    private fun enqueueAndSchedule(
        context: Context,
        operation: PendingOperation
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            getRepository(context).enqueue(operation)
            scheduleSync(context)
        }
    }

    /**
     * 🔥 Планирует фоновую синхронизацию через WorkManager.
     * WorkManager сам позаботится о retry, network constraints и persistence.
     */
    fun scheduleSync(context: Context, force: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)  // Только при наличии сети
            .setRequiresBatteryNotLow(false)
            .build()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10_000L,  // 10 секунд
                TimeUnit.MILLISECONDS
            )
            .addTag("sync_work")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "proles_sync",
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )

        Log.d("SyncQueue", "🚀 Scheduled sync work")
    }

    /**
     * 🗓 Планирует периодическую синхронизацию (каждые 15 минут).
     * Вызывать при старте приложения.
     */
    fun schedulePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            15, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag("periodic_sync")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "proles_periodic_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )

        Log.d("SyncQueue", "⏰ Periodic sync scheduled (every 15 min)")
    }

    // ═══════════════════════════════════════════════════════════
    // 🎯 Удобные методы для конкретных операций
    // ═══════════════════════════════════════════════════════════

    fun enqueueCreateEntry(context: Context, entry: com.example.prolestimesheet.model.TimeEntry) {
        enqueueAndSchedule(context, PendingOperation(
            operationType = PendingOperation.OP_CREATE_ENTRY,
            entityType = "ENTRY",
            entityId = entry.id,
            payload = json.encodeToString(entry)
        ))
    }

    fun enqueueUpdateEntry(context: Context, entry: com.example.prolestimesheet.model.TimeEntry) {
        enqueueAndSchedule(context, PendingOperation(
            operationType = PendingOperation.OP_UPDATE_ENTRY,
            entityType = "ENTRY",
            entityId = entry.id,
            payload = json.encodeToString(entry)
        ))
    }

    fun enqueueDeleteEntry(context: Context, entryId: String) {
        enqueueAndSchedule(context, PendingOperation(
            operationType = PendingOperation.OP_DELETE_ENTRY,
            entityType = "ENTRY",
            entityId = entryId,
            payload = """{"entryId": "$entryId"}"""
        ))
    }

    fun enqueueCreateExpense(context: Context, expense: com.example.prolestimesheet.model.Expense) {
        enqueueAndSchedule(context, PendingOperation(
            operationType = PendingOperation.OP_CREATE_EXPENSE,
            entityType = "EXPENSE",
            entityId = expense.id,
            payload = json.encodeToString(expense)
        ))
    }

    fun enqueueDeleteExpense(context: Context, expenseId: String) {
        enqueueAndSchedule(context, PendingOperation(
            operationType = PendingOperation.OP_DELETE_EXPENSE,
            entityType = "EXPENSE",
            entityId = expenseId,
            payload = """{"expenseId": "$expenseId"}"""
        ))
    }

    fun enqueueCreateTrip(context: Context, trip: com.example.prolestimesheet.model.BusinessTrip) {
        enqueueAndSchedule(context, PendingOperation(
            operationType = PendingOperation.OP_CREATE_TRIP,
            entityType = "TRIP",
            entityId = trip.id,
            payload = json.encodeToString(trip)
        ))
    }

    fun enqueueCreateDayOff(context: Context, userId: String, date: String) {
        enqueueAndSchedule(context, PendingOperation(
            operationType = PendingOperation.OP_CREATE_DAYOFF,
            entityType = "DAYOFF",
            payload = """{"userId": "$userId", "date": "$date"}"""
        ))
    }

    fun enqueueDeleteDayOff(context: Context, userId: String, date: String) {
        enqueueAndSchedule(context, PendingOperation(
            operationType = PendingOperation.OP_DELETE_DAYOFF,
            entityType = "DAYOFF",
            payload = """{"userId": "$userId", "date": "$date"}"""
        ))
    }

    /** Очистка очереди при logout */
    fun clearQueue(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            getRepository(context).clearAll()
            WorkManager.getInstance(context).cancelAllWork()
            Log.d("SyncQueue", "🧹 Queue cleared on logout")
        }
    }
}