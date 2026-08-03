package com.example.prolestimesheet.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.prolestimesheet.network.ApiClient
import com.example.prolestimesheet.model.*
import kotlinx.serialization.json.Json

/**
 * 🎯 WorkManager worker, который обрабатывает очередь синхронизации.
 * Запускается автоматически при наличии интернета.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val repository = SyncRepository(context)

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "🔄 Sync started")

        // Проверяем, есть ли токен
        if (ApiClient.authToken == null) {
            Log.w("SyncWorker", "⚠️ No auth token, skipping sync")
            return Result.success()
        }

        val operations = repository.getPendingOperations()

        if (operations.isEmpty()) {
            Log.d("SyncWorker", "✅ Queue is empty")
            return Result.success()
        }

        Log.d("SyncWorker", "📋 Processing ${operations.size} operations")

        var successCount = 0
        var failCount = 0

        for (op in operations) {
            try {
                repository.markAsProcessing(op.id)
                processOperation(op)
                repository.deleteById(op.id)
                successCount++
                Log.d("SyncWorker", "✅ ${op.operationType} synced successfully")
            } catch (e: Exception) {
                failCount++
                val error = e.message ?: "Unknown error"
                Log.e("SyncWorker", "❌ ${op.operationType} failed: $error")

                // Если слишком много попыток — удаляем (иначе вечный loop)
                if (op.retryCount >= 10) {
                    Log.e("SyncWorker", "💀 Too many retries, dropping operation ${op.id}")
                    repository.deleteById(op.id)
                } else {
                    repository.markAsFailed(op.id, error)
                }
            }
        }

        Log.d("SyncWorker", "🏁 Sync finished: $successCount success, $failCount failed")

        return if (failCount == 0) Result.success() else Result.retry()
    }

    /**
     * 🎯 Обрабатывает одну операцию из очереди.
     */
    private suspend fun processOperation(op: PendingOperation) {
        when (op.operationType) {

            // ═══════ TIME ENTRIES ═══════
            PendingOperation.OP_CREATE_ENTRY,
            PendingOperation.OP_UPDATE_ENTRY -> {
                val entry = json.decodeFromString<TimeEntry>(op.payload)
                ApiClient.updateEntry(entry)  // PUT делает upsert
            }

            PendingOperation.OP_DELETE_ENTRY -> {
                val payload = json.decodeFromString<Map<String, String>>(op.payload)
                ApiClient.deleteEntry(payload["entryId"]!!)
            }

            // ═══════ EXPENSES ═══════
            PendingOperation.OP_CREATE_EXPENSE,
            PendingOperation.OP_UPDATE_EXPENSE -> {
                val expense = json.decodeFromString<Expense>(op.payload)
                ApiClient.updateExpense(expense)
            }

            PendingOperation.OP_DELETE_EXPENSE -> {
                val payload = json.decodeFromString<Map<String, String>>(op.payload)
                ApiClient.deleteExpense(payload["expenseId"]!!)
            }

            // ═══════ TRIPS ═══════
            PendingOperation.OP_CREATE_TRIP,
            PendingOperation.OP_UPDATE_TRIP -> {
                val trip = json.decodeFromString<BusinessTrip>(op.payload)
                ApiClient.updateBusinessTrip(trip)
            }

            PendingOperation.OP_DELETE_TRIP -> {
                val payload = json.decodeFromString<Map<String, String>>(op.payload)
                ApiClient.deleteBusinessTrip(payload["tripId"]!!)
            }

            // ═══════ VACATIONS ═══════
            PendingOperation.OP_CREATE_VACATION -> {
                val vacation = json.decodeFromString<VacationPeriod>(op.payload)
                ApiClient.submitVacation(vacation)
            }

            PendingOperation.OP_DELETE_VACATIONS -> {
                val payload = json.decodeFromString<Map<String, String>>(op.payload)
                ApiClient.deleteAllVacations(payload["userId"]!!)
            }

            // ═══════ DAY OFFS ═══════
            PendingOperation.OP_CREATE_DAYOFF -> {
                val payload = json.decodeFromString<Map<String, String>>(op.payload)
                val dayOff = DayOff(
                    userId = payload["userId"]!!,
                    date = kotlinx.datetime.LocalDate.parse(payload["date"]!!)
                )
                ApiClient.createDayOff(dayOff)
            }

            PendingOperation.OP_DELETE_DAYOFF -> {
                val payload = json.decodeFromString<Map<String, String>>(op.payload)
                ApiClient.deleteDayOff(
                    payload["userId"]!!,
                    kotlinx.datetime.LocalDate.parse(payload["date"]!!)
                )
            }

            // ═══════ PROJECTS ═══════
            PendingOperation.OP_CREATE_PROJECT,
            PendingOperation.OP_UPDATE_PROJECT -> {
                val project = json.decodeFromString<Project>(op.payload)
                ApiClient.updateProject(project)
            }

            PendingOperation.OP_DELETE_PROJECT -> {
                val payload = json.decodeFromString<Map<String, String>>(op.payload)
                ApiClient.deleteProject(payload["projectId"]!!)
            }

            else -> throw IllegalArgumentException("Unknown operation type: ${op.operationType}")
        }
    }
}