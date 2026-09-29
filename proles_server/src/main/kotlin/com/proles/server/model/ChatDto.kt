package com.proles.server.model

import kotlinx.serialization.Serializable

@Serializable
data class CreateDirectConversationRequest(val userId: String)

@Serializable
data class SendChatMessageRequest(val text: String, val clientMessageId: String, val replyToMessageId: String? = null)

@Serializable
data class ChatUserDto(val id: String, val name: String, val role: String, val position: String = "")

@Serializable
data class ChatAttachmentDto(val id: String, val originalName: String, val mimeType: String, val sizeBytes: Long, val downloadUrl: String)

@Serializable
data class ChatMessageDto(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val clientMessageId: String,
    val replyToMessageId: String? = null,
    val createdAt: Long,
    val editedAt: Long? = null,
    val deletedAt: Long? = null,
    val attachments: List<ChatAttachmentDto> = emptyList()
)

@Serializable
data class ChatConversationDto(
    val id: String,
    val type: String,
    val title: String,
    val members: List<ChatUserDto>,
    val lastMessage: ChatMessageDto? = null,
    val unreadCount: Int = 0,
    val updatedAt: Long
)

@Serializable
data class ChatMessagesPageDto(val items: List<ChatMessageDto>, val nextCursor: String? = null)

@Serializable
data class MarkChatReadRequest(val messageId: String? = null)