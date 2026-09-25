package com.proles.server.routes

import com.proles.server.config.PermissionMiddleware
import com.proles.server.config.PermissionMiddleware.checkPermission
import com.proles.server.config.SessionManager
import com.proles.server.data.*
import com.proles.server.model.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

@Serializable
data class UserPermissionOverrideDto(
    val permission: String,
    val canView: Boolean? = null,
    val canCreate: Boolean? = null,
    val canEdit: Boolean? = null,
    val canDelete: Boolean? = null
)

@Serializable
data class RolePermissionUpdateDto(
    val permission: String,
    val canView: Boolean,
    val canCreate: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean
)

private suspend fun ApplicationCall.checkRbacSession():
    SessionManager.Session? {
    val token = request.headers["X-Session-Token"]
    val session = SessionManager.validate(token)

    if (session == null) {
        respond(HttpStatusCode.Unauthorized, "Session expired")
        return null
    }

    return session
}

internal fun Route.permissionRoutes() {
    route("/api/v1/rbac") {
        // 🔥 НОВЫЙ ЭНДПОИНТ: effective-права ТЕКУЩЕГО пользователя
        // Отдаёт объединённые права (role + overrides) — используется клиентом
        get("/my-permissions") {
            val session = call.checkRbacSession() ?: return@get
            val permissions = com.proles.server.config.PermissionMiddleware
                .getPermissionsPublic(session.userId, session.role)
            call.respond(HttpStatusCode.OK, permissions)
        }
        // Получить все роли с их правами
        get("/roles") {
            if (!call.checkPermission(Permission.PERMISSIONS, "view")) return@get
            val roles = transaction {
                RolesTable.selectAll().map { roleRow ->
                    val roleId = roleRow[RolesTable.id].value
                    val permissions = RolePermissionsTable.selectAll()
                        .where { RolePermissionsTable.roleId eq roleId }
                        .map { permRow ->
                            RolePermissionDto(
                                permission = permRow[RolePermissionsTable.permission],
                                canView = permRow[RolePermissionsTable.canView],
                                canCreate = permRow[RolePermissionsTable.canCreate],
                                canEdit = permRow[RolePermissionsTable.canEdit],
                                canDelete = permRow[RolePermissionsTable.canDelete]
                            )
                        }
                    RoleDto(
                        id = roleId.toString(),
                        name = roleRow[RolesTable.name],
                        displayName = roleRow[RolesTable.displayName],
                        description = roleRow[RolesTable.description],
                        permissions = permissions
                    )
                }
            }
            call.respond(HttpStatusCode.OK, roles)
        }

        put("/roles/{roleId}") {
            if (!call.checkPermission(Permission.PERMISSIONS, "edit")) return@put
            val roleId = runCatching {
                UUID.fromString(call.parameters["roleId"])
            }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid roleId")
            val body = try { call.receive<List<RolePermissionUpdateDto>>() }
            catch (e: Exception) {
                println("❌ PUT /roles/$roleId: bad JSON: ${e.message}")
                return@put call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
            }
            println("📥 PUT /roles/$roleId: received ${body.size} permissions")
            var updated = 0
            var inserted = 0
            transaction {
                body.forEach { item ->
                    // 🔥 ИСПРАВЛЕНО: объявляем локальные переменные
                    val perm = item.permission
                    val cView = item.canView
                    val cCreate = item.canCreate
                    val cEdit = item.canEdit
                    val cDelete = item.canDelete

                    val exists = RolePermissionsTable.selectAll()
                        .where {
                            (RolePermissionsTable.roleId eq roleId) and
                                    (RolePermissionsTable.permission eq perm)
                        }.singleOrNull()

                    if (exists != null) {
                        RolePermissionsTable.update({
                            (RolePermissionsTable.roleId eq roleId) and
                                    (RolePermissionsTable.permission eq perm)
                        }) {
                            it[RolePermissionsTable.canView] = cView      // ✅ Используем cView
                            it[RolePermissionsTable.canCreate] = cCreate  // ✅ Используем cCreate
                            it[RolePermissionsTable.canEdit] = cEdit      // ✅ Используем cEdit
                            it[RolePermissionsTable.canDelete] = cDelete  // ✅ Используем cDelete
                        }
                        updated++
                        println("  ✅ Updated $perm: V=$cView C=$cCreate E=$cEdit D=$cDelete")
                    } else {
                        RolePermissionsTable.insert {
                            it[RolePermissionsTable.id] = UUID.randomUUID()
                            it[RolePermissionsTable.roleId] = roleId
                            it[RolePermissionsTable.permission] = perm
                            it[RolePermissionsTable.canView] = cView
                            it[RolePermissionsTable.canCreate] = cCreate
                            it[RolePermissionsTable.canEdit] = cEdit
                            it[RolePermissionsTable.canDelete] = cDelete
                        }
                        inserted++
                        println("  ➕ Inserted $perm")
                    }
                }
            }
            PermissionMiddleware.invalidateAllCache()
            println("✅ Role $roleId updated: $updated updated, $inserted inserted")
            call.respond(HttpStatusCode.OK)
        }

        // Получить effective-права пользователя (role + overrides merged)
        get("/users/{userId}/permissions") {
            if (!call.checkPermission(Permission.PERMISSIONS, "view")) return@get
            val userId = runCatching {
                UUID.fromString(call.parameters["userId"])
            }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)

            // Получаем роль пользователя
            val userRole = transaction {
                UsersTable.selectAll()
                    .where { UsersTable.id eq userId }
                    .singleOrNull()?.get(UsersTable.role)
            } ?: return@get call.respond(HttpStatusCode.NotFound, "User not found")

            // Получаем roleId
            val roleId = transaction {
                RolesTable.selectAll()
                    .where { RolesTable.name eq userRole }
                    .singleOrNull()?.get(RolesTable.id)?.value
            }

            // Получаем все права роли
            val rolePermissions: Map<String, Map<String, Boolean>> = if (roleId != null) {
                transaction {
                    RolePermissionsTable.selectAll()
                        .where { RolePermissionsTable.roleId eq roleId }
                        .associate { row ->
                            row[RolePermissionsTable.permission] to mapOf(
                                "canView" to row[RolePermissionsTable.canView],
                                "canCreate" to row[RolePermissionsTable.canCreate],
                                "canEdit" to row[RolePermissionsTable.canEdit],
                                "canDelete" to row[RolePermissionsTable.canDelete]
                            )
                        }
                }
            } else emptyMap()

            // Получаем overrides пользователя
            val overrides: Map<String, Map<String, Boolean?>> = transaction {
                UserPermissionOverridesTable.selectAll()
                    .where { UserPermissionOverridesTable.userId eq userId }
                    .associate { row ->
                        row[UserPermissionOverridesTable.permission] to mapOf(
                            "canView" to row[UserPermissionOverridesTable.canView],
                            "canCreate" to row[UserPermissionOverridesTable.canCreate],
                            "canEdit" to row[UserPermissionOverridesTable.canEdit],
                            "canDelete" to row[UserPermissionOverridesTable.canDelete]
                        )
                    }
            }

            // 🎯 Формируем список UserEffectivePermissionDto (без mapOf!)
            val result = Permission.entries.map { perm ->
                val rolePerm = rolePermissions[perm.key]
                val override = overrides[perm.key]

                UserEffectivePermissionDto(
                    permission = perm.key,
                    canView = override?.get("canView") ?: rolePerm?.get("canView") ?: false,
                    canCreate = override?.get("canCreate") ?: rolePerm?.get("canCreate") ?: false,
                    canEdit = override?.get("canEdit") ?: rolePerm?.get("canEdit") ?: false,
                    canDelete = override?.get("canDelete") ?: rolePerm?.get("canDelete") ?: false,
                    isOverride = override != null
                )
            }

            call.respond(HttpStatusCode.OK, result)
        }

        put("/users/{userId}/permissions") {
            if (!call.checkPermission(Permission.PERMISSIONS, "edit")) return@put
            val userId = runCatching {
                UUID.fromString(call.parameters["userId"])
            }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)

            println("📥 PUT /users/$userId/permissions: receiving overrides")

            // 🎯 Принимаем List<UserPermissionOverrideDto> вместо Map<String, Any?>
            val body = try { call.receive<List<UserPermissionOverrideDto>>() }
            catch (e: Exception) {
                println("❌ PUT /users/$userId: bad JSON: ${e.message}")
                return@put call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
            }

            println("📥 PUT /users/$userId: received ${body.size} overrides")

            transaction {
                // Удаляем все старые overrides пользователя
                UserPermissionOverridesTable.deleteWhere {
                    UserPermissionOverridesTable.userId eq userId
                }

                // Вставляем новые
                body.forEach { item ->
                    // Если все поля null — пропускаем (используется значение из роли)
                    if (item.canView == null && item.canCreate == null &&
                        item.canEdit == null && item.canDelete == null) {
                        return@forEach
                    }

                    UserPermissionOverridesTable.insert {
                        it[UserPermissionOverridesTable.id] = UUID.randomUUID()
                        it[UserPermissionOverridesTable.userId] = userId
                        it[UserPermissionOverridesTable.permission] = item.permission
                        it[UserPermissionOverridesTable.canView] = item.canView
                        it[UserPermissionOverridesTable.canCreate] = item.canCreate
                        it[UserPermissionOverridesTable.canEdit] = item.canEdit
                        it[UserPermissionOverridesTable.canDelete] = item.canDelete
                    }
                    println("  ✅ Override set for ${item.permission}: V=${item.canView} C=${item.canCreate} E=${item.canEdit} D=${item.canDelete}")
                }
            }

            PermissionMiddleware.invalidateUserCache(userId)
            println("✅ User $userId overrides updated")
            call.respond(HttpStatusCode.OK)
        }

        // Получить все доступные permissions (из enum)
        get("/permissions") {
            if (!call.checkPermission(Permission.PERMISSIONS, "view")) return@get
            val permissions = Permission.entries.map { perm ->   // ✅ Используем импорт + явный параметр
                PermissionDto(key = perm.key, displayName = perm.displayName)
            }
            call.respond(HttpStatusCode.OK, permissions)
        }
    }
}