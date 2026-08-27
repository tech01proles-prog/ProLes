package com.proles.server.services

import com.proles.server.data.BusinessTripsTable
import com.proles.server.data.ExpensesTable
import com.proles.server.data.ProjectsTable
import com.proles.server.data.UsersTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.time.Duration.Companion.days

/**
 * 📒 Сервис автоматического начисления суточных за командировки.
 * 
 * Запускается как фоновая задача раз в 24 часа (в 00:00).
 * Для каждого сотрудника с незавершённой командировкой (DEPARTURE или TRANSFER)
 * создаётся запись о расходе "Суточные" в размере perDiemRate из командировки.
 */
object PerDiemService {

    private const val PER_DIEM_TYPE = "PER_DIEM"
    private const val PER_DIEM_NAME = "Суточные"

    fun startScheduler() {
        println("⏰ PerDiemService: запуск планировщика...")
        
        CoroutineScope(Dispatchers.IO).launch {
            // Ждём до следующего 00:00
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            val tomorrow = now.date.plus(1, DateTimeUnit.DAY)
            val nextRun = LocalDateTime(tomorrow, LocalTime(0, 0, 0))
            val delayMillis = now.until(nextRun, DateTimeUnit.MILLISECOND)

            println("⏰ PerDiemService: следующее выполнение через ${delayMillis / 1000 / 60} мин (в ${nextRun})")
            delay(delayMillis)

            // Первый запуск
            processPerDiems()

            // Затем каждые 24 часа
            while (true) {
                delay(1.days.inWholeMilliseconds)
                processPerDiems()
            }
        }
    }

    /**
     * 📒 Основная логика: находит активные командировки и создаёт записи о суточных.
     */
    fun processPerDiems() {
        println("📒 PerDiemService: начало обработки суточных...")
        
        try {
            val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

            val activeTrips = transaction {
                BusinessTripsTable.selectAll()
                    .filter { row ->
                        val type = row[BusinessTripsTable.type]
                        // Активные командировки: DEPARTURE или TRANSFER (но не COMPLETION)
                        type == "DEPARTURE" || type == "TRANSFER"
                    }
                    .toList()
            }

            if (activeTrips.isEmpty()) {
                println("ℹ️ PerDiemService: нет активных командировок")
                return
            }

            println("📒 PerDiemService: найдено ${activeTrips.size} активных командировок")

            var createdCount = 0
            var skippedCount = 0

            activeTrips.forEach { tripRow ->
                try {
                    val tripId = tripRow[BusinessTripsTable.id].value.toString()
                    val userId = tripRow[BusinessTripsTable.userId].value.toString()
                    val projectId = tripRow[BusinessTripsTable.projectId].value.toString()
                    val perDiemRate = tripRow[BusinessTripsTable.perDiemRate]
                    val tripType = tripRow[BusinessTripsTable.type]
                    val tripDate = tripRow[BusinessTripsTable.date].toString()

                    // Получаем имя пользователя и проекта для логов
                    val (userName, projectName) = transaction {
                        val user = UsersTable.selectAll()
                            .where { UsersTable.id eq UUID.fromString(userId) }
                            .firstOrNull()
                        val project = ProjectsTable.selectAll()
                            .where { ProjectsTable.id eq UUID.fromString(projectId) }
                            .firstOrNull()
                        Pair(user?.get(UsersTable.name) ?: "Unknown", project?.get(ProjectsTable.name) ?: "Unknown")
                    }

                    // Проверяем, существует ли уже запись о суточных за сегодня для этого пользователя
                    val existingExpense = transaction {
                        ExpensesTable.selectAll()
                            .where {
                                (ExpensesTable.userId eq UUID.fromString(userId)) and
                                    (ExpensesTable.date eq today) and
                                    (ExpensesTable.type eq PER_DIEM_TYPE)
                            }
                            .firstOrNull()
                    }

                    if (existingExpense != null) {
                        println("⏭️ PerDiemService: пропуск для $userName - суточные за $today уже начислены")
                        skippedCount++
                        return@forEach
                    }

                    // Создаём запись о расходе "Суточные"
                    transaction {
                        ExpensesTable.insert {
                            it[ExpensesTable.id] = UUID.randomUUID()
                            it[ExpensesTable.userId] = UUID.fromString(userId)
                            it[ExpensesTable.projectId] = UUID.fromString(projectId)
                            it[ExpensesTable.date] = today
                            it[ExpensesTable.type] = PER_DIEM_TYPE
                            it[ExpensesTable.name] = PER_DIEM_NAME
                            it[ExpensesTable.amount] = perDiemRate
                            it[ExpensesTable.currency] = "RUB"
                            it[ExpensesTable.comment] = "Автоматическое начисление суточных за командировку ($tripType) от $tripDate"
                            it[ExpensesTable.receiptSubmitted] = false
                            it[ExpensesTable.hasReceiptPhoto] = false
                            it[ExpensesTable.createdAt] = System.currentTimeMillis()
                        }
                    }

                    createdCount++
                    println("✅ PerDiemService: начислено $perDiemRate руб. суточных для $userName (проект: $projectName)")

                } catch (e: Exception) {
                    println("❌ PerDiemService: ошибка при обработке командировки: ${e.message}")
                    e.printStackTrace()
                }
            }

            println("📒 PerDiemService: завершено. Создано записей: $createdCount, пропущено: $skippedCount")

        } catch (e: Exception) {
            println("❌ PerDiemService: критическая ошибка: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 🧪 Ручной запуск для тестирования (можно вызвать из админки).
     */
    fun triggerManual() {
        println("🔧 PerDiemService: ручной запуск...")
        processPerDiems()
    }
}
