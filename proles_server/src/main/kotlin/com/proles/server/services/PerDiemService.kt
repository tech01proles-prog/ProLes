package com.proles.server.services

import com.proles.server.data.BusinessTripsTable
import com.proles.server.data.ExpensesTable
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
import kotlinx.datetime.plus
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.days

/**
 * Автоматические суточные синхронизируются со всеми командировками:
 * учитываются и поездки, созданные задним числом, и завершённые прошедшей датой.
 * День окончания включается в расчёт.
 */
object PerDiemService {

    private const val PER_DIEM_TYPE = "PER_DIEM"
    private const val PER_DIEM_EXTRA_TYPE = "PER_DIEM_EXTRA"
    private const val PER_DIEM_NAME = "Суточные"
    private const val PER_DIEM_EXTRA_NAME = "Суточные сверх."
    private const val STANDARD_PER_DIEM = 750.0
    private const val AUTO_COMMENT_PREFIX = "Автоматическое начисление суточных"
    private const val AUTO_EXTRA_COMMENT_PREFIX = "Автоматическое начисление сверхсуточных"

    private data class TripDay(
        val tripId: UUID,
        val userId: UUID,
        val projectId: UUID?,
        val startDate: LocalDate,
        val rate: Double,
        val sourceType: String,
        val createdAt: Long
    )

    private data class ExpenseDayKey(val userId: UUID, val date: LocalDate)

    fun startScheduler() {
        println("⏰ PerDiemService: запуск планировщика...")
        processPerDiems()

        CoroutineScope(Dispatchers.IO).launch {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            val tomorrow = now.date.plus(1, DateTimeUnit.DAY)
            val nextRun = LocalDateTime(tomorrow, LocalTime(0, 0, 0))
            val delayMillis = Duration.between(
                now.toJavaLocalDateTime(),
                nextRun.toJavaLocalDateTime()
            ).toMillis()

            println("⏰ PerDiemService: следующее выполнение через ${delayMillis / 1000 / 60} мин (в $nextRun)")
            delay(delayMillis)

            while (true) {
                processPerDiems()
                delay(1.days.inWholeMilliseconds)
            }
        }
    }

    /**
     * Пересчитывает автоматические суточные за весь фактический период поездок.
     * Повторный запуск безопасен: он не создаёт дубли и удаляет только свои
     * автоматические записи, переставшие соответствовать датам командировок.
     */
    fun processPerDiems() {
        println("📒 PerDiemService: начало синхронизации суточных...")

        try {
            val today = Clock.System.now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date

            val (createdCount, updatedCount, removedCount, coveredDays) = transaction {
                val expectedDays = mutableMapOf<ExpenseDayKey, TripDay>()

                BusinessTripsTable.selectAll().forEach { row ->
                    val status = row[BusinessTripsTable.status].uppercase()
                    val type = row[BusinessTripsTable.type].uppercase()

                    if (status == "CANCELLED" || type == "COMPLETION") {
                        return@forEach
                    }

                    val startDate = row[BusinessTripsTable.startDate]
                        ?: row[BusinessTripsTable.date]
                    val endDate = row[BusinessTripsTable.endDate]
                        ?: row[BusinessTripsTable.completedDate]

                    // Будущие поездки пока не начисляют суточные.
                    if (startDate > today || (endDate != null && endDate < startDate)) {
                        return@forEach
                    }

                    // Дата окончания включается: в этот день поездка ещё учитывается.
                    val lastChargeDate = minOf(endDate ?: today, today)
                    val trip = TripDay(
                        tripId = row[BusinessTripsTable.id].value,
                        userId = row[BusinessTripsTable.userId].value,
                        projectId = row[BusinessTripsTable.projectId]?.value,
                        startDate = startDate,
                        rate = row[BusinessTripsTable.perDiemRate].coerceAtLeast(0.0),
                        sourceType = type,
                        createdAt = row[BusinessTripsTable.createdAt]
                    )

                    var date = startDate
                    while (date <= lastChargeDate) {
                        val key = ExpenseDayKey(trip.userId, date)
                        val previous = expectedDays[key]

                        // Если две поездки соприкасаются в один календарный день
                        // (например, переезд), день относится к начавшейся позже.
                        if (
                            previous == null ||
                            trip.startDate > previous.startDate ||
                            (trip.startDate == previous.startDate && trip.createdAt > previous.createdAt)
                        ) {
                            expectedDays[key] = trip
                        }

                        date = date.plus(1, DateTimeUnit.DAY)
                    }
                }

                val perDiemRows = ExpensesTable.selectAll()
                    .filter {
                        it[ExpensesTable.type] == PER_DIEM_TYPE ||
                            it[ExpensesTable.type] == PER_DIEM_EXTRA_TYPE
                    }
                    .toList()

                fun isAutomatic(row: ResultRow): Boolean {
                    val comment = row[ExpensesTable.comment]
                    return when (row[ExpensesTable.type]) {
                        PER_DIEM_TYPE -> comment.startsWith(AUTO_COMMENT_PREFIX)
                        PER_DIEM_EXTRA_TYPE ->
                            comment.startsWith(AUTO_EXTRA_COMMENT_PREFIX) ||
                                comment.startsWith("Сверхсуточные:")
                        else -> false
                    }
                }

                var removed = 0
                // Убираем только автоматические начисления, для которых больше
                // нет действующей даты командировки (включая даты после завершения).
                perDiemRows.filter(::isAutomatic).forEach { row ->
                    val key = ExpenseDayKey(
                        row[ExpensesTable.userId].value,
                        row[ExpensesTable.date]
                    )
                    if (key !in expectedDays) {
                        ExpensesTable.deleteWhere {
                            ExpensesTable.id eq row[ExpensesTable.id].value
                        }
                        removed++
                    }
                }

                val remainingRows = ExpensesTable.selectAll()
                    .filter {
                        it[ExpensesTable.type] == PER_DIEM_TYPE ||
                            it[ExpensesTable.type] == PER_DIEM_EXTRA_TYPE
                    }
                    .toList()

                var created = 0
                var updated = 0

                expectedDays.forEach { (key, trip) ->
                    val dayRows = remainingRows.filter {
                        it[ExpensesTable.userId].value == key.userId &&
                            it[ExpensesTable.date] == key.date
                    }
                    val automaticRows = dayRows.filter(::isAutomatic)
                    val manualRows = dayRows.filterNot(::isAutomatic)

                    // Ручные суточные не дублируем и не изменяем.
                    if (manualRows.isNotEmpty()) {
                        automaticRows.forEach { row ->
                            ExpensesTable.deleteWhere {
                                ExpensesTable.id eq row[ExpensesTable.id].value
                            }
                            removed++
                        }
                        return@forEach
                    }

                    if (trip.rate <= 0.0) {
                        automaticRows.forEach { row ->
                            ExpensesTable.deleteWhere {
                                ExpensesTable.id eq row[ExpensesTable.id].value
                            }
                            removed++
                        }
                        return@forEach
                    }

                    val standardAmount = minOf(trip.rate, STANDARD_PER_DIEM)
                    val extraAmount = (trip.rate - STANDARD_PER_DIEM).coerceAtLeast(0.0)
                    val tripMarker = "[trip:${trip.tripId}]"
                    val standardComment =
                        "$AUTO_COMMENT_PREFIX $tripMarker за ${key.date} (начало ${trip.startDate})"
                    val extraComment =
                        "$AUTO_EXTRA_COMMENT_PREFIX $tripMarker: ${trip.rate} ₽ − ${STANDARD_PER_DIEM.toInt()} ₽"

                    val standardRow = automaticRows.firstOrNull {
                        it[ExpensesTable.type] == PER_DIEM_TYPE
                    }

                    if (standardRow == null) {
                        ExpensesTable.insert {
                            it[ExpensesTable.id] = UUID.randomUUID()
                            it[ExpensesTable.userId] = key.userId
                            it[ExpensesTable.projectId] = trip.projectId
                            it[ExpensesTable.date] = key.date
                            it[ExpensesTable.type] = PER_DIEM_TYPE
                            it[ExpensesTable.name] = PER_DIEM_NAME
                            it[ExpensesTable.amount] = standardAmount
                            it[ExpensesTable.currency] = "RUB"
                            it[ExpensesTable.comment] = standardComment
                            it[ExpensesTable.receiptSubmitted] = false
                            it[ExpensesTable.hasReceiptPhoto] = false
                            it[ExpensesTable.category] = "WORK"
                            it[ExpensesTable.createdAt] = System.currentTimeMillis()
                        }
                        created++
                    } else {
                        ExpensesTable.update({ ExpensesTable.id eq standardRow[ExpensesTable.id].value }) {
                            it[ExpensesTable.projectId] = trip.projectId
                            it[ExpensesTable.name] = PER_DIEM_NAME
                            it[ExpensesTable.amount] = standardAmount
                            it[ExpensesTable.comment] = standardComment
                        }
                        updated++
                    }

                    val extraRow = automaticRows.firstOrNull {
                        it[ExpensesTable.type] == PER_DIEM_EXTRA_TYPE
                    }

                    if (extraAmount > 0.0) {
                        if (extraRow == null) {
                            ExpensesTable.insert {
                                it[ExpensesTable.id] = UUID.randomUUID()
                                it[ExpensesTable.userId] = key.userId
                                it[ExpensesTable.projectId] = trip.projectId
                                it[ExpensesTable.date] = key.date
                                it[ExpensesTable.type] = PER_DIEM_EXTRA_TYPE
                                it[ExpensesTable.name] = PER_DIEM_EXTRA_NAME
                                it[ExpensesTable.amount] = extraAmount
                                it[ExpensesTable.currency] = "RUB"
                                it[ExpensesTable.comment] = extraComment
                                it[ExpensesTable.receiptSubmitted] = false
                                it[ExpensesTable.hasReceiptPhoto] = false
                                it[ExpensesTable.category] = "WORK"
                                it[ExpensesTable.createdAt] = System.currentTimeMillis()
                            }
                            created++
                        } else {
                            ExpensesTable.update({ ExpensesTable.id eq extraRow[ExpensesTable.id].value }) {
                                it[ExpensesTable.projectId] = trip.projectId
                                it[ExpensesTable.name] = PER_DIEM_EXTRA_NAME
                                it[ExpensesTable.amount] = extraAmount
                                it[ExpensesTable.comment] = extraComment
                            }
                            updated++
                        }
                    } else if (extraRow != null) {
                        ExpensesTable.deleteWhere {
                            ExpensesTable.id eq extraRow[ExpensesTable.id].value
                        }
                        removed++
                    }
                }

                Quadruple(created, updated, removed, expectedDays.size)
            }

            println(
                "📒 PerDiemService: завершено. Дней к начислению: $coveredDays, " +
                    "создано: $createdCount, обновлено: $updatedCount, удалено: $removedCount"
            )
        } catch (e: Exception) {
            println("❌ PerDiemService: ошибка синхронизации суточных: ${e.message}")
            e.printStackTrace()
        }
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    fun triggerManual() {
        println("🔧 PerDiemService: ручная синхронизация суточных...")
        processPerDiems()
    }
}
