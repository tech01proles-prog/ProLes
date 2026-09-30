package com.proles.server.routes

import com.proles.server.config.NotificationService
import com.proles.server.config.PermissionMiddleware.checkPermission
import com.proles.server.config.SessionManager
import com.proles.server.data.*
import com.proles.server.model.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDate as KtLocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate as JavaLocalDate
import java.util.UUID

@Serializable
data class PayrollExportEmployeeDto(
    val userId: String,
    val name: String,
    val position: String,
    val role: String,
    val totalHours: Double,
    val workDays: Int,
    val fixed: Double,
    val piece: Double,
    val hourly: Double,
    val bonus: Double,
    val salaryTotal: Double,
    val expensesTotal: Double,
    val expensesByCurrency: Map<String, Double>,
    val tripsCount: Int
)

@Serializable
data class PayrollExportResponse(
    val year: Int,
    val month: Int,
    val generatedAt: Long,
    val employees: List<PayrollExportEmployeeDto>
)

@Serializable
data class CalculateSalaryRequest(
    val userId: String,
    val year: Int,
    val month: Int,
    val withholdingAmount: Double = 0.0,
    val withholdingRepaymentAmount: Double = 0.0,
    val comment: String = "",
    val idempotencyKey: String? = null
)

@Serializable
data class PayrollBalanceSummaryDto(
  val userId: String,
  val balance: Double,
  val transactions: List<EmployeeBalanceTransactionDto> = emptyList()
)

@Serializable
data class PayrollAdjustmentBody(
  val userId: String,
  val year: Int,
  val month: Int,
  val withholdingAmount: Double = 0.0,
  val withholdingRepaymentAmount: Double = 0.0,
  val comment: String = ""
)


private suspend fun ApplicationCall.checkPayrollSession(
    vararg allowedRoles: String
): SessionManager.Session? {
    val token = request.headers["X-Session-Token"]
    val session = SessionManager.validate(token)

    if (session == null) {
        respond(HttpStatusCode.Unauthorized, "Session expired")
        return null
    }

    if (allowedRoles.isNotEmpty() && session.role !in allowedRoles) {
        respond(HttpStatusCode.Forbidden, "Access denied")
        return null
    }

    return session
}

private val payrollStatuses =
    setOf("draft", "approved", "paid")

private val payrollStatusTransitions = mapOf("draft" to setOf("approved"), "approved" to setOf("draft", "paid"), "paid" to emptySet())

private fun validPayrollPeriod(
    year: Int,
    month: Int
): Boolean =
    year in 2000..2100 && month in 1..12

private fun parsePayrollDate(
    value: String?
): KtLocalDate? =
    value
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let {
            runCatching { KtLocalDate.parse(it) }.getOrNull()
        }

private fun currentEmployeeBalance(userId: UUID): Double = EmployeeBalanceTransactionsTable.selectAll().where { EmployeeBalanceTransactionsTable.userId eq userId }.sumOf { it[EmployeeBalanceTransactionsTable.amount].toDouble() }

private fun reverseSalaryBalanceTransactions(
    salaryRecordId: UUID,
    userId: UUID,
    createdBy: UUID,
    reason: String
) {
    val alreadyReversedIds = EmployeeBalanceTransactionsTable
        .selectAll()
        .where {
            EmployeeBalanceTransactionsTable.salaryRecordId eq salaryRecordId
        }
        .mapNotNull {
            it[EmployeeBalanceTransactionsTable.reversedTransactionId]
        }
        .toSet()

    val originals = EmployeeBalanceTransactionsTable
        .selectAll()
        .where {
            (EmployeeBalanceTransactionsTable.salaryRecordId eq salaryRecordId) and
                EmployeeBalanceTransactionsTable.reversedTransactionId.isNull()
        }
        .filter {
            it[EmployeeBalanceTransactionsTable.id].value !in alreadyReversedIds
        }

    val now = System.currentTimeMillis()

    originals.forEachIndexed { index, original ->
        val originalId =
            original[EmployeeBalanceTransactionsTable.id].value
        val originalType =
            original[EmployeeBalanceTransactionsTable.transactionType]
        val originalAmount =
            original[EmployeeBalanceTransactionsTable.amount]

        EmployeeBalanceTransactionsTable.insert {
            it[id] = UUID.randomUUID()
            it[EmployeeBalanceTransactionsTable.userId] = userId
            it[EmployeeBalanceTransactionsTable.salaryRecordId] =
                salaryRecordId
            it[transactionType] = "REVERSAL"
            it[amount] = originalAmount.negate()
            it[comment] =
                "$reason: $originalType, операция $originalId"
            it[EmployeeBalanceTransactionsTable.createdBy] = createdBy
            it[createdAt] = now + index
            it[reversedTransactionId] = originalId
            it[idempotencyKey] =
                "salary:$salaryRecordId:reverse:$originalId"
        }
    }
}

private fun balanceTransactionDto(row: ResultRow): EmployeeBalanceTransactionDto {
    val creatorId = row[EmployeeBalanceTransactionsTable.createdBy].value
    val creatorName = UsersTable.selectAll().where { UsersTable.id eq creatorId }.limit(1).singleOrNull()?.get(UsersTable.name).orEmpty()
    return EmployeeBalanceTransactionDto(
        id = row[EmployeeBalanceTransactionsTable.id].value.toString(),
        userId = row[EmployeeBalanceTransactionsTable.userId].value.toString(),
        salaryRecordId = row[EmployeeBalanceTransactionsTable.salaryRecordId]?.value?.toString(),
        transactionType = row[EmployeeBalanceTransactionsTable.transactionType],
        amount = row[EmployeeBalanceTransactionsTable.amount].toDouble(),
        comment = row[EmployeeBalanceTransactionsTable.comment],
        createdBy = creatorId.toString(),
        createdByName = creatorName,
        createdAt = row[EmployeeBalanceTransactionsTable.createdAt],
        reversedTransactionId = row[EmployeeBalanceTransactionsTable.reversedTransactionId]?.toString()
    )
}

internal fun Route.payrollRoutes() {
    route("/api/v1/payroll") {
        // Сотрудники для управления зарплатой. Доступ определяется правом PAYROLL.view.
        get("/users") {
            if (!call.checkPermission(Permission.PAYROLL, "view")) return@get
            val users = transaction {
                UsersTable.selectAll()
                    .orderBy(UsersTable.lastName to SortOrder.ASC, UsersTable.firstName to SortOrder.ASC)
                    .map { row ->
                        UserDto(
                            id = row[UsersTable.id].value.toString(),
                            lastName = row[UsersTable.lastName],
                            firstName = row[UsersTable.firstName],
                            middleName = row[UsersTable.middleName],
                            name = row[UsersTable.name],
                            login = row[UsersTable.login],
                            email = row[UsersTable.email],
                            role = row[UsersTable.role],
                            position = row[UsersTable.position] ?: "",
                            defaultRateType = row[UsersTable.defaultRateType],
                            defaultRate = row[UsersTable.defaultRate],
                            defaultCurrency = row[UsersTable.defaultCurrency],
                            phone = row[UsersTable.phone],
                            telegramUsername = row[UsersTable.telegramUsername],
                            birthDate = row[UsersTable.birthDate]?.toString(),
                            positionId = row[UsersTable.positionId]?.value?.toString()
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, users)
        }

        get("/balance/{userId}") {
            if (!call.checkPermission(Permission.PAYROLL, "view")) return@get
            val userId = runCatching { UUID.fromString(call.parameters["userId"]) }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid userId")
            val result = transaction {
                val transactions = EmployeeBalanceTransactionsTable.selectAll().where { EmployeeBalanceTransactionsTable.userId eq userId }.orderBy(EmployeeBalanceTransactionsTable.createdAt to SortOrder.DESC).map(::balanceTransactionDto)
                PayrollBalanceSummaryDto(userId.toString(), transactions.sumOf { it.amount }, transactions)
            }
            call.respond(HttpStatusCode.OK, result)
        }

        // Получить все компоненты зарплаты (для CostCalculationPage)
        get("/components/all") {
            if (!call.checkPermission(Permission.PAYROLL, "view")) return@get
            val components = transaction {
                SalaryComponentsTable.selectAll()
                    .orderBy(SalaryComponentsTable.type to SortOrder.ASC)
                    .map { row ->
                        SalaryComponentDto(
                            id = row[SalaryComponentsTable.id].value.toString(),
                            userId = row[SalaryComponentsTable.userId].value.toString(),
                            type = row[SalaryComponentsTable.type],
                            amount = row[SalaryComponentsTable.amount],
                            projectId = row[SalaryComponentsTable.projectId]?.value?.toString(),
                            subprojectId = row[SalaryComponentsTable.subprojectId]?.value?.toString(),
                            subprojectName = row[SalaryComponentsTable.subprojectId]?.value?.let { subprojectId -> SubprojectsTable.selectAll().where { SubprojectsTable.id eq subprojectId }.limit(1).singleOrNull()?.get(SubprojectsTable.name) }.orEmpty(),
                            ratePerHour = row[SalaryComponentsTable.ratePerHour],
                            ratePerUnit = row[SalaryComponentsTable.ratePerUnit],
                            description = row[SalaryComponentsTable.description],
                            effectiveFrom = row[SalaryComponentsTable.effectiveFrom].toString(),
                            effectiveTo = row[SalaryComponentsTable.effectiveTo]?.toString(),
                            isActive = row[SalaryComponentsTable.isActive]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, components)
        }

        // Получить компоненты зарплаты пользователя
        get("/components/{userId}") {
            if (!call.checkPermission(Permission.PAYROLL, "view")) return@get
            val userId = runCatching {
                UUID.fromString(call.parameters["userId"])
            }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val components = transaction {
                SalaryComponentsTable.selectAll()
                    .where { SalaryComponentsTable.userId eq userId }
                    .orderBy(SalaryComponentsTable.type to SortOrder.ASC)
                    .map { row ->
                        SalaryComponentDto(             // ✅ Типизированный DTO
                            id = row[SalaryComponentsTable.id].value.toString(),
                            userId = row[SalaryComponentsTable.userId].value.toString(),
                            type = row[SalaryComponentsTable.type],
                            amount = row[SalaryComponentsTable.amount],
                            projectId = row[SalaryComponentsTable.projectId]?.value?.toString(),
                            subprojectId = row[SalaryComponentsTable.subprojectId]?.value?.toString(),
                            subprojectName = row[SalaryComponentsTable.subprojectId]?.value?.let { subprojectId -> SubprojectsTable.selectAll().where { SubprojectsTable.id eq subprojectId }.limit(1).singleOrNull()?.get(SubprojectsTable.name) }.orEmpty(),
                            ratePerHour = row[SalaryComponentsTable.ratePerHour],
                            ratePerUnit = row[SalaryComponentsTable.ratePerUnit],
                            description = row[SalaryComponentsTable.description],
                            effectiveFrom = row[SalaryComponentsTable.effectiveFrom].toString(),
                            effectiveTo = row[SalaryComponentsTable.effectiveTo]?.toString(),
                            isActive = row[SalaryComponentsTable.isActive]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, components)
        }

        // Добавить компонент зарплаты
        post("/components") {
            if (!call.checkPermission(Permission.PAYROLL, "create")) return@post

            val body = try { call.receive<SalaryComponentDto>() }
            catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
            }


            val userId = runCatching { UUID.fromString(body.userId) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid userId")

            val projectId = body.projectId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val subprojectId = body.subprojectId?.takeIf(String::isNotBlank)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: body.subprojectId?.takeIf(String::isNotBlank)?.let { return@post call.respond(HttpStatusCode.BadRequest, "Invalid subprojectId") }
            val effectiveFrom = parsePayrollDate(body.effectiveFrom)
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid effectiveFrom"
                )

            val effectiveTo = if (body.effectiveTo.isNullOrBlank()) {
                null
            } else {
                parsePayrollDate(body.effectiveTo)
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        "Invalid effectiveTo"
                    )
            }

            if (effectiveTo != null && effectiveTo < effectiveFrom) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "effectiveTo precedes effectiveFrom"
                )
            }

            if (!body.amount.isFinite() || body.amount < 0.0) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid amount"
                )
            }

            if (subprojectId != null && projectId == null) return@post call.respond(HttpStatusCode.BadRequest, "Project is required for subproject")
            if (subprojectId != null && transaction { SubprojectsTable.selectAll().where { (SubprojectsTable.id eq subprojectId) and (SubprojectsTable.projectId eq requireNotNull(projectId)) }.limit(1).none() }) return@post call.respond(HttpStatusCode.BadRequest, "Subproject does not belong to project")

            val componentId = UUID.randomUUID()
            transaction {
                SalaryComponentsTable.insert {
                    it[SalaryComponentsTable.id] = componentId
                    it[SalaryComponentsTable.userId] = userId
                    it[SalaryComponentsTable.type] = body.type
                    it[SalaryComponentsTable.amount] = body.amount
                    if (projectId != null) it[SalaryComponentsTable.projectId] = projectId
                    if (subprojectId != null) it[SalaryComponentsTable.subprojectId] = subprojectId
                    if (body.ratePerHour != null) it[SalaryComponentsTable.ratePerHour] = body.ratePerHour
                    if (body.ratePerUnit != null) it[SalaryComponentsTable.ratePerUnit] = body.ratePerUnit
                    it[SalaryComponentsTable.description] = body.description
                    it[SalaryComponentsTable.effectiveFrom] = effectiveFrom
                    if (effectiveTo != null) it[SalaryComponentsTable.effectiveTo] = effectiveTo
                }
            }

            call.respond(HttpStatusCode.Created, mapOf("id" to componentId.toString()))
        }

        // 🗑 Удалить компонент зарплаты
        delete("/components/{componentId}") {
            if (!call.checkPermission(Permission.PAYROLL, "delete")) return@delete
            val componentId = runCatching {
                UUID.fromString(call.parameters["componentId"])
            }.getOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid componentId")

            // Проверяем, что компонент принадлежит пользователю (или текущий пользователь — админ)
            val componentOwner = transaction {
                SalaryComponentsTable.selectAll()
                    .where { SalaryComponentsTable.id eq componentId }
                    .singleOrNull()?.get(SalaryComponentsTable.userId)?.value
            }

            if (componentOwner == null) {
                return@delete call.respond(HttpStatusCode.NotFound, "Component not found")
            }

            // PAYROLL.delete уже проверен выше: пользователь с этим правом может управлять
            // компонентами выбранного сотрудника, так же как своими.

            transaction {
                SalaryComponentsTable.deleteWhere { SalaryComponentsTable.id eq componentId }
            }

            call.respond(HttpStatusCode.NoContent)
        }

        // ✏️ Редактировать компонент зарплаты
        put("/components/{componentId}") {
            if (!call.checkPermission(Permission.PAYROLL, "edit")) return@put

            val componentId = runCatching {
                UUID.fromString(call.parameters["componentId"])
            }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid componentId")

            val body = try { call.receive<SalaryComponentDto>() }
            catch (e: Exception) {
                return@put call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
            }

            // Проверяем, что компонент принадлежит пользователю (или текущий пользователь — админ)
            val componentOwner = transaction {
                SalaryComponentsTable.selectAll()
                    .where { SalaryComponentsTable.id eq componentId }
                    .singleOrNull()?.get(SalaryComponentsTable.userId)?.value
            }

            if (componentOwner == null) {
                return@put call.respond(HttpStatusCode.NotFound, "Component not found")
            }

            // PAYROLL.edit уже проверен выше: пользователь с этим правом может изменять
            // компоненты выбранного сотрудника.

            val userId = runCatching { UUID.fromString(body.userId) }.getOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid userId")

            val projectId = body.projectId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val subprojectId = body.subprojectId?.takeIf(String::isNotBlank)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: body.subprojectId?.takeIf(String::isNotBlank)?.let { return@put call.respond(HttpStatusCode.BadRequest, "Invalid subprojectId") }
            val effectiveFrom = parsePayrollDate(body.effectiveFrom)
                ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid effectiveFrom"
                )

            val effectiveTo = if (body.effectiveTo.isNullOrBlank()) {
                null
            } else {
                parsePayrollDate(body.effectiveTo)
                    ?: return@put call.respond(
                        HttpStatusCode.BadRequest,
                        "Invalid effectiveTo"
                    )
            }

            if (effectiveTo != null && effectiveTo < effectiveFrom) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "effectiveTo precedes effectiveFrom"
                )
            }

            if (!body.amount.isFinite() || body.amount < 0.0) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid amount"
                )
            }

            if (subprojectId != null && projectId == null) return@put call.respond(HttpStatusCode.BadRequest, "Project is required for subproject")

            if (subprojectId != null && transaction { SubprojectsTable.selectAll().where { (SubprojectsTable.id eq subprojectId) and (SubprojectsTable.projectId eq requireNotNull(projectId)) }.limit(1).none() }) return@put call.respond(HttpStatusCode.BadRequest, "Subproject does not belong to project")

            transaction {
                SalaryComponentsTable.update({ SalaryComponentsTable.id eq componentId }) {
                    it[SalaryComponentsTable.userId] = userId
                    it[SalaryComponentsTable.type] = body.type
                    it[SalaryComponentsTable.amount] = body.amount
                    if (projectId != null) it[SalaryComponentsTable.projectId] = projectId
                    else it[SalaryComponentsTable.projectId] = null
                    if (subprojectId != null) it[SalaryComponentsTable.subprojectId] = subprojectId
                    else it[SalaryComponentsTable.subprojectId] = null
                    if (body.ratePerHour != null) it[SalaryComponentsTable.ratePerHour] = body.ratePerHour
                    else it[SalaryComponentsTable.ratePerHour] = null
                    if (body.ratePerUnit != null) it[SalaryComponentsTable.ratePerUnit] = body.ratePerUnit
                    else it[SalaryComponentsTable.ratePerUnit] = null
                    it[SalaryComponentsTable.description] = body.description
                    it[SalaryComponentsTable.effectiveFrom] = effectiveFrom
                    if (effectiveTo != null) it[SalaryComponentsTable.effectiveTo] = effectiveTo
                    else it[SalaryComponentsTable.effectiveTo] = null
                    it[SalaryComponentsTable.isActive] = body.isActive
                }
            }

            call.respond(HttpStatusCode.OK, mapOf("id" to componentId.toString()))
        }

        // Рассчитать зарплату за период
        post("/calculate") {
            if (!call.checkPermission(Permission.PAYROLL, "create")) return@post
            val session = call.checkPayrollSession() ?: return@post
            val body = runCatching { call.receive<CalculateSalaryRequest>() }.getOrElse { return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${it.message}") }
            if (!validPayrollPeriod(body.year, body.month)) return@post call.respond(HttpStatusCode.BadRequest, "Invalid payroll period")
            if (!body.withholdingAmount.isFinite() || body.withholdingAmount < 0.0 || !body.withholdingRepaymentAmount.isFinite() || body.withholdingRepaymentAmount < 0.0) return@post call.respond(HttpStatusCode.BadRequest, "Invalid withholding amount")

            val userId = runCatching { UUID.fromString(body.userId) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid userId")
            val creatorId = session.userId
            val breakdown = com.proles.server.services.SalaryCalculator.calculate(userId, body.year, body.month)
            val gross = breakdown.fixed + breakdown.piece + breakdown.hourly + breakdown.bonus
            val availableBalance = transaction { currentEmployeeBalance(userId) }
            val repayment = body.withholdingRepaymentAmount.coerceAtMost(availableBalance.coerceAtLeast(0.0))
            val total = (gross - breakdown.penalty - body.withholdingAmount + repayment).coerceAtLeast(0.0)
            val response = transaction {
                val oldRecord = SalaryRecordsTable
                    .selectAll()
                    .where {
                        (SalaryRecordsTable.userId eq userId) and
                            (SalaryRecordsTable.periodYear eq body.year) and
                            (SalaryRecordsTable.periodMonth eq body.month)
                    }
                    .limit(1)
                    .singleOrNull()

                val recordId =
                    oldRecord?.get(SalaryRecordsTable.id)?.value
                        ?: UUID.randomUUID()

                if (oldRecord == null) {
                    SalaryRecordsTable.insert {
                        it[id] = recordId
                        it[SalaryRecordsTable.userId] = userId
                        it[periodYear] = body.year
                        it[periodMonth] = body.month
                        it[fixedAmount] = breakdown.fixed
                        it[pieceAmount] = breakdown.piece
                        it[hourlyAmount] = breakdown.hourly
                        it[bonusAmount] = breakdown.bonus
                        it[penaltyAmount] = breakdown.penalty
                        it[withholdingAmount] = body.withholdingAmount
                        it[withholdingRepaymentAmount] = repayment
                        it[grossAmount] = gross
                        it[totalAmount] = total
                        it[status] = "draft"
                        it[calculatedAt] = System.currentTimeMillis()
                        it[notes] = body.comment.trim()
                    }
                } else {
                    reverseSalaryBalanceTransactions(
                        salaryRecordId = recordId,
                        userId = userId,
                        createdBy = creatorId,
                        reason = "Сторнирование перед перерасчётом " +
                            "${body.month}.${body.year}"
                    )

                    SalaryRecordsTable.update({
                        SalaryRecordsTable.id eq recordId
                    }) {
                        it[fixedAmount] = breakdown.fixed
                        it[pieceAmount] = breakdown.piece
                        it[hourlyAmount] = breakdown.hourly
                        it[bonusAmount] = breakdown.bonus
                        it[penaltyAmount] = breakdown.penalty
                        it[withholdingAmount] = body.withholdingAmount
                        it[withholdingRepaymentAmount] = repayment
                        it[grossAmount] = gross
                        it[totalAmount] = total
                        it[status] = "draft"
                        it[calculatedAt] = System.currentTimeMillis()
                        it[paidAt] = null
                        it[notes] = body.comment.trim()
                    }
                }

                val now = System.currentTimeMillis()
                if (body.withholdingAmount > 0.0) EmployeeBalanceTransactionsTable.insert {
                    it[id] = UUID.randomUUID()
                    it[EmployeeBalanceTransactionsTable.userId] = userId
                    it[salaryRecordId] = recordId
                    it[transactionType] = "WITHHOLDING"
                    it[amount] = body.withholdingAmount.toBigDecimal()
                    it[comment] = body.comment.trim().ifBlank { "Удержание за ${body.month}.${body.year}" }
                    it[createdBy] = creatorId
                    it[createdAt] = now
                    it[idempotencyKey] ="salary:$recordId:$now:withholding"
                }
                if (repayment > 0.0) EmployeeBalanceTransactionsTable.insert {
                    it[id] = UUID.randomUUID()
                    it[EmployeeBalanceTransactionsTable.userId] = userId
                    it[salaryRecordId] = recordId
                    it[transactionType] = "REPAYMENT"
                    it[amount] = repayment.toBigDecimal().negate()
                    it[comment] = body.comment.trim().ifBlank { "Погашение удержания за ${body.month}.${body.year}" }
                    it[createdBy] = creatorId
                    it[createdAt] = now + 1
                    it[idempotencyKey] = "salary:$recordId:$now:repayment"
                }

                val balance = currentEmployeeBalance(userId)
                SalaryBreakdownResponse(
                    id = recordId.toString(),
                    fixed = breakdown.fixed,
                    piece = breakdown.piece,
                    hourly = breakdown.hourly,
                    bonus = breakdown.bonus,
                    penalty = breakdown.penalty,
                    withholding = body.withholdingAmount,
                    withholdingRepayment = repayment,
                    gross = gross,
                    total = total,
                    balance = balance
                )
            }

            NotificationService.notifyUsers(
                targetUserIds = listOf(userId),
                senderUserId = creatorId,
                type = "PAYROLL_UPDATED",
                title = "💰 Расчёт зарплаты",
                message = "Подготовлен расчёт за ${body.month}.${body.year}: ${"%.2f".format(response.total)}",
                payload = "{\"salaryRecordId\":\"${response.id}\",\"year\":${body.year},\"month\":${body.month}}",
                preferenceKey = "payroll"
            )

            call.respond(HttpStatusCode.Created, response)
        }

        // Получить все расчёты зарплат
        get("/records") {
            if (!call.checkPermission(Permission.PAYROLL, "view")) return@get
            val records = transaction {
                (SalaryRecordsTable innerJoin UsersTable)
                    .selectAll()
                    .orderBy(SalaryRecordsTable.periodYear to SortOrder.DESC, SalaryRecordsTable.periodMonth to SortOrder.DESC)
                    .map { row ->
                        mapOf(
                            "id" to row[SalaryRecordsTable.id].value.toString(),
                            "userId" to row[SalaryRecordsTable.userId].value.toString(),
                            "userName" to row[UsersTable.name],
                            "year" to row[SalaryRecordsTable.periodYear],
                            "month" to row[SalaryRecordsTable.periodMonth],
                            "fixed" to row[SalaryRecordsTable.fixedAmount],
                            "piece" to row[SalaryRecordsTable.pieceAmount],
                            "hourly" to row[SalaryRecordsTable.hourlyAmount],
                            "bonus" to row[SalaryRecordsTable.bonusAmount],
                            "penalty" to row[SalaryRecordsTable.penaltyAmount],
                            "withholding" to row[SalaryRecordsTable.withholdingAmount],
                            "withholdingRepayment" to row[SalaryRecordsTable.withholdingRepaymentAmount],
                            "gross" to row[SalaryRecordsTable.grossAmount],
                            "total" to row[SalaryRecordsTable.totalAmount],
                            "taxInclusiveCost" to row[SalaryRecordsTable.taxInclusiveCost],
                            "status" to row[SalaryRecordsTable.status]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, records)
        }
        // 📊 ЭКСПОРТ: агрегированные данные по всем сотрудникам за период
        get("/export") {
            if (!call.checkPermission(Permission.PAYROLL, "view")) return@get
            val yearParam = call.request.queryParameters["year"]
            val monthParam = call.request.queryParameters["month"]

            val year = yearParam?.toIntOrNull() ?: java.time.LocalDate.now().year
            val month = monthParam?.toIntOrNull() ?: java.time.LocalDate.now().monthValue

            if (!validPayrollPeriod(year, month)) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid payroll period"
                )
            }

            val startDate = kotlinx.datetime.LocalDate(year, month, 1)
            val daysInMonth = java.time.YearMonth.of(year, month).lengthOfMonth()
            val endDate = kotlinx.datetime.LocalDate(year, month, daysInMonth)

            val exportData: List<PayrollExportEmployeeDto> = transaction {
                val users = UsersTable.selectAll()
                    .orderBy(UsersTable.lastName to SortOrder.ASC)
                    .toList()

                users.map { row ->
                    val userId = row[UsersTable.id].value
                    val userName = row[UsersTable.name]
                    val position = row[UsersTable.position] ?: ""
                    val role = row[UsersTable.role]

                    // Часы за месяц
                    val entries = TimeEntriesTable.selectAll()
                        .where {
                            (TimeEntriesTable.userId eq userId) and
                                    (TimeEntriesTable.date greaterEq startDate) and
                                    (TimeEntriesTable.date lessEq endDate)
                        }.toList()
                    val totalHours = entries.sumOf { it[TimeEntriesTable.hours].toDouble() }
                    val workDays = entries.map { it[TimeEntriesTable.date] }.distinct().size

                    // Расходы за месяц
                    val expenses = ExpensesTable.selectAll()
                        .where {
                            (ExpensesTable.userId eq userId) and
                                    (ExpensesTable.date greaterEq startDate) and
                                    (ExpensesTable.date lessEq endDate)
                        }.toList()
                    val totalExpenses = expenses.sumOf { it[ExpensesTable.amount] }
                    val expensesByCurrency: Map<String, Double> = expenses
                        .groupBy { it[ExpensesTable.currency] }
                        .mapValues { (_, list) -> list.sumOf { it[ExpensesTable.amount] } }

                    // Командировки
                    val trips = BusinessTripsTable.selectAll()
                        .where {
                            (BusinessTripsTable.userId eq userId) and
                                    (BusinessTripsTable.date greaterEq startDate) and
                                    (BusinessTripsTable.date lessEq endDate)
                        }.toList()

                    // Расчёт зарплаты
                    val breakdown = com.proles.server.services.SalaryCalculator.calculate(userId, year, month)

                    PayrollExportEmployeeDto(
                        userId = userId.toString(),
                        name = userName,
                        position = position,
                        role = role,
                        totalHours = totalHours,
                        workDays = workDays,
                        fixed = breakdown.fixed,
                        piece = breakdown.piece,
                        hourly = breakdown.hourly,
                        bonus = breakdown.bonus,
                        salaryTotal = breakdown.total,
                        expensesTotal = totalExpenses,
                        expensesByCurrency = expensesByCurrency,
                        tripsCount = trips.size
                    )
                }
            }


            // ✅ Типизированный ответ
            call.respond(HttpStatusCode.OK, PayrollExportResponse(
                year = year,
                month = month,
                generatedAt = System.currentTimeMillis(),
                employees = exportData
            ))
        }

        // Обновить статус (approved/paid)
        put("/records/{recordId}/status") {
            val session = call.checkPayrollSession() ?: return@put
            if (!call.checkPermission(Permission.PAYROLL, "edit")) return@put
            val recordId = runCatching {
                UUID.fromString(call.parameters["recordId"])
            }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)

            val body = try { call.receive<Map<String, String>>() }
            catch (e: Exception) { return@put call.respond(HttpStatusCode.BadRequest) }

            val status = body["status"]
                ?.trim()
                ?.lowercase()
                ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "status required"
                )

            if (status !in payrollStatuses) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Unsupported status"
                )
            }

            val currentStatus = transaction {
                SalaryRecordsTable
                    .selectAll()
                    .where { SalaryRecordsTable.id eq recordId }
                    .limit(1)
                    .singleOrNull()
                    ?.get(SalaryRecordsTable.status)
                    ?.lowercase()
            } ?: return@put call.respond(
                HttpStatusCode.NotFound,
                "Salary record not found"
            )

            if (status == currentStatus) {
                return@put call.respond(HttpStatusCode.OK)
            }

            if (status !in payrollStatusTransitions[currentStatus].orEmpty()) {
                return@put call.respond(
                    HttpStatusCode.Conflict,
                    "Invalid payroll status transition"
                )
            }

            val affected = transaction {
                SalaryRecordsTable.update({
                    SalaryRecordsTable.id eq recordId
                }) {
                    it[SalaryRecordsTable.status] = status
                    it[paidAt] = if (status == "paid") {
                        System.currentTimeMillis()
                    } else {
                        null
                    }
                }
            }

            if (affected == 0) {
                call.respond(
                    HttpStatusCode.NotFound,
                    "Salary record not found"
                )
            } else {
                val employeeId = transaction {
                    SalaryRecordsTable
                        .selectAll()
                        .where { SalaryRecordsTable.id eq recordId }
                        .single()[SalaryRecordsTable.userId].value
                }
                val statusLabel = when (status) {
                    "approved" -> "утверждён"
                    "paid" -> "выплачен"
                    "draft" -> "возвращён в черновик"
                    else -> status
                }
                NotificationService.notifyUsers(
                    targetUserIds = listOf(employeeId),
                    senderUserId = session.userId,
                    type = "PAYROLL_UPDATED",
                    title = "💰 Изменение зарплаты",
                    message = "Расчёт зарплаты за запись $recordId $statusLabel",
                    payload = "{\"salaryRecordId\":\"$recordId\",\"status\":\"$status\"}",
                    preferenceKey = "payroll"
                )
                call.respond(HttpStatusCode.OK)
            }

        }
    }
}