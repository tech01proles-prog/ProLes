package com.proles.server.routes

import com.proles.server.config.PermissionMiddleware.checkPermission
import com.proles.server.data.PositionsTable
import com.proles.server.data.UsersTable
import com.proles.server.model.Permission
import com.proles.server.model.PositionDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

internal fun Route.positionRoutes() {
    route("/api/v1/positions") {
        get {
            if (!call.checkPermission(Permission.EMPLOYEES, "view")) return@get
            val list = transaction {
                val names = PositionsTable.selectAll().associate { it[PositionsTable.id].value to it[PositionsTable.name] }
                PositionsTable.selectAll()
                    .orderBy(PositionsTable.sortOrder to SortOrder.ASC, PositionsTable.name to SortOrder.ASC)
                    .map {
                        PositionDto(
                            id = it[PositionsTable.id].value.toString(),
                            name = it[PositionsTable.name],
                            parentId = it[PositionsTable.parentId]?.toString(),
                            parentName = it[PositionsTable.parentId]?.let(names::get),
                            isActive = it[PositionsTable.isActive],
                            sortOrder = it[PositionsTable.sortOrder]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, list)
        }
        post {
            if (!call.checkPermission(Permission.EMPLOYEES, "create")) return@post
            val body = call.receive<PositionDto>()
            if (body.name.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, "Название должности обязательно")
            val parentId = body.parentId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (body.parentId != null && parentId == null) return@post call.respond(HttpStatusCode.BadRequest, "Invalid parentId")
            val id = UUID.randomUUID()
            transaction {
                PositionsTable.insert {
                    it[PositionsTable.id] = id
                    it[name] = body.name.trim()
                    it[PositionsTable.parentId] = parentId
                    it[isActive] = body.isActive
                    it[sortOrder] = body.sortOrder
                }
            }
            call.respond(HttpStatusCode.Created, body.copy(id = id.toString(), parentId = parentId?.toString()))
        }
        put {
            if (!call.checkPermission(Permission.EMPLOYEES, "edit")) return@put
            val body = call.receive<PositionDto>()
            val id = runCatching { UUID.fromString(body.id) }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid id")
            val parentId = body.parentId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (parentId == id) return@put call.respond(HttpStatusCode.BadRequest, "Должность не может быть родителем самой себя")
            transaction {
                PositionsTable.update({ PositionsTable.id eq id }) {
                    it[name] = body.name.trim()
                    it[PositionsTable.parentId] = parentId
                    it[isActive] = body.isActive
                    it[sortOrder] = body.sortOrder
                }
            }
            call.respond(HttpStatusCode.OK, body)
        }
        delete {
            if (!call.checkPermission(Permission.EMPLOYEES, "delete")) return@delete
            val id = call.request.queryParameters["positionId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "positionId required")
            val hasChildren = transaction { PositionsTable.selectAll().where { PositionsTable.parentId eq id }.count() > 0 }
            if (hasChildren) return@delete call.respond(HttpStatusCode.Conflict, "Сначала перенесите дочерние должности")
            transaction {
                PositionsTable.update({ PositionsTable.id eq id }) { it[isActive] = false }
                UsersTable.update({ UsersTable.positionId eq id }) { it[UsersTable.positionId] = null }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}