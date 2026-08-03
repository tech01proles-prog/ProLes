package com.proles.server.config

import com.proles.server.data.*
import com.proles.server.model.Permission
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Middleware для проверки прав доступа (RBAC).
 *
 * Логика:
 * 1. Сначала проверяем персональные override'ы пользователя
 * 2. Если override нет — берём права из роли пользователя
 * 3. Супер-админы (role = "superadmin") имеют все права
 */
object PermissionMiddleware {

    // 🚀 Кеш прав: userId -> Map<permissionKey, Actions>
    // Обновляется раз в 60 секунд или при изменении прав
    private val cache = ConcurrentHashMap<UUID, CachedPermissions>()
    private const val CACHE_TTL_MS = 60_000L

    private data class CachedPermissions(
        val permissions: Map<String, Actions>,
        val role: String,
        val timestamp: Long
    )

    private data class Actions(
        val canView: Boolean,
        val canCreate: Boolean,
        val canEdit: Boolean,
        val canDelete: Boolean
    )

    /**
     * Главная функция проверки прав.
     * Возвращает true, если доступ разрешён, false — если запрещён (и сам отправляет 403).
     */
    suspend fun ApplicationCall.checkPermission(
        permission: Permission,
        action: String = "view"
    ): Boolean {
        // 1. Получаем сессию (аналог checkSession)
        val token = this.request.headers["X-Session-Token"]
        val session = SessionManager.validate(token)
        if (session == null) {
            this.respond(HttpStatusCode.Unauthorized, "Session expired")
            return false
        }

        // 2. Супер-админ имеет все права
        if (session.role == "superadmin") return true

        // 3. Получаем права (из кеша или БД)
        val userPermissions = getPermissionsForUser(session.userId, session.role)

        // 4. Проверяем конкретное действие
        val actions = userPermissions[permission.key]
        val allowed = when (action) {
            "view" -> actions?.canView ?: false
            "create" -> actions?.canCreate ?: false
            "edit" -> actions?.canEdit ?: false
            "delete" -> actions?.canDelete ?: false
            else -> false
        }

        if (!allowed) {
            this.respond(
                HttpStatusCode.Forbidden,
                mapOf(
                    "error" to "access_denied",
                    "permission" to permission.key,
                    "action" to action,
                    "message" to "У вас нет прав на действие: ${permission.displayName} ($action)"
                )
            )
            return false
        }

        return true
    }

    /**
     * Удобные методы для проверки конкретных действий.
     * Возвращают Boolean без отправки 403 (для серверных проверок без auto-respond).
     */
    fun canView(userId: UUID, permission: Permission): Boolean {
        if (isSuperAdmin(userId)) return true
        val actions = getPermissionsForUser(userId, getUserRole(userId))
        return actions[permission.key]?.canView ?: false
    }

    fun canCreate(userId: UUID, permission: Permission): Boolean {
        if (isSuperAdmin(userId)) return true
        val actions = getPermissionsForUser(userId, getUserRole(userId))
        return actions[permission.key]?.canCreate ?: false
    }

    fun canEdit(userId: UUID, permission: Permission): Boolean {
        if (isSuperAdmin(userId)) return true
        val actions = getPermissionsForUser(userId, getUserRole(userId))
        return actions[permission.key]?.canEdit ?: false
    }

    fun canDelete(userId: UUID, permission: Permission): Boolean {
        if (isSuperAdmin(userId)) return true
        val actions = getPermissionsForUser(userId, getUserRole(userId))
        return actions[permission.key]?.canDelete ?: false
    }

    private fun isSuperAdmin(userId: UUID): Boolean {
        return transaction {
            UsersTable.selectAll().where { UsersTable.id eq userId }
                .singleOrNull()?.get(UsersTable.role) == "superadmin"
        }
    }

    private fun getUserRole(userId: UUID): String {
        return transaction {
            UsersTable.selectAll().where { UsersTable.id eq userId }
                .singleOrNull()?.get(UsersTable.role) ?: "employee"
        }
    }

    /**
     * Публичный метод для API: возвращает права пользователя.
     * Формат ответа: Map<permissionKey, Map<action, Boolean>>
     */
    fun getPermissionsPublic(userId: UUID, role: String): Map<String, Map<String, Boolean>> {
        val actions = getPermissionsForUser(userId, role)
        return actions.mapValues { (_, a) ->
            mapOf(
                "canView" to a.canView,
                "canCreate" to a.canCreate,
                "canEdit" to a.canEdit,
                "canDelete" to a.canDelete
            )
        }
    }


    /**
     * Получение всех прав пользователя с кешированием.
     */
    private fun getPermissionsForUser(
        userId: UUID,
        role: String
    ): Map<String, Actions> {
        val now = System.currentTimeMillis()
        val cached = cache[userId]

        // Используем кеш, если он свежий
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            return cached.permissions
        }

        // Иначе загружаем из БД
        val permissions = transaction { loadPermissionsFromDb(userId, role) }
        cache[userId] = CachedPermissions(permissions, role, now)
        return permissions
    }

    /**
     * Загрузка прав из БД: сначала override'ы, затем роль.
     */
    private fun loadPermissionsFromDb(userId: UUID, role: String): Map<String, Actions> {
        val result = mutableMapOf<String, Actions>()

        // 1. Получаем права роли
        val rolePermissions = (RolePermissionsTable innerJoin RolesTable)
            .selectAll()
            .where { RolesTable.name eq role }
            .toList()

        rolePermissions.forEach { row ->
            val key = row[RolePermissionsTable.permission]
            result[key] = Actions(
                canView = row[RolePermissionsTable.canView],
                canCreate = row[RolePermissionsTable.canCreate],
                canEdit = row[RolePermissionsTable.canEdit],
                canDelete = row[RolePermissionsTable.canDelete]
            )
        }

        // 2. Применяем override'ы пользователя (только те, что не null)
        val overrides = UserPermissionOverridesTable.selectAll()
            .where { UserPermissionOverridesTable.userId eq userId }
            .toList()

        overrides.forEach { row ->
            val key = row[UserPermissionOverridesTable.permission]
            val current = result[key] ?: Actions(false, false, false, false)

            result[key] = Actions(
                canView = row[UserPermissionOverridesTable.canView] ?: current.canView,
                canCreate = row[UserPermissionOverridesTable.canCreate] ?: current.canCreate,
                canEdit = row[UserPermissionOverridesTable.canEdit] ?: current.canEdit,
                canDelete = row[UserPermissionOverridesTable.canDelete] ?: current.canDelete
            )
        }

        return result
    }

    /**
     * Сброс кеша прав конкретного пользователя.
     * Вызывайте после изменения прав через UI.
     */
    fun invalidateUserCache(userId: UUID) {
        cache.remove(userId)
    }

    /**
     * Сброс всего кеша (например, после изменения ролей).
     */
    fun invalidateAllCache() {
        cache.clear()
    }
}