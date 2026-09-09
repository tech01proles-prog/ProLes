package com.proles.server.config

import com.proles.server.data.NotificationPreferencesTable
import com.proles.server.data.TelegramLinksTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Telegram Bot API: отправка и одноразовая привязка пользователя по числовому коду. */
object TelegramService {
    private var botToken: String? = null
    private var enabled = false
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(token: String?, legacyChatId: String? = null, chatId: String? = legacyChatId) {
        if (token.isNullOrBlank()) {
            println("⚠️ Telegram: TELEGRAM_BOT_TOKEN не задан. Telegram отключен.")
            return
        }
        botToken = token
        enabled = true
        println("✅ Telegram Bot инициализирован")
        startPolling()
        if (!chatId.isNullOrBlank()) println("ℹ️ TELEGRAM_CHAT_ID используется только для обратной совместимости")
    }

    suspend fun sendMessage(text: String, parseMode: String = "HTML"): Boolean {
        return NotificationService.broadcastTelegram(text)
    }

    suspend fun sendMessageToChat(chatId: String, text: String, parseMode: String = "HTML"): Boolean {
        if (!enabled || botToken.isNullOrBlank()) return false
        return try {
            val body = buildJsonObject {
                put("chat_id", chatId)
                put("text", text)
                put("parse_mode", parseMode)
                put("disable_web_page_preview", true)
            }.toString()
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot${botToken}/sendMessage"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build()
            httpClient.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
        } catch (e: Exception) {
            println("❌ Telegram sendMessage: ${e.message}")
            false
        }
    }

    suspend fun sendFile(fileBytes: ByteArray, fileName: String, caption: String = ""): Boolean {
        if (!enabled || botToken.isNullOrBlank()) return false
        val chats = transaction { TelegramLinksTable.selectAll().map { it[TelegramLinksTable.chatId] }.distinct() }
        var ok = false
        chats.forEach { chatId -> ok = sendDocumentToChat(chatId, fileBytes, fileName, caption) || ok }
        return ok
    }

    private suspend fun sendDocumentToChat(chatId: String, bytes: ByteArray, fileName: String, caption: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val boundary = "----ProlesTelegram${System.currentTimeMillis()}"
            val crlf = "\r\n"
            val head = StringBuilder().apply {
                append("--$boundary$crlf")
                append("Content-Disposition: form-data; name=\"chat_id\"$crlf$crlf")
                append(chatId).append(crlf)
                append("--$boundary$crlf")
                append("Content-Disposition: form-data; name=\"caption\"$crlf$crlf")
                append(caption).append(crlf)
                append("--$boundary$crlf")
                append("Content-Disposition: form-data; name=\"document\"; filename=\"$fileName\"$crlf")
                append("Content-Type: application/octet-stream$crlf$crlf")
            }.toString().toByteArray()
            val tail = "$crlf--$boundary--$crlf".toByteArray()
            val body = ByteArrayOutputStream(head.size + bytes.size + tail.size).apply { write(head); write(bytes); write(tail) }.toByteArray()
            val req = HttpRequest.newBuilder().uri(URI.create("https://api.telegram.org/bot${botToken}/sendDocument"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).timeout(Duration.ofSeconds(60)).build()
            httpClient.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
        } catch (e: Exception) { println("❌ Telegram sendDocument: ${e.message}"); false }
    }

    fun formatTripMessage(
        senderName: String,
        tripType: String,
        projectName: String,
        city: String,
        date: String,
        participants: List<String>,
        transport: String,
        notes: String
    ): String = buildString {
        val emoji = when (tripType) { "DEPARTURE" -> "🚆"; "TRANSFER" -> "🔄"; "COMPLETION" -> "✅"; else -> "📋" }
        appendLine("<b>$emoji КОМАНДИРОВКА</b>")
        appendLine("<b>👤 Сотрудник:</b> ${NotificationService.escapeTelegram(senderName)}")
        if (projectName.isNotBlank()) appendLine("<b>📁 Проект:</b> ${NotificationService.escapeTelegram(projectName)}")
        if (city.isNotBlank()) appendLine("<b>📍:</b> ${NotificationService.escapeTelegram(city)}")
        appendLine("<b>📅:</b> $date")
        if (transport.isNotBlank()) appendLine("<b>🚗:</b> ${NotificationService.escapeTelegram(transport)}")
        if (participants.isNotEmpty()) appendLine("<b>👥:</b> ${participants.joinToString(", ") { NotificationService.escapeTelegram(it) }}")
        if (notes.isNotBlank()) appendLine("<i>📝 ${NotificationService.escapeTelegram(notes)}</i>")
    }.trim()

    private fun startPolling() {
        scope.launch {
            var offset = 0L
            while (isActive && enabled) {
                try {
                    val response = getUpdates(offset)
                    val updates = response?.jsonObject?.get("result")?.jsonArray.orEmpty()
                    for (item in updates) {
                        val obj = item.jsonObject
                        offset = (obj["update_id"]?.jsonPrimitive?.longOrNull ?: offset) + 1
                        val message = obj["message"]?.jsonObject ?: continue
                        val chat = message["chat"]?.jsonObject ?: continue
                        val chatId = chat["id"]?.jsonPrimitive?.contentOrNull ?: continue
                        val username = chat["username"]?.jsonPrimitive?.contentOrNull ?: ""
                        val text = message["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: continue
                        if (!linkByCode(text, chatId, username)) {
                            if (text == "/start") {
                                sendMessageToChat(chatId, "<b>Proles Sys</b>\nЧтобы привязать аккаунт, включите Telegram в настройках системы и отправьте сюда выданный код.")
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️ Telegram polling: ${e.message}")
                    delay(3000)
                }
            }
        }
    }

    private fun getUpdates(offset: Long): JsonObject? {
        val url = "https://api.telegram.org/bot$botToken/getUpdates?timeout=25&allowed_updates=%5B%22message%22%5D&offset=$offset"
        val req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(35)).GET().build()
        val response = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        return Json.parseToJsonElement(response.body()).jsonObject
    }

    private fun linkByCode(code: String, chatId: String, username: String): Boolean {
        if (!code.matches(Regex("\\d{6,12}"))) return false
        val userId = transaction {
            NotificationPreferencesTable.selectAll()
                .where { NotificationPreferencesTable.telegramLinkCode eq code }
                .singleOrNull()?.get(NotificationPreferencesTable.userId)?.value
        } ?: return false

        transaction {
            TelegramLinksTable.deleteWhere { TelegramLinksTable.userId eq userId }
            TelegramLinksTable.deleteWhere { TelegramLinksTable.chatId eq chatId }
            TelegramLinksTable.insert {
                it[id] = UUID.randomUUID()
                it[TelegramLinksTable.userId] = userId
                it[TelegramLinksTable.chatId] = chatId
                it[TelegramLinksTable.username] = username
                it[TelegramLinksTable.linkedAt] = System.currentTimeMillis()
            }
            NotificationPreferencesTable.update({ NotificationPreferencesTable.userId eq userId }) {
                it[NotificationPreferencesTable.telegramChatId] = chatId
                it[NotificationPreferencesTable.telegramUsername] = username
                it[NotificationPreferencesTable.telegramLinkedAt] = System.currentTimeMillis()
                it[NotificationPreferencesTable.telegramLinkCode] = null
            }
        }
        scope.launch { sendMessageToChat(chatId, "✅ Telegram успешно привязан к вашему аккаунту Proles Sys. Дальше уведомления будут приходить сюда согласно настройкам.") }
        return true
    }
}
