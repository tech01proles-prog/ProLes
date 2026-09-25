package com.proles.server.routes

import com.proles.server.config.PermissionMiddleware
import com.proles.server.config.SessionManager
import com.proles.server.data.*
import com.proles.server.model.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDate as KtLocalDate
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

private val businessTripJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private suspend fun ApplicationCall.checkBusinessTripSession():
    SessionManager.Session? {
    val token = request.headers["X-Session-Token"]
    val session = SessionManager.validate(token)

    if (session == null) {
        respond(HttpStatusCode.Unauthorized, "Session expired")
        return null
    }

    return session
}

private fun String?.toBusinessTripUuidOrNull(): UUID? =
    this
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private fun validateBusinessTripSubproject(
    projectId: UUID?,
    subprojectId: UUID?
): Boolean {
    if (subprojectId == null) return true
    if (projectId == null) return false

    return validateActiveSubproject(projectId, subprojectId)
}

private enum class BusinessTripResult {
    NOT_FOUND,
    FORBIDDEN,
    UPDATED,
    DELETED
}

private fun businessTripSubprojectName(row: ResultRow): String {
    val subprojectId = row[BusinessTripsTable.subprojectId]?.value
        ?: return ""

    return SubprojectsTable
        .selectAll()
        .where { SubprojectsTable.id eq subprojectId }
        .limit(1)
        .singleOrNull()
        ?.get(SubprojectsTable.name)
        .orEmpty()
}

private fun mapRowToBusinessTripDto(row: ResultRow): BusinessTripDto {
    val userId = row[BusinessTripsTable.userId].value
    val projectId = row[BusinessTripsTable.projectId]?.value
    val startDate = row[BusinessTripsTable.startDate] ?: row[BusinessTripsTable.date]
    val endDate = row[BusinessTripsTable.endDate]

    val userName = UsersTable
        .selectAll()
        .where { UsersTable.id eq userId }
        .limit(1)
        .singleOrNull()
        ?.get(UsersTable.name)
        .orEmpty()

    val projectName = projectId?.let { id ->
        ProjectsTable
            .selectAll()
            .where { ProjectsTable.id eq id }
            .limit(1)
            .singleOrNull()
            ?.get(ProjectsTable.name)
    }.orEmpty()

    val waypoints = runCatching {
       businessTripJson.decodeFromString<List<WaypointDto>>(
            row[BusinessTripsTable.waypoints]
        )
    }.getOrDefault(emptyList())

    val participants = runCatching {
       businessTripJson.decodeFromString<List<String>>(
            row[BusinessTripsTable.participants]
        )
    }.getOrDefault(emptyList())

    return BusinessTripDto(
        id = row[BusinessTripsTable.id].value.toString(),
        userId = userId.toString(),
        userName = userName,
        projectId = projectId?.toString(),
        subprojectId = row[
            BusinessTripsTable.subprojectId
        ]?.value?.toString(),
        subprojectName = businessTripSubprojectName(row),
        projectNumber = row[BusinessTripsTable.projectNumber],
        companyName = row[BusinessTripsTable.companyName],
        country = row[BusinessTripsTable.country],
        projectName = projectName,
        type = row[BusinessTripsTable.type],
        date = row[BusinessTripsTable.date].toString(),
        completedDate = row[
            BusinessTripsTable.completedDate
        ]?.toString(),
        city = row[BusinessTripsTable.city],
        waypoints = waypoints,
        transport = row[BusinessTripsTable.transport],
        notes = row[BusinessTripsTable.notes],
        participants = participants,
        createdAt = row[BusinessTripsTable.createdAt],
        perDiemRate = row[BusinessTripsTable.perDiemRate],
        startDate = startDate.toString(),
        endDate = endDate?.toString(),
        status = row[BusinessTripsTable.status]
    )
}

internal fun Route.businessTripRoutes() {
    route("/api/v1/business-trips") {
        get("/all") {
            val session = call.checkBusinessTripSession() ?: return@get

            val canViewAll =
                session.role in setOf(
                    "superadmin",
                    "admin",
                    "director",
                    "logist"
                ) ||
                    PermissionMiddleware.canView(
                        session.userId,
                        Permission.BUSINESS_TRIPS_ALL
                    )

            if (!canViewAll) {
                return@get call.respond(
                    HttpStatusCode.Forbidden,
                    "Нет прав на просмотр командировок"
                )
            }

            val trips = transaction {
                BusinessTripsTable
                    .selectAll()
                    .orderBy(
                        BusinessTripsTable.startDate to SortOrder.DESC,
                        BusinessTripsTable.createdAt to SortOrder.DESC
                    )
                    .map(::mapRowToBusinessTripDto)
            }

            call.respond(HttpStatusCode.OK, trips)
        }

        get {
            val session = call.checkBusinessTripSession() ?: return@get

            val requestedUserId =
                call.request.queryParameters["userId"]
                    .toBusinessTripUuidOrNull()
                    ?: session.userId

            if (
                requestedUserId != session.userId &&
                session.role !in setOf(
                    "superadmin",
                    "admin",
                    "director",
                    "logist"
                )
            ) {
                return@get call.respond(
                    HttpStatusCode.Forbidden,
                    "Access denied"
                )
            }

            val trips = transaction {
                BusinessTripsTable
                    .selectAll()
                    .where {
                        BusinessTripsTable.userId eq requestedUserId
                    }
                    .orderBy(
                        BusinessTripsTable.startDate to SortOrder.DESC,
                        BusinessTripsTable.createdAt to SortOrder.DESC
                    )
                    .map(::mapRowToBusinessTripDto)
            }

            call.respond(HttpStatusCode.OK, trips)
        }

        post {
            val session = call.checkBusinessTripSession() ?: return@post

            val trip = try {
                call.receive<BusinessTripDto>()
            } catch (e: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid JSON: ${e.message}"
                )
            }

            val userId = trip.userId.toBusinessTripUuidOrNull()
                ?: session.userId

            if (
                userId != session.userId &&
                session.role !in setOf(
                    "superadmin",
                    "admin",
                    "director",
                    "logist"
                )
            ) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    "Access denied"
                )
            }

            val projectId = trip.projectId.toBusinessTripUuidOrNull()
            val subprojectId = trip.subprojectId.toBusinessTripUuidOrNull()

            if (trip.projectId?.isNotBlank() == true &&
                projectId == null
            ) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid projectId"
                )
            }

            if (trip.subprojectId?.isNotBlank() == true &&
                subprojectId == null
            ) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid subprojectId"
                )
            }

            if (projectId == null && subprojectId != null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Нельзя выбрать подпроект без проекта"
                )
            }

            val startDateValue =
                trip.startDate.ifBlank { trip.date }

            val startDate = runCatching {
                KtLocalDate.parse(startDateValue)
            }.getOrElse {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid startDate"
                )
            }

            val endDate = trip.endDate
                ?.takeIf(String::isNotBlank)
                ?.let {
                    runCatching { KtLocalDate.parse(it) }.getOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid endDate"
                        )
                }

            if (endDate != null && endDate < startDate) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Дата окончания раньше даты начала"
                )
            }

            if (
                !trip.perDiemRate.isFinite() ||
                trip.perDiemRate < 0.0
            ) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "perDiemRate must be non-negative"
                )
            }

            val id = trip.id.toBusinessTripUuidOrNull() ?: UUID.randomUUID()
            val normalizedStatus = when {
                endDate != null -> "COMPLETED"
                trip.status.uppercase() == "CANCELLED" -> "CANCELLED"
                else -> "ACTIVE"
            }

            val created = try {
                transaction {
                    if (
                        !validateBusinessTripSubproject(
                            projectId,
                            subprojectId
                        )
                    ) {
                        throw IllegalArgumentException(
                            "Подпроект не принадлежит проекту " +
                                "или архивирован"
                        )
                    }

                    if (
                        projectId != null &&
                        ProjectsTable
                            .selectAll()
                            .where { ProjectsTable.id eq projectId }
                            .limit(1)
                            .none()
                    ) {
                        throw IllegalArgumentException(
                            "Проект не найден"
                        )
                    }

                    BusinessTripsTable.insert {
                        it[BusinessTripsTable.id] = id
                        it[BusinessTripsTable.userId] = userId
                        it[BusinessTripsTable.projectId] = projectId
                        it[BusinessTripsTable.subprojectId] =
                            subprojectId
                        it[projectNumber] = trip.projectNumber
                        it[companyName] = trip.companyName
                        it[country] = trip.country
                        it[type] = trip.type
                        it[date] = startDate
                        it[completedDate] = endDate
                        it[BusinessTripsTable.startDate] = startDate
                        it[BusinessTripsTable.endDate] = endDate
                        it[status] = normalizedStatus
                        it[city] = trip.city
                        it[participants] =
                            businessTripJson.encodeToString(trip.participants)
                        it[transport] = trip.transport
                        it[notes] = trip.notes
                        it[waypoints] =
                            businessTripJson.encodeToString(trip.waypoints)
                        it[perDiemRate] = trip.perDiemRate
                        it[createdAt] = System.currentTimeMillis()
                    }

                    BusinessTripsTable
                        .selectAll()
                        .where { BusinessTripsTable.id eq id }
                        .single()
                        .let(::mapRowToBusinessTripDto)
                }
            } catch (e: IllegalArgumentException) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    e.message ?: "Invalid business trip"
                )
            }

            call.respond(HttpStatusCode.Created, created)
        }

        put {
            val session = call.checkBusinessTripSession() ?: return@put

            val trip = try {
                call.receive<BusinessTripDto>()
            } catch (e: Exception) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid JSON: ${e.message}"
                )
            }

            val tripId = trip.id.toBusinessTripUuidOrNull()
                ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid tripId"
                )

            val projectId = trip.projectId.toBusinessTripUuidOrNull()
            val subprojectId = trip.subprojectId.toBusinessTripUuidOrNull()

            if (trip.projectId?.isNotBlank() == true &&
                projectId == null
            ) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid projectId"
                )
            }

            if (trip.subprojectId?.isNotBlank() == true &&
                subprojectId == null
            ) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid subprojectId"
                )
            }

            if (projectId == null && subprojectId != null) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Нельзя выбрать подпроект без проекта"
                )
            }

            val startDate = runCatching {
                KtLocalDate.parse(
                    trip.startDate.ifBlank { trip.date }
                )
            }.getOrElse {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid startDate"
                )
            }

            val endDate = trip.endDate
                ?.takeIf(String::isNotBlank)
                ?.let {
                    runCatching { KtLocalDate.parse(it) }.getOrNull()
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid endDate"
                        )
                }

            if (endDate != null && endDate < startDate) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Дата окончания раньше даты начала"
                )
            }

            val result = try {
                transaction {
                    val existing = BusinessTripsTable
                        .selectAll()
                        .where { BusinessTripsTable.id eq tripId }
                        .singleOrNull()
                        ?: return@transaction null

                    val ownerId =
                        existing[BusinessTripsTable.userId].value

                    if (
                        ownerId != session.userId &&
                        session.role !in setOf(
                            "superadmin",
                            "admin",
                            "director",
                            "logist"
                        )
                    ) {
                        throw SecurityException("Access denied")
                    }

                    if (
                        !validateBusinessTripSubproject(
                            projectId,
                            subprojectId
                        )
                    ) {
                        throw IllegalArgumentException(
                            "Подпроект не принадлежит проекту " +
                                "или архивирован"
                        )
                    }

                    val normalizedStatus = when {
                        endDate != null -> "COMPLETED"
                        trip.status.uppercase() == "CANCELLED" ->
                            "CANCELLED"
                        else -> "ACTIVE"
                    }

                    BusinessTripsTable.update(
                        { BusinessTripsTable.id eq tripId }
                    ) {
                        it[BusinessTripsTable.projectId] = projectId
                        it[BusinessTripsTable.subprojectId] =
                            subprojectId
                        it[projectNumber] = trip.projectNumber
                        it[companyName] = trip.companyName
                        it[country] = trip.country
                        it[type] = trip.type
                        it[date] = startDate
                        it[completedDate] = endDate
                        it[BusinessTripsTable.startDate] = startDate
                        it[BusinessTripsTable.endDate] = endDate
                        it[status] = normalizedStatus
                        it[city] = trip.city
                        it[participants] =
                            businessTripJson.encodeToString(trip.participants)
                        it[transport] = trip.transport
                        it[notes] = trip.notes
                        it[waypoints] =
                            businessTripJson.encodeToString(trip.waypoints)
                        it[perDiemRate] = trip.perDiemRate
                    }

                    BusinessTripsTable
                        .selectAll()
                        .where { BusinessTripsTable.id eq tripId }
                        .single()
                        .let(::mapRowToBusinessTripDto)
                }
            } catch (e: SecurityException) {
                return@put call.respond(
                    HttpStatusCode.Forbidden,
                    e.message ?: "Access denied"
                )
            } catch (e: IllegalArgumentException) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    e.message ?: "Invalid business trip"
                )
            }

            if (result == null) {
                return@put call.respond(
                    HttpStatusCode.NotFound,
                    "Business trip not found"
                )
            }

            call.respond(HttpStatusCode.OK, result)
        }

        post("/{tripId}/complete") {
            val session = call.checkBusinessTripSession() ?: return@post

            val tripId = call.parameters["tripId"].toBusinessTripUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid tripId"
                )

            val request = try {
                call.receive<CompleteBusinessTripRequest>()
            } catch (e: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid JSON: ${e.message}"
                )
            }

            val endDate = runCatching {
                KtLocalDate.parse(request.endDate)
            }.getOrElse {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid endDate"
                )
            }

            val result = transaction {
                val existing = BusinessTripsTable
                    .selectAll()
                    .where { BusinessTripsTable.id eq tripId }
                    .singleOrNull()
                    ?: return@transaction BusinessTripResult.NOT_FOUND

                val ownerId =
                    existing[BusinessTripsTable.userId].value

                if (
                    ownerId != session.userId &&
                    session.role !in setOf(
                        "superadmin",
                        "admin",
                        "director",
                        "logist"
                    )
                ) {
                    return@transaction BusinessTripResult.FORBIDDEN
                }

                val startDate = existing[BusinessTripsTable.startDate] ?: existing[BusinessTripsTable.date]

                if (endDate < startDate) {
                    throw IllegalArgumentException(
                        "Дата окончания раньше даты начала"
                    )
                }

                BusinessTripsTable.update(
                    { BusinessTripsTable.id eq tripId }
                ) {
                    it[BusinessTripsTable.endDate] = endDate
                    it[completedDate] = endDate
                    it[status] = "COMPLETED"
                }

                BusinessTripResult.UPDATED
            }

            when (result) {
                BusinessTripResult.NOT_FOUND ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        "Business trip not found"
                    )

                BusinessTripResult.FORBIDDEN ->
                    call.respond(
                        HttpStatusCode.Forbidden,
                        "Access denied"
                    )

                BusinessTripResult.UPDATED ->
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "success" to true,
                            "status" to "COMPLETED",
                            "endDate" to endDate.toString()
                        )
                    )

                BusinessTripResult.DELETED ->
                    call.respond(HttpStatusCode.NoContent)
            }
        }

        delete {
            val session = call.checkBusinessTripSession() ?: return@delete

            val tripId = call.request.queryParameters["tripId"]
                .toBusinessTripUuidOrNull()
                ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    "Valid tripId required"
                )

            val result = transaction {
                val existing = BusinessTripsTable
                    .selectAll()
                    .where { BusinessTripsTable.id eq tripId }
                    .singleOrNull()
                    ?: return@transaction BusinessTripResult.NOT_FOUND

                val ownerId =
                    existing[BusinessTripsTable.userId].value

                if (
                    ownerId != session.userId &&
                    session.role !in setOf(
                        "superadmin",
                        "admin",
                        "director",
                        "logist"
                    )
                ) {
                    return@transaction BusinessTripResult.FORBIDDEN
                }

                BusinessTripsTable.deleteWhere {
                    BusinessTripsTable.id eq tripId
                }

                BusinessTripResult.DELETED
            }

            when (result) {
                BusinessTripResult.NOT_FOUND ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        "Business trip not found"
                    )

                BusinessTripResult.FORBIDDEN ->
                    call.respond(
                        HttpStatusCode.Forbidden,
                        "Access denied"
                    )

                BusinessTripResult.DELETED ->
                    call.respond(HttpStatusCode.NoContent)

                BusinessTripResult.UPDATED ->
                    call.respond(HttpStatusCode.OK)
            }
        }
    }
}