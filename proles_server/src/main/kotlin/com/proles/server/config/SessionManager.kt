package com.proles.server.config

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import com.proles.server.data.SessionsTable
import java.util.UUID

/**
 * 🔐 Персистентный менеджер сессий.
 * Сессии хранятся в БД, переживают перезапуск сервера.
 */
object SessionManager {

    data class Session(
        val userId: UUID,
        val role: String,
        val expiresAt: Long,
        val rememberMe: Boolean = false
    )

    /**
     * Создаёт новую сессию и сохраняет её в БД.
     * @param rememberMe Если true — сессия живёт 30 дней, иначе 7 дней
     */
    fun create(userId: UUID, role: String, rememberMe: Boolean, deviceInfo: String = ""): String {
        val token = UUID.randomUUID().toString()
        // 🆕 Увеличенные TTL: 30 дней для rememberMe, 7 дней для обычной сессии
        val ttl = if (rememberMe) 30L * 24 * 60 * 60 * 1000 else 7L * 24 * 60 * 60 * 1000
        val now = System.currentTimeMillis()

        transaction {
            // Удаляем старые истёкшие сессии этого пользователя (опционально, для чистоты БД)
            SessionsTable.deleteWhere {
                (SessionsTable.userId eq userId) and (SessionsTable.expiresAt less now)
            }

            // Создаём новую сессию
            SessionsTable.insert {
                it[SessionsTable.id] = UUID.randomUUID()
                it[SessionsTable.userId] = userId
                it[SessionsTable.token] = token
                it[SessionsTable.role] = role
                it[SessionsTable.expiresAt] = now + ttl
                it[SessionsTable.rememberMe] = rememberMe
                it[SessionsTable.createdAt] = now
                it[SessionsTable.lastUsed] = now
                it[SessionsTable.deviceInfo] = deviceInfo.take(200)
            }
        }

        println("🔐 Session created for userId=$userId, rememberMe=$rememberMe, ttl=${ttl / (24 * 60 * 60 * 1000)} days")
        return token
    }

    /**
     * Валидирует токен: читает из БД, обновляет lastUsed.
     * Если сессия истекла — удаляет её из БД.
     */
    fun validate(token: String?): Session? {
        if (token.isNullOrBlank()) return null

        return try {
            transaction {
                val row = SessionsTable.selectAll()
                    .where { SessionsTable.token eq token }
                    .singleOrNull() ?: return@transaction null

                val expiresAt = row[SessionsTable.expiresAt]
                val now = System.currentTimeMillis()

                // Сессия истекла — удаляем
                if (now > expiresAt) {
                    SessionsTable.deleteWhere { SessionsTable.token eq token }
                    return@transaction null
                }

                // Обновляем lastUsed (раз в час, чтобы не спамить БД)
                val lastUsed = row[SessionsTable.lastUsed]
                if (now - lastUsed > 60 * 60 * 1000) {
                    SessionsTable.update({ SessionsTable.token eq token }) {
                        it[SessionsTable.lastUsed] = now
                    }
                }

                Session(
                    userId = row[SessionsTable.userId].value,
                    role = row[SessionsTable.role],
                    expiresAt = expiresAt,
                    rememberMe = row[SessionsTable.rememberMe]
                )
            }
        } catch (e: Exception) {
            println("❌ Session validation error: ${e.message}")
            null
        }
    }

    /**
     * Инвалидирует (удаляет) сессию из БД.
     * Вызывается при явном logout.
     */
    fun invalidate(token: String?) {
        if (token.isNullOrBlank()) return
        try {
            transaction {
                SessionsTable.deleteWhere { SessionsTable.token eq token }
            }
            println("🗑️ Session invalidated: ${token.take(10)}...")
        } catch (e: Exception) {
            println("⚠️ Failed to invalidate session: ${e.message}")
        }
    }

    /**
     * Удаляет все сессии пользователя (при удалении аккаунта).
     */
    fun invalidateAllForUser(userId: UUID) {
        try {
            transaction {
                SessionsTable.deleteWhere { SessionsTable.userId eq userId }
            }
        } catch (e: Exception) {
            println("⚠️ Failed to invalidate all sessions for $userId: ${e.message}")
        }
    }

    /**
     * Периодическая очистка истёкших сессий (вызывать раз в сутки).
     */
    fun cleanupExpiredSessions() {
        try {
            val now = System.currentTimeMillis()
            val deleted = transaction {
                SessionsTable.deleteWhere { SessionsTable.expiresAt less now }
            }
            if (deleted > 0) {
                println("🧹 Cleaned up $deleted expired sessions")
            }
        } catch (e: Exception) {
            println("⚠️ Cleanup failed: ${e.message}")
        }
    }
}