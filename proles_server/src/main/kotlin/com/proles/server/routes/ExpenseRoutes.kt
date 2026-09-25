package com.proles.server.routes

import com.proles.server.config.NotificationService
import com.proles.server.config.PermissionMiddleware
import com.proles.server.data.*
import com.proles.server.model.ExpenseDto
import com.proles.server.model.Permission
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDate as KtLocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate as JavaLocalDate
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json

@Serializable
internal data class ExpenseReceiptDto(
    val id: String,
    val expenseId: String,
    val url: String,
    val fileName: String,
    val uploadedAt: Long
)

internal object ExpenseTypes {
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

    fun label(type: String): String =
        ALL.find { it.first == type }?.second ?: type
}

private fun normalizeReceiptCategory(value: String?): String =
    when (value.orEmpty().trim().uppercase()) {
        "PERSONAL", "WITHOUT_RECEIPT" -> "WITHOUT_RECEIPT"
        else -> "WITH_RECEIPT"
    }

private val directorExpenseRoles =
    setOf("superadmin", "admin", "director")

private fun normalizeExpenseScope(
    requestedScope: String?,
    creatorRole: String
): String {
    val requested = requestedScope.orEmpty().trim().uppercase()

    return if (
        requested == "DIRECTOR" &&
        creatorRole.lowercase() == "director"
    ) {
        "DIRECTOR"
    } else {
        "GENERAL"
    }
}

private fun validateNullableSubprojectForProject(
    projectId: UUID?,
    subprojectId: UUID?
): Boolean {
    if (subprojectId == null) return true
    if (projectId == null) return false

    return validateActiveSubproject(projectId, subprojectId)
}

private fun expenseSubprojectName(row: ResultRow): String {
    val subprojectId = row[ExpensesTable.subprojectId]?.value
        ?: return ""

    return SubprojectsTable
        .selectAll()
        .where { SubprojectsTable.id eq subprojectId }
        .limit(1)
        .singleOrNull()
        ?.get(SubprojectsTable.name)
        .orEmpty()
}

private fun mapRowToExpenseDto(row: ResultRow): ExpenseDto {
    val expenseId = row[ExpensesTable.id].value

    val receiptCount = ExpenseReceiptsTable
        .selectAll()
        .where { ExpenseReceiptsTable.expenseId eq expenseId }
        .count()
        .toInt()

    return ExpenseDto(
        id = expenseId.toString(),
        userId = row[ExpensesTable.userId].value.toString(),
        projectId = row[ExpensesTable.projectId].value.toString(),
        subprojectId = row[ExpensesTable.subprojectId]?.value?.toString(),
        subprojectName = expenseSubprojectName(row),
        projectName = row.getOrNull(ProjectsTable.name).orEmpty(),
        date = row[ExpensesTable.date].toString(),
        type = row[ExpensesTable.type],
        name = row[ExpensesTable.name],
        amount = row[ExpensesTable.amount],
        currency = row[ExpensesTable.currency],
        comment = row[ExpensesTable.comment],
        receiptSubmitted = row[ExpensesTable.receiptSubmitted],
        hasReceiptPhoto = row[ExpensesTable.hasReceiptPhoto],
        category = normalizeReceiptCategory(row[ExpensesTable.category]),
        subcategory = row[ExpensesTable.subcategory],
        receiptCount = receiptCount,
        expenseScope = row[ExpensesTable.expenseScope],
        creatorRole = row[ExpensesTable.creatorRole],
        createdAt = row[ExpensesTable.createdAt]
    )
}

private val expenseJson =
    Json { ignoreUnknownKeys = true; encodeDefaults = true }

private enum class ExpenseDeleteResult {
    NOT_FOUND,
    FORBIDDEN,
    DELETED
}

internal fun Route.expenseRoutes() {
    route("/api/v1/expenses") {
        // 💰 Список типов расходов (для UI)
        get("/types") {
            if (call.checkSession() == null) return@get
            call.respond(HttpStatusCode.OK, ExpenseTypes.ALL.map {
                mapOf("key" to it.first, "label" to it.second)
            })
        }

        suspend fun handleExpenseAttachment(
            call: ApplicationCall,
            requireImage: Boolean = false
        ) {
            val session = call.checkSession() ?: return

            val requestBody = try {
                call.receive<Map<String, String>>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid JSON: ${e.message}"
                )
                return
            }

            val expenseId = requestBody["expenseId"]
            val fileBase64 =
                requestBody["fileBase64"] ?: requestBody["imageBase64"]
            val originalName =
                requestBody["fileName"] ?: "receipt.jpg"
            val mimeType =
                requestBody["mimeType"] ?: "image/jpeg"

            if (expenseId.isNullOrBlank() || fileBase64.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    "Missing expenseId or fileBase64"
                )
                return
            }

            if (requireImage && !mimeType.startsWith("image/")) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    "Receipt must be an image"
                )
                return
            }

            val expenseUuid = expenseId.toUuidOrNull()
            if (expenseUuid == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid expenseId"
                )
                return
            }

            val fileBytes = try {
                Base64.getDecoder().decode(fileBase64)
            } catch (_: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid Base64 file data"
                )
                return
            }

            val expenseWithProject = transaction {
                (ExpensesTable innerJoin ProjectsTable)
                    .selectAll()
                    .where {
                        (ExpensesTable.id eq expenseUuid) and
                            ExpensesTable.deletedAt.isNull()
                    }
                    .singleOrNull()
            }

            if (expenseWithProject == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    "Expense not found"
                )
                return
            }

            val category = normalizeReceiptCategory(
                expenseWithProject[ExpensesTable.category]
            )

            if (category == "WITHOUT_RECEIPT") {
                call.respond(
                    HttpStatusCode.Conflict,
                    "Для расхода без чека вложение запрещено"
                )
                return
            }

            val scope = expenseWithProject[ExpensesTable.expenseScope]
            if (
                scope == "DIRECTOR" &&
                session.role !in directorExpenseRoles
            ) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    "Access denied"
                )
                return
            }

            val expenseUserId =
                expenseWithProject[ExpensesTable.userId].value
            val projectName =
                expenseWithProject[ProjectsTable.name]

            val canUploadForeignExpense =
                session.role in directorExpenseRoles

            if (
                expenseUserId != session.userId &&
                !canUploadForeignExpense
            ) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    "Access denied: not your expense"
                )
                return
            }

            val userFullNameShort = transaction {
                UsersTable
                    .selectAll()
                    .where { UsersTable.id eq expenseUserId }
                    .singleOrNull()
                    ?.let { row ->
                        val lastName = row[UsersTable.lastName]
                        val firstName = row[UsersTable.firstName]
                        val middleName = row[UsersTable.middleName]

                        val initials = buildString {
                            firstName.firstOrNull()?.let {
                                append(it.uppercase())
                                append('.')
                            }
                            middleName.firstOrNull()?.let {
                                append(it.uppercase())
                                append('.')
                            }
                        }

                        "$lastName $initials".trim()
                    }
                    ?: "unknown_user"
            }

            val currentMonth = JavaLocalDate.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")
            )

            val safeUserFolder = userFullNameShort
                .replace(
                    Regex("[^A-Za-zА-Яа-яЁё0-9._ -]"),
                    "_"
                )
                .replace("..", "_")
                .ifBlank { "unknown_user" }

            val uploadDir = java.io.File(
                "uploads/receipts/$currentMonth/$safeUserFolder"
            ).apply { mkdirs() }

            val safeProject = projectName
                .replace(
                    Regex("[^A-Za-zА-Яа-яЁё0-9._ -]"),
                    "_"
                )
                .replace("..", "_")
                .take(80)
                .ifBlank { "project" }

            val extension = originalName
                .substringAfterLast('.', "")
                .lowercase()
                .take(10)
                .let {
                    if (it.matches(Regex("[a-z0-9]+"))) {
                        it
                    } else {
                        when {
                            mimeType == "application/pdf" -> "pdf"
                            mimeType.startsWith("image/") -> "jpg"
                            else -> "bin"
                        }
                    }
                }

            val fileName =
                "${safeProject}_${JavaLocalDate.now()}_" +
                    "${System.currentTimeMillis()}_" +
                    "${UUID.randomUUID().toString().take(8)}.$extension"

            val targetFile = java.io.File(uploadDir, fileName)
            targetFile.writeBytes(fileBytes)

            val fileUrl =
                "/uploads/receipts/$currentMonth/" +
                    "$safeUserFolder/$fileName"
            val receiptId = UUID.randomUUID()

            try {
                transaction {
                    ExpenseReceiptsTable.insert {
                        it[id] = receiptId
                        it[ExpenseReceiptsTable.expenseId] = expenseUuid
                        it[imageUrl] = fileUrl
                        it[uploadedAt] = System.currentTimeMillis()
                    }

                    ExpensesTable.update(
                        { ExpensesTable.id eq expenseUuid }
                    ) {
                        it[hasReceiptPhoto] =
                            mimeType.startsWith("image/")
                        it[receiptSubmitted] = true
                    }
                }
            } catch (e: Exception) {
                targetFile.delete()
                throw e
            }

            call.respond(
                HttpStatusCode.Created,
                mapOf(
                    "id" to receiptId.toString(),
                    "url" to fileUrl,
                    "fileName" to fileName
                )
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

            if (expense[ExpensesTable.deletedAt] != null) {
                return@get call.respond(
                    HttpStatusCode.NotFound,
                    "Expense not found"
                )
            }

            if (
                expense[ExpensesTable.expenseScope] == "DIRECTOR" &&
                session.role !in directorExpenseRoles
            ) {
                return@get call.respond(
                    HttpStatusCode.Forbidden,
                    "Access denied"
                )
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

        get("/all") {
            val session = call.checkSession() ?: return@get

            if (
                !PermissionMiddleware.canView(
                    session.userId,
                    Permission.EXPENSES_ALL
                )
            ) {
                return@get call.respond(
                    HttpStatusCode.Forbidden,
                    "Нет прав на просмотр расходов"
                )
            }

            val dateFrom = call.request.queryParameters["dateFrom"]
                ?.takeIf(String::isNotBlank)
                ?.let {
                    runCatching { KtLocalDate.parse(it) }.getOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid dateFrom"
                        )
                }

            val dateTo = call.request.queryParameters["dateTo"]
                ?.takeIf(String::isNotBlank)
                ?.let {
                    runCatching { KtLocalDate.parse(it) }.getOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid dateTo"
                        )
                }

            val list = transaction {
                var condition: Op<Boolean> =
                    ExpensesTable.deletedAt.isNull()

                if (dateFrom != null) {
                    condition = condition and
                        (ExpensesTable.date greaterEq dateFrom)
                }

                if (dateTo != null) {
                    condition = condition and
                        (ExpensesTable.date lessEq dateTo)
                }

                if (session.role !in directorExpenseRoles) {
                    condition = condition and
                        (ExpensesTable.expenseScope eq "GENERAL")
                }

                (ExpensesTable innerJoin ProjectsTable)
                    .selectAll()
                    .where { condition }
                    .orderBy(
                        ExpensesTable.date to SortOrder.DESC,
                        ExpensesTable.createdAt to SortOrder.DESC
                    )
                    .map(::mapRowToExpenseDto)
            }

            call.respond(HttpStatusCode.OK, list)
        }

        get {
            val session = call.checkSession() ?: return@get

            val requestedUserId = call.request.queryParameters["userId"]
                .toUuidOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    "Valid userId required"
                )

            if (
                requestedUserId != session.userId &&
                session.role !in directorExpenseRoles
            ) {
                return@get call.respond(
                    HttpStatusCode.Forbidden,
                    "Access denied"
                )
            }

            val dateFrom = call.request.queryParameters["dateFrom"]
                ?.takeIf(String::isNotBlank)
                ?.let {
                    runCatching { KtLocalDate.parse(it) }.getOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid dateFrom"
                        )
                }

            val dateTo = call.request.queryParameters["dateTo"]
                ?.takeIf(String::isNotBlank)
                ?.let {
                    runCatching { KtLocalDate.parse(it) }.getOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid dateTo"
                        )
                }

            val list = transaction {
                var condition: Op<Boolean> =
                    (ExpensesTable.userId eq requestedUserId) and
                        ExpensesTable.deletedAt.isNull()

                if (dateFrom != null) {
                    condition = condition and
                        (ExpensesTable.date greaterEq dateFrom)
                }

                if (dateTo != null) {
                    condition = condition and
                        (ExpensesTable.date lessEq dateTo)
                }

                if (session.role !in directorExpenseRoles) {
                    condition = condition and
                        (ExpensesTable.expenseScope eq "GENERAL")
                }

                (ExpensesTable innerJoin ProjectsTable)
                    .selectAll()
                    .where { condition }
                    .orderBy(
                        ExpensesTable.date to SortOrder.DESC,
                        ExpensesTable.createdAt to SortOrder.DESC
                    )
                    .map(::mapRowToExpenseDto)
            }

            call.respond(HttpStatusCode.OK, list)
        }

        post {
            val session = call.checkSession() ?: return@post

            val expense = try {
                call.receive<ExpenseDto>()
            } catch (e: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    e.message ?: "Bad JSON"
                )
            }

            val userId = expense.userId.toUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid userId"
                )

            val projectId = expense.projectId.toUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "projectId required"
                )

            val subprojectId = expense.subprojectId.toUuidOrNull()

            val expenseDate = runCatching {
                KtLocalDate.parse(expense.date)
            }.getOrElse {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid date"
                )
            }

            if (
                userId != session.userId &&
                session.role !in directorExpenseRoles
            ) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    "Access denied"
                )
            }

            if (!expense.amount.isFinite() || expense.amount < 0.0) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Amount must be non-negative"
                )
            }

            val category = normalizeReceiptCategory(expense.category)
            val expenseScope = normalizeExpenseScope(
                expense.expenseScope,
                session.role
            )

            val id = UUID.randomUUID()

            val created = try {
                transaction {
                    if (
                        !validateNullableSubprojectForProject(
                            projectId,
                            subprojectId
                        )
                    ) {
                        throw IllegalArgumentException(
                            "Подпроект не принадлежит проекту или архивирован"
                        )
                    }

                    ExpensesTable.insert {
                        it[ExpensesTable.id] = id
                        it[ExpensesTable.userId] = userId
                        it[ExpensesTable.projectId] = projectId
                        it[ExpensesTable.subprojectId] = subprojectId
                        it[date] = expenseDate
                        it[type] = expense.type
                        it[name] = expense.name
                        it[amount] = expense.amount
                        it[currency] = expense.currency
                        it[comment] = expense.comment
                        it[ExpensesTable.category] = category
                        it[subcategory] = expense.subcategory
                        it[receiptSubmitted] =
                            if (category == "WITHOUT_RECEIPT") {
                                true
                            } else {
                                expense.receiptSubmitted
                            }
                        it[hasReceiptPhoto] =
                            if (category == "WITHOUT_RECEIPT") {
                                false
                            } else {
                                expense.hasReceiptPhoto
                            }
                        it[ExpensesTable.expenseScope] = expenseScope
                        it[creatorRole] = session.role
                        it[createdAt] = System.currentTimeMillis()
                        it[deletedAt] = null
                        it[deletedBy] = null
                    }

                    (ExpensesTable innerJoin ProjectsTable)
                        .selectAll()
                        .where { ExpensesTable.id eq id }
                        .single()
                        .let(::mapRowToExpenseDto)
                }
            } catch (e: IllegalArgumentException) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    e.message ?: "Invalid subproject"
                )
            }

            val senderName = transaction {
                UsersTable
                    .selectAll()
                    .where { UsersTable.id eq userId }
                    .singleOrNull()
                    ?.get(UsersTable.name)
                    ?: "Сотрудник"
            }

            val financeRecipients = transaction {
                UsersTable
                    .selectAll()
                    .where {
                        UsersTable.role inList
                            directorExpenseRoles.toList()
                    }
                    .map { it[UsersTable.id].value }
                    .filter { it != userId }
            }

            NotificationService.notifyUsers(
                financeRecipients,
                userId,
                "EXPENSE_CREATED",
                "Новый расход",
                "$senderName: ${expense.name} — " +
                    "${"%.2f".format(expense.amount)} " +
                    expense.currency,
                expenseJson.encodeToString(
                    mapOf(
                        "expenseId" to created.id,
                        "projectId" to projectId.toString(),
                        "expenseScope" to expenseScope
                    )
                ),
                "expenseCreated"
            )

            call.respond(HttpStatusCode.Created, created)
        }

        put {
            val session = call.checkSession() ?: return@put

            val expense = try {
                call.receive<ExpenseDto>()
            } catch (e: Exception) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    e.message ?: "Bad JSON"
                )
            }

            val expenseId = expense.id.toUuidOrNull()
                ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid expenseId"
                )

            val projectId = expense.projectId.toUuidOrNull()
                ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "projectId required"
                )

            val subprojectId = expense.subprojectId.toUuidOrNull()

            val expenseDate = runCatching {
                KtLocalDate.parse(expense.date)
            }.getOrElse {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid date"
                )
            }

            if (!expense.amount.isFinite() || expense.amount < 0.0) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Amount must be non-negative"
                )
            }

            val category = normalizeReceiptCategory(expense.category)

            val updated = try {
                transaction {
                    val existing = ExpensesTable
                        .selectAll()
                        .where {
                            (ExpensesTable.id eq expenseId) and
                                ExpensesTable.deletedAt.isNull()
                        }
                        .singleOrNull()
                        ?: return@transaction null

                    val ownerId = existing[ExpensesTable.userId].value
                    val currentScope =
                        existing[ExpensesTable.expenseScope]

                    if (
                        ownerId != session.userId &&
                        session.role !in directorExpenseRoles
                    ) {
                        throw SecurityException("Access denied")
                    }

                    if (
                        currentScope == "DIRECTOR" &&
                        session.role !in directorExpenseRoles
                    ) {
                        throw SecurityException("Access denied")
                    }

                    if (
                        !validateNullableSubprojectForProject(
                            projectId,
                            subprojectId
                        )
                    ) {
                        throw IllegalArgumentException(
                            "Подпроект не принадлежит проекту или архивирован"
                        )
                    }

                    if (category == "WITHOUT_RECEIPT") {
                        ExpenseReceiptsTable.deleteWhere {
                            ExpenseReceiptsTable.expenseId eq expenseId
                        }
                    }

                    val requestedScope = if (
                        existing[ExpensesTable.creatorRole] == "director"
                    ) {
                        normalizeExpenseScope(
                            expense.expenseScope,
                            "director"
                        )
                    } else {
                        "GENERAL"
                    }

                    ExpensesTable.update(
                        { ExpensesTable.id eq expenseId }
                    ) {
                        it[ExpensesTable.projectId] = projectId
                        it[ExpensesTable.subprojectId] = subprojectId
                        it[date] = expenseDate
                        it[type] = expense.type
                        it[name] = expense.name
                        it[amount] = expense.amount
                        it[currency] = expense.currency
                        it[comment] = expense.comment
                        it[ExpensesTable.category] = category
                        it[subcategory] = expense.subcategory
                        it[expenseScope] = requestedScope
                        it[receiptSubmitted] =
                            if (category == "WITHOUT_RECEIPT") {
                                true
                            } else {
                                expense.receiptSubmitted
                            }
                        it[hasReceiptPhoto] =
                            if (category == "WITHOUT_RECEIPT") {
                                false
                            } else {
                                expense.hasReceiptPhoto
                            }
                    }

                    (ExpensesTable innerJoin ProjectsTable)
                        .selectAll()
                        .where { ExpensesTable.id eq expenseId }
                        .single()
                        .let(::mapRowToExpenseDto)
                }
            } catch (e: SecurityException) {
                return@put call.respond(
                    HttpStatusCode.Forbidden,
                    e.message ?: "Access denied"
                )
            } catch (e: IllegalArgumentException) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    e.message ?: "Invalid subproject"
                )
            }

            if (updated == null) {
                return@put call.respond(
                    HttpStatusCode.NotFound,
                    "Expense not found"
                )
            }

            call.respond(HttpStatusCode.OK, updated)
        }

        delete {
            val session = call.checkSession() ?: return@delete

            val expenseId = call.request.queryParameters["expenseId"]
                .toUuidOrNull()
                ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    "Valid expenseId required"
                )

            val result = transaction {
                val existing = ExpensesTable
                    .selectAll()
                    .where {
                        (ExpensesTable.id eq expenseId) and
                            ExpensesTable.deletedAt.isNull()
                    }
                    .singleOrNull()
                    ?: return@transaction ExpenseDeleteResult.NOT_FOUND

                val ownerId = existing[ExpensesTable.userId].value
                val scope = existing[ExpensesTable.expenseScope]

                if (
                    ownerId != session.userId &&
                    session.role !in directorExpenseRoles
                ) {
                    return@transaction ExpenseDeleteResult.FORBIDDEN
                }

                if (
                    scope == "DIRECTOR" &&
                    session.role !in directorExpenseRoles
                ) {
                    return@transaction ExpenseDeleteResult.FORBIDDEN
                }

                ExpensesTable.update(
                    { ExpensesTable.id eq expenseId }
                ) {
                    it[deletedAt] = System.currentTimeMillis()
                    it[deletedBy] = session.userId
                }

                ExpenseDeleteResult.DELETED
            }

            when (result) {
                ExpenseDeleteResult.NOT_FOUND ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        "Expense not found"
                    )

                ExpenseDeleteResult.FORBIDDEN ->
                    call.respond(
                        HttpStatusCode.Forbidden,
                        "Access denied"
                    )

                ExpenseDeleteResult.DELETED ->
                    call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}