package com.proles.server.config

import com.proles.server.data.NotificationPreferencesTable
import com.proles.server.data.TelegramLinksTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
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
    private var systemChatId: String? = null

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .apply {
            telegramProxySelector()?.let { proxy(it) }
        }
        .build()

    private fun telegramProxySelector(): ProxySelector? {
        val raw = sequenceOf(
            System.getenv("TELEGRAM_HTTP_PROXY"),
            System.getenv("HTTPS_PROXY"),
            System.getenv("https_proxy"),
            System.getenv("HTTP_PROXY"),
            System.getenv("http_proxy")
        )
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: return null

        return runCatching {
            val normalized = if (raw.contains("://")) raw else "http://$raw"
            val uri = URI.create(normalized)
            val host = uri.host ?: error("proxy host is missing")
            val port = if (uri.port > 0) uri.port else 8080
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))

            object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> = listOf(proxy)
                override fun connectFailed(
                    uri: URI?,
                    sa: SocketAddress?,
                    ioe: java.io.IOException?
                ) {
                    println("⚠️ Telegram proxy connection failed: ${ioe?.message}")
                }
            }
        }.getOrElse {
            println("⚠️ Telegram proxy config is invalid: ${it.message}")
            null
        }
    }

    private val apiBaseUrl =
        System.getenv("TELEGRAM_API_BASE_URL")?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: "https://api.telegram.org"

    private fun maskedBotToken(): String =
        botToken?.let { token ->
            if (token.length <= 8) "***" else "${token.take(4)}...${token.takeLast(4)}"
        } ?: "<empty>"

    private fun safeRequestUri(request: HttpRequest): String =
        request.uri().toString().replace(Regex("/bot[^/]+/"), "/bot***/")

    private suspend fun sendTelegramRequest(request: HttpRequest): HttpResponse<String> {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                println("📡 [Telegram HTTP] attempt ${attempt + 1}/3 ${request.method()} ${safeRequestUri(request)}")
                val startedAt = System.currentTimeMillis()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                println("📥 [Telegram HTTP] ${response.statusCode()} in ${System.currentTimeMillis() - startedAt} ms for ${safeRequestUri(request)}")
                return response
            } catch (e: Exception) {
                lastError = e
                println("⚠️ Telegram HTTP attempt ${attempt + 1}/3 failed: ${e::class.simpleName}: ${e.message}")
                if (attempt < 2) delay(1000L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Telegram HTTP request failed")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(token: String?, legacyChatId: String? = null, chatId: String? = legacyChatId) {
        println("🔎 [Telegram] init: tokenPresent=${!token.isNullOrBlank()}, apiBaseUrl=$apiBaseUrl, proxyConfigured=${telegramProxySelector() != null}")
        if (token.isNullOrBlank()) {
            println("⚠️ [Telegram] TELEGRAM_BOT_TOKEN не задан. Telegram отключен.")
            return
        }
        botToken = token
        systemChatId = chatId?.trim()?.takeIf { it.isNotBlank() }
        enabled = true
        println("✅ [Telegram] Bot инициализирован, token=${maskedBotToken()}, systemChatConfigured=${systemChatId != null}, polling запускается")
        startPolling()
        if (!chatId.isNullOrBlank()) println("ℹ️ [Telegram] TELEGRAM_CHAT_ID используется только для обратной совместимости")
    }

    fun configuredSystemChatId(): String? = systemChatId

    suspend fun sendSystemMessage(text: String, parseMode: String = "HTML"): Boolean {
        val chatId = systemChatId
        if (chatId.isNullOrBlank()) {
            println("⚠️ [Telegram] system notification skipped: TELEGRAM_CHAT_ID is empty")
            return false
        }
        println("📢 [Telegram] system notification: chatId=$chatId, textLength=${text.length}")
        return sendMessageToChat(chatId, text, parseMode)
    }

    suspend fun sendMessage(text: String, parseMode: String = "HTML"): Boolean {
        println("📨 [Telegram] sendMessage/broadcast вызван: textLength=${text.length}, parseMode=$parseMode")
        val result = NotificationService.broadcastTelegram(text)
        println("📨 [Telegram] sendMessage/broadcast завершён: success=$result")
        return result
    }

    suspend fun sendMessageToChat(chatId: String, text: String, parseMode: String = "HTML"): Boolean {
        if (!enabled || botToken.isNullOrBlank()) {
            println("⚠️ [Telegram] sendMessageToChat пропущен: enabled=$enabled, tokenPresent=${!botToken.isNullOrBlank()}, chatId=$chatId")
            return false
        }
        println("➡️ [Telegram] sendMessageToChat: chatId=$chatId, textLength=${text.length}")
        return try {
            val body = buildJsonObject {
                put("chat_id", chatId)
                put("text", text)
                put("parse_mode", parseMode)
                put("disable_web_page_preview", true)
            }.toString()
            val req = HttpRequest.newBuilder()
                .uri(URI.create("${apiBaseUrl}/bot${botToken}/sendMessage"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build()
            sendTelegramRequest(req).let { response ->
                if (response.statusCode() != 200) println("❌ [Telegram] sendMessage HTTP ${response.statusCode()}: ${response.body()}")
                val success = response.statusCode() == 200
                println("⬅️ [Telegram] sendMessageToChat result: chatId=$chatId, success=$success")
                success
            }
        } catch (e: Exception) {
            println("❌ [Telegram] sendMessageToChat exception: ${e::class.simpleName}: ${e.message}")
            false
        }
    }

    suspend fun sendFile(fileBytes: ByteArray, fileName: String, caption: String = ""): Boolean {
        if (!enabled || botToken.isNullOrBlank()) {
            println("⚠️ [Telegram] sendFile пропущен: enabled=$enabled, tokenPresent=${!botToken.isNullOrBlank()}, file=$fileName")
            return false
        }
        val linkedChats = transaction { TelegramLinksTable.selectAll().map { it[TelegramLinksTable.chatId] } }
        val chats = linkedChats.asSequence()
            .plus(systemChatId?.let(::sequenceOf) ?: emptySequence())
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        println("📎 [Telegram] sendFile: file=$fileName, bytes=${fileBytes.size}, chats=${chats.size}, systemChatIncluded=${systemChatId != null}")
        var ok = false
        chats.forEach { chatId -> ok = sendDocumentToChat(chatId, fileBytes, fileName, caption) || ok }
        println("📎 [Telegram] sendFile завершён: file=$fileName, success=$ok")
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
            val req = HttpRequest.newBuilder().uri(URI.create("${apiBaseUrl}/bot${botToken}/sendDocument"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).timeout(Duration.ofSeconds(60)).build()
            sendTelegramRequest(req).let { response -> if (response.statusCode() != 200) println("❌ Telegram sendDocument HTTP ${response.statusCode()}: ${response.body()}"); response.statusCode() == 200 }
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
        println("🔄 [Telegram] startPolling() вызван")
        scope.launch {
            var offset = 0L
            while (isActive && enabled) {
                try {
                    println("🔄 [Telegram polling] getUpdates offset=$offset")
                    val response = getUpdates(offset)
                    val updates = response?.jsonObject?.get("result")?.jsonArray.orEmpty()
                    println("🔄 [Telegram polling] updates=${updates.size}")
                    for (item in updates) {
                        val obj = item.jsonObject
                        offset = (obj["update_id"]?.jsonPrimitive?.longOrNull ?: offset) + 1
                        val message = obj["message"]?.jsonObject ?: continue
                        val chat = message["chat"]?.jsonObject ?: continue
                        val chatId = chat["id"]?.jsonPrimitive?.contentOrNull ?: continue
                        val username = chat["username"]?.jsonPrimitive?.contentOrNull ?: ""
                        val text = message["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: continue
                        println("📨 [Telegram polling] message received: chatId=$chatId, username=$username, textLength=${text.length}")
                        if (!linkByCode(text, chatId, username)) {
                            if (text == "/start") {
                                sendMessageToChat(chatId, "<b>Proles Sys</b>\nЧтобы привязать аккаунт, включите Telegram в настройках системы и отправьте сюда выданный код.")
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️ [Telegram polling] ${e::class.simpleName}: ${e.message}")
                    delay(3000)
                }
            }
        }
    }

    private suspend fun getUpdates(offset: Long): JsonObject? {
        val url = "${apiBaseUrl}/bot$botToken/getUpdates?timeout=25&allowed_updates=%5B%22message%22%5D&offset=$offset"
        val req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(35)).GET().build()
        val response = sendTelegramRequest(req)
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
            val updatedRows = NotificationPreferencesTable.update({ NotificationPreferencesTable.userId eq userId }) {
                it[NotificationPreferencesTable.telegramChatId] = chatId
                it[NotificationPreferencesTable.telegramUsername] = username
                it[NotificationPreferencesTable.telegramLinkedAt] = System.currentTimeMillis()
                it[NotificationPreferencesTable.telegramLinkCode] = null
            }
            if (updatedRows == 0) {
                println("⚠️ [Telegram] notification_preferences missing for userId=$userId; creating it during Telegram link")
                NotificationPreferencesTable.insert {
                    it[id] = UUID.randomUUID()
                    it[NotificationPreferencesTable.userId] = userId
                    it[NotificationPreferencesTable.telegramEnabled] = true
                    it[NotificationPreferencesTable.telegramChatId] = chatId
                    it[NotificationPreferencesTable.telegramUsername] = username
                    it[NotificationPreferencesTable.telegramLinkedAt] = System.currentTimeMillis()
                }
            }
        }
        println("✅ [Telegram] linkByCode success: userId=$userId, chatId=$chatId, username=$username")
        scope.launch { sendMessageToChat(chatId, "✅ Telegram успешно привязан к вашему аккаунту Proles Sys. Дальше уведомления будут приходить сюда согласно настройкам.") }
        return true
    }
}
