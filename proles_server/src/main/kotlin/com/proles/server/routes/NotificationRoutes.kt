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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

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
                        (NotificationsTable.targetUserId.isNull()) or
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
            val req = try {
                call.receive<CreateNotificationRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest)
            }

            val preferenceKey = when (req.type.uppercase()) {
                "VACATION", "VACATION_CREATED", "VACATION_UPDATED" -> "vacation"
                "TRIP", "TRIP_CREATED" -> "trip"
                "TRIP_COMPLETED", "TRIP_UPDATED" -> "tripChange"
                "DAYOFF_WEEKDAY" -> "dayoff"
                "TICKET", "TICKET_CREATED" -> "ticket"
                "TICKET_RECEIPT" -> "ticketReceipt"
                "EXPENSE", "EXPENSE_CREATED", "EXPENSE_UPDATED" -> "expense"
                "PAYROLL", "SALARY" -> "payroll"
                "CHAT", "CHAT_MESSAGE" -> "chatMessage"
                "VACATION_DECISION" -> "vacationDecision"
                else -> ""
            }

            val targetUserIds = transaction {
                if (req.type.uppercase() in setOf("VACATION", "TRIP", "DAYOFF_WEEKDAY")) {
                    UsersTable.selectAll()
                        .where {
                            (UsersTable.role eq "admin") or
                                (UsersTable.role eq "director")
                        }
                        .map { it[UsersTable.id].value }
                        .filter { it != session.userId }
                } else {
                    UsersTable.selectAll()
                        .map { it[UsersTable.id].value }
                        .filter { it != session.userId }
                }
            }

            NotificationService.notifyUsers(
                targetUserIds = targetUserIds,
                senderUserId = session.userId,
                type = req.type,
                title = req.title,
                message = req.message,
                payload = req.payload,
                preferenceKey = preferenceKey,
                deliverTelegram = false
            )

            call.respond(
                HttpStatusCode.Created,
                mapOf("sent" to targetUserIds.size)
            )
        }
        post("/mark-read") {
            val session = call.checkSession() ?: return@post
            val body = call.receive<Map<String, String>>()
            val notifId = body["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            transaction {
                NotificationsTable.update({
                    (NotificationsTable.id eq UUID.fromString(notifId)) and
                            ((NotificationsTable.targetUserId eq session.userId) or
                                    (NotificationsTable.targetUserId.isNull()))
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
                            (NotificationsTable.targetUserId.isNull())) and
                            (NotificationsTable.isRead eq false)
                }) { it[isRead] = true }
            }
            call.respond(HttpStatusCode.OK)
        }
    }
}