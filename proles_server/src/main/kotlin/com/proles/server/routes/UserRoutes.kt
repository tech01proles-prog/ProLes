package com.proles.server.routes

import at.favre.lib.crypto.bcrypt.BCrypt
import com.proles.server.config.PermissionMiddleware.checkPermission
import com.proles.server.config.SessionManager
import com.proles.server.data.*
import com.proles.server.model.Permission
import com.proles.server.model.UserDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDate as KtLocalDate
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

private suspend fun ApplicationCall.checkUserSession():
    SessionManager.Session? {
    val token = request.headers["X-Session-Token"]
    val session = SessionManager.validate(token)

    if (session == null) {
        respond(HttpStatusCode.Unauthorized, "Session expired")
        return null
    }

    return session
}

internal fun Route.userRoutes() {
    // ─────────────────────────────────────────────────────────────
    // 👥 USERS
    // ─────────────────────────────────────────────────────────────
    route("/api/v1/users") {
        get {
            if (!call.checkPermission(Permission.EMPLOYEES, "view")) return@get
            val users = transaction {
                val today = KtLocalDate.parse(java.time.LocalDate.now().toString())
                val vacationUserIds = VacationsTable.selectAll().where {
                    (VacationsTable.status eq "APPROVED") and
                    (VacationsTable.start lessEq today) and
                    (VacationsTable.end greaterEq today)
                }.map { it[VacationsTable.userId].value }.toSet()
                UsersTable.selectAll()
                    .orderBy(UsersTable.lastName to SortOrder.ASC)
                    .map { row ->
                        UserDto(
                            id = row[UsersTable.id].value.toString(),
                            lastName = row[UsersTable.lastName],
                            firstName = row[UsersTable.firstName],
                            middleName = row[UsersTable.middleName],
                            name = row[UsersTable.name],
                            login = row[UsersTable.login],
                            email = row[UsersTable.email],
                            role = row[UsersTable.role],
                            position = row[UsersTable.position] ?: "",
                            defaultRateType = row[UsersTable.defaultRateType],
                            defaultRate = row[UsersTable.defaultRate],
                            defaultCurrency = row[UsersTable.defaultCurrency],
                            phone = row[UsersTable.phone],
                            telegramUsername = row[UsersTable.telegramUsername],
                            birthDate = row[UsersTable.birthDate]?.toString(),
                            positionId = row[UsersTable.positionId]?.value?.toString(),
                            onVacation = row[UsersTable.id].value in vacationUserIds
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, users)
        }

        post {
            if (!call.checkPermission(Permission.EMPLOYEES, "create")) return@post
            val user = try {
                call.receive<UserDto>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON"); return@post
            }
            val created = transaction {
                val hash = BCrypt.withDefaults().hashToString(12, (user.newPassword ?: "password").toCharArray())
                UsersTable.insert {
                    it[id] = UUID.fromString(user.id)
                    it[email] = user.email.ifBlank { user.login + "@proles.local" }
                    it[login] = user.login
                    it[lastName] = user.lastName
                    it[firstName] = user.firstName
                    it[middleName] = user.middleName
                    it[name] = listOf(user.lastName, user.firstName, user.middleName).filter { s -> s.isNotBlank() }.joinToString(" ")
                    it[passwordHash] = hash
                    it[role] = user.role
                    it[position] = user.position
                    it[positionId] = user.positionId?.let(UUID::fromString)
                    it[UsersTable.isRemote] = user.isRemote
                    it[defaultRateType] = user.defaultRateType
                    it[defaultRate] = user.defaultRate
                    it[defaultCurrency] = user.defaultCurrency
                    it[phone] = user.phone
                    it[telegramUsername] = user.telegramUsername
                    it[birthDate] = user.birthDate?.let(KtLocalDate::parse)
                }
                user
            }
            call.respond(HttpStatusCode.Created, created)
        }

        put("/profile") {
            val session = call.checkUserSession() ?: return@put
            val body = try { call.receive<UserDto>() } catch (e: Exception) { return@put call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}") }
            val updated = transaction {
                UsersTable.update({ UsersTable.id eq session.userId }) {
                    it[lastName] = body.lastName
                    it[firstName] = body.firstName
                    it[middleName] = body.middleName
                    it[name] = listOf(body.lastName, body.firstName, body.middleName).filter { x -> x.isNotBlank() }.joinToString(" ")
                    it[email] = body.email
                    it[phone] = body.phone
                    it[telegramUsername] = body.telegramUsername
                    it[birthDate] = body.birthDate?.let(KtLocalDate::parse)
                }
                body.copy(id = session.userId.toString())
            }
            call.respond(HttpStatusCode.OK, updated)
        }

        put {
            if (!call.checkPermission(Permission.EMPLOYEES, "edit")) return@put
            val user = try {
                call.receive<UserDto>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON"); return@put
            }
            val userId = UUID.fromString(user.id)
            val updated = transaction {
                val existing = UsersTable.selectAll().where { UsersTable.id eq userId }.single()
                val hash = if (user.newPassword.isNullOrBlank()) existing[UsersTable.passwordHash]
                else BCrypt.withDefaults().hashToString(12, user.newPassword.toCharArray())
                UsersTable.update({ UsersTable.id eq userId }) {
                    it[login] = user.login
                    it[email] = user.email
                    it[lastName] = user.lastName
                    it[firstName] = user.firstName
                    it[middleName] = user.middleName
                    it[name] = listOf(user.lastName, user.firstName, user.middleName).filter { s -> s.isNotBlank() }.joinToString(" ")
                    it[passwordHash] = hash
                    it[role] = user.role
                    it[position] = user.position
                    it[positionId] = user.positionId?.let(UUID::fromString)
                    it[UsersTable.isRemote] = user.isRemote
                    it[defaultRateType] = user.defaultRateType
                    it[defaultRate] = user.defaultRate
                    it[defaultCurrency] = user.defaultCurrency
                    it[phone] = user.phone
                    it[telegramUsername] = user.telegramUsername
                    it[birthDate] = user.birthDate?.let(KtLocalDate::parse)
                }
                user
            }
            call.respond(HttpStatusCode.OK, updated)
        }

        // 🗑 Удаление сотрудника (только для имеющих право employees.delete)
        delete {
            if (!call.checkPermission(Permission.EMPLOYEES, "delete")) return@delete
            val userId = call.request.queryParameters["userId"]
            if (userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "userId required"); return@delete
            }
            val userUuid = runCatching { UUID.fromString(userId) }.getOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid userId")

            // Запрещаем удалять самого себя
            val session = call.checkUserSession() ?: return@delete
            if (session.userId == userUuid) {
                return@delete call.respond(HttpStatusCode.Forbidden, "Нельзя удалить самого себя")
            }

            // Запрещаем удалять superadmin
            val targetRole = transaction {
                UsersTable.selectAll().where { UsersTable.id eq userUuid }
                    .singleOrNull()?.get(UsersTable.role)
            }
            if (targetRole == "superadmin") {
                return@delete call.respond(HttpStatusCode.Forbidden, "Нельзя удалить superadmin")
            }

            transaction {
                com.proles.server.config.SessionManager.invalidateAllForUser(userUuid)
                // Удаляем связанные данные (каскадно через FK, но для надежности явно)
                TimeEntriesTable.deleteWhere { TimeEntriesTable.userId eq userUuid }
                ExpensesTable.deleteWhere { ExpensesTable.userId eq userUuid }
                BusinessTripsTable.deleteWhere { BusinessTripsTable.userId eq userUuid }
                VacationsTable.deleteWhere { VacationsTable.userId eq userUuid }
                DayOffsTable.deleteWhere { DayOffsTable.userId eq userUuid }
                NotificationsTable.deleteWhere {
                    (NotificationsTable.senderUserId eq userUuid) or
                            (NotificationsTable.targetUserId eq userUuid)
                }
                FcmTokensTable.deleteWhere { FcmTokensTable.userId eq userUuid }
                UserPermissionOverridesTable.deleteWhere { UserPermissionOverridesTable.userId eq userUuid }
                SalaryComponentsTable.deleteWhere { SalaryComponentsTable.userId eq userUuid }
                SalaryRecordsTable.deleteWhere { SalaryRecordsTable.userId eq userUuid }
                // Сам пользователь
                UsersTable.deleteWhere { UsersTable.id eq userUuid }
            }
            println("✅ User $userId deleted with all related data")
            call.respond(HttpStatusCode.NoContent)
        }

    }
}