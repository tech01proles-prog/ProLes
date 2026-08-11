package com.proles.server.data

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.kotlin.datetime.date

object UsersTable : UUIDTable("users") {
    val email = varchar("email", 100).uniqueIndex()
    val login = varchar("login", 100).uniqueIndex()
    val lastName = varchar("last_name", 100).default("")
    val firstName = varchar("first_name", 100).default("")
    val middleName = varchar("middle_name", 100).default("")
    val name = varchar("name", 150)
    val passwordHash = varchar("password_hash", 60)
    val role = varchar("role", 20)
    val position = varchar("position", 100).nullable()
    val defaultRateType = varchar("default_rate_type", 20).default("HOURLY")
    val defaultRate = double("default_rate").default(0.0)
    val defaultCurrency = varchar("default_currency", 3).default("RUB")
}

object ProjectsTable : UUIDTable("projects") {
    val name = varchar("name", 255).uniqueIndex()
    val isActive = bool("is_active").default(true)

    // 🔥 Основные поля (оставляем)
    val projectNumber = varchar("project_number", 50).default("")
    val subProjectNumber = varchar("sub_project_number", 50).default("")
    val client = text("client").default("")       // 🆕 Клиент (заказчик)
    val location = text("location").default("")   // 🆕 Город / локация
    val productService = text("product_service").default("")
    val quantity = integer("quantity").default(1)  // 🔥 По умолчанию 1
    val deliveryDate = date("delivery_date").nullable()
    val contract = text("contract").default("")
    val status = varchar("status", 50).default("new")
    val notes = text("notes").default("")

    // 🔥 Финансовые поля (для аналитики)
    val revenue = double("revenue").default(0.0)
    val expenses = double("expenses").default(0.0)
    val cost = double("cost").default(0.0)
    val profit = double("profit").default(0.0)
    val productionCost = double("production_cost").default(0.0)
    val transportToClient = double("transport_to_client").default(0.0)
    val sellingPrice = double("selling_price").default(0.0)

    // 🔥 Мета-поля
    val lead = varchar("lead", 150).default("")
    val projectCode = varchar("project_code", 50).default("")
    val customer = varchar("customer", 255).default("")
    val completionDate = date("completion_date").nullable()
}

object TimeEntriesTable : UUIDTable("time_entries") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).index()
    val projectId = reference("project_id", ProjectsTable, onDelete = ReferenceOption.CASCADE)
    val projectName = varchar("project_name", 150).default("")
    val date = date("date").index()
    val hours = float("hours").default(0f)
    val country = varchar("country", 10).default("RF")
    val roadExpenses = text("road_expenses").nullable()
    val expenseCurrency = varchar("expense_currency", 3).default("RUB")
    val otherExpenses = text("other_expenses").nullable()
    val comment = text("comment").nullable()
    val synced = bool("synced").default(false)
    val receiptSubmitted = bool("receipt_submitted").default(false)
}

object ExpensesTable : UUIDTable("expenses") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).index()
    val projectId = reference("project_id", ProjectsTable, onDelete = ReferenceOption.CASCADE)
    val date = date("date").index()
    val type = varchar("type", 20)
    val name = varchar("name", 255).default("")
    val amount = double("amount").default(0.0)
    val currency = varchar("currency", 3).default("RUB")
    val comment = text("comment").default("")
    val receiptSubmitted = bool("receipt_submitted").default(false)
    val hasReceiptPhoto = bool("has_receipt_photo").default(false)
    val createdAt = long("created_at").default(0L)
}

// ─────────────────────────────────────────────────────────────
// 💵 INCOMES: Доходы (аналог расходов, без чеков)
// ─────────────────────────────────────────────────────────────
object IncomesTable : UUIDTable("incomes") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).index()
    val projectId = reference("project_id", ProjectsTable, onDelete = ReferenceOption.CASCADE).nullable()
    val date = date("date").index()
    val name = varchar("name", 255).default("")
    val amount = double("amount").default(0.0)
    val currency = varchar("currency", 3).default("RUB")
    val createdAt = long("created_at").default(0L)
}

object VacationsTable : UUIDTable("vacations") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).index()
    val start = date("start_date")
    val end = date("end_date")
}

object DayOffsTable : UUIDTable("day_offs") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).index()
    val date = date("date").index()
    init { uniqueIndex("unique_user_date", userId, date) }
}

// 🆕 Таблица командировок
object BusinessTripsTable : UUIDTable("business_trips") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).index()
    val projectId = reference("project_id", ProjectsTable, onDelete = ReferenceOption.CASCADE)
    val type = varchar("type", 20).default("DEPARTURE")
    val date = date("date")
    val city = varchar("city", 255).default("")
    val participants = text("participants").default("[]")
    val transport = varchar("transport", 100).default("")
    val notes = text("notes").default("")
    val waypoints = text("waypoints").default("[]")  // 🆕 JSON массив пунктов следования
    val createdAt = long("created_at").default(0L)
}

// 🆕 Таблица уведомлений
object NotificationsTable : UUIDTable("notifications") {
    val targetUserId = reference("target_user_id", UsersTable, onDelete = ReferenceOption.CASCADE).nullable()
    val senderUserId = reference("sender_user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val type = varchar("type", 30)
    val title = varchar("title", 255)
    val message = text("message")
    val payload = text("payload").default("")
    val isRead = bool("is_read").default(false)
    val createdAt = long("created_at").default(0L)
}

// 🆕 Таблица фото чеков
object ExpenseReceiptsTable : UUIDTable("expense_receipts") {
    val expenseId = reference("expense_id", ExpensesTable, onDelete = ReferenceOption.CASCADE).index()
    val imageUrl = text("image_url")
    val uploadedAt = long("uploaded_at").default(0L)
}

// 🆕 Таблица фото чеков для билетов
object TicketReceiptsTable : UUIDTable("ticket_receipts") {
    val ticketId = reference("ticket_id", TicketsTable, onDelete = ReferenceOption.CASCADE).index()
    val imageUrl = text("image_url")
    val uploadedAt = long("uploaded_at").default(0L)
}

// 🆕 Таблица FCM токенов
object FcmTokensTable : UUIDTable("fcm_tokens") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).index()
    val token = text("token").uniqueIndex()
    val createdAt = long("created_at").default(0L)
    val lastUsed = long("last_used").default(0L)
}

object NotificationPreferencesTable : UUIDTable("notification_preferences") {
    val userId = reference("user_id", UsersTable).uniqueIndex()
    val tripEnabled = bool("trip_enabled").default(true)
    val vacationEnabled = bool("vacation_enabled").default(true)
    val dayoffEnabled = bool("dayoff_enabled").default(true)  // Только для admin/director
    val expenseEnabled = bool("expense_enabled").default(true)
    val payrollEnabled = bool("payroll_enabled").default(true)  // Только для admin/director
    val telegramEnabled = bool("telegram_enabled").default(false)
    val emailEnabled = bool("email_enabled").default(false)
    val email = varchar("email", 255).default("")
}

// ─────────────────────────────────────────────────────────────
// 🔐 RBAC: Роли и права доступа
// ─────────────────────────────────────────────────────────────
object RolesTable : UUIDTable("roles") {
    val name = varchar("name", 50).uniqueIndex()
    val displayName = varchar("display_name", 100)
    val description = text("description").default("")
}

object RolePermissionsTable : UUIDTable("role_permissions") {
    val roleId = reference("role_id", RolesTable)
    val permission = varchar("permission", 100)
    val canView = bool("can_view").default(true)
    val canCreate = bool("can_create").default(false)
    val canEdit = bool("can_edit").default(false)
    val canDelete = bool("can_delete").default(false)

    init {
        uniqueIndex(roleId, permission)
    }
}

object UserPermissionOverridesTable : UUIDTable("user_permission_overrides") {
    val userId = reference("user_id", UsersTable)
    val permission = varchar("permission", 100)
    val canView = bool("can_view").nullable()
    val canCreate = bool("can_create").nullable()
    val canEdit = bool("can_edit").nullable()
    val canDelete = bool("can_delete").nullable()

    init {
        uniqueIndex(userId, permission)
    }
}

// ─────────────────────────────────────────────────────────────
// 💰 PAYROLL: Зарплаты сотрудников
// ─────────────────────────────────────────────────────────────
object SalaryComponentsTable : UUIDTable("salary_components") {
    val userId = reference("user_id", UsersTable)
    val type = varchar("type", 20)  // FIXED, PIECE, HOURLY, BONUS
    val amount = double("amount").default(0.0)
    val projectId = reference("project_id", ProjectsTable).nullable()
    val ratePerHour = double("rate_per_hour").nullable()
    val ratePerUnit = double("rate_per_unit").nullable()
    val description = text("description").default("")
    val effectiveFrom = date("effective_from")
    val effectiveTo = date("effective_to").nullable()
    val isActive = bool("is_active").default(true)
}

object SalaryRecordsTable : UUIDTable("salary_records") {
    val userId = reference("user_id", UsersTable)
    val periodYear = integer("period_year")
    val periodMonth = integer("period_month")
    val fixedAmount = double("fixed_amount").default(0.0)
    val pieceAmount = double("piece_amount").default(0.0)
    val hourlyAmount = double("hourly_amount").default(0.0)
    val bonusAmount = double("bonus_amount").default(0.0)
    val totalAmount = double("total_amount").default(0.0)
    val status = varchar("status", 20).default("draft")  // draft, approved, paid
    val calculatedAt = long("calculated_at")
    val paidAt = long("paid_at").nullable()
    val notes = text("notes").default("")

    init {
        uniqueIndex(userId, periodYear, periodMonth)
    }
}


// ─────────────────────────────────────────────────────────────
// 🎫 TICKETS: Билеты на поездки/перелёты
// ─────────────────────────────────────────────────────────────
object TicketsTable : UUIDTable("tickets") {
    val uploadedBy = reference("uploaded_by", UsersTable)
    val projectId = reference("project_id", ProjectsTable)
    val fileName = varchar("file_name", 255)
    val originalName = varchar("original_name", 255)
    val filePath = varchar("file_path", 500)
    val fileType = varchar("file_type", 50)
    val fileSize = long("file_size")
    val sendToAccountant = bool("send_to_accountant").default(false)
    val accountantEmail = varchar("accountant_email", 255).default("")
    val amount = double("amount").default(0.0)        // 🆕 СТОИМОСТЬ
    val currency = varchar("currency", 3).default("RUB")  // 🆕 ВАЛЮТА
    val description = text("description").default("")
    val uploadedAt = long("uploaded_at")
    // 🆕 Поля для чека
    val receiptPath = varchar("receipt_path", 500).nullable()
    val receiptOriginalName = varchar("receipt_original_name", 255).nullable()
    val receiptFileType = varchar("receipt_file_type", 50).nullable()
}

object TicketRecipientsTable : UUIDTable("ticket_recipients") {
    val ticketId = reference("ticket_id", TicketsTable)
    val userId = reference("user_id", UsersTable)
    val viewedAt = long("viewed_at").nullable()
    val downloadedAt = long("downloaded_at").nullable()

    init {
        uniqueIndex(ticketId, userId)
    }
}

// ─────────────────────────────────────────────────────────────
// 🔐 SESSIONS: Персистентные сессии пользователей
// ─────────────────────────────────────────────────────────────
object SessionsTable : UUIDTable("sessions") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).index()
    val token = varchar("token", 255).uniqueIndex()
    val role = varchar("role", 20)
    val expiresAt = long("expires_at")
    val rememberMe = bool("remember_me").default(false)
    val createdAt = long("created_at").default(0L)
    val lastUsed = long("last_used").default(0L)
    val deviceInfo = text("device_info").default("")
}