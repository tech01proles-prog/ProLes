package com.proles.server.config

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Сервис отправки сообщений в Telegram через Bot API.
 * Использует встроенный java.net.http.HttpClient (не требует доп. зависимостей).
 */
object TelegramService {
    private var botToken: String? = null
    private var chatId: String? = null
    private var enabled = false

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(30))
        .build()

    fun init(token: String?, chatId: String?) {
        if (token.isNullOrBlank() || chatId.isNullOrBlank()) {
            println("⚠️ Telegram: токен или chat_id не заданы. Уведомления в Telegram отключены.")
            return
        }
        botToken = token
        this.chatId = chatId
        enabled = true
        println("✅ Telegram Bot инициализирован (chat_id: $chatId)")
    }

    /**
     * Отправка текстового сообщения с HTML-разметкой.
     * Работает асинхронно, не блокирует основной поток.
     */
    suspend fun sendMessage(text: String, parseMode: String = "HTML"): Boolean {
        if (!enabled) {
            println("⚠️ Telegram: сервис не инициализирован, сообщение не отправлено")
            return false
        }

        return try {
            val url = "https://api.telegram.org/bot$botToken/sendMessage"

            // Формируем JSON вручную, чтобы не зависеть от kotlinx.serialization
            val escapedText = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                // Возвращаем разрешённые HTML-теги обратно
                .replace("&lt;b&gt;", "<b>").replace("&lt;/b&gt;", "</b>")
                .replace("&lt;i&gt;", "<i>").replace("&lt;/i&gt;", "</i>")
                .replace("&lt;u&gt;", "<u>").replace("&lt;/u&gt;", "</u>")
                .replace("&lt;a ", "<a ").replace("&lt;/a&gt;", "</a>")
                .replace("&lt;code&gt;", "<code>").replace("&lt;/code&gt;", "</code>")
                .replace("&lt;pre&gt;", "<pre>").replace("&lt;/pre&gt;", "</pre>")

            val jsonBody = """
                {
                    "chat_id": "$chatId",
                    "text": "$escapedText",
                    "parse_mode": "$parseMode",
                    "disable_web_page_preview": true
                }
            """.trimIndent()

            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(java.time.Duration.ofSeconds(30))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                println("✅ Telegram: сообщение отправлено в чат $chatId")
                true
            } else {
                println("❌ Telegram: ошибка ${response.statusCode()} - ${response.body().take(200)}")
                false
            }
        } catch (e: Exception) {
            println("❌ Telegram: исключение ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Отправка файла (документа) в Telegram.
     * Работает асинхронно, не блокирует основной поток.
     */
    suspend fun sendFile(fileBytes: ByteArray, fileName: String, caption: String = ""): Boolean {
        if (!enabled) {
            println("⚠️ Telegram: сервис не инициализирован, файл не отправлен")
            return false
        }

        return try {
            val url = "https://api.telegram.org/bot$botToken/sendDocument"
            
            // Определяем MIME-тип по расширению
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = when (ext) {
                "pdf" -> "application/pdf"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "doc" -> "application/msword"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xls" -> "application/vnd.ms-excel"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                else -> "application/octet-stream"
            }

            // Формируем multipart/form-data запрос вручную
            val boundary = "----WebKitFormBoundary${System.currentTimeMillis()}"
            val crlf = "\r\n"
            
            // Для caption используем тот же подход экранирования, что и в sendMessage
            val escapedCaption = caption
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("&lt;b&gt;", "<b>").replace("&lt;/b&gt;", "</b>")
                .replace("&lt;i&gt;", "<i>").replace("&lt;/i&gt;", "</i>")
                .replace("&lt;u&gt;", "<u>").replace("&lt;/u&gt;", "</u>")
                .replace("&lt;a ", "<a ").replace("&lt;/a&gt;", "</a>")
                .replace("&lt;code&gt;", "<code>").replace("&lt;/code&gt;", "</code>")
                .replace("&lt;pre&gt;", "<pre>").replace("&lt;/pre&gt;", "</pre>")
            
            val parseMode = "HTML"
            
            // Собираем все части multipart запроса
            val fullBody = ByteArrayOutputStream()
            
            // Часть для chat_id
            fullBody.write("--$boundary$crlf".toByteArray(Charsets.UTF_8))
            fullBody.write("Content-Disposition: form-data; name=\"chat_id\"$crlf$crlf".toByteArray(Charsets.UTF_8))
            fullBody.write("$chatId$crlf".toByteArray(Charsets.UTF_8))
            
            // Часть для caption
            fullBody.write("--$boundary$crlf".toByteArray(Charsets.UTF_8))
            fullBody.write("Content-Disposition: form-data; name=\"caption\"$crlf$crlf".toByteArray(Charsets.UTF_8))
            fullBody.write("$escapedCaption$crlf".toByteArray(Charsets.UTF_8))
            
            // Часть для parse_mode
            fullBody.write("--$boundary$crlf".toByteArray(Charsets.UTF_8))
            fullBody.write("Content-Disposition: form-data; name=\"parse_mode\"$crlf$crlf".toByteArray(Charsets.UTF_8))
            fullBody.write("$parseMode$crlf".toByteArray(Charsets.UTF_8))
            
            // Часть для файла
            fullBody.write("--$boundary$crlf".toByteArray(Charsets.UTF_8))
            fullBody.write("Content-Disposition: form-data; name=\"document\"; filename=\"$fileName\"$crlf".toByteArray(Charsets.UTF_8))
            fullBody.write("Content-Type: $mimeType$crlf".toByteArray(Charsets.UTF_8))
            fullBody.write("Content-Transfer-Encoding: binary$crlf$crlf".toByteArray(Charsets.UTF_8))
            fullBody.write(fileBytes)
            
            // Закрывающий boundary
            fullBody.write("$crlf--$boundary--$crlf".toByteArray(Charsets.UTF_8))
            
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(fullBody.toByteArray()))
                .timeout(java.time.Duration.ofSeconds(60))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                println("✅ Telegram: файл $fileName отправлен в чат $chatId")
                true
            } else {
                println("❌ Telegram: ошибка ${response.statusCode()} - ${response.body().take(200)}")
                false
            }
        } catch (e: Exception) {
            println("❌ Telegram: исключение ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Формирует красивое HTML-сообщение о командировке.
     */
    fun formatTripMessage(
        senderName: String,
        tripType: String,
        projectName: String,
        city: String,
        date: String,
        participants: List<String>,
        transport: String,
        notes: String
    ): String {
        val typeEmoji = when (tripType) {
            "DEPARTURE" -> "🚆"
            "TRANSFER" -> "🔄"
            "COMPLETION" -> "✅"
            else -> "📋"
        }
        val typeLabel = when (tripType) {
            "DEPARTURE" -> "НОВАЯ КОМАНДИРОВКА"
            "TRANSFER" -> "ПЕРЕЕЗД"
            "COMPLETION" -> "ЗАВЕРШЕНИЕ КОМАНДИРОВКИ"
            else -> "КОМАНДИРОВКА"
        }

        return buildString {
            appendLine("<b>$typeEmoji $typeLabel</b>")
            appendLine("")
            if (participants.isNotEmpty()) {
                appendLine("<b>👥:</b>")
                participants.forEach { appendLine(" $it") }
            }
            else {
                appendLine("<b>👤 Сотрудник:</b> $senderName")
            }
            appendLine("<b>📅:</b> $date")
            appendLine("<b>📁:</b> <i>$projectName</i>")

            if (city.isNotBlank()) {
                appendLine("<b>📍:</b> $city")
            }

            if (transport.isNotBlank()) {
                appendLine("<b>🚗:</b> $transport")
            }

            if (notes.isNotBlank()) {
                appendLine("")
                appendLine("<i>📝 $notes</i>")
            }
        }.trim()
    }
}