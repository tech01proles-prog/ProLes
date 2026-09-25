package com.proles.server.routes

import com.proles.server.config.SessionManager
import com.proles.server.data.NotificationPreferencesTable
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
data class NotificationPreferencesResponseDto(
    val tripEnabled: Boolean,
    val vacationEnabled: Boolean,
    val dayoffEnabled: Boolean,
    val expenseEnabled: Boolean,
    val payrollEnabled: Boolean,
    val ticketEnabled: Boolean,
    val tripVisibleToAll: Boolean,
    val tripTelegramBroadcast: Boolean,
    val tripChangeEnabled: Boolean,
    val vacationDecisionEnabled: Boolean,
    val expenseCreatedEnabled: Boolean,
    val ticketReceiptEnabled: Boolean,
    val telegramEnabled: Boolean,
    val telegramLinked: Boolean,
    val telegramLinkCode: String?,
    val emailEnabled: Boolean,
    val email: String
)

@Serializable
data class NotificationPreferencesUpdateResponseDto(
    val telegramLinkCode: String?,
    val telegramEnabled: Boolean
)

private suspend fun ApplicationCall.checkPreferenceSession():
    SessionManager.Session? {
    val token = request.headers["X-Session-Token"]
    val session = SessionManager.validate(token)

    if (session == null) {
        respond(HttpStatusCode.Unauthorized, "Session expired")
        return null
    }

    return session
}

internal fun Route.notificationPreferenceRoutes() {
    route("/api/v1/notification-preferences") {
        get {
            val session = call.checkPreferenceSession() ?: return@get
            val row = transaction { NotificationPreferencesTable.selectAll().where { NotificationPreferencesTable.userId eq session.userId }.limit(1).firstOrNull() }
            call.respond(HttpStatusCode.OK, NotificationPreferencesResponseDto(
                tripEnabled = row?.get(NotificationPreferencesTable.tripEnabled) ?: true,
                vacationEnabled = row?.get(NotificationPreferencesTable.vacationEnabled) ?: true,
                dayoffEnabled = row?.get(NotificationPreferencesTable.dayoffEnabled) ?: true,
                expenseEnabled = row?.get(NotificationPreferencesTable.expenseEnabled) ?: true,
                payrollEnabled = row?.get(NotificationPreferencesTable.payrollEnabled) ?: true,
                ticketEnabled = row?.get(NotificationPreferencesTable.ticketEnabled) ?: true,
                tripVisibleToAll = if (row?.get(NotificationPreferencesTable.privacyDefaultsConfigured) == true) row[NotificationPreferencesTable.tripVisibleToAll] else true,
                tripTelegramBroadcast = if (row?.get(NotificationPreferencesTable.privacyDefaultsConfigured) == true) row[NotificationPreferencesTable.tripTelegramBroadcast] else true,
                tripChangeEnabled = row?.get(NotificationPreferencesTable.tripChangeEnabled) ?: true,
                vacationDecisionEnabled = row?.get(NotificationPreferencesTable.vacationDecisionEnabled) ?: true,
                expenseCreatedEnabled = row?.get(NotificationPreferencesTable.expenseCreatedEnabled) ?: true,
                ticketReceiptEnabled = row?.get(NotificationPreferencesTable.ticketReceiptEnabled) ?: true,
                telegramEnabled = row?.get(NotificationPreferencesTable.telegramEnabled) ?: false,
                telegramLinked = row?.get(NotificationPreferencesTable.telegramChatId) != null,
                telegramLinkCode = row?.get(NotificationPreferencesTable.telegramLinkCode),
                emailEnabled = row?.get(NotificationPreferencesTable.emailEnabled) ?: false,
                email = row?.get(NotificationPreferencesTable.email) ?: ""
            ))
        }
        put {
            val session = call.checkPreferenceSession() ?: return@put
            val body = try { call.receive<Map<String, String>>() } catch (e: Exception) { return@put call.respond(HttpStatusCode.BadRequest, "Invalid JSON") }
            var generatedCode: String? = null
            transaction {
                val existing = NotificationPreferencesTable.selectAll().where { NotificationPreferencesTable.userId eq session.userId }.limit(1).firstOrNull()
                val telegramEnabled = body["telegramEnabled"]?.toBoolean() ?: existing?.get(NotificationPreferencesTable.telegramEnabled) ?: false
                generatedCode = if (telegramEnabled && existing?.get(NotificationPreferencesTable.telegramLinkCode).isNullOrBlank() && existing?.get(NotificationPreferencesTable.telegramChatId).isNullOrBlank()) {
                    (10000000L..99999999L).random().toString()
                } else existing?.get(NotificationPreferencesTable.telegramLinkCode)
                if (existing == null) {
                    NotificationPreferencesTable.insert {
                        it[id] = UUID.randomUUID(); it[userId] = session.userId
                        it[tripEnabled] = body["tripEnabled"]?.toBoolean() ?: true
                        it[vacationEnabled] = body["vacationEnabled"]?.toBoolean() ?: true
                        it[dayoffEnabled] = body["dayoffEnabled"]?.toBoolean() ?: true
                        it[expenseEnabled] = body["expenseEnabled"]?.toBoolean() ?: true
                        it[payrollEnabled] = body["payrollEnabled"]?.toBoolean() ?: true
                        it[ticketEnabled] = body["ticketEnabled"]?.toBoolean() ?: true
                        it[tripVisibleToAll] = body["tripVisibleToAll"]?.toBoolean() ?: true
                        it[tripTelegramBroadcast] = body["tripTelegramBroadcast"]?.toBoolean() ?: true
                        it[tripChangeEnabled] = body["tripChangeEnabled"]?.toBoolean() ?: true
                        it[vacationDecisionEnabled] = body["vacationDecisionEnabled"]?.toBoolean() ?: true
                        it[expenseCreatedEnabled] = body["expenseCreatedEnabled"]?.toBoolean() ?: true
                        it[ticketReceiptEnabled] = body["ticketReceiptEnabled"]?.toBoolean() ?: true
                        it[privacyDefaultsConfigured] = true
                        it[NotificationPreferencesTable.telegramEnabled] = telegramEnabled
                        it[telegramLinkCode] = generatedCode
                        it[emailEnabled] = body["emailEnabled"]?.toBoolean() ?: false
                        it[email] = body["email"] ?: ""
                    }
                } else {
                    NotificationPreferencesTable.update({ NotificationPreferencesTable.userId eq session.userId }) {
                        it[tripEnabled] = body["tripEnabled"]?.toBoolean() ?: existing[tripEnabled]
                        it[vacationEnabled] = body["vacationEnabled"]?.toBoolean() ?: existing[vacationEnabled]
                        it[dayoffEnabled] = body["dayoffEnabled"]?.toBoolean() ?: existing[dayoffEnabled]
                        it[expenseEnabled] = body["expenseEnabled"]?.toBoolean() ?: existing[expenseEnabled]
                        it[payrollEnabled] = body["payrollEnabled"]?.toBoolean() ?: existing[payrollEnabled]
                        it[ticketEnabled] = body["ticketEnabled"]?.toBoolean() ?: existing[ticketEnabled]
                        it[tripVisibleToAll] = body["tripVisibleToAll"]?.toBoolean() ?: existing[tripVisibleToAll]
                        it[tripTelegramBroadcast] = body["tripTelegramBroadcast"]?.toBoolean() ?: existing[tripTelegramBroadcast]
                        it[tripChangeEnabled] = body["tripChangeEnabled"]?.toBoolean() ?: existing[tripChangeEnabled]
                        it[vacationDecisionEnabled] = body["vacationDecisionEnabled"]?.toBoolean() ?: existing[vacationDecisionEnabled]
                        it[expenseCreatedEnabled] = body["expenseCreatedEnabled"]?.toBoolean() ?: existing[expenseCreatedEnabled]
                        it[ticketReceiptEnabled] = body["ticketReceiptEnabled"]?.toBoolean() ?: existing[ticketReceiptEnabled]
                        it[privacyDefaultsConfigured] = true
                        it[NotificationPreferencesTable.telegramEnabled] = telegramEnabled
                        if (generatedCode != null) it[telegramLinkCode] = generatedCode
                        it[emailEnabled] = body["emailEnabled"]?.toBoolean() ?: existing[emailEnabled]
                        it[email] = body["email"] ?: existing[email]
                    }
                }
            }
            call.respond(
                HttpStatusCode.OK,
                NotificationPreferencesUpdateResponseDto(
                    telegramLinkCode = generatedCode,
                    telegramEnabled = body["telegramEnabled"]?.toBoolean() ?: false
                )
            )
        }
    }
}