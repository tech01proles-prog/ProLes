package com.proles.server.config

import com.proles.server.data.FcmTokensTable
import com.proles.server.data.NotificationPreferencesTable
import com.proles.server.data.NotificationsTable
import com.proles.server.data.TelegramLinksTable
import com.proles.server.data.UsersTable
import com.proles.server.model.BusinessTripDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import java.time.LocalDate as JavaLocalDate

/** Единая доставка событий в Web/FCM/Telegram с разделением системных и персональных сообщений. */
object NotificationService {
    private val deliveryScope = CoroutineScope(Dispatchers.IO)

    private val webAppUrl = System.getenv("PROLES_WEB_URL")?.trim()?.trimEnd('/').takeIf { !it.isNullOrBlank() }

    private fun preferenceEnabled(userId: UUID, key: String): Boolean = transaction {
        val row = NotificationPreferencesTable.selectAll()
            .where { NotificationPreferencesTable.userId eq userId }
            .singleOrNull()
        when (key) {
            "trip" -> row?.get(NotificationPreferencesTable.tripEnabled) ?: true
            "vacation" -> row?.get(NotificationPreferencesTable.vacationEnabled) ?: true
            "dayoff" -> row?.get(NotificationPreferencesTable.dayoffEnabled) ?: true
            "expense" -> row?.get(NotificationPreferencesTable.expenseEnabled) ?: true
            "payroll" -> row?.get(NotificationPreferencesTable.payrollEnabled) ?: true
            "ticket" -> row?.get(NotificationPreferencesTable.ticketEnabled) ?: true
            "tripChange" -> row?.get(NotificationPreferencesTable.tripChangeEnabled) ?: true
            "vacationDecision" -> row?.get(NotificationPreferencesTable.vacationDecisionEnabled) ?: true
            "ticketReceipt" -> row?.get(NotificationPreferencesTable.ticketReceiptEnabled) ?: true
            else -> true
        }
    }

    private data class TelegramConfig(
        val enabled: Boolean,
        val chatId: String?
    )

    private fun telegramConfig(userId: UUID, preferenceKey: String = ""): TelegramConfig = transaction {
        val pref = NotificationPreferencesTable.selectAll()
            .where { NotificationPreferencesTable.userId eq userId }
            .singleOrNull()
        val linkedChatId = TelegramLinksTable.selectAll()
            .where { TelegramLinksTable.userId eq userId }
            .singleOrNull()
            ?.get(TelegramLinksTable.chatId)
        val prefChatId = pref?.get(NotificationPreferencesTable.telegramChatId)
            ?.takeIf { it.isNotBlank() }
        val chatId = prefChatId ?: linkedChatId
        val telegramEnabled = if (pref == null) linkedChatId != null else pref[NotificationPreferencesTable.telegramEnabled]
        val eventEnabled = preferenceKey.isBlank() || preferenceEnabled(userId, preferenceKey)
        TelegramConfig(telegramEnabled && eventEnabled && !chatId.isNullOrBlank(), chatId)
    }

    /**
     * Персональное уведомление. Системный Telegram-чат здесь никогда не используется.
     */
    fun notifyUsers(
        targetUserIds: Collection<UUID>,
        senderUserId: UUID,
        type: String,
        title: String,
        message: String,
        payload: String = "",
        preferenceKey: String = "",
        deliverTelegram: Boolean = true
    ) {
        val uniqueTargets = targetUserIds.distinct().filter { it != senderUserId }
        println("🔔 [Notification] type=$type targets=${targetUserIds.size} unique=${uniqueTargets.size} preference=$preferenceKey telegram=$deliverTelegram")
        val enabledTargets = if (preferenceKey.isBlank()) uniqueTargets else {
            uniqueTargets.filter { preferenceEnabled(it, preferenceKey) }
        }

        if (enabledTargets.isEmpty()) {
            println("🔔 [Notification] no personal recipients after preference filtering")
            return
        }

        transaction {
            enabledTargets.forEach { targetId ->
                NotificationsTable.insert {
                    it[id] = UUID.randomUUID()
                    it[targetUserId] = targetId
                    it[NotificationsTable.senderUserId] = senderUserId
                    it[NotificationsTable.type] = type
                    it[NotificationsTable.title] = title
                    it[NotificationsTable.message] = message
                    it[NotificationsTable.payload] = payload
                    it[NotificationsTable.createdAt] = System.currentTimeMillis()
                }
            }
        }

        deliveryScope.launch {
            enabledTargets.forEach { userId ->
                val fcmTokens = transaction {
                    FcmTokensTable.selectAll()
                        .where { FcmTokensTable.userId eq userId }
                        .map { it[FcmTokensTable.token] }
                }
                fcmTokens.forEach { token ->
                    runCatching {
                        FirebaseService.sendPush(
                            token = token,
                            title = title,
                            body = message.lines().firstOrNull().orEmpty().take(180),
                            data = mapOf("type" to type, "payload" to payload)
                        )
                    }.onFailure { error ->
                        println("❌ FCM delivery failed for userId=$userId: ${error::class.simpleName}: ${error.message}")
                    }
                }

                if (!deliverTelegram) {
                    println("🔔 [Notification] Telegram intentionally disabled for type=$type")
                    return@forEach
                }

                val telegram = telegramConfig(userId, preferenceKey)
                val chatId = telegram.chatId
                println("🔎 [Notification] Telegram personal: userId=$userId enabled=${telegram.enabled} chatIdPresent=${!chatId.isNullOrBlank()}")
                if (!telegram.enabled || chatId.isNullOrBlank()) return@forEach

                runCatching {
                    TelegramService.sendMessageToChat(
                        chatId,
                        formatPersonalTelegram(type, title, message)
                    )
                }.onFailure { error ->
                    println("❌ Telegram personal delivery failed for userId=$userId: ${error::class.simpleName}: ${error.message}")
                }
            }
        }
    }

    suspend fun notifyTelegramUser(userId: UUID, text: String, preferenceKey: String = ""): Boolean {
        val config = telegramConfig(userId, preferenceKey)
        val chatId = config.chatId
        println("🔎 [Notification] user Telegram: userId=$userId enabled=${config.enabled} chatIdPresent=${!chatId.isNullOrBlank()} preference=$preferenceKey")
        if (!config.enabled || chatId.isNullOrBlank()) return false
        return TelegramService.sendMessageToChat(chatId, text)
    }

    suspend fun notifyTelegramUserWithFile(
        userId: UUID,
        caption: String,
        fileBytes: ByteArray,
        fileName: String,
        preferenceKey: String = ""
    ): Boolean {
        val config = telegramConfig(userId, preferenceKey)
        val chatId = config.chatId
        println("🔎 [Notification] user Telegram file: userId=$userId enabled=${config.enabled} chatIdPresent=${!chatId.isNullOrBlank()} file=$fileName bytes=${fileBytes.size}")
        if (!config.enabled || chatId.isNullOrBlank()) return false
        return TelegramService.sendDocumentToChats(listOf(chatId), fileBytes, fileName, caption)
    }

    fun notifyTicket(
        ticketId: UUID,
        projectName: String,
        recipientIds: Collection<UUID>,
        senderUserId: UUID,
        senderName: String,
        fileBytes: ByteArray,
        fileName: String
    ) {
        val recipients = recipientIds.distinct()
        val projectTag = projectTag(projectName)
        val personalCaption = listOf(
            "#БИЛЕТ",
            "<b>🎫 Новый билет</b>",
            projectTag
        ).filter { it.isNotBlank() }.joinToString("\n")

        notifyUsers(
            targetUserIds = recipients,
            senderUserId = senderUserId,
            type = "TICKET",
            title = "🎫 Новый билет",
            message = "$senderName загрузил билет по проекту «$projectName»",
            payload = "{\"ticketId\":\"$ticketId\"}",
            preferenceKey = "ticket",
            deliverTelegram = false
        )

        deliveryScope.launch {
            val recipientLabels = transaction {
                recipients.mapNotNull { userId ->
                    val user = UsersTable.selectAll()
                        .where { UsersTable.id eq userId }
                        .singleOrNull()
                    if (user == null) {
                        null
                    } else {
                        val linkUsername = TelegramLinksTable.selectAll()
                            .where { TelegramLinksTable.userId eq userId }
                            .singleOrNull()
                            ?.get(TelegramLinksTable.username)
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                        val profileUsername = user[UsersTable.telegramUsername]
                            .trim()
                            .takeIf { it.isNotBlank() }
                        val username = linkUsername ?: profileUsername
                        if (username != null) {
                            if (username.startsWith("@")) username else "@$username"
                        } else {
                            surnameInitials(
                                user[UsersTable.lastName],
                                user[UsersTable.firstName],
                                user[UsersTable.middleName],
                                user[UsersTable.name]
                            )
                        }
                    }
                }
            }

            val systemCaption = listOf(
                "#БИЛЕТ",
                "<b>🎫 Новый билет</b>",
                projectTag,
                recipientLabels.joinToString(", ").ifBlank { "—" }
            ).filter { it.isNotBlank() }.joinToString("\n")

            println("📢 [Ticket notification] system chat: ticketId=$ticketId configured=${TelegramService.configuredSystemChatId() != null}")
            TelegramService.sendSystemDocument(fileBytes, fileName, systemCaption.take(1000))

            recipients.forEach { userId ->
                runCatching {
                    notifyTelegramUserWithFile(
                        userId = userId,
                        caption = personalCaption,
                        fileBytes = fileBytes,
                        fileName = fileName,
                        preferenceKey = "ticket"
                    )
                }.onFailure { error ->
                    println("❌ [Ticket notification] userId=$userId: ${error::class.simpleName}: ${error.message}")
                }
            }
        }
    }

    fun notifyBusinessTripCreated(trip: BusinessTripDto) {
        val participantIds = listOfNotNull(runCatching { UUID.fromString(trip.userId) }.getOrNull()) +
            trip.participants.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        notifyUsers(
            targetUserIds = participantIds.distinct(),
            senderUserId = runCatching { UUID.fromString(trip.userId) }.getOrDefault(UUID(0L, 0L)),
            type = "TRIP_CREATED",
            title = "🚆 Новая командировка",
            message = buildBusinessTripCreatedMessage(trip).removePrefix("#КОМАНДИРОВКА\n").removePrefix("<b>🚆 НОВАЯ КОМАНДИРОВКА</b>\n"),
            payload = "{\"tripId\":\"${trip.id}\"}",
            preferenceKey = "trip",
            deliverTelegram = false
        )
        deliveryScope.launch {
            val message = buildBusinessTripCreatedMessage(trip)
            println("🚆 [Trip notification] NEW: tripId=${trip.id} systemChat=${TelegramService.configuredSystemChatId() != null}")
            TelegramService.sendSystemMessage(message)
        }
    }

    fun notifyBusinessTripCompleted(trip: BusinessTripDto) {
        val ownerId = runCatching { UUID.fromString(trip.userId) }.getOrNull()
        if (ownerId != null) {
            val participantIds = listOf(ownerId) + trip.participants.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            notifyUsers(
                targetUserIds = participantIds.distinct(),
                senderUserId = ownerId,
                type = "TRIP_COMPLETED",
                title = "✅ Командировка завершена",
                message = buildBusinessTripCompletedMessage(trip).removePrefix("#КОМАНДИРОВКА\n").removePrefix("<b>🚆 КОМАНДИРОВКА ЗАВЕРШЕНА</b>\n"),
                payload = "{\"tripId\":\"${trip.id}\"}",
                preferenceKey = "tripChange",
                deliverTelegram = false
            )
        }
        deliveryScope.launch {
            val message = buildBusinessTripCompletedMessage(trip)
            println("✅ [Trip notification] COMPLETED: tripId=${trip.id} systemChat=${TelegramService.configuredSystemChatId() != null}")
            TelegramService.sendSystemMessage(message)
        }
    }

    private fun buildBusinessTripCreatedMessage(trip: BusinessTripDto): String {
        val participants = resolveTripParticipants(trip.userId, trip.participants)
            .ifEmpty { listOf("Участники не указаны") }
        val typeLabel = if (trip.type == "TRANSFER") "Переезд" else "Выезд"
        val projectTag = projectTag(trip.projectName)
        return buildString {
            appendLine("#КОМАНДИРОВКА")
            appendLine("<b>🚆 НОВАЯ КОМАНДИРОВКА</b>")
            appendLine(participants.joinToString(", "))
            appendLine(trip.startDate.ifBlank { trip.date })
            appendLine((typeLabel + if (projectTag.isNotBlank()) " $projectTag" else "").trim())
            if (trip.transport.isNotBlank()) appendLine(trip.transport.trim())
            if (trip.notes.isNotBlank()) appendLine(trip.notes.trim())
        }.trim()
    }

    private fun buildBusinessTripCompletedMessage(trip: BusinessTripDto): String {
        val participants = resolveTripParticipants(trip.userId, trip.participants)
            .ifEmpty { listOf("Участники не указаны") }
        val dateRange = listOf(
            trip.startDate.ifBlank { trip.date },
            trip.endDate ?: trip.completedDate
        ).filter { !it.isNullOrBlank() }.joinToString(" — ")
        val projectTag = projectTag(trip.projectName)
        return buildString {
            appendLine("#КОМАНДИРОВКА")
            appendLine("<b>🚆 КОМАНДИРОВКА ЗАВЕРШЕНА</b>")
            appendLine(participants.joinToString(", "))
            appendLine(dateRange)
            if (projectTag.isNotBlank()) appendLine(projectTag)
        }.trim()
    }

    private fun resolveTripParticipants(ownerIdRaw: String, participantIds: Collection<String>): List<String> {
        val ids = listOf(ownerIdRaw).plus(participantIds)
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            .distinct()
        return transaction {
            ids.mapNotNull { userId ->
                UsersTable.selectAll()
                    .where { UsersTable.id eq userId }
                    .singleOrNull()
                    ?.let {
                        surnameInitials(
                            it[UsersTable.lastName],
                            it[UsersTable.firstName],
                            it[UsersTable.middleName],
                            it[UsersTable.name]
                        )
                    }
            }
        }
    }

    private fun surnameInitials(lastName: String, firstName: String, middleName: String, legacyName: String): String {
        val surname = lastName.trim().ifBlank {
            legacyName.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        }
        val firstInitial = firstName.trim().firstOrNull()?.uppercaseChar()
        val middleInitial = middleName.trim().firstOrNull()?.uppercaseChar()
        return buildString {
            append(surname)
            if (firstInitial != null) append(" ").append(firstInitial).append(".")
            if (middleInitial != null) append(middleInitial).append(".")
        }.ifBlank { "Неизвестный" }
    }

    private fun projectTag(projectName: String): String =
        projectName.trim().takeIf { it.isNotBlank() }?.let { raw ->
            val searchable = raw.replace(Regex("[^\\p{L}\\p{N}_]+"), "_").trim('_')
            if (searchable.isBlank()) "" else "#${escapeTelegram(searchable)}"
        }.orEmpty()

    fun notifyDirectorsTelegram(
        tag: String,
        title: String,
        message: String,
        payload: String = "",
        preferenceKey: String,
        linkUrl: String? = null
    ) {
        val directors = transaction {
            UsersTable.selectAll()
                .where { UsersTable.role eq "director" }
                .map { it[UsersTable.id].value }
                .distinct()
        }
        println("📣 [Notification] director alert: tag=$tag directors=${directors.size}")

        deliveryScope.launch {
            directors.forEach { directorId ->
                val config = telegramConfig(directorId, preferenceKey)
                val chatId = config.chatId
                println("🔎 [Notification] director Telegram: userId=$directorId enabled=${config.enabled} chatIdPresent=${!chatId.isNullOrBlank()}")
                if (!config.enabled || chatId.isNullOrBlank()) return@forEach

                val body = buildList {
                    add("#$tag")
                    add("<b>${escapeTelegram(title)}</b>")
                    add(escapeTelegram(message))
                    if (!linkUrl.isNullOrBlank()) add(linkUrl)
                }.joinToString("\n")

                runCatching {
                    TelegramService.sendMessageToChat(chatId, body)
                }.onFailure { error ->
                    println("❌ [Notification] director Telegram failed: userId=$directorId, ${error::class.simpleName}: ${error.message}")
                }
            }
        }
    }

    fun vacationStatus(start: String, end: String, status: String): String {
        val normalized = status.uppercase()
        return when (normalized) {
            "PENDING" -> "На рассмотрении"
            "REJECTED" -> "Отклонен"
            "APPROVED" -> {
                val today = JavaLocalDate.now()
                val startDate = runCatching { JavaLocalDate.parse(start) }.getOrNull()
                val endDate = runCatching { JavaLocalDate.parse(end) }.getOrNull()
                when {
                    endDate != null && today.isAfter(endDate) -> "Завершен"
                    startDate != null && !today.isBefore(startDate) -> "В процессе"
                    else -> "Одобрен"
                }
            }
            "IN_PROGRESS" -> "В процессе"
            "COMPLETED" -> "Завершен"
            else -> status
        }
    }

    fun formatVacationTelegram(
        title: String,
        employeeName: String,
        start: String,
        end: String,
        status: String,
        reason: String = "",
        linkUrl: String? = null
    ): String = buildList {
        add("#ОТПУСК")
        add("<b>${escapeTelegram(title)}</b>")
        add(escapeTelegram(employeeName))
        add("$start — $end")
        add("Статус: ${escapeTelegram(vacationStatus(start, end, status))}")
        if (reason.isNotBlank()) add("Причина: ${escapeTelegram(reason)}")
        if (!linkUrl.isNullOrBlank()) add(linkUrl)
    }.joinToString("\n")

    fun formatDayOffTelegram(title: String, employeeName: String, date: String): String = buildList {
        add("#ВЫХОДНОЙ")
        add("<b>${escapeTelegram(title)}</b>")
        add(escapeTelegram(employeeName))
        add(escapeTelegram(date))
    }.joinToString("\n")
    fun userDisplayName(userId: UUID): String = transaction {
        val row = UsersTable.selectAll().where { UsersTable.id eq userId }.singleOrNull()
        row?.let {
            surnameInitials(
                it[UsersTable.lastName],
                it[UsersTable.firstName],
                it[UsersTable.middleName],
                it[UsersTable.name]
            )
        } ?: "Сотрудник"
    }
    fun linkedChatId(userId: UUID): String? = transaction {
        TelegramLinksTable.selectAll()
            .where { TelegramLinksTable.userId eq userId }
            .singleOrNull()
            ?.get(TelegramLinksTable.chatId)
    }

    private fun formatPersonalTelegram(type: String, title: String, message: String): String {
        val tag = when (type) {
            "VACATION_REQUEST", "VACATION_DECISION" -> "#ОТПУСК"
            "DAYOFF_WEEKDAY" -> "#ВЫХОДНОЙ"
            "EXPENSE_CREATED" -> "#РАСХОД"
            "PAYROLL_UPDATED" -> "#ЗАРПЛАТА"
            "TICKET_RECEIPT" -> "#БИЛЕТ"
            else -> ""
        }
        return listOf(
            tag,
            "<b>${escapeTelegram(title)}</b>",
            escapeTelegram(message)
        ).filter { it.isNotBlank() }.joinToString("\n")
    }

    fun escapeTelegram(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}