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

/** Единая доставка событий в Web/FCM/Telegram с разделением системных и персональных сообщений. */
object NotificationService {
    private val deliveryScope = CoroutineScope(Dispatchers.IO)

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
                println("🔎 [Notification] Telegram personal: userId=$userId enabled=${telegram.enabled} chatIdPresent=${!telegram.chatId.isNullOrBlank()}")
                if (!telegram.enabled || telegram.chatId.isNullOrBlank()) return@forEach

                runCatching {
                    TelegramService.sendMessageToChat(
                        telegram.chatId,
                        "<b>${escapeTelegram(title)}</b>\n\n${escapeTelegram(message)}"
                    )
                }.onFailure { error ->
                    println("❌ Telegram personal delivery failed for userId=$userId: ${error::class.simpleName}: ${error.message}")
                }
            }
        }
    }

    suspend fun notifyTelegramUser(userId: UUID, text: String, preferenceKey: String = ""): Boolean {
        val config = telegramConfig(userId, preferenceKey)
        println("🔎 [Notification] user Telegram: userId=$userId enabled=${config.enabled} chatIdPresent=${!config.chatId.isNullOrBlank()} preference=$preferenceKey")
        if (!config.enabled || config.chatId.isNullOrBlank()) return false
        return TelegramService.sendMessageToChat(config.chatId, text)
    }

    suspend fun notifyTelegramUserWithFile(
        userId: UUID,
        caption: String,
        fileBytes: ByteArray,
        fileName: String,
        preferenceKey: String = ""
    ): Boolean {
        val config = telegramConfig(userId, preferenceKey)
        println("🔎 [Notification] user Telegram file: userId=$userId enabled=${config.enabled} chatIdPresent=${!config.chatId.isNullOrBlank()} file=$fileName bytes=${fileBytes.size}")
        if (!config.enabled || config.chatId.isNullOrBlank()) return false
        return TelegramService.sendDocumentToChats(listOf(config.chatId), fileBytes, fileName, caption)
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
        deliveryScope.launch {
            val message = buildBusinessTripCreatedMessage(trip)
            println("🚆 [Trip notification] NEW: tripId=${trip.id} systemChat=${TelegramService.configuredSystemChatId() != null}")
            TelegramService.sendSystemMessage(message)
        }
    }

    fun notifyBusinessTripCompleted(trip: BusinessTripDto) {
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
        projectName.trim().takeIf { it.isNotBlank() }?.let { "#${escapeTelegram(it)}" }.orEmpty()

    fun linkedChatId(userId: UUID): String? = transaction {
        TelegramLinksTable.selectAll()
            .where { TelegramLinksTable.userId eq userId }
            .singleOrNull()
            ?.get(TelegramLinksTable.chatId)
    }

    fun escapeTelegram(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}