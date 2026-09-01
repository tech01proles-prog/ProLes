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
    val role: String,
    val position: String = "",
    val defaultRateType: String = "HOURLY",
    val defaultRate: Double = 0.0,
    val defaultCurrency: String = "RUB",
    val newPassword: String? = null
)

@Serializable
data class IncomeDto(
    val id: String = "",
    val userId: String = "",
    val projectId: String? = null,
    val projectName: String = "",
    val date: String = "",
    val type: String = "",        // 🆕 Тип дохода (HOUSEHOLD, CARD, CASH)
    val name: String = "",
    val amount: Double = 0.0,
    val currency: String = "RUB",
    val category: String = "WORK", // 🆕 Надкатегория: WORK | PERSONAL
    val subcategory: String? = null, // 🆕 Подкатегория типа расхода
    val createdAt: Long = 0L
)

@Serializable
data class ExpenseDto(
    val id: String,
    val userId: String,
    val projectId: String,
    val projectName: String = "",
    val date: String,
    val type: String,
    val name: String = "",
    val amount: Double,
    val currency: String = "RUB",
    val comment: String = "",
    val receiptSubmitted: Boolean = false,
    val hasReceiptPhoto: Boolean = false, // 🆕
    val category: String = "WORK",         // 🆕 Надкатегория: WORK | PERSONAL
    val subcategory: String? = null        // 🆕 Подкатегория типа расхода
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
    val sellingPrice: Double = 0.0
)

@Serializable
data class VacationDto(val id: String, val userId: String, val start: String, val end: String)

@Serializable
data class TimeEntry(
    val id: String,
    val userId: String,
    val projectId: String,
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
    val projectId: String,
    val projectName: String = "",
    val type: String, // DEPARTURE / TRANSFER / COMPLETION
    val date: String,
    val city: String = "",  // ← оставить для обратной совместимости
    val waypoints: List<WaypointDto> = emptyList(),  // 🆕 маршрут
    val transport: String = "",
    val notes: String = "",
    val participants: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val perDiemRate: Double = 750.0  // 🆕 Размер суточных по умолчанию
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
    val total: Double
)

@Serializable
data class SalaryComponentDto(
    val id: String = "",
    val userId: String = "",
    val type: String = "",           // FIXED, HOURLY, PIECE, BONUS
    val amount: Double = 0.0,
    val projectId: String? = null,
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
    val city: String,
    val address: String = ""
)