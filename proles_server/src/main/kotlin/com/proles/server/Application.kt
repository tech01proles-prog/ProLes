package com.proles.server

import com.proles.server.config.DatabaseFactory
import com.proles.server.config.EmailService
import com.proles.server.routes.authRoutes
import com.proles.server.routes.dataRoutes
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import com.proles.server.data.*
import at.favre.lib.crypto.bcrypt.BCrypt
import com.proles.server.config.FirebaseService
import io.ktor.server.http.content.staticFiles
import com.proles.server.config.TelegramService
import com.proles.server.model.Permission
import com.proles.server.services.PerDiemService
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.update
import java.io.File
import org.jetbrains.exposed.sql.deleteWhere


fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    // 🔥 Инициализация Firebase Admin
    FirebaseService.init()

    // 🆕 Инициализация Telegram Bot
    TelegramService.init(
        token = System.getenv("TELEGRAM_BOT_TOKEN"),
        chatId = System.getenv("TELEGRAM_CHAT_ID")
    )

    // 🆕 Инициализация Email сервиса (читает из переменных окружения)
    EmailService.init()

    DatabaseFactory.init(
        url = System.getenv("DB_URL") ?: "jdbc:postgresql://db:5432/prolescompany_db",
        user = System.getenv("DB_USER") ?: "proles_user",
        password = System.getenv("DB_PASS") ?: "admin"
    )

    transaction {
        SchemaUtils.createMissingTablesAndColumns(
            UsersTable, PositionsTable, ProjectsTable, TimeEntriesTable, ExpensesTable,
            IncomesTable,  // 🆕
            VacationsTable, DayOffsTable, BusinessTripsTable,
            NotificationsTable, ExpenseReceiptsTable, FcmTokensTable,
            NotificationPreferencesTable, TelegramLinksTable,  // 🆕
            RolesTable, RolePermissionsTable, UserPermissionOverridesTable,
            TicketsTable, TicketRecipientsTable,
            SalaryComponentsTable, SalaryRecordsTable, SessionsTable
        )

        // 🔥 Миграция старых вариантов типа суточных в единый PER_DIEM.
        // Обновляем только значение типа — сами расходы, суммы и связи не затрагиваются.
        exec("UPDATE expenses SET type = 'PER_DIEM' WHERE LOWER(type) IN ('per_diem', 'perdiem')")

        // 🔥 Инициализируем дефолтные роли
        initDefaultRoles()
        ensureCompanyUser()

    }

    // 📒 Запуск сервиса автоматического начисления суточных
    PerDiemService.startScheduler()

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }

    routing {
        staticFiles("/uploads", java.io.File("uploads"))
        get("/health") {
            call.respondText("{ \"status\": \"ok\", \"timestamp\": ${System.currentTimeMillis()} }")
        }
        authRoutes()
        dataRoutes()

        // 🆕 Раздача статики веб-клиента
        staticFiles("/", File("web-client/dist")) {
            default("index.html") // 🔥 Ключевой момент для SPA!
        }
    }
}

// ✅ Функция верхнего уровня (НЕ внутри module!)
private fun initDefaultRoles() {
    transaction {
        // 🎯 ТОЧНАЯ МАТРИЦА ПРАВ: permission -> V/C/E/D
        // V=canView, C=canCreate, E=canEdit, D=canDelete
        data class Actions(val v: Boolean, val c: Boolean, val e: Boolean, val d: Boolean)

        val rolesConfig = mapOf(
            "superadmin" to mapOf(
                "displayName" to "Супер-админ",
                "description" to "Полный доступ ко всему",
                "permissions" to mapOf(
                    "timesheet"             to Actions(true, true, true, true),
                    "projects"              to Actions(true, true, true, true),
                    "employees"             to Actions(true, true, true, true),
                    "payroll"               to Actions(true, true, true, true),
                    "expenses_all"          to Actions(true, true, true, true),
                    "business_trips_all"    to Actions(true, true, true, true),
                    "vacations_all"         to Actions(true, true, true, true),
                    "dayoffs_all"           to Actions(true, true, true, true),
                    "notifications"         to Actions(true, false, false, false),
                    "analytics"             to Actions(true, false, false, false),
                    "cost_calculation"      to Actions(true, true, true, true),
                    "tickets"               to Actions(true, true, true, true),
                    "permissions"           to Actions(true, true, true, true),
                )
            ),
            "admin" to mapOf(
                "displayName" to "Администратор",
                "description" to "Управление проектами, сотрудниками и финансами",
                "permissions" to mapOf(
                    "timesheet"             to Actions(true, true, true, true),
                    "projects"              to Actions(true, true, true, true),   // полный доступ к проектам
                    "employees"             to Actions(true, true, false, false), // создание/просмотр, без редактирования/удаления
                    "payroll"               to Actions(true, true, true, false),  // может считать и изменять, но не удалять
                    "expenses_all"          to Actions(true, true, false, false), // создание/просмотр всех расходов
                    "business_trips_all"    to Actions(true, true, false, false),
                    "vacations_all"         to Actions(true, true, false, false),
                    "dayoffs_all"           to Actions(true, true, false, false),
                    "notifications"         to Actions(true, false, false, false),
                    "analytics"             to Actions(true, false, false, false),
                    "cost_calculation"      to Actions(true, false, false, false),
                    "tickets"               to Actions(true, true, true, true),   // полный доступ к билетам
                    "permissions"           to Actions(true, false, true, false), // может редактировать права, но не создавать/удалять роли
                )
            ),
            "director" to mapOf(
                "displayName" to "Директор",
                "description" to "Просмотр аналитики и отчётов",
                "permissions" to mapOf(
                    "timesheet"             to Actions(true, true, true, false),
                    "projects"              to Actions(true, false, false, false),
                    "employees"             to Actions(true, false, false, false),
                    "payroll"               to Actions(true, true, true, false),  // может считать и изменять
                    "expenses_all"          to Actions(true, false, false, false),
                    "business_trips_all"    to Actions(true, false, false, false),
                    "vacations_all"         to Actions(true, false, false, false),
                    "dayoffs_all"           to Actions(true, false, false, false),
                    "notifications"         to Actions(true, false, false, false),
                    "analytics"             to Actions(true, false, false, false),
                    "cost_calculation"      to Actions(true, false, false, false),
                    "tickets"               to Actions(true, false, false, false),
                    "permissions"           to Actions(true, false, false, false),
                )
            ),
            "tech" to mapOf(
                "displayName" to "Тех. отдел",
                "description" to "Доступ к табелям и техническим разделам",
                "permissions" to mapOf(
                    "timesheet"             to Actions(true, true, true, true),
                    "projects"              to Actions(true, false, false, false),
                    "employees"             to Actions(false, false, false, false),
                    "payroll"               to Actions(false, false, false, false),
                    "expenses_all"          to Actions(false, false, false, false),
                    "business_trips_all"    to Actions(false, false, false, false),
                    "vacations_all"         to Actions(false, false, false, false),
                    "dayoffs_all"           to Actions(false, false, false, false),
                    "notifications"         to Actions(true, false, false, false),
                    "analytics"             to Actions(false, false, false, false),
                    "cost_calculation"      to Actions(false, false, false, false),
                    "tickets"               to Actions(true, true, false, false),
                    "permissions"           to Actions(false, false, false, false),
                )
            ),
            "employee" to mapOf(
                "displayName" to "Сотрудник",
                "description" to "Только свои данные",
                "permissions" to mapOf(
                    "timesheet"             to Actions(false, false, false, false),
                    "projects"              to Actions(true, false, false, false),
                    "employees"             to Actions(false, false, false, false),
                    "payroll"               to Actions(false, false, false, false),
                    "expenses_all"          to Actions(false, false, false, false),
                    "business_trips_all"    to Actions(false, false, false, false),
                    "vacations_all"         to Actions(false, false, false, false),
                    "dayoffs_all"           to Actions(false, false, false, false),
                    "notifications"         to Actions(true, false, false, false),
                    "analytics"             to Actions(false, false, false, false),
                    "cost_calculation"      to Actions(false, false, false, false),
                    "tickets"               to Actions(true, true, false, false),  // может создавать свои билеты
                    "permissions"           to Actions(false, false, false, false),
                )
            )
        )

        // Создаём или обновляем роли
        rolesConfig.forEach { (roleName, config) ->
            @Suppress("UNCHECKED_CAST")
            val displayName = config["displayName"] as String
            @Suppress("UNCHECKED_CAST")
            val description = config["description"] as String
            @Suppress("UNCHECKED_CAST")
            val permissions = config["permissions"] as Map<String, Actions>

            val roleId = RolesTable.selectAll()
                .where { RolesTable.name eq roleName }
                .singleOrNull()?.get(RolesTable.id)?.value
                ?: run {
                    val id = UUID.randomUUID()
                    RolesTable.insert {
                        it[RolesTable.id] = id
                        it[RolesTable.name] = roleName
                        it[RolesTable.displayName] = displayName
                        it[RolesTable.description] = description
                    }
                    id
                }

            // 🔄 Обновляем ВСЕ права (не только новые) — миграция
            permissions.forEach { (perm, actions) ->
                val existing = RolePermissionsTable.selectAll()
                    .where {
                        (RolePermissionsTable.roleId eq roleId) and
                                (RolePermissionsTable.permission eq perm)
                    }.singleOrNull()

                if (existing != null) {
                    // 🔄 ОБНОВЛЯЕМ существующую запись
                    RolePermissionsTable.update({
                        (RolePermissionsTable.roleId eq roleId) and
                                (RolePermissionsTable.permission eq perm)
                    }) {
                        it[canView] = actions.v
                        it[canCreate] = actions.c
                        it[canEdit] = actions.e
                        it[canDelete] = actions.d
                    }
                } else {
                    // ➕ Создаём новую запись
                    RolePermissionsTable.insert {
                        it[RolePermissionsTable.id] = UUID.randomUUID()
                        it[RolePermissionsTable.roleId] = roleId
                        it[RolePermissionsTable.permission] = perm
                        it[canView] = actions.v
                        it[canCreate] = actions.c
                        it[canEdit] = actions.e
                        it[canDelete] = actions.d
                    }
                }
            }
        }

        // Удаляем лишние permissions, которых нет в конфиге
        val validPermissions = rolesConfig.values
            .flatMap { config ->
                @Suppress("UNCHECKED_CAST")
                (config["permissions"] as Map<String, Actions>).keys
            }
            .toSet()

        // Находим все уникальные права в БД и удаляем те, которых нет в валидном списке
        val dbPermissions = RolePermissionsTable.selectAll()
            .map { it[RolePermissionsTable.permission] }
            .distinct()
        val permissionsToDelete = dbPermissions.filter { it !in validPermissions }

        // Удаляем устаревшие permissions по одной
        permissionsToDelete.forEach { perm ->
            RolePermissionsTable.deleteWhere {
                RolePermissionsTable.permission eq perm
            }
        }
    }

    // Сбрасываем весь кеш прав после обновления
    com.proles.server.config.PermissionMiddleware.invalidateAllCache()
    println("✅ Дефолтные роли инициализированы/обновлены с точной матрицей прав")
}
private fun ensureCompanyUser() {
    transaction {
        val exists = UsersTable.selectAll().where { UsersTable.login eq "proles_company" }.singleOrNull()
        if (exists == null) {
            UsersTable.insert {
                it[id] = UUID.randomUUID()
                it[email] = "proles.company@proles.local"
                it[login] = "proles_company"
                it[lastName] = "Proles"
                it[firstName] = "Company"
                it[name] = "Proles Company"
                it[passwordHash] = BCrypt.withDefaults().hashToString(12, UUID.randomUUID().toString().toCharArray())
                it[role] = "employee"
                it[position] = "Системный пользователь"
            }
            println("✅ Создан системный пользователь расходов: Proles Company")
        } else if (exists[UsersTable.name] != "Proles Company") {
            UsersTable.update({ UsersTable.login eq "proles_company" }) { it[name] = "Proles Company"; it[lastName] = "Proles"; it[firstName] = "Company" }
        }
    }
}
