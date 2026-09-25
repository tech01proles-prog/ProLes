package com.proles.server.routes

import com.proles.server.config.PermissionMiddleware
import com.proles.server.config.PermissionMiddleware.checkPermission
import com.proles.server.data.BusinessTripsTable
import com.proles.server.data.ExpenseReceiptsTable
import com.proles.server.data.ExpensesTable
import com.proles.server.data.IncomesTable
import com.proles.server.data.ProjectsTable
import com.proles.server.data.SalaryComponentsTable
import com.proles.server.data.SubprojectsTable
import com.proles.server.data.TicketRecipientsTable
import com.proles.server.data.TicketsTable
import com.proles.server.data.TimeEntriesTable
import com.proles.server.data.TnpaDocumentsTable
import com.proles.server.model.Permission
import com.proles.server.model.ProjectDto
import com.proles.server.model.SubprojectDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.orderBy
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

internal fun Route.projectRoutes() {
    route("/api/v1/projects") {
        get {
            call.requireSession() ?: return@get

            val projects = transaction {
                ProjectsTable
                    .selectAll()
                    .orderBy(ProjectsTable.name to SortOrder.ASC)
                    .map(::mapProjectDto)
            }

            call.respond(HttpStatusCode.OK, projects)
        }

        post {
            val session = call.requireSession() ?: return@post

            if (
                !PermissionMiddleware.canCreate(
                    session.userId,
                    Permission.PROJECTS
                )
            ) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    "Нет прав на создание проектов"
                )
            }

            val project = try {
                call.receive<ProjectDto>()
            } catch (e: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid JSON: ${e.message}"
                )
            }

            val projectId = project.id.toRouteUuidOrNull()
                ?: UUID.randomUUID()

            val deliveryDate = try {
                project.deliveryDate
                    ?.takeIf(String::isNotBlank)
                    ?.let(LocalDate::parse)
            } catch (_: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid deliveryDate"
                )
            }

            val completionDate = try {
                project.completionDate
                    ?.takeIf(String::isNotBlank)
                    ?.let(LocalDate::parse)
            } catch (_: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid completionDate"
                )
            }

            val created = transaction {
                if (
                    ProjectsTable
                        .selectAll()
                        .where { ProjectsTable.id eq projectId }
                        .limit(1)
                        .any()
                ) {
                    return@transaction null
                }

                ProjectsTable.insert {
                    it[id] = projectId
                    it[name] = project.name.trim()
                    it[isActive] = project.isActive
                    it[status] = project.status
                    it[lead] = project.lead
                    it[revenue] = project.revenue
                    it[expenses] = project.expenses
                    it[cost] = project.cost
                    it[profit] = project.profit
                    it[projectNumber] = project.projectNumber
                    it[subProjectNumber] =
                        project.subProjectNumber
                    it[client] = project.client
                    it[location] = project.location
                    it[productService] =
                        project.productService
                    it[quantity] = project.quantity
                    it[ProjectsTable.deliveryDate] =
                        deliveryDate
                    it[contract] = project.contract
                    it[notes] = project.notes
                    it[projectCode] = project.projectCode
                    it[ProjectsTable.completionDate] =
                        completionDate
                    it[customer] = project.customer
                    it[productionCost] =
                        project.productionCost
                    it[transportToClient] =
                        project.transportToClient
                    it[sellingPrice] = project.sellingPrice
                    it[materials] = project.materials
                    it[contractors] = project.contractors
                    it[creditPercent] = project.creditPercent
                }

                ProjectsTable
                    .selectAll()
                    .where { ProjectsTable.id eq projectId }
                    .single()
                    .let(::mapProjectDto)
            }

            if (created == null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    "Project with this id already exists"
                )
            } else {
                call.respond(HttpStatusCode.Created, created)
            }
        }

        put {
            if (
                !call.checkPermission(
                    Permission.PROJECTS,
                    "edit"
                )
            ) {
                return@put
            }

            val project = try {
                call.receive<ProjectDto>()
            } catch (e: Exception) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid JSON: ${e.message}"
                )
            }

            val projectId = project.id.toRouteUuidOrNull()
                ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid projectId"
                )

            val deliveryDate = try {
                project.deliveryDate
                    ?.takeIf(String::isNotBlank)
                    ?.let(LocalDate::parse)
            } catch (_: Exception) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid deliveryDate"
                )
            }

            val completionDate = try {
                project.completionDate
                    ?.takeIf(String::isNotBlank)
                    ?.let(LocalDate::parse)
            } catch (_: Exception) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid completionDate"
                )
            }

            val updated = transaction {
                val affected = ProjectsTable.update(
                    { ProjectsTable.id eq projectId }
                ) {
                    it[name] = project.name.trim()
                    it[isActive] = project.isActive
                    it[status] = project.status
                    it[lead] = project.lead
                    it[revenue] = project.revenue
                    it[expenses] = project.expenses
                    it[cost] = project.cost
                    it[profit] = project.profit
                    it[projectNumber] = project.projectNumber
                    it[subProjectNumber] =
                        project.subProjectNumber
                    it[client] = project.client
                    it[location] = project.location
                    it[productService] =
                        project.productService
                    it[quantity] = project.quantity
                    it[ProjectsTable.deliveryDate] =
                        deliveryDate
                    it[contract] = project.contract
                    it[notes] = project.notes
                    it[projectCode] = project.projectCode
                    it[ProjectsTable.completionDate] =
                        completionDate
                    it[customer] = project.customer
                    it[productionCost] =
                        project.productionCost
                    it[transportToClient] =
                        project.transportToClient
                    it[sellingPrice] = project.sellingPrice
                    it[materials] = project.materials
                    it[contractors] = project.contractors
                    it[creditPercent] = project.creditPercent
                }

                if (affected == 0) {
                    null
                } else {
                    ProjectsTable
                        .selectAll()
                        .where { ProjectsTable.id eq projectId }
                        .single()
                        .let(::mapProjectDto)
                }
            }

            if (updated == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    "Project not found"
                )
            } else {
                call.respond(HttpStatusCode.OK, updated)
            }
        }

        delete {
            if (
                !call.checkPermission(
                    Permission.PROJECTS,
                    "delete"
                )
            ) {
                return@delete
            }

            val projectId = call.request
                .queryParameters["projectId"]
                .toRouteUuidOrNull()
                ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    "Valid projectId required"
                )

            val deleted = transaction {
                if (
                    ProjectsTable
                        .selectAll()
                        .where { ProjectsTable.id eq projectId }
                        .limit(1)
                        .none()
                ) {
                    return@transaction false
                }

                TnpaDocumentsTable.deleteWhere {
                    TnpaDocumentsTable.projectId eq projectId
                }

                val ticketIds = TicketsTable
                    .selectAll()
                    .where {
                        TicketsTable.projectId eq projectId
                    }
                    .map { it[TicketsTable.id].value }

                if (ticketIds.isNotEmpty()) {
                    TicketRecipientsTable.deleteWhere {
                        TicketRecipientsTable.ticketId inList
                            ticketIds
                    }
                    TicketsTable.deleteWhere {
                        TicketsTable.projectId eq projectId
                    }
                }

                BusinessTripsTable.deleteWhere {
                    BusinessTripsTable.projectId eq projectId
                }

                val expenseIds = ExpensesTable
                    .selectAll()
                    .where {
                        ExpensesTable.projectId eq projectId
                    }
                    .map { it[ExpensesTable.id].value }

                if (expenseIds.isNotEmpty()) {
                    ExpenseReceiptsTable.deleteWhere {
                        ExpenseReceiptsTable.expenseId inList
                            expenseIds
                    }
                    ExpensesTable.deleteWhere {
                        ExpensesTable.projectId eq projectId
                    }
                }

                TimeEntriesTable.deleteWhere {
                    TimeEntriesTable.projectId eq projectId
                }

                SalaryComponentsTable.deleteWhere {
                    SalaryComponentsTable.projectId eq projectId
                }

                IncomesTable.deleteWhere {
                    IncomesTable.projectId eq projectId
                }

                SubprojectsTable.deleteWhere {
                    SubprojectsTable.projectId eq projectId
                }

                ProjectsTable.deleteWhere {
                    ProjectsTable.id eq projectId
                }

                true
            }

            if (!deleted) {
                call.respond(
                    HttpStatusCode.NotFound,
                    "Project not found"
                )
            } else {
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun mapProjectDto(row: org.jetbrains.exposed.sql.ResultRow):
    ProjectDto {
    val projectId = row[ProjectsTable.id].value

    val subprojects = SubprojectsTable
        .selectAll()
        .where { SubprojectsTable.projectId eq projectId }
        .orderBy(
            SubprojectsTable.sortOrder to SortOrder.ASC,
            SubprojectsTable.name to SortOrder.ASC
        )
        .map { subproject ->
            SubprojectDto(
                id = subproject[SubprojectsTable.id]
                    .value
                    .toString(),
                projectId = subproject[
                    SubprojectsTable.projectId
                ].value.toString(),
                name = subproject[SubprojectsTable.name],
                code = subproject[SubprojectsTable.code],
                description = subproject[
                    SubprojectsTable.description
                ],
                isActive = subproject[
                    SubprojectsTable.isActive
                ],
                sortOrder = subproject[
                    SubprojectsTable.sortOrder
                ],
                createdAt = subproject[
                    SubprojectsTable.createdAt
                ],
                updatedAt = subproject[
                    SubprojectsTable.updatedAt
                ],
                archivedAt = subproject[
                    SubprojectsTable.archivedAt
                ]
            )
        }

    return ProjectDto(
        id = projectId.toString(),
        name = row[ProjectsTable.name],
        isActive = row[ProjectsTable.isActive],
        status = row[ProjectsTable.status],
        lead = row[ProjectsTable.lead],
        revenue = row[ProjectsTable.revenue],
        expenses = row[ProjectsTable.expenses],
        cost = row[ProjectsTable.cost],
        profit = row[ProjectsTable.profit],
        projectNumber = row[ProjectsTable.projectNumber],
        subProjectNumber = row[ProjectsTable.subProjectNumber],
        client = row[ProjectsTable.client],
        location = row[ProjectsTable.location],
        productService = row[ProjectsTable.productService],
        quantity = row[ProjectsTable.quantity],
        deliveryDate = row[
            ProjectsTable.deliveryDate
        ]?.toString(),
        contract = row[ProjectsTable.contract],
        notes = row[ProjectsTable.notes],
        projectCode = row[ProjectsTable.projectCode],
        completionDate = row[
            ProjectsTable.completionDate
        ]?.toString(),
        customer = row[ProjectsTable.customer],
        productionCost = row[ProjectsTable.productionCost],
        transportToClient = row[
            ProjectsTable.transportToClient
        ],
        sellingPrice = row[ProjectsTable.sellingPrice],
        materials = row[ProjectsTable.materials],
        contractors = row[ProjectsTable.contractors],
        creditPercent = row[ProjectsTable.creditPercent],
        subprojects = subprojects
    )
}