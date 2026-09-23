package com.proles.server.routes

import com.proles.server.data.BusinessTripsTable
import com.proles.server.data.EmployeeBalanceTransactionsTable
import com.proles.server.data.ProjectsTable
import com.proles.server.data.SubprojectsTable
import com.proles.server.data.TimeEntriesTable
import com.proles.server.data.TnpaDocumentsTable
import com.proles.server.data.UsersTable
import com.proles.server.model.BalanceRepaymentRequest
import com.proles.server.model.CompleteBusinessTripRequest
import com.proles.server.model.CreateSubprojectRequest
import com.proles.server.model.EmployeeBalanceDto
import com.proles.server.model.EmployeeBalanceTransactionDto
import com.proles.server.model.GroupedHoursResponseDto
import com.proles.server.model.HoursProjectGroupDto
import com.proles.server.model.HoursSubprojectGroupDto
import com.proles.server.model.SubprojectDto
import com.proles.server.model.TnpaDocumentDto
import com.proles.server.model.UpdateSubprojectRequest
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.auth.jwt.JWTPrincipal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

private const val MAX_TNPA_FILE_BYTES = 1024L * 1024L * 1024L

private data class SessionUser(
    val id: UUID,
    val role: String
)

private data class TnpaUpload(
    val projectId: UUID,
    val subprojectId: UUID?,
    val description: String,
    val originalName: String,
    val mimeType: String,
    val temporaryFile: File,
    val sizeBytes: Long,
    val sha256: String
)

fun Application.configurePlatformRoutes() {
    val tnpaRoot = File(
        System.getenv("TNPA_STORAGE_DIR")
            ?: "data/tnpa"
    ).apply { mkdirs() }

    routing {
        authenticate("auth-jwt") {
            route("/api") {
                subprojectRoutes()
                groupedHoursRoutes()
                employeeBalanceRoutes()
                businessTripCompletionRoutes()
                tnpaRoutes(tnpaRoot)
            }
        }
    }
}

private fun io.ktor.server.routing.Route.subprojectRoutes() {
    route("/projects/{projectId}/subprojects") {
        get {
            val session = call.requireSessionUser() ?: return@get
            val projectId = call.parameters["projectId"].asUuidOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Некорректный projectId")
                )

            val rows = transaction {
                ensureProjectExists(projectId)

                SubprojectsTable
                    .select {
                        SubprojectsTable.projectId eq projectId
                    }
                    .orderBy(
                        SubprojectsTable.sortOrder to SortOrder.ASC,
                        SubprojectsTable.name to SortOrder.ASC
                    )
                    .map(::subprojectDto)
            }

            call.respond(rows)
        }

        post {
            val session = call.requireSessionUser() ?: return@post
            if (!session.canManageProjects()) {
                return@post call.respond(HttpStatusCode.Forbidden)
            }

            val projectId = call.parameters["projectId"].asUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Некорректный projectId")
                )
            val request = call.receive<CreateSubprojectRequest>()
            val name = request.name.trim()

            if (name.isBlank()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Название подпроекта обязательно")
                )
            }

            val now = System.currentTimeMillis()
            val id = UUID.randomUUID()

            val dto = try {
                transaction {
                    ensureProjectExists(projectId)

                    val duplicate = SubprojectsTable
                        .select {
                            (SubprojectsTable.projectId eq projectId) and
                                (SubprojectsTable.name eq name)
                        }
                        .limit(1)
                        .any()

                    if (duplicate) {
                        throw DuplicateSubprojectException()
                    }

                    SubprojectsTable.insert {
                        it[SubprojectsTable.id] = id
                        it[SubprojectsTable.projectId] = projectId
                        it[SubprojectsTable.name] = name
                        it[code] = request.code.trim()
                        it[description] = request.description.trim()
                        it[isActive] = true
                        it[sortOrder] = request.sortOrder
                        it[createdAt] = now
                        it[updatedAt] = now
                    }

                    SubprojectsTable
                        .select { SubprojectsTable.id eq id }
                        .single()
                        .let(::subprojectDto)
                }
            } catch (_: DuplicateSubprojectException) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "Подпроект с таким названием уже существует")
                )
            } catch (_: ProjectNotFoundException) {
                return@post call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Проект не найден")
                )
            }

            call.respond(HttpStatusCode.Created, dto)
        }
    }

    route("/subprojects/{subprojectId}") {
        patch {
            val session = call.requireSessionUser() ?: return@patch
            if (!session.canManageProjects()) {
                return@patch call.respond(HttpStatusCode.Forbidden)
            }

            val subprojectId = call.parameters["subprojectId"].asUuidOrNull()
                ?: return@patch call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Некорректный subprojectId")
                )
            val request = call.receive<UpdateSubprojectRequest>()

            val updated = transaction {
                val existing = SubprojectsTable
                    .select { SubprojectsTable.id eq subprojectId }
                    .singleOrNull()
                    ?: return@transaction null

                val projectId = existing[SubprojectsTable.projectId].value
                val newName = request.name?.trim()

                if (!newName.isNullOrBlank()) {
                    val duplicate = SubprojectsTable
                        .select {
                            (SubprojectsTable.projectId eq projectId) and
                                (SubprojectsTable.name eq newName) and
                                (SubprojectsTable.id neq subprojectId)
                        }
                        .limit(1)
                        .any()

                    if (duplicate) {
                        throw DuplicateSubprojectException()
                    }
                }

                SubprojectsTable.update(
                    { SubprojectsTable.id eq subprojectId }
                ) {
                    request.name?.trim()?.takeIf(String::isNotBlank)
                        ?.let { value -> it[name] = value }
                    request.code?.trim()
                        ?.let { value -> it[code] = value }
                    request.description?.trim()
                        ?.let { value -> it[description] = value }
                    request.isActive
                        ?.let { value -> it[isActive] = value }
                    request.sortOrder
                        ?.let { value -> it[sortOrder] = value }
                    it[updatedAt] = System.currentTimeMillis()
                    if (request.isActive == false) {
                        it[archivedAt] = System.currentTimeMillis()
                    } else if (request.isActive == true) {
                        it[archivedAt] = null
                    }
                }

                SubprojectsTable
                    .select { SubprojectsTable.id eq subprojectId }
                    .single()
                    .let(::subprojectDto)
            }

            if (updated == null) {
                return@patch call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Подпроект не найден")
                )
            }

            call.respond(updated)
        }

        delete {
            val session = call.requireSessionUser() ?: return@delete
            if (!session.canManageProjects()) {
                return@delete call.respond(HttpStatusCode.Forbidden)
            }

            val subprojectId = call.parameters["subprojectId"].asUuidOrNull()
                ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Некорректный subprojectId")
                )

            val affected = transaction {
                SubprojectsTable.update(
                    { SubprojectsTable.id eq subprojectId }
                ) {
                    it[isActive] = false
                    it[archivedAt] = System.currentTimeMillis()
                    it[updatedAt] = System.currentTimeMillis()
                }
            }

            if (affected == 0) {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Подпроект не найден")
                )
            } else {
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun io.ktor.server.routing.Route.groupedHoursRoutes() {
    get("/hours/grouped") {
        val session = call.requireSessionUser() ?: return@get
        val requestedUserId = call.request.queryParameters["userId"]
            .asUuidOrNull()
            ?: session.id

        if (requestedUserId != session.id && !session.canViewAllHours()) {
            return@get call.respond(HttpStatusCode.Forbidden)
        }

        val from = call.request.queryParameters["from"]
            ?.let(::parseDateOrNull)
        val to = call.request.queryParameters["to"]
            ?.let(::parseDateOrNull)

        if (
            call.request.queryParameters["from"] != null &&
            from == null
        ) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Некорректная дата from")
            )
        }

        if (
            call.request.queryParameters["to"] != null &&
            to == null
        ) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Некорректная дата to")
            )
        }

        val response = transaction {
            var condition =
                TimeEntriesTable.userId eq requestedUserId

            if (from != null) {
                condition = condition and
                    (TimeEntriesTable.date greaterEq from)
            }
            if (to != null) {
                condition = condition and
                    (TimeEntriesTable.date lessEq to)
            }

            val entries = (
                TimeEntriesTable
                    .leftJoin(ProjectsTable)
                    .leftJoin(SubprojectsTable)
                )
                .select { condition }
                .orderBy(TimeEntriesTable.date to SortOrder.DESC)
                .toList()

            val groups = entries
                .groupBy { row ->
                    row[TimeEntriesTable.projectId].value
                }
                .map { (projectId, projectEntries) ->
                    val projectName = projectEntries.firstOrNull()
                        ?.getOrNull(ProjectsTable.name)
                        ?: "Без проекта"

                    val withoutSubproject = projectEntries
                        .filter {
                            it[TimeEntriesTable.subprojectId] == null
                        }
                        .map(::existingTimeEntryDto)

                    val subprojectGroups = projectEntries
                        .filter {
                            it[TimeEntriesTable.subprojectId] != null
                        }
                        .groupBy {
                            it[TimeEntriesTable.subprojectId]!!.value
                        }
                        .map { (subprojectId, subprojectEntries) ->
                            HoursSubprojectGroupDto(
                                subprojectId = subprojectId.toString(),
                                subprojectName = subprojectEntries
                                    .first()
                                    .getOrNull(SubprojectsTable.name)
                                    ?: "Подпроект",
                                totalHours = subprojectEntries
                                    .sumOf(::entryHours),
                                entries = subprojectEntries
                                    .map(::existingTimeEntryDto)
                            )
                        }
                        .sortedBy { it.subprojectName.lowercase() }

                    HoursProjectGroupDto(
                        projectId = projectId.toString(),
                        projectName = projectName,
                        totalHours = projectEntries.sumOf(::entryHours),
                        subprojects = subprojectGroups,
                        entriesWithoutSubproject = withoutSubproject
                    )
                }
                .sortedBy { it.projectName.lowercase() }

            GroupedHoursResponseDto(
                userId = requestedUserId.toString(),
                totalHours = entries.sumOf(::entryHours),
                groups = groups
            )
        }

        call.respond(response)
    }
}

private fun io.ktor.server.routing.Route.employeeBalanceRoutes() {
    route("/employee-balances/{userId}") {
        get {
            val session = call.requireSessionUser() ?: return@get
            val userId = call.parameters["userId"].asUuidOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Некорректный userId")
                )

            if (userId != session.id && !session.canManagePayroll()) {
                return@get call.respond(HttpStatusCode.Forbidden)
            }

            val response = transaction {
                if (!userExists(userId)) {
                    return@transaction null
                }

                val transactions = EmployeeBalanceTransactionsTable
                    .select {
                        EmployeeBalanceTransactionsTable.userId eq userId
                    }
                    .orderBy(
                        EmployeeBalanceTransactionsTable.createdAt to
                            SortOrder.DESC
                    )
                    .map(::balanceTransactionDto)

                EmployeeBalanceDto(
                    userId = userId.toString(),
                    balance = calculateBalance(transactions),
                    transactions = transactions
                )
            }

            if (response == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Сотрудник не найден")
                )
            } else {
                call.respond(response)
            }
        }

        post("/repayments") {
            val session = call.requireSessionUser() ?: return@post
            if (!session.canManagePayroll()) {
                return@post call.respond(HttpStatusCode.Forbidden)
            }

            val userId = call.parameters["userId"].asUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Некорректный userId")
                )
            val request = call.receive<BalanceRepaymentRequest>()

            if (!request.amount.isFinite() || request.amount <= 0.0) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Сумма погашения должна быть больше нуля")
                )
            }

            val result = try {
                transaction {
                    UsersTable
                        .select { UsersTable.id eq userId }
                        .forUpdate()
                        .singleOrNull()
                        ?: throw UserNotFoundException()

                    val currentTransactions =
                        EmployeeBalanceTransactionsTable
                            .select {
                                EmployeeBalanceTransactionsTable.userId eq
                                    userId
                            }
                            .map(::balanceTransactionDto)

                    val currentBalance =
                        calculateBalance(currentTransactions)

                    if (request.amount > currentBalance + 0.005) {
                        throw InsufficientBalanceException(currentBalance)
                    }

                    val existing = request.idempotencyKey
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let { key ->
                            EmployeeBalanceTransactionsTable
                                .select {
                                    EmployeeBalanceTransactionsTable
                                        .idempotencyKey eq key
                                }
                                .singleOrNull()
                        }

                    if (existing != null) {
                        return@transaction balanceTransactionDto(existing)
                    }

                    val id = UUID.randomUUID()
                    val salaryRecordId = request.salaryRecordId.asUuidOrNull()

                    EmployeeBalanceTransactionsTable.insert {
                        it[EmployeeBalanceTransactionsTable.id] = id
                        it[EmployeeBalanceTransactionsTable.userId] = userId
                        it[
                            EmployeeBalanceTransactionsTable.salaryRecordId
                        ] = salaryRecordId
                        it[transactionType] = "REPAYMENT"
                        it[amount] = BigDecimal
                            .valueOf(request.amount)
                            .setScale(2)
                        it[comment] = request.comment.trim()
                        it[createdBy] = session.id
                        it[createdAt] = System.currentTimeMillis()
                        it[reversedTransactionId] = null
                        it[idempotencyKey] = request.idempotencyKey
                            ?.trim()
                            ?.takeIf(String::isNotBlank)
                    }

                    EmployeeBalanceTransactionsTable
                        .select {
                            EmployeeBalanceTransactionsTable.id eq id
                        }
                        .single()
                        .let(::balanceTransactionDto)
                }
            } catch (_: UserNotFoundException) {
                return@post call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Сотрудник не найден")
                )
            } catch (error: InsufficientBalanceException) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    mapOf(
                        "error" to "Сумма превышает доступный баланс",
                        "balance" to error.balance
                    )
                )
            }

            call.respond(HttpStatusCode.Created, result)
        }
    }
}

private fun io.ktor.server.routing.Route.businessTripCompletionRoutes() {
    post("/business-trips/{tripId}/complete") {
        val session = call.requireSessionUser() ?: return@post
        val tripId = call.parameters["tripId"].asUuidOrNull()
            ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Некорректный tripId")
            )
        val request = call.receive<CompleteBusinessTripRequest>()
        val endDate = parseDateOrNull(request.endDate)
            ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Некорректная дата окончания")
            )

        val result = transaction {
            val trip = BusinessTripsTable
                .select { BusinessTripsTable.id eq tripId }
                .singleOrNull()
                ?: return@transaction TripCompletionResult.NotFound

            val ownerId = trip[BusinessTripsTable.userId].value

            if (ownerId != session.id && !session.canManageTrips()) {
                return@transaction TripCompletionResult.Forbidden
            }

            val startDate = trip[BusinessTripsTable.startDate]
            if (endDate < startDate) {
                return@transaction TripCompletionResult.InvalidRange
            }

            BusinessTripsTable.update(
                { BusinessTripsTable.id eq tripId }
            ) {
                it[BusinessTripsTable.endDate] = endDate
                it[BusinessTripsTable.completedDate] = endDate
                it[BusinessTripsTable.status] = "COMPLETED"
            }

            TripCompletionResult.Success
        }

        when (result) {
            TripCompletionResult.NotFound ->
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Командировка не найдена")
                )
            TripCompletionResult.Forbidden ->
                call.respond(HttpStatusCode.Forbidden)
            TripCompletionResult.InvalidRange ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(
                        "error" to
                            "Дата окончания раньше даты начала"
                    )
                )
            TripCompletionResult.Success ->
                call.respond(mapOf("success" to true))
        }
    }
}

private fun io.ktor.server.routing.Route.tnpaRoutes(
    storageRoot: File
) {
    route("/tnpa") {
        get {
            val session = call.requireSessionUser() ?: return@get
            if (!session.canReadTnpa()) {
                return@get call.respond(HttpStatusCode.Forbidden)
            }

            val projectId = call.request.queryParameters["projectId"]
                .asUuidOrNull()
            val subprojectId = call.request.queryParameters["subprojectId"]
                .asUuidOrNull()

            val result = transaction {
                var condition =
                    TnpaDocumentsTable.deletedAt.isNull()

                if (projectId != null) {
                    condition = condition and
                        (TnpaDocumentsTable.projectId eq projectId)
                }
                if (subprojectId != null) {
                    condition = condition and
                        (TnpaDocumentsTable.subprojectId eq subprojectId)
                }

                (
                    TnpaDocumentsTable
                        .innerJoin(ProjectsTable)
                        .leftJoin(SubprojectsTable)
                        .innerJoin(UsersTable)
                    )
                    .select { condition }
                    .orderBy(
                        TnpaDocumentsTable.uploadedAt to SortOrder.DESC
                    )
                    .map(::tnpaDocumentDto)
            }

            call.respond(result)
        }

        post {
            val session = call.requireSessionUser() ?: return@post
            if (!session.canManageTnpa()) {
                return@post call.respond(HttpStatusCode.Forbidden)
            }

            val upload = try {
                receiveTnpaUpload(storageRoot)
            } catch (error: UploadValidationException) {
                return@post call.respond(
                    error.status,
                    mapOf("error" to error.message)
                )
            }

            val id = UUID.randomUUID()
            val finalDirectory = File(
                storageRoot,
                "${upload.projectId}/${upload.subprojectId ?: "root"}"
            ).apply { mkdirs() }
            val storedName = "$id.bin"
            val finalFile = File(finalDirectory, storedName)

            try {
                withContext(Dispatchers.IO) {
                    Files.move(
                        upload.temporaryFile.toPath(),
                        finalFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                }

                val dto = transaction {
                    ensureProjectExists(upload.projectId)
                    validateSubproject(
                        upload.projectId,
                        upload.subprojectId
                    )

                    TnpaDocumentsTable.insert {
                        it[TnpaDocumentsTable.id] = id
                        it[projectId] = upload.projectId
                        it[subprojectId] = upload.subprojectId
                        it[originalName] = upload.originalName
                        it[TnpaDocumentsTable.storedName] = storedName
                        it[storagePath] = finalFile.absolutePath
                        it[mimeType] = upload.mimeType
                        it[sizeBytes] = upload.sizeBytes
                        it[checksumSha256] = upload.sha256
                        it[description] = upload.description
                        it[uploadedBy] = session.id
                        it[uploadedAt] = System.currentTimeMillis()
                        it[deletedAt] = null
                        it[deletedBy] = null
                    }

                    (
                        TnpaDocumentsTable
                            .innerJoin(ProjectsTable)
                            .leftJoin(SubprojectsTable)
                            .innerJoin(UsersTable)
                        )
                        .select { TnpaDocumentsTable.id eq id }
                        .single()
                        .let(::tnpaDocumentDto)
                }

                call.respond(HttpStatusCode.Created, dto)
            } catch (_: ProjectNotFoundException) {
                finalFile.delete()
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Проект не найден")
                )
            } catch (_: InvalidSubprojectException) {
                finalFile.delete()
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(
                        "error" to
                            "Подпроект не принадлежит выбранному проекту"
                    )
                )
            } catch (error: Throwable) {
                finalFile.delete()
                throw error
            }
        }

        get("/{documentId}/download") {
            val session = call.requireSessionUser() ?: return@get
            if (!session.canReadTnpa()) {
                return@get call.respond(HttpStatusCode.Forbidden)
            }

            val documentId = call.parameters["documentId"].asUuidOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Некорректный documentId")
                )

            val document = transaction {
                TnpaDocumentsTable
                    .select {
                        (TnpaDocumentsTable.id eq documentId) and
                            TnpaDocumentsTable.deletedAt.isNull()
                    }
                    .singleOrNull()
                    ?.let {
                        Triple(
                            it[TnpaDocumentsTable.originalName],
                            it[TnpaDocumentsTable.mimeType],
                            File(it[TnpaDocumentsTable.storagePath])
                        )
                    }
            } ?: return@get call.respond(HttpStatusCode.NotFound)

            if (!document.third.exists() || !document.third.isFile) {
                return@get call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Файл отсутствует в хранилище")
                )
            }

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(
                        ContentDisposition.Parameters.FileName,
                        document.first
                    )
                    .toString()
            )
            call.response.header(HttpHeaders.ContentType, document.second)
            call.respondFile(document.third)
        }

        delete("/{documentId}") {
            val session = call.requireSessionUser() ?: return@delete
            if (!session.canManageTnpa()) {
                return@delete call.respond(HttpStatusCode.Forbidden)
            }

            val documentId = call.parameters["documentId"].asUuidOrNull()
                ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Некорректный documentId")
                )

            val affected = transaction {
                TnpaDocumentsTable.update(
                    {
                        (TnpaDocumentsTable.id eq documentId) and
                            TnpaDocumentsTable.deletedAt.isNull()
                    }
                ) {
                    it[deletedAt] = System.currentTimeMillis()
                    it[deletedBy] = session.id
                }
            }

            if (affected == 0) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall
    .receiveTnpaUpload(
        storageRoot: File
    ): TnpaUpload {
    val multipart = receiveMultipart()
    var projectId: UUID? = null
    var subprojectId: UUID? = null
    var description = ""
    var temporaryFile: File? = null
    var originalName: String? = null
    var mimeType = "application/octet-stream"
    var sizeBytes = 0L
    var sha256 = ""

    try {
        multipart.forEachPart { part ->
            try {
                when (part) {
                    is PartData.FormItem -> when (part.name) {
                        "projectId" ->
                            projectId = part.value.asUuidOrNull()
                        "subprojectId" ->
                            subprojectId = part.value.asUuidOrNull()
                        "description" ->
                            description = part.value.trim()
                    }

                    is PartData.FileItem -> {
                        if (part.name != "file" || temporaryFile != null) {
                            return@forEachPart
                        }

                        originalName = sanitizeFileName(
                            part.originalFileName ?: "document"
                        )
                        mimeType = part.contentType?.toString()
                            ?: "application/octet-stream"

                        val tempDirectory =
                            File(storageRoot, ".tmp").apply { mkdirs() }
                        val temp = File.createTempFile(
                            "tnpa-",
                            ".upload",
                            tempDirectory
                        )
                        val digest = MessageDigest.getInstance("SHA-256")

                        withContext(Dispatchers.IO) {
                            part.streamProvider().use { input ->
                                temp.outputStream().use { output ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break

                                        sizeBytes += read
                                        if (sizeBytes > MAX_TNPA_FILE_BYTES) {
                                            throw UploadValidationException(
                                                HttpStatusCode.PayloadTooLarge,
                                                "Файл превышает 1 ГБ"
                                            )
                                        }

                                        digest.update(buffer, 0, read)
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                        }

                        temporaryFile = temp
                        sha256 = digest.digest().toHex()
                    }

                    else -> Unit
                }
            } finally {
                part.dispose()
            }
        }

        val requiredProjectId = projectId
            ?: throw UploadValidationException(
                HttpStatusCode.BadRequest,
                "projectId обязателен"
            )
        val requiredTemporaryFile = temporaryFile
            ?: throw UploadValidationException(
                HttpStatusCode.BadRequest,
                "Файл обязателен"
            )

        return TnpaUpload(
            projectId = requiredProjectId,
            subprojectId = subprojectId,
            description = description,
            originalName = originalName ?: "document",
            mimeType = mimeType,
            temporaryFile = requiredTemporaryFile,
            sizeBytes = sizeBytes,
            sha256 = sha256
        )
    } catch (error: Throwable) {
        temporaryFile?.delete()
        throw error
    }
}

private fun subprojectDto(row: ResultRow) = SubprojectDto(
    id = row[SubprojectsTable.id].value.toString(),
    projectId = row[SubprojectsTable.projectId].value.toString(),
    name = row[SubprojectsTable.name],
    code = row[SubprojectsTable.code],
    description = row[SubprojectsTable.description],
    isActive = row[SubprojectsTable.isActive],
    sortOrder = row[SubprojectsTable.sortOrder],
    createdAt = row[SubprojectsTable.createdAt],
    updatedAt = row[SubprojectsTable.updatedAt],
    archivedAt = row[SubprojectsTable.archivedAt]
)

private fun balanceTransactionDto(
    row: ResultRow
) = EmployeeBalanceTransactionDto(
    id = row[EmployeeBalanceTransactionsTable.id].value.toString(),
    userId = row[
        EmployeeBalanceTransactionsTable.userId
    ].value.toString(),
    salaryRecordId = row[
        EmployeeBalanceTransactionsTable.salaryRecordId
    ]?.value?.toString(),
    transactionType = row[
        EmployeeBalanceTransactionsTable.transactionType
    ],
    amount = row[
        EmployeeBalanceTransactionsTable.amount
    ].toDouble(),
    comment = row[EmployeeBalanceTransactionsTable.comment],
    createdBy = row[
        EmployeeBalanceTransactionsTable.createdBy
    ].value.toString(),
    createdAt = row[EmployeeBalanceTransactionsTable.createdAt],
    reversedTransactionId = row[
        EmployeeBalanceTransactionsTable.reversedTransactionId
    ]?.toString()
)

private fun calculateBalance(
    rows: List<EmployeeBalanceTransactionDto>
): Double {
    val byId = rows.associateBy { it.id }

    return rows.sumOf { row ->
        when (row.transactionType) {
            "WITHHOLDING",
            "ADJUSTMENT_INCREASE" -> row.amount

            "REPAYMENT",
            "ADJUSTMENT_DECREASE" -> -row.amount

            "REVERSAL" -> {
                when (
                    byId[row.reversedTransactionId]?.transactionType
                ) {
                    "WITHHOLDING",
                    "ADJUSTMENT_INCREASE" -> -row.amount
                    "REPAYMENT",
                    "ADJUSTMENT_DECREASE" -> row.amount
                    else -> 0.0
                }
            }

            else -> 0.0
        }
    }.let { kotlin.math.round(it * 100.0) / 100.0 }
}

private fun tnpaDocumentDto(row: ResultRow) = TnpaDocumentDto(
    id = row[TnpaDocumentsTable.id].value.toString(),
    projectId = row[TnpaDocumentsTable.projectId].value.toString(),
    projectName = row.getOrNull(ProjectsTable.name).orEmpty(),
    subprojectId = row[
        TnpaDocumentsTable.subprojectId
    ]?.value?.toString(),
    subprojectName = row.getOrNull(SubprojectsTable.name).orEmpty(),
    originalName = row[TnpaDocumentsTable.originalName],
    mimeType = row[TnpaDocumentsTable.mimeType],
    sizeBytes = row[TnpaDocumentsTable.sizeBytes],
    checksumSha256 = row[TnpaDocumentsTable.checksumSha256],
    description = row[TnpaDocumentsTable.description],
    uploadedBy = row[TnpaDocumentsTable.uploadedBy].value.toString(),
    uploaderName = row.getOrNull(UsersTable.name).orEmpty(),
    uploadedAt = row[TnpaDocumentsTable.uploadedAt],
    downloadUrl =
        "/api/tnpa/${row[TnpaDocumentsTable.id].value}/download",
    deletedAt = row[TnpaDocumentsTable.deletedAt]
)

private fun ensureProjectExists(projectId: UUID) {
    if (
        ProjectsTable
            .select { ProjectsTable.id eq projectId }
            .limit(1)
            .none()
    ) {
        throw ProjectNotFoundException()
    }
}

private fun validateSubproject(
    projectId: UUID,
    subprojectId: UUID?
) {
    if (subprojectId == null) return

    val valid = SubprojectsTable
        .select {
            (SubprojectsTable.id eq subprojectId) and
                (SubprojectsTable.projectId eq projectId)
        }
        .limit(1)
        .any()

    if (!valid) throw InvalidSubprojectException()
}

private fun userExists(userId: UUID): Boolean =
    UsersTable
        .select { UsersTable.id eq userId }
        .limit(1)
        .any()

private fun String?.asUuidOrNull(): UUID? =
    this?.trim()?.takeIf(String::isNotBlank)?.let {
        runCatching { UUID.fromString(it) }.getOrNull()
    }

private fun parseDateOrNull(value: String): LocalDate? =
    runCatching { LocalDate.parse(value.trim()) }.getOrNull()

private fun SessionUser.canManageProjects(): Boolean =
    role in setOf("super-admin", "admin")

private fun SessionUser.canViewAllHours(): Boolean =
    role in setOf("super-admin", "admin", "director")

private fun SessionUser.canManagePayroll(): Boolean =
    role in setOf("super-admin", "admin")

private fun SessionUser.canManageTrips(): Boolean =
    role in setOf("super-admin", "admin", "logist")

private fun SessionUser.canReadTnpa(): Boolean = true

private fun SessionUser.canManageTnpa(): Boolean =
    role in setOf("super-admin", "admin")

private suspend fun io.ktor.server.application.ApplicationCall
    .requireSessionUser(): SessionUser? {
    val principal = principal<JWTPrincipal>()
        ?: run {
            respond(HttpStatusCode.Unauthorized)
            return null
        }

    val id = principal.payload
        .getClaim("userId")
        .asString()
        .asUuidOrNull()
        ?: principal.payload.subject.asUuidOrNull()
        ?: run {
            respond(HttpStatusCode.Unauthorized)
            return null
        }

    val role = principal.payload
        .getClaim("role")
        .asString()
        ?.trim()
        ?.lowercase()
        .orEmpty()

    return SessionUser(id = id, role = role)
}

private fun entryHours(row: ResultRow): Double {
    return row[TimeEntriesTable.hours]
}

private fun existingTimeEntryDto(row: ResultRow) =
    com.proles.server.model.TimeEntry(
        id = row[TimeEntriesTable.id].value.toString(),
        userId = row[TimeEntriesTable.userId].value.toString(),
        projectId = row[TimeEntriesTable.projectId].value.toString(),
        subprojectId = row[
            TimeEntriesTable.subprojectId
        ]?.value?.toString(),
        subprojectName = row.getOrNull(SubprojectsTable.name).orEmpty(),
        date = row[TimeEntriesTable.date].toString(),
        hours = row[TimeEntriesTable.hours],
        description = row[TimeEntriesTable.description],
        createdAt = row[TimeEntriesTable.createdAt]
    )

private fun sanitizeFileName(value: String): String =
    value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001F]"), "_")
        .take(500)
        .ifBlank { "document" }

private fun ByteArray.toHex(): String =
    joinToString("") { byte -> "%02x".format(byte) }

private sealed interface TripCompletionResult {
    data object Success : TripCompletionResult
    data object NotFound : TripCompletionResult
    data object Forbidden : TripCompletionResult
    data object InvalidRange : TripCompletionResult
}

private class ProjectNotFoundException : RuntimeException()
private class UserNotFoundException : RuntimeException()
private class InvalidSubprojectException : RuntimeException()
private class DuplicateSubprojectException : RuntimeException()

private class InsufficientBalanceException(
    val balance: Double
) : RuntimeException()

private class UploadValidationException(
    val status: HttpStatusCode,
    override val message: String
) : RuntimeException(message)