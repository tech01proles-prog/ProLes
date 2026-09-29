package com.proles.server.routes

import com.proles.server.config.SessionManager
import com.proles.server.data.*
import com.proles.server.model.*
import com.proles.server.services.ChatCrypto
import com.proles.server.services.ChatRealtimeHub
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import io.ktor.utils.io.jvm.javaio.toInputStream
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

private data class ChatSession(val userId: UUID)
private const val MAX_CHAT_ATTACHMENT_BYTES = 100L * 1024L * 1024L
private val chatUploadDirectory = File(System.getenv("CHAT_UPLOAD_DIR") ?: "uploads/chat").apply { mkdirs() }

fun Route.chatRoutes() {
    route("/api/v1/chat") {
        webSocket("/ws") {
            val token = call.request.queryParameters["token"]
            val session = SessionManager.validate(token)

            if (session == null) {
                close(
                    CloseReason(
                        CloseReason.Codes.VIOLATED_POLICY,
                        "Unauthorized"
                    )
                )
                return@webSocket
            }

            ChatRealtimeHub.register(session.userId, this)

            try {
                ChatRealtimeHub.send(
                    session.userId,
                    ChatRealtimeEvent(
                        type = "CONNECTED",
                        userId = session.userId.toString()
                    )
                )

                incoming.consumeEach { frame ->
                    if (frame is Frame.Close) {
                        return@consumeEach
                    }
                }
            } finally {
                ChatRealtimeHub.unregister(session.userId, this)
            }
        }

        get("/users") {
            val session = call.requireChatSession() ?: return@get
            val users = transaction {
                UsersTable.selectAll().where { UsersTable.id neq session.userId }.orderBy(UsersTable.name to SortOrder.ASC).map {
                    ChatUserDto(it[UsersTable.id].value.toString(), it[UsersTable.name], it[UsersTable.role], it[UsersTable.position].orEmpty())
                }
            }
            call.respond(users)
        }

        get("/conversations") {
            val session = call.requireChatSession() ?: return@get
            call.respond(transaction { conversationIdsFor(session.userId).mapNotNull { conversationDto(it, session.userId) } })
        }

        post("/conversations/direct") {
            val session = call.requireChatSession() ?: return@post
            val body = runCatching { call.receive<CreateDirectConversationRequest>() }.getOrElse { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный JSON")) }
            val otherId = runCatching { UUID.fromString(body.userId) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный userId"))
            if (otherId == session.userId) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Нельзя создать диалог с собой"))

            val conversation = transaction {
                if (UsersTable.selectAll().where { UsersTable.id eq otherId }.limit(1).none()) return@transaction null
                val directKey = listOf(session.userId.toString(), otherId.toString()).sorted().joinToString(":")
                val existing = ChatConversationsTable.selectAll().where { ChatConversationsTable.directKey eq directKey }.limit(1).singleOrNull()?.get(ChatConversationsTable.id)?.value
                val id = existing ?: UUID.randomUUID().also { conversationId ->
                    val now = System.currentTimeMillis()
                    ChatConversationsTable.insert {
                        it[id] = conversationId
                        it[conversationType] = "DIRECT"
                        it[ChatConversationsTable.directKey] = directKey
                        it[createdBy] = session.userId
                        it[createdAt] = now
                        it[updatedAt] = now
                        it[lastMessageId] = null
                    }
                    listOf(session.userId, otherId).forEach { memberId ->
                        ChatConversationMembersTable.insert {
                            it[ChatConversationMembersTable.conversationId] = conversationId
                            it[ChatConversationMembersTable.userId] = memberId
                            it[joinedAt] = now
                            it[lastReadMessageId] = null
                            it[lastReadAt] = null
                            it[isArchived] = false
                        }
                    }
                }
                conversationDto(id, session.userId)
            } ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Пользователь не найден"))

            call.respond(HttpStatusCode.Created, conversation)
        }

        get("/conversations/{conversationId}/messages") {
            val session = call.requireChatSession() ?: return@get
            val conversationId = call.parameters["conversationId"].uuidOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный conversationId"))
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
            val cursor = call.request.queryParameters["cursor"]?.toLongOrNull()
            val page = transaction {
                if (!isConversationMember(conversationId, session.userId)) return@transaction null
                var condition: Op<Boolean> = ChatMessagesTable.conversationId eq conversationId
                if (cursor != null) condition = condition and (ChatMessagesTable.createdAt less cursor)
                val rows = ChatMessagesTable.selectAll().where { condition }.orderBy(ChatMessagesTable.createdAt to SortOrder.DESC).limit(limit + 1).toList()
                val hasMore = rows.size > limit
                val visible = rows.take(limit)
                ChatMessagesPageDto(visible.map(::messageDto), if (hasMore) visible.lastOrNull()?.get(ChatMessagesTable.createdAt)?.toString() else null)
            } ?: return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Нет доступа к диалогу"))
            call.respond(page)
        }

        post("/conversations/{conversationId}/messages") {
            val session = call.requireChatSession() ?: return@post
            val conversationId = call.parameters["conversationId"].uuidOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный conversationId"))
            val body = runCatching { call.receive<SendChatMessageRequest>() }.getOrElse { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный JSON")) }
            val text = body.text.trim()
            if (text.isBlank() || text.length > 20_000) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Сообщение должно содержать от 1 до 20000 символов"))
            val clientMessageId = body.clientMessageId.trim()
            if (clientMessageId.isBlank() || clientMessageId.length > 100) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный clientMessageId"))
            val replyId = body.replyToMessageId.uuidOrNull()

            val result = transaction {
                if (!isConversationMember(conversationId, session.userId)) return@transaction null
                ChatMessagesTable.selectAll().where { (ChatMessagesTable.senderId eq session.userId) and (ChatMessagesTable.clientMessageId eq clientMessageId) }.limit(1).singleOrNull()?.let { return@transaction messageDto(it) }
                if (replyId != null && ChatMessagesTable.selectAll().where { (ChatMessagesTable.id eq replyId) and (ChatMessagesTable.conversationId eq conversationId) }.limit(1).none()) return@transaction null

                val messageId = UUID.randomUUID()
                val lastCreatedAt = ChatMessagesTable.selectAll().where { ChatMessagesTable.conversationId eq conversationId }.orderBy(ChatMessagesTable.createdAt to SortOrder.DESC).limit(1).singleOrNull()?.get(ChatMessagesTable.createdAt) ?: 0L
                val now = maxOf(System.currentTimeMillis(), lastCreatedAt + 1)
                val encrypted = ChatCrypto.encrypt(text)
                ChatMessagesTable.insert {
                    it[id] = messageId
                    it[ChatMessagesTable.conversationId] = conversationId
                    it[senderId] = session.userId
                    it[bodyCiphertext] = encrypted.first
                    it[bodyIv] = encrypted.second
                    it[bodyKeyVersion] = 1
                    it[ChatMessagesTable.clientMessageId] = clientMessageId
                    it[replyToMessageId] = replyId
                    it[createdAt] = now
                    it[editedAt] = null
                    it[deletedAt] = null
                }
                ChatConversationsTable.update({ ChatConversationsTable.id eq conversationId }) { it[updatedAt] = now; it[lastMessageId] = messageId }
                ChatConversationMembersTable.update({ (ChatConversationMembersTable.conversationId eq conversationId) and (ChatConversationMembersTable.userId eq session.userId) }) {
                    it[lastReadMessageId] = messageId
                    it[lastReadAt] = now
                }
                messageDto(ChatMessagesTable.selectAll().where { ChatMessagesTable.id eq messageId }.single())
            } ?: return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Нет доступа к диалогу или ответу"))
            publishMessageCreated(conversationId, result)
            call.respond(HttpStatusCode.Created, result)
        }

        post("/conversations/{conversationId}/read") {
            val session = call.requireChatSession() ?: return@post
            val conversationId = call.parameters["conversationId"].uuidOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный conversationId"))
            val body = runCatching { call.receive<MarkChatReadRequest>() }.getOrElse { MarkChatReadRequest() }
            val requestedMessageId = body.messageId.uuidOrNull()

            val updated = transaction {
                if (!isConversationMember(conversationId, session.userId)) return@transaction false
                val messageId = requestedMessageId ?: ChatConversationsTable.selectAll().where { ChatConversationsTable.id eq conversationId }.limit(1).singleOrNull()?.get(ChatConversationsTable.lastMessageId)
                if (messageId != null && ChatMessagesTable.selectAll().where { (ChatMessagesTable.id eq messageId) and (ChatMessagesTable.conversationId eq conversationId) }.limit(1).none()) return@transaction false
                ChatConversationMembersTable.update({ (ChatConversationMembersTable.conversationId eq conversationId) and (ChatConversationMembersTable.userId eq session.userId) }) {
                    it[lastReadMessageId] = messageId
                    it[lastReadAt] = System.currentTimeMillis()
                }
                true
            }

            if (!updated) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "Нет доступа к сообщению")
                )
            } else {
                publishConversationRead(
                    conversationId = conversationId,
                    readerId = session.userId,
                    messageId = requestedMessageId
                )
                call.respond(mapOf("ok" to true))
            }
        }

        post("/conversations/{conversationId}/attachments") {
            val session = call.requireChatSession() ?: return@post
            val conversationId = call.parameters["conversationId"].uuidOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный conversationId"))
            if (!transaction { isConversationMember(conversationId, session.userId) }) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Нет доступа к диалогу"))

            val multipart = call.receiveMultipart()
            var originalName = ""
            var mimeType = "application/octet-stream"
            var text = ""
            var clientMessageId = ""
            var tempFile: File? = null
            var size = 0L
            val digest = MessageDigest.getInstance("SHA-256")

            try {
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> when (part.name) {
                            "text" -> text = part.value.trim()
                            "clientMessageId" -> clientMessageId = part.value.trim()
                        }
                        is PartData.FileItem -> if (part.name == "file" && tempFile == null) {
                            originalName = File(part.originalFileName ?: "attachment").name.take(500)
                            mimeType = part.contentType?.toString()?.take(255) ?: "application/octet-stream"
                            tempFile = File.createTempFile("chat-", ".upload", chatUploadDirectory)
                            FileOutputStream(tempFile!!).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                part.provider().toInputStream().use { input ->
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        if (read == 0) continue
                                        size += read.toLong()
                                        if (size > MAX_CHAT_ATTACHMENT_BYTES) {
                                            throw IllegalArgumentException(
                                                "Файл превышает 100 MiB"
                                            )
                                        }
                                        digest.update(buffer, 0, read)
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                        }
                        else -> Unit
                    }
                    part.dispose()
                }

                val sourceFile = tempFile ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Файл не передан"))
                if (clientMessageId.isBlank() || clientMessageId.length > 100) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный clientMessageId"))
                if (text.length > 20_000) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Текст превышает 20000 символов"))

                val response = transaction {
                    ChatMessagesTable.selectAll().where { (ChatMessagesTable.senderId eq session.userId) and (ChatMessagesTable.clientMessageId eq clientMessageId) }.limit(1).singleOrNull()?.let { existing ->
                        val message = messageDto(existing)
                        val attachment = message.attachments.firstOrNull() ?: return@transaction null
                        return@transaction ChatAttachmentUploadResponse(attachment, message)
                    }

                    val lastCreatedAt = ChatMessagesTable.selectAll().where { ChatMessagesTable.conversationId eq conversationId }.orderBy(ChatMessagesTable.createdAt to SortOrder.DESC).limit(1).singleOrNull()?.get(ChatMessagesTable.createdAt) ?: 0L
                    val now = maxOf(System.currentTimeMillis(), lastCreatedAt + 1)
                    val messageId = UUID.randomUUID()
                    val attachmentId = UUID.randomUUID()
                    val storedName = "$attachmentId.bin"
                    val encryptedFile = File(chatUploadDirectory, storedName)
                    val encryptedText = ChatCrypto.encrypt(text)

                    FileInputStream(sourceFile).use { input ->
                        FileOutputStream(encryptedFile).use { output ->
                            val iv = ChatCrypto.encryptStream(input, output)
                            ChatMessagesTable.insert {
                                it[id] = messageId
                                it[ChatMessagesTable.conversationId] = conversationId
                                it[senderId] = session.userId
                                it[bodyCiphertext] = encryptedText.first
                                it[bodyIv] = encryptedText.second
                                it[bodyKeyVersion] = 1
                                it[ChatMessagesTable.clientMessageId] = clientMessageId
                                it[replyToMessageId] = null
                                it[createdAt] = now
                                it[editedAt] = null
                                it[deletedAt] = null
                            }
                            ChatAttachmentsTable.insert {
                                it[id] = attachmentId
                                it[ChatAttachmentsTable.messageId] = messageId
                                it[ChatAttachmentsTable.originalName] = originalName
                                it[ChatAttachmentsTable.storedName] = storedName
                                it[storagePath] = encryptedFile.absolutePath
                                it[ChatAttachmentsTable.mimeType] = mimeType
                                it[sizeBytes] = size
                                it[checksumSha256] = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                                it[encryptionIv] = iv
                                it[encryptionKeyVersion] = 1
                                it[createdAt] = now
                            }
                        }
                    }

                    ChatConversationsTable.update({ ChatConversationsTable.id eq conversationId }) { it[updatedAt] = now; it[lastMessageId] = messageId }
                    ChatConversationMembersTable.update({ (ChatConversationMembersTable.conversationId eq conversationId) and (ChatConversationMembersTable.userId eq session.userId) }) { it[lastReadMessageId] = messageId; it[lastReadAt] = now }
                    val message = messageDto(ChatMessagesTable.selectAll().where { ChatMessagesTable.id eq messageId }.single())
                    ChatAttachmentUploadResponse(message.attachments.single(), message)
                }

                if (response == null) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "clientMessageId уже занят текстовым сообщением")
                    )
                } else {
                    publishMessageCreated(conversationId, response.message)
                    call.respond(HttpStatusCode.Created, response)
                }
            } catch (error: IllegalArgumentException) {
                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to (error.message ?: "Файл слишком большой")))
            } finally {
                tempFile?.delete()
            }
        }

        get("/attachments/{attachmentId}/download") {
            val session = call.requireChatSession() ?: return@get
            val attachmentId = call.parameters["attachmentId"].uuidOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный attachmentId"))

            val attachment = transaction {
                val row = ChatAttachmentsTable.selectAll().where { ChatAttachmentsTable.id eq attachmentId }.limit(1).singleOrNull() ?: return@transaction null
                val message = ChatMessagesTable.selectAll().where { ChatMessagesTable.id eq row[ChatAttachmentsTable.messageId].value }.limit(1).singleOrNull() ?: return@transaction null
                if (!isConversationMember(message[ChatMessagesTable.conversationId].value, session.userId)) return@transaction null
                Triple(row[ChatAttachmentsTable.storagePath], row[ChatAttachmentsTable.originalName], Pair(row[ChatAttachmentsTable.mimeType], row[ChatAttachmentsTable.encryptionIv]))
            } ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Файл не найден"))

            val file = File(attachment.first)
            if (!file.isFile) return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Файл не найден"))
            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, attachment.second).toString())
            call.response.header(HttpHeaders.ContentType, attachment.third.first)
            call.respondOutputStream {
                FileInputStream(file).use { input -> ChatCrypto.decryptStream(input, this, attachment.third.second) }
            }
        }
    }
}
private suspend fun publishMessageCreated(
    conversationId: UUID,
    message: ChatMessageDto
) {
    val memberIds = transaction {
        conversationMemberIds(conversationId)
    }

    memberIds.forEach { memberId ->
        val unreadCount = transaction {
            conversationDto(conversationId, memberId)?.unreadCount ?: 0
        }

        ChatRealtimeHub.send(
            memberId,
            ChatRealtimeEvent(
                type = "MESSAGE_CREATED",
                conversationId = conversationId.toString(),
                message = message,
                unreadCount = unreadCount
            )
        )
    }
}

private suspend fun publishConversationRead(
    conversationId: UUID,
    readerId: UUID,
    messageId: UUID?
) {
    val memberIds = transaction {
        conversationMemberIds(conversationId)
    }

    ChatRealtimeHub.sendToUsers(
        memberIds,
        ChatRealtimeEvent(
            type = "CONVERSATION_READ",
            conversationId = conversationId.toString(),
            userId = readerId.toString(),
            messageId = messageId?.toString(),
            unreadCount = 0
        )
    )
}

private fun conversationMemberIds(conversationId: UUID): List<UUID> =
    ChatConversationMembersTable
        .selectAll()
        .where {
            ChatConversationMembersTable.conversationId eq conversationId
        }
        .map {
            it[ChatConversationMembersTable.userId].value
        }

private suspend fun ApplicationCall.requireChatSession(): ChatSession? {
    val session = SessionManager.validate(request.headers["X-Session-Token"])
    if (session == null) { respond(HttpStatusCode.Unauthorized, mapOf("error" to "Session expired")); return null }
    return ChatSession(session.userId)
}

private fun conversationIdsFor(userId: UUID): List<UUID> {
    val ids = ChatConversationMembersTable.selectAll().where { (ChatConversationMembersTable.userId eq userId) and (ChatConversationMembersTable.isArchived eq false) }.map { it[ChatConversationMembersTable.conversationId].value }
    return ChatConversationsTable.selectAll().where { ChatConversationsTable.id inList ids }.orderBy(ChatConversationsTable.updatedAt to SortOrder.DESC).map { it[ChatConversationsTable.id].value }
}

private fun isConversationMember(conversationId: UUID, userId: UUID): Boolean =
    ChatConversationMembersTable.selectAll().where { (ChatConversationMembersTable.conversationId eq conversationId) and (ChatConversationMembersTable.userId eq userId) }.limit(1).any()

private fun conversationDto(conversationId: UUID, viewerId: UUID): ChatConversationDto? {
    val conversation = ChatConversationsTable.selectAll().where { ChatConversationsTable.id eq conversationId }.limit(1).singleOrNull() ?: return null
    val members = ChatConversationMembersTable.selectAll().where { ChatConversationMembersTable.conversationId eq conversationId }.mapNotNull { member ->
        UsersTable.selectAll().where { UsersTable.id eq member[ChatConversationMembersTable.userId].value }.limit(1).singleOrNull()?.let {
            ChatUserDto(it[UsersTable.id].value.toString(), it[UsersTable.name], it[UsersTable.role], it[UsersTable.position].orEmpty())
        }
    }
    val lastMessage = conversation[ChatConversationsTable.lastMessageId]?.let { id -> ChatMessagesTable.selectAll().where { ChatMessagesTable.id eq id }.limit(1).singleOrNull()?.let(::messageDto) }
    val readAt = ChatConversationMembersTable.selectAll().where { (ChatConversationMembersTable.conversationId eq conversationId) and (ChatConversationMembersTable.userId eq viewerId) }.limit(1).singleOrNull()?.get(ChatConversationMembersTable.lastReadAt) ?: 0L
    val unread = ChatMessagesTable.selectAll().where { (ChatMessagesTable.conversationId eq conversationId) and (ChatMessagesTable.senderId neq viewerId) and (ChatMessagesTable.createdAt greater readAt) }.count().toInt()
    val title = if (conversation[ChatConversationsTable.conversationType] == "DIRECT") members.firstOrNull { it.id != viewerId.toString() }?.name ?: "Диалог" else "Групповой чат"
    return ChatConversationDto(conversationId.toString(), conversation[ChatConversationsTable.conversationType], title, members, lastMessage, unread, conversation[ChatConversationsTable.updatedAt])
}

private fun messageDto(row: ResultRow): ChatMessageDto {
    val messageId = row[ChatMessagesTable.id].value
    val senderId = row[ChatMessagesTable.senderId].value
    val senderName = UsersTable.selectAll().where { UsersTable.id eq senderId }.limit(1).singleOrNull()?.get(UsersTable.name).orEmpty()
    val text = if (row[ChatMessagesTable.deletedAt] == null) runCatching { ChatCrypto.decrypt(row[ChatMessagesTable.bodyCiphertext], row[ChatMessagesTable.bodyIv]) }.getOrDefault("Не удалось расшифровать сообщение") else ""
    val attachments = ChatAttachmentsTable.selectAll().where { ChatAttachmentsTable.messageId eq messageId }.map {
        ChatAttachmentDto(it[ChatAttachmentsTable.id].value.toString(), it[ChatAttachmentsTable.originalName], it[ChatAttachmentsTable.mimeType], it[ChatAttachmentsTable.sizeBytes], "/api/v1/chat/attachments/${it[ChatAttachmentsTable.id].value}/download")
    }
    return ChatMessageDto(messageId.toString(), row[ChatMessagesTable.conversationId].value.toString(), senderId.toString(), senderName, text, row[ChatMessagesTable.clientMessageId], row[ChatMessagesTable.replyToMessageId]?.toString(), row[ChatMessagesTable.createdAt], row[ChatMessagesTable.editedAt], row[ChatMessagesTable.deletedAt], attachments)
}

private fun String?.uuidOrNull(): UUID? = this?.trim()?.takeIf(String::isNotBlank)?.let { runCatching { UUID.fromString(it) }.getOrNull() }