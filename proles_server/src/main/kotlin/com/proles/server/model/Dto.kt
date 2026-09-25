package com.proles.server.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val email: String, val password: String, val rememberMe: Boolean = false)

@Serializable
data class AuthResponse(val token: String, val expiresAt: Long, val user: UserDto)

/**
 * 🎯 Effective-права пользователя (role + overrides merged).
 * Возвращается в GET /rbac/users/{userId}/permissions
 */
@Serializable
data class UserEffectivePermissionDto(
    val permission: String,
    val canView: Boolean = false,
    val canCreate: Boolean = false,
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
    val isOverride: Boolean = false  // true если есть персональный override
)

@Serializable
data class UserDto(
    val id: String,
    val lastName: String = "",
    val firstName: String = "",
    val middleName: String = "",
    val name: String = "",
    val login: String = "",
    val email: String = "",
    val role: String,
    val position: String = "",
    val defaultRateType: String = "HOURLY",
    val defaultRate: Double = 0.0,
    val defaultCurrency: String = "RUB",
    val phone: String = "",
    val telegramUsername: String = "",
    val birthDate: String? = null,
    val positionId: String? = null,
    val onVacation: Boolean = false,
    val isRemote: Boolean = false,
    val newPassword: String? = null
)

@Serializable
data class IncomeDto(
    val id: String = "",
    val userId: String = "",
    val projectId: String? = null,
    val subprojectId: String? = null,
    val subprojectName: String = "",
    val projectName: String = "",
    val date: String = "",
    val type: String = "",        // 🆕 Тип дохода (HOUSEHOLD, CARD, CASH)
    val name: String = "",
    val amount: Double = 0.0,
    val currency: String = "RUB",
    val category: String = "WITH_RECEIPT",
    val subcategory: String? = null, // 🆕 Подкатегория типа расхода
    val createdAt: Long = 0L
)

@Serializable
data class ExpenseDto(
    val id: String,
    val userId: String,
    val projectId: String? = null,
    val subprojectId: String? = null,
    val subprojectName: String = "",
    val projectName: String = "",
    val date: String,
    val type: String,
    val name: String = "",
    val amount: Double,
    val currency: String = "RUB",
    val comment: String = "",
    val receiptSubmitted: Boolean = false,
    val hasReceiptPhoto: Boolean = false, // 🆕
    val category: String = "WITH_RECEIPT",
    val subcategory: String? = null,       // 🆕 Подкатегория типа расхода
    val receiptCount: Int = 0,
    val expenseScope: String = "GENERAL",
    val creatorRole: String = "",
    val createdAt: Long = 0L
)



@Serializable
data class ProjectCostPayrollDataDto(
    val employees: List<UserDto> = emptyList(),
    val components: List<SalaryComponentDto> = emptyList()
)

@Serializable
data class ProjectCostUpdateDto(
    val sellingPrice: Double = 0.0,
    val transportToClient: Double = 0.0,
    val materials: Double = 0.0,
    val contractors: Double = 0.0,
    val creditPercent: Double = 0.0
)

@Serializable
data class ProjectDto(
    val id: String,
    val name: String,
    val isActive: Boolean = true,
    val status: String = "new",
    val lead: String = "",
    val revenue: Double = 0.0,
    val expenses: Double = 0.0,
    val cost: Double = 0.0,
    val profit: Double = 0.0,
    val projectNumber: String = "",
    val subProjectNumber: String = "",
    val client: String = "",      // 🆕
    val location: String = "",    // 🆕
    val productService: String = "",
    val quantity: Int = 1,
    val deliveryDate: String? = null,
    val contract: String = "",
    val notes: String = "",
    val projectCode: String = "",
    val completionDate: String? = null,
    val customer: String = "",
    val productionCost: Double = 0.0,
    val transportToClient: Double = 0.0,
    val sellingPrice: Double = 0.0,
    val materials: Double = 0.0,
    val contractors: Double = 0.0,
    val creditPercent: Double = 0.0,
    val subprojects: List<SubprojectDto> = emptyList()
)

@Serializable
data class VacationDto(
    val id: String,
    val userId: String,
    val start: String,
    val end: String,
    val status: String = "PENDING",
    val approvedBy: String? = null,
    val approvedAt: Long? = null,
    val rejectionReason: String = ""
)

@Serializable
data class PersonalTimesheetTaskDto(
    val id: String,
    val userId: String,
    val year: Int,
    val month: Int,
    val periodStart: String,
    val periodEnd: String,
    val name: String = "",
    val description: String = "",
    val hours: Double = 0.0,
    val category: String = "",
    val status: String = "Not started",
    val isMonthTask: Boolean = false,
    val sortOrder: Int = 0
)

@Serializable
data class PersonalTimesheetResponseDto(
    val userId: String,
    val year: Int,
    val month: Int,
    val periodStart: String,
    val periodEnd: String,
    val monthlyTaskId: String? = null,
    val tasks: List<PersonalTimesheetTaskDto> = emptyList()
)

@Serializable
data class PersonalTimesheetSaveTaskDto(
    val id: String,
    val name: String = "",
    val description: String = "",
    val hours: Double = 0.0,
    val category: String = "",
    val status: String = "Not started",
    val sortOrder: Int = 0
)

@Serializable
data class PersonalTimesheetSaveResponseDto(
    val success: Boolean,
    val year: Int,
    val month: Int
)

@Serializable
data class PersonalTimesheetCategoryDto(
    val id: String,
    val name: String
)

@Serializable
data class PersonalTimesheetSaveRequest(
    val year: Int,
    val month: Int,
    val periodStart: String,
    val periodEnd: String,
    val monthlyTaskId: String? = null,
    val tasks: List<PersonalTimesheetSaveTaskDto> = emptyList()
)

@Serializable
data class TimeEntry(
    val id: String,
    val userId: String,
    val projectId: String,
    val subprojectId: String? = null,
    val subprojectName: String = "",
    val projectName: String = "",
    val date: String,
    val hours: Float = 0f,
    val country: String = "RF",
    val comment: String = "",
    val synced: Boolean = false
)

@Serializable
data class DayOffRequest(val user_id: String, val date: String)

// 🆕 DTO для командировок
@Serializable
data class BusinessTripDto(
    val id: String,
    val userId: String,
    val userName: String = "",
    val projectId: String? = null,
    val subprojectId: String? = null,
    val subprojectName: String = "",
    val projectNumber: String = "",
    val companyName: String = "",
    val country: String = "",
    val projectName: String = "",
    val type: String, // DEPARTURE / TRANSFER / COMPLETION
    val date: String,
    val completedDate: String? = null,
    val city: String = "",  // ← оставить для обратной совместимости
    val waypoints: List<WaypointDto> = emptyList(),  // 🆕 маршрут
    val transport: String = "",
    val notes: String = "",
    val participants: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val perDiemRate: Double = 750.0,
    val startDate: String = date,
    val endDate: String? = completedDate,
    val status: String = if (completedDate == null) "ACTIVE" else "COMPLETED"
)

// 🆕 DTO для уведомлений
@Serializable
data class NotificationDto(
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

@Serializable
data class CreateNotificationRequest(
    val type: String,
    val title: String,
    val message: String,
    val payload: String = ""
)

// 🔥 Data class для RBAC ответов (kotlinx.serialization не умеет Map<String, Any>)
@Serializable
data class RolePermissionDto(
    val permission: String,
    val canView: Boolean,
    val canCreate: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean
)

@Serializable
data class RoleDto(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val permissions: List<RolePermissionDto>
)

@Serializable
data class PermissionDto(val key: String, val displayName: String)


@Serializable
data class SalaryBreakdownResponse(
    val id: String,
    val fixed: Double,
    val piece: Double,
    val hourly: Double,
    val bonus: Double,
    val penalty: Double = 0.0,
    val withholding: Double = 0.0,
    val withholdingRepayment: Double = 0.0,
    val gross: Double = fixed + piece + hourly + bonus,
    val total: Double,
    val balance: Double = 0.0,
    val taxInclusiveCost: Double = 0.0,
    val remoteEmployee: Boolean = false,
    val projectGroups: List<PayrollProjectGroupDto> = emptyList()
)

@Serializable
data class SalaryComponentDto(
    val id: String = "",
    val userId: String = "",
    val type: String = "",           // FIXED, HOURLY, PIECE, BONUS
    val amount: Double = 0.0,
    val projectId: String? = null,
    val subprojectId: String? = null,
    val subprojectName: String = "",
    val ratePerHour: Double? = null,
    val ratePerUnit: Double? = null,
    val description: String = "",
    val effectiveFrom: String = "",
    val effectiveTo: String? = null,
    val isActive: Boolean = true
)

@Serializable
data class WaypointDto(
    val order: Int,
    val country: String = "",
    val city: String,
    val address: String = ""
)

@Serializable
data class PositionDto(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val parentName: String? = null,
    val isActive: Boolean = true,
    val sortOrder: Int = 0
)

@Serializable
data class SubprojectDto(
    val id: String = "",
    val projectId: String,
    val name: String,
    val code: String = "",
    val description: String = "",
    val isActive: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val archivedAt: Long? = null
)

@Serializable
data class ProjectTreeDto(
    val project: ProjectDto,
    val subprojects: List<SubprojectDto> = emptyList()
)

@Serializable
data class CreateSubprojectRequest(
    val name: String,
    val code: String = "",
    val description: String = "",
    val sortOrder: Int = 0
)

@Serializable
data class UpdateSubprojectRequest(
    val name: String? = null,
    val code: String? = null,
    val description: String? = null,
    val isActive: Boolean? = null,
    val sortOrder: Int? = null
)

@Serializable
data class TnpaDocumentDto(
    val id: String,
    val projectId: String,
    val projectName: String = "",
    val subprojectId: String? = null,
    val subprojectName: String = "",
    val originalName: String,
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long,
    val checksumSha256: String,
    val description: String = "",
    val uploadedBy: String,
    val uploaderName: String = "",
    val uploadedAt: Long,
    val downloadUrl: String = "",
    val deletedAt: Long? = null
)

@Serializable
data class CompleteBusinessTripRequest(
    val endDate: String
)

@Serializable
data class HoursProjectGroupDto(
    val projectId: String? = null,
    val projectName: String = "Без проекта",
    val totalHours: Double = 0.0,
    val subprojects: List<HoursSubprojectGroupDto> = emptyList(),
    val entriesWithoutSubproject: List<TimeEntry> = emptyList()
)

@Serializable
data class HoursSubprojectGroupDto(
    val subprojectId: String,
    val subprojectName: String,
    val totalHours: Double = 0.0,
    val entries: List<TimeEntry> = emptyList()
)

@Serializable
data class GroupedHoursResponseDto(
    val userId: String,
    val totalHours: Double,
    val groups: List<HoursProjectGroupDto> = emptyList()
)

@Serializable
data class PayrollHourEntryDto(
    val id: String,
    val date: String,
    val hours: Double,
    val hourlyCost: Double = 0.0,
    val amount: Double = 0.0,
    val comment: String = ""
)

@Serializable
data class PayrollSubprojectGroupDto(
    val subprojectId: String,
    val subprojectName: String,
    val hours: Double = 0.0,
    val amount: Double = 0.0,
    val entries: List<PayrollHourEntryDto> = emptyList()
)

@Serializable
data class PayrollProjectGroupDto(
    val projectId: String? = null,
    val projectName: String = "Без проекта",
    val hours: Double = 0.0,
    val amount: Double = 0.0,
    val subprojects: List<PayrollSubprojectGroupDto> = emptyList(),
    val entriesWithoutSubproject: List<PayrollHourEntryDto> = emptyList()
)

@Serializable
data class PayrollAdjustmentRequest(
    val penaltyAmount: Double = 0.0,
    val withholdingAmount: Double = 0.0,
    val withholdingRepaymentAmount: Double = 0.0,
    val comment: String = "",
    val idempotencyKey: String? = null
)

@Serializable
data class CalculateSalaryWithAdjustmentsRequest(
    val userId: String,
    val year: Int,
    val month: Int,
    val penaltyAmount: Double = 0.0,
    val withholdingAmount: Double = 0.0,
    val withholdingRepaymentAmount: Double = 0.0,
    val comment: String = "",
    val idempotencyKey: String? = null
)

@Serializable
data class EmployeeBalanceDto(
    val userId: String,
    val balance: Double,
    val transactions: List<EmployeeBalanceTransactionDto> = emptyList()
)

@Serializable
data class EmployeeBalanceTransactionDto(
    val id: String,
    val userId: String,
    val salaryRecordId: String? = null,
    val transactionType: String,
    val amount: Double,
    val comment: String = "",
    val createdBy: String,
    val createdByName: String = "",
    val createdAt: Long,
    val reversedTransactionId: String? = null
)

@Serializable
data class BalanceRepaymentRequest(
    val amount: Double,
    val salaryRecordId: String? = null,
    val comment: String = "",
    val idempotencyKey: String? = null
)

@Serializable
data class TaxInclusiveEmployeeCostDto(
    val userId: String,
    val earnedAmount: Double,
    val isRemote: Boolean,
    val taxInclusiveCost: Double
)

@Serializable
data class DirectorExpenseNotificationPayload(
    val expenseId: String,
    val authorId: String,
    val authorName: String,
    val amount: Double,
    val currency: String,
    val date: String
)

@Serializable
data class ChatUserDto(
    val id: String,
    val name: String,
    val role: String,
    val position: String = ""
)

@Serializable
data class ChatConversationDto(
    val id: String,
    val type: String = "DIRECT",
    val participant: ChatUserDto,
    val lastMessage: ChatMessageDto? = null,
    val unreadCount: Int = 0,
    val updatedAt: Long
)

@Serializable
data class CreateDirectConversationRequest(
    val participantUserId: String
)

@Serializable
data class ChatMessageDto(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String = "",
    val body: String = "",
    val clientMessageId: String,
    val replyToMessageId: String? = null,
    val attachments: List<ChatAttachmentDto> = emptyList(),
    val createdAt: Long,
    val editedAt: Long? = null,
    val deletedAt: Long? = null,
    val isOwn: Boolean = false
)

@Serializable
data class SendChatMessageRequest(
    val body: String = "",
    val clientMessageId: String,
    val replyToMessageId: String? = null,
    val attachmentIds: List<String> = emptyList()
)

@Serializable
data class ChatAttachmentDto(
    val id: String,
    val messageId: String? = null,
    val originalName: String,
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long,
    val checksumSha256: String,
    val downloadUrl: String = "",
    val createdAt: Long
)

@Serializable
data class ChatMessagesPageDto(
    val messages: List<ChatMessageDto>,
    val nextCursor: String? = null,
    val hasMore: Boolean = false
)

@Serializable
data class MarkChatReadRequest(
    val messageId: String
)

@Serializable
data class ChatSocketEventDto(
    val type: String,
    val conversationId: String? = null,
    val message: ChatMessageDto? = null,
    val messageId: String? = null,
    val userId: String? = null,
    val occurredAt: Long = System.currentTimeMillis()
)