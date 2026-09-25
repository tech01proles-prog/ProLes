package com.proles.server.routes

import com.proles.server.config.PermissionMiddleware.checkPermission
import com.proles.server.data.ProjectsTable
import com.proles.server.data.SalaryComponentsTable
import com.proles.server.data.SubprojectsTable
import com.proles.server.data.UsersTable
import com.proles.server.model.Permission
import com.proles.server.model.ProjectCostPayrollDataDto
import com.proles.server.model.ProjectCostUpdateDto
import com.proles.server.model.SalaryComponentDto
import com.proles.server.model.UserDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.orderBy
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

internal fun Route.projectCostRoutes() {
    route("/api/v1/project-costs") {
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