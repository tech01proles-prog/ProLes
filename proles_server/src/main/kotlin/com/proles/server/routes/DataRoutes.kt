package com.proles.server.routes

import com.proles.server.config.SessionManager
import com.proles.server.data.*
import com.proles.server.model.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.datetime.LocalDate as KtLocalDate
import java.time.LocalDate as JavaLocalDate
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import at.favre.lib.crypto.bcrypt.BCrypt
import io.ktor.server.request.*
import io.ktor.http.content.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.proles.server.config.FirebaseService
import com.proles.server.config.NotificationService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receive
import com.proles.server.config.TelegramService
import java.util.Base64
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import com.proles.server.config.PermissionMiddleware
import com.proles.server.config.PermissionMiddleware.checkPermission  //  extension-функция
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList  // 
import org.jetbrains.exposed.dao.id.EntityID  //  для работы с ID


@Serializable
data class NotificationPreferencesResponseDto(
    val tripEnabled: Boolean,
    val vacationEnabled: Boolean,
    val dayoffEnabled: Boolean,
    val expenseEnabled: Boolean,
    val payrollEnabled: Boolean,
    val ticketEnabled: Boolean,
    val tripVisibleToAll: Boolean,
    val tripTelegramBroadcast: Boolean,
    val tripChangeEnabled: Boolean,
    val vacationDecisionEnabled: Boolean,
    val expenseCreatedEnabled: Boolean,
    val ticketReceiptEnabled: Boolean,
    val telegramEnabled: Boolean,
    val telegramLinked: Boolean,
    val telegramLinkCode: String?,
    val emailEnabled: Boolean,
    val email: String
)

@Serializable
data class NotificationPreferencesUpdateResponseDto(
    val telegramLinkCode: String?,
    val telegramEnabled: Boolean
)

@Serializable
data class ExpenseReceiptDto(
    val id: String,
    val expenseId: String,
    val url: String,
    val fileName: String,
    val uploadedAt: Long
)

@Serializable
data class UserPermissionOverrideDto(
    val permission: String,
    val canView: Boolean? = null,
    val canCreate: Boolean? = null,
    val canEdit: Boolean? = null,
    val canDelete: Boolean? = null
)

/**
 * 💰 Расширенный список типов расходов.
 * Используется в UI как источник истины для выпадающих списков.
 */
object ExpenseTypes {
    val ALL = listOf(
        "HOUSEHOLD" to "🏠 Хоз.нужды",
        "CONTRACTORS" to "👷 Подрядчики",
        "ROAD" to "🚗 Дорога",
        "PER_DIEM" to "💵 Суточные",
        "PER_DIEM_EXTRA" to "🔴 Суточные сверх.",
        "CASH" to "💰 Наличные",
        "CARD" to "💳 Карта",
        "OTHER" to "📦 Прочее"
    )

    fun label(type: String): String = ALL.find { it.first == type }?.second ?: type
}


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
data class SalaryBreakdownResponse(
    val id: String,
    val fixed: Double,
    val piece: Double,
    val hourly: Double,
    val bonus: Double,
    val penalty: Double = 0.0,
    val total: Double
)

@Serializable
data class CalculateSalaryRequest(
    val userId: String,
    val year: Int,
    val month: Int
)

@Serializable
data class TicketUploadRequest(
    val projectId: String,
    val description: String,
    val sendToAccountant: Boolean,
    val accountantEmail: String,
    val recipientIds: List<String>,
    val fileBase64: String,
    val fileName: String,
    val fileType: String,
    val amount: Double = 0.0,        // 
    val currency: String = "RUB",     // 
    val receiptBase64: String? = null,  // 🆕 Чек (опционально)
    val receiptFileName: String? = null,  // 🆕 Имя файла чека
    val receiptFileType: String? = null  // 🆕 Тип файла чека
)

@Serializable
data class RolePermissionUpdateDto(
    val permission: String,
    val canView: Boolean,
    val canCreate: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private suspend fun ApplicationCall.checkSession(vararg allowedRoles: String): SessionManager.Session? {
    val token = this.request.headers["X-Session-Token"]
    val session = SessionManager.validate(token)
    if (session == null) {
        this.respond(HttpStatusCode.Unauthorized, "Session expired")
        return null
    }
    if (allowedRoles.isNotEmpty() && session.role !in allowedRoles) {
        this.respond(HttpStatusCode.Forbidden, "Access denied")
        return null
    }
    return session
}

private fun mapRowToEntryDto(row: ResultRow): TimeEntry {
    return TimeEntry(
        id = row[TimeEntriesTable.id].value.toString(),
        userId = row[TimeEntriesTable.userId].value.toString(),
        projectId = row[TimeEntriesTable.projectId].value.toString(),
        projectName = row[TimeEntriesTable.projectName],
        date = row[TimeEntriesTable.date].toString(),
        hours = row[TimeEntriesTable.hours],
        country = row[TimeEntriesTable.country],
        comment = row[TimeEntriesTable.comment] ?: "",
        synced = row[TimeEntriesTable.synced]
    )
}

fun Route.dataRoutes() {

    // ─────────────────────────────────────────────────────────────
    // 🔥 FCM TOKENS (регистрация токенов для push-уведомлений)
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/fcm") {
        post("/register") {

            val session = call.checkSession()
            if (session == null) {
                println("❌ FCM register: сессия невалидна (401)")
                return@post call.respond(HttpStatusCode.Unauthorized, "Session expired")
            }

            val body = try {
                call.receive<Map<String, String>>()
            } catch (e: Exception) {
                println("❌ FCM register: не удалось распарсить JSON: ${e.message}")
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
            }

            val token = body["token"]
            if (token.isNullOrBlank()) {
                println("❌ FCM register: токен пустой")
                return@post call.respond(HttpStatusCode.BadRequest, "Token required")
            }

            println("📱 FCM register: получен токен длиной ${token.length} от userId=${session.userId}")

            try {
                transaction {
                    // Удаляем дубликаты токена (один токен = одна запись)
                    FcmTokensTable.deleteWhere { FcmTokensTable.token eq token }

                    // Создаём новую запись
                    FcmTokensTable.insert {
                        it[FcmTokensTable.id] = UUID.randomUUID()
                        it[FcmTokensTable.userId] = session.userId
                        it[FcmTokensTable.token] = token
                        it[createdAt] = System.currentTimeMillis()
                        it[lastUsed] = System.currentTimeMillis()
                    }
                }
                println("✅ FCM register: токен успешно сохранён в БД для userId=${session.userId}")
                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                println("❌ FCM register: ОШИБКА БД: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, "DB error: ${e.message}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 🕐 TIME ENTRIES
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/entries") {

        //  Эндпоинт для админа: все записи всех сотрудников
        get("/all") {
            if (!call.checkPermission(Permission.PROJECTS, "view")) return@get
            val dateFrom = call.request.queryParameters["dateFrom"]
            val dateTo = call.request.queryParameters["dateTo"]
            val entries = transaction {
                val query = TimeEntriesTable.selectAll()
                    .apply {
                        if (!dateFrom.isNullOrBlank()) {
                            where { TimeEntriesTable.date greaterEq KtLocalDate.parse(dateFrom) }
                        }
                    }
                    .apply {
                        if (!dateTo.isNullOrBlank()) {
                            where { TimeEntriesTable.date lessEq KtLocalDate.parse(dateTo) }
                        }
                    }
                    .orderBy(TimeEntriesTable.date to SortOrder.DESC)
                query.map { row -> mapRowToEntryDto(row) }
            }
            call.respond(HttpStatusCode.OK, entries)
        }
        get {
            if (call.checkSession() == null) return@get
            val userIdParam = call.request.queryParameters["userId"]
            val dateFrom = call.request.queryParameters["dateFrom"]
            val dateTo = call.request.queryParameters["dateTo"]
            if (userIdParam.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "userId parameter required")
                return@get
            }
            val entries = transaction {
                val conditions = mutableListOf<Op<Boolean>>()
                conditions.add(TimeEntriesTable.userId eq UUID.fromString(userIdParam))
                if (!dateFrom.isNullOrBlank()) {
                    conditions.add(TimeEntriesTable.date greaterEq KtLocalDate.parse(dateFrom))
                }
                if (!dateTo.isNullOrBlank()) {
                    conditions.add(TimeEntriesTable.date lessEq KtLocalDate.parse(dateTo))
                }
                val query = TimeEntriesTable.selectAll()
                    .where { conditions.reduce { acc, op -> acc and op } }
                query.map { row -> mapRowToEntryDto(row) }
            }
            call.respond(HttpStatusCode.OK, entries)
        }

        post {
            if (call.checkSession() == null) return@post
            val entry = try {
                call.receive<TimeEntry>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}"); return@post
            }
            transaction {
                TimeEntriesTable.insert {
                    it[TimeEntriesTable.id] = UUID.fromString(entry.id)
                    it[TimeEntriesTable.userId] = UUID.fromString(entry.userId)
                    it[TimeEntriesTable.projectId] = UUID.fromString(entry.projectId)
                    it[TimeEntriesTable.projectName] = entry.projectName
                    it[TimeEntriesTable.date] = KtLocalDate.parse(entry.date)
                    it[TimeEntriesTable.hours] = entry.hours
                    it[TimeEntriesTable.country] = entry.country
                    it[TimeEntriesTable.comment] = entry.comment
                    it[TimeEntriesTable.synced] = entry.synced
                }
            }
            call.respond(HttpStatusCode.Created, entry)
        }

        // ✅ PUT: UPSERT (обновить или создать)
        put {
            if (call.checkSession() == null) return@put
            val entry = try { call.receive<TimeEntry>() }
            catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}"); return@put
            }

            // 🔥 ЗАПРЕТ: нельзя добавлять часы за будущие даты
            val entryDate = KtLocalDate.parse(entry.date)
            val today = java.time.LocalDate.now()
            val entryJavaDate = java.time.LocalDate.of(entryDate.year, entryDate.monthNumber, entryDate.dayOfMonth)
            if (entryJavaDate > today) {
                call.respond(HttpStatusCode.BadRequest, "Нельзя добавлять часы за будущие даты")
                return@put
            }

            val userId = UUID.fromString(entry.userId)
            val projectId = UUID.fromString(entry.projectId)

            // 🔥 Возвращаем из транзакции и данные, и флаг "было ли обновление"
            val result = transaction {
                val existing = TimeEntriesTable.selectAll()
                    .where {
                        (TimeEntriesTable.userId eq userId) and
                                (TimeEntriesTable.projectId eq projectId) and
                                (TimeEntriesTable.date eq entryDate)
                    }
                    .firstOrNull()

                if (existing != null) {
                    // 🔄 ОБНОВЛЕНИЕ
                    val newHours = existing[TimeEntriesTable.hours] + entry.hours
                    val oldComment = existing[TimeEntriesTable.comment] ?: ""
                    val newComment = if (entry.comment.isNotBlank() && entry.comment != oldComment) {
                        if (oldComment.isNotBlank()) "$oldComment | ${entry.comment}" else entry.comment
                    } else oldComment

                    TimeEntriesTable.update({
                        (TimeEntriesTable.userId eq userId) and
                                (TimeEntriesTable.projectId eq projectId) and
                                (TimeEntriesTable.date eq entryDate)
                    }) {
                        it[TimeEntriesTable.hours] = newHours
                        it[TimeEntriesTable.comment] = newComment
                        it[TimeEntriesTable.synced] = entry.synced
                    }
                    // Возвращаем обновлённую запись + флаг
                    val updated = TimeEntriesTable.selectAll()
                        .where { TimeEntriesTable.id eq existing[TimeEntriesTable.id] }
                        .map { row -> mapRowToEntryDto(row) }
                        .first()
                    Pair(updated, true) // true = было обновление
                } else {
                    // ➕ СОЗДАНИЕ
                    TimeEntriesTable.insert {
                        it[TimeEntriesTable.id] = UUID.fromString(entry.id)
                        it[TimeEntriesTable.userId] = userId
                        it[TimeEntriesTable.projectId] = projectId
                        it[TimeEntriesTable.projectName] = entry.projectName
                        it[TimeEntriesTable.date] = entryDate
                        it[TimeEntriesTable.hours] = entry.hours
                        it[TimeEntriesTable.country] = entry.country
                        it[TimeEntriesTable.comment] = entry.comment
                        it[TimeEntriesTable.synced] = entry.synced
                    }
                    Pair(entry, false) // false = было создание
                }
            }

            // Теперь используем результат транзакции
            val (dto, wasUpdate) = result
            val statusCode = if (wasUpdate) HttpStatusCode.OK else HttpStatusCode.Created
            call.respond(statusCode, dto)
        }

        delete {
            if (call.checkSession() == null) return@delete
            val entryIdParam = call.request.queryParameters["entryId"]
            if (entryIdParam.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "entryId parameter required")
                return@delete
            }
            val entryId = UUID.fromString(entryIdParam)
            transaction {
                TimeEntriesTable.deleteWhere { TimeEntriesTable.id eq entryId }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 🧮 PROJECT COSTS — финансовые поля себестоимости
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/project-costs") {
        get("/payroll-data") {
            if (!call.checkPermission(Permission.COST_CALCULATION, "view")) return@get
            val data = transaction {
                val employees = UsersTable.selectAll()
                    .where { UsersTable.role eq "employee" }
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
                val components = SalaryComponentsTable.selectAll()
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
                ProjectCostPayrollDataDto(employees, components)
            }
            call.respond(HttpStatusCode.OK, data)
        }

        put("/{projectId}") {
            if (!call.checkPermission(Permission.COST_CALCULATION, "edit")) return@put
            val projectId = runCatching { UUID.fromString(call.parameters["projectId"]) }.getOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid projectId")
            val body = try { call.receive<ProjectCostUpdateDto>() }
            catch (e: Exception) {
                return@put call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
            }

            val updated = transaction {
                val changed = ProjectsTable.update({ ProjectsTable.id eq projectId }) {
                    it[ProjectsTable.sellingPrice] = body.sellingPrice.coerceAtLeast(0.0)
                    it[ProjectsTable.transportToClient] = body.transportToClient.coerceAtLeast(0.0)
                    it[ProjectsTable.materials] = body.materials.coerceAtLeast(0.0)
                    it[ProjectsTable.contractors] = body.contractors.coerceAtLeast(0.0)
                    it[ProjectsTable.creditPercent] = body.creditPercent.coerceAtLeast(0.0)
                }
                changed > 0
            }

            if (!updated) return@put call.respond(HttpStatusCode.NotFound, "Project not found")
            call.respond(HttpStatusCode.OK, body)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 📦 PROJECTS
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/projects") {
        get {
            if (call.checkSession() == null) return@get
            val projects = transaction {
                ProjectsTable.selectAll()
                    .orderBy(ProjectsTable.name to SortOrder.ASC)
                    .map { row ->
                        ProjectDto(
                            id = row[ProjectsTable.id].value.toString(),
                            name = row[ProjectsTable.name],
                            isActive = row[ProjectsTable.isActive],
                            status = row[ProjectsTable.status],
                            lead = row[ProjectsTable.lead],
                            revenue = row[ProjectsTable.revenue],
                            expenses = row[ProjectsTable.expenses],
                            cost = row[ProjectsTable.cost],
                            profit = row[ProjectsTable.profit],
                            projectNumber = row[ProjectsTable.projectNumber],
                            subProjectNumber = row[ProjectsTable.subProjectNumber],
                            client = row[ProjectsTable.client],
                            location = row[ProjectsTable.location],
                            productService = row[ProjectsTable.productService],
                            quantity = row[ProjectsTable.quantity],
                            deliveryDate = row[ProjectsTable.deliveryDate]?.toString(),
                            contract = row[ProjectsTable.contract],
                            notes = row[ProjectsTable.notes],
                            projectCode = row[ProjectsTable.projectCode],
                            completionDate = row[ProjectsTable.completionDate]?.toString(),
                            customer = row[ProjectsTable.customer],
                            productionCost = row[ProjectsTable.productionCost],
                            transportToClient = row[ProjectsTable.transportToClient],
                            sellingPrice = row[ProjectsTable.sellingPrice],
                            materials = row[ProjectsTable.materials],
                            contractors = row[ProjectsTable.contractors],
                            creditPercent = row[ProjectsTable.creditPercent]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, projects)
        }

        post {
            val session = call.checkSession() ?: return@post
            // 🎯 Теперь проверят галочки из БД (role_permissions + user_permission_overrides)
            if (!PermissionMiddleware.canCreate(session.userId, Permission.PROJECTS)) {
                return@post call.respond(HttpStatusCode.Forbidden, "Нет прав на создание проектов")
            }
            val project = try { call.receive<ProjectDto>() }
            catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}"); return@post
            }
            val created = transaction {
                ProjectsTable.insert {
                    it[ProjectsTable.id] = UUID.fromString(project.id)
                    it[ProjectsTable.name] = project.name
                    it[ProjectsTable.isActive] = project.isActive
                    it[ProjectsTable.status] = project.status
                    it[ProjectsTable.lead] = project.lead
                    it[ProjectsTable.revenue] = project.revenue
                    it[ProjectsTable.expenses] = project.expenses
                    it[ProjectsTable.cost] = project.cost
                    it[ProjectsTable.profit] = project.profit
                    it[ProjectsTable.projectNumber] = project.projectNumber
                    it[ProjectsTable.subProjectNumber] = project.subProjectNumber
                    it[ProjectsTable.client] = project.client
                    it[ProjectsTable.location] = project.location
                    it[ProjectsTable.productService] = project.productService
                    it[ProjectsTable.quantity] = project.quantity
                    project.deliveryDate?.let { d -> it[ProjectsTable.deliveryDate] = KtLocalDate.parse(d) }
                    it[ProjectsTable.contract] = project.contract
                    it[ProjectsTable.notes] = project.notes
                    it[ProjectsTable.projectCode] = project.projectCode
                    project.completionDate?.let { d -> it[ProjectsTable.completionDate] = KtLocalDate.parse(d) }
                    it[ProjectsTable.customer] = project.customer
                    it[ProjectsTable.productionCost] = project.productionCost
                    it[ProjectsTable.transportToClient] = project.transportToClient
                    it[ProjectsTable.sellingPrice] = project.sellingPrice
                    it[ProjectsTable.materials] = project.materials
                    it[ProjectsTable.contractors] = project.contractors
                    it[ProjectsTable.creditPercent] = project.creditPercent
                }
                project
            }
            call.respond(HttpStatusCode.Created, created)
        }

        put {
            if (!call.checkPermission(Permission.PROJECTS, "edit")) return@put
            val project = try { call.receive<ProjectDto>() }
            catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}"); return@put
            }
            val projectId = UUID.fromString(project.id)
            val updated = transaction {
                ProjectsTable.update({ ProjectsTable.id eq projectId }) {
                    it[name] = project.name
                    it[isActive] = project.isActive
                    it[status] = project.status
                    it[lead] = project.lead
                    it[revenue] = project.revenue
                    it[expenses] = project.expenses
                    it[cost] = project.cost
                    it[profit] = project.profit
                    it[projectNumber] = project.projectNumber
                    it[subProjectNumber] = project.subProjectNumber
                    it[ProjectsTable.client] = project.client
                    it[ProjectsTable.location] = project.location
                    it[productService] = project.productService
                    it[quantity] = project.quantity
                    project.deliveryDate?.let { d -> it[deliveryDate] = KtLocalDate.parse(d) }
                    it[contract] = project.contract
                    it[notes] = project.notes
                    it[projectCode] = project.projectCode
                    project.completionDate?.let { d -> it[completionDate] = KtLocalDate.parse(d) }
                    it[customer] = project.customer
                    it[productionCost] = project.productionCost
                    it[transportToClient] = project.transportToClient
                    it[sellingPrice] = project.sellingPrice
                    it[ProjectsTable.materials] = project.materials
                    it[ProjectsTable.contractors] = project.contractors
                    it[ProjectsTable.creditPercent] = project.creditPercent
                }
                project
            }
            call.respond(HttpStatusCode.OK, updated)
        }

        delete {
            if (!call.checkPermission(Permission.PROJECTS, "delete")) return@delete
            val projectId = call.request.queryParameters["projectId"]
            if (projectId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "projectId required"); return@delete
            }
            val projectUuid = UUID.fromString(projectId)

            transaction {
                // 🔥 КАСКАДНОЕ УДАЛЕНИЕ: удаляем ВСЕ зависимые записи перед удалением проекта

                // 1. Билеты и их получатели
                val ticketIds = TicketsTable.selectAll()
                    .where { TicketsTable.projectId eq projectUuid }
                    .map { it[TicketsTable.id].value }
                if (ticketIds.isNotEmpty()) {
                    TicketRecipientsTable.deleteWhere { TicketRecipientsTable.ticketId inList ticketIds }
                    TicketsTable.deleteWhere { TicketsTable.projectId eq projectUuid }
                }

                // 2. Командировки
                BusinessTripsTable.deleteWhere { BusinessTripsTable.projectId eq projectUuid }

                // 3. Расходы и их чеки
                val expenseIds = ExpensesTable.selectAll()
                    .where { ExpensesTable.projectId eq projectUuid }
                    .map { it[ExpensesTable.id].value }
                if (expenseIds.isNotEmpty()) {
                    ExpenseReceiptsTable.deleteWhere { ExpenseReceiptsTable.expenseId inList expenseIds }
                    ExpensesTable.deleteWhere { ExpensesTable.projectId eq projectUuid }
                }

                // 4. Записи времени (часы)
                TimeEntriesTable.deleteWhere { TimeEntriesTable.projectId eq projectUuid }

                // 5. Компоненты зарплаты (ссылка на проект)
                SalaryComponentsTable.deleteWhere { SalaryComponentsTable.projectId eq projectUuid }

                // 6. Доходы (если есть таблица IncomesTable)
                IncomesTable.deleteWhere { IncomesTable.projectId eq projectUuid }

                // 7. Сам проект (последним, когда все зависимости удалены)
                ProjectsTable.deleteWhere { ProjectsTable.id eq projectUuid }
            }

            println("✅ Project $projectId deleted with all dependencies")
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 💰 EXPENSES (отдельная таблица расходов)
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/expenses") {
        // 💰 Список типов расходов (для UI)
        get("/types") {
            if (call.checkSession() == null) return@get
            call.respond(HttpStatusCode.OK, ExpenseTypes.ALL.map {
                mapOf("key" to it.first, "label" to it.second)
            })
        }

        suspend fun handleExpenseAttachment(call: ApplicationCall, requireImage: Boolean = false) {
            val session = call.checkSession() ?: return
            val requestBody = try {
                call.receive<Map<String, String>>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
                return
            }

            val expenseId = requestBody["expenseId"]
            val fileBase64 = requestBody["fileBase64"] ?: requestBody["imageBase64"]
            val originalName = requestBody["fileName"] ?: "receipt.jpg"
            val mimeType = requestBody["mimeType"] ?: "image/jpeg"

            if (expenseId.isNullOrBlank() || fileBase64.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Missing expenseId or fileBase64")
                return
            }

            if (requireImage && !mimeType.startsWith("image/")) {
                call.respond(HttpStatusCode.BadRequest, "Receipt must be an image")
                return
            }

            val expenseUuid = try { UUID.fromString(expenseId) } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid expenseId")
                return
            }

            val fileBytes = try {
                Base64.getDecoder().decode(fileBase64)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid Base64 file data")
                return
            }

            val expenseWithProject = transaction {
                (ExpensesTable innerJoin ProjectsTable)
                    .selectAll()
                    .where { ExpensesTable.id eq expenseUuid }
                    .singleOrNull()
            }

            if (expenseWithProject == null) {
                call.respond(HttpStatusCode.NotFound, "Expense not found")
                return
            }

            val expenseUserId = expenseWithProject[ExpensesTable.userId].value
            val projectName = expenseWithProject[ProjectsTable.name]
            val canUploadForeignExpense = session.role in listOf("admin", "director", "superadmin")
            if (expenseUserId != session.userId && !canUploadForeignExpense) {
                call.respond(HttpStatusCode.Forbidden, "Access denied: not your expense")
                return
            }

            val userFullNameShort = transaction {
                UsersTable.selectAll()
                    .where { UsersTable.id eq expenseUserId }
                    .singleOrNull()
                    ?.let { row ->
                        val lastName = row[UsersTable.lastName]
                        val firstName = row[UsersTable.firstName]
                        val middleName = row[UsersTable.middleName]
                        val initials = buildString {
                            if (firstName.isNotEmpty()) append(firstName.first().uppercase())
                            if (middleName.isNotEmpty()) append(".").append(middleName.first().uppercase()).append(".")
                        }
                        "$lastName $initials".trim()
                    } ?: "unknown_user"
            }

            val currentMonth = java.time.LocalDate.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")
            )
            val safeUserFolder = userFullNameShort
                .replace(Regex("[^A-Za-zА-Яа-яЁё0-9._ -]"), "_")
                .replace("..", "_")
                .ifBlank { "unknown_user" }

            val uploadDir = java.io.File("uploads/receipts/$currentMonth/$safeUserFolder").apply { mkdirs() }

            val safeProject = projectName
                .replace(Regex("[^A-Za-zА-Яа-яЁё0-9._ -]"), "_")
                .replace("..", "_")
                .take(80)
                .ifBlank { "project" }
            val extension = originalName.substringAfterLast('.', "").lowercase().take(10).let {
                if (it.matches(Regex("[a-z0-9]+"))) it else when {
                    mimeType == "application/pdf" -> "pdf"
                    mimeType.startsWith("image/") -> "jpg"
                    else -> "bin"
                }
            }
            val fileName = "${safeProject}_${java.time.LocalDate.now()}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$extension"
            java.io.File(uploadDir, fileName).writeBytes(fileBytes)

            val fileUrl = "/uploads/receipts/$currentMonth/$safeUserFolder/$fileName"
            val receiptId = UUID.randomUUID()
            transaction {
                ExpenseReceiptsTable.insert {
                    it[id] = receiptId
                    it[ExpenseReceiptsTable.expenseId] = expenseUuid
                    it[ExpenseReceiptsTable.imageUrl] = fileUrl
                    it[uploadedAt] = System.currentTimeMillis()
                }
                ExpensesTable.update({ ExpensesTable.id eq expenseUuid }) {
                    it[hasReceiptPhoto] = mimeType.startsWith("image/")
                    it[receiptSubmitted] = true
                }
            }

            println("✅ Вложение расхода сохранено: $fileUrl (${fileBytes.size / 1024} КБ, $mimeType) | expense=$expenseId")
            call.respond(
                HttpStatusCode.Created,
                mapOf("id" to receiptId.toString(), "url" to fileUrl, "fileName" to fileName)
            )
        }

        post("/upload-receipt") {
            handleExpenseAttachment(call, requireImage = true)
        }

        post("/upload-attachment") {
            handleExpenseAttachment(call, requireImage = false)
        }

        get("/receipts") {
            val session = call.checkSession() ?: return@get
            val expenseIdParam = call.request.queryParameters["expenseId"]
            val expenseUuid = try {
                UUID.fromString(expenseIdParam)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid expenseId")
                return@get
            }

            val expense = transaction {
                ExpensesTable
                    .selectAll()
                    .where { ExpensesTable.id eq expenseUuid }
                    .singleOrNull()
            }
            if (expense == null) {
                call.respond(HttpStatusCode.NotFound, "Expense not found")
                return@get
            }

            val expenseUserId = expense[ExpensesTable.userId].value
            val canViewForeignExpense = session.role in listOf("admin", "director", "superadmin")
            if (expenseUserId != session.userId && !canViewForeignExpense) {
                call.respond(HttpStatusCode.Forbidden, "Access denied")
                return@get
            }

            val receipts = transaction {
                ExpenseReceiptsTable
                    .selectAll()
                    .where { ExpenseReceiptsTable.expenseId eq expenseUuid }
                    .orderBy(ExpenseReceiptsTable.uploadedAt to SortOrder.ASC)
                    .map { row ->
                        val url = row[ExpenseReceiptsTable.imageUrl]
                        ExpenseReceiptDto(
                            id = row[ExpenseReceiptsTable.id].value.toString(),
                            expenseId = expenseUuid.toString(),
                            url = url,
                            fileName = url.substringAfterLast('/'),
                            uploadedAt = row[ExpenseReceiptsTable.uploadedAt]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, receipts)
        }

        delete("/receipt") {
            val session = call.checkSession() ?: return@delete
            val receiptIdParam = call.request.queryParameters["receiptId"]
            val receiptUuid = try {
                UUID.fromString(receiptIdParam)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid receiptId")
                return@delete
            }

            val receipt = transaction {
                (ExpenseReceiptsTable innerJoin ExpensesTable)
                    .selectAll()
                    .where { ExpenseReceiptsTable.id eq receiptUuid }
                    .singleOrNull()
            }
            if (receipt == null) {
                call.respond(HttpStatusCode.NotFound, "Receipt not found")
                return@delete
            }

            val expenseUserId = receipt[ExpensesTable.userId].value
            val canEditForeignExpense = session.role in listOf("admin", "director", "superadmin")
            if (expenseUserId != session.userId && !canEditForeignExpense) {
                call.respond(HttpStatusCode.Forbidden, "Access denied")
                return@delete
            }

            val path = receipt[ExpenseReceiptsTable.imageUrl]
            transaction {
                ExpenseReceiptsTable.deleteWhere { ExpenseReceiptsTable.id eq receiptUuid }
                val remaining = ExpenseReceiptsTable
                    .selectAll()
                    .where { ExpenseReceiptsTable.expenseId eq receipt[ExpensesTable.id].value }
                    .count()
                if (remaining == 0L) {
                    ExpensesTable.update({ ExpensesTable.id eq receipt[ExpensesTable.id].value }) {
                        it[ExpensesTable.receiptSubmitted] = false
                        it[ExpensesTable.hasReceiptPhoto] = false
                    }
                }
            }

            val relativePath = path.removePrefix("/")
            java.io.File(relativePath).takeIf { it.exists() && it.isFile }?.delete()
            call.respond(HttpStatusCode.NoContent)
        }

        //  Эндпоинт для админа: все расходы всех сотрудников
        get("/all") {
            if (!call.checkPermission(Permission.EXPENSES_ALL, "view")) return@get
            val dateFrom = call.request.queryParameters["dateFrom"]
            val dateTo = call.request.queryParameters["dateTo"]
            val list = transaction {
                val query = (ExpensesTable innerJoin ProjectsTable)
                    .selectAll()
                    .apply {
                        if (!dateFrom.isNullOrBlank()) {
                            where { ExpensesTable.date greaterEq KtLocalDate.parse(dateFrom) }
                        }
                    }
                    .apply {
                        if (!dateTo.isNullOrBlank()) {
                            where { ExpensesTable.date lessEq KtLocalDate.parse(dateTo) }
                        }
                    }
                    .orderBy(ExpensesTable.date to SortOrder.DESC)
                query.map { row ->
                        ExpenseDto(
                            id = row[ExpensesTable.id].value.toString(),
                            userId = row[ExpensesTable.userId].value.toString(),
                            projectId = row[ExpensesTable.projectId].value.toString(),
                            projectName = row[ProjectsTable.name],
                            date = row[ExpensesTable.date].toString(),
                            type = row[ExpensesTable.type],
                            name = row[ExpensesTable.name],
                            amount = row[ExpensesTable.amount],
                            currency = row[ExpensesTable.currency],
                            comment = row[ExpensesTable.comment],
                            receiptSubmitted = row[ExpensesTable.receiptSubmitted],
                            hasReceiptPhoto = row[ExpensesTable.hasReceiptPhoto],
                            category = row[ExpensesTable.category],
                            receiptCount = ExpenseReceiptsTable
                                .selectAll()
                                .where { ExpenseReceiptsTable.expenseId eq row[ExpensesTable.id].value }
                                .count().toInt()
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        get {
            if (call.checkSession() == null) return@get
            val userIdParam = call.request.queryParameters["userId"]
            val dateFrom = call.request.queryParameters["dateFrom"]
            val dateTo = call.request.queryParameters["dateTo"]
            if (userIdParam.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "userId required"); return@get
            }
            val list = transaction {
                val conditions = mutableListOf<Op<Boolean>>()
                conditions.add(ExpensesTable.userId eq UUID.fromString(userIdParam))
                if (!dateFrom.isNullOrBlank()) {
                    conditions.add(ExpensesTable.date greaterEq KtLocalDate.parse(dateFrom))
                }
                if (!dateTo.isNullOrBlank()) {
                    conditions.add(ExpensesTable.date lessEq KtLocalDate.parse(dateTo))
                }
                val query = (ExpensesTable innerJoin ProjectsTable)
                    .selectAll()
                    .where { conditions.reduce { acc, op -> acc and op } }
                    .orderBy(ExpensesTable.date to SortOrder.DESC)
                query.map { row ->
                        ExpenseDto(
                            id = row[ExpensesTable.id].value.toString(),
                            userId = row[ExpensesTable.userId].value.toString(),
                            projectId = row[ExpensesTable.projectId].value.toString(),
                            projectName = row[ProjectsTable.name],
                            date = row[ExpensesTable.date].toString(),
                            type = row[ExpensesTable.type],
                            name = row[ExpensesTable.name],
                            amount = row[ExpensesTable.amount],
                            currency = row[ExpensesTable.currency],
                            comment = row[ExpensesTable.comment],
                            receiptSubmitted = row[ExpensesTable.receiptSubmitted],
                            hasReceiptPhoto = row[ExpensesTable.hasReceiptPhoto],
                            category = row[ExpensesTable.category],
                            receiptCount = ExpenseReceiptsTable
                                .selectAll()
                                .where { ExpenseReceiptsTable.expenseId eq row[ExpensesTable.id].value }
                                .count().toInt()
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        post {
            if (call.checkSession() == null) return@post
            val exp = try { call.receive<ExpenseDto>() }
            catch (e: Exception) { call.respond(HttpStatusCode.BadRequest, e.message ?: "Bad JSON"); return@post }

            val created = transaction {
                val id = UUID.randomUUID()
                ExpensesTable.insert {
                    it[ExpensesTable.id] = id
                    it[userId] = UUID.fromString(exp.userId)
                    it[projectId] = UUID.fromString(exp.projectId)
                    it[date] = KtLocalDate.parse(exp.date)
                    it[type] = exp.type
                    it[name] = exp.name
                    it[amount] = exp.amount
                    it[currency] = exp.currency
                    it[comment] = exp.comment
                    it[receiptSubmitted] = exp.receiptSubmitted
                    it[hasReceiptPhoto] = exp.hasReceiptPhoto
                    it[category] = exp.category
                    it[createdAt] = System.currentTimeMillis()
                }
                exp.copy(id = id.toString())
            }
            val senderName = transaction { UsersTable.selectAll().where { UsersTable.id eq UUID.fromString(exp.userId) }.singleOrNull()?.get(UsersTable.name) ?: "Сотрудник" }
            val financeRecipients = transaction {
                UsersTable.selectAll().where { (UsersTable.role eq "superadmin") or (UsersTable.role eq "director") or (UsersTable.role eq "admin") }
                    .map { it[UsersTable.id].value }.filter { it != UUID.fromString(exp.userId) }
            }
            NotificationService.notifyUsers(
                financeRecipients, UUID.fromString(exp.userId), "EXPENSE_CREATED", "🧾 Новый расход",
                "$senderName: ${exp.name} — ${"%.2f".format(exp.amount)} ${exp.currency}",
                json.encodeToString(mapOf("expenseId" to created.id, "projectId" to exp.projectId)), "expenseCreated"
            )
            call.respond(HttpStatusCode.Created, created)
        }

        put {
            if (call.checkSession() == null) return@put
            val exp = try { call.receive<ExpenseDto>() }
            catch (e: Exception) { call.respond(HttpStatusCode.BadRequest, e.message ?: "Bad JSON"); return@put }

            val expId = UUID.fromString(exp.id)
            val updated = transaction {
                ExpensesTable.update({ ExpensesTable.id eq expId }) {
                    it[projectId] = UUID.fromString(exp.projectId)
                    it[date] = KtLocalDate.parse(exp.date)
                    it[type] = exp.type
                    it[name] = exp.name
                    it[amount] = exp.amount
                    it[currency] = exp.currency
                    it[comment] = exp.comment
                    it[receiptSubmitted] = exp.receiptSubmitted
                    it[hasReceiptPhoto] = exp.hasReceiptPhoto
                    it[category] = exp.category
                }
                exp
            }
            call.respond(HttpStatusCode.OK, updated)
        }

        delete {
            if (call.checkSession() == null) return@delete
            val expId = call.request.queryParameters["expenseId"]
            if (expId.isNullOrBlank()) { call.respond(HttpStatusCode.BadRequest, "expenseId required"); return@delete }
            transaction { ExpensesTable.deleteWhere { ExpensesTable.id eq UUID.fromString(expId) } }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // ─────────────────────────────────────────────────────────────
// 💵 INCOMES: Доходы (аналог расходов, без чеков)
// ─────────────────────────────────────────────────────────────
    route("/api/v1/incomes") {
        get("/all") {
            if (!call.checkPermission(Permission.EXPENSES_ALL, "view")) return@get
            val dateFrom = call.request.queryParameters["dateFrom"]
            val dateTo = call.request.queryParameters["dateTo"]
            val list = transaction {
                val query = (IncomesTable leftJoin ProjectsTable)
                    .selectAll()
                    .apply {
                        if (!dateFrom.isNullOrBlank()) {
                            where { IncomesTable.date greaterEq KtLocalDate.parse(dateFrom) }
                        }
                    }
                    .apply {
                        if (!dateTo.isNullOrBlank()) {
                            where { IncomesTable.date lessEq KtLocalDate.parse(dateTo) }
                        }
                    }
                    .orderBy(IncomesTable.date to SortOrder.DESC)
                query.map { row ->
                        IncomeDto(
                            id = row[IncomesTable.id].value.toString(),
                            userId = row[IncomesTable.userId].value.toString(),
                            projectId = row[IncomesTable.projectId]?.value?.toString(),
                            projectName = row.getOrNull(ProjectsTable.name) ?: "Без проекта",
                            date = row[IncomesTable.date].toString(),
                            type = row[IncomesTable.type],
                            name = row[IncomesTable.name],
                            amount = row[IncomesTable.amount],
                            currency = row[IncomesTable.currency],
                            category = row[IncomesTable.category],
                            createdAt = row[IncomesTable.createdAt]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, list)
        }
        get {
            if (call.checkSession() == null) return@get
            val userIdParam = call.request.queryParameters["userId"]
            val dateFrom = call.request.queryParameters["dateFrom"]
            val dateTo = call.request.queryParameters["dateTo"]
            if (userIdParam.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "userId required"); return@get
            }
            val list = transaction {
                val conditions = mutableListOf<Op<Boolean>>()
                conditions.add(IncomesTable.userId eq UUID.fromString(userIdParam))
                if (!dateFrom.isNullOrBlank()) {
                    conditions.add(IncomesTable.date greaterEq KtLocalDate.parse(dateFrom))
                }
                if (!dateTo.isNullOrBlank()) {
                    conditions.add(IncomesTable.date lessEq KtLocalDate.parse(dateTo))
                }
                val query = (IncomesTable leftJoin ProjectsTable)
                    .selectAll()
                    .where { conditions.reduce { acc, op -> acc and op } }
                    .orderBy(IncomesTable.date to SortOrder.DESC)
                query.map { row ->
                        IncomeDto(
                            id = row[IncomesTable.id].value.toString(),
                            userId = row[IncomesTable.userId].value.toString(),
                            projectId = row[IncomesTable.projectId]?.value?.toString(),
                            projectName = row.getOrNull(ProjectsTable.name) ?: "Без проекта",
                            date = row[IncomesTable.date].toString(),
                            type = row[IncomesTable.type],
                            name = row[IncomesTable.name],
                            amount = row[IncomesTable.amount],
                            currency = row[IncomesTable.currency],
                            category = row[IncomesTable.category],
                            createdAt = row[IncomesTable.createdAt]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, list)
        }
        post {
            if (call.checkSession() == null) return@post
            val income = try { call.receive<IncomeDto>() }
            catch (e: Exception) { call.respond(HttpStatusCode.BadRequest, e.message ?: "Bad JSON"); return@post }
            val created = transaction {
                val id = UUID.randomUUID()
                IncomesTable.insert {
                    it[IncomesTable.id] = id
                    it[userId] = UUID.fromString(income.userId)
                    if (!income.projectId.isNullOrBlank()) it[projectId] = UUID.fromString(income.projectId)
                    it[date] = KtLocalDate.parse(income.date)
                    it[type] = income.type
                    it[name] = income.name
                    it[amount] = income.amount
                    it[currency] = income.currency
                    it[category] = income.category
                    it[createdAt] = System.currentTimeMillis()
                }
                income.copy(id = id.toString(), createdAt = System.currentTimeMillis())
            }
            call.respond(HttpStatusCode.Created, created)
        }
        put {
            if (call.checkSession() == null) return@put
            val income = try { call.receive<IncomeDto>() }
            catch (e: Exception) { call.respond(HttpStatusCode.BadRequest, e.message ?: "Bad JSON"); return@put }
            val incomeId = UUID.fromString(income.id)
            val updated = transaction {
                IncomesTable.update({ IncomesTable.id eq incomeId }) {
                    if (!income.projectId.isNullOrBlank()) it[projectId] = UUID.fromString(income.projectId)
                    else it[projectId] = null
                    it[date] = KtLocalDate.parse(income.date)
                    it[type] = income.type
                    it[name] = income.name
                    it[amount] = income.amount
                    it[currency] = income.currency
                    it[category] = income.category
                }
                income
            }
            call.respond(HttpStatusCode.OK, updated)
        }
        delete {
            if (call.checkSession() == null) return@delete
            val incomeId = call.request.queryParameters["incomeId"]
            if (incomeId.isNullOrBlank()) { call.respond(HttpStatusCode.BadRequest, "incomeId required"); return@delete }
            transaction { IncomesTable.deleteWhere { IncomesTable.id eq UUID.fromString(incomeId) } }
            call.respond(HttpStatusCode.NoContent)
        }
    }


    // ─────────────────────────────────────────────────────────────
    // 🧭 POSITIONS: иерархия должностей
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/positions") {
        get {
            if (!call.checkPermission(Permission.EMPLOYEES, "view")) return@get
            val list = transaction {
                val names = PositionsTable.selectAll().associate { it[PositionsTable.id].value to it[PositionsTable.name] }
                PositionsTable.selectAll()
                    .orderBy(PositionsTable.sortOrder to SortOrder.ASC, PositionsTable.name to SortOrder.ASC)
                    .map {
                        PositionDto(
                            id = it[PositionsTable.id].value.toString(),
                            name = it[PositionsTable.name],
                            parentId = it[PositionsTable.parentId]?.toString(),
                            parentName = it[PositionsTable.parentId]?.let(names::get),
                            isActive = it[PositionsTable.isActive],
                            sortOrder = it[PositionsTable.sortOrder]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, list)
        }
        post {
            if (!call.checkPermission(Permission.EMPLOYEES, "create")) return@post
            val body = call.receive<PositionDto>()
            if (body.name.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, "Название должности обязательно")
            val parentId = body.parentId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (body.parentId != null && parentId == null) return@post call.respond(HttpStatusCode.BadRequest, "Invalid parentId")
            val id = UUID.randomUUID()
            transaction {
                PositionsTable.insert {
                    it[PositionsTable.id] = id
                    it[name] = body.name.trim()
                    it[PositionsTable.parentId] = parentId
                    it[isActive] = body.isActive
                    it[sortOrder] = body.sortOrder
                }
            }
            call.respond(HttpStatusCode.Created, body.copy(id = id.toString(), parentId = parentId?.toString()))
        }
        put {
            if (!call.checkPermission(Permission.EMPLOYEES, "edit")) return@put
            val body = call.receive<PositionDto>()
            val id = runCatching { UUID.fromString(body.id) }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid id")
            val parentId = body.parentId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (parentId == id) return@put call.respond(HttpStatusCode.BadRequest, "Должность не может быть родителем самой себя")
            transaction {
                PositionsTable.update({ PositionsTable.id eq id }) {
                    it[name] = body.name.trim()
                    it[PositionsTable.parentId] = parentId
                    it[isActive] = body.isActive
                    it[sortOrder] = body.sortOrder
                }
            }
            call.respond(HttpStatusCode.OK, body)
        }
        delete {
            if (!call.checkPermission(Permission.EMPLOYEES, "delete")) return@delete
            val id = call.request.queryParameters["positionId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "positionId required")
            val hasChildren = transaction { PositionsTable.selectAll().where { PositionsTable.parentId eq id }.count() > 0 }
            if (hasChildren) return@delete call.respond(HttpStatusCode.Conflict, "Сначала перенесите дочерние должности")
            transaction {
                PositionsTable.update({ PositionsTable.id eq id }) { it[isActive] = false }
                UsersTable.update({ UsersTable.positionId eq id }) { it[UsersTable.positionId] = null }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 👥 USERS
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/users") {
        get {
            if (!call.checkPermission(Permission.EMPLOYEES, "view")) return@get
            val users = transaction {
                val today = KtLocalDate.parse(java.time.LocalDate.now().toString())
                val vacationUserIds = VacationsTable.selectAll().where {
                    (VacationsTable.status eq "APPROVED") and
                    (VacationsTable.start lessEq today) and
                    (VacationsTable.end greaterEq today)
                }.map { it[VacationsTable.userId].value }.toSet()
                UsersTable.selectAll()
                    .orderBy(UsersTable.lastName to SortOrder.ASC)
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
                            positionId = row[UsersTable.positionId]?.value?.toString(),
                            onVacation = row[UsersTable.id].value in vacationUserIds
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, users)
        }

        post {
            if (!call.checkPermission(Permission.EMPLOYEES, "create")) return@post
            val user = try {
                call.receive<UserDto>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON"); return@post
            }
            val created = transaction {
                val hash = BCrypt.withDefaults().hashToString(12, (user.newPassword ?: "password").toCharArray())
                UsersTable.insert {
                    it[id] = UUID.fromString(user.id)
                    it[email] = user.email.ifBlank { user.login + "@proles.local" }
                    it[login] = user.login
                    it[email] = user.email
                    it[lastName] = user.lastName
                    it[firstName] = user.firstName
                    it[middleName] = user.middleName
                    it[name] = listOf(user.lastName, user.firstName, user.middleName).filter { s -> s.isNotBlank() }.joinToString(" ")
                    it[passwordHash] = hash
                    it[role] = user.role
                    it[position] = user.position
                    it[positionId] = user.positionId?.let(UUID::fromString)
                    it[defaultRateType] = user.defaultRateType
                    it[defaultRate] = user.defaultRate
                    it[defaultCurrency] = user.defaultCurrency
                    it[phone] = user.phone
                    it[telegramUsername] = user.telegramUsername
                    it[birthDate] = user.birthDate?.let(KtLocalDate::parse)
                }
                user
            }
            call.respond(HttpStatusCode.Created, created)
        }

        put("/profile") {
            val session = call.checkSession() ?: return@put
            val body = try { call.receive<UserDto>() } catch (e: Exception) { return@put call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}") }
            val updated = transaction {
                UsersTable.update({ UsersTable.id eq session.userId }) {
                    it[lastName] = body.lastName
                    it[firstName] = body.firstName
                    it[middleName] = body.middleName
                    it[name] = listOf(body.lastName, body.firstName, body.middleName).filter { x -> x.isNotBlank() }.joinToString(" ")
                    it[email] = body.email
                    it[phone] = body.phone
                    it[telegramUsername] = body.telegramUsername
                    it[birthDate] = body.birthDate?.let(KtLocalDate::parse)
                }
                body.copy(id = session.userId.toString())
            }
            call.respond(HttpStatusCode.OK, updated)
        }

        put {
            if (!call.checkPermission(Permission.EMPLOYEES, "edit")) return@put
            val user = try {
                call.receive<UserDto>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON"); return@put
            }
            val userId = UUID.fromString(user.id)
            val updated = transaction {
                val existing = UsersTable.selectAll().where { UsersTable.id eq userId }.single()
                val hash = if (user.newPassword.isNullOrBlank()) existing[UsersTable.passwordHash]
                else BCrypt.withDefaults().hashToString(12, user.newPassword.toCharArray())
                UsersTable.update({ UsersTable.id eq userId }) {
                    it[login] = user.login
                    it[email] = user.email
                    it[lastName] = user.lastName
                    it[firstName] = user.firstName
                    it[middleName] = user.middleName
                    it[name] = listOf(user.lastName, user.firstName, user.middleName).filter { s -> s.isNotBlank() }.joinToString(" ")
                    it[passwordHash] = hash
                    it[role] = user.role
                    it[position] = user.position
                    it[positionId] = user.positionId?.let(UUID::fromString)
                    it[defaultRateType] = user.defaultRateType
                    it[defaultRate] = user.defaultRate
                    it[defaultCurrency] = user.defaultCurrency
                    it[phone] = user.phone
                    it[telegramUsername] = user.telegramUsername
                    it[birthDate] = user.birthDate?.let(KtLocalDate::parse)
                }
                user
            }
            call.respond(HttpStatusCode.OK, updated)
        }

        // 🗑 Удаление сотрудника (только для имеющих право employees.delete)
        delete {
            if (!call.checkPermission(Permission.EMPLOYEES, "delete")) return@delete
            val userId = call.request.queryParameters["userId"]
            if (userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "userId required"); return@delete
            }
            val userUuid = runCatching { UUID.fromString(userId) }.getOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid userId")

            // Запрещаем удалять самого себя
            val session = call.checkSession() ?: return@delete
            if (session.userId == userUuid) {
                return@delete call.respond(HttpStatusCode.Forbidden, "Нельзя удалить самого себя")
            }

            // Запрещаем удалять superadmin
            val targetRole = transaction {
                UsersTable.selectAll().where { UsersTable.id eq userUuid }
                    .singleOrNull()?.get(UsersTable.role)
            }
            if (targetRole == "superadmin") {
                return@delete call.respond(HttpStatusCode.Forbidden, "Нельзя удалить superadmin")
            }

            transaction {
                com.proles.server.config.SessionManager.invalidateAllForUser(userUuid)
                // Удаляем связанные данные (каскадно через FK, но для надежности явно)
                TimeEntriesTable.deleteWhere { TimeEntriesTable.userId eq userUuid }
                ExpensesTable.deleteWhere { ExpensesTable.userId eq userUuid }
                BusinessTripsTable.deleteWhere { BusinessTripsTable.userId eq userUuid }
                VacationsTable.deleteWhere { VacationsTable.userId eq userUuid }
                DayOffsTable.deleteWhere { DayOffsTable.userId eq userUuid }
                NotificationsTable.deleteWhere {
                    (NotificationsTable.senderUserId eq userUuid) or
                            (NotificationsTable.targetUserId eq userUuid)
                }
                FcmTokensTable.deleteWhere { FcmTokensTable.userId eq userUuid }
                UserPermissionOverridesTable.deleteWhere { UserPermissionOverridesTable.userId eq userUuid }
                SalaryComponentsTable.deleteWhere { SalaryComponentsTable.userId eq userUuid }
                SalaryRecordsTable.deleteWhere { SalaryRecordsTable.userId eq userUuid }
                // Сам пользователь
                UsersTable.deleteWhere { UsersTable.id eq userUuid }
            }
            println("✅ User $userId deleted with all related data")
            call.respond(HttpStatusCode.NoContent)
        }

    }

    // ─────────────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────
    // 🏖 VACATIONS
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/vacations") {
        get("/all") {
            if (!call.checkPermission(Permission.VACATIONS_ALL, "view")) return@get
            val yearParam = call.request.queryParameters["year"]?.toIntOrNull()
            val rows = transaction {
                var q = VacationsTable.selectAll()
                if (yearParam != null) {
                    val from = KtLocalDate(yearParam, 1, 1)
                    val to = KtLocalDate(yearParam, 12, 31)
                    q = q.where { (VacationsTable.start lessEq to) and (VacationsTable.end greaterEq from) }
                }
                q.orderBy(VacationsTable.start to SortOrder.ASC).map { row ->
                    VacationDto(
                        id = row[VacationsTable.id].value.toString(),
                        userId = row[VacationsTable.userId].value.toString(),
                        start = row[VacationsTable.start].toString(),
                        end = row[VacationsTable.end].toString(),
                        status = row[VacationsTable.status],
                        approvedBy = row[VacationsTable.approvedBy]?.value?.toString(),
                        approvedAt = row[VacationsTable.approvedAt],
                        rejectionReason = row[VacationsTable.rejectionReason]
                    )
                }
            }
            call.respond(HttpStatusCode.OK, rows)
        }

        get {
            val session = call.checkSession() ?: return@get
            val requested = call.request.queryParameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: session.userId
            val canViewAll = PermissionMiddleware.canView(session.userId, Permission.VACATIONS_ALL)
            if (requested != session.userId && !canViewAll) return@get call.respond(HttpStatusCode.Forbidden, "Можно просматривать только свои отпуска")
            val rows = transaction {
                VacationsTable.selectAll().where { VacationsTable.userId eq requested }
                    .orderBy(VacationsTable.start to SortOrder.ASC).map { row ->
                        VacationDto(
                            id = row[VacationsTable.id].value.toString(), userId = row[VacationsTable.userId].value.toString(),
                            start = row[VacationsTable.start].toString(), end = row[VacationsTable.end].toString(),
                            status = row[VacationsTable.status], approvedBy = row[VacationsTable.approvedBy]?.value?.toString(),
                            approvedAt = row[VacationsTable.approvedAt], rejectionReason = row[VacationsTable.rejectionReason]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, rows)
        }

        post {
            val session = call.checkSession() ?: return@post
            val vacation = try { call.receive<VacationDto>() } catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}") }
            val userId = runCatching { UUID.fromString(vacation.userId) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid userId")
            val canCreateAll = PermissionMiddleware.canCreate(session.userId, Permission.VACATIONS_ALL)
            if (userId != session.userId && !canCreateAll) return@post call.respond(HttpStatusCode.Forbidden, "Можно оформлять отпуск только для себя")
            val startDate = runCatching { KtLocalDate.parse(vacation.start) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid start")
            val endDate = runCatching { KtLocalDate.parse(vacation.end) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid end")
            if (endDate < startDate) return@post call.respond(HttpStatusCode.BadRequest, "End date cannot be before start date")
            val id = UUID.randomUUID()
            transaction {
                VacationsTable.insert {
                    it[VacationsTable.id] = id
                    it[VacationsTable.userId] = userId
                    it[start] = startDate
                    it[end] = endDate
                    it[status] = "PENDING"
                    it[rejectionReason] = ""
                }
            }
            val employeeName = transaction { UsersTable.selectAll().where { UsersTable.id eq userId }.single()[UsersTable.name] }
            val approvers = transaction {
                UsersTable.selectAll().where {
                    (UsersTable.role eq "superadmin") or (UsersTable.role eq "director") or (UsersTable.role eq "admin")
                }.map { it[UsersTable.id].value }
            }
            val days = endDate.toEpochDays() - startDate.toEpochDays() + 1
            val payload = json.encodeToString(mapOf("vacationId" to id.toString(), "userId" to userId.toString()))
            NotificationService.notifyUsers(
                approvers, session.userId, "VACATION_REQUEST", "🏖 Новый запрос на отпуск",
                "👤 $employeeName\n📅 ${startDate} — ${endDate}\n📊 $days дн.\n⏳ Требуется подтверждение",
                payload, "vacation"
            )
            call.respond(HttpStatusCode.Created, VacationDto(id.toString(), userId.toString(), startDate.toString(), endDate.toString(), "PENDING", null, null, ""))
        }

        post("/{vacationId}/decision") {
            val session = call.checkSession() ?: return@post
            if (session.role !in listOf("superadmin", "director", "admin")) return@post call.respond(HttpStatusCode.Forbidden, "Подтвердить отпуск может руководитель или superadmin")
            val id = runCatching { UUID.fromString(call.parameters["vacationId"]) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid vacationId")
            val body = try { call.receive<Map<String, String>>() } catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON") }
            val approved = body["approved"]?.toBooleanStrictOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, "approved must be true/false")
            val reason = body["reason"].orEmpty()
            val updated = transaction {
                VacationsTable.update({ VacationsTable.id eq id and (VacationsTable.status eq "PENDING") }) {
                    it[status] = if (approved) "APPROVED" else "REJECTED"
                    it[approvedBy] = if (approved) session.userId else null
                    it[approvedAt] = System.currentTimeMillis()
                    it[rejectionReason] = if (approved) "" else reason
                }
            }
            if (updated == 0) return@post call.respond(HttpStatusCode.Conflict, "Отпуск уже обработан")
            val vacation = transaction { VacationsTable.selectAll().where { VacationsTable.id eq id }.single() }
            val employeeId = vacation[VacationsTable.userId].value
            val employeeName = transaction { UsersTable.selectAll().where { UsersTable.id eq employeeId }.single()[UsersTable.name] }
            val title = if (approved) "✅ Отпуск подтверждён" else "❌ Отпуск отклонён"
            val msg = "👤 $employeeName\n📅 ${vacation[VacationsTable.start]} — ${vacation[VacationsTable.end]}\n${if (!approved && reason.isNotBlank()) "📝 Причина: $reason" else ""}".trim()
            NotificationService.notifyUsers(listOf(employeeId), session.userId, "VACATION_DECISION", title, msg, json.encodeToString(mapOf("vacationId" to id.toString(), "approved" to approved)), "vacationDecision")
            call.respond(HttpStatusCode.OK, VacationDto(
                id.toString(), employeeId.toString(), vacation[VacationsTable.start].toString(), vacation[VacationsTable.end].toString(),
                vacation[VacationsTable.status], vacation[VacationsTable.approvedBy]?.value?.toString(), vacation[VacationsTable.approvedAt], vacation[VacationsTable.rejectionReason]
            ))
        }

        delete {
            val session = call.checkSession() ?: return@delete
            val id = call.request.queryParameters["vacationId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "vacationId required")
            val row = transaction { VacationsTable.selectAll().where { VacationsTable.id eq id }.singleOrNull() }
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            if (row[VacationsTable.userId].value != session.userId && session.role !in listOf("superadmin", "director", "admin")) return@delete call.respond(HttpStatusCode.Forbidden)
            transaction { VacationsTable.deleteWhere { VacationsTable.id eq id } }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 🌞 DAY OFFS
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/dayoffs") {
        get("/all") {
            if (!call.checkPermission(Permission.DAYOFFS_ALL, "view")) return@get
            val dayOffs = transaction {
                (DayOffsTable innerJoin UsersTable)
                    .selectAll()
                    .orderBy(DayOffsTable.date to SortOrder.DESC)
                    .map { row ->
                        DayOffRequest(
                            user_id = row[DayOffsTable.userId].value.toString(),
                            date = row[DayOffsTable.date].toString()
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, dayOffs)
        }

        get {
            if (call.checkSession() == null) return@get
            val userIdParam = call.request.queryParameters["userId"]
            if (userIdParam.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "userId parameter required")
                return@get
            }
            val dayOffs = transaction {
                DayOffsTable.selectAll()
                    .where { DayOffsTable.userId eq UUID.fromString(userIdParam) }
                    .orderBy(DayOffsTable.date to SortOrder.ASC)
                    .map { row ->
                        DayOffRequest(
                            user_id = row[DayOffsTable.userId].value.toString(),
                            date = row[DayOffsTable.date].toString()
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, dayOffs)
        }

        post {
            if (call.checkSession() == null) return@post
            val request = try {
                call.receive<DayOffRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
                return@post
            }

            val userId = UUID.fromString(request.user_id)
            val date = KtLocalDate.parse(request.date)

            // 🔥 Проверяем, будний ли это день (пн-пт)
            val dayOfWeek = date.dayOfWeek
            val isWeekday = dayOfWeek != kotlinx.datetime.DayOfWeek.SATURDAY &&
                    dayOfWeek != kotlinx.datetime.DayOfWeek.SUNDAY

            // Форматируем дату
            val formattedDate = "%02d.%02d.%04d".format(date.dayOfMonth, date.monthNumber, date.year)

            // Название дня недели на русском
            val dayOfWeekRu = when (dayOfWeek) {
                kotlinx.datetime.DayOfWeek.MONDAY -> "Понедельник"
                kotlinx.datetime.DayOfWeek.TUESDAY -> "Вторник"
                kotlinx.datetime.DayOfWeek.WEDNESDAY -> "Среда"
                kotlinx.datetime.DayOfWeek.THURSDAY -> "Четверг"
                kotlinx.datetime.DayOfWeek.FRIDAY -> "Пятница"
                kotlinx.datetime.DayOfWeek.SATURDAY -> "Суббота"
                kotlinx.datetime.DayOfWeek.SUNDAY -> "Воскресенье"
                else -> ""
            }

            val status = transaction {
                try {
                    DayOffsTable.insert {
                        it[DayOffsTable.id] = UUID.randomUUID()
                        it[DayOffsTable.userId] = userId
                        it[DayOffsTable.date] = date
                    }
                    HttpStatusCode.Created
                } catch (_: org.jetbrains.exposed.exceptions.ExposedSQLException) {
                    HttpStatusCode.Conflict
                }
            }

            // 🔥 Если выходной создан в БУДНИЙ день — отправляем уведомления админам
            if (status == HttpStatusCode.Created && isWeekday) {
                val session = call.checkSession()!!
                val senderName = transaction {
                    UsersTable.selectAll()
                        .where { UsersTable.id eq userId }
                        .single()[UsersTable.name]
                }

                val notifTitle = "🌞 Выходной в будний день"
                val notifMessage = buildString {
                    appendLine("👤 $senderName")
                    appendLine("📅 Дата: $formattedDate ($dayOfWeekRu)")
                    appendLine("⚠️ Сотрудник взял выходной в рабочий день")
                }.trim()

                val notifPayload = json.encodeToString(mapOf(
                    "userId" to userId.toString(),
                    "date" to request.date,
                    "type" to "DAYOFF_WEEKDAY"
                ))

                // Получаем ID админов (кроме самого сотрудника)
                val adminIds = transaction {
                    UsersTable.selectAll()
                        .where { (UsersTable.role eq "admin") or (UsersTable.role eq "director") }
                        .map { it[UsersTable.id].value }
                        .filter { it != userId }
                }

                NotificationService.notifyUsers(
                    adminIds, userId, "DAYOFF_WEEKDAY", notifTitle, notifMessage, notifPayload, "dayoff"
                )

                println("🌞 Выходной в будний день: $senderName на $formattedDate, уведомления отправлены ${adminIds.size} админам")
            }

            call.respond(status, mapOf<String, String>("status" to if (status.value in 200..299) "success" else "already_exists"))
        }

        delete {
            if (call.checkSession() == null) return@delete
            val userIdParam = call.request.queryParameters["userId"]
            val dateParam = call.request.queryParameters["date"]

            if (userIdParam.isNullOrBlank() || dateParam.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "userId and date query parameters required")
                return@delete
            }

            val userId = UUID.fromString(userIdParam)
            val date = KtLocalDate.parse(dateParam)

            transaction {
                DayOffsTable.deleteWhere {
                    (DayOffsTable.userId eq userId) and (DayOffsTable.date eq date)
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────
    // 🚆 BUSINESS TRIPS
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/business-trips") {
        fun ResultRow.toTripDto(): BusinessTripDto {
            val projectId = this[BusinessTripsTable.projectId]?.value
            val projectName = projectId?.let { id ->
                ProjectsTable.selectAll().where { ProjectsTable.id eq id }.singleOrNull()?.get(ProjectsTable.name)
            }.orEmpty()
            return BusinessTripDto(
                id = this[BusinessTripsTable.id].value.toString(),
                userId = this[BusinessTripsTable.userId].value.toString(),
                projectId = projectId?.toString(),
                projectName = projectName,
                projectNumber = this[BusinessTripsTable.projectNumber],
                companyName = this[BusinessTripsTable.companyName],
                country = this[BusinessTripsTable.country],
                type = this[BusinessTripsTable.type],
                date = this[BusinessTripsTable.date].toString(),
                completedDate = this[BusinessTripsTable.completedDate]?.toString(),
                city = this[BusinessTripsTable.city],
                waypoints = runCatching { json.decodeFromString<List<WaypointDto>>(this[BusinessTripsTable.waypoints]) }.getOrDefault(emptyList()),
                transport = this[BusinessTripsTable.transport],
                notes = this[BusinessTripsTable.notes],
                participants = runCatching { json.decodeFromString<List<String>>(this[BusinessTripsTable.participants]) }.getOrDefault(emptyList()),
                createdAt = this[BusinessTripsTable.createdAt],
                perDiemRate = this[BusinessTripsTable.perDiemRate]
            )
        }

        get {
            val session = call.checkSession() ?: return@get
            val userIdParam = call.request.queryParameters["userId"]
            val dateFrom = call.request.queryParameters["dateFrom"]
            val dateTo = call.request.queryParameters["dateTo"]
            val all = call.request.queryParameters["all"] == "true"
            val canViewAll = PermissionMiddleware.canView(session.userId, Permission.BUSINESS_TRIPS_ALL)
            if (all && !canViewAll) return@get call.respond(HttpStatusCode.Forbidden, "Access denied")
            if (!canViewAll && !userIdParam.isNullOrBlank() && runCatching { UUID.fromString(userIdParam) }.getOrNull() != session.userId) {
                return@get call.respond(HttpStatusCode.Forbidden, "Можно просматривать только свои командировки")
            }

            val trips = transaction {
                var query = BusinessTripsTable.selectAll()
                val conditions = mutableListOf<Op<Boolean>>()
                if (canViewAll && all) {
                    // все записи
                } else if (!userIdParam.isNullOrBlank()) {
                    conditions += (BusinessTripsTable.userId eq UUID.fromString(userIdParam))
                } else {
                    val visibleUserIds = NotificationPreferencesTable.selectAll()
                        .where { NotificationPreferencesTable.tripVisibleToAll eq true }
                        .map { it[NotificationPreferencesTable.userId].value }
                    conditions += ((BusinessTripsTable.userId eq session.userId) or
                        (BusinessTripsTable.userId inList visibleUserIds))
                }
                if (!dateFrom.isNullOrBlank()) conditions += (BusinessTripsTable.date greaterEq KtLocalDate.parse(dateFrom))
                if (!dateTo.isNullOrBlank()) conditions += (BusinessTripsTable.date lessEq KtLocalDate.parse(dateTo))
                if (conditions.isNotEmpty()) {
                    query = query.where { conditions.reduce { acc, op -> acc and op } }
                }
                query.orderBy(BusinessTripsTable.date to SortOrder.DESC).map { it.toTripDto() }
            }
            call.respond(HttpStatusCode.OK, trips)
        }

        post {
            val session = call.checkSession() ?: return@post
            val trip = try { call.receive<BusinessTripDto>() }
            catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest, e.message ?: "Bad JSON") }
            val requestedUserId = runCatching { UUID.fromString(trip.userId) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid userId")
            val canCreateAll = PermissionMiddleware.canCreate(session.userId, Permission.BUSINESS_TRIPS_ALL)
            val userId = if (canCreateAll) requestedUserId else session.userId
            if (trip.projectId.isNullOrBlank() && trip.projectNumber.isBlank() && trip.companyName.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, "Укажите проект, номер проекта или название фирмы")
            }
            val projectUuid = trip.projectId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (!trip.projectId.isNullOrBlank() && projectUuid == null) return@post call.respond(HttpStatusCode.BadRequest, "Invalid projectId")

            val resultTrip = transaction {
                if (trip.type == "DEPARTURE") {
                    val id = UUID.randomUUID()
                    BusinessTripsTable.insert {
                        it[BusinessTripsTable.id] = id
                        it[BusinessTripsTable.userId] = userId
                        it[BusinessTripsTable.projectId] = projectUuid
                        it[BusinessTripsTable.projectNumber] = trip.projectNumber
                        it[BusinessTripsTable.companyName] = trip.companyName
                        it[BusinessTripsTable.country] = trip.country.trim()
                        it[type] = trip.type
                        it[date] = KtLocalDate.parse(trip.date)
                        it[completedDate] = trip.completedDate?.let(KtLocalDate::parse)
                        it[city] = trip.city
                        it[participants] = json.encodeToString(trip.participants)
                        it[transport] = trip.transport
                        it[notes] = trip.notes
                        it[waypoints] = json.encodeToString(trip.waypoints)
                        it[perDiemRate] = trip.perDiemRate
                        it[createdAt] = System.currentTimeMillis()
                    }
                    trip.copy(id = id.toString(), userId = userId.toString())
                } else {
                    val activeTrip = BusinessTripsTable.selectAll()
                        .where { (BusinessTripsTable.userId eq userId) and (BusinessTripsTable.type neq "COMPLETION") }
                        .orderBy(BusinessTripsTable.date to SortOrder.DESC)
                        .firstOrNull()
                        ?: return@transaction trip.copy(id = "", userId = userId.toString())
                    val id = activeTrip[BusinessTripsTable.id].value
                    BusinessTripsTable.update({ BusinessTripsTable.id eq id }) {
                        if (projectUuid != null) it[BusinessTripsTable.projectId] = projectUuid
                        it[BusinessTripsTable.projectNumber] = trip.projectNumber
                        it[BusinessTripsTable.companyName] = trip.companyName
                        if (trip.country.isNotBlank()) it[BusinessTripsTable.country] = trip.country
                        it[date] = KtLocalDate.parse(trip.date)
                        if (trip.city.isNotBlank()) it[city] = trip.city
                        if (trip.transport.isNotBlank()) it[transport] = trip.transport
                        if (trip.notes.isNotBlank()) it[notes] = trip.notes
                        if (trip.waypoints.isNotEmpty()) it[waypoints] = json.encodeToString(trip.waypoints)
                        if (trip.participants.isNotEmpty()) it[participants] = json.encodeToString(trip.participants)
                        it[perDiemRate] = trip.perDiemRate
                        it[type] = trip.type
                    }
                    trip.copy(id = id.toString(), userId = userId.toString())
                }
            }
            if (resultTrip.id.isBlank()) return@post call.respond(HttpStatusCode.NotFound, "Активная командировка не найдена")

            val senderName = transaction { UsersTable.selectAll().where { UsersTable.id eq session.userId }.single()[UsersTable.name] }
            val projectName = resultTrip.projectId?.let { pid -> transaction { ProjectsTable.selectAll().where { ProjectsTable.id eq UUID.fromString(pid) }.singleOrNull()?.get(ProjectsTable.name) } }.orEmpty()
            val location = listOf(resultTrip.country, resultTrip.city).filter { it.isNotBlank() }.joinToString(", ")
            val payload = json.encodeToString(mapOf("tripId" to resultTrip.id, "userId" to resultTrip.userId, "tripType" to resultTrip.type))
            val message = buildString {
                appendLine("👤 $senderName")
                if (projectName.isNotBlank()) appendLine("📁 Проект: $projectName")
                if (resultTrip.projectNumber.isNotBlank()) appendLine("🔢 Номер проекта: ${resultTrip.projectNumber}")
                if (resultTrip.companyName.isNotBlank()) appendLine("🏢 Фирма: ${resultTrip.companyName}")
                if (location.isNotBlank()) appendLine("📍 $location")
                appendLine("📅 ${resultTrip.date}")
            }.trim()

            val adminsAndDirectors = transaction {
                UsersTable.selectAll()
                    .where { (UsersTable.role eq "admin") or (UsersTable.role eq "director") or (UsersTable.role eq "superadmin") }
                    .map { it[UsersTable.id].value }
            }
            val participants = resultTrip.participants.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            NotificationService.notifyUsers(
                targetUserIds = (adminsAndDirectors + participants).distinct(),
                senderUserId = session.userId,
                type = "TRIP",
                title = "✈️ ${if (resultTrip.type == "DEPARTURE") "Новая командировка" else "Командировка обновлена"}",
                message = message,
                payload = payload,
                preferenceKey = if (resultTrip.type == "DEPARTURE") "trip" else "tripChange"
            )

            val broadcastUsers = transaction {
                (NotificationPreferencesTable innerJoin UsersTable)
                    .selectAll()
                    .where {
                        (NotificationPreferencesTable.tripVisibleToAll eq true) and
                        (NotificationPreferencesTable.tripTelegramBroadcast eq true)
                    }
                    .map { it[UsersTable.id].value }
            }
            NotificationService.notifyUsers(
                targetUserIds = broadcastUsers,
                senderUserId = session.userId,
                type = "TRIP_PUBLIC",
                title = "🌍 Командировка доступна всем",
                message = message,
                payload = payload,
                preferenceKey = "tripBroadcast"
            )
            call.respond(HttpStatusCode.Created, resultTrip)
        }

        put {
            val session = call.checkSession() ?: return@put
            val trip = try { call.receive<BusinessTripDto>() }
            catch (e: Exception) { return@put call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}") }
            val tripId = runCatching { UUID.fromString(trip.id) }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid tripId")
            val existingUser = transaction { BusinessTripsTable.selectAll().where { BusinessTripsTable.id eq tripId }.singleOrNull()?.get(BusinessTripsTable.userId)?.value }
                ?: return@put call.respond(HttpStatusCode.NotFound, "Командировка не найдена")
            val allowed = existingUser == session.userId || PermissionMiddleware.canEdit(session.userId, Permission.BUSINESS_TRIPS_ALL)
            if (!allowed) return@put call.respond(HttpStatusCode.Forbidden, "Нет права редактировать эту командировку")
            val projectUuid = trip.projectId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            transaction {
                BusinessTripsTable.update({ BusinessTripsTable.id eq tripId }) {
                    it[projectId] = projectUuid
                    it[projectNumber] = trip.projectNumber
                    it[companyName] = trip.companyName
                    it[country] = trip.country.trim()
                    it[type] = trip.type
                    it[date] = KtLocalDate.parse(trip.date)
                    it[completedDate] = trip.completedDate?.let(KtLocalDate::parse)
                    it[city] = trip.city
                    it[participants] = json.encodeToString(trip.participants)
                    it[transport] = trip.transport
                    it[notes] = trip.notes
                    it[waypoints] = json.encodeToString(trip.waypoints)
                    it[perDiemRate] = trip.perDiemRate
                }
            }
            call.respond(HttpStatusCode.OK, trip.copy(userId = existingUser.toString()))
        }

        // Завершение текущей командировки пользователя.
        post("/complete") {
            val session = call.checkSession() ?: return@post
            val now = KtLocalDate.parse(JavaLocalDate.now().toString())
            data class ActiveTrip(val row: ResultRow, val id: UUID)
            val active = transaction {
                BusinessTripsTable.selectAll()
                    .where { (BusinessTripsTable.userId eq session.userId) and (BusinessTripsTable.type neq "COMPLETION") }
                    .orderBy(BusinessTripsTable.date to SortOrder.DESC)
                    .firstOrNull()
                    ?.let { ActiveTrip(it, it[BusinessTripsTable.id].value) }
            } ?: return@post call.respond(HttpStatusCode.NotFound, "Активная командировка не найдена")
            val startDate = active.row[BusinessTripsTable.date]
            if (now < startDate) return@post call.respond(HttpStatusCode.BadRequest, "Дата завершения раньше даты начала командировки")
            transaction {
                BusinessTripsTable.update({ BusinessTripsTable.id eq active.id }) {
                    it[type] = "COMPLETION"
                    it[completedDate] = now
                }
            }
            val senderName = transaction { UsersTable.selectAll().where { UsersTable.id eq session.userId }.single()[UsersTable.name] }
            val projectName = active.row[BusinessTripsTable.projectId]?.value?.let { projectId ->
                transaction { ProjectsTable.selectAll().where { ProjectsTable.id eq projectId }.singleOrNull()?.get(ProjectsTable.name) }
            }.orEmpty()
            val location = listOf(active.row[BusinessTripsTable.country], active.row[BusinessTripsTable.city]).filter { it.isNotBlank() }.joinToString(", ")
            val participants = runCatching { json.decodeFromString<List<String>>(active.row[BusinessTripsTable.participants]) }.getOrDefault(emptyList())
            val message = buildString {
                appendLine("✅ Командировка завершена")
                appendLine("👤 Сотрудник: $senderName")
                if (projectName.isNotBlank()) appendLine("📁 Проект: $projectName")
                if (active.row[BusinessTripsTable.projectNumber].isNotBlank()) appendLine("🔢 Номер проекта: ${active.row[BusinessTripsTable.projectNumber]}")
                if (active.row[BusinessTripsTable.companyName].isNotBlank()) appendLine("🏢 Фирма: ${active.row[BusinessTripsTable.companyName]}")
                if (location.isNotBlank()) appendLine("📍 $location")
                appendLine("📅 ${startDate} — ${now}")
                if (active.row[BusinessTripsTable.transport].isNotBlank()) appendLine("🚗 ${active.row[BusinessTripsTable.transport]}")
                if (participants.isNotEmpty()) appendLine("👥 ${participants.joinToString(", ")}")
                if (active.row[BusinessTripsTable.notes].isNotBlank()) appendLine("📝 ${active.row[BusinessTripsTable.notes]}")
            }.trim()
            val payload = json.encodeToString(mapOf("tripId" to active.id.toString(), "tripType" to "COMPLETION", "dateFrom" to startDate.toString(), "dateTo" to now.toString()))
            val notifyTargets = transaction {
                UsersTable.selectAll().where {
                    (UsersTable.role eq "admin") or (UsersTable.role eq "director") or (UsersTable.role eq "superadmin")
                }.map { it[UsersTable.id].value }.filter { it != session.userId }
            }
            NotificationService.notifyUsers(notifyTargets, session.userId, "TRIP_UPDATE", "✅ Командировка завершена", message, payload, "tripChange")
            NotificationService.notifyTelegramUser(session.userId, "✅ <b>Командировка завершена</b>\n\n${NotificationService.escapeTelegram(message)}", "tripChange")
            call.respond(HttpStatusCode.OK, mapOf("tripId" to active.id.toString(), "dateFrom" to startDate.toString(), "dateTo" to now.toString()))
        }

        // Переезд: завершить старую командировку датой начала новой и создать новую активную.
        post("/transfer") {
            val session = call.checkSession() ?: return@post
            val incoming = try { call.receive<BusinessTripDto>() }
            catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}") }
            if (incoming.projectId.isNullOrBlank() && incoming.projectNumber.isBlank() && incoming.companyName.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, "Укажите проект, номер проекта или название фирмы")
            }
            val newDate = runCatching { KtLocalDate.parse(incoming.date) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid date")
            data class TransferResult(val oldId: UUID, val newId: UUID)
            val result = transaction {
                val active = BusinessTripsTable.selectAll()
                    .where { (BusinessTripsTable.userId eq session.userId) and (BusinessTripsTable.type neq "COMPLETION") }
                    .orderBy(BusinessTripsTable.date to SortOrder.DESC)
                    .firstOrNull() ?: return@transaction null
                val activeId = active[BusinessTripsTable.id].value
                val activeStart = active[BusinessTripsTable.date]
                if (newDate < activeStart) return@transaction null
                val projectUuid = incoming.projectId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (!incoming.projectId.isNullOrBlank() && projectUuid == null) return@transaction null
                BusinessTripsTable.update({ BusinessTripsTable.id eq activeId }) {
                    it[type] = "COMPLETION"
                    it[completedDate] = newDate
                }
                val newId = UUID.randomUUID()
                BusinessTripsTable.insert {
                    it[id] = newId
                    it[userId] = session.userId
                    it[projectId] = projectUuid
                    it[projectNumber] = incoming.projectNumber
                    it[companyName] = incoming.companyName
                    it[country] = incoming.country.trim()
                    it[type] = "DEPARTURE"
                    it[date] = newDate
                    it[completedDate] = null
                    it[city] = incoming.city
                    it[participants] = json.encodeToString(incoming.participants)
                    it[transport] = incoming.transport
                    it[notes] = incoming.notes
                    it[waypoints] = json.encodeToString(incoming.waypoints)
                    it[perDiemRate] = incoming.perDiemRate
                    it[createdAt] = System.currentTimeMillis()
                }
                TransferResult(activeId, newId)
            } ?: return@post call.respond(HttpStatusCode.BadRequest, "Не удалось выполнить переезд: проверьте дату и проект")
            val senderName = transaction { UsersTable.selectAll().where { UsersTable.id eq session.userId }.single()[UsersTable.name] }
            val location = listOf(incoming.country, incoming.city).filter { it.isNotBlank() }.joinToString(", ")
            val message = buildString {
                appendLine("🔄 Переезд между командировками")
                appendLine("👤 Сотрудник: $senderName")
                if (incoming.projectNumber.isNotBlank()) appendLine("🔢 Номер проекта: ${incoming.projectNumber}")
                if (incoming.companyName.isNotBlank()) appendLine("🏢 Фирма: ${incoming.companyName}")
                if (location.isNotBlank()) appendLine("📍 $location")
                appendLine("📅 Новая точка с $newDate")
                if (incoming.transport.isNotBlank()) appendLine("🚗 ${incoming.transport}")
                if (incoming.participants.isNotEmpty()) appendLine("👥 ${incoming.participants.joinToString(", ")}")
                if (incoming.notes.isNotBlank()) appendLine("📝 ${incoming.notes}")
            }.trim()
            val payload = json.encodeToString(mapOf("completedTripId" to result.oldId.toString(), "tripId" to result.newId.toString(), "tripType" to "TRANSFER", "date" to newDate.toString()))
            val notifyTargets = transaction {
                UsersTable.selectAll().where {
                    (UsersTable.role eq "admin") or (UsersTable.role eq "director") or (UsersTable.role eq "superadmin")
                }.map { it[UsersTable.id].value }.filter { it != session.userId }
            }
            NotificationService.notifyUsers(notifyTargets, session.userId, "TRIP_UPDATE", "🔄 Переезд", message, payload, "tripChange")
            NotificationService.notifyTelegramUser(session.userId, "🔄 <b>Переезд</b>\n\n${NotificationService.escapeTelegram(message)}", "tripChange")
            call.respond(HttpStatusCode.Created, incoming.copy(id = result.newId.toString(), userId = session.userId.toString(), type = "DEPARTURE", completedDate = null))
        }

        delete {
            val session = call.checkSession() ?: return@delete
            val tripId = call.request.queryParameters["tripId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "tripId required")
            val row = transaction { BusinessTripsTable.selectAll().where { BusinessTripsTable.id eq tripId }.singleOrNull() }
                ?: return@delete call.respond(HttpStatusCode.NotFound, "Командировка не найдена")
            val allowed = row[BusinessTripsTable.userId].value == session.userId || PermissionMiddleware.canDelete(session.userId, Permission.BUSINESS_TRIPS_ALL)
            if (!allowed) return@delete call.respond(HttpStatusCode.Forbidden, "Нет права удалить эту командировку")
            transaction { BusinessTripsTable.deleteWhere { BusinessTripsTable.id eq tripId } }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 🔔 NOTIFICATIONS
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/notifications") {
        get {
            val session = call.checkSession() ?: return@get

            // 🔐 Используем RBAC вместо прямой проверки роли
            val canViewAll = com.proles.server.config.PermissionMiddleware
                .canView(session.userId, Permission.NOTIFICATIONS) &&
                    session.role in listOf("admin", "director", "superadmin")

            val list = transaction {
                val baseQuery = NotificationsTable.selectAll()
                val query = if (canViewAll) {
                    // Админы видят все уведомления (личные + общие)
                    baseQuery.where {
                        (NotificationsTable.targetUserId eq null) or
                                (NotificationsTable.targetUserId eq session.userId)
                    }
                } else {
                    // Обычные сотрудники — только свои
                    baseQuery.where { NotificationsTable.targetUserId eq session.userId }
                }
                query.orderBy(NotificationsTable.createdAt to SortOrder.DESC)
                    .map { row ->
                        val senderId = row[NotificationsTable.senderUserId].value
                        val senderName = runCatching {
                            UsersTable.selectAll().where { UsersTable.id eq senderId }.single()[UsersTable.name]
                        }.getOrDefault("")
                        NotificationDto(
                            id = row[NotificationsTable.id].value.toString(),
                            targetUserId = row[NotificationsTable.targetUserId]?.value?.toString(),
                            senderUserId = senderId.toString(),
                            senderName = senderName,
                            type = row[NotificationsTable.type],
                            title = row[NotificationsTable.title],
                            message = row[NotificationsTable.message],
                            payload = row[NotificationsTable.payload],
                            isRead = row[NotificationsTable.isRead],
                            createdAt = row[NotificationsTable.createdAt]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        post {
            val session = call.checkSession() ?: return@post
            val req = try { call.receive<CreateNotificationRequest>() }
            catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest) }

            val targetUserIds = transaction {
                if (req.type in listOf("VACATION", "TRIP", "DAYOFF_WEEKDAY")) {
                    UsersTable.selectAll()
                        .where { (UsersTable.role eq "admin") or (UsersTable.role eq "director") }
                        .map { it[UsersTable.id].value }
                        .filter { it != session.userId }
                } else emptyList()
            }

            transaction {
                val targets = if (targetUserIds.isEmpty()) listOf<UUID?>(null)
                else targetUserIds.map { it }
                targets.forEach { targetId ->
                    NotificationsTable.insert {
                        it[id] = UUID.randomUUID()
                        it[targetUserId] = targetId
                        it[senderUserId] = session.userId
                        it[type] = req.type
                        it[title] = req.title
                        it[message] = req.message
                        it[payload] = req.payload
                        it[createdAt] = System.currentTimeMillis()
                    }
                }
            }

            // ✅ ОТПРАВКА ПУШЕЙ С ЛОГИРОВАНИЕМ
            CoroutineScope(Dispatchers.IO).launch {
                println("🔔 Начинаем отправку FCM пушей для уведомления типа ${req.type}")

                val tokens = transaction {
                    FcmTokensTable.selectAll().map { it[FcmTokensTable.token] }
                }

                println("📱 Найдено токенов в БД: ${tokens.size}")
                if (tokens.isEmpty()) {
                    println("⚠️ В БД нет ни одного FCM токена! Клиенты не зарегистрировались.")
                }

                tokens.forEach { token ->
                    FirebaseService.sendPush(
                        token = token,
                        title = req.title,
                        body = req.message,
                        data = mapOf("type" to req.type, "payload" to req.payload)
                    )
                }
            }

            call.respond(HttpStatusCode.Created, mapOf("sent" to targetUserIds.size))
        }

        post("/mark-read") {
            val session = call.checkSession() ?: return@post
            val body = call.receive<Map<String, String>>()
            val notifId = body["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            transaction {
                NotificationsTable.update({
                    (NotificationsTable.id eq UUID.fromString(notifId)) and
                            ((NotificationsTable.targetUserId eq session.userId) or
                                    (NotificationsTable.targetUserId eq null))
                }) { it[isRead] = true }
            }
            call.respond(HttpStatusCode.OK)
        }

        post("/mark-all-read") {
            val session = call.checkSession() ?: return@post
            transaction {
                NotificationsTable.update({
                    // 🔥 ИСПРАВЛЕНО: помечаем и личные, и общие уведомления
                    ((NotificationsTable.targetUserId eq session.userId) or
                            (NotificationsTable.targetUserId eq null)) and
                            (NotificationsTable.isRead eq false)
                }) { it[isRead] = true }
            }
            call.respond(HttpStatusCode.OK)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 🎀 ПЕРСОНАЛЬНЫЙ РОЗОВЫЙ ТАБЕЛЬ для a.ermashkevich
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/personal-timesheet") {
        fun targetUserId(session: SessionManager.Session): UUID? {
            return transaction {
                UsersTable.selectAll()
                    .where { (UsersTable.id eq session.userId) and (UsersTable.login eq "a.ermashkevich") }
                    .limit(1)
                    .firstOrNull()
                    ?.get(UsersTable.id)?.value
            }
        }

        get {
            val session = call.checkSession() ?: return@get
            val targetUserId = targetUserId(session)
                ?: return@get call.respond(HttpStatusCode.Forbidden, "Personal timesheet is unavailable for this user")

            val year = call.request.queryParameters["year"]?.toIntOrNull()
            val month = call.request.queryParameters["month"]?.toIntOrNull()
            if (year == null || month == null || month !in 1..12) {
                return@get call.respond(HttpStatusCode.BadRequest, "year and month are required")
            }

            val defaultStart = JavaLocalDate.of(year, month, 1)
            val defaultEnd = defaultStart.withDayOfMonth(defaultStart.lengthOfMonth())

            val response = transaction {
                val rows = PersonalTimesheetTasksTable.selectAll()
                    .where {
                        (PersonalTimesheetTasksTable.userId eq targetUserId) and
                                (PersonalTimesheetTasksTable.year eq year) and
                                (PersonalTimesheetTasksTable.month eq month)
                    }
                    .orderBy(PersonalTimesheetTasksTable.sortOrder to SortOrder.ASC)
                    .orderBy(PersonalTimesheetTasksTable.id to SortOrder.ASC)
                    .toList()

                val periodStart = rows.firstOrNull()?.get(PersonalTimesheetTasksTable.periodStart)?.toString() ?: defaultStart.toString()
                val periodEnd = rows.firstOrNull()?.get(PersonalTimesheetTasksTable.periodEnd)?.toString() ?: defaultEnd.toString()
                val monthlyTaskId = rows.firstOrNull { it[PersonalTimesheetTasksTable.isMonthTask] }?.get(PersonalTimesheetTasksTable.id)?.value?.toString()

                PersonalTimesheetResponseDto(
                    userId = targetUserId.toString(),
                    year = year,
                    month = month,
                    periodStart = periodStart,
                    periodEnd = periodEnd,
                    monthlyTaskId = monthlyTaskId,
                    tasks = rows.map { row ->
                        PersonalTimesheetTaskDto(
                            id = row[PersonalTimesheetTasksTable.id].value.toString(),
                            userId = targetUserId.toString(),
                            year = year,
                            month = month,
                            periodStart = row[PersonalTimesheetTasksTable.periodStart].toString(),
                            periodEnd = row[PersonalTimesheetTasksTable.periodEnd].toString(),
                            name = row[PersonalTimesheetTasksTable.name],
                            description = row[PersonalTimesheetTasksTable.description],
                            hours = row[PersonalTimesheetTasksTable.hours],
                            category = row[PersonalTimesheetTasksTable.category],
                            status = row[PersonalTimesheetTasksTable.status],
                            isMonthTask = row[PersonalTimesheetTasksTable.isMonthTask],
                            sortOrder = row[PersonalTimesheetTasksTable.sortOrder]
                        )
                    }
                )
            }
            call.respond(HttpStatusCode.OK, response)
        }

        put {
            val session = call.checkSession() ?: return@put
            val targetUserId = targetUserId(session)
                ?: return@put call.respond(HttpStatusCode.Forbidden, "Personal timesheet is unavailable for this user")

            val request = try {
                call.receive<PersonalTimesheetSaveRequest>()
            } catch (e: Exception) {
                return@put call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
            }

            if (request.month !in 1..12) {
                return@put call.respond(HttpStatusCode.BadRequest, "Invalid month")
            }

            val periodStart = try { KtLocalDate.parse(request.periodStart) }
            catch (_: Exception) { return@put call.respond(HttpStatusCode.BadRequest, "Invalid periodStart") }
            val periodEnd = try { KtLocalDate.parse(request.periodEnd) }
            catch (_: Exception) { return@put call.respond(HttpStatusCode.BadRequest, "Invalid periodEnd") }

            if (periodStart > periodEnd) {
                return@put call.respond(HttpStatusCode.BadRequest, "periodStart must be before periodEnd")
            }

            transaction {
                PersonalTimesheetTasksTable.deleteWhere {
                    (PersonalTimesheetTasksTable.userId eq targetUserId) and
                            (PersonalTimesheetTasksTable.year eq request.year) and
                            (PersonalTimesheetTasksTable.month eq request.month)
                }

                val normalizedMonthlyTaskId = request.monthlyTaskId?.takeIf { id ->
                    request.tasks.any { it.id == id }
                }

                request.tasks.forEachIndexed { index, task ->
                    val taskId = runCatching { UUID.fromString(task.id) }.getOrNull() ?: UUID.randomUUID()
                    PersonalTimesheetTasksTable.insert {
                        it[PersonalTimesheetTasksTable.id] = taskId
                        it[PersonalTimesheetTasksTable.userId] = targetUserId
                        it[PersonalTimesheetTasksTable.year] = request.year
                        it[PersonalTimesheetTasksTable.month] = request.month
                        it[PersonalTimesheetTasksTable.periodStart] = periodStart
                        it[PersonalTimesheetTasksTable.periodEnd] = periodEnd
                        it[PersonalTimesheetTasksTable.name] = task.name.trim()
                        it[PersonalTimesheetTasksTable.description] = task.description.trim()
                        it[PersonalTimesheetTasksTable.hours] = task.hours.coerceAtLeast(0.0)
                        it[PersonalTimesheetTasksTable.category] = task.category.trim()
                        it[PersonalTimesheetTasksTable.status] = task.status.trim().ifBlank { "Not started" }
                        it[PersonalTimesheetTasksTable.isMonthTask] = normalizedMonthlyTaskId == task.id
                        it[PersonalTimesheetTasksTable.sortOrder] = index
                    }
                }
            }

            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "success" to true,
                    "year" to request.year,
                    "month" to request.month
                )
            )
        }
    }

    route("/api/v1/notification-preferences") {
        get {
            val session = call.checkSession() ?: return@get
            val row = transaction { NotificationPreferencesTable.selectAll().where { NotificationPreferencesTable.userId eq session.userId }.limit(1).firstOrNull() }
            call.respond(HttpStatusCode.OK, NotificationPreferencesResponseDto(
                tripEnabled = row?.get(NotificationPreferencesTable.tripEnabled) ?: true,
                vacationEnabled = row?.get(NotificationPreferencesTable.vacationEnabled) ?: true,
                dayoffEnabled = row?.get(NotificationPreferencesTable.dayoffEnabled) ?: true,
                expenseEnabled = row?.get(NotificationPreferencesTable.expenseEnabled) ?: true,
                payrollEnabled = row?.get(NotificationPreferencesTable.payrollEnabled) ?: true,
                ticketEnabled = row?.get(NotificationPreferencesTable.ticketEnabled) ?: true,
                tripVisibleToAll = if (row?.get(NotificationPreferencesTable.privacyDefaultsConfigured) == true) row[NotificationPreferencesTable.tripVisibleToAll] else true,
                tripTelegramBroadcast = if (row?.get(NotificationPreferencesTable.privacyDefaultsConfigured) == true) row[NotificationPreferencesTable.tripTelegramBroadcast] else true,
                tripChangeEnabled = row?.get(NotificationPreferencesTable.tripChangeEnabled) ?: true,
                vacationDecisionEnabled = row?.get(NotificationPreferencesTable.vacationDecisionEnabled) ?: true,
                expenseCreatedEnabled = row?.get(NotificationPreferencesTable.expenseCreatedEnabled) ?: true,
                ticketReceiptEnabled = row?.get(NotificationPreferencesTable.ticketReceiptEnabled) ?: true,
                telegramEnabled = row?.get(NotificationPreferencesTable.telegramEnabled) ?: false,
                telegramLinked = row?.get(NotificationPreferencesTable.telegramChatId) != null,
                telegramLinkCode = row?.get(NotificationPreferencesTable.telegramLinkCode),
                emailEnabled = row?.get(NotificationPreferencesTable.emailEnabled) ?: false,
                email = row?.get(NotificationPreferencesTable.email) ?: ""
            ))
        }
        put {
            val session = call.checkSession() ?: return@put
            val body = try { call.receive<Map<String, String>>() } catch (e: Exception) { return@put call.respond(HttpStatusCode.BadRequest, "Invalid JSON") }
            var generatedCode: String? = null
            transaction {
                val existing = NotificationPreferencesTable.selectAll().where { NotificationPreferencesTable.userId eq session.userId }.limit(1).firstOrNull()
                val telegramEnabled = body["telegramEnabled"]?.toBoolean() ?: existing?.get(NotificationPreferencesTable.telegramEnabled) ?: false
                generatedCode = if (telegramEnabled && existing?.get(NotificationPreferencesTable.telegramLinkCode).isNullOrBlank() && existing?.get(NotificationPreferencesTable.telegramChatId).isNullOrBlank()) {
                    (10000000L..99999999L).random().toString()
                } else existing?.get(NotificationPreferencesTable.telegramLinkCode)
                if (existing == null) {
                    NotificationPreferencesTable.insert {
                        it[id] = UUID.randomUUID(); it[userId] = session.userId
                        it[tripEnabled] = body["tripEnabled"]?.toBoolean() ?: true
                        it[vacationEnabled] = body["vacationEnabled"]?.toBoolean() ?: true
                        it[dayoffEnabled] = body["dayoffEnabled"]?.toBoolean() ?: true
                        it[expenseEnabled] = body["expenseEnabled"]?.toBoolean() ?: true
                        it[payrollEnabled] = body["payrollEnabled"]?.toBoolean() ?: true
                        it[ticketEnabled] = body["ticketEnabled"]?.toBoolean() ?: true
                        it[tripVisibleToAll] = body["tripVisibleToAll"]?.toBoolean() ?: true
                        it[tripTelegramBroadcast] = body["tripTelegramBroadcast"]?.toBoolean() ?: true
                        it[tripChangeEnabled] = body["tripChangeEnabled"]?.toBoolean() ?: true
                        it[vacationDecisionEnabled] = body["vacationDecisionEnabled"]?.toBoolean() ?: true
                        it[expenseCreatedEnabled] = body["expenseCreatedEnabled"]?.toBoolean() ?: true
                        it[ticketReceiptEnabled] = body["ticketReceiptEnabled"]?.toBoolean() ?: true
                        it[privacyDefaultsConfigured] = true
                        it[NotificationPreferencesTable.telegramEnabled] = telegramEnabled
                        it[telegramLinkCode] = generatedCode
                        it[emailEnabled] = body["emailEnabled"]?.toBoolean() ?: false
                        it[email] = body["email"] ?: ""
                    }
                } else {
                    NotificationPreferencesTable.update({ NotificationPreferencesTable.userId eq session.userId }) {
                        it[tripEnabled] = body["tripEnabled"]?.toBoolean() ?: existing[tripEnabled]
                        it[vacationEnabled] = body["vacationEnabled"]?.toBoolean() ?: existing[vacationEnabled]
                        it[dayoffEnabled] = body["dayoffEnabled"]?.toBoolean() ?: existing[dayoffEnabled]
                        it[expenseEnabled] = body["expenseEnabled"]?.toBoolean() ?: existing[expenseEnabled]
                        it[payrollEnabled] = body["payrollEnabled"]?.toBoolean() ?: existing[payrollEnabled]
                        it[ticketEnabled] = body["ticketEnabled"]?.toBoolean() ?: existing[ticketEnabled]
                        it[tripVisibleToAll] = body["tripVisibleToAll"]?.toBoolean() ?: existing[tripVisibleToAll]
                        it[tripTelegramBroadcast] = body["tripTelegramBroadcast"]?.toBoolean() ?: existing[tripTelegramBroadcast]
                        it[tripChangeEnabled] = body["tripChangeEnabled"]?.toBoolean() ?: existing[tripChangeEnabled]
                        it[vacationDecisionEnabled] = body["vacationDecisionEnabled"]?.toBoolean() ?: existing[vacationDecisionEnabled]
                        it[expenseCreatedEnabled] = body["expenseCreatedEnabled"]?.toBoolean() ?: existing[expenseCreatedEnabled]
                        it[ticketReceiptEnabled] = body["ticketReceiptEnabled"]?.toBoolean() ?: existing[ticketReceiptEnabled]
                        it[privacyDefaultsConfigured] = true
                        it[NotificationPreferencesTable.telegramEnabled] = telegramEnabled
                        if (generatedCode != null) it[telegramLinkCode] = generatedCode
                        it[emailEnabled] = body["emailEnabled"]?.toBoolean() ?: existing[emailEnabled]
                        it[email] = body["email"] ?: existing[email]
                    }
                }
            }
            call.respond(
                HttpStatusCode.OK,
                NotificationPreferencesUpdateResponseDto(
                    telegramLinkCode = generatedCode,
                    telegramEnabled = body["telegramEnabled"]?.toBoolean() ?: false
                )
            )
        }
    }

    route("/api/v1/rbac") {
        // 🔥 НОВЫЙ ЭНДПОИНТ: effective-права ТЕКУЩЕГО пользователя
        // Отдаёт объединённые права (role + overrides) — используется клиентом
        get("/my-permissions") {
            val session = call.checkSession() ?: return@get
            val permissions = com.proles.server.config.PermissionMiddleware
                .getPermissionsPublic(session.userId, session.role)
            call.respond(HttpStatusCode.OK, permissions)
        }
        // Получить все роли с их правами
        get("/roles") {
            if (!call.checkPermission(Permission.PERMISSIONS, "view")) return@get
            val roles = transaction {
                RolesTable.selectAll().map { roleRow ->
                    val roleId = roleRow[RolesTable.id].value
                    val permissions = RolePermissionsTable.selectAll()
                        .where { RolePermissionsTable.roleId eq roleId }
                        .map { permRow ->
                            RolePermissionDto(
                                permission = permRow[RolePermissionsTable.permission],
                                canView = permRow[RolePermissionsTable.canView],
                                canCreate = permRow[RolePermissionsTable.canCreate],
                                canEdit = permRow[RolePermissionsTable.canEdit],
                                canDelete = permRow[RolePermissionsTable.canDelete]
                            )
                        }
                    RoleDto(
                        id = roleId.toString(),
                        name = roleRow[RolesTable.name],
                        displayName = roleRow[RolesTable.displayName],
                        description = roleRow[RolesTable.description],
                        permissions = permissions
                    )
                }
            }
            call.respond(HttpStatusCode.OK, roles)
        }

        put("/roles/{roleId}") {
            if (!call.checkPermission(Permission.PERMISSIONS, "edit")) return@put
            val roleId = runCatching {
                UUID.fromString(call.parameters["roleId"])
            }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid roleId")
            val body = try { call.receive<List<RolePermissionUpdateDto>>() }
            catch (e: Exception) {
                println("❌ PUT /roles/$roleId: bad JSON: ${e.message}")
                return@put call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
            }
            println("📥 PUT /roles/$roleId: received ${body.size} permissions")
            var updated = 0
            var inserted = 0
            transaction {
                body.forEach { item ->
                    // 🔥 ИСПРАВЛЕНО: объявляем локальные переменные
                    val perm = item.permission
                    val cView = item.canView
                    val cCreate = item.canCreate
                    val cEdit = item.canEdit
                    val cDelete = item.canDelete

                    val exists = RolePermissionsTable.selectAll()
                        .where {
                            (RolePermissionsTable.roleId eq roleId) and
                                    (RolePermissionsTable.permission eq perm)
                        }.singleOrNull()

                    if (exists != null) {
                        RolePermissionsTable.update({
                            (RolePermissionsTable.roleId eq roleId) and
                                    (RolePermissionsTable.permission eq perm)
                        }) {
                            it[RolePermissionsTable.canView] = cView      // ✅ Используем cView
                            it[RolePermissionsTable.canCreate] = cCreate  // ✅ Используем cCreate
                            it[RolePermissionsTable.canEdit] = cEdit      // ✅ Используем cEdit
                            it[RolePermissionsTable.canDelete] = cDelete  // ✅ Используем cDelete
                        }
                        updated++
                        println("  ✅ Updated $perm: V=$cView C=$cCreate E=$cEdit D=$cDelete")
                    } else {
                        RolePermissionsTable.insert {
                            it[RolePermissionsTable.id] = UUID.randomUUID()
                            it[RolePermissionsTable.roleId] = roleId
                            it[RolePermissionsTable.permission] = perm
                            it[RolePermissionsTable.canView] = cView
                            it[RolePermissionsTable.canCreate] = cCreate
                            it[RolePermissionsTable.canEdit] = cEdit
                            it[RolePermissionsTable.canDelete] = cDelete
                        }
                        inserted++
                        println("  ➕ Inserted $perm")
                    }
                }
            }
            com.proles.server.config.PermissionMiddleware.invalidateAllCache()
            println("✅ Role $roleId updated: $updated updated, $inserted inserted")
            call.respond(HttpStatusCode.OK)
        }

        // Получить effective-права пользователя (role + overrides merged)
        get("/users/{userId}/permissions") {
            if (!call.checkPermission(Permission.PERMISSIONS, "view")) return@get
            val userId = runCatching {
                UUID.fromString(call.parameters["userId"])
            }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)

            // Получаем роль пользователя
            val userRole = transaction {
                UsersTable.selectAll()
                    .where { UsersTable.id eq userId }
                    .singleOrNull()?.get(UsersTable.role)
            } ?: return@get call.respond(HttpStatusCode.NotFound, "User not found")

            // Получаем roleId
            val roleId = transaction {
                RolesTable.selectAll()
                    .where { RolesTable.name eq userRole }
                    .singleOrNull()?.get(RolesTable.id)?.value
            }

            // Получаем все права роли
            val rolePermissions: Map<String, Map<String, Boolean>> = if (roleId != null) {
                transaction {
                    RolePermissionsTable.selectAll()
                        .where { RolePermissionsTable.roleId eq roleId }
                        .associate { row ->
                            row[RolePermissionsTable.permission] to mapOf(
                                "canView" to row[RolePermissionsTable.canView],
                                "canCreate" to row[RolePermissionsTable.canCreate],
                                "canEdit" to row[RolePermissionsTable.canEdit],
                                "canDelete" to row[RolePermissionsTable.canDelete]
                            )
                        }
                }
            } else emptyMap()

            // Получаем overrides пользователя
            val overrides: Map<String, Map<String, Boolean?>> = transaction {
                UserPermissionOverridesTable.selectAll()
                    .where { UserPermissionOverridesTable.userId eq userId }
                    .associate { row ->
                        row[UserPermissionOverridesTable.permission] to mapOf(
                            "canView" to row[UserPermissionOverridesTable.canView],
                            "canCreate" to row[UserPermissionOverridesTable.canCreate],
                            "canEdit" to row[UserPermissionOverridesTable.canEdit],
                            "canDelete" to row[UserPermissionOverridesTable.canDelete]
                        )
                    }
            }

            // 🎯 Формируем список UserEffectivePermissionDto (без mapOf!)
            val result = Permission.entries.map { perm ->
                val rolePerm = rolePermissions[perm.key]
                val override = overrides[perm.key]

                UserEffectivePermissionDto(
                    permission = perm.key,
                    canView = override?.get("canView") ?: rolePerm?.get("canView") ?: false,
                    canCreate = override?.get("canCreate") ?: rolePerm?.get("canCreate") ?: false,
                    canEdit = override?.get("canEdit") ?: rolePerm?.get("canEdit") ?: false,
                    canDelete = override?.get("canDelete") ?: rolePerm?.get("canDelete") ?: false,
                    isOverride = override != null
                )
            }

            call.respond(HttpStatusCode.OK, result)
        }

        put("/users/{userId}/permissions") {
            if (!call.checkPermission(Permission.PERMISSIONS, "edit")) return@put
            val userId = runCatching {
                UUID.fromString(call.parameters["userId"])
            }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)

            println("📥 PUT /users/$userId/permissions: receiving overrides")

            // 🎯 Принимаем List<UserPermissionOverrideDto> вместо Map<String, Any?>
            val body = try { call.receive<List<UserPermissionOverrideDto>>() }
            catch (e: Exception) {
                println("❌ PUT /users/$userId: bad JSON: ${e.message}")
                return@put call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
            }

            println("📥 PUT /users/$userId: received ${body.size} overrides")

            transaction {
                // Удаляем все старые overrides пользователя
                UserPermissionOverridesTable.deleteWhere {
                    UserPermissionOverridesTable.userId eq userId
                }

                // Вставляем новые
                body.forEach { item ->
                    // Если все поля null — пропускаем (используется значение из роли)
                    if (item.canView == null && item.canCreate == null &&
                        item.canEdit == null && item.canDelete == null) {
                        return@forEach
                    }

                    UserPermissionOverridesTable.insert {
                        it[UserPermissionOverridesTable.id] = UUID.randomUUID()
                        it[UserPermissionOverridesTable.userId] = userId
                        it[UserPermissionOverridesTable.permission] = item.permission
                        it[UserPermissionOverridesTable.canView] = item.canView
                        it[UserPermissionOverridesTable.canCreate] = item.canCreate
                        it[UserPermissionOverridesTable.canEdit] = item.canEdit
                        it[UserPermissionOverridesTable.canDelete] = item.canDelete
                    }
                    println("  ✅ Override set for ${item.permission}: V=${item.canView} C=${item.canCreate} E=${item.canEdit} D=${item.canDelete}")
                }
            }

            com.proles.server.config.PermissionMiddleware.invalidateUserCache(userId)
            println("✅ User $userId overrides updated")
            call.respond(HttpStatusCode.OK)
        }

        // Получить все доступные permissions (из enum)
        get("/permissions") {
            if (!call.checkPermission(Permission.PERMISSIONS, "view")) return@get
            val permissions = Permission.entries.map { perm ->   // ✅ Используем импорт + явный параметр
                PermissionDto(key = perm.key, displayName = perm.displayName)
            }
            call.respond(HttpStatusCode.OK, permissions)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 🎫 TICKETS: DTO для сериализации
    // ─────────────────────────────────────────────────────────────
    @Serializable
    data class TicketRecipientDto(
        val userId: String,
        val userName: String
    )

    @Serializable
    data class TicketDto(
        val id: String,
        val projectId: String,
        val projectName: String,
        val fileName: String,
        val fileType: String,
        val fileSize: Long,
        val description: String,
        val uploadedAt: Long,
        val viewedAt: Long? = null,
        val downloadUrl: String,
        val sendToAccountant: Boolean = false,
        val accountantEmail: String = "",
        val amount: Double = 0.0,        // 
        val currency: String = "RUB",    // 
        val hasReceipt: Boolean = false,  // 🆕 Флаг наличия чека
        val recipients: List<TicketRecipientDto> = emptyList()
    )

    // ─────────────────────────────────────────────────────────────
    // 🎫 TICKETS: Загрузка и просмотр билетов
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/tickets") {
        delete("/{ticketId}") {
            if (!call.checkPermission(Permission.TICKETS, "delete")) return@delete
            val session = call.checkSession() ?: return@delete
            val ticketId = runCatching { UUID.fromString(call.parameters["ticketId"]) }
                .getOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)

            // Получаем данные билета (для лога и проверки прав)
            val ticket = transaction {
                TicketsTable.selectAll()
                    .where { TicketsTable.id eq ticketId }
                    .singleOrNull()
            }

            if (ticket == null) {
                return@delete call.respond(HttpStatusCode.NotFound, "Ticket not found")
            }

            // Проверка прав: удалить может только загрузивший или admin/superadmin
            if (session.role !in listOf("admin", "superadmin", "director") &&
                ticket[TicketsTable.uploadedBy].value != session.userId) {
                return@delete call.respond(HttpStatusCode.Forbidden, "Cannot delete another user's ticket")
            }

            transaction {
                // Удаляем получателей
                TicketRecipientsTable.deleteWhere { TicketRecipientsTable.ticketId eq ticketId }
                // Удаляем сам билет
                TicketsTable.deleteWhere { TicketsTable.id eq ticketId }
            }

            // Удаляем файл с диска
            val filePath = ticket[TicketsTable.filePath]
            val file = java.io.File("." + filePath)
            if (file.exists()) {
                file.delete()
                println("🗑️ Deleted ticket file: $filePath")
            }

            println("✅ Ticket $ticketId deleted by ${session.userId}")
            call.respond(HttpStatusCode.NoContent)
        }

        // Загрузка билета
        post("/upload") {
            if (!call.checkPermission(Permission.TICKETS, "create")) return@post
            val request = try {
                call.receive<TicketUploadRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
            }
            if (request.projectId.isBlank() || request.fileBase64.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, "projectId and fileBase64 required")
            }
            val fileBytes = try { java.util.Base64.getDecoder().decode(request.fileBase64) }
            catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest, "Invalid Base64 data") }
            val session = call.checkSession() ?: return@post
            val ticketId = UUID.randomUUID()
            val uniqueFileName = "${ticketId}_${request.fileName}"
            val uploadDir = java.io.File("uploads/tickets").apply { mkdirs() }
            java.io.File(uploadDir, uniqueFileName).writeBytes(fileBytes)
            
            // 🆕 Обработка чека если предоставлен
            var receiptFilePath: String? = null
            var receiptOriginalName: String? = null
            var receiptFileType: String? = null
            
            if (!request.receiptBase64.isNullOrBlank()) {
                val receiptBytes = try { java.util.Base64.getDecoder().decode(request.receiptBase64) }
                catch (e: Exception) { 
                    println("⚠️ Invalid receipt Base64: ${e.message}")
                    null 
                }
                
                if (receiptBytes != null) {
                    val companyFolder = "Proles Company"
                    val receiptMonth = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))
                    val receiptDir = java.io.File("uploads/receipts/$receiptMonth/$companyFolder").apply { mkdirs() }
                    val receiptFileName = request.receiptFileName ?: "receipt"
                    val safeReceiptName = receiptFileName.replace(Regex("[^A-Za-zА-Яа-яЁё0-9._ -]"), "_")
                    val uniqueReceiptFileName = "${ticketId}_$safeReceiptName"
                    java.io.File(receiptDir, uniqueReceiptFileName).writeBytes(receiptBytes)
                    receiptFilePath = "/uploads/receipts/$receiptMonth/$companyFolder/$uniqueReceiptFileName"
                    receiptOriginalName = receiptFileName
                    receiptFileType = request.receiptFileType ?: "application/octet-stream"
                    println("✅ Receipt saved: $receiptFilePath (${receiptBytes.size / 1024} KB)")
                }
            }
            
            transaction {
                // Находим UUID пользователя COMPANY для расходов компании
                val companyUser = UsersTable.selectAll()
                    .where { (UsersTable.name eq "Proles Company") or (UsersTable.login eq "proles_company") }
                    .singleOrNull()
                val companyUserId = companyUser?.let { it[UsersTable.id].value }
                
                // Загрузка билета
                TicketsTable.insert {
                    it[TicketsTable.id] = ticketId
                    it[uploadedBy] = session.userId
                    it[TicketsTable.projectId] = UUID.fromString(request.projectId)
                    it[TicketsTable.fileName] = uniqueFileName
                    it[TicketsTable.originalName] = request.fileName
                    it[filePath] = "/uploads/tickets/$uniqueFileName"
                    it[TicketsTable.fileType] = request.fileType
                    it[fileSize] = fileBytes.size.toLong()
                    it[TicketsTable.sendToAccountant] = request.sendToAccountant
                    it[TicketsTable.accountantEmail] = request.accountantEmail
                    it[TicketsTable.amount] = request.amount            // 
                    it[TicketsTable.currency] = request.currency        // 
                    it[TicketsTable.description] = request.description
                    it[uploadedAt] = System.currentTimeMillis()
                    // 🆕 Сохраняем данные чека
                    it[TicketsTable.receiptPath] = receiptFilePath
                    it[TicketsTable.receiptOriginalName] = receiptOriginalName
                    it[TicketsTable.receiptFileType] = receiptFileType
                }
                //  Автоматически создаём расход "Билет" от имени COMPANY (если есть сумма)
                if (request.amount > 0.0) {
                    val expenseUserId = companyUserId ?: session.userId
                    val hasReceipt = receiptFilePath != null
                    transaction {
                        // ✅ Используем java.time вместо kotlinx.datetime (проще и всегда доступно)
                        val todayJava = java.time.LocalDate.now()
                        val todayKt = kotlinx.datetime.LocalDate(todayJava.year, todayJava.monthValue, todayJava.dayOfMonth)

                        val expenseId = UUID.randomUUID()
                        ExpensesTable.insert {
                            it[ExpensesTable.id] = expenseId
                            it[userId] = expenseUserId
                            it[projectId] = UUID.fromString(request.projectId)
                            it[date] = todayKt
                            it[type] = "OTHER"
                            it[name] = "🎫 Билет: ${request.fileName.take(50)}"
                            it[amount] = request.amount
                            it[currency] = request.currency
                            it[comment] = "Автоматически создан при загрузке билета. ${request.description.takeIf { it.isNotBlank() } ?: ""}"
                            it[receiptSubmitted] = hasReceipt
                            it[hasReceiptPhoto] = hasReceipt
                            it[createdAt] = System.currentTimeMillis()
                        }
                        if (hasReceipt && receiptFilePath != null) {
                            ExpenseReceiptsTable.insert {
                                it[id] = UUID.randomUUID()
                                it[ExpenseReceiptsTable.expenseId] = expenseId
                                it[imageUrl] = receiptFilePath!!
                                it[uploadedAt] = System.currentTimeMillis()
                            }
                        }
                    }
                    println("✅ Auto-expense created: ${request.amount} ${request.currency} for ticket $uniqueFileName (userId=$expenseUserId, hasReceipt=$hasReceipt)")
                }

                request.recipientIds.forEach { recipientId ->
                    runCatching {
                        TicketRecipientsTable.insert {
                            it[TicketRecipientsTable.id] = UUID.randomUUID()
                            it[TicketRecipientsTable.ticketId] = ticketId
                            it[TicketRecipientsTable.userId] = UUID.fromString(recipientId)
                        }
                    }
                }
            }
            val projectName = transaction {
                ProjectsTable.selectAll().where { ProjectsTable.id eq UUID.fromString(request.projectId) }
                    .single()[ProjectsTable.name]
            }
            val senderName = transaction {
                UsersTable.selectAll().where { UsersTable.id eq session.userId }.single()[UsersTable.name]
            }
            val recipientUuids = request.recipientIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            transaction {
                recipientUuids.forEach { recipientId ->
                    NotificationsTable.insert {
                        it[id] = UUID.randomUUID()
                        it[targetUserId] = recipientId
                        it[senderUserId] = session.userId
                        it[type] = "TICKET"
                        it[title] = "🎫 Новый билет"
                        it[message] = "$senderName загрузил(а) билет по проекту «$projectName»"
                        it[payload] = json.encodeToString(mapOf(
                            "ticketId" to ticketId.toString(),
                            "projectId" to request.projectId,
                            "projectName" to projectName
                        ))
                        it[createdAt] = System.currentTimeMillis()
                    }
                }
            }
            CoroutineScope(Dispatchers.IO).launch {
                val tokens = transaction {
                    FcmTokensTable.selectAll()
                        .where { FcmTokensTable.userId inList recipientUuids }
                        .map { it[FcmTokensTable.token] }
                }
                tokens.forEach { token ->
                    FirebaseService.sendPush(token = token, title = "🎫 Новый билет",
                        body = "$senderName загрузил билет по проекту «$projectName»",
                        data = mapOf("type" to "TICKET"))
                }
            }
            //  Отправка email бухгалтеру (если выбрана галочка)
            if (request.sendToAccountant && request.accountantEmail.isNotBlank()) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Читаем файл с диска
                        val ticketFile = java.io.File("uploads/tickets/$uniqueFileName")
                        val fileBytes = if (ticketFile.exists()) ticketFile.readBytes() else fileBytes

                        val subject = "🎫 Новый билет по проекту «$projectName»"
                        val htmlBody = """
                        <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                            <h2 style="color: #2E7D32;">🎫 Новый билет загружен</h2>
                            <table style="width: 100%; border-collapse: collapse; margin: 16px 0;">
                                <tr><td style="padding: 8px; background: #f5f5f5; font-weight: bold;">Загрузил:</td>
                                    <td style="padding: 8px;">$senderName</td></tr>
                                <tr><td style="padding: 8px; background: #f5f5f5; font-weight: bold;">Проект:</td>
                                    <td style="padding: 8px;">$projectName</td></tr>
                                ${if (request.amount > 0.0) """
                                <tr><td style="padding: 8px; background: #FFF3E0; font-weight: bold; color: #E65100;">💰 Стоимость:</td>
                                    <td style="padding: 8px; background: #FFF3E0; font-weight: bold; color: #E65100;">
                                        ${"%.2f".format(request.amount)} ${request.currency}
                                    </td></tr>
                                """ else ""}
                                ${if (request.description.isNotBlank()) """
                                <tr><td style="padding: 8px; background: #f5f5f5; font-weight: bold;">Описание:</td>
                                    <td style="padding: 8px;">${request.description}</td></tr>
                                """ else ""}
                            </table>
                            <p style="color: #666; font-size: 12px; margin-top: 20px;">
                                Отправлено автоматически из системы ProlesSys
                            </p>
                        </div>
                    """.trimIndent()

                        val attachment = com.proles.server.config.EmailAttachment(
                            fileName = request.fileName,
                            bytes = fileBytes,
                            mimeType = request.fileType
                        )

                        com.proles.server.config.EmailService.sendHtmlEmail(
                            to = request.accountantEmail,
                            subject = subject,
                            htmlBody = htmlBody,
                            attachments = listOf(attachment)
                        )
                    } catch (e: Exception) {
                        println("❌ Failed to send email to accountant: ${e.message}")
                    }
                }
            }
            NotificationService.notifyUsers(
                recipientUuids, session.userId, "TICKET", "🎫 Новый билет",
                "$senderName загрузил билет по проекту «$projectName»",
                json.encodeToString(mapOf("ticketId" to ticketId.toString(), "projectId" to request.projectId)),
                "ticket"
            )
            if (request.receiptBase64 != null && request.amount > 0.0) {
                val financeRecipients = transaction {
                    UsersTable.selectAll().where { (UsersTable.role eq "superadmin") or (UsersTable.role eq "director") or (UsersTable.role eq "admin") }.map { it[UsersTable.id].value }.distinct()
                }
                NotificationService.notifyUsers(
                    financeRecipients, session.userId, "TICKET_RECEIPT", "🧷 Чек билета сохранён",
                    "Расход на Proles Company: ${"%.2f".format(request.amount)} ${request.currency}",
                    json.encodeToString(mapOf("ticketId" to ticketId.toString(), "receiptPath" to (receiptFilePath ?: ""))),
                    "ticketReceipt"
                )
            }
            call.respond(HttpStatusCode.Created, mapOf("id" to ticketId.toString(), "fileName" to uniqueFileName))
        }


        // 📥 Свои билеты (получатель)
        get("/my") {
            if (!call.checkPermission(Permission.TICKETS, "view")) return@get
            val session = call.checkSession() ?: return@get
            val tickets = transaction {
                (TicketRecipientsTable innerJoin TicketsTable)
                    .selectAll()
                    .where { TicketRecipientsTable.userId eq session.userId }
                    .orderBy(TicketsTable.uploadedAt to SortOrder.DESC)
                    .map { row ->
                        val ticketId = row[TicketsTable.id].value
                        val projectId = row[TicketsTable.projectId].value
                        val projectName = ProjectsTable.selectAll()
                            .where { ProjectsTable.id eq projectId }.single()[ProjectsTable.name]
                        TicketDto(
                            id = ticketId.toString(),
                            projectId = projectId.toString(),
                            projectName = projectName,
                            fileName = row[TicketsTable.originalName],
                            fileType = row[TicketsTable.fileType],
                            fileSize = row[TicketsTable.fileSize],
                            description = row[TicketsTable.description],
                            uploadedAt = row[TicketsTable.uploadedAt],
                            viewedAt = row[TicketRecipientsTable.viewedAt],
                            downloadUrl = row[TicketsTable.filePath],
                            sendToAccountant = row[TicketsTable.sendToAccountant],
                            accountantEmail = row[TicketsTable.accountantEmail],
                            amount = row[TicketsTable.amount],          // 
                            currency = row[TicketsTable.currency],      // 
                            hasReceipt = row[TicketsTable.receiptPath] != null,  // 🆕 Флаг наличия чека
                            recipients = emptyList()
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, tickets)
        }

        // 📥 Все билеты (для админа)
        get("/all") {
            if (!call.checkPermission(Permission.TICKETS, "view")) return@get
            val tickets = transaction {
                (TicketsTable innerJoin ProjectsTable)
                    .selectAll()
                    .orderBy(TicketsTable.uploadedAt to SortOrder.DESC)
                    .map { row ->
                        val ticketId = row[TicketsTable.id].value
                        val recipients = TicketRecipientsTable.selectAll()
                            .where { TicketRecipientsTable.ticketId eq ticketId }
                            .map { recipientRow ->
                                val recipientId = recipientRow[TicketRecipientsTable.userId].value
                                val recipientName = UsersTable.selectAll()
                                    .where { UsersTable.id eq recipientId }
                                    .singleOrNull()?.get(UsersTable.name) ?: "Неизвестный"
                                TicketRecipientDto(userId = recipientId.toString(), userName = recipientName)
                            }
                        TicketDto(
                            id = ticketId.toString(),
                            projectId = row[TicketsTable.projectId].value.toString(),
                            projectName = row[ProjectsTable.name],
                            fileName = row[TicketsTable.originalName],
                            fileType = row[TicketsTable.fileType],
                            fileSize = row[TicketsTable.fileSize],
                            description = row[TicketsTable.description],
                            sendToAccountant = row[TicketsTable.sendToAccountant],
                            accountantEmail = row[TicketsTable.accountantEmail],
                            amount = row[TicketsTable.amount],          // 
                            currency = row[TicketsTable.currency],      // 
                            uploadedAt = row[TicketsTable.uploadedAt],
                            hasReceipt = row[TicketsTable.receiptPath] != null,  // 🆕 Флаг наличия чека
                            recipients = recipients,
                            downloadUrl = row[TicketsTable.filePath]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, tickets)
        }

        // 👁 Отметить как просмотренный
        post("/{ticketId}/view") {
            if (!call.checkPermission(Permission.TICKETS, "view")) return@post
            val session = call.checkSession() ?: return@post
            val ticketId = runCatching { UUID.fromString(call.parameters["ticketId"]) }
                .getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            transaction {
                TicketRecipientsTable.update({
                    (TicketRecipientsTable.ticketId eq ticketId) and
                            (TicketRecipientsTable.userId eq session.userId)
                }) { it[viewedAt] = System.currentTimeMillis() }
            }
            call.respond(HttpStatusCode.OK)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 💰 PAYROLL: Зарплаты
    // ─────────────────────────────────────────────────────────────
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
                println("❌ POST /components: bad JSON: ${e.message}")
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
            }

            println("📥 POST /components: type=${body.type}, userId=${body.userId}")

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

            println("✅ Component created: $componentId")
            call.respond(HttpStatusCode.Created, mapOf("id" to componentId.toString()))
        }

        // 🗑 Удалить компонент зарплаты
        delete("/components/{componentId}") {
            if (!call.checkPermission(Permission.PAYROLL, "delete")) return@delete
            val componentId = runCatching {
                UUID.fromString(call.parameters["componentId"])
            }.getOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid componentId")

            // Проверяем, что компонент принадлежит пользователю (или текущий пользователь — админ)
            val session = call.checkSession() ?: return@delete
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

            println("✅ Component $componentId deleted")
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
                println("❌ PUT /components: bad JSON: ${e.message}")
                return@put call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
            }

            val session = call.checkSession() ?: return@put
            
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

            println("✅ Component $componentId updated")
            call.respond(HttpStatusCode.OK, mapOf("id" to componentId.toString()))
        }

        // Рассчитать зарплату за период
        post("/calculate") {
            if (!call.checkPermission(Permission.PAYROLL, "create")) return@post

            val body = try { call.receive<CalculateSalaryRequest>() }
            catch (e: Exception) {
                println("❌ POST /calculate: bad JSON: ${e.message}")
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
            }

            println("📥 POST /calculate: userId=${body.userId}, year=${body.year}, month=${body.month}")

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

            println("✅ Salary calculated: fixed=${breakdown.fixed}, piece=${breakdown.piece}, hourly=${breakdown.hourly}, bonus=${breakdown.bonus}, total=${breakdown.total}")

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

            println("📊 GET /payroll/export: year=$year, month=$month")

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

            println("✅ Export generated for ${exportData.size} employees")

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