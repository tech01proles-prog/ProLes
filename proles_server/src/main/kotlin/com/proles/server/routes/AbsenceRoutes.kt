package com.proles.server.routes

import com.proles.server.config.NotificationService
import com.proles.server.config.PermissionMiddleware
import com.proles.server.config.PermissionMiddleware.checkPermission
import com.proles.server.config.SessionManager
import com.proles.server.data.*
import com.proles.server.model.DayOffRequest
import com.proles.server.model.Permission
import com.proles.server.model.VacationDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDate as KtLocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

private val absenceJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private suspend fun ApplicationCall.checkAbsenceSession():
    SessionManager.Session? {
    val token = request.headers["X-Session-Token"]
    val session = SessionManager.validate(token)

    if (session == null) {
        respond(HttpStatusCode.Unauthorized, "Session expired")
        return null
    }

    return session
}

internal fun Route.absenceRoutes() {
    vacationRoutes()
    dayOffRoutes()
}

private fun Route.vacationRoutes() {
    route("/api/v1/vacations") {
        get("/all") {
            if (!call.checkPermission(Permission.VACATIONS_ALL, "view")) return@get
            val yearParam = call.request.queryParameters["year"]?.toIntOrNull()
            val rows = transaction {
                var q = VacationsTable.selectAll()
                if (yearParam != null) {
                    val from = KtLocalDate(yearParam, 1, 1)
                    val to = KtLocalDate(yearParam, 12, 31)
                    q = q.where { (VacationsTable.start lessEq to) and (VacationsTable.end greaterEq from) }
                }
                q.orderBy(VacationsTable.start to SortOrder.ASC).map { row ->
                    VacationDto(
                        id = row[VacationsTable.id].value.toString(),
                        userId = row[VacationsTable.userId].value.toString(),
                        start = row[VacationsTable.start].toString(),
                        end = row[VacationsTable.end].toString(),
                        status = row[VacationsTable.status],
                        approvedBy = row[VacationsTable.approvedBy]?.value?.toString(),
                        approvedAt = row[VacationsTable.approvedAt],
                        rejectionReason = row[VacationsTable.rejectionReason]
                    )
                }
            }
            call.respond(HttpStatusCode.OK, rows)
        }

        get {
            val session = call.checkAbsenceSession() ?: return@get
            val requested = call.request.queryParameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: session.userId
            val canViewAll = PermissionMiddleware.canView(session.userId, Permission.VACATIONS_ALL)
            if (requested != session.userId && !canViewAll) return@get call.respond(HttpStatusCode.Forbidden, "Можно просматривать только свои отпуска")
            val rows = transaction {
                VacationsTable.selectAll().where { VacationsTable.userId eq requested }
                    .orderBy(VacationsTable.start to SortOrder.ASC).map { row ->
                        VacationDto(
                            id = row[VacationsTable.id].value.toString(), userId = row[VacationsTable.userId].value.toString(),
                            start = row[VacationsTable.start].toString(), end = row[VacationsTable.end].toString(),
                            status = row[VacationsTable.status], approvedBy = row[VacationsTable.approvedBy]?.value?.toString(),
                            approvedAt = row[VacationsTable.approvedAt], rejectionReason = row[VacationsTable.rejectionReason]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, rows)
        }

        post {
            val session = call.checkAbsenceSession() ?: return@post
            val vacation = try { call.receive<VacationDto>() } catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}") }
            val userId = runCatching { UUID.fromString(vacation.userId) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid userId")
            val canCreateAll = PermissionMiddleware.canCreate(session.userId, Permission.VACATIONS_ALL)
            if (userId != session.userId && !canCreateAll) return@post call.respond(HttpStatusCode.Forbidden, "Можно оформлять отпуск только для себя")
            val startDate = runCatching { KtLocalDate.parse(vacation.start) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid start")
            val endDate = runCatching { KtLocalDate.parse(vacation.end) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid end")
            if (endDate < startDate) return@post call.respond(HttpStatusCode.BadRequest, "End date cannot be before start date")
            val id = UUID.randomUUID()
            transaction {
                VacationsTable.insert {
                    it[VacationsTable.id] = id
                    it[VacationsTable.userId] = userId
                    it[start] = startDate
                    it[end] = endDate
                    it[status] = "PENDING"
                    it[rejectionReason] = ""
                }
            }
            val employeeName = transaction { UsersTable.selectAll().where { UsersTable.id eq userId }.single()[UsersTable.name] }
            val approvers = transaction {
                UsersTable.selectAll().where {
                    (UsersTable.role eq "superadmin") or (UsersTable.role eq "director") or (UsersTable.role eq "admin")
                }.map { it[UsersTable.id].value }
            }
            val days = endDate.toEpochDays() - startDate.toEpochDays() + 1
            val payload = absenceJson.encodeToString(mapOf("vacationId" to id.toString(), "userId" to userId.toString()))
            NotificationService.notifyUsers(
                approvers, session.userId, "VACATION_REQUEST", "🏖 Новый запрос на отпуск",
                "👤 $employeeName\n📅 ${startDate} — ${endDate}\n📊 $days дн.\n⏳ Требуется подтверждение",
                payload, "vacation"
            )
            call.respond(HttpStatusCode.Created, VacationDto(id.toString(), userId.toString(), startDate.toString(), endDate.toString(), "PENDING", null, null, ""))
        }

        post("/{vacationId}/decision") {
            val session = call.checkAbsenceSession() ?: return@post
            if (session.role !in listOf("superadmin", "director", "admin")) return@post call.respond(HttpStatusCode.Forbidden, "Подтвердить отпуск может руководитель или superadmin")
            val id = runCatching { UUID.fromString(call.parameters["vacationId"]) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid vacationId")
            val body = try { call.receive<Map<String, String>>() } catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON") }
            val approved = body["approved"]?.toBooleanStrictOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, "approved must be true/false")
            val reason = body["reason"].orEmpty()
            val updated = transaction {
                VacationsTable.update({ VacationsTable.id eq id and (VacationsTable.status eq "PENDING") }) {
                    it[status] = if (approved) "APPROVED" else "REJECTED"
                    it[approvedBy] = if (approved) session.userId else null
                    it[approvedAt] = System.currentTimeMillis()
                    it[rejectionReason] = if (approved) "" else reason
                }
            }
            if (updated == 0) return@post call.respond(HttpStatusCode.Conflict, "Отпуск уже обработан")
            val vacation = transaction { VacationsTable.selectAll().where { VacationsTable.id eq id }.single() }
            val employeeId = vacation[VacationsTable.userId].value
            val employeeName = transaction { UsersTable.selectAll().where { UsersTable.id eq employeeId }.single()[UsersTable.name] }
            val title = if (approved) "✅ Отпуск подтверждён" else "❌ Отпуск отклонён"
            val msg = "👤 $employeeName\n📅 ${vacation[VacationsTable.start]} — ${vacation[VacationsTable.end]}\n${if (!approved && reason.isNotBlank()) "📝 Причина: $reason" else ""}".trim()
            NotificationService.notifyUsers(listOf(employeeId), session.userId, "VACATION_DECISION", title, msg, absenceJson.encodeToString(mapOf("vacationId" to id.toString(), "approved" to approved)), "vacationDecision")
            call.respond(HttpStatusCode.OK, VacationDto(
                id.toString(), employeeId.toString(), vacation[VacationsTable.start].toString(), vacation[VacationsTable.end].toString(),
                vacation[VacationsTable.status], vacation[VacationsTable.approvedBy]?.value?.toString(), vacation[VacationsTable.approvedAt], vacation[VacationsTable.rejectionReason]
            ))
        }

        delete {
            val session = call.checkAbsenceSession() ?: return@delete
            val id = call.request.queryParameters["vacationId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "vacationId required")
            val row = transaction { VacationsTable.selectAll().where { VacationsTable.id eq id }.singleOrNull() }
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            if (row[VacationsTable.userId].value != session.userId && session.role !in listOf("superadmin", "director", "admin")) return@delete call.respond(HttpStatusCode.Forbidden)
            transaction { VacationsTable.deleteWhere { VacationsTable.id eq id } }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Route.dayOffRoutes() {
    route("/api/v1/dayoffs") {
        get("/all") {
            if (!call.checkPermission(Permission.DAYOFFS_ALL, "view")) return@get
            val dayOffs = transaction {
                (DayOffsTable innerJoin UsersTable)
                    .selectAll()
                    .orderBy(DayOffsTable.date to SortOrder.DESC)
                    .map { row ->
                        DayOffRequest(
                            user_id = row[DayOffsTable.userId].value.toString(),
                            date = row[DayOffsTable.date].toString()
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, dayOffs)
        }

        get {
            if (call.checkAbsenceSession() == null) return@get
            val userIdParam = call.request.queryParameters["userId"]
            if (userIdParam.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "userId parameter required")
                return@get
            }
            val dayOffs = transaction {
                DayOffsTable.selectAll()
                    .where { DayOffsTable.userId eq UUID.fromString(userIdParam) }
                    .orderBy(DayOffsTable.date to SortOrder.ASC)
                    .map { row ->
                        DayOffRequest(
                            user_id = row[DayOffsTable.userId].value.toString(),
                            date = row[DayOffsTable.date].toString()
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, dayOffs)
        }

        post {
            if (call.checkAbsenceSession() == null) return@post
            val request = try {
                call.receive<DayOffRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
                return@post
            }

            val userId = UUID.fromString(request.user_id)
            val date = KtLocalDate.parse(request.date)

            // 🔥 Проверяем, будний ли это день (пн-пт)
            val dayOfWeek = date.dayOfWeek
            val isWeekday = dayOfWeek != kotlinx.datetime.DayOfWeek.SATURDAY &&
                    dayOfWeek != kotlinx.datetime.DayOfWeek.SUNDAY

            // Форматируем дату
            val formattedDate = "%02d.%02d.%04d".format(date.dayOfMonth, date.monthNumber, date.year)

            // Название дня недели на русском
            val dayOfWeekRu = when (dayOfWeek) {
                kotlinx.datetime.DayOfWeek.MONDAY -> "Понедельник"
                kotlinx.datetime.DayOfWeek.TUESDAY -> "Вторник"
                kotlinx.datetime.DayOfWeek.WEDNESDAY -> "Среда"
                kotlinx.datetime.DayOfWeek.THURSDAY -> "Четверг"
                kotlinx.datetime.DayOfWeek.FRIDAY -> "Пятница"
                kotlinx.datetime.DayOfWeek.SATURDAY -> "Суббота"
                kotlinx.datetime.DayOfWeek.SUNDAY -> "Воскресенье"
                else -> ""
            }

            val status = transaction {
                try {
                    DayOffsTable.insert {
                        it[DayOffsTable.id] = UUID.randomUUID()
                        it[DayOffsTable.userId] = userId
                        it[DayOffsTable.date] = date
                    }
                    HttpStatusCode.Created
                } catch (_: org.jetbrains.exposed.exceptions.ExposedSQLException) {
                    HttpStatusCode.Conflict
                }
            }

            // 🔥 Если выходной создан в БУДНИЙ день — отправляем уведомления админам
            if (status == HttpStatusCode.Created && isWeekday) {
                val session = call.checkAbsenceSession()!!
                val senderName = transaction {
                    UsersTable.selectAll()
                        .where { UsersTable.id eq userId }
                        .single()[UsersTable.name]
                }

                val notifTitle = "🌞 Выходной в будний день"
                val notifMessage = buildString {
                    appendLine("👤 $senderName")
                    appendLine("📅 Дата: $formattedDate ($dayOfWeekRu)")
                    appendLine("⚠️ Сотрудник взял выходной в рабочий день")
                }.trim()

                val notifPayload = absenceJson.encodeToString(mapOf(
                    "userId" to userId.toString(),
                    "date" to request.date,
                    "type" to "DAYOFF_WEEKDAY"
                ))

                // Получаем ID админов (кроме самого сотрудника)
                val adminIds = transaction {
                    UsersTable.selectAll()
                        .where { (UsersTable.role eq "admin") or (UsersTable.role eq "director") }
                        .map { it[UsersTable.id].value }
                        .filter { it != userId }
                }

                NotificationService.notifyUsers(
                    adminIds, userId, "DAYOFF_WEEKDAY", notifTitle, notifMessage, notifPayload, "dayoff"
                )

                println("🌞 Выходной в будний день: $senderName на $formattedDate, уведомления отправлены ${adminIds.size} админам")
            }

            call.respond(status, mapOf<String, String>("status" to if (status.value in 200..299) "success" else "already_exists"))
        }

        delete {
            if (call.checkAbsenceSession() == null) return@delete
            val userIdParam = call.request.queryParameters["userId"]
            val dateParam = call.request.queryParameters["date"]

            if (userIdParam.isNullOrBlank() || dateParam.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "userId and date query parameters required")
                return@delete
            }

            val userId = UUID.fromString(userIdParam)
            val date = KtLocalDate.parse(dateParam)

            transaction {
                DayOffsTable.deleteWhere {
                    (DayOffsTable.userId eq userId) and (DayOffsTable.date eq date)
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}