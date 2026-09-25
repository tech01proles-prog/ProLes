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
    val month: Int
)

private val payrollJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

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

private fun payrollTaxInclusiveCost(
    earnedAmount: Double,
    isRemote: Boolean
): Double =
    if (isRemote) {
        earnedAmount / 0.87
    } else {
        earnedAmount * 1.37
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
            val effectiveFrom = runCatching {
                kotlinx.datetime.LocalDate.parse(body.effectiveFrom)  // ✅ Прямой доступ к полю DTO
            }.getOrNull() ?: run {
                // 🔥 Fallback: берём текущую дату через java.time (всегда работает на JVM)
                val today = java.time.LocalDate.now()
                kotlinx.datetime.LocalDate(today.year, today.monthValue, today.dayOfMonth)
            }
            val effectiveTo = body.effectiveTo?.let {  // ✅ Прямой доступ к полю DTO
                runCatching { kotlinx.datetime.LocalDate.parse(it) }.getOrNull()
            }

            val componentId = UUID.randomUUID()
            transaction {
                SalaryComponentsTable.insert {
                    it[SalaryComponentsTable.id] = componentId
                    it[SalaryComponentsTable.userId] = userId
                    it[SalaryComponentsTable.type] = body.type
                    it[SalaryComponentsTable.amount] = body.amount
                    if (projectId != null) it[SalaryComponentsTable.projectId] = projectId
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
            val session = call.checkPayrollSession() ?: return@delete
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

            val session = call.checkPayrollSession() ?: return@put

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
            val effectiveFrom = runCatching {
                kotlinx.datetime.LocalDate.parse(body.effectiveFrom)
            }.getOrNull() ?: run {
                val today = java.time.LocalDate.now()
                kotlinx.datetime.LocalDate(today.year, today.monthValue, today.dayOfMonth)
            }
            val effectiveTo = body.effectiveTo?.let {
                runCatching { kotlinx.datetime.LocalDate.parse(it) }.getOrNull()
            }

            transaction {
                SalaryComponentsTable.update({ SalaryComponentsTable.id eq componentId }) {
                    it[SalaryComponentsTable.userId] = userId
                    it[SalaryComponentsTable.type] = body.type
                    it[SalaryComponentsTable.amount] = body.amount
                    if (projectId != null) it[SalaryComponentsTable.projectId] = projectId
                    else it[SalaryComponentsTable.projectId] = null
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

            val body = try { call.receive<CalculateSalaryRequest>() }
            catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
            }


            val userId = runCatching { UUID.fromString(body.userId) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid userId")
            val year = body.year
            val month = body.month

            val breakdown = com.proles.server.services.SalaryCalculator.calculate(userId, year, month)

            // Сохраняем расчёт
            val recordId = UUID.randomUUID()
            transaction {
                // Удаляем старый расчёт если есть
                SalaryRecordsTable.deleteWhere {
                    (SalaryRecordsTable.userId eq userId) and
                            (SalaryRecordsTable.periodYear eq year) and
                            (SalaryRecordsTable.periodMonth eq month)
                }
                SalaryRecordsTable.insert {
                    it[SalaryRecordsTable.id] = recordId
                    it[SalaryRecordsTable.userId] = userId
                    it[periodYear] = year
                    it[periodMonth] = month
                    it[fixedAmount] = breakdown.fixed
                    it[pieceAmount] = breakdown.piece
                    it[hourlyAmount] = breakdown.hourly
                    it[bonusAmount] = breakdown.bonus
                    it[totalAmount] = breakdown.total
                    it[status] = "draft"
                    it[calculatedAt] = System.currentTimeMillis()
                }
            }


            // ✅ ПРАВИЛЬНО: Возвращаем data class
            call.respond(HttpStatusCode.Created, SalaryBreakdownResponse(
                id = recordId.toString(),
                fixed = breakdown.fixed,
                piece = breakdown.piece,
                hourly = breakdown.hourly,
                bonus = breakdown.bonus,
                penalty = breakdown.penalty,
                total = breakdown.total
            ))
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
                            "total" to row[SalaryRecordsTable.totalAmount],
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
            if (!call.checkPermission(Permission.PAYROLL, "edit")) return@put
            val recordId = runCatching {
                UUID.fromString(call.parameters["recordId"])
            }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)

            val body = try { call.receive<Map<String, String>>() }
            catch (e: Exception) { return@put call.respond(HttpStatusCode.BadRequest) }

            val status = body["status"] ?: return@put call.respond(HttpStatusCode.BadRequest)

            transaction {
                SalaryRecordsTable.update({ SalaryRecordsTable.id eq recordId }) {
                    it[SalaryRecordsTable.status] = status
                    if (status == "paid") {
                        it[paidAt] = System.currentTimeMillis()
                    }
                }
            }
            call.respond(HttpStatusCode.OK)
        }
    }
}