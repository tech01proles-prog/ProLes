package com.proles.server.routes

import com.proles.server.config.PermissionMiddleware
import com.proles.server.config.SessionManager
import com.proles.server.data.IncomesTable
import com.proles.server.data.ProjectsTable
import com.proles.server.data.SubprojectsTable
import com.proles.server.model.IncomeDto
import com.proles.server.model.Permission
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDate as KtLocalDate
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

private suspend fun ApplicationCall.checkIncomeSession():
    SessionManager.Session? {
    val token = request.headers["X-Session-Token"]
    val session = SessionManager.validate(token)

    if (session == null) {
        respond(HttpStatusCode.Unauthorized, "Session expired")
        return null
    }

    return session
}

private fun String?.toIncomeUuidOrNull(): UUID? =
    this
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { value ->
            runCatching { UUID.fromString(value) }.getOrNull()
        }

private fun normalizeIncomeCategory(value: String?): String =
    when (value.orEmpty().trim().uppercase()) {
        "PERSONAL", "WITHOUT_RECEIPT" -> "WITHOUT_RECEIPT"
        else -> "WITH_RECEIPT"
    }

private fun validateIncomeSubproject(
    projectId: UUID?,
    subprojectId: UUID?
): Boolean {
    if (subprojectId == null) return true
    if (projectId == null) return false

    return validateActiveSubproject(projectId, subprojectId)
}

private fun incomeSubprojectName(row: ResultRow): String {
    val subprojectId = row[IncomesTable.subprojectId]?.value
        ?: return ""

    return SubprojectsTable
        .selectAll()
        .where { SubprojectsTable.id eq subprojectId }
        .limit(1)
        .singleOrNull()
        ?.get(SubprojectsTable.name)
        .orEmpty()
}

private fun mapRowToIncomeDto(row: ResultRow): IncomeDto =
    IncomeDto(
        id = row[IncomesTable.id].value.toString(),
        userId = row[IncomesTable.userId].value.toString(),
        projectId = row[
            IncomesTable.projectId
        ]?.value?.toString(),
        subprojectId = row[
            IncomesTable.subprojectId
        ]?.value?.toString(),
        subprojectName = incomeSubprojectName(row),
        projectName = row[IncomesTable.projectId]?.value
            ?.let { projectId ->
                ProjectsTable
                    .selectAll()
                    .where { ProjectsTable.id eq projectId }
                    .limit(1)
                    .singleOrNull()
                    ?.get(ProjectsTable.name)
            }
            .orEmpty(),
        date = row[IncomesTable.date].toString(),
        type = row[IncomesTable.type],
        name = row[IncomesTable.name],
        amount = row[IncomesTable.amount],
        currency = row[IncomesTable.currency],
        category = normalizeIncomeCategory(
            row[IncomesTable.category]
        ),
        subcategory = row[IncomesTable.subcategory],
        createdAt = row[IncomesTable.createdAt]
    )

private enum class IncomeDeleteResult {
    NOT_FOUND,
    FORBIDDEN,
    DELETED
}

internal fun Route.incomeRoutes() {
    route("/api/v1/incomes") {
        get("/all") {
            val session = call.checkIncomeSession() ?: return@get

            if (
                !PermissionMiddleware.canView(
                    session.userId,
                    Permission.EXPENSES_ALL
                )
            ) {
                return@get call.respond(
                    HttpStatusCode.Forbidden,
                    "Нет прав на просмотр доходов"
                )
            }

            val dateFromParam =
                call.request.queryParameters["dateFrom"]
            val dateToParam =
                call.request.queryParameters["dateTo"]

            val dateFrom = dateFromParam
                ?.takeIf(String::isNotBlank)
                ?.let {
                    runCatching { KtLocalDate.parse(it) }.getOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid dateFrom"
                        )
                }

            val dateTo = dateToParam
                ?.takeIf(String::isNotBlank)
                ?.let {
                    runCatching { KtLocalDate.parse(it) }.getOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid dateTo"
                        )
                }

            if (
                dateFrom != null &&
                dateTo != null &&
                dateFrom > dateTo
            ) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    "dateFrom must not be later than dateTo"
                )
            }

            val incomes = transaction {
                var condition: Op<Boolean> = Op.TRUE

                if (dateFrom != null) {
                    condition = condition and
                        (IncomesTable.date greaterEq dateFrom)
                }

                if (dateTo != null) {
                    condition = condition and
                        (IncomesTable.date lessEq dateTo)
                }

                IncomesTable
                    .selectAll()
                    .where { condition }
                    .orderBy(
                        IncomesTable.date to SortOrder.DESC,
                        IncomesTable.createdAt to SortOrder.DESC
                    )
                    .map(::mapRowToIncomeDto)
            }

            call.respond(HttpStatusCode.OK, incomes)
        }

        get {
            val session = call.checkIncomeSession() ?: return@get

            val userId = call.request.queryParameters["userId"]
                .toIncomeUuidOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    "Valid userId required"
                )

            if (
                userId != session.userId &&
                session.role !in setOf(
                    "superadmin",
                    "admin",
                    "director"
                )
            ) {
                return@get call.respond(
                    HttpStatusCode.Forbidden,
                    "Access denied"
                )
            }

            val dateFromParam =
                call.request.queryParameters["dateFrom"]
            val dateToParam =
                call.request.queryParameters["dateTo"]

            val dateFrom = dateFromParam
                ?.takeIf(String::isNotBlank)
                ?.let {
                    runCatching { KtLocalDate.parse(it) }.getOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid dateFrom"
                        )
                }

            val dateTo = dateToParam
                ?.takeIf(String::isNotBlank)
                ?.let {
                    runCatching { KtLocalDate.parse(it) }.getOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid dateTo"
                        )
                }

            if (
                dateFrom != null &&
                dateTo != null &&
                dateFrom > dateTo
            ) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    "dateFrom must not be later than dateTo"
                )
            }

            val incomes = transaction {
                var condition: Op<Boolean> =
                    IncomesTable.userId eq userId

                if (dateFrom != null) {
                    condition = condition and
                        (IncomesTable.date greaterEq dateFrom)
                }

                if (dateTo != null) {
                    condition = condition and
                        (IncomesTable.date lessEq dateTo)
                }

                IncomesTable
                    .selectAll()
                    .where { condition }
                    .orderBy(
                        IncomesTable.date to SortOrder.DESC,
                        IncomesTable.createdAt to SortOrder.DESC
                    )
                    .map(::mapRowToIncomeDto)
            }

            call.respond(HttpStatusCode.OK, incomes)
        }

        post {
            val session = call.checkIncomeSession() ?: return@post

            val income = try {
                call.receive<IncomeDto>()
            } catch (e: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid JSON: ${e.message}"
                )
            }

            val userId = income.userId.toIncomeUuidOrNull()
                ?: session.userId

            if (
                userId != session.userId &&
                session.role !in setOf(
                    "superadmin",
                    "admin",
                    "director"
                )
            ) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    "Access denied"
                )
            }

            val projectId = income.projectId.toIncomeUuidOrNull()
            val subprojectId = income.subprojectId.toIncomeUuidOrNull()

            if (income.projectId?.isNotBlank() == true &&
                projectId == null
            ) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid projectId"
                )
            }

            if (income.subprojectId?.isNotBlank() == true &&
                subprojectId == null
            ) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid subprojectId"
                )
            }

            if (projectId == null && subprojectId != null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Нельзя выбрать подпроект без проекта"
                )
            }

            val incomeDate = runCatching {
                KtLocalDate.parse(income.date)
            }.getOrElse {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid date"
                )
            }

            if (
                !income.amount.isFinite() ||
                income.amount < 0.0
            ) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Amount must be non-negative"
                )
            }

            val id = income.id.toIncomeUuidOrNull() ?: UUID.randomUUID()

            val created = try {
                transaction {
                    if (
                        !validateIncomeSubproject(
                            projectId,
                            subprojectId
                        )
                    ) {
                        throw IllegalArgumentException(
                            "Подпроект не принадлежит проекту " +
                                "или архивирован"
                        )
                    }

                    if (
                        projectId != null &&
                        ProjectsTable
                            .selectAll()
                            .where { ProjectsTable.id eq projectId }
                            .limit(1)
                            .none()
                    ) {
                        throw IllegalArgumentException(
                            "Проект не найден"
                        )
                    }

                    IncomesTable.insert {
                        it[IncomesTable.id] = id
                        it[IncomesTable.userId] = userId
                        it[IncomesTable.projectId] = projectId
                        it[IncomesTable.subprojectId] = subprojectId
                        it[date] = incomeDate
                        it[type] = income.type.trim()
                        it[name] = income.name.trim()
                        it[amount] = income.amount
                        it[currency] = income.currency
                            .trim()
                            .uppercase()
                            .take(3)
                            .ifBlank { "RUB" }
                        it[category] = normalizeIncomeCategory(
                            income.category
                        )
                        it[subcategory] = income.subcategory
                            ?.trim()
                            ?.takeIf(String::isNotBlank)
                        it[createdAt] = System.currentTimeMillis()
                    }

                    IncomesTable
                        .selectAll()
                        .where { IncomesTable.id eq id }
                        .single()
                        .let(::mapRowToIncomeDto)
                }
            } catch (e: IllegalArgumentException) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    e.message ?: "Invalid income"
                )
            }

            call.respond(HttpStatusCode.Created, created)
        }

        put {
            val session = call.checkIncomeSession() ?: return@put

            val income = try {
                call.receive<IncomeDto>()
            } catch (e: Exception) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid JSON: ${e.message}"
                )
            }

            val incomeId = income.id.toIncomeUuidOrNull()
                ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid incomeId"
                )

            val projectId = income.projectId.toIncomeUuidOrNull()
            val subprojectId = income.subprojectId.toIncomeUuidOrNull()

            if (income.projectId?.isNotBlank() == true &&
                projectId == null
            ) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid projectId"
                )
            }

            if (income.subprojectId?.isNotBlank() == true &&
                subprojectId == null
            ) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid subprojectId"
                )
            }

            if (projectId == null && subprojectId != null) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Нельзя выбрать подпроект без проекта"
                )
            }

            val incomeDate = runCatching {
                KtLocalDate.parse(income.date)
            }.getOrElse {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid date"
                )
            }

            if (
                !income.amount.isFinite() ||
                income.amount < 0.0
            ) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Amount must be non-negative"
                )
            }

            val result = try {
                transaction {
                    val existing = IncomesTable
                        .selectAll()
                        .where { IncomesTable.id eq incomeId }
                        .singleOrNull()
                        ?: return@transaction null

                    val ownerId = existing[
                        IncomesTable.userId
                    ].value

                    if (
                        ownerId != session.userId &&
                        session.role !in setOf(
                            "superadmin",
                            "admin",
                            "director"
                        )
                    ) {
                        throw SecurityException("Access denied")
                    }

                    if (
                        !validateIncomeSubproject(
                            projectId,
                            subprojectId
                        )
                    ) {
                        throw IllegalArgumentException(
                            "Подпроект не принадлежит проекту " +
                                "или архивирован"
                        )
                    }

                    if (
                        projectId != null &&
                        ProjectsTable
                            .selectAll()
                            .where { ProjectsTable.id eq projectId }
                            .limit(1)
                            .none()
                    ) {
                        throw IllegalArgumentException(
                            "Проект не найден"
                        )
                    }

                    IncomesTable.update(
                        { IncomesTable.id eq incomeId }
                    ) {
                        it[IncomesTable.projectId] = projectId
                        it[IncomesTable.subprojectId] = subprojectId
                        it[date] = incomeDate
                        it[type] = income.type.trim()
                        it[name] = income.name.trim()
                        it[amount] = income.amount
                        it[currency] = income.currency
                            .trim()
                            .uppercase()
                            .take(3)
                            .ifBlank { "RUB" }
                        it[category] = normalizeIncomeCategory(
                            income.category
                        )
                        it[subcategory] = income.subcategory
                            ?.trim()
                            ?.takeIf(String::isNotBlank)
                    }

                    IncomesTable
                        .selectAll()
                        .where { IncomesTable.id eq incomeId }
                        .single()
                        .let(::mapRowToIncomeDto)
                }
            } catch (e: SecurityException) {
                return@put call.respond(
                    HttpStatusCode.Forbidden,
                    e.message ?: "Access denied"
                )
            } catch (e: IllegalArgumentException) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    e.message ?: "Invalid income"
                )
            }

            if (result == null) {
                return@put call.respond(
                    HttpStatusCode.NotFound,
                    "Income not found"
                )
            }

            call.respond(HttpStatusCode.OK, result)
        }

        delete {
            val session = call.checkIncomeSession() ?: return@delete

            val incomeId = call.request.queryParameters["incomeId"]
                .toIncomeUuidOrNull()
                ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    "Valid incomeId required"
                )

            val result = transaction {
                val existing = IncomesTable
                    .selectAll()
                    .where { IncomesTable.id eq incomeId }
                    .singleOrNull()
                    ?: return@transaction IncomeDeleteResult.NOT_FOUND

                val ownerId = existing[IncomesTable.userId].value

                if (
                    ownerId != session.userId &&
                    session.role !in setOf(
                        "superadmin",
                        "admin",
                        "director"
                    )
                ) {
                    return@transaction IncomeDeleteResult.FORBIDDEN
                }

                IncomesTable.deleteWhere {
                    IncomesTable.id eq incomeId
                }

                IncomeDeleteResult.DELETED
            }

            when (result) {
                IncomeDeleteResult.NOT_FOUND ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        "Income not found"
                    )

                IncomeDeleteResult.FORBIDDEN ->
                    call.respond(
                        HttpStatusCode.Forbidden,
                        "Access denied"
                    )

                IncomeDeleteResult.DELETED ->
                    call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}