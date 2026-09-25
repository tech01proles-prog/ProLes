package com.proles.server.routes

import com.proles.server.config.FirebaseService
import com.proles.server.config.PermissionMiddleware
import com.proles.server.config.SessionManager
import com.proles.server.data.*
import com.proles.server.model.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

private suspend fun ApplicationCall.checkNotificationSession():
    SessionManager.Session? {
    val token = request.headers["X-Session-Token"]
    val session = SessionManager.validate(token)

    if (session == null) {
        respond(HttpStatusCode.Unauthorized, "Session expired")
        return null
    }

    return session
}

internal fun Route.notificationRoutes() {
    route("/api/v1/notifications") {
        get {
            val session = call.checkSession() ?: return@get

            // 🔐 Используем RBAC вместо прямой проверки роли
            val canViewAll = com.proles.server.config.PermissionMiddleware
                .canView(session.userId, Permission.NOTIFICATIONS) &&
                    session.role in listOf("admin", "director", "superadmin")

            val list = transaction {
                val baseQuery = NotificationsTable.selectAll()
                val query = if (canViewAll) {
                    // Админы видят все уведомления (личные + общие)
                    baseQuery.where {
                        (NotificationsTable.targetUserId eq null) or
                                (NotificationsTable.targetUserId eq session.userId)
                    }
                } else {
                    // Обычные сотрудники — только свои
                    baseQuery.where { NotificationsTable.targetUserId eq session.userId }
                }
                query.orderBy(NotificationsTable.createdAt to SortOrder.DESC)
                    .map { row ->
                        val senderId = row[NotificationsTable.senderUserId].value
                        val senderName = runCatching {
                            UsersTable.selectAll().where { UsersTable.id eq senderId }.single()[UsersTable.name]
                        }.getOrDefault("")
                        NotificationDto(
                            id = row[NotificationsTable.id].value.toString(),
                            targetUserId = row[NotificationsTable.targetUserId]?.value?.toString(),
                            senderUserId = senderId.toString(),
                            senderName = senderName,
                            type = row[NotificationsTable.type],
                            title = row[NotificationsTable.title],
                            message = row[NotificationsTable.message],
                            payload = row[NotificationsTable.payload],
                            isRead = row[NotificationsTable.isRead],
                            createdAt = row[NotificationsTable.createdAt]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        post {
            val session = call.checkSession() ?: return@post
            val req = try { call.receive<CreateNotificationRequest>() }
            catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest) }

            val targetUserIds = transaction {
                if (req.type in listOf("VACATION", "TRIP", "DAYOFF_WEEKDAY")) {
                    UsersTable.selectAll()
                        .where { (UsersTable.role eq "admin") or (UsersTable.role eq "director") }
                        .map { it[UsersTable.id].value }
                        .filter { it != session.userId }
                } else emptyList()
            }

            transaction {
                val targets = if (targetUserIds.isEmpty()) listOf<UUID?>(null)
                else targetUserIds.map { it }
                targets.forEach { targetId ->
                    NotificationsTable.insert {
                        it[id] = UUID.randomUUID()
                        it[targetUserId] = targetId
                        it[senderUserId] = session.userId
                        it[type] = req.type
                        it[title] = req.title
                        it[message] = req.message
                        it[payload] = req.payload
                        it[createdAt] = System.currentTimeMillis()
                    }
                }
            }

            // ✅ ОТПРАВКА ПУШЕЙ С ЛОГИРОВАНИЕМ
            CoroutineScope(Dispatchers.IO).launch {
                println("🔔 Начинаем отправку FCM пушей для уведомления типа ${req.type}")

                val tokens = transaction {
                    FcmTokensTable.selectAll().map { it[FcmTokensTable.token] }
                }

                println("📱 Найдено токенов в БД: ${tokens.size}")
                if (tokens.isEmpty()) {
                    println("⚠️ В БД нет ни одного FCM токена! Клиенты не зарегистрировались.")
                }

                tokens.forEach { token ->
                    FirebaseService.sendPush(
                        token = token,
                        title = req.title,
                        body = req.message,
                        data = mapOf("type" to req.type, "payload" to req.payload)
                    )
                }
            }

            call.respond(HttpStatusCode.Created, mapOf("sent" to targetUserIds.size))
        }

        post("/mark-read") {
            val session = call.checkSession() ?: return@post
            val body = call.receive<Map<String, String>>()
            val notifId = body["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            transaction {
                NotificationsTable.update({
                    (NotificationsTable.id eq UUID.fromString(notifId)) and
                            ((NotificationsTable.targetUserId eq session.userId) or
                                    (NotificationsTable.targetUserId eq null))
                }) { it[isRead] = true }
            }
            call.respond(HttpStatusCode.OK)
        }

        post("/mark-all-read") {
            val session = call.checkSession() ?: return@post
            transaction {
                NotificationsTable.update({
                    // 🔥 ИСПРАВЛЕНО: помечаем и личные, и общие уведомления
                    ((NotificationsTable.targetUserId eq session.userId) or
                            (NotificationsTable.targetUserId eq null)) and
                            (NotificationsTable.isRead eq false)
                }) { it[isRead] = true }
            }
            call.respond(HttpStatusCode.OK)
        }
    }
}