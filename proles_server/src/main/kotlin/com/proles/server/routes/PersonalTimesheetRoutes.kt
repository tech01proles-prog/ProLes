package com.proles.server.routes

import com.proles.server.config.SessionManager
import com.proles.server.data.*
import com.proles.server.model.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDate as KtLocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate as JavaLocalDate
import java.util.UUID

private val personalTimesheetJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private suspend fun ApplicationCall.checkPersonalTimesheetSession():
    SessionManager.Session? {
    val token = request.headers["X-Session-Token"]
    val session = SessionManager.validate(token)

    if (session == null) {
        respond(HttpStatusCode.Unauthorized, "Session expired")
        return null
    }

    return session
}

internal fun Route.personalTimesheetRoutes() {
    route("/api/v1/personal-timesheet") {
        fun targetUserId(
            session: SessionManager.Session
        ): UUID? {
            return transaction {
                UsersTable
                    .selectAll()
                    .where {
                        (UsersTable.id eq session.userId) and
                            (UsersTable.login eq "a.ermashkevich")
                    }
                    .limit(1)
                    .firstOrNull()
                    ?.get(UsersTable.id)
                    ?.value
            }
        }


        get {
            val session = call.checkNotificationSession() ?: return@get
            val targetUserId = targetUserId(session)
                ?: return@get call.respond(HttpStatusCode.Forbidden, "Personal timesheet is unavailable for this user")

            val year = call.request.queryParameters["year"]?.toIntOrNull()
            val month = call.request.queryParameters["month"]?.toIntOrNull()
            if (year == null || month == null || month !in 1..12) {
                return@get call.respond(HttpStatusCode.BadRequest, "year and month are required")
            }

            val defaultStart = JavaLocalDate.of(year, month, 1)
            val defaultEnd = defaultStart.withDayOfMonth(defaultStart.lengthOfMonth())

            val response = transaction {
                val rows = PersonalTimesheetTasksTable.selectAll()
                    .where {
                        (PersonalTimesheetTasksTable.userId eq targetUserId) and
                                (PersonalTimesheetTasksTable.year eq year) and
                                (PersonalTimesheetTasksTable.month eq month)
                    }
                    .orderBy(PersonalTimesheetTasksTable.sortOrder to SortOrder.ASC)
                    .orderBy(PersonalTimesheetTasksTable.id to SortOrder.ASC)
                    .toList()

                val periodStart = rows.firstOrNull()?.get(PersonalTimesheetTasksTable.periodStart)?.toString() ?: defaultStart.toString()
                val periodEnd = rows.firstOrNull()?.get(PersonalTimesheetTasksTable.periodEnd)?.toString() ?: defaultEnd.toString()
                val monthlyTaskId = rows.firstOrNull { it[PersonalTimesheetTasksTable.isMonthTask] }?.get(PersonalTimesheetTasksTable.id)?.value?.toString()

                PersonalTimesheetResponseDto(
                    userId = targetUserId.toString(),
                    year = year,
                    month = month,
                    periodStart = periodStart,
                    periodEnd = periodEnd,
                    monthlyTaskId = monthlyTaskId,
                    tasks = rows.map { row ->
                        PersonalTimesheetTaskDto(
                            id = row[PersonalTimesheetTasksTable.id].value.toString(),
                            userId = targetUserId.toString(),
                            year = year,
                            month = month,
                            periodStart = row[PersonalTimesheetTasksTable.periodStart].toString(),
                            periodEnd = row[PersonalTimesheetTasksTable.periodEnd].toString(),
                            name = row[PersonalTimesheetTasksTable.name],
                            description = row[PersonalTimesheetTasksTable.description],
                            hours = row[PersonalTimesheetTasksTable.hours],
                            category = row[PersonalTimesheetTasksTable.category],
                            status = row[PersonalTimesheetTasksTable.status],
                            isMonthTask = row[PersonalTimesheetTasksTable.isMonthTask],
                            sortOrder = row[PersonalTimesheetTasksTable.sortOrder]
                        )
                    }
                )
            }
            call.respond(HttpStatusCode.OK, response)
        }

        put {
            val session = call.checkNotificationSession() ?: return@put
            val targetUserId = targetUserId(session)
                ?: return@put call.respond(HttpStatusCode.Forbidden, "Personal timesheet is unavailable for this user")

            val request = try {
                call.receive<PersonalTimesheetSaveRequest>()
            } catch (e: Exception) {
                return@put call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
            }

            if (request.month !in 1..12) {
                return@put call.respond(HttpStatusCode.BadRequest, "Invalid month")
            }

            val periodStart = try { KtLocalDate.parse(request.periodStart) }
            catch (_: Exception) { return@put call.respond(HttpStatusCode.BadRequest, "Invalid periodStart") }
            val periodEnd = try { KtLocalDate.parse(request.periodEnd) }
            catch (_: Exception) { return@put call.respond(HttpStatusCode.BadRequest, "Invalid periodEnd") }

            if (periodStart > periodEnd) {
                return@put call.respond(HttpStatusCode.BadRequest, "periodStart must be before periodEnd")
            }

            transaction {
                PersonalTimesheetTasksTable.deleteWhere {
                    (PersonalTimesheetTasksTable.userId eq targetUserId) and
                            (PersonalTimesheetTasksTable.year eq request.year) and
                            (PersonalTimesheetTasksTable.month eq request.month)
                }

                val normalizedMonthlyTaskId = request.monthlyTaskId?.takeIf { id ->
                    request.tasks.any { it.id == id }
                }

                request.tasks.forEachIndexed { index, task ->
                    val taskId = runCatching { UUID.fromString(task.id) }.getOrNull() ?: UUID.randomUUID()
                    PersonalTimesheetTasksTable.insert {
                        it[PersonalTimesheetTasksTable.id] = taskId
                        it[PersonalTimesheetTasksTable.userId] = targetUserId
                        it[PersonalTimesheetTasksTable.year] = request.year
                        it[PersonalTimesheetTasksTable.month] = request.month
                        it[PersonalTimesheetTasksTable.periodStart] = periodStart
                        it[PersonalTimesheetTasksTable.periodEnd] = periodEnd
                        it[PersonalTimesheetTasksTable.name] = task.name.trim()
                        it[PersonalTimesheetTasksTable.description] = task.description.trim()
                        it[PersonalTimesheetTasksTable.hours] = task.hours.coerceAtLeast(0.0)
                        it[PersonalTimesheetTasksTable.category] = task.category.trim()
                        it[PersonalTimesheetTasksTable.status] = task.status.trim().ifBlank { "Not started" }
                        it[PersonalTimesheetTasksTable.isMonthTask] = normalizedMonthlyTaskId == task.id
                        it[PersonalTimesheetTasksTable.sortOrder] = index
                    }
                }
            }

            val responseJson = personalTimesheetJson.encodeToString(
                PersonalTimesheetSaveResponseDto(
                    success = true,
                    year = request.year,
                    month = request.month
                )
            )
            call.respondText(responseJson, ContentType.Application.Json)
        }

        get("/categories") {
            val session = call.checkNotificationSession() ?: return@get
            val targetUserId = targetUserId(session)
                ?: return@get call.respond(HttpStatusCode.Forbidden, "Personal timesheet is unavailable for this user")
            val categories = transaction {
                PersonalTimesheetCategoriesTable
                    .selectAll()
                    .where { PersonalTimesheetCategoriesTable.userId eq targetUserId }
                    .orderBy(PersonalTimesheetCategoriesTable.name to SortOrder.ASC)
                    .map { PersonalTimesheetCategoryDto(it[PersonalTimesheetCategoriesTable.id].value.toString(), it[PersonalTimesheetCategoriesTable.name]) }
            }
            call.respond(HttpStatusCode.OK, categories)
        }

        post("/categories") {
            val session = call.checkNotificationSession() ?: return@post
            val targetUserId = targetUserId(session)
                ?: return@post call.respond(HttpStatusCode.Forbidden, "Personal timesheet is unavailable for this user")
            val body = try { call.receive<Map<String, String>>() } catch (_: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
            }
            val name = body["name"]?.trim().orEmpty()
            if (name.isBlank() || name.length > 100) return@post call.respond(HttpStatusCode.BadRequest, "Invalid category name")
            val category = transaction {
                val existing = PersonalTimesheetCategoriesTable.selectAll().where {
                    (PersonalTimesheetCategoriesTable.userId eq targetUserId) and
                            (PersonalTimesheetCategoriesTable.name eq name)
                }.firstOrNull()
                existing ?: run {
                    val id = UUID.randomUUID()
                    PersonalTimesheetCategoriesTable.insert {
                        it[PersonalTimesheetCategoriesTable.id] = id
                        it[PersonalTimesheetCategoriesTable.userId] = targetUserId
                        it[PersonalTimesheetCategoriesTable.name] = name
                    }
                    PersonalTimesheetCategoriesTable.selectAll().where { PersonalTimesheetCategoriesTable.id eq id }.single()
                }
            }
            call.respond(HttpStatusCode.OK, PersonalTimesheetCategoryDto(category[PersonalTimesheetCategoriesTable.id].value.toString(), category[PersonalTimesheetCategoriesTable.name]))
        }

        delete("/categories/{id}") {
            val session = call.checkNotificationSession() ?: return@delete
            val targetUserId = targetUserId(session)
                ?: return@delete call.respond(HttpStatusCode.Forbidden, "Personal timesheet is unavailable for this user")
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid category id")
            transaction {
                PersonalTimesheetCategoriesTable.deleteWhere {
                    (PersonalTimesheetCategoriesTable.id eq id) and
                            (PersonalTimesheetCategoriesTable.userId eq targetUserId)
                }
            }
            call.respond(HttpStatusCode.OK)
        }
    }
}