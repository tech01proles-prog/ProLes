package com.proles.server.model

enum class UserRole(val code: String) {
    SUPER_ADMIN("super-admin"),
    ADMIN("admin"),
    DIRECTOR("director"),
    LOGIST("logist"),
    TECH("tech"),
    OFFICE("office"),
    EMPLOYEE("employee");

    companion object {
        fun fromCode(code: String): UserRole {
            val normalized = code.trim().lowercase()
            return entries.find { it.code == normalized } ?: EMPLOYEE
        }
    }

    fun isTechnicalDepartment(): Boolean =
        this == EMPLOYEE || this == TECH

    fun canViewDirectorExpenses(): Boolean =
        this == SUPER_ADMIN || this == ADMIN || this == DIRECTOR
}

enum class Permission(val key: String, val displayName: String) {
    PROJECTS("projects", "Проекты"),
    TNPA("tnpa", "ТНПА"),
    EMPLOYEES("employees", "Сотрудники"),
    PAYROLL("payroll", "Зарплата"),
    EXPENSES_ALL("expenses_all", "Все расходы"),
    DIRECTOR_EXPENSES("director_expenses", "Расходы директоров"),
    BUSINESS_TRIPS_ALL("business_trips_all", "Все командировки"),
    VACATIONS_ALL("vacations_all", "Все отпуска"),
    DAYOFFS_ALL("dayoffs_all", "Все выходные"),
    NOTIFICATIONS("notifications", "Уведомления"),
    ANALYTICS("analytics", "Аналитика"),
    COST_CALCULATION("cost_calculation", "Себестоимость"),
    TICKETS("tickets", "Билеты"),
    PERMISSIONS("permissions", "Права доступа"),
    TIMESHEET("timesheet", "Табель рабочего времени"),
    CHAT("chat", "PRO-Chat")
}

enum class ReceiptCategory(val code: String) {
    WITH_RECEIPT("WITH_RECEIPT"),
    WITHOUT_RECEIPT("WITHOUT_RECEIPT");

    companion object {
        fun normalize(value: String?): ReceiptCategory = when (
            value.orEmpty().trim().uppercase()
        ) {
            "WITHOUT_RECEIPT", "PERSONAL" -> WITHOUT_RECEIPT
            else -> WITH_RECEIPT
        }
    }
}

enum class ExpenseScope {
    GENERAL,
    DIRECTOR
}

enum class BusinessTripStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED
}

enum class BalanceTransactionType {
    WITHHOLDING,
    REPAYMENT,
    ADJUSTMENT_INCREASE,
    ADJUSTMENT_DECREASE,
    REVERSAL
}

enum class ConversationType {
    DIRECT
}