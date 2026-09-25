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
data class UserPermissionOverrideDto(
    val permission: String,
    val canView: Boolean? = null,
    val canCreate: Boolean? = null,
    val canEdit: Boolean? = null,
    val canDelete: Boolean? = null
)

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



private fun String?.toUuidOrNull(): UUID? =
    this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private fun taxInclusiveCost(
    earnedAmount: Double,
    isRemote: Boolean
): Double =
    if (isRemote) {
        earnedAmount / 0.87
    } else {
        earnedAmount * 1.37
    }

fun Route.dataRoutes() {
    fcmRoutes()
    timeEntryRoutes()
    projectCostRoutes()
    projectRoutes()
    expenseRoutes()
    incomeRoutes()
    positionRoutes()
    userRoutes()
    absenceRoutes()
    businessTripRoutes()
    notificationRoutes()
    personalTimesheetRoutes()

    route("/api/v1/notification-preferences") {
        get {
            val session = call.checkNotificationSession() ?: return@get
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
            val session = call.checkNotificationSession() ?: return@put
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
            val session = call.checkNotificationSession() ?: return@get
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
            val session = call.checkNotificationSession() ?: return@delete
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
            val session = call.checkNotificationSession() ?: return@post
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
            val session = call.checkNotificationSession() ?: return@get
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
            val session = call.checkNotificationSession() ?: return@post
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
            val session = call.checkNotificationSession() ?: return@delete
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

            val session = call.checkNotificationSession() ?: return@put
            
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