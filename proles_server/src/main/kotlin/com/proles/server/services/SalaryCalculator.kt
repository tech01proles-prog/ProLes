package com.proles.server.services

import com.proles.server.data.*
import com.proles.server.model.PayrollHourEntryDto
import com.proles.server.model.PayrollProjectGroupDto
import com.proles.server.model.PayrollSubprojectGroupDto
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
        val total: Double,
        val projectGroups: List<PayrollProjectGroupDto> = emptyList()
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

            val hourlyComponents = components
                .filter { it[SalaryComponentsTable.type] == "HOURLY" }

            fun applicableHourlyComponents(entry: ResultRow): List<ResultRow> {
                val entryProjectId = entry[TimeEntriesTable.projectId].value
                val entrySubprojectId = entry[TimeEntriesTable.subprojectId]?.value

                val exactSubproject = hourlyComponents.filter { comp ->
                    entrySubprojectId != null &&
                        comp[SalaryComponentsTable.projectId]?.value == entryProjectId &&
                        comp[SalaryComponentsTable.subprojectId]?.value == entrySubprojectId
                }
                if (exactSubproject.isNotEmpty()) return exactSubproject

                val projectSpecific = hourlyComponents.filter { comp ->
                    comp[SalaryComponentsTable.projectId]?.value == entryProjectId &&
                        comp[SalaryComponentsTable.subprojectId] == null
                }
                if (projectSpecific.isNotEmpty()) return projectSpecific

                return hourlyComponents.filter { comp ->
                    comp[SalaryComponentsTable.projectId] == null
                }
            }

            val hourlyLines = periodEntries.mapNotNull { entry ->
                val applicable = applicableHourlyComponents(entry)
                if (applicable.isEmpty()) return@mapNotNull null

                val rate = applicable.sumOf {
                    it[SalaryComponentsTable.ratePerHour] ?: 0.0
                }
                if (rate <= 0.0) return@mapNotNull null

                val hours = entry[TimeEntriesTable.hours].toDouble()
                PayrollHourEntryDto(
                    id = entry[TimeEntriesTable.id].value.toString(),
                    date = entry[TimeEntriesTable.date].toString(),
                    hours = hours,
                    hourlyCost = rate,
                    amount = hours * rate,
                    comment = entry[TimeEntriesTable.comment].orEmpty()
                )
            }

            val hourlyLineById = hourlyLines.associateBy { it.id }

            val groupsByProject = periodEntries
                .mapNotNull { entry ->
                    hourlyLineById[entry[TimeEntriesTable.id].value.toString()]
                        ?.let { line -> entry to line }
                }
                .groupBy { it.first[TimeEntriesTable.projectId].value }

            val projectGroups = groupsByProject.map { (projectId, pairs) ->
                val firstEntry = pairs.first().first
                val projectName = firstEntry[TimeEntriesTable.projectName]
                    .takeIf { it.isNotBlank() }
                    ?: ProjectsTable
                        .selectAll()
                        .where { ProjectsTable.id eq projectId }
                        .limit(1)
                        .singleOrNull()
                        ?.get(ProjectsTable.name)
                        .orEmpty()
                        .ifBlank { "Без проекта" }

                val subGroups = pairs
                    .filter { it.first[TimeEntriesTable.subprojectId] != null }
                    .groupBy { it.first[TimeEntriesTable.subprojectId]!!.value }
                    .map { (subprojectId, subPairs) ->
                        val subprojectName = SubprojectsTable
                            .selectAll()
                            .where { SubprojectsTable.id eq subprojectId }
                            .limit(1)
                            .singleOrNull()
                            ?.get(SubprojectsTable.name)
                            .orEmpty()
                        val lines = subPairs.map { it.second }
                        PayrollSubprojectGroupDto(
                            subprojectId = subprojectId.toString(),
                            subprojectName = subprojectName,
                            hours = lines.sumOf { it.hours },
                            amount = lines.sumOf { it.amount },
                            entries = lines
                        )
                    }

                val linesWithoutSubproject = pairs
                    .filter { it.first[TimeEntriesTable.subprojectId] == null }
                    .map { it.second }

                PayrollProjectGroupDto(
                    projectId = projectId.toString(),
                    projectName = projectName,
                    hours = pairs.sumOf { it.second.hours },
                    amount = pairs.sumOf { it.second.amount },
                    subprojects = subGroups,
                    entriesWithoutSubproject = linesWithoutSubproject
                )
            }.sortedBy { it.projectName }

            val hourly = hourlyLines.sumOf { it.amount }

            SalaryBreakdown(
                fixed = fixed,
                piece = piece,
                hourly = hourly,
                bonus = bonus,
                penalty = penalty,
                total = fixed + piece + hourly + bonus - penalty,
                projectGroups = projectGroups
            )
        }
    }
}
