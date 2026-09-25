package com.proles.server.routes

import com.proles.server.data.FcmTokensTable
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

internal fun Route.fcmRoutes() {
    route("/api/v1/fcm") {
        post("/register") {
            val session = call.requireSession() ?: return@post

            val body = try {
                call.receive<Map<String, String>>()
            } catch (e: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid JSON: ${e.message}"
                )
            }

            val token = body["token"]?.trim()
            if (token.isNullOrEmpty()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Token required"
                )
            }

            try {
                transaction {
                    FcmTokensTable.deleteWhere {
                        FcmTokensTable.token eq token
                    }

                    FcmTokensTable.insert {
                        it[id] = UUID.randomUUID()
                        it[userId] = session.userId
                        it[FcmTokensTable.token] = token
                        it[createdAt] = System.currentTimeMillis()
                        it[lastUsed] = System.currentTimeMillis()
                    }
                }

                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    "DB error: ${e.message}"
                )
            }
        }
    }
}