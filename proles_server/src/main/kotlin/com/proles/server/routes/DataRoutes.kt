package com.proles.server.routes

import com.proles.server.config.SessionManager
import com.proles.server.data.*
import com.proles.server.model.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.datetime.LocalDate as KtLocalDate
import java.time.LocalDate as JavaLocalDate
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import io.ktor.http.content.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.proles.server.config.FirebaseService
import com.proles.server.config.NotificationService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receive
import com.proles.server.config.TelegramService
import java.util.Base64
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import com.proles.server.config.PermissionMiddleware
import com.proles.server.config.PermissionMiddleware.checkPermission  //  extension-функция
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList  // 
import org.jetbrains.exposed.dao.id.EntityID  //  для работы с ID

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private suspend fun ApplicationCall.checkSession(vararg allowedRoles: String): SessionManager.Session? {
    val token = this.request.headers["X-Session-Token"]
    val session = SessionManager.validate(token)
    if (session == null) {
        this.respond(HttpStatusCode.Unauthorized, "Session expired")
        return null
    }
    if (allowedRoles.isNotEmpty() && session.role !in allowedRoles) {
        this.respond(HttpStatusCode.Forbidden, "Access denied")
        return null
    }
    return session
}

private fun String?.toUuidOrNull(): UUID? =
    this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }


fun Route.dataRoutes() {
    fcmRoutes()
    timeEntryRoutes()
    projectCostRoutes()
    projectRoutes()
    expenseRoutes()
    incomeRoutes()
    positionRoutes()
    userRoutes()
    absenceRoutes()
    businessTripRoutes()
    notificationRoutes()
    personalTimesheetRoutes()
    notificationPreferenceRoutes()
    permissionRoutes()
    ticketRoutes()
    payrollRoutes()
}