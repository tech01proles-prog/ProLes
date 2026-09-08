package com.proles.server.services

import com.proles.server.data.*
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Единая серверная модель расчёта зарплаты за календарный месяц.
 *
 * Правила:
 * - берём только активные компоненты, действующие в выбранном месяце;
 * - FIXED/BONUS суммируются;
 * - PENALTY уменьшает итог;
 * - PIECE = ставка за фактически выполненную запись (день) с часами > 0;
 * - HOURLY = ставка × фактические часы;
 * - projectId у PIECE/HOURLY ограничивает расчёт конкретным проектом.
 */
object SalaryCalculator {
    data class SalaryBreakdown(
        val fixed: Double,
        val piece: Double,
        val hourly: Double,
        val bonus: Double,
        val penalty: Double,
        val total: Double
    )

    fun calculate(userId: UUID, year: Int, month: Int): SalaryBreakdown {
        require(month in 1..12) { "Month must be 1..12" }

        return transaction {
            val startDate = LocalDate(year, month, 1)
            val daysInMonth = java.time.YearMonth.of(year, month).lengthOfMonth()
            val endDate = LocalDate(year, month, daysInMonth)

            val components = SalaryComponentsTable.selectAll()
                .where {
                    (SalaryComponentsTable.userId eq userId) and
                        (SalaryComponentsTable.isActive eq true)
                }
                .toList()
                .filter { row ->
                    val effectiveFrom = row[SalaryComponentsTable.effectiveFrom]
                    val effectiveTo = row[SalaryComponentsTable.effectiveTo]
                    effectiveFrom <= endDate && (effectiveTo == null || effectiveTo >= startDate)
                }

            val fixed = components
                .filter { it[SalaryComponentsTable.type] == "FIXED" }
                .sumOf { it[SalaryComponentsTable.amount] }

            val bonus = components
                .filter { it[SalaryComponentsTable.type] == "BONUS" }
                .sumOf { it[SalaryComponentsTable.amount] }

            val penalty = components
                .filter { it[SalaryComponentsTable.type] == "PENALTY" }
                .sumOf { it[SalaryComponentsTable.amount] }

            val periodEntries = TimeEntriesTable.selectAll()
                .where {
                    (TimeEntriesTable.userId eq userId) and
                        (TimeEntriesTable.date greaterEq startDate) and
                        (TimeEntriesTable.date lessEq endDate) and
                        (TimeEntriesTable.hours greater 0f)
                }
                .toList()

            val piece = components
                .filter { it[SalaryComponentsTable.type] == "PIECE" }
                .sumOf { comp ->
                    val projectId = comp[SalaryComponentsTable.projectId]?.value
                    val ratePerUnit = comp[SalaryComponentsTable.ratePerUnit] ?: 0.0
                    periodEntries.count { entry ->
                        (projectId == null || entry[TimeEntriesTable.projectId].value == projectId)
                    } * ratePerUnit
                }

            val hourly = components
                .filter { it[SalaryComponentsTable.type] == "HOURLY" }
                .sumOf { comp ->
                    val projectId = comp[SalaryComponentsTable.projectId]?.value
                    val ratePerHour = comp[SalaryComponentsTable.ratePerHour] ?: 0.0
                    periodEntries
                        .filter { entry -> projectId == null || entry[TimeEntriesTable.projectId].value == projectId }
                        .sumOf { it[TimeEntriesTable.hours].toDouble() } * ratePerHour
                }

            SalaryBreakdown(
                fixed = fixed,
                piece = piece,
                hourly = hourly,
                bonus = bonus,
                penalty = penalty,
                total = fixed + piece + hourly + bonus - penalty
            )
        }
    }
}
