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
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondFile

@Serializable
data class TicketUploadRequest(
    val projectId: String,
    val subprojectId: String? = null,
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
    val subprojectId: String? = null,
    val subprojectName: String = "",
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
    val receiptDownloadUrl: String? = null,
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

                        // Удаляем билет и связанный чек с диска.
            val ticketFilePath = ticket[TicketsTable.filePath]
            val receiptPath = ticket[TicketsTable.receiptPath]

            listOfNotNull(ticketFilePath, receiptPath).forEach { path ->
                runCatching {
                    java.io.File("." + path)
                        .takeIf { it.isFile }
                        ?.delete()
                }
            }

            call.respond(HttpStatusCode.NoContent)
        }

        // Загрузка билета
        post("/upload") {
            if (!call.checkPermission(Permission.TICKETS, "create")) {
                return@post
            }

            val session = call.checkTicketSession() ?: return@post
            println("🎫 [Ticket upload] started: userId=${session.userId}, role=${session.role}")

            val request = try {
                call.receive<TicketUploadRequest>()
            } catch (_: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid JSON"
                )
            }

            val projectId = runCatching {
                UUID.fromString(request.projectId.trim())
            }.getOrNull() ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                "Invalid projectId"
            )

            val subprojectId = request.subprojectId?.takeIf(String::isNotBlank)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: request.subprojectId?.takeIf(String::isNotBlank)?.let { return@post call.respond(HttpStatusCode.BadRequest, "Invalid subprojectId") }


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

            val currency = request.currency.trim().uppercase()


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

            if (subprojectId != null && transaction { SubprojectsTable.selectAll().where { (SubprojectsTable.id eq subprojectId) and (SubprojectsTable.projectId eq projectId) }.limit(1).none() }) return@post call.respond(HttpStatusCode.BadRequest, "Subproject does not belong to project")

            val knownRecipientIds =
                if (recipientIds.isEmpty()) {
                    emptySet()
                } else {
                    transaction {
                        UsersTable
                            .selectAll()
                            .where { UsersTable.id inList recipientIds }
                            .map { it[UsersTable.id].value }
                            .toSet()
                    }
                }

            if (knownRecipientIds.size != recipientIds.size) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Unknown recipientId"
                )
            }

            val ticketId = UUID.randomUUID()
            println("🎫 [Ticket upload] validated: projectId=$projectId, recipients=${recipientIds.size}, fileBytes=${fileBytes.size}, receiptBytes=${receiptBytes?.size ?: 0}, amount=${request.amount}")
            val originalFileName =
                sanitizeTicketFileName(request.fileName)
            val uniqueFileName = "${ticketId}_$originalFileName"
            val uploadDir = java.io.File("uploads/tickets").apply {
                mkdirs()
            }
            val ticketFile = java.io.File(uploadDir, uniqueFileName)

                        // Подготавливаем и сохраняем файлы до создания записей в БД.
            val receiptFile = receiptBytes?.let { bytes ->
                val receiptName = sanitizeTicketFileName(
                    request.receiptFileName.orEmpty()
                )
                java.io.File(
                    uploadDir,
                    "${ticketId}_receipt_$receiptName"
                ).also { file ->
                    file.writeBytes(bytes)
                }
            }

            val receiptFilePath = receiptFile?.let {
                "/uploads/tickets/${it.name}"
            }
            val receiptOriginalName = receiptFile?.let {
                sanitizeTicketFileName(
                    request.receiptFileName.orEmpty()
                )
            }
            val receiptFileType = receiptFile?.let {
                request.receiptFileType
                    ?.trim()
                    ?.take(100)
                    ?.ifBlank { "application/octet-stream" }
                    ?: "application/octet-stream"
            }

            try {
                ticketFile.writeBytes(fileBytes)

                transaction {
                    val companyUser = UsersTable
                        .selectAll()
                        .where {
                            (UsersTable.name eq "Proles Company") or
                                (UsersTable.login eq "proles_company")
                        }
                        .singleOrNull()

                    val companyUserId =
                        companyUser?.get(UsersTable.id)?.value

                    TicketsTable.insert {
                        it[TicketsTable.id] = ticketId
                        it[uploadedBy] = session.userId
                        it[TicketsTable.projectId] = projectId
                        if (subprojectId != null) it[TicketsTable.subprojectId] = subprojectId
                        it[TicketsTable.fileName] = uniqueFileName
                        it[TicketsTable.originalName] = originalFileName
                        it[filePath] = "/uploads/tickets/$uniqueFileName"
                        it[TicketsTable.fileType] = request.fileType
                            .trim()
                            .take(100)
                            .ifBlank { "application/octet-stream" }
                        it[fileSize] = fileBytes.size.toLong()
                        it[TicketsTable.sendToAccountant] =
                            request.sendToAccountant
                        it[TicketsTable.accountantEmail] =
                            request.accountantEmail.trim()
                        it[TicketsTable.amount] = request.amount
                        it[TicketsTable.currency] = currency
                        it[TicketsTable.description] =
                            request.description.trim().take(2000)
                        it[uploadedAt] = System.currentTimeMillis()
                        it[TicketsTable.receiptPath] = receiptFilePath
                        it[TicketsTable.receiptOriginalName] =
                            receiptOriginalName
                        it[TicketsTable.receiptFileType] =
                            receiptFileType
                    }

                    if (request.amount > 0.0) {
                        val expenseUserId =
                            companyUserId ?: session.userId
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
                            if (subprojectId != null) it[ExpensesTable.subprojectId] = subprojectId
                            it[date] = todayKt
                            it[type] = "OTHER"
                            it[name] =
                                "Билет: ${originalFileName.take(50)}"
                            it[amount] = request.amount
                            it[ExpensesTable.currency] = currency
                            it[comment] =
                                "Автоматически создан при загрузке " +
                                    "билета. " +
                                    request.description.take(500)
                            it[receiptSubmitted] = hasReceipt
                            it[hasReceiptPhoto] = hasReceipt
                            it[createdAt] = System.currentTimeMillis()
                        }

                        if (hasReceipt) {
                            ExpenseReceiptsTable.insert {
                                it[id] = UUID.randomUUID()
                                it[ExpenseReceiptsTable.expenseId] =
                                    expenseId
                                it[imageUrl] =
                                    requireNotNull(receiptFilePath)
                                it[uploadedAt] =
                                    System.currentTimeMillis()
                            }
                        }
                    }

                    recipientIds.forEach { recipientId ->
                        TicketRecipientsTable.insert {
                            it[TicketRecipientsTable.id] =
                                UUID.randomUUID()
                            it[TicketRecipientsTable.ticketId] =
                                ticketId
                            it[TicketRecipientsTable.userId] =
                                recipientId
                        }
                    }
                }
            } catch (error: Exception) {
                runCatching {
                    ticketFile.takeIf { it.isFile }?.delete()
                }
                runCatching {
                    receiptFile?.takeIf { it.isFile }?.delete()
                }
                throw error
            }

            println("🎫 [Ticket upload] saved: ticketId=$ticketId, file=$uniqueFileName")
            val projectName = transaction {
                ProjectsTable.selectAll().where { ProjectsTable.id eq projectId }
                    .single()[ProjectsTable.name]
            }
            val senderName = transaction {
                UsersTable.selectAll().where { UsersTable.id eq session.userId }.single()[UsersTable.name]
            }
            val recipientUuids = request.recipientIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }

            println("🎫 [Ticket upload] preparing notifications: ticketId=$ticketId, recipientUuids=${recipientUuids.size}, sendToAccountant=${request.sendToAccountant}")
            // Важно: загрузка билета сама по себе не вызывает TelegramService.sendFile().
            // Telegram может отправить текстовое уведомление через NotificationService, если
            // у получателя включён Telegram и указан telegramChatId.
            //  Отправка email бухгалтеру (если выбрана галочка)
            if (request.sendToAccountant && request.accountantEmail.isNotBlank()) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Читаем файл с диска
                        val ticketFile = java.io.File("uploads/tickets/$uniqueFileName")
                        val fileBytes = if (ticketFile.exists()) ticketFile.readBytes() else fileBytes

                        val emailProjectName = projectName
                            .replace(Regex("[\\r\\n]"), " ")
                            .take(200)

                        val subject = "Новый билет по проекту «$emailProjectName»"

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
                                    <td style="padding: 8px;">$safeSenderName</td></tr>
                                <tr><td style="padding: 8px; background: #f5f5f5; font-weight: bold;">Проект:</td>
                                    <td style="padding: 8px;">$safeProjectName</td></tr>
                                ${if (request.amount > 0.0) """
                                <tr><td style="padding: 8px; background: #FFF3E0; font-weight: bold; color: #E65100;">💰 Стоимость:</td>
                                    <td style="padding: 8px; background: #FFF3E0; font-weight: bold; color: #E65100;">
                                        ${"%.2f".format(request.amount)} ${safeCurrency}
                                    </td></tr>
                                """ else ""}
                                ${if (request.description.isNotBlank()) """
                                <tr><td style="padding: 8px; background: #f5f5f5; font-weight: bold;">Описание:</td>
                                    <td style="padding: 8px;">${safeDescription}</td></tr>
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
            println("🎫 [Ticket upload] calling NotificationService.notifyUsers for TICKET")
            NotificationService.notifyUsers(
                recipientUuids, session.userId, "TICKET", "🎫 Новый билет",
                "$senderName загрузил билет по проекту «$projectName»",
                ticketJson.encodeToString(mapOf("ticketId" to ticketId.toString(), "projectId" to request.projectId)),
                "ticket"
            )
            if (recipientUuids.isEmpty()) {
                println("🎫 [Ticket upload] no explicit recipients; scheduling Telegram fallback to uploader userId=${session.userId}")
                CoroutineScope(Dispatchers.IO).launch {
                    val selfTelegramResult = NotificationService.notifyTelegramUser(
                        session.userId,
                        "<b>🎫 Новый билет</b>\n\n" +
                            NotificationService.escapeTelegram(
                                "$senderName загрузил билет по проекту «$projectName»"
                            ),
                        "ticket"
                    )
                    println("🎫 [Ticket upload] uploader Telegram fallback result=$selfTelegramResult")
                }
            }
            if (request.receiptBase64 != null && request.amount > 0.0) {
                val financeRecipients = transaction {
                    UsersTable.selectAll().where { (UsersTable.role eq "superadmin") or (UsersTable.role eq "director") or (UsersTable.role eq "admin") }.map { it[UsersTable.id].value }.distinct()
                }
                println("🎫 [Ticket upload] calling NotificationService.notifyUsers for TICKET_RECEIPT: recipients=${financeRecipients.size}")
                NotificationService.notifyUsers(
                    financeRecipients, session.userId, "TICKET_RECEIPT", "🧷 Чек билета сохранён",
                    "Расход на Proles Company: ${"%.2f".format(request.amount)} ${currency}",
                    ticketJson.encodeToString(mapOf("ticketId" to ticketId.toString(), "receiptPath" to (receiptFilePath ?: ""))),
                    "ticketReceipt"
                )
            }
            println("🎫 [Ticket upload] completed: ticketId=$ticketId")
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
                            subprojectId = row[TicketsTable.subprojectId]?.value?.toString(),
                            subprojectName = row[TicketsTable.subprojectId]?.value?.let { id -> SubprojectsTable.selectAll().where { SubprojectsTable.id eq id }.limit(1).singleOrNull()?.get(SubprojectsTable.name) }.orEmpty(),
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
                            receiptDownloadUrl = row[TicketsTable.receiptPath]?.let { "/api/v1/tickets/$ticketId/receipt" },
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
                            subprojectId = row[TicketsTable.subprojectId]?.value?.toString(),
                            subprojectName = row[TicketsTable.subprojectId]?.value?.let { id -> SubprojectsTable.selectAll().where { SubprojectsTable.id eq id }.limit(1).singleOrNull()?.get(SubprojectsTable.name) }.orEmpty(),
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
                            receiptDownloadUrl = row[TicketsTable.receiptPath]?.let { "/api/v1/tickets/$ticketId/receipt" },
                            recipients = recipients,
                            downloadUrl = row[TicketsTable.filePath]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, tickets)
        }

        get("/{ticketId}/receipt") {
            if (!call.checkPermission(Permission.TICKETS, "view")) return@get
            val session = call.checkTicketSession() ?: return@get
            val ticketId = runCatching { UUID.fromString(call.parameters["ticketId"]) }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid ticketId")
            val ticket = transaction { TicketsTable.selectAll().where { TicketsTable.id eq ticketId }.limit(1).singleOrNull() } ?: return@get call.respond(HttpStatusCode.NotFound, "Ticket not found")
            val allowed = session.role in listOf("admin", "superadmin", "director") || ticket[TicketsTable.uploadedBy].value == session.userId || transaction { TicketRecipientsTable.selectAll().where { (TicketRecipientsTable.ticketId eq ticketId) and (TicketRecipientsTable.userId eq session.userId) }.limit(1).any() }
            if (!allowed) return@get call.respond(HttpStatusCode.Forbidden, "Access denied")
            val storedPath = ticket[TicketsTable.receiptPath] ?: return@get call.respond(HttpStatusCode.NotFound, "Receipt not found")
            val file = java.io.File("." + storedPath)
            if (!file.isFile) return@get call.respond(HttpStatusCode.NotFound, "Receipt file not found")
            val fileName = ticket[TicketsTable.receiptOriginalName] ?: "receipt"
            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString())
            call.respondFile(file)
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