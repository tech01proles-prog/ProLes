package com.proles.server.routes

import com.proles.server.config.*
import com.proles.server.config.PermissionMiddleware.checkPermission
import com.proles.server.data.*
import com.proles.server.model.Permission
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Base64
import java.util.UUID

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
    val amount: Double = 0.0,
    val currency: String = "RUB",
    val receiptBase64: String? = null,
    val receiptFileName: String? = null,
    val receiptFileType: String? = null
)

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
    val amount: Double = 0.0,
    val currency: String = "RUB",
    val hasReceipt: Boolean = false,
    val recipients: List<TicketRecipientDto> = emptyList()
)

private val ticketJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private suspend fun ApplicationCall.checkTicketSession():
    SessionManager.Session? {
    val token = request.headers["X-Session-Token"]
    val session = SessionManager.validate(token)

    if (session == null) {
        respond(HttpStatusCode.Unauthorized, "Session expired")
        return null
    }

    return session
}


private const val MAX_TICKET_FILE_BYTES = 100 * 1024 * 1024
private const val MAX_TICKET_RECEIPT_BYTES = 25 * 1024 * 1024

private fun sanitizeTicketFileName(value: String): String =
    value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001F<>:\"/\\\\|?*]"), "_")
        .trim()
        .take(180)
        .ifBlank { "file" }

private fun escapeHtml(value: String): String = buildString {
    value.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> character
            }
        )
    }
}

private fun decodeBase64OrNull(
    value: String,
    maximumBytes: Int
): ByteArray? {
    val maximumEncodedLength =
        ((maximumBytes.toLong() + 2L) / 3L * 4L) + 16L

    if (value.length.toLong() > maximumEncodedLength) {
        return null
    }

    return runCatching {
        Base64.getDecoder().decode(value)
    }.getOrNull()?.takeIf { it.size <= maximumBytes }
}


internal fun Route.ticketRoutes() {
    route("/api/v1/tickets") {
        delete("/{ticketId}") {
            if (!call.checkPermission(Permission.TICKETS, "delete")) return@delete
            val session = call.checkTicketSession() ?: return@delete
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
            }

            call.respond(HttpStatusCode.NoContent)
        }

        // Загрузка билета
        post("/upload") {
            if (!call.checkPermission(Permission.TICKETS, "create")) {
                return@post
            }

            val session = call.checkTicketSession() ?: return@post

            val request = try {
                call.receive<TicketUploadRequest>()
            } catch (_: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid JSON"
                )
            }

            val projectId = runCatching {
                projectId
            }.getOrNull() ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                "Invalid projectId"
            )

            if (request.fileBase64.isBlank()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "fileBase64 required"
                )
            }

            if (!request.amount.isFinite() || request.amount < 0.0) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid amount"
                )
            }

            val allowedCurrencies = setOf("RUB", "BYN", "USD", "EUR")
            val currency = currency.trim().uppercase()

            if (currency !in allowedCurrencies) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Unsupported currency"
                )
            }

            val fileBytes = decodeBase64OrNull(
                request.fileBase64,
                MAX_TICKET_FILE_BYTES
            ) ?: return@post call.respond(
                HttpStatusCode.PayloadTooLarge,
                "Invalid Base64 or ticket exceeds 100 MB"
            )

            val receiptBytes = request.receiptBase64
                ?.takeIf(String::isNotBlank)
                ?.let {
                    decodeBase64OrNull(
                        it,
                        MAX_TICKET_RECEIPT_BYTES
                    ) ?: return@post call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        "Invalid Base64 or receipt exceeds 25 MB"
                    )
                }

            val recipientIds = request.recipientIds
                .mapNotNull { raw ->
                    runCatching { UUID.fromString(raw) }.getOrNull()
                }
                .distinct()

            if (recipientIds.size != request.recipientIds.distinct().size) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid recipientIds"
                )
            }

            val projectExists = transaction {
                ProjectsTable
                    .selectAll()
                    .where { ProjectsTable.id eq projectId }
                    .limit(1)
                    .any()
            }

            if (!projectExists) {
                return@post call.respond(
                    HttpStatusCode.NotFound,
                    "Project not found"
                )
            }

            val knownRecipientIds = transaction {
                UsersTable
                    .selectAll()
                    .where { UsersTable.id inList recipientIds }
                    .map { it[UsersTable.id].value }
                    .toSet()
            }

            if (knownRecipientIds.size != recipientIds.size) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Unknown recipientId"
                )
            }

            val ticketId = UUID.randomUUID()
            val originalFileName =
                sanitizeTicketFileName(request.fileName)
            val uniqueFileName = "${ticketId}_$originalFileName"
            val uploadDir = java.io.File("uploads/tickets").apply {
                mkdirs()
            }
            val ticketFile = java.io.File(uploadDir, uniqueFileName)
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
                    it[TicketsTable.projectId] = projectId
                    it[TicketsTable.fileName] = uniqueFileName
                    it[TicketsTable.originalName] = request.fileName
                    it[filePath] = "/uploads/tickets/$uniqueFileName"
                    it[TicketsTable.fileType] = request.fileType
                    it[fileSize] = fileBytes.size.toLong()
                    it[TicketsTable.sendToAccountant] = request.sendToAccountant
                    it[TicketsTable.accountantEmail] = request.accountantEmail
                    it[TicketsTable.amount] = request.amount            //
                    it[TicketsTable.currency] = currency        //
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
                    val todayJava = java.time.LocalDate.now()
                    val todayKt = kotlinx.datetime.LocalDate(
                        todayJava.year,
                        todayJava.monthValue,
                        todayJava.dayOfMonth
                    )
                    val expenseId = UUID.randomUUID()

                    ExpensesTable.insert {
                        it[ExpensesTable.id] = expenseId
                        it[userId] = expenseUserId
                        it[ExpensesTable.projectId] = projectId
                        it[date] = todayKt
                        it[type] = "OTHER"
                        it[name] = "Билет: ${originalFileName.take(50)}"
                        it[amount] = request.amount
                        it[ExpensesTable.currency] = currency
                        it[comment] =
                            "Автоматически создан при загрузке билета. " +
                                request.description.take(500)
                        it[receiptSubmitted] = hasReceipt
                        it[hasReceiptPhoto] = hasReceipt
                        it[createdAt] = System.currentTimeMillis()
                    }

                    if (hasReceipt) {
                        ExpenseReceiptsTable.insert {
                            it[id] = UUID.randomUUID()
                            it[ExpenseReceiptsTable.expenseId] = expenseId
                            it[imageUrl] = requireNotNull(receiptFilePath)
                            it[uploadedAt] = System.currentTimeMillis()
                        }
                    }
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
                ProjectsTable.selectAll().where { ProjectsTable.id eq projectId }
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
                        it[payload] = ticketJson.encodeToString(mapOf(
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
                        val safeSenderName = escapeHtml(senderName)
                        val safeProjectName = escapeHtml(projectName)
                        val safeDescription = escapeHtml(
                            request.description.take(2000)
                        )
                        val safeCurrency = escapeHtml(currency)

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
                                        ${"%.2f".format(request.amount)} ${currency}
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

                        val attachment = EmailAttachment(
                            fileName = originalFileName,
                            bytes = fileBytes,
                            mimeType = request.fileType
                                .trim()
                                .take(100)
                                .ifBlank { "application/octet-stream" }
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
                ticketJson.encodeToString(mapOf("ticketId" to ticketId.toString(), "projectId" to request.projectId)),
                "ticket"
            )
            if (request.receiptBase64 != null && request.amount > 0.0) {
                val financeRecipients = transaction {
                    UsersTable.selectAll().where { (UsersTable.role eq "superadmin") or (UsersTable.role eq "director") or (UsersTable.role eq "admin") }.map { it[UsersTable.id].value }.distinct()
                }
                NotificationService.notifyUsers(
                    financeRecipients, session.userId, "TICKET_RECEIPT", "🧷 Чек билета сохранён",
                    "Расход на Proles Company: ${"%.2f".format(request.amount)} ${currency}",
                    ticketJson.encodeToString(mapOf("ticketId" to ticketId.toString(), "receiptPath" to (receiptFilePath ?: ""))),
                    "ticketReceipt"
                )
            }
            call.respond(HttpStatusCode.Created, mapOf("id" to ticketId.toString(), "fileName" to uniqueFileName))
        }


        // 📥 Свои билеты (получатель)
        get("/my") {
            if (!call.checkPermission(Permission.TICKETS, "view")) return@get
            val session = call.checkTicketSession() ?: return@get
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
            val session = call.checkTicketSession() ?: return@post
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
}