package com.proles.server.config

import com.proles.server.data.FcmTokensTable
import com.proles.server.data.NotificationPreferencesTable
import com.proles.server.data.NotificationsTable
import com.proles.server.data.TelegramLinksTable
import com.proles.server.data.UsersTable
import com.proles.server.model.Permission
import com.proles.server.config.PermissionMiddleware
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** Единая доставка событий в Web/FCM/Telegram с учётом пользовательских настроек. */
object NotificationService {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun preferenceEnabled(userId: UUID, key: String): Boolean = transaction {
        val row = NotificationPreferencesTable.selectAll()
            .where { NotificationPreferencesTable.userId eq userId }
            .singleOrNull()
        when (key) {
            "trip" -> row?.get(NotificationPreferencesTable.tripEnabled) ?: true
            "tripChange" -> row?.get(NotificationPreferencesTable.tripChangeEnabled) ?: true
            "tripBroadcast" -> row?.get(NotificationPreferencesTable.tripTelegramBroadcast) ?: false
            "vacation" -> row?.get(NotificationPreferencesTable.vacationEnabled) ?: true
            "vacationDecision" -> row?.get(NotificationPreferencesTable.vacationDecisionEnabled) ?: true
            "dayoff" -> row?.get(NotificationPreferencesTable.dayoffEnabled) ?: true
            "expense" -> row?.get(NotificationPreferencesTable.expenseEnabled) ?: true
            "expenseCreated" -> row?.get(NotificationPreferencesTable.expenseCreatedEnabled) ?: true
            "payroll" -> row?.get(NotificationPreferencesTable.payrollEnabled) ?: true
            "ticket" -> row?.get(NotificationPreferencesTable.ticketEnabled) ?: true
            "ticketReceipt" -> row?.get(NotificationPreferencesTable.ticketReceiptEnabled) ?: true
            else -> true
        }
    }

    fun notifyUsers(
        targetUserIds: Collection<UUID>,
        senderUserId: UUID,
        type: String,
        title: String,
        message: String,
        payload: String = "",
        preferenceKey: String = ""
    ) {
        val uniqueTargets = targetUserIds.filter { it != senderUserId }.distinct()
        if (uniqueTargets.isEmpty()) return

        val enabledTargets = if (preferenceKey.isBlank()) uniqueTargets else {
            uniqueTargets.filter { preferenceEnabled(it, preferenceKey) }
        }
        if (enabledTargets.isEmpty()) return

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

        CoroutineScope(Dispatchers.IO).launch {
            enabledTargets.forEach { userId ->
                val fcmTokens = transaction {
                    FcmTokensTable.selectAll()
                        .where { FcmTokensTable.userId eq userId }
                        .map { it[FcmTokensTable.token] }
                }
                fcmTokens.forEach { token ->
                    FirebaseService.sendPush(
                        token = token,
                        title = title,
                        body = message.lines().firstOrNull().orEmpty().take(180),
                        data = mapOf("type" to type, "payload" to payload)
                    )
                }

                val telegram = transaction {
                    val pref = NotificationPreferencesTable.selectAll()
                        .where { NotificationPreferencesTable.userId eq userId }
                        .singleOrNull()
                    val enabled = pref?.get(NotificationPreferencesTable.telegramEnabled) ?: false
                    val chatId = pref?.get(NotificationPreferencesTable.telegramChatId)
                    Pair(enabled && !chatId.isNullOrBlank(), chatId)
                }
                if (telegram.first) {
                    telegram.second?.let { chatId ->
                        TelegramService.sendMessageToChat(chatId, "<b>$title</b>\n\n${escapeTelegram(message)}")
                    }
                }
            }
        }
    }

    suspend fun notifyTelegramUser(userId: UUID, text: String, preferenceKey: String = ""): Boolean {
        val config = transaction {
            val pref = NotificationPreferencesTable.selectAll()
                .where { NotificationPreferencesTable.userId eq userId }
                .firstOrNull()
            val enabled = pref?.get(NotificationPreferencesTable.telegramEnabled) ?: false
            val chatId = pref?.get(NotificationPreferencesTable.telegramChatId)
            enabled to chatId
        }
        if (!config.first || config.second.isNullOrBlank()) return false
        if (preferenceKey.isNotBlank() && !preferenceEnabled(userId, preferenceKey)) return false
        return TelegramService.sendMessageToChat(config.second!!, text)
    }

    suspend fun broadcastTelegram(text: String): Boolean {
        val chats = transaction { TelegramLinksTable.selectAll().map { it[TelegramLinksTable.chatId] }.distinct() }
        var ok = false
        chats.forEach { ok = TelegramService.sendMessageToChat(it, escapeTelegram(text).replace("&lt;b&gt;", "<b>").replace("&lt;/b&gt;", "</b>")) || ok }
        return ok
    }

    fun linkedChatId(userId: UUID): String? = transaction {
        TelegramLinksTable.selectAll()
            .where { TelegramLinksTable.userId eq userId }
            .singleOrNull()?.get(TelegramLinksTable.chatId)
    }

    fun escapeTelegram(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
