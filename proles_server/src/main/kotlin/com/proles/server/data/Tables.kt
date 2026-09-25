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
    val phone = varchar("phone", 30).default("")
    val telegramUsername = varchar("telegram_username", 100).default("")
    val birthDate = date("birth_date").nullable()
    val positionId = reference("position_id", PositionsTable).nullable()
    val isRemote = bool("is_remote").default(false)
}

object PositionsTable : UUIDTable("positions") {
    val name = varchar("name", 150).uniqueIndex()
    val parentId = uuid("parent_id").nullable()
    val isActive = bool("is_active").default(true)
    val sortOrder = integer("sort_order").default(0)
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
    val materials = double("materials").default(0.0)
    val contractors = double("contractors").default(0.0)
    val creditPercent = double("credit_percent").default(0.0)

    // 🔥 Мета-поля
    val lead = varchar("lead", 150).default("")
    val projectCode = varchar("project_code", 50).default("")
    val customer = varchar("customer", 255).default("")
    val completionDate = date("completion_date").nullable()
}

object SubprojectsTable : UUIDTable("subprojects") {
    val projectId = reference(
        "project_id",
        ProjectsTable,
        onDelete = ReferenceOption.CASCADE,
        onUpdate = ReferenceOption.CASCADE
    ).index()
    val name = varchar("name", 255)
    val code = varchar("code", 100).default("")
    val description = text("description").default("")
    val isActive = bool("is_active").default(true)
    val sortOrder = integer("sort_order").default(0)
    val createdAt = long("created_at").default(0L)
    val updatedAt = long("updated_at").default(0L)
    val archivedAt = long("archived_at").nullable()

    init {
        uniqueIndex(
            "subprojects_project_name_uidx",
            projectId,
            name
        )
        index(
            "subprojects_project_active_idx",
            false,
            projectId,
            isActive,
            sortOrder
        )
    }
}

object PersonalTimesheetCategoriesTable : UUIDTable("personal_timesheet_categories") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).index()
    val name = varchar("name", 100)

    init {
        uniqueIndex("personal_timesheet_category_user_name_uidx", userId, name)
    }
}

object PersonalTimesheetTasksTable : UUIDTable("personal_timesheet_tasks") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).index()
    val year = integer("year").index()
    val month = integer("month").index()
    val periodStart = date("period_start")
    val periodEnd = date("period_end")
    val name = varchar("name", 255).default("")
    val description = text("description").default("")
    val hours = double("hours").default(0.0)
    val category = varchar("category", 100).default("")
    val status = varchar("status", 50).default("Not started")
    val isMonthTask = bool("is_month_task").default(false)
    val sortOrder = integer("sort_order").default(0)

    init {
        index("personal_timesheet_period_idx", false, userId, year, month)
    }
}

object TimeEntriesTable : UUIDTable("time_entries") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).index()
    val projectId = reference("project_id", ProjectsTable, onDelete = ReferenceOption.CASCADE)
    val subprojectId = reference("subproject_id", SubprojectsTable, onDelete = ReferenceOption.SET_NULL).nullable().index()
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
    val subprojectId = reference("subproject_id", SubprojectsTable, onDelete = ReferenceOption.SET_NULL).nullable().index()
    val date = date("date").index()
    val type = varchar("type", 50)
    val name = varchar("name", 255).default("")
    val amount = double("amount").default(0.0)
    val currency = varchar("currency", 3).default("RUB")
    val comment = text("comment").default("")
    val receiptSubmitted = bool("receipt_submitted").default(false)
    val hasReceiptPhoto = bool("has_receipt_photo").default(false)
    val category = varchar("category", 20).default("WITH_RECEIPT")
    val subcategory = varchar("subcategory", 50).nullable()  // 🆕 Подкатегория типа расхода
    val createdAt = long("created_at").default(0L)
    val expenseScope = varchar("expense_scope", 32).default("GENERAL").index()
    val creatorRole = varchar("creator_role", 20).default("")
    val deletedAt = long("deleted_at").nullable().index()
    val deletedBy = reference(
        "deleted_by",
        UsersTable,
        onDelete = ReferenceOption.SET_NULL
    ).nullable()
}

// ─────────────────────────────────────────────────────────────
// 💵 INCOMES: Доходы (аналог расходов, без чеков)
// ─────────────────────────────────────────────────────────────
object IncomesTable : UUIDTable("incomes") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).index()
    val projectId = reference("project_id", ProjectsTable, onDelete = ReferenceOption.CASCADE).nullable()
    val subprojectId = reference("subproject_id", SubprojectsTable, onDelete = ReferenceOption.SET_NULL).nullable().index()
    val date = date("date").index()
    val type = varchar("type", 50).default("")  // 🆕 Тип дохода: HOUSEHOLD, CARD, CASH
    val name = varchar("name", 255).default("")
    val amount = double("amount").default(0.0)
    val currency = varchar("currency", 3).default("RUB")
    val category = varchar("category", 20).default("WITH_RECEIPT")
    val subcategory = varchar("subcategory", 50).nullable()  // 🆕 Подкатегория типа расхода
    val createdAt = long("created_at").default(0L)
}

object VacationsTable : UUIDTable("vacations") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).index()
    val start = date("start_date")
    val end = date("end_date")
    val status = varchar("status", 20).default("PENDING")
    val approvedBy = reference("approved_by", UsersTable).nullable()
    val approvedAt = long("approved_at").nullable()
    val rejectionReason = text("rejection_reason").default("")
}

object DayOffsTable : UUIDTable("day_offs") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).index()
    val date = date("date").index()
    init { uniqueIndex("unique_user_date", userId, date) }
}

// 🆕 Таблица командировок
object BusinessTripsTable : UUIDTable("business_trips") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).index()
    val projectId = reference("project_id", ProjectsTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val subprojectId = reference("subproject_id", SubprojectsTable, onDelete = ReferenceOption.SET_NULL).nullable().index()
    val projectNumber = varchar("project_number", 100).default("")
    val companyName = varchar("company_name", 255).default("")
    val country = varchar("country", 100).default("")
    val type = varchar("type", 20).default("DEPARTURE")
    val date = date("date")
    val completedDate = date("completed_date").nullable()
    val startDate = date("start_date").nullable()
    val endDate = date("end_date").nullable()
    val status = varchar("status", 20).default("ACTIVE").index()
    val city = varchar("city", 255).default("")
    val participants = text("participants").default("[]")
    val transport = varchar("transport", 100).default("")
    val notes = text("notes").default("")
    val waypoints = text("waypoints").default("[]")  // 🆕 JSON массив пунктов следования
    val perDiemRate = double("per_diem_rate").default(750.0)  // 🆕 Размер суточных
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
object TelegramLinksTable : UUIDTable("telegram_links") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val chatId = varchar("chat_id", 100).uniqueIndex()
    val username = varchar("username", 100).default("")
    val linkedAt = long("linked_at").default(0L)
}

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
    val dayoffEnabled = bool("dayoff_enabled").default(true)
    val expenseEnabled = bool("expense_enabled").default(true)
    val payrollEnabled = bool("payroll_enabled").default(true)
    val ticketEnabled = bool("ticket_enabled").default(true)
    val tripVisibleToAll = bool("trip_visible_to_all").default(true)
    val tripTelegramBroadcast = bool("trip_telegram_broadcast").default(true)
    val tripChangeEnabled = bool("trip_change_enabled").default(true)
    val vacationDecisionEnabled = bool("vacation_decision_enabled").default(true)
    val expenseCreatedEnabled = bool("expense_created_enabled").default(true)
    val ticketReceiptEnabled = bool("ticket_receipt_enabled").default(true)
    val privacyDefaultsConfigured = bool("privacy_defaults_configured").default(false)
    val telegramEnabled = bool("telegram_enabled").default(false)
    val telegramLinkCode = varchar("telegram_link_code", 20).uniqueIndex().nullable()
    val telegramLinkedAt = long("telegram_linked_at").nullable()
    val telegramChatId = varchar("telegram_chat_id", 100).nullable().uniqueIndex()
    val telegramUsername = varchar("telegram_linked_username", 100).default("")
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
    val subprojectId = reference("subproject_id", SubprojectsTable, onDelete = ReferenceOption.SET_NULL).nullable().index()
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

    val penaltyAmount = double("penalty_amount").default(0.0)
    val withholdingAmount = double("withholding_amount").default(0.0)
    val withholdingRepaymentAmount = double("withholding_repayment_amount").default(0.0)
    val grossAmount = double("gross_amount").default(0.0)
    val taxInclusiveCost = double("tax_inclusive_cost").default(0.0)
    val remoteEmployee = bool("remote_employee").default(false)

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
    val subprojectId = reference("subproject_id", SubprojectsTable, onDelete = ReferenceOption.SET_NULL).nullable().index()
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

object TnpaDocumentsTable : UUIDTable("tnpa_documents") {
    val projectId = reference(
        "project_id",
        ProjectsTable,
        onDelete = ReferenceOption.CASCADE
    ).index()
    val subprojectId = reference(
        "subproject_id",
        SubprojectsTable,
        onDelete = ReferenceOption.SET_NULL
    ).nullable().index()
    val originalName = varchar("original_name", 500)
    val storedName = varchar("stored_name", 500)
    val storagePath = text("storage_path")
    val mimeType = varchar("mime_type", 255)
        .default("application/octet-stream")
    val sizeBytes = long("size_bytes")
    val checksumSha256 = char("checksum_sha256", 64)
    val description = text("description").default("")
    val uploadedBy = reference(
        "uploaded_by",
        UsersTable,
        onDelete = ReferenceOption.RESTRICT
    )
    val uploadedAt = long("uploaded_at")
    val deletedAt = long("deleted_at").nullable()
    val deletedBy = reference(
        "deleted_by",
        UsersTable,
        onDelete = ReferenceOption.SET_NULL
    ).nullable()
}

object EmployeeBalanceTransactionsTable :
    UUIDTable("employee_balance_transactions") {

    val userId = reference(
        "user_id",
        UsersTable,
        onDelete = ReferenceOption.RESTRICT
    ).index()
    val salaryRecordId = reference(
        "salary_record_id",
        SalaryRecordsTable,
        onDelete = ReferenceOption.RESTRICT
    ).nullable().index()
    val transactionType = varchar("transaction_type", 32)
    val amount = decimal("amount", 14, 2)
    val comment = text("comment").default("")
    val createdBy = reference(
        "created_by",
        UsersTable,
        onDelete = ReferenceOption.RESTRICT
    )
    val createdAt = long("created_at").index()
    val reversedTransactionId = uuid("reversed_transaction_id").nullable()
    val idempotencyKey = varchar("idempotency_key", 100)
        .nullable()
        .uniqueIndex()
}

object ChatConversationsTable : UUIDTable("chat_conversations") {
    val conversationType =
        varchar("conversation_type", 20).default("DIRECT")
    val directKey = varchar("direct_key", 80).nullable().uniqueIndex()
    val createdBy = reference(
        "created_by",
        UsersTable,
        onDelete = ReferenceOption.RESTRICT
    )
    val createdAt = long("created_at")
    val updatedAt = long("updated_at").index()
    val lastMessageId = uuid("last_message_id").nullable()
}

object ChatConversationMembersTable :
    UUIDTable("chat_conversation_members") {

    val conversationId = reference(
        "conversation_id",
        ChatConversationsTable,
        onDelete = ReferenceOption.CASCADE
    ).index()
    val userId = reference(
        "user_id",
        UsersTable,
        onDelete = ReferenceOption.CASCADE
    ).index()
    val joinedAt = long("joined_at")
    val lastReadMessageId = uuid("last_read_message_id").nullable()
    val lastReadAt = long("last_read_at").nullable()
    val isArchived = bool("is_archived").default(false)

    init {
        uniqueIndex(
            "chat_member_conversation_user_uidx",
            conversationId,
            userId
        )
    }
}

object ChatMessagesTable : UUIDTable("chat_messages") {
    val conversationId = reference(
        "conversation_id",
        ChatConversationsTable,
        onDelete = ReferenceOption.CASCADE
    ).index()
    val senderId = reference(
        "sender_id",
        UsersTable,
        onDelete = ReferenceOption.RESTRICT
    ).index()
    val bodyCiphertext = text("body_ciphertext").default("")
    val bodyIv = varchar("body_iv", 64).default("")
    val bodyKeyVersion = integer("body_key_version").default(1)
    val clientMessageId = varchar("client_message_id", 100)
    val replyToMessageId = uuid("reply_to_message_id").nullable()
    val createdAt = long("created_at").index()
    val editedAt = long("edited_at").nullable()
    val deletedAt = long("deleted_at").nullable()

    init {
        uniqueIndex(
            "chat_message_sender_client_uidx",
            senderId,
            clientMessageId
        )
    }
}

object ChatAttachmentsTable : UUIDTable("chat_attachments") {
    val messageId = reference(
        "message_id",
        ChatMessagesTable,
        onDelete = ReferenceOption.CASCADE
    ).index()
    val originalName = varchar("original_name", 500)
    val storedName = varchar("stored_name", 500)
    val storagePath = text("storage_path")
    val mimeType = varchar("mime_type", 255)
        .default("application/octet-stream")
    val sizeBytes = long("size_bytes")
    val checksumSha256 = char("checksum_sha256", 64)
    val encryptionIv = varchar("encryption_iv", 64)
    val encryptionKeyVersion = integer("encryption_key_version").default(1)
    val createdAt = long("created_at")
}