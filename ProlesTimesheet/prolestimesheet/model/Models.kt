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
    val sellingPrice: Double = 0.0
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TimeEntry(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val projectId: String,
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
    val projectName: String = "",
    val date: LocalDate = LocalDate(2026, 1, 1),
    val name: String = "",
    val amount: Double = 0.0,
    val currency: String = "RUB",
    val type: String = "WORK", // 🆕 Тип дохода: WORK | PERSONAL | OTHER
    val category: String = "SALARY", // 🆕 Надкатегория: SALARY | BONUS | GIFT | OTHER
    val subcategory: String? = null, // 🆕 Подкатегория типа дохода
    val createdAt: Long = 0L
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class Expense(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val projectId: String,
    val projectName: String = "",
    val date: LocalDate,
    val type: String,          // ROAD или OTHER
    val name: String = "",     // название для OTHER
    val amount: Double,
    val currency: String = "RUB",
    val comment: String = "",
    val receiptSubmitted: Boolean = false,
    val hasReceiptPhoto: Boolean = false,
    val category: String = "WORK",         // 🆕 Надкатегория: WORK | PERSONAL
    val subcategory: String? = null        // 🆕 Подкатегория типа расхода
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
    val projectId: String,
    val projectName: String = "",
    val type: String = "DEPARTURE", // DEPARTURE, TRANSFER, COMPLETION
    val date: LocalDate,
    val city: String = "",
    val waypoints: List<Waypoint> = emptyList(),  // 🆕 Пункты следования
    val participants: List<String> = emptyList(),
    val transport: String = "",
    val notes: String = "",
    val createdAt: Long = 0L,
    val perDiemRate: Double = 750.0  // 🆕 Размер суточных
)

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