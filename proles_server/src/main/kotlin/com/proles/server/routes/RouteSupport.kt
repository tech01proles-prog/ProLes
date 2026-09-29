package com.proles.server.routes

import com.proles.server.config.SessionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.util.UUID

internal suspend fun ApplicationCall.checkSession():
    SessionManager.Session? {
    val token = request.headers["X-Session-Token"]
    val session = SessionManager.validate(token)

    if (session == null) {
        respond(HttpStatusCode.Unauthorized, "Session expired")
        return null
    }

    return session
}

internal fun String?.toUuidOrNull(): UUID? =
    this
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { value ->
            runCatching { UUID.fromString(value) }.getOrNull()
        }