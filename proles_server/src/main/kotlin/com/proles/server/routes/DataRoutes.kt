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
        "CONTRACTORS" to "👷 Подрядчики",
        "MATERIALS" to "🧱 Материалы",
        "EQUIPMENT" to "🔧 Оборудование",
        "TRANSPORT" to "🚚 Транспорт Доп.",
        "ROAD" to "🚗 Транспорт",  // для обратной совместимости
        "MANAGER_COMMISSION" to "💼 Комиссия менеджеру",
        "FINES" to "⚠️ Штрафы",
        "CREDIT" to "🏦 Кредит",
        "OTHER" to "📦 Другое"
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
    val currency: String = "RUB"     // 
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
                val query = TimeEntriesTable.selectAll()
                    .where { TimeEntriesTable.userId eq UUID.fromString(userIdParam) }
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
                            sellingPrice = row[ProjectsTable.sellingPrice]
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

        post("/upload-receipt") {
            if (call.checkSession() == null) return@post

            //  НОВЫЙ ПОДХОД: принимаем JSON вместо multipart
            val requestBody = try {
                call.receive<Map<String, String>>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
                return@post
            }

            val expenseId = requestBody["expenseId"]
            val imageBase64 = requestBody["imageBase64"]

            if (expenseId.isNullOrBlank() || imageBase64.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Missing expenseId or imageBase64")
                return@post
            }

            // Декодируем Base64 обратно в ByteArray
            val imageBytes = try {
                Base64.getDecoder().decode(imageBase64)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid Base64 image data")
                return@post
            }

            // 🔥 Получаем расход из БД для валидации и извлечения userId/projectId
            val expense = transaction {
                ExpensesTable.selectAll()
                    .where { ExpensesTable.id eq UUID.fromString(expenseId) }
                    .singleOrNull()
            }

            if (expense == null) {
                call.respond(HttpStatusCode.NotFound, "Expense not found")
                return@post
            }

            val expenseUserId = expense[ExpensesTable.userId].value
            val expenseProjectId = expense[ExpensesTable.projectId].value

            // 🔒 Проверка безопасности: только владелец или админ может загружать
//            if (expenseUserId != session.userId && session.role !in listOf("admin", "director")) {
//                println("⚠️ Попытка загрузки чужого чека: user=${session.userId}, owner=$expenseUserId")
//                call.respond(HttpStatusCode.Forbidden, "Access denied: not your expense")
//                return@post
//            }

            // 📁 Структура: uploads/receipts/{userId}/{projectId}/
            val uploadDir = java.io.File("uploads/receipts/$expenseUserId/$expenseProjectId").apply {
                mkdirs()
            }

            // 📸 Имя файла: timestamp_уникальныйID.jpg
            val timestamp = System.currentTimeMillis()
            val uniqueId = UUID.randomUUID().toString().take(8)
            val fileName = "${timestamp}_${uniqueId}.jpg"

            java.io.File(uploadDir, fileName).writeBytes(imageBytes!!)

            // 🔗 URL для доступа к фото
            val imageUrl = "/uploads/receipts/$expenseUserId/$expenseProjectId/$fileName"

            val receiptId = UUID.randomUUID()
            transaction {
                ExpenseReceiptsTable.insert {
                    it[id] = receiptId
                    it[ExpenseReceiptsTable.expenseId] = UUID.fromString(expenseId)
                    it[ExpenseReceiptsTable.imageUrl] = imageUrl
                    it[uploadedAt] = System.currentTimeMillis()
                }
                ExpensesTable.update({ ExpensesTable.id eq UUID.fromString(expenseId) }) {
                    it[hasReceiptPhoto] = true
                    it[receiptSubmitted] = true  //  Автоматически подтверждаем чек
                }
            }

            println("✅ Фото сохранено: $imageUrl (${imageBytes!!.size / 1024} КБ) | user=$expenseUserId, project=$expenseProjectId")
            call.respond(HttpStatusCode.Created, mapOf("id" to receiptId.toString(), "url" to imageUrl))
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
                            hasReceiptPhoto = row[ExpensesTable.hasReceiptPhoto]  //  ДОБАВЬТЕ
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
                val query = (ExpensesTable innerJoin ProjectsTable)
                    .selectAll()
                    .where { ExpensesTable.userId eq UUID.fromString(userIdParam) }
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
                            hasReceiptPhoto = row[ExpensesTable.hasReceiptPhoto]  //  ДОБАВЬТЕ
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
                    it[hasReceiptPhoto] = exp.hasReceiptPhoto  //  ДОБАВЬТЕ
                    it[createdAt] = System.currentTimeMillis()
                }
                exp.copy(id = id.toString())
            }
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
                    it[hasReceiptPhoto] = exp.hasReceiptPhoto  //  ДОБАВЬТЕ
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
                            name = row[IncomesTable.name],
                            amount = row[IncomesTable.amount],
                            currency = row[IncomesTable.currency],
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
                val query = (IncomesTable leftJoin ProjectsTable)
                    .selectAll()
                    .where { IncomesTable.userId eq UUID.fromString(userIdParam) }
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
                            name = row[IncomesTable.name],
                            amount = row[IncomesTable.amount],
                            currency = row[IncomesTable.currency],
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
                    it[name] = income.name
                    it[amount] = income.amount
                    it[currency] = income.currency
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
                    it[name] = income.name
                    it[amount] = income.amount
                    it[currency] = income.currency
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
    // 👥 USERS
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/users") {
        get {
            if (!call.checkPermission(Permission.EMPLOYEES, "view")) return@get
            val users = transaction {
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
                            role = row[UsersTable.role],
                            position = row[UsersTable.position] ?: "",
                            defaultRateType = row[UsersTable.defaultRateType],
                            defaultRate = row[UsersTable.defaultRate],
                            defaultCurrency = row[UsersTable.defaultCurrency]
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
                    it[email] = user.login + "@proles.local"
                    it[login] = user.login
                    it[lastName] = user.lastName
                    it[firstName] = user.firstName
                    it[middleName] = user.middleName
                    it[name] = listOf(user.lastName, user.firstName, user.middleName).filter { s -> s.isNotBlank() }.joinToString(" ")
                    it[passwordHash] = hash
                    it[role] = user.role
                    it[position] = user.position
                    it[defaultRateType] = user.defaultRateType
                    it[defaultRate] = user.defaultRate
                    it[defaultCurrency] = user.defaultCurrency
                }
                user
            }
            call.respond(HttpStatusCode.Created, created)
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
                    it[lastName] = user.lastName
                    it[firstName] = user.firstName
                    it[middleName] = user.middleName
                    it[name] = listOf(user.lastName, user.firstName, user.middleName).filter { s -> s.isNotBlank() }.joinToString(" ")
                    it[passwordHash] = hash
                    it[role] = user.role
                    it[position] = user.position
                    it[defaultRateType] = user.defaultRateType
                    it[defaultRate] = user.defaultRate
                    it[defaultCurrency] = user.defaultCurrency
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
    // 🏖 VACATIONS
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/vacations") {
        //  Эндпоинт для админа: все отпуска всех сотрудников
        get("/all") {
            if (!call.checkPermission(Permission.VACATIONS_ALL, "view")) return@get

            // Фильтр по году (по умолчанию — текущий)
            val yearParam = call.request.queryParameters["year"]?.toIntOrNull()
            val targetYear = yearParam ?: java.time.LocalDate.now().year

            val vacations = transaction {
                val query = VacationsTable.innerJoin(UsersTable).selectAll()
                val yearFiltered = if (yearParam != null) {
                    // Фильтруем по пересечению периода отпуска с указанным годом
                    val startOfYear = kotlinx.datetime.LocalDate(yearParam, 1, 1)
                    val endOfYear = kotlinx.datetime.LocalDate(yearParam, 12, 31)
                    query.andWhere {
                        (VacationsTable.start lessEq endOfYear) and (VacationsTable.end greaterEq startOfYear)
                    }
                } else query

                yearFiltered
                    .orderBy(VacationsTable.start to SortOrder.ASC)
                    .map { row ->
                        VacationDto(
                            id = row[VacationsTable.id].value.toString(),
                            userId = row[VacationsTable.userId].value.toString(),
                            start = row[VacationsTable.start].toString(),
                            end = row[VacationsTable.end].toString()
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, vacations)
        }

        get {
            if (call.checkSession() == null) return@get
            val userIdParam = call.request.queryParameters["userId"]
            if (userIdParam.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "userId parameter required")
                return@get
            }
            val vacations = transaction {
                VacationsTable.selectAll()
                    .where { VacationsTable.userId eq UUID.fromString(userIdParam) }
                    .orderBy(VacationsTable.start to SortOrder.ASC)
                    .map { row ->
                        VacationDto(
                            id = row[VacationsTable.id].value.toString(),
                            userId = row[VacationsTable.userId].value.toString(),
                            start = row[VacationsTable.start].toString(),
                            end = row[VacationsTable.end].toString()
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, vacations)
        }

        post {
            if (call.checkSession() == null) return@post
            val vacation = try {
                call.receive<VacationDto>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}"); return@post
            }
            val startDate = KtLocalDate.parse(vacation.start)
            val endDate = KtLocalDate.parse(vacation.end)
            if (endDate < startDate) {
                call.respond(HttpStatusCode.BadRequest, "End date cannot be before start date")
                return@post
            }
            val created = transaction {
                VacationsTable.insert {
                    it[VacationsTable.id] = UUID.fromString(vacation.id)
                    it[VacationsTable.userId] = UUID.fromString(vacation.userId)
                    it[VacationsTable.start] = startDate
                    it[VacationsTable.end] = endDate
                }
                VacationDto(vacation.id, vacation.userId, vacation.start, vacation.end)
            }

            // 🔥 ОТПРАВКА УВЕДОМЛЕНИЙ ОБ ОТПУСКЕ
            val session = call.checkSession()!!
            val senderName = transaction {
                UsersTable.selectAll()
                    .where { UsersTable.id eq session.userId }
                    .single()[UsersTable.name]
            }

            val daysCount = endDate.toEpochDays() - startDate.toEpochDays() + 1
            val formattedStart = "%02d.%02d.%04d".format(startDate.dayOfMonth, startDate.monthNumber, startDate.year)
            val formattedEnd = "%02d.%02d.%04d".format(endDate.dayOfMonth, endDate.monthNumber, endDate.year)

            val notifTitle = "🏖 Новый отпуск"
            val notifMessage = buildString {
                appendLine("👤 $senderName")
                appendLine("📅 Период: $formattedStart — $formattedEnd")
                appendLine("📊 Длительность: $daysCount дн.")
            }.trim()

            val notifPayload = json.encodeToString(mapOf(
                "vacationId" to created.id,
                "userId" to created.userId,
                "start" to created.start,
                "end" to created.end
            ))

            val adminIds = transaction {
                UsersTable.selectAll()
                    .where { (UsersTable.role eq "admin") or (UsersTable.role eq "director") }
                    .map { it[UsersTable.id].value }
                    .filter { it != session.userId }
            }

            transaction {
                adminIds.forEach { adminId ->
                    NotificationsTable.insert {
                        it[id] = UUID.randomUUID()
                        it[targetUserId] = adminId
                        it[senderUserId] = session.userId
                        it[type] = "VACATION"
                        it[title] = notifTitle
                        it[message] = notifMessage
                        it[payload] = notifPayload
                        it[createdAt] = System.currentTimeMillis()
                    }
                }
            }

            CoroutineScope(Dispatchers.IO).launch {
                val tokens = transaction {
                    FcmTokensTable.selectAll()
                        .map { it[FcmTokensTable.userId].value to it[FcmTokensTable.token] }
                }
                val pushBody = "$senderName оформил отпуск: $formattedStart — $formattedEnd ($daysCount дн.)"

                tokens.filter { (uid, _) -> uid in adminIds }.forEach { (_, token) ->
                    FirebaseService.sendPush(
                        token = token,
                        title = notifTitle,
                        body = pushBody,
                        data = mapOf("type" to "VACATION", "payload" to notifPayload)
                    )
                }

                // Telegram
                com.proles.server.config.TelegramService.sendMessage(buildString {
                    appendLine("<b>🏖 НОВЫЙ ОТПУСК</b>")
                    appendLine("")
                    appendLine("<b>👤:</b> $senderName")
                    appendLine("<b>📅:</b> $formattedStart — $formattedEnd")
                    appendLine("<b>📊:</b> $daysCount дн.")
                }.trim())
            }

            call.respond(HttpStatusCode.Created, created)
        }

        delete {
            if (call.checkSession() == null) return@delete
            val userIdParam = call.request.queryParameters["userId"]
            if (userIdParam.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "userId parameter required")
                return@delete
            }
            val userId = UUID.fromString(userIdParam)
            transaction {
                VacationsTable.deleteWhere { VacationsTable.userId eq userId }
            }
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

                // 💾 Сохраняем уведомления в БД
                transaction {
                    adminIds.forEach { adminId ->
                        NotificationsTable.insert {
                            it[id] = UUID.randomUUID()
                            it[targetUserId] = adminId
                            it[senderUserId] = userId
                            it[type] = "DAYOFF_WEEKDAY"
                            it[title] = notifTitle
                            it[message] = notifMessage
                            it[payload] = notifPayload
                            it[createdAt] = System.currentTimeMillis()
                        }
                    }
                }

                // 📱 Отправляем FCM пуши + Telegram
                CoroutineScope(Dispatchers.IO).launch {
                    val allTokens: List<Pair<UUID, String>> = transaction {
                        FcmTokensTable.selectAll()
                            .map { it[FcmTokensTable.userId].value to it[FcmTokensTable.token] }
                    }

                    val pushBody = "$senderName взял выходной $formattedDate ($dayOfWeekRu)"

                    allTokens
                        .filter { (tokenUserId, _) -> tokenUserId in adminIds }
                        .forEach { (_, token) ->
                            FirebaseService.sendPush(
                                token = token,
                                title = notifTitle,
                                body = pushBody,
                                data = mapOf("type" to "DAYOFF_WEEKDAY", "payload" to notifPayload)
                            )
                        }

//                    // 🤖 Telegram
//                    val telegramMessage = buildString {
//                        appendLine("<b>🌞 ВЫХОДНОЙ В БУДНИЙ ДЕНЬ</b>")
//                        appendLine("")
//                        appendLine("<b>👤 Сотрудник:</b> $senderName")
//                        appendLine("<b>📅 Дата:</b> $formattedDate ($dayOfWeekRu)")
//                    }.trim()
//                    com.proles.server.config.TelegramService.sendMessage(telegramMessage)
                }

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
    // 🚆 BUSINESS TRIPS
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/business-trips") {
        get {
            val session = call.checkSession() ?: return@get  // ✅ Сохраняем сессию
            val userIdParam = call.request.queryParameters["userId"]
            val dateFrom = call.request.queryParameters["dateFrom"]
            val dateTo = call.request.queryParameters["dateTo"]
            val all = call.request.queryParameters["all"] == "true"
            if (!all && userIdParam.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "userId required")
                return@get
            }
            val isAdmin = session.role == "superadmin" ||
                    com.proles.server.config.PermissionMiddleware.canView(session.userId, Permission.BUSINESS_TRIPS_ALL)

            val trips = transaction {
                val userId = if (!all || !isAdmin) UUID.fromString(userIdParam!!) else null

                var query = (BusinessTripsTable innerJoin ProjectsTable).selectAll()

                query = query.where {
                    val conditions = mutableListOf<Op<Boolean>>()

                    if (userId != null) {
                        conditions.add(BusinessTripsTable.userId eq userId)
                    }
                    if (!dateFrom.isNullOrBlank()) {
                        conditions.add(BusinessTripsTable.date greaterEq KtLocalDate.parse(dateFrom))
                    }
                    if (!dateTo.isNullOrBlank()) {
                        conditions.add(BusinessTripsTable.date lessEq KtLocalDate.parse(dateTo))
                    }

                    when {
                        conditions.isEmpty() -> Op.TRUE
                        conditions.size == 1 -> conditions[0]
                        else -> conditions.reduce { acc, op -> acc and op }
                    }
                }

                query.orderBy(BusinessTripsTable.date to SortOrder.DESC)
                    .map { row ->
                        BusinessTripDto(
                            id = row[BusinessTripsTable.id].value.toString(),
                            userId = row[BusinessTripsTable.userId].value.toString(),
                            projectId = row[BusinessTripsTable.projectId].value.toString(),
                            projectName = row[ProjectsTable.name],
                            type = row[BusinessTripsTable.type],
                            date = row[BusinessTripsTable.date].toString(),
                            city = row[BusinessTripsTable.city],
                            waypoints = runCatching {
                                json.decodeFromString<List<WaypointDto>>(row[BusinessTripsTable.waypoints])
                            }.getOrDefault(emptyList()),
                            participants = runCatching {
                                json.decodeFromString<List<String>>(row[BusinessTripsTable.participants])
                            }.getOrDefault(emptyList()),
                            transport = row[BusinessTripsTable.transport],
                            notes = row[BusinessTripsTable.notes],
                            createdAt = row[BusinessTripsTable.createdAt]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, trips)
        }

        post {
            if (call.checkSession() == null) return@post
            val trip = try { call.receive<BusinessTripDto>() }
            catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest, e.message ?: "Bad") }

            val session = call.checkSession()!!
            val userId = UUID.fromString(trip.userId)

            // 🔥 ЛОГИКА: DEPARTURE = INSERT, TRANSFER/COMPLETION = UPDATE последней активной
            val (resultTrip, wasUpdate) = transaction {
                if (trip.type == "DEPARTURE") {
                    // ➕ Новая командировка
                    val id = UUID.randomUUID()
                    BusinessTripsTable.insert {
                        it[BusinessTripsTable.id] = id
                        it[BusinessTripsTable.userId] = userId
                        it[projectId] = UUID.fromString(trip.projectId)
                        it[type] = trip.type
                        it[date] = KtLocalDate.parse(trip.date)
                        it[city] = trip.city
                        it[participants] = json.encodeToString(trip.participants)
                        it[transport] = trip.transport
                        it[notes] = trip.notes
                        it[BusinessTripsTable.waypoints] = json.encodeToString(trip.waypoints)
                        it[createdAt] = System.currentTimeMillis()
                    }
                    Pair(trip.copy(id = id.toString()), false)
                } else {
                    // 🔄 TRANSFER или COMPLETION - обновляем последнюю активную
                    val activeTrip = BusinessTripsTable.selectAll()
                        .where {
                            (BusinessTripsTable.userId eq userId) and
                                    (BusinessTripsTable.type neq "COMPLETION")
                        }
                        .orderBy(BusinessTripsTable.date to SortOrder.DESC)
                        .firstOrNull()

                    if (activeTrip == null) {
                        // Если активной нет - создаём новую (fallback)
                        val id = UUID.randomUUID()
                        BusinessTripsTable.insert {
                            it[BusinessTripsTable.id] = id
                            it[BusinessTripsTable.userId] = userId
                            it[projectId] = UUID.fromString(trip.projectId)
                            it[type] = trip.type
                            it[date] = KtLocalDate.parse(trip.date)
                            it[city] = trip.city
                            it[participants] = json.encodeToString(trip.participants)
                            it[transport] = trip.transport
                            it[notes] = trip.notes
                            it[BusinessTripsTable.waypoints] = json.encodeToString(trip.waypoints)
                            it[createdAt] = System.currentTimeMillis()
                        }
                        Pair(trip.copy(id = id.toString()), false)
                    } else {
                        // ✅ Обновляем существующую запись
                        val activeId = activeTrip[BusinessTripsTable.id].value
                        val activeProjectName = activeTrip[BusinessTripsTable.projectId].let { projectId ->
                            ProjectsTable.selectAll()
                                .where { ProjectsTable.id eq projectId }
                                .single()[ProjectsTable.name]
                        }

                        BusinessTripsTable.update({ BusinessTripsTable.id eq activeId }) {
                            if (trip.type == "TRANSFER") {
                                // 🔄 TRANSFER: обновляем проект, город, дату, транспорт
                                it[projectId] = UUID.fromString(trip.projectId)
                                it[city] = trip.city
                                it[date] = KtLocalDate.parse(trip.date)
                                it[transport] = trip.transport
                                it[participants] = json.encodeToString(trip.participants)
                                it[BusinessTripsTable.waypoints] = json.encodeToString(trip.waypoints)
                                if (trip.notes.isNotBlank()) {
                                    it[notes] = trip.notes
                                }
                                it[type] = "TRANSFER"
                            } else { // COMPLETION
                                // ✅ COMPLETION: меняем тип, дату завершения, город возвращения
                                it[type] = "COMPLETION"
                                it[date] = KtLocalDate.parse(trip.date)
                                if (trip.city.isNotBlank()) it[city] = trip.city
                                if (trip.transport.isNotBlank()) it[transport] = trip.transport
                                if (trip.notes.isNotBlank()) it[notes] = trip.notes
                            }
                        }

                        // Возвращаем обновлённую запись с правильным projectName
                        Pair(
                            trip.copy(
                                id = activeId.toString(),
                                projectName = if (trip.type == "TRANSFER") trip.projectName else activeProjectName
                            ),
                            true
                        )
                    }
                }
            }

            // 👤 Получаем имя отправителя
            val senderName = transaction {
                UsersTable.selectAll()
                    .where { UsersTable.id eq session.userId }
                    .single()[UsersTable.name]
            }

            // 👥 Получаем имена всех участников
            val participantNamesMap: Map<UUID, String> = transaction {
                if (resultTrip.participants.isEmpty()) {
                    emptyMap()
                } else {
                    val uuids = resultTrip.participants.mapNotNull {
                        runCatching { UUID.fromString(it) }.getOrNull()
                    }
                    if (uuids.isEmpty()) {
                        emptyMap()
                    } else {
                        UsersTable.selectAll()
                            .where { UsersTable.id inList uuids }
                            .associate { it[UsersTable.id].value to it[UsersTable.name] }
                    }
                }
            }

            // 📅 Форматируем дату
            val dateObj = KtLocalDate.parse(resultTrip.date)
            val formattedDate = "%02d.%02d.%04d".format(dateObj.dayOfMonth, dateObj.monthNumber, dateObj.year)

            // 🎨 Перевод типа с учётом UPDATE
            val typeLabel = when (resultTrip.type) {
                "DEPARTURE" -> "🚆 Отъезд"
                "TRANSFER" -> if (wasUpdate) "🔄 Переезд" else "🚆 Отъезд"
                "COMPLETION" -> "✅ Завершение"
                else -> "📋 Командировка"
            }

            // 🎯 Заголовок уведомления
            val notifTitle = when (resultTrip.type) {
                "DEPARTURE" -> "🚆 Новая командировка"
                "TRANSFER" -> if (wasUpdate) "🔄 Переезд по командировке" else "🚆 Новая командировка"
                "COMPLETION" -> "✅ Командировка завершена"
                else -> "📋 Командировка"
            }

            // 📝 Полный текст для БД
            val notifMessage = buildString {
                appendLine("👤 $senderName")
                appendLine("📁 Проект: ${resultTrip.projectName}")
                appendLine("📍 Город: ${resultTrip.city.ifBlank { "—" }}")
                appendLine("📅 Дата: $formattedDate")
                appendLine("🏷 Тип: $typeLabel")
                if (participantNamesMap.isNotEmpty()) {
                    appendLine("👥 Участники: ${participantNamesMap.values.joinToString(", ")}")
                }
                if (resultTrip.transport.isNotBlank()) {
                    appendLine("🚗 Транспорт: ${resultTrip.transport}")
                }
                if (resultTrip.notes.isNotBlank()) {
                    appendLine("📝 Заметки: ${resultTrip.notes}")
                }
            }.trim()

            // 📱 Краткий текст для push
            val pushBody = when (resultTrip.type) {
                "DEPARTURE" -> "$senderName → ${resultTrip.city.ifBlank { "в командировку" }} (${resultTrip.projectName})"
                "TRANSFER" -> if (wasUpdate) {
                    "$senderName переехал в ${resultTrip.city.ifBlank { "другой город" }} (${resultTrip.projectName})"
                } else {
                    "$senderName → ${resultTrip.city.ifBlank { "в командировку" }} (${resultTrip.projectName})"
                }
                "COMPLETION" -> "$senderName завершил командировку (${resultTrip.projectName})"
                else -> "$senderName: ${resultTrip.projectName}"
            }

            // 📝 Payload для навигации
            val notifPayload = json.encodeToString(mapOf(
                "tripId" to resultTrip.id,
                "tripType" to resultTrip.type,
                "userId" to resultTrip.userId,
                "projectId" to resultTrip.projectId
            ))

            // 🔥 РАЗДЕЛЯЕМ ПОЛУЧАТЕЛЕЙ
            val allAdmins: Map<UUID, String> = transaction {
                UsersTable.selectAll()
                    .where { (UsersTable.role eq "admin") or (UsersTable.role eq "director") }
                    .associate { it[UsersTable.id].value to it[UsersTable.name] }
            }

            val adminIds: List<UUID> = allAdmins.keys.filter { it != session.userId }

            val participantUserIds: List<UUID> = resultTrip.participants.mapNotNull {
                runCatching { UUID.fromString(it) }.getOrNull()
            }.filter { it != session.userId && it !in allAdmins.keys }

            val participantOnlyNames: Map<UUID, String> = transaction {
                if (participantUserIds.isEmpty()) {
                    emptyMap()
                } else {
                    UsersTable.selectAll()
                        .where { UsersTable.id inList participantUserIds }
                        .associate { it[UsersTable.id].value to it[UsersTable.name] }
                }
            }

            // 💾 СОХРАНЯЕМ УВЕДОМЛЕНИЯ В БД
            transaction {
                // 1️⃣ Для админов
                adminIds.forEach { adminId ->
                    NotificationsTable.insert {
                        it[id] = UUID.randomUUID()
                        it[targetUserId] = adminId
                        it[senderUserId] = session.userId
                        it[type] = "TRIP"
                        it[title] = notifTitle
                        it[message] = notifMessage
                        it[payload] = notifPayload
                        it[createdAt] = System.currentTimeMillis()
                    }
                }

                // 2️⃣ Для участников (не админов)
                participantOnlyNames.forEach { (participantId, participantName) ->
                    val participantMessage = buildString {
                        appendLine("📢 ${if (wasUpdate && resultTrip.type == "TRANSFER") "Переезд" else if (resultTrip.type == "COMPLETION") "Завершение" else "Новая"} командировка!")
                        appendLine("")
                        appendLine("👤 Организатор: $senderName")
                        appendLine("📁 Проект: ${resultTrip.projectName}")
                        appendLine("📍 Город: ${resultTrip.city.ifBlank { "—" }}")
                        appendLine("📅 Дата: $formattedDate")
                        appendLine("🏷 Тип: $typeLabel")
                        if (participantNamesMap.size > 1) {
                            val otherParticipants = participantNamesMap.values
                                .filter { it != participantName }
                                .joinToString(", ")
                            appendLine("👥 Также в командировке: $otherParticipants")
                        }
                        if (resultTrip.transport.isNotBlank()) {
                            appendLine("🚗 Транспорт: ${resultTrip.transport}")
                        }
                        if (resultTrip.notes.isNotBlank()) {
                            appendLine("📝 Заметки: ${resultTrip.notes}")
                        }
                    }.trim()

                    NotificationsTable.insert {
                        it[id] = UUID.randomUUID()
                        it[targetUserId] = participantId
                        it[senderUserId] = session.userId
                        it[type] = if (wasUpdate && resultTrip.type != "COMPLETION") "TRIP_UPDATE" else "TRIP_INVITE"
                        it[title] = if (wasUpdate && resultTrip.type == "TRANSFER") {
                            "🔄 Переезд по командировке"
                        } else if (resultTrip.type == "COMPLETION") {
                            "✅ Командировка завершена"
                        } else {
                            "📢 Вас добавили в командировку"
                        }
                        it[message] = participantMessage
                        it[payload] = notifPayload
                        it[createdAt] = System.currentTimeMillis()
                    }
                }
            }

            // 📱 ОТПРАВКА FCM ПУШЕЙ + Telegram
            CoroutineScope(Dispatchers.IO).launch {
                // 🔥 Фильтруем токены с учётом настроек
                val allTokens = transaction {
                    FcmTokensTable.selectAll()
                        .map { it[FcmTokensTable.userId].value to it[FcmTokensTable.token] }
                }

                // Получаем пользователей, у которых ВКЛЮЧЕНЫ уведомления о командировках
                val enabledUserIds = transaction {
                    NotificationPreferencesTable.selectAll()
                        .where { NotificationPreferencesTable.tripEnabled eq true }
                        .map { it[NotificationPreferencesTable.userId].value }
                        .toSet()
                }

                val adminPushBody = pushBody
                val participantPushBody = when {
                    wasUpdate && resultTrip.type == "TRANSFER" -> "$senderName переехал в ${resultTrip.city}: ${resultTrip.projectName}"
                    resultTrip.type == "COMPLETION" -> "$senderName завершил командировку: ${resultTrip.projectName}"
                    else -> "$senderName добавил(а) вас в командировку: ${resultTrip.projectName}"
                }

                // Отправляем только тем, у кого включено (или у кого нет настроек = по умолчанию включено)
                allTokens
                    .filter { (uid, _) -> uid in adminIds && (uid !in enabledUserIds || uid in enabledUserIds) }
                    .forEach { (_, token) ->
                        FirebaseService.sendPush(
                            token = token,
                            title = notifTitle,
                            body = adminPushBody,
                            data = mapOf("type" to "TRIP", "payload" to notifPayload)
                        )
                    }

                // 🔥 Отправляем участникам (не админам)
                allTokens
                    .filter { (userId, _) -> userId in participantOnlyNames.keys }
                    .forEach { (_, token) ->
                        FirebaseService.sendPush(
                            token = token,
                            title = if (wasUpdate && resultTrip.type == "TRANSFER") {
                                "🔄 Переезд по командировке"
                            } else if (resultTrip.type == "COMPLETION") {
                                "✅ Командировка завершена"
                            } else {
                                "📢 Вас добавили в командировку"
                            },
                            body = participantPushBody,
                            data = mapOf(
                                "type" to if (wasUpdate && resultTrip.type != "COMPLETION") "TRIP_UPDATE" else "TRIP_INVITE",
                                "payload" to notifPayload
                            )
                        )
                    }

                // 🔥 Telegram
                com.proles.server.config.TelegramService.sendMessage(
                    com.proles.server.config.TelegramService.formatTripMessage(
                        senderName = senderName,
                        tripType = resultTrip.type,
                        projectName = resultTrip.projectName,
                        city = resultTrip.city,
                        date = formattedDate,
                        participants = participantNamesMap.values.toList(),
                        transport = resultTrip.transport,
                        notes = resultTrip.notes
                    )
                )
            }

            call.respond(HttpStatusCode.Created, resultTrip)
        }

        put {
            if (call.checkSession() == null) return@put
            val trip = try { call.receive<BusinessTripDto>() }
            catch (e: Exception) { return@put call.respond(HttpStatusCode.BadRequest, e.message ?: "Bad") }

            transaction {
                BusinessTripsTable.update({ BusinessTripsTable.id eq UUID.fromString(trip.id) }) {
                    it[projectId] = UUID.fromString(trip.projectId)
                    it[type] = trip.type
                    it[date] = KtLocalDate.parse(trip.date)
                    it[city] = trip.city
                    // ✅ ИСПРАВЛЕНО: правильный синтаксис сериализации
                    it[participants] = json.encodeToString(trip.participants)
                    it[transport] = trip.transport
                    it[notes] = trip.notes
                }
            }
            call.respond(HttpStatusCode.OK, trip)
        }

        delete {
            if (call.checkSession() == null) return@delete
            val tripId = call.request.queryParameters["tripId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            transaction { BusinessTripsTable.deleteWhere { BusinessTripsTable.id eq UUID.fromString(tripId) } }
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

    route("/api/v1/notification-preferences") {
        get {
            val session = call.checkSession() ?: return@get
            val prefs = transaction {
                NotificationPreferencesTable.selectAll()
                    .where { NotificationPreferencesTable.userId eq session.userId }
                    .singleOrNull()
            }
            val isAdmin = session.role in listOf("admin", "director")

            val result = mapOf(
                "tripEnabled" to (prefs?.get(NotificationPreferencesTable.tripEnabled) ?: true),
                "vacationEnabled" to (prefs?.get(NotificationPreferencesTable.vacationEnabled) ?: true),
                "dayoffEnabled" to if (isAdmin) (prefs?.get(NotificationPreferencesTable.dayoffEnabled) ?: true) else false,
                "expenseEnabled" to (prefs?.get(NotificationPreferencesTable.expenseEnabled) ?: true),
                "payrollEnabled" to if (isAdmin) (prefs?.get(NotificationPreferencesTable.payrollEnabled) ?: true) else false,
                "telegramEnabled" to (prefs?.get(NotificationPreferencesTable.telegramEnabled) ?: false),
                "emailEnabled" to (prefs?.get(NotificationPreferencesTable.emailEnabled) ?: false),
                "email" to (prefs?.get(NotificationPreferencesTable.email) ?: ""),
                "isAdmin" to isAdmin
            )
            call.respond(HttpStatusCode.OK, result)
        }

        put {
            val session = call.checkSession() ?: return@put
            val body = try { call.receive<Map<String, String>>() }
            catch (e: Exception) { return@put call.respond(HttpStatusCode.BadRequest) }

            val isAdmin = session.role in listOf("admin", "director")

            transaction {
                val existing = NotificationPreferencesTable.selectAll()
                    .where { NotificationPreferencesTable.userId eq session.userId }
                    .singleOrNull()

                if (existing == null) {
                    NotificationPreferencesTable.insert {
                        it[NotificationPreferencesTable.id] = UUID.randomUUID()
                        it[userId] = session.userId
                        it[tripEnabled] = body["tripEnabled"]?.toBoolean() ?: true
                        it[vacationEnabled] = body["vacationEnabled"]?.toBoolean() ?: true
                        if (isAdmin) it[dayoffEnabled] = body["dayoffEnabled"]?.toBoolean() ?: true
                        it[expenseEnabled] = body["expenseEnabled"]?.toBoolean() ?: true
                        if (isAdmin) it[payrollEnabled] = body["payrollEnabled"]?.toBoolean() ?: true
                        it[telegramEnabled] = body["telegramEnabled"]?.toBoolean() ?: false
                        it[emailEnabled] = body["emailEnabled"]?.toBoolean() ?: false
                        it[email] = body["email"] ?: ""
                    }
                } else {
                    NotificationPreferencesTable.update({ NotificationPreferencesTable.userId eq session.userId }) {
                        it[tripEnabled] = body["tripEnabled"]?.toBoolean() ?: true
                        it[vacationEnabled] = body["vacationEnabled"]?.toBoolean() ?: true
                        if (isAdmin) it[dayoffEnabled] = body["dayoffEnabled"]?.toBoolean() ?: true
                        it[expenseEnabled] = body["expenseEnabled"]?.toBoolean() ?: true
                        if (isAdmin) it[payrollEnabled] = body["payrollEnabled"]?.toBoolean() ?: true
                        it[telegramEnabled] = body["telegramEnabled"]?.toBoolean() ?: false
                        it[emailEnabled] = body["emailEnabled"]?.toBoolean() ?: false
                        it[email] = body["email"] ?: ""
                    }
                }
            }
            call.respond(HttpStatusCode.OK)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 🔐 RBAC: Управление ролями и правами
    // ─────────────────────────────────────────────────────────────
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
            transaction {
                // Находим UUID пользователя COMPANY для расходов компании
                val companyUser = UsersTable.selectAll()
                    .where { UsersTable.name eq "COMPANY" }
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
                }
                //  Автоматически создаём расход "Билет" от имени COMPANY (если есть сумма)
                if (request.amount > 0.0) {
                    val expenseUserId = companyUserId ?: session.userId
                    transaction {
                        // ✅ Используем java.time вместо kotlinx.datetime (проще и всегда доступно)
                        val todayJava = java.time.LocalDate.now()
                        val todayKt = kotlinx.datetime.LocalDate(todayJava.year, todayJava.monthValue, todayJava.dayOfMonth)

                        ExpensesTable.insert {
                            it[ExpensesTable.id] = UUID.randomUUID()
                            it[userId] = expenseUserId
                            it[projectId] = UUID.fromString(request.projectId)
                            it[date] = todayKt
                            it[type] = "OTHER"
                            it[name] = "🎫 Билет: ${request.fileName.take(50)}"
                            it[amount] = request.amount
                            it[currency] = request.currency
                            it[comment] = "Автоматически создан при загрузке билета. ${request.description.takeIf { it.isNotBlank() } ?: ""}"
                            it[receiptSubmitted] = false
                            it[hasReceiptPhoto] = false
                            it[createdAt] = System.currentTimeMillis()
                        }
                    }
                    println("✅ Auto-expense created: ${request.amount} ${request.currency} for ticket $uniqueFileName (userId=$expenseUserId)")
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
            //  Telegram-уведомление о новом билете
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val telegramMsg = buildString {
                        appendLine("<b>🎫 НОВЫЙ БИЛЕТ</b>")
                        appendLine()
                        appendLine("<b>👤 Загрузил:</b> $senderName")
                        appendLine("<b>📁 Проект:</b> $projectName")
                        appendLine("<b>📄 Файл:</b> ${request.fileName}")
                        if (request.amount > 0.0) {
                            appendLine("<b>💰 Стоимость:</b> ${"%.2f".format(request.amount)} ${request.currency}")
                        }
                        if (request.description.isNotBlank()) {
                            appendLine("<b>📝 Описание:</b> ${request.description}")
                        }
                        if (request.recipientIds.isNotEmpty()) {
                            appendLine("<b>👥 Получателей:</b> ${request.recipientIds.size}")
                        }
                        if (request.sendToAccountant) {
                            appendLine("<b>📧 Отправлено:</b> ${request.accountantEmail}")
                        }
                    }.trim()
                    com.proles.server.config.TelegramService.sendMessage(telegramMsg)
                } catch (e: Exception) {
                    println("⚠️ Telegram notification failed: ${e.message}")
                }
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
            if (!call.checkPermission(Permission.PAYROLL, "edit")) return@delete
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

            // Если не супер-админ, проверяем что компонент принадлежит текущему пользователю
            if (session.role != "superadmin" && componentOwner != session.userId) {
                return@delete call.respond(HttpStatusCode.Forbidden, "Cannot delete another user's component")
            }

            transaction {
                SalaryComponentsTable.deleteWhere { SalaryComponentsTable.id eq componentId }
            }

            println("✅ Component $componentId deleted")
            call.respond(HttpStatusCode.NoContent)
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