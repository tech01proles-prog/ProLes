package com.proles.server.model

enum class UserRole(val code: String) {
    ADMIN("admin"), DIRECTOR("director"), LOGIST("logist"), TECH("tech"), OFFICE("office"), EMPLOYEE("employee");
    companion object {
        fun fromCode(code: String) = entries.find { it.code == code } ?: EMPLOYEE
    }
}

enum class Permission(val key: String, val displayName: String) {
    PROJECTS("projects", "Проекты"),
    EMPLOYEES("employees", "Сотрудники"),
    PAYROLL("payroll", "Зарплата"),
    EXPENSES_ALL("expenses_all", "Все расходы"),
    BUSINESS_TRIPS_ALL("business_trips_all", "Все командировки"),
    VACATIONS_ALL("vacations_all", "Все отпуска"),
    DAYOFFS_ALL("dayoffs_all", "Все выходные"),
    NOTIFICATIONS("notifications", "Уведомления"),
    ANALYTICS("analytics", "Аналитика"),
    COST_CALCULATION("cost_calculation", "Себестоимость"),
    TICKETS("tickets", "Билеты"),
    PERMISSIONS("permissions", "Права доступа")
}