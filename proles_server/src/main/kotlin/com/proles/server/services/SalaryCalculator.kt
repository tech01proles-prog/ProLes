package com.proles.server.services

import com.proles.server.data.*
import com.proles.server.model.PayrollComponentBreakdownDto
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
 * Почасовые и сдельные компоненты используют единое распределение рабочих записей:
 * подпроект -> проект -> общий компонент. На первом найденном уровне выбирается
 * только один тип начисления: при наличии PIECE запись относится к сдельной части,
 * иначе применяется HOURLY. Поэтому одни и те же часы не могут попасть в обе части.
 */
object SalaryCalculator {
    data class SalaryBreakdown(
        val fixed: Double,
        val piece: Double,
        val hourly: Double,
        val bonus: Double,
        val penalty: Double,
        val total: Double,
        val projectGroups: List<PayrollProjectGroupDto> = emptyList(),
        val componentBreakdown: List<PayrollComponentBreakdownDto> = emptyList()
    )

    private data class ComponentBreakdownKey(
        val componentId: UUID,
        val projectId: UUID,
        val subprojectId: UUID?
    )

    private data class ComponentBreakdownAccumulator(
        val componentId: UUID,
        val type: String,
        val projectId: UUID,
        val projectName: String,
        val subprojectId: UUID?,
        val subprojectName: String,
        val scope: String,
        val rate: Double,
        var hours: Double = 0.0,
        var units: Double = 0.0,
        var amount: Double = 0.0
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

            // Не используем некорректно настроенные ставки при выборе уровня:
            // компонент без положительной ставки не должен блокировать fallback.
            val timeComponents = components.filter { component ->
                when (component[SalaryComponentsTable.type]) {
                    "PIECE" -> (component[SalaryComponentsTable.ratePerUnit] ?: 0.0) > 0.0
                    "HOURLY" -> (component[SalaryComponentsTable.ratePerHour] ?: 0.0) > 0.0
                    else -> false
                }
            }

            // На каждой записи применяется самый конкретный уровень среди обоих типов.
            // Сначала подпроект, затем весь проект, затем общий компонент.
            fun applicableComponents(entry: ResultRow): List<ResultRow> {
                val projectId = entry[TimeEntriesTable.projectId].value
                val subprojectId = entry[TimeEntriesTable.subprojectId]?.value

                val subprojectSpecific = timeComponents.filter { component ->
                    subprojectId != null &&
                        component[SalaryComponentsTable.projectId]?.value == projectId &&
                        component[SalaryComponentsTable.subprojectId]?.value == subprojectId
                }
                if (subprojectSpecific.isNotEmpty()) return subprojectSpecific

                val projectSpecific = timeComponents.filter { component ->
                    component[SalaryComponentsTable.projectId]?.value == projectId &&
                        component[SalaryComponentsTable.subprojectId] == null
                }
                if (projectSpecific.isNotEmpty()) return projectSpecific

                return timeComponents.filter { component ->
                    component[SalaryComponentsTable.projectId] == null &&
                        component[SalaryComponentsTable.subprojectId] == null
                }
            }

            val projectNames = mutableMapOf<UUID, String>()
            val subprojectNames = mutableMapOf<UUID, String>()

            fun resolveProjectName(entry: ResultRow, projectId: UUID): String {
                val fromEntry = entry[TimeEntriesTable.projectName].trim()
                if (fromEntry.isNotBlank()) return fromEntry
                return projectNames.getOrPut(projectId) {
                    ProjectsTable.selectAll()
                        .where { ProjectsTable.id eq projectId }
                        .limit(1)
                        .singleOrNull()
                        ?.get(ProjectsTable.name)
                        .orEmpty()
                        .ifBlank { "Без проекта" }
                }
            }

            fun resolveSubprojectName(subprojectId: UUID?): String {
                if (subprojectId == null) return ""
                return subprojectNames.getOrPut(subprojectId) {
                    SubprojectsTable.selectAll()
                        .where { SubprojectsTable.id eq subprojectId }
                        .limit(1)
                        .singleOrNull()
                        ?.get(SubprojectsTable.name)
                        .orEmpty()
                }
            }

            val hourlyLines = mutableListOf<PayrollHourEntryDto>()
            val componentAccumulators =
                linkedMapOf<ComponentBreakdownKey, ComponentBreakdownAccumulator>()
            var piece = 0.0

            periodEntries.forEach { entry ->
                val applicable = applicableComponents(entry)
                if (applicable.isEmpty()) return@forEach

                val entryHours = entry[TimeEntriesTable.hours].toDouble()
                val projectId = entry[TimeEntriesTable.projectId].value
                val subprojectId = entry[TimeEntriesTable.subprojectId]?.value
                val projectName = resolveProjectName(entry, projectId)
                val subprojectName = resolveSubprojectName(subprojectId)

                // При одинаковом уровне сдельная ставка забирает запись целиком:
                // её часы не дублируются в почасовой части.
                val pieceComponents = applicable.filter {
                    it[SalaryComponentsTable.type] == "PIECE"
                }

                val selectedComponents = if (pieceComponents.isNotEmpty()) {
                    pieceComponents
                } else {
                    applicable.filter { it[SalaryComponentsTable.type] == "HOURLY" }
                }

                selectedComponents.forEach componentLoop@ { component ->
                    val type = component[SalaryComponentsTable.type]
                    val componentId = component[SalaryComponentsTable.id].value
                    val rate = if (type == "PIECE") {
                        component[SalaryComponentsTable.ratePerUnit] ?: 0.0
                    } else {
                        component[SalaryComponentsTable.ratePerHour] ?: 0.0
                    }
                    if (rate <= 0.0) return@componentLoop

                    val key = ComponentBreakdownKey(
                        componentId = componentId,
                        projectId = projectId,
                        subprojectId = subprojectId
                    )
                    val scope = when {
                        component[SalaryComponentsTable.subprojectId] != null -> "SUBPROJECT"
                        component[SalaryComponentsTable.projectId] != null -> "PROJECT"
                        else -> "GENERAL"
                    }
                    val accumulator = componentAccumulators.getOrPut(key) {
                        ComponentBreakdownAccumulator(
                            componentId = componentId,
                            type = type,
                            projectId = projectId,
                            projectName = projectName,
                            subprojectId = subprojectId,
                            subprojectName = subprojectName,
                            scope = scope,
                            rate = rate
                        )
                    }

                    accumulator.hours += entryHours
                    if (type == "PIECE") {
                        // Текущая модель сдельной оплаты: одна единица за рабочую запись.
                        accumulator.units += 1.0
                        accumulator.amount += rate
                        piece += rate
                    } else {
                        accumulator.amount += entryHours * rate
                    }
                }

                if (selectedComponents.any { it[SalaryComponentsTable.type] == "HOURLY" }) {
                    val hourlyRate = selectedComponents.sumOf {
                        it[SalaryComponentsTable.ratePerHour] ?: 0.0
                    }
                    if (hourlyRate > 0.0) {
                        hourlyLines += PayrollHourEntryDto(
                            id = entry[TimeEntriesTable.id].value.toString(),
                            date = entry[TimeEntriesTable.date].toString(),
                            hours = entryHours,
                            hourlyCost = hourlyRate,
                            amount = entryHours * hourlyRate,
                            comment = entry[TimeEntriesTable.comment].orEmpty()
                        )
                    }
                }
            }

            val componentBreakdown = componentAccumulators.values
                .map { line ->
                    PayrollComponentBreakdownDto(
                        componentId = line.componentId.toString(),
                        type = line.type,
                        projectId = line.projectId.toString(),
                        projectName = line.projectName,
                        subprojectId = line.subprojectId?.toString(),
                        subprojectName = line.subprojectName,
                        scope = line.scope,
                        hours = line.hours,
                        units = line.units,
                        rate = line.rate,
                        amount = line.amount
                    )
                }
                .sortedWith(
                    compareBy<PayrollComponentBreakdownDto> { it.projectName }
                        .thenBy { it.subprojectName }
                        .thenBy { it.type }
                )

            // Сохраняем прежний hourly-only формат для экранов и экспортов,
            // которые используют projectGroups как пояснение почасовой части.
            val hourlyLineById = hourlyLines.associateBy { it.id }

            val groupsByProject = periodEntries
                .mapNotNull { entry ->
                    hourlyLineById[entry[TimeEntriesTable.id].value.toString()]
                        ?.let { line -> entry to line }
                }
                .groupBy { it.first[TimeEntriesTable.projectId].value }

            val projectGroups = groupsByProject.map { (projectId, pairs) ->
                val firstEntry = pairs.first().first
                val projectName = resolveProjectName(firstEntry, projectId)

                val subGroups = pairs
                    .filter { it.first[TimeEntriesTable.subprojectId] != null }
                    .groupBy { it.first[TimeEntriesTable.subprojectId]!!.value }
                    .map { (subprojectId, subPairs) ->
                        val subprojectName = resolveSubprojectName(subprojectId)
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
                projectGroups = projectGroups,
                componentBreakdown = componentBreakdown
            )
        }
    }
}
