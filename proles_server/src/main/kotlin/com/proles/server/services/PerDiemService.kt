package com.proles.server.services

import com.proles.server.config.NotificationService
import com.proles.server.data.BusinessTripsTable
import com.proles.server.data.ExpensesTable
import com.proles.server.data.ProjectsTable
import com.proles.server.model.BusinessTripDto
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.days

/**
 * Сверяет автоматически созданные суточные со всеми командировками.
 *
 * Начисление идёт за каждый календарный день от даты начала включительно
 * до даты окончания включительно. Для незакрытой командировки расчёт
 * ограничивается сегодняшним днём. Сверка восстанавливает пропущенные дни
 * и корректирует начисления после изменения дат, ставки или удаления поездки.
 */
object PerDiemService {
    private val tripJson = Json { ignoreUnknownKeys = true }

    private const val PER_DIEM_TYPE = "PER_DIEM"
    private const val PER_DIEM_EXTRA_TYPE = "PER_DIEM_EXTRA"
    private const val PER_DIEM_NAME = "Суточные"
    private const val PER_DIEM_EXTRA_NAME = "Суточные сверх."
    private const val STANDARD_PER_DIEM = 750.0
    private const val AUTO_MARKER = "[AUTO_PER_DIEM]"
    private const val LEGACY_STANDARD_MARKER =
        "Автоматическое начисление суточных за командировку"
    private const val LEGACY_EXTRA_MARKER = "Сверхсуточные:"

    private data class DayKey(val userId: UUID, val date: LocalDate)

    private data class ExpectedDay(
        val tripId: UUID,
        val userId: UUID,
        val projectId: UUID?,
        val projectName: String,
        val subprojectId: UUID?,
        val date: LocalDate,
        val startDate: LocalDate,
        val createdAt: Long,
        val tripType: String,
        val rate: Double
    )

    fun startScheduler() {
        println("⏰ PerDiemService: запуск планировщика...")
        processPerDiems()

        CoroutineScope(Dispatchers.IO).launch {
            val now = Clock.System.now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
            val tomorrow = now.date.plus(1, DateTimeUnit.DAY)
            val nextRun = LocalDateTime(tomorrow, LocalTime(0, 0, 0))
            val delayMillis = Duration.between(
                now.toJavaLocalDateTime(),
                nextRun.toJavaLocalDateTime()
            ).toMillis()

            println(
                "⏰ PerDiemService: следующий запуск через " +
                    "${delayMillis / 1000 / 60} мин (в $nextRun)"
            )
            delay(delayMillis.coerceAtLeast(0L))

            while (true) {
                processPerDiems()
                delay(1.days.inWholeMilliseconds)
            }
        }
    }

    /**
     * Автоматически закрывает поездки с наступившей датой окончания.
     * Отправка уведомления выполняется только для перехода ACTIVE -> COMPLETED,
     * поэтому ручное завершение и переезд не приводят к дублям.
     */
    private fun completeTripsDueToday(today: LocalDate) {
        val completedTrips = transaction {
            val dueTrips = BusinessTripsTable.selectAll().filter { row ->
                row[BusinessTripsTable.status].uppercase() == "ACTIVE" &&
                    (row[BusinessTripsTable.endDate]
                        ?: row[BusinessTripsTable.completedDate])?.let { it <= today } == true
            }

            dueTrips.map { row ->
                val tripId = row[BusinessTripsTable.id].value
                val endDate = row[BusinessTripsTable.endDate]
                    ?: row[BusinessTripsTable.completedDate]!!

                BusinessTripsTable.update({ BusinessTripsTable.id eq tripId }) {
                    it[BusinessTripsTable.status] = "COMPLETED"
                }

                val projectId = row[BusinessTripsTable.projectId]?.value
                val projectName = projectId?.let { id ->
                    ProjectsTable.selectAll()
                        .where { ProjectsTable.id eq id }
                        .singleOrNull()
                        ?.get(ProjectsTable.name)
                }.orEmpty()
                val participants = runCatching {
                    tripJson.decodeFromString<List<String>>(
                        row[BusinessTripsTable.participants]
                    )
                }.getOrDefault(emptyList())

                BusinessTripDto(
                    id = tripId.toString(),
                    userId = row[BusinessTripsTable.userId].value.toString(),
                    projectId = projectId?.toString(),
                    projectNumber = row[BusinessTripsTable.projectNumber],
                    companyName = row[BusinessTripsTable.companyName],
                    country = row[BusinessTripsTable.country],
                    projectName = projectName,
                    type = "DEPARTURE",
                    date = row[BusinessTripsTable.date].toString(),
                    completedDate = endDate.toString(),
                    startDate = (row[BusinessTripsTable.startDate]
                        ?: row[BusinessTripsTable.date]).toString(),
                    endDate = endDate.toString(),
                    city = row[BusinessTripsTable.city],
                    transport = row[BusinessTripsTable.transport],
                    notes = row[BusinessTripsTable.notes],
                    purpose = row[BusinessTripsTable.purpose],
                    participants = participants,
                    createdAt = row[BusinessTripsTable.createdAt],
                    perDiemRate = row[BusinessTripsTable.perDiemRate],
                    status = "COMPLETED"
                )
            }
        }

        completedTrips.forEach { trip ->
            runCatching {
                NotificationService.notifyBusinessTripCompleted(trip)
            }.onFailure { error ->
                println(
                    "⚠️ PerDiemService: не удалось отправить уведомление " +
                        "о завершении командировки ${trip.id}: ${error.message}"
                )
            }
        }
    }

    fun processPerDiems() {
        println("📒 PerDiemService: сверка суточных...")
        try {
            val today = Clock.System.now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date

            completeTripsDueToday(today)

            transaction {
                val expectedDays = linkedMapOf<DayKey, ExpectedDay>()

                BusinessTripsTable.selectAll().forEach { trip ->
                    val status = trip[BusinessTripsTable.status].uppercase()
                    val tripType = trip[BusinessTripsTable.type].uppercase()
                    if (status == "CANCELLED" || tripType == "COMPLETION") return@forEach

                    val startDate = trip[BusinessTripsTable.startDate]
                        ?: trip[BusinessTripsTable.date]
                    if (startDate > today) return@forEach

                    val storedEndDate = trip[BusinessTripsTable.endDate]
                        ?: trip[BusinessTripsTable.completedDate]
                    val endDate = when {
                        storedEndDate == null || storedEndDate > today -> today
                        else -> storedEndDate
                    }
                    if (endDate < startDate) return@forEach

                    val userId = trip[BusinessTripsTable.userId].value
                    val projectId = trip[BusinessTripsTable.projectId]?.value
                    val projectName = projectId?.let { id ->
                        ProjectsTable.selectAll()
                            .where { ProjectsTable.id eq id }
                            .limit(1)
                            .singleOrNull()
                            ?.get(ProjectsTable.name)
                    }.orEmpty()
                    val candidateBase = ExpectedDay(
                        tripId = trip[BusinessTripsTable.id].value,
                        userId = userId,
                        projectId = projectId,
                        projectName = projectName,
                        subprojectId = trip[BusinessTripsTable.subprojectId]?.value,
                        date = startDate,
                        startDate = startDate,
                        createdAt = trip[BusinessTripsTable.createdAt],
                        tripType = trip[BusinessTripsTable.type],
                        rate = trip[BusinessTripsTable.perDiemRate].coerceAtLeast(0.0)
                    )

                    var day = startDate
                    while (day <= endDate) {
                        val key = DayKey(userId, day)
                        val candidate = candidateBase.copy(date = day)
                        val current = expectedDays[key]
                        if (
                            current == null ||
                            candidate.startDate > current.startDate ||
                            (
                                candidate.startDate == current.startDate &&
                                    candidate.createdAt > current.createdAt
                                )
                        ) {
                            expectedDays[key] = candidate
                        }
                        day = day.plus(1, DateTimeUnit.DAY)
                    }
                }

                fun isAutoPerDiem(row: ResultRow): Boolean {
                    val comment = row[ExpensesTable.comment]
                    return row[ExpensesTable.isAutoGenerated] ||
                        row[ExpensesTable.creatorRole] == "SYSTEM_PER_DIEM" ||
                        row[ExpensesTable.expenseScope] == "AUTO_PER_DIEM" ||
                        comment.contains(AUTO_MARKER) ||
                        comment.startsWith(LEGACY_STANDARD_MARKER) ||
                        comment.startsWith(LEGACY_EXTRA_MARKER)
                }

                val existingAutoExpenses = ExpensesTable.selectAll()
                    .where {
                        ExpensesTable.type inList listOf(
                            PER_DIEM_TYPE,
                            PER_DIEM_EXTRA_TYPE
                        )
                    }
                    .filter { row ->
                        row[ExpensesTable.deletedAt] == null &&
                            isAutoPerDiem(row)
                    }

                val rowsByUserDate = existingAutoExpenses.groupBy { row ->
                    DayKey(
                        row[ExpensesTable.userId].value,
                        row[ExpensesTable.date]
                    )
                }

                val staleIds = mutableListOf<UUID>()
                var inserted = 0
                var updated = 0
                var removed = 0

                rowsByUserDate.forEach { (key, rows) ->
                    val expected = expectedDays[key]
                    val expectedTypes = when {
                        expected == null -> emptySet()
                        expected.rate > STANDARD_PER_DIEM ->
                            setOf(PER_DIEM_TYPE, PER_DIEM_EXTRA_TYPE)
                        else -> setOf(PER_DIEM_TYPE)
                    }
                    rows.forEach { row ->
                        if (row[ExpensesTable.type] !in expectedTypes) {
                            staleIds += row[ExpensesTable.id].value
                        }
                    }
                }

                expectedDays.forEach { (key, expected) ->
                    val baseAmount = minOf(expected.rate, STANDARD_PER_DIEM)
                    val extraAmount = (expected.rate - STANDARD_PER_DIEM)
                        .coerceAtLeast(0.0)
                    val desired = buildMap {
                        put(PER_DIEM_TYPE, baseAmount to PER_DIEM_NAME)
                        if (extraAmount > 0.0) {
                            put(PER_DIEM_EXTRA_TYPE, extraAmount to PER_DIEM_EXTRA_NAME)
                        }
                    }

                    desired.forEach { (expenseType, amountAndName) ->
                        val (amount, name) = amountAndName
                        val matchingRows = rowsByUserDate[key].orEmpty()
                            .filter { it[ExpensesTable.type] == expenseType }
                        val projectLabel = expected.projectName.ifBlank { "без проекта" }
                        val comment = "Командировка ($projectLabel)"

                        val existing = matchingRows.firstOrNull()
                        if (existing == null) {
                            ExpensesTable.insert {
                                it[ExpensesTable.id] = UUID.randomUUID()
                                it[ExpensesTable.userId] = expected.userId
                                it[ExpensesTable.projectId] = expected.projectId
                                it[ExpensesTable.subprojectId] = expected.subprojectId
                                it[ExpensesTable.date] = expected.date
                                it[ExpensesTable.type] = expenseType
                                it[ExpensesTable.name] = name
                                it[ExpensesTable.amount] = amount
                                it[ExpensesTable.currency] = "RUB"
                                it[ExpensesTable.comment] = comment
                                it[ExpensesTable.receiptSubmitted] = true
                                it[ExpensesTable.hasReceiptPhoto] = false
                                it[ExpensesTable.category] = "WITHOUT_RECEIPT"
                                it[ExpensesTable.expenseScope] = "GENERAL"
                                it[ExpensesTable.isAutoGenerated] = true
                                it[ExpensesTable.createdAt] = System.currentTimeMillis()
                            }
                            inserted++
                        } else {
                            val expenseId = existing[ExpensesTable.id].value
                            ExpensesTable.update({ ExpensesTable.id eq expenseId }) {
                                it[ExpensesTable.projectId] = expected.projectId
                                it[ExpensesTable.subprojectId] = expected.subprojectId
                                it[ExpensesTable.name] = name
                                it[ExpensesTable.amount] = amount
                                it[ExpensesTable.currency] = "RUB"
                                it[ExpensesTable.comment] = comment
                                it[ExpensesTable.category] = "WITHOUT_RECEIPT"
                                it[ExpensesTable.expenseScope] = "GENERAL"
                                it[ExpensesTable.isAutoGenerated] = true
                                it[ExpensesTable.receiptSubmitted] = true
                                it[ExpensesTable.hasReceiptPhoto] = false
                            }
                            updated++
                            matchingRows.drop(1).forEach {
                                staleIds += it[ExpensesTable.id].value
                            }
                        }
                    }
                }

                if (staleIds.isNotEmpty()) {
                    removed = ExpensesTable.deleteWhere {
                        ExpensesTable.id inList staleIds.distinct()
                    }
                }

                println(
                    "📒 PerDiemService: дней=${expectedDays.size}, " +
                        "добавлено=$inserted, обновлено=$updated, удалено=$removed"
                )
            }
        } catch (e: Exception) {
            println("❌ PerDiemService: ошибка сверки суточных: ${e.message}")
            e.printStackTrace()
        }
    }

    fun triggerManual() {
        println("🔧 PerDiemService: ручной запуск...")
        processPerDiems()
    }
}
