package com.proles.server.routes

import com.proles.server.config.SessionManager
import com.proles.server.data.UsersTable
import com.proles.server.model.AuthResponse
import com.proles.server.model.LoginRequest
import com.proles.server.model.UserDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import at.favre.lib.crypto.bcrypt.BCrypt

fun Route.authRoutes() {
    route("/api/v1/auth") {
        post("/login") {
            val req = call.receive<LoginRequest>()
            val user = transaction {
                UsersTable.selectAll()
                    .where { (UsersTable.email eq req.email) or (UsersTable.login eq req.email) }
                    .singleOrNull()
            }

            if (user != null && BCrypt.verifyer().verify(req.password.toCharArray(), user[UsersTable.passwordHash]).verified) {
                val userId = user[UsersTable.id].value
                val deviceInfo = call.request.headers["User-Agent"] ?: "Unknown"
                val token = SessionManager.create(userId, user[UsersTable.role], req.rememberMe, deviceInfo)
                val expiresAt = SessionManager.validate(token)!!.expiresAt

                call.respond(HttpStatusCode.OK, AuthResponse(
                    token = token,
                    expiresAt = expiresAt,
                    user = UserDto(
                        id = userId.toString(),
                        lastName = user[UsersTable.lastName],
                        firstName = user[UsersTable.firstName],
                        middleName = user[UsersTable.middleName],
                        name = user[UsersTable.name],
                        login = user[UsersTable.login],
                        role = user[UsersTable.role],
                        position = user[UsersTable.position] ?: "",
                        defaultRateType = user[UsersTable.defaultRateType],
                        defaultRate = user[UsersTable.defaultRate],
                        defaultCurrency = user[UsersTable.defaultCurrency]
                    )
                ))
            } else {
                call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
            }
        }

        get("/me") {
            val token = call.request.headers["X-Session-Token"]
            val session = SessionManager.validate(token)
            if (session == null) return@get call.respond(HttpStatusCode.Unauthorized, "Session expired")

            val user = transaction {
                UsersTable.selectAll().where { UsersTable.id eq session.userId }.singleOrNull()
            }

            user?.let {
                call.respond(HttpStatusCode.OK, UserDto(
                    id = it[UsersTable.id].value.toString(),
                    lastName = it[UsersTable.lastName],
                    firstName = it[UsersTable.firstName],
                    middleName = it[UsersTable.middleName],
                    name = it[UsersTable.name],
                    login = it[UsersTable.login],
                    role = it[UsersTable.role],
                    position = it[UsersTable.position] ?: "",
                    defaultRateType = it[UsersTable.defaultRateType],
                    defaultRate = it[UsersTable.defaultRate],
                    defaultCurrency = it[UsersTable.defaultCurrency]
                ))
            } ?: call.respond(HttpStatusCode.NotFound)
        }

        post("/logout") {
            val token = call.request.headers["X-Session-Token"]
            SessionManager.invalidate(token)
            call.respond(HttpStatusCode.OK, "Logged out")
        }
    }
}