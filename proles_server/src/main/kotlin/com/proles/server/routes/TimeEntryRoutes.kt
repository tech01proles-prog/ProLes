package com.proles.server.routes

import com.proles.server.config.PermissionMiddleware.checkPermission
import com.proles.server.data.TimeEntriesTable
import com.proles.server.model.Permission
import com.proles.server.model.TimeEntry
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate as KtLocalDate
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.orderBy
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDate as JavaLocalDate
import java.util.UUID

internal fun Route.timeEntryRoutes() {
    route("/api/v1/entries") {
        get("/all") {
            if (
                !call.checkPermission(
                    Permission.PROJECTS,
                    "view"
                )
            ) {
                return@get
            }

            val dateFromText =
                call.request.queryParameters["dateFrom"]
            val dateToText =
                call.request.queryParameters["dateTo"]

            val dateFrom = dateFromText
                ?.takeIf(String::isNotBlank)
                ?.let {
                    runCatching { KtLocalDate.parse(it) }
                        .getOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid dateFrom"
                        )
                }

            val dateTo = dateToText
                ?.takeIf(String::isNotBlank)
                ?.let {
                    runCatching { KtLocalDate.parse(it) }
                        .getOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid dateTo"
                        )
                }

            val entries = transaction {
                var condition: Op<Boolean> =
                    TimeEntriesTable.id neq UUID(0L, 0L)

                dateFrom?.let {
                    condition = condition and
                        (TimeEntriesTable.date greaterEq it)
                }

                dateTo?.let {
                    condition = condition and
                        (TimeEntriesTable.date lessEq it)
                }

                TimeEntriesTable
                    .selectAll()
                    .where { condition }
                    .orderBy(
                        TimeEntriesTable.date to SortOrder.DESC
                    )
                    .map(::mapTimeEntry)
            }

            call.respond(HttpStatusCode.OK, entries)
        }

        get {
            val session = call.requireSession() ?: return@get

            val userId = call.request.queryParameters["userId"]
                .toRouteUuidOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    "Valid userId required"
                )

            if (
                userId != session.userId &&
                session.role !in setOf(
                    "superadmin",
                    "admin",
                    "director"
                )
            ) {
                return@get call.respond(
                    HttpStatusCode.Forbidden,
                    "Access denied"
                )
            }

            val dateFromText =
                call.request.queryParameters["dateFrom"]
            val dateToText =
                call.request.queryParameters["dateTo"]

            val dateFrom = dateFromText
                ?.takeIf(String::isNotBlank)
                ?.let {
                    runCatching { KtLocalDate.parse(it) }
                        .getOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid dateFrom"
                        )
                }

            val dateTo = dateToText
                ?.takeIf(String::isNotBlank)
                ?.let {
                    runCatching { KtLocalDate.parse(it) }
                        .getOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid dateTo"
                        )
                }

            val entries = transaction {
                var condition: Op<Boolean> =
                    TimeEntriesTable.userId eq userId

                dateFrom?.let {
                    condition = condition and
                        (TimeEntriesTable.date greaterEq it)
                }

                dateTo?.let {
                    condition = condition and
                        (TimeEntriesTable.date lessEq it)
                }

                TimeEntriesTable
                    .selectAll()
                    .where { condition }
                    .orderBy(
                        TimeEntriesTable.date to SortOrder.DESC
                    )
                    .map(::mapTimeEntry)
            }

            call.respond(HttpStatusCode.OK, entries)
        }

        post {
            val session = call.requireSession() ?: return@post

            val entry = try {
                call.receive<TimeEntry>()
            } catch (e: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid JSON: ${e.message}"
                )
            }

            val entryId =
                entry.id.toRouteUuidOrNull() ?: UUID.randomUUID()
            val userId = entry.userId.toRouteUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid userId"
                )
            val projectId = entry.projectId.toRouteUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid projectId"
                )
            val subprojectId =
                entry.subprojectId.toRouteUuidOrNull()
            val entryDate = runCatching {
                KtLocalDate.parse(entry.date)
            }.getOrElse {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid date"
                )
            }

            if (entry.hours <= 0f) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Hours must be greater than zero"
                )
            }

            if (
                userId != session.userId &&
                session.role !in setOf(
                    "superadmin",
                    "admin",
                    "director"
                )
            ) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    "Access denied"
                )
            }

            val created = try {
                transaction {
                    if (
                        !validateActiveSubproject(
                            projectId,
                            subprojectId
                        )
                    ) {
                        throw IllegalArgumentException(
                            "Подпроект не принадлежит проекту " +
                                "или архивирован"
                        )
                    }

                    TimeEntriesTable.insert {
                        it[id] = entryId
                        it[TimeEntriesTable.userId] = userId
                        it[TimeEntriesTable.projectId] = projectId
                        it[
                            TimeEntriesTable.subprojectId
                        ] = subprojectId
                        it[projectName] = entry.projectName
                        it[date] = entryDate
                        it[hours] = entry.hours
                        it[country] = entry.country
                        it[comment] = entry.comment
                        it[synced] = entry.synced
                    }

                    TimeEntriesTable
                        .selectAll()
                        .where {
                            TimeEntriesTable.id eq entryId
                        }
                        .single()
                        .let(::mapTimeEntry)
                }
            } catch (e: IllegalArgumentException) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    e.message ?: "Invalid subproject"
                )
            }

            call.respond(HttpStatusCode.Created, created)
        }

        put {
            val session = call.requireSession() ?: return@put

            val entry = try {
                call.receive<TimeEntry>()
            } catch (e: Exception) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid JSON: ${e.message}"
                )
            }

            val entryId =
                entry.id.toRouteUuidOrNull() ?: UUID.randomUUID()
            val userId = entry.userId.toRouteUuidOrNull()
                ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid userId"
                )
            val projectId = entry.projectId.toRouteUuidOrNull()
                ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid projectId"
                )
            val subprojectId =
                entry.subprojectId.toRouteUuidOrNull()
            val entryDate = runCatching {
                KtLocalDate.parse(entry.date)
            }.getOrElse {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid date"
                )
            }

            val javaEntryDate = JavaLocalDate.of(
                entryDate.year,
                entryDate.monthNumber,
                entryDate.dayOfMonth
            )

            if (javaEntryDate > JavaLocalDate.now()) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Нельзя добавлять часы за будущие даты"
                )
            }

            if (entry.hours <= 0f) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Hours must be greater than zero"
                )
            }

            if (
                userId != session.userId &&
                session.role !in setOf(
                    "superadmin",
                    "admin",
                    "director"
                )
            ) {
                return@put call.respond(
                    HttpStatusCode.Forbidden,
                    "Access denied"
                )
            }

            val result = try {
                transaction {
                    if (
                        !validateActiveSubproject(
                            projectId,
                            subprojectId
                        )
                    ) {
                        throw IllegalArgumentException(
                            "Подпроект не принадлежит проекту " +
                                "или архивирован"
                        )
                    }

                    val baseCondition =
                        (TimeEntriesTable.userId eq userId) and
                            (TimeEntriesTable.projectId eq projectId) and
                            (TimeEntriesTable.date eq entryDate)

                    val existing = TimeEntriesTable
                        .selectAll()
                        .where {
                            if (subprojectId == null) {
                                baseCondition and
                                    TimeEntriesTable
                                        .subprojectId
                                        .isNull()
                            } else {
                                baseCondition and
                                    (TimeEntriesTable.subprojectId eq
                                        subprojectId)
                            }
                        }
                        .firstOrNull()

                    if (existing != null) {
                        val existingId =
                            existing[TimeEntriesTable.id].value
                        val newHours =
                            existing[TimeEntriesTable.hours] +
                                entry.hours
                        val oldComment =
                            existing[
                                TimeEntriesTable.comment
                            ].orEmpty()
                        val newComment =
                            if (
                                entry.comment.isNotBlank() &&
                                entry.comment != oldComment
                            ) {
                                listOf(
                                    oldComment,
                                    entry.comment
                                )
                                    .filter(String::isNotBlank)
                                    .joinToString(" | ")
                            } else {
                                oldComment
                            }

                        TimeEntriesTable.update(
                            {
                                TimeEntriesTable.id eq existingId
                            }
                        ) {
                            it[hours] = newHours
                            it[comment] = newComment
                            it[country] = entry.country
                            it[synced] = entry.synced
                        }

                        TimeEntriesTable
                            .selectAll()
                            .where {
                                TimeEntriesTable.id eq existingId
                            }
                            .single()
                            .let(::mapTimeEntry) to true
                    } else {
                        TimeEntriesTable.insert {
                            it[id] = entryId
                            it[TimeEntriesTable.userId] = userId
                            it[
                                TimeEntriesTable.projectId
                            ] = projectId
                            it[
                                TimeEntriesTable.subprojectId
                            ] = subprojectId
                            it[projectName] = entry.projectName
                            it[date] = entryDate
                            it[hours] = entry.hours
                            it[country] = entry.country
                            it[comment] = entry.comment
                            it[synced] = entry.synced
                        }

                        TimeEntriesTable
                            .selectAll()
                            .where {
                                TimeEntriesTable.id eq entryId
                            }
                            .single()
                            .let(::mapTimeEntry) to false
                    }
                }
            } catch (e: IllegalArgumentException) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    e.message ?: "Invalid subproject"
                )
            }

            call.respond(
                if (result.second) {
                    HttpStatusCode.OK
                } else {
                    HttpStatusCode.Created
                },
                result.first
            )
        }

        delete {
            val session = call.requireSession() ?: return@delete

            val entryId =
                call.request.queryParameters["entryId"]
                    .toRouteUuidOrNull()
                    ?: return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        "Valid entryId required"
                    )

            val deleted = transaction {
                val existing = TimeEntriesTable
                    .selectAll()
                    .where { TimeEntriesTable.id eq entryId }
                    .singleOrNull()
                    ?: return@transaction 0

                if (
                    existing[TimeEntriesTable.userId].value !=
                    session.userId &&
                    session.role !in setOf(
                        "superadmin",
                        "admin",
                        "director"
                    )
                ) {
                    return@transaction -1
                }

                TimeEntriesTable.deleteWhere {
                    TimeEntriesTable.id eq entryId
                }
            }

            when {
                deleted < 0 -> call.respond(
                    HttpStatusCode.Forbidden,
                    "Access denied"
                )

                deleted == 0 -> call.respond(
                    HttpStatusCode.NotFound,
                    "Entry not found"
                )

                else -> call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}