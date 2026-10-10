package com.example.prolestimesheet.network

import android.content.Context
import android.provider.OpenableColumns
import android.util.Log
import com.example.prolestimesheet.model.ChatAttachment
import com.example.prolestimesheet.model.ChatConversation
import com.example.prolestimesheet.model.ChatMessage
import com.example.prolestimesheet.model.ChatMessagesPage
import com.example.prolestimesheet.model.ChatReadRequest
import com.example.prolestimesheet.model.ChatSendMessageRequest
import com.example.prolestimesheet.model.ChatUpdateMessageRequest
import com.example.prolestimesheet.model.ChatUser
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class ChatDownloadedFile(
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String
)

object ChatApi {
    private const val MAX_FILE_BYTES = 100L * 1024L * 1024L

    suspend fun users(): Result<List<ChatUser>> = request {
        ApiClient.client.get("${ApiClient.BASE_URL}/chat/users") { withChatSession() }.body()
    }

    suspend fun conversations(): Result<List<ChatConversation>> = request {
        ApiClient.client.get("${ApiClient.BASE_URL}/chat/conversations") { withChatSession() }.body()
    }

    suspend fun messages(conversationId: String, limit: Int = 50, cursor: String? = null): Result<ChatMessagesPage> = request {
        ApiClient.client.get("${ApiClient.BASE_URL}/chat/conversations/$conversationId/messages") {
            withChatSession()
            parameter("limit", limit)
            cursor?.let { parameter("cursor", it) }
        }.body()
    }

    suspend fun createDirectConversation(userId: String): Result<ChatConversation> = request {
        ApiClient.client.post("${ApiClient.BASE_URL}/chat/conversations/direct") {
            withChatSession()
            setBody(mapOf("userId" to userId))
        }.body()
    }

    suspend fun sendMessage(conversationId: String, text: String): Result<ChatMessage> = request {
        ApiClient.client.post("${ApiClient.BASE_URL}/chat/conversations/$conversationId/messages") {
            withChatSession()
            setBody(ChatSendMessageRequest(text.trim(), createClientMessageId()))
        }.body()
    }

    suspend fun uploadAttachment(context: Context, conversationId: String, uri: android.net.Uri, text: String = ""): Result<ChatMessage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Не удалось прочитать файл")
                if (bytes.size.toLong() > MAX_FILE_BYTES) error("Файл превышает 100 MiB")
                val fileName = queryDisplayName(context, uri).ifBlank { "attachment" }
                val mimeType = resolver.getType(uri) ?: "application/octet-stream"
                val response = ApiClient.client.submitFormWithBinaryData(
                    url = "${ApiClient.BASE_URL}/chat/conversations/$conversationId/attachments",
                    formData = formData {
                        append("text", text.trim())
                        append("clientMessageId", createClientMessageId())
                        append(
                            "file",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"${fileName.replace("\"", "_")}\"")
                                append(HttpHeaders.ContentType, mimeType)
                            }
                        )
                    }
                ) { withChatSession() }
                if (response.status != HttpStatusCode.Created) error("Ошибка загрузки файла: ${response.status}")
                response.body<com.example.prolestimesheet.model.ChatAttachmentUploadResponse>().message
            }.onFailure { error -> Log.e("ChatApi", "attachment upload failed", error) }
        }

    suspend fun updateMessage(conversationId: String, messageId: String, text: String): Result<ChatMessage> = request {
        ApiClient.client.put("${ApiClient.BASE_URL}/chat/conversations/$conversationId/messages/$messageId") {
            withChatSession()
            setBody(ChatUpdateMessageRequest(text.trim()))
        }.body()
    }

    suspend fun deleteMessage(conversationId: String, messageId: String): Result<ChatMessage> = request {
        ApiClient.client.delete("${ApiClient.BASE_URL}/chat/conversations/$conversationId/messages/$messageId") { withChatSession() }.body()
    }

    suspend fun markRead(conversationId: String, messageId: String? = null): Result<Unit> = request {
        val response = ApiClient.client.post("${ApiClient.BASE_URL}/chat/conversations/$conversationId/read") {
            withChatSession()
            setBody(ChatReadRequest(messageId))
        }
        if (response.status != HttpStatusCode.OK) error("Ошибка отметки прочитанным: ${response.status}")
        Unit
    }

    suspend fun downloadAttachment(attachment: ChatAttachment): Result<ChatDownloadedFile> = withContext(Dispatchers.IO) {
        runCatching {
            val url = if (attachment.downloadUrl.startsWith("/api/v1")) {
                "${ApiClient.BASE_URL}" + attachment.downloadUrl.removePrefix("/api/v1")
            } else attachment.downloadUrl
            val response = ApiClient.client.get(url) { withChatSession() }
            if (response.status != HttpStatusCode.OK) error("Ошибка скачивания: ${response.status}")
            ChatDownloadedFile(response.body<ByteArray>(), attachment.originalName, attachment.mimeType)
        }
    }

    private fun queryDisplayName(context: Context, uri: android.net.Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty()
            }
        }
        return "attachment"
    }

    private fun createClientMessageId(): String = "android-${System.currentTimeMillis()}-${UUID.randomUUID()}"

    private fun HttpRequestBuilder.withChatSession() {
        ApiClient.authToken?.takeIf { it.isNotBlank() }?.let { header("X-Session-Token", it) }
    }

    private suspend inline fun <T> request(crossinline block: suspend () -> T): Result<T> =
        runCatching { block() }.onFailure { Log.e("ChatApi", "Chat request failed", it) }
}
