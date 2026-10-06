package com.proles.server.routes

import com.proles.server.config.PermissionMiddleware.checkPermission
import com.proles.server.data.ProjectRealizationCostsTable
import com.proles.server.data.ProjectsTable
import com.proles.server.data.SalaryComponentsTable
import com.proles.server.data.SalaryRecordsTable
import com.proles.server.data.SubprojectsTable
import com.proles.server.data.UsersTable
import com.proles.server.model.Permission
import com.proles.server.model.CreateProjectRealizationCostDto
import com.proles.server.model.ProjectCostPayrollDataDto
import com.proles.server.model.ProjectCostUpdateDto
import com.proles.server.model.ProjectRealizationCostDto
import com.proles.server.model.SalaryComponentDto
import com.proles.server.model.UserDto
import kotlinx.serialization.Serializable
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import java.util.UUID
import kotlinx.datetime.LocalDate as KtLocalDate

@Serializable
private data class PaidSalaryCostDto(val userId: String, val year: Int, val month: Int, val amount: Double, val taxInclusiveAmount: Double, val status: String)

internal fun Route.projectCostRoutes() {
    route("/api/v1/project-costs") {
        get("/paid-salaries") {
            if (!call.checkPermission(Permission.COST_CALCULATION, "view")) return@get

            val fromYear = call.request.queryParameters["fromYear"]?.toIntOrNull()
            val fromMonth = call.request.queryParameters["fromMonth"]?.toIntOrNull()
            val toYear = call.request.queryParameters["toYear"]?.toIntOrNull()
            val toMonth = call.request.queryParameters["toMonth"]?.toIntOrNull()

            if (fromYear == null || fromMonth == null || fromMonth !in 1..12 || toYear == null || toMonth == null || toMonth !in 1..12) return@get call.respond(HttpStatusCode.BadRequest, "Invalid period")

            val fromKey = fromYear * 100 + fromMonth
            val toKey = toYear * 100 + toMonth
            if (fromKey > toKey) return@get call.respond(HttpStatusCode.BadRequest, "Invalid period range")

            val result = transaction {
                SalaryRecordsTable.selectAll().where { SalaryRecordsTable.status inList listOf("approved", "paid") }.mapNotNull { row ->
                    val year = row[SalaryRecordsTable.periodYear]
                    val month = row[SalaryRecordsTable.periodMonth]
                    val key = year * 100 + month
                    if (key !in fromKey..toKey) null else PaidSalaryCostDto(
                        userId = row[SalaryRecordsTable.userId].value.toString(),
                        year = year,
                        month = month,
                        amount = row[SalaryRecordsTable.totalAmount],
                        taxInclusiveAmount = row[SalaryRecordsTable.taxInclusiveCost],
                        status = row[SalaryRecordsTable.status]
                    )
                }
            }

            call.respond(HttpStatusCode.OK, result)
        }

        get("/payroll-data") {
            if (
                !call.checkPermission(
                    Permission.COST_CALCULATION,
                    "view"
                )
            ) {
                return@get
            }

            val data = transaction {
                val employees = UsersTable
                    .selectAll()
                    .where {
                        UsersTable.role inList
                            listOf("employee", "tech")
                    }
                    .orderBy(
                        UsersTable.lastName to SortOrder.ASC,
                        UsersTable.firstName to SortOrder.ASC
                    )
                    .map { row ->
                        UserDto(
                            id = row[UsersTable.id]
                                .value
                                .toString(),
                            lastName = row[UsersTable.lastName],
                            firstName = row[UsersTable.firstName],
                            middleName = row[UsersTable.middleName],
                            name = row[UsersTable.name],
                            login = row[UsersTable.login],
                            email = row[UsersTable.email],
                            role = row[UsersTable.role],
                            position =
                                row[UsersTable.position] ?: "",
                            defaultRateType =
                                row[UsersTable.defaultRateType],
                            defaultRate =
                                row[UsersTable.defaultRate],
                            defaultCurrency =
                                row[UsersTable.defaultCurrency],
                            phone = row[UsersTable.phone],
                            telegramUsername =
                                row[UsersTable.telegramUsername],
                            birthDate =
                                row[UsersTable.birthDate]
                                    ?.toString(),
                            positionId =
                                row[UsersTable.positionId]
                                    ?.value
                                    ?.toString(),
                            isRemote = row[UsersTable.isRemote]
                        )
                    }

                val components = SalaryComponentsTable
                    .selectAll()
                    .orderBy(
                        SalaryComponentsTable.type to
                            SortOrder.ASC
                    )
                    .map { row ->
                        val subprojectId =
                            row[
                                SalaryComponentsTable
                                    .subprojectId
                            ]?.value

                        SalaryComponentDto(
                            id = row[SalaryComponentsTable.id]
                                .value
                                .toString(),
                            userId =
                                row[
                                    SalaryComponentsTable.userId
                                ].value.toString(),
                            type =
                                row[SalaryComponentsTable.type],
                            amount =
                                row[SalaryComponentsTable.amount],
                            projectId =
                                row[
                                    SalaryComponentsTable.projectId
                                ]?.value?.toString(),
                            subprojectId =
                                subprojectId?.toString(),
                            subprojectName =
                                subprojectId?.let { id ->
                                    SubprojectsTable
                                        .selectAll()
                                        .where {
                                            SubprojectsTable.id eq id
                                        }
                                        .limit(1)
                                        .singleOrNull()
                                        ?.get(SubprojectsTable.name)
                                }.orEmpty(),
                            ratePerHour =
                                row[
                                    SalaryComponentsTable.ratePerHour
                                ],
                            ratePerUnit =
                                row[
                                    SalaryComponentsTable.ratePerUnit
                                ],
                            description =
                                row[
                                    SalaryComponentsTable.description
                                ],
                            effectiveFrom =
                                row[
                                    SalaryComponentsTable.effectiveFrom
                                ].toString(),
                            effectiveTo =
                                row[
                                    SalaryComponentsTable.effectiveTo
                                ]?.toString(),
                            isActive =
                                row[
                                    SalaryComponentsTable.isActive
                                ]
                        )
                    }

                ProjectCostPayrollDataDto(
                    employees,
                    components
                )
            }

            call.respond(HttpStatusCode.OK, data)
        }

        get("/realization-costs") {
            if (!call.checkPermission(Permission.COST_CALCULATION, "view")) return@get

            val fromDate = call.request.queryParameters["fromDate"]?.takeIf { it.isNotBlank() }?.let {
                runCatching { KtLocalDate.parse(it) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid fromDate")
            }
            val toDate = call.request.queryParameters["toDate"]?.takeIf { it.isNotBlank() }?.let {
                runCatching { KtLocalDate.parse(it) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid toDate")
            }
            if (fromDate != null && toDate != null && fromDate > toDate) {
                return@get call.respond(HttpStatusCode.BadRequest, "Invalid period")
            }

            val costs = transaction {
                ProjectRealizationCostsTable.selectAll().mapNotNull { row ->
                    val date = row[ProjectRealizationCostsTable.date]
                    if ((fromDate != null && date < fromDate) || (toDate != null && date > toDate)) null
                    else ProjectRealizationCostDto(
                        id = row[ProjectRealizationCostsTable.id].value.toString(),
                        projectId = row[ProjectRealizationCostsTable.projectId].value.toString(),
                        amount = row[ProjectRealizationCostsTable.amount],
                        comment = row[ProjectRealizationCostsTable.comment],
                        date = date.toString(),
                        createdAt = row[ProjectRealizationCostsTable.createdAt]
                    )
                }.sortedBy { it.date }
            }
            call.respond(HttpStatusCode.OK, costs)
        }

        post("/{projectId}/realization-costs") {
            if (!call.checkPermission(Permission.COST_CALCULATION, "edit")) return@post

            val projectId = call.parameters["projectId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid projectId")
            val body = runCatching { call.receive<CreateProjectRealizationCostDto>() }.getOrElse { error ->
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${error.message}")
            }
            if (!body.amount.isFinite() || body.amount <= 0.0) {
                return@post call.respond(HttpStatusCode.BadRequest, "Amount must be greater than zero")
            }
            val date = runCatching { KtLocalDate.parse(body.date) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid date")
            val projectExists = transaction {
                ProjectsTable.selectAll().where { ProjectsTable.id eq projectId }.limit(1).any()
            }
            if (!projectExists) return@post call.respond(HttpStatusCode.NotFound, "Project not found")

            val id = UUID.randomUUID()
            val createdAt = System.currentTimeMillis()
            transaction {
                ProjectRealizationCostsTable.insert {
                    it[ProjectRealizationCostsTable.id] = id
                    it[ProjectRealizationCostsTable.projectId] = projectId
                    it[ProjectRealizationCostsTable.amount] = body.amount
                    it[ProjectRealizationCostsTable.comment] = body.comment.trim().take(2000)
                    it[ProjectRealizationCostsTable.date] = date
                    it[ProjectRealizationCostsTable.createdAt] = createdAt
                }
            }
            call.respond(
                HttpStatusCode.Created,
                ProjectRealizationCostDto(
                    id = id.toString(),
                    projectId = projectId.toString(),
                    amount = body.amount,
                    comment = body.comment.trim().take(2000),
                    date = date.toString(),
                    createdAt = createdAt
                )
            )
        }
        put("/{projectId}") {
            if (
                !call.checkPermission(
                    Permission.COST_CALCULATION,
                    "edit"
                )
            ) {
                return@put
            }

            val projectId = call.parameters["projectId"]
                .toRouteUuidOrNull()
                ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid projectId"
                )

            val body = try {
                call.receive<ProjectCostUpdateDto>()
            } catch (e: Exception) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid JSON: ${e.message}"
                )
            }

            val updated = transaction {
                ProjectsTable.update(
                    { ProjectsTable.id eq projectId }
                ) {
                    it[sellingPrice] =
                        body.sellingPrice.coerceAtLeast(0.0)
                    it[transportToClient] =
                        body.transportToClient
                            .coerceAtLeast(0.0)
                    it[materials] =
                        body.materials.coerceAtLeast(0.0)
                    it[contractors] =
                        body.contractors.coerceAtLeast(0.0)
                    it[creditPercent] =
                        body.creditPercent.coerceAtLeast(0.0)
                } > 0
            }

            if (!updated) {
                return@put call.respond(
                    HttpStatusCode.NotFound,
                    "Project not found"
                )
            }

            call.respond(HttpStatusCode.OK, body)
        }
    }
}