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
        println("🔔 [Notification] notifyUsers: type=$type, title=$title, targets=${targetUserIds.size}, uniqueTargets=${uniqueTargets.size}, preferenceKey=$preferenceKey")
        val enabledTargets = if (preferenceKey.isBlank()) uniqueTargets else {
            uniqueTargets.filter { preferenceEnabled(it, preferenceKey) }
        }
        println("🔔 [Notification] enabledTargets=${enabledTargets.size}/${uniqueTargets.size}")

        if (enabledTargets.isNotEmpty()) {
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
        } else {
            println("🔔 [Notification] no per-user targets; system Telegram delivery will still be attempted")
        }

        println("🔔 [Notification] async personal delivery started")
        deliveryScope.launch {
            enabledTargets.forEach { userId ->
                println("🔔 [Notification] personal delivery: type=$type, userId=$userId")

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
                    println("🔔 [Notification] Telegram disabled for event type=$type")
                    return@forEach
                }

                val telegram = telegramConfig(userId, preferenceKey)
                println("🔎 [Notification] Telegram personal config: userId=$userId, enabled=${telegram.enabled}, chatIdPresent=${!telegram.chatId.isNullOrBlank()}")
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
        val config = transaction {
            val pref = NotificationPreferencesTable.selectAll()
                .where { NotificationPreferencesTable.userId eq userId }
                .firstOrNull()
            val linkedChatId = TelegramLinksTable.selectAll()
                .where { TelegramLinksTable.userId eq userId }
                .firstOrNull()?.get(TelegramLinksTable.chatId)
            val enabled = if (pref == null) linkedChatId != null else pref[NotificationPreferencesTable.telegramEnabled]
            val chatId = pref?.get(NotificationPreferencesTable.telegramChatId)?.takeIf { !it.isNullOrBlank() } ?: linkedChatId
            println("🔎 [Notification] user Telegram config: userId=$userId, prefExists=${pref != null}, telegramEnabled=$enabled, chatIdPresent=${!chatId.isNullOrBlank()}, source=${if (!pref?.get(NotificationPreferencesTable.telegramChatId).isNullOrBlank()) "preferences" else if (linkedChatId != null) "telegram_links" else "none"}, preferenceKey=$preferenceKey")
            enabled to chatId
        }
        if (!config.first || config.second.isNullOrBlank()) {
            println("⚠️ [Notification] user Telegram skipped: disabled or chatId missing, userId=$userId")
            return false
        }
        if (preferenceKey.isNotBlank() && !preferenceEnabled(userId, preferenceKey)) {
            println("⚠️ [Notification] user Telegram skipped by preference: userId=$userId, preferenceKey=$preferenceKey")
            return false
        }
        println("📨 [Notification] calling TelegramService for userId=$userId, chatId=${config.second}")
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
