package com.example.prolestimesheet.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.prolestimesheet.R
import com.example.prolestimesheet.data.LocalDataStore
import com.example.prolestimesheet.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.todayIn
import java.util.concurrent.TimeUnit

/**
 * 🔔 Сервис напоминаний на основе WorkManager.
 *
 * Проверяет различные условия и показывает уведомления:
 * - Нет часов 3+ дня → "Проставь часы!"
 * - 10 дней без выходного → "Возьми выходной!"
 * - И т.д.
 */
object ReminderService {

    private const val TAG = "ReminderService"
    private const val CHANNEL_ID = "proles_reminders"
    private const val WORK_NAME = "proles_reminder_work"

    /**
     * Планирует периодическую проверку напоминаний (раз в 6 часов)
     */
    fun scheduleReminders(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<ReminderWorker>(
            6, TimeUnit.HOURS,
            30, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag("reminder_work")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Log.d(TAG, "⏰ Reminders scheduled (every 6 hours)")
    }

    /**
     * Создаёт канал уведомлений (для Android 8+)
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Напоминания",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Напоминания о часах, выходных и других действиях"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Показывает уведомление
     */
    fun showNotification(context: Context, reminderType: ReminderType, title: String, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)  // Замените на свою иконку
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(reminderType.notificationId, notification)
        Log.d(TAG, "🔔 Notification shown: $title")
    }
}

/**
 * Типы напоминаний
 */
enum class ReminderType(val notificationId: Int) {
    MISSING_HOURS(1001),       // Не проставлял часы 3+ дня
    NEED_REST(1002),           // 10+ дней без выходного
    FORGOTTEN_EXPENSE(1003),   // Расход без чека
    MONTHLY_REPORT(1004)       // Напоминание о закрытии месяца
}

/**
 * Worker, выполняющий проверки
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d("ReminderWorker", "🔍 Starting reminder checks...")

            ReminderService.createNotificationChannel(applicationContext)

            val user = LocalDataStore.getSession(applicationContext)
            if (user == null) {
                Log.d("ReminderWorker", "⏭️ No user session, skipping")
                return@withContext Result.success()
            }

            // Пропускаем напоминания для админов (они не проставляют часы)
            if (user.role == "admin" || user.role == "superadmin" || user.role == "director") {
                Log.d("ReminderWorker", "⏭️ Admin user, skipping reminders")
                return@withContext Result.success()
            }

            // Проверяем разные условия
            checkMissingHours(user)
            checkNeedRest(user)

            Log.d("ReminderWorker", "✅ Reminder checks completed")
            Result.success()
        } catch (e: Exception) {
            Log.e("ReminderWorker", "❌ Reminder check failed", e)
            Result.retry()
        }
    }

    /**
     * Проверка: не проставлял часы 3+ дня подряд (в рабочие дни)
     */
    private suspend fun checkMissingHours(user: User) {
        val entries = LocalDataStore.getEntries(applicationContext, user.id)
        val today = kotlinx.datetime.Clock.System.todayIn(kotlinx.datetime.TimeZone.currentSystemDefault())

        // Рабочие дни за последние 7 дней
        val recentDays = (0..6).map { offset ->
            kotlinx.datetime.LocalDate.fromEpochDays(today.toEpochDays() - offset)
        }

        // Считаем рабочие дни без часов
        var consecutiveDaysWithoutHours = 0
        for (day in recentDays) {
            val isWeekend = day.dayOfWeek == kotlinx.datetime.DayOfWeek.SATURDAY ||
                    day.dayOfWeek == kotlinx.datetime.DayOfWeek.SUNDAY
            if (isWeekend) continue

            val hasHours = entries.any { it.date == day && it.hours > 0f }
            if (!hasHours) {
                consecutiveDaysWithoutHours++
            } else {
                break  // Прерываем последовательность
            }
        }

        if (consecutiveDaysWithoutHours >= 3) {
            ReminderService.showNotification(
                applicationContext,
                ReminderType.MISSING_HOURS,
                "⏰ Проставь часы!",
                "Ты не проставлял часы уже $consecutiveDaysWithoutHours рабочих дня. Не забудь заполнить табель!"
            )
        }
    }

    /**
     * Проверка: 10+ дней работы без выходного
     */
    private suspend fun checkNeedRest(user: User) {
        val entries = LocalDataStore.getEntries(applicationContext, user.id)
        val dayOffs = LocalDataStore.getDayOffs(applicationContext, user.id)
        val today = kotlinx.datetime.Clock.System.todayIn(kotlinx.datetime.TimeZone.currentSystemDefault())

        // Проверяем последние 14 дней
        val recentDays = (0..13).map { offset ->
            kotlinx.datetime.LocalDate.fromEpochDays(today.toEpochDays() - offset)
        }

        var consecutiveWorkingDays = 0
        for (day in recentDays) {
            val isWeekend = day.dayOfWeek == kotlinx.datetime.DayOfWeek.SATURDAY ||
                    day.dayOfWeek == kotlinx.datetime.DayOfWeek.SUNDAY
            val isDayOff = dayOffs.any { it.date == day }

            // День считается рабочим, если это будний и не dayOff,
            // ИЛИ выходной, но с проставленными часами
            val isWorkingDay = (!isWeekend && !isDayOff) ||
                    (isWeekend && entries.any { it.date == day && it.hours > 0f })

            if (isWorkingDay) {
                consecutiveWorkingDays++
            } else {
                break
            }
        }

        if (consecutiveWorkingDays >= 10) {
            ReminderService.showNotification(
                applicationContext,
                ReminderType.NEED_REST,
                "😴 Пора отдохнуть!",
                "Ты работаешь $consecutiveWorkingDays дней подряд без выходного. Возьми день отдыха!"
            )
        }
    }
}