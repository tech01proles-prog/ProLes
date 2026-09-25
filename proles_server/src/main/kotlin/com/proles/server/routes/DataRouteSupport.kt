package com.proles.server.routes

import com.proles.server.config.SessionManager
import com.proles.server.data.SubprojectsTable
import com.proles.server.data.TimeEntriesTable
import com.proles.server.model.TimeEntry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

internal suspend fun ApplicationCall.requireSession(
    vararg allowedRoles: String
): SessionManager.Session? {
    val token = request.header("X-Session-Token")
    val session = SessionManager.validate(token)

    if (session == null) {
        respond(HttpStatusCode.Unauthorized, "Session expired")
        return null
    }

    if (
        allowedRoles.isNotEmpty() &&
        session.role !in allowedRoles
    ) {
        respond(HttpStatusCode.Forbidden, "Access denied")
        return null
    }

    return session
}

internal fun String?.toRouteUuidOrNull(): UUID? =
    this
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

internal fun validateActiveSubproject(
    projectId: UUID,
    subprojectId: UUID?
): Boolean {
    if (subprojectId == null) return true

    return SubprojectsTable
        .selectAll()
        .where {
            (SubprojectsTable.id eq subprojectId) and
                (SubprojectsTable.projectId eq projectId) and
                (SubprojectsTable.isActive eq true)
        }
        .limit(1)
        .any()
}

internal fun mapTimeEntry(row: ResultRow): TimeEntry {
    val subprojectId = row[TimeEntriesTable.subprojectId]?.value

    val subprojectName = subprojectId
        ?.let { id ->
            SubprojectsTable
                .selectAll()
                .where { SubprojectsTable.id eq id }
                .limit(1)
                .singleOrNull()
                ?.get(SubprojectsTable.name)
        }
        .orEmpty()

    return TimeEntry(
        id = row[TimeEntriesTable.id].value.toString(),
        userId = row[TimeEntriesTable.userId].value.toString(),
        projectId = row[TimeEntriesTable.projectId].value.toString(),
        subprojectId = subprojectId?.toString(),
        subprojectName = subprojectName,
        projectName = row[TimeEntriesTable.projectName],
        date = row[TimeEntriesTable.date].toString(),
        hours = row[TimeEntriesTable.hours],
        country = row[TimeEntriesTable.country],
        comment = row[TimeEntriesTable.comment].orEmpty(),
        synced = row[TimeEntriesTable.synced]
    )
}