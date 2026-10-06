package com.example.prolestimesheet.model

import android.annotation.SuppressLint
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import java.util.UUID

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class UserEffectivePermissionDto(
    val permission: String,
    val canView: Boolean = false,
    val canCreate: Boolean = false,
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
    val isOverride: Boolean = false
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class UserPermissionOverrideDto(
    val permission: String,
    val canView: Boolean? = null,
    val canCreate: Boolean? = null,
    val canEdit: Boolean? = null,
    val canDelete: Boolean? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class User(
    val id: String,
    val lastName: String = "",
    val firstName: String = "",
    val middleName: String = "",
    val name: String = "",
    val login: String = "",
    val role: String,
    val position: String = "",
    val defaultRateType: RateType = RateType.HOURLY,
    val defaultRate: Double = 0.0,
    val defaultCurrency: Currency = Currency.RUB,
    val newPassword: String? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class Subproject(
    val id: String = "",
    val projectId: String = "",
    val name: String = "",
    val code: String = "",
    val description: String = "",
    val isActive: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val archivedAt: Long? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class Project(
    val id: String = "",
    val name: String = "",
    val isActive: Boolean = true,
    val status: String = "new",
    val lead: String = "",
    val revenue: Double = 0.0,
    val expenses: Double = 0.0,
    val cost: Double = 0.0,
    val profit: Double = 0.0,
    val projectNumber: String = "",
    val subProjectNumber: String = "",
    val client: String = "",      // 🆕 Клиент
    val location: String = "",    // 🆕 Город
    val productService: String = "",
    val quantity: Int = 1,
    val deliveryDate: String? = null,  // Дата может быть null
    val contract: String = "",
    val notes: String = "",
    val projectCode: String = "",
    val completionDate: String? = null, // Дата может быть null
    val customer: String = "",
    val productionCost: Double = 0.0,
    val transportToClient: Double = 0.0,
    val sellingPrice: Double = 0.0,
    val subprojects: List<Subproject> = emptyList()
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TimeEntry(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val projectId: String,
    val subprojectId: String? = null,
    val subprojectName: String = "",
    val projectName: String = "",
    val date: LocalDate,
    val hours: Float = 0f,
    val country: String = "RF",
    val comment: String = "",
    val synced: Boolean = false,
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class VacationPeriod(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val start: LocalDate,
    val end: LocalDate
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class DayOff(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val date: LocalDate
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class Income(
    val id: String = "",
    val userId: String = "",
    val projectId: String? = null,
    val subprojectId: String? = null,
    val subprojectName: String = "",
    val projectName: String = "",
    val date: LocalDate = LocalDate(2026, 1, 1),
    val name: String = "",
    val amount: Double = 0.0,
    val currency: String = "RUB",
    val type: String = "HOUSEHOLD", // Тип дохода: HOUSEHOLD | CARD | CASH
    val category: String = "SALARY", // 🆕 Надкатегория: SALARY | BONUS | GIFT | OTHER
    val subcategory: String? = null, // 🆕 Подкатегория типа дохода
    val createdAt: Long = 0L
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class Expense(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val projectId: String? = null,
    val subprojectId: String? = null,
    val subprojectName: String = "",
    val projectName: String = "",
    val date: LocalDate,
    val type: String,          // ROAD или OTHER
    val name: String = "",     // название для OTHER
    val amount: Double,
    val currency: String = "RUB",
    val comment: String = "",
    val receiptSubmitted: Boolean = false,
    val hasReceiptPhoto: Boolean = false,
    val category: String = "WITH_RECEIPT",
    val subcategory: String? = null,
    val receiptCount: Int = 0,
    val expenseScope: String = "GENERAL",
    val creatorRole: String = "",
    val createdAt: Long = 0L
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class Waypoint(
    val id: String = UUID.randomUUID().toString(),
    val order: Int,
    val city: String,
    val address: String = ""
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class BusinessTrip(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val projectId: String? = null,
    val subprojectId: String? = null,
    val subprojectName: String = "",
    val projectName: String = "",
    val type: String = "DEPARTURE", // DEPARTURE, TRANSFER, COMPLETION
    val date: LocalDate,
    val city: String = "",
    val waypoints: List<Waypoint> = emptyList(),  // 🆕 Пункты следования
    val participants: List<String> = emptyList(),
    val transport: String = "",
    val notes: String = "",
    val createdAt: Long = 0L,
    val perDiemRate: Double = 750.0,
    val startDate: LocalDate = date,
    val endDate: LocalDate? = null,
    val status: String = if (endDate == null) "ACTIVE" else "COMPLETED"
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ChatUser(
    val id: String,
    val name: String,
    val role: String,
    val position: String = "",
    val online: Boolean = false
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ChatAttachment(
    val id: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val downloadUrl: String
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ChatMessage(
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
    val deliveryStatus: String = "SENT",
    val attachments: List<ChatAttachment> = emptyList()
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ChatConversation(
    val id: String,
    val type: String,
    val title: String,
    val members: List<ChatUser> = emptyList(),
    val lastMessage: ChatMessage? = null,
    val unreadCount: Int = 0,
    val updatedAt: Long = 0L
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ChatMessagesPage(
    val items: List<ChatMessage> = emptyList(),
    val nextCursor: String? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ChatSendMessageRequest(
    val text: String,
    val clientMessageId: String,
    val replyToMessageId: String? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ChatUpdateMessageRequest(val text: String)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ChatReadRequest(val messageId: String? = null)
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class Notification(
    val id: String,
    val targetUserId: String? = null,
    val senderUserId: String,
    val senderName: String = "",
    val type: String,
    val title: String,
    val message: String,
    val payload: String = "",
    val isRead: Boolean = false,
    val createdAt: Long = 0L
)
enum class RateType { FIXED, HOURLY, PER_PROJECT, PER_DAY }
enum class Currency { RUB, BYN, USD, EUR }
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ExpenseReceipt(
    val id: String,
    val expenseId: String,
    val url: String,
    val fileName: String,
    val uploadedAt: Long
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class NotificationPreferences(
    val tripEnabled: Boolean = true,
    val vacationEnabled: Boolean = true,
    val dayoffEnabled: Boolean = true,
    val expenseEnabled: Boolean = true,
    val payrollEnabled: Boolean = true,
    val ticketEnabled: Boolean = true,
    val tripVisibleToAll: Boolean = true,
    val tripChangeEnabled: Boolean = true,
    val vacationDecisionEnabled: Boolean = true,
    val ticketReceiptEnabled: Boolean = true,
    val chatMessageEnabled: Boolean = true,
    val telegramEnabled: Boolean = false,
    val telegramLinked: Boolean = false,
    val telegramLinkCode: String? = null,
    val emailEnabled: Boolean = false,
    val email: String = ""
)
