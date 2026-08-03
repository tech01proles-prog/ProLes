package com.proles.server.services

import com.proles.server.data.*
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

object SalaryCalculator {
    data class SalaryBreakdown(
        val fixed: Double,
        val piece: Double,
        val hourly: Double,
        val bonus: Double,
        val total: Double
    )

    fun calculate(userId: UUID, year: Int, month: Int): SalaryBreakdown {
        return transaction {
            val components = SalaryComponentsTable.selectAll()
                .where {
                    (SalaryComponentsTable.userId eq userId) and
                            (SalaryComponentsTable.isActive eq true)
                }
                .toList()

            val startDate = LocalDate(year, month, 1)
            val daysInMonth = java.time.YearMonth.of(year, month).lengthOfMonth()
            val endDate = LocalDate(year, month, daysInMonth)

            // 1. FIXED (оклад) — СУММИРУЕМ все компоненты
            val fixed = components
                .filter { it[SalaryComponentsTable.type] == "FIXED" }
                .sumOf { it[SalaryComponentsTable.amount] }

            // 2. BONUS (премия) — СУММИРУЕМ все компоненты
            val bonus = components
                .filter { it[SalaryComponentsTable.type] == "BONUS" }
                .sumOf { it[SalaryComponentsTable.amount] }

            // 3. PIECE (сдельная) — ratePerUnit × количество ЗАПИСЕЙ за месяц
            val pieceComponents = components.filter {
                it[SalaryComponentsTable.type] == "PIECE"
            }
            val piece = pieceComponents.sumOf { comp ->
                val projectId = comp[SalaryComponentsTable.projectId]?.value
                val ratePerUnit = comp[SalaryComponentsTable.ratePerUnit] ?: 0.0

                // Считаем количество записей (дней с часами) за месяц
                val query = TimeEntriesTable.selectAll()
                    .where {
                        (TimeEntriesTable.userId eq userId) and
                                (TimeEntriesTable.date greaterEq startDate) and
                                (TimeEntriesTable.date lessEq endDate) and
                                (TimeEntriesTable.hours greater 0f)
                    }

                val entryCount = if (projectId != null) {
                    query.andWhere { TimeEntriesTable.projectId eq projectId }.count()
                } else {
                    query.count()
                }

                entryCount * ratePerUnit
            }

            // 4. HOURLY (почасовая) — ratePerHour × СУММА ЧАСОВ за месяц
            val hourlyComponents = components.filter {
                it[SalaryComponentsTable.type] == "HOURLY"
            }
            val hourly = hourlyComponents.sumOf { comp ->
                val projectId = comp[SalaryComponentsTable.projectId]?.value
                val ratePerHour = comp[SalaryComponentsTable.ratePerHour] ?: 0.0

                val query = TimeEntriesTable.selectAll()
                    .where {
                        (TimeEntriesTable.userId eq userId) and
                                (TimeEntriesTable.date greaterEq startDate) and
                                (TimeEntriesTable.date lessEq endDate)
                    }

                val entries = if (projectId != null) {
                    query.andWhere { TimeEntriesTable.projectId eq projectId }
                } else {
                    query
                }

                val totalHours = entries.sumOf { it[TimeEntriesTable.hours].toDouble() }
                totalHours * ratePerHour
            }

            SalaryBreakdown(
                fixed = fixed,
                piece = piece,
                hourly = hourly,
                bonus = bonus,
                total = fixed + piece + hourly + bonus
            )
        }
    }
}