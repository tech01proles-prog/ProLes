package com.proles.server.routes

import com.proles.server.config.SessionManager
import com.proles.server.data.*
import com.proles.server.model.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.math.BigDecimal
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

private const val MAX_TNPA_FILE_BYTES = 1024L * 1024L * 1024L

private data class PlatformSession(
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

fun Route.platformRoutes() {
    val tnpaRoot = File(
        System.getenv("TNPA_STORAGE_DIR") ?: "data/tnpa"
    ).apply { mkdirs() }

    route("/api/v1") {
        subprojectRoutes()
        groupedHoursRoutes()
        employeeBalanceRoutes()
        tnpaRoutes(tnpaRoot)
    }
}

private fun Route.subprojectRoutes() {
    route("/projects/{projectId}/subprojects") {
        get {
            call.requirePlatformSession() ?: return@get

            val projectId = call.parameters["projectId"].asUuidOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Некорректный projectId")
                )

            val result = transaction {
                val projectExists = ProjectsTable
                    .selectAll()
                    .where { ProjectsTable.id eq projectId }
                    .limit(1)
                    .any()

                if (!projectExists) {
                    null
                } else {
                    SubprojectsTable
                        .selectAll()
                        .where {
                            SubprojectsTable.projectId eq projectId
                        }
                        .orderBy(
                            SubprojectsTable.sortOrder to SortOrder.ASC,
                            SubprojectsTable.name to SortOrder.ASC
                        )
                        .map(::subprojectDto)
                }
            }

            if (result == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Проект не найден")
                )
            } else {
                call.respond(result)
            }
        }

        post {
            val session =
                call.requirePlatformSession() ?: return@post

            if (!session.canManageProjects()) {
                return@post call.respond(HttpStatusCode.Forbidden)
            }

            val projectId = call.parameters["projectId"].asUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Некорректный projectId")
                )

            val request = try {
                call.receive<CreateSubprojectRequest>()
            } catch (e: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Некорректный JSON")
                )
            }

            val name = request.name.trim()
            if (name.isBlank()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Название обязательно")
                )
            }

            val result = transaction {
                if (
                    ProjectsTable
                        .selectAll()
                        .where { ProjectsTable.id eq projectId }
                        .limit(1)
                        .none()
                ) {
                    return@transaction SubprojectCreateResult.NotFound
                }

                if (
                    SubprojectsTable
                        .selectAll()
                        .where {
                            (SubprojectsTable.projectId eq projectId) and
                                (SubprojectsTable.name eq name)
                        }
                        .limit(1)
                        .any()
                ) {
                    return@transaction SubprojectCreateResult.Duplicate
                }

                val id = UUID.randomUUID()
                val now = System.currentTimeMillis()

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
                    it[archivedAt] = null
                }

                SubprojectCreateResult.Created(
                    SubprojectsTable
                        .selectAll()
                        .where { SubprojectsTable.id eq id }
                        .single()
                        .let(::subprojectDto)
                )
            }

            when (result) {
                SubprojectCreateResult.NotFound ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Проект не найден")
                    )

                SubprojectCreateResult.Duplicate ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf(
                            "error" to
                                "Подпроект с таким названием существует"
                        )
                    )

                is SubprojectCreateResult.Created ->
                    call.respond(HttpStatusCode.Created, result.dto)
            }
        }
    }

    route("/subprojects/{subprojectId}") {
        patch {
            val session =
                call.requirePlatformSession() ?: return@patch

            if (!session.canManageProjects()) {
                return@patch call.respond(HttpStatusCode.Forbidden)
            }

            val subprojectId =
                call.parameters["subprojectId"].asUuidOrNull()
                    ?: return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Некорректный subprojectId")
                    )

            val request = try {
                call.receive<UpdateSubprojectRequest>()
            } catch (e: Exception) {
                return@patch call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Некорректный JSON")
                )
            }

            val result = transaction {
                val existing = SubprojectsTable
                    .selectAll()
                    .where { SubprojectsTable.id eq subprojectId }
                    .singleOrNull()
                    ?: return@transaction SubprojectUpdateResult.NotFound

                val projectId =
                    existing[SubprojectsTable.projectId].value
                val newName = request.name
                    ?.trim()
                    ?.takeIf(String::isNotBlank)

                if (
                    newName != null &&
                    SubprojectsTable
                        .selectAll()
                        .where {
                            (SubprojectsTable.projectId eq projectId) and
                                (SubprojectsTable.name eq newName) and
                                (SubprojectsTable.id neq subprojectId)
                        }
                        .limit(1)
                        .any()
                ) {
                    return@transaction SubprojectUpdateResult.Duplicate
                }

                SubprojectsTable.update(
                    { SubprojectsTable.id eq subprojectId }
                ) {
                    newName?.let { value -> it[name] = value }
                    request.code?.trim()?.let { value ->
                        it[code] = value
                    }
                    request.description?.trim()?.let { value ->
                        it[description] = value
                    }
                    request.isActive?.let { value ->
                        it[isActive] = value
                        it[archivedAt] = if (value) {
                            null
                        } else {
                            System.currentTimeMillis()
                        }
                    }
                    request.sortOrder?.let { value ->
                        it[sortOrder] = value
                    }
                    it[updatedAt] = System.currentTimeMillis()
                }

                SubprojectUpdateResult.Updated(
                    SubprojectsTable
                        .selectAll()
                        .where { SubprojectsTable.id eq subprojectId }
                        .single()
                        .let(::subprojectDto)
                )
            }

            when (result) {
                SubprojectUpdateResult.NotFound ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Подпроект не найден")
                    )

                SubprojectUpdateResult.Duplicate ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf(
                            "error" to
                                "Подпроект с таким названием существует"
                        )
                    )

                is SubprojectUpdateResult.Updated ->
                    call.respond(result.dto)
            }
        }

        delete {
            val session =
                call.requirePlatformSession() ?: return@delete

            if (!session.canManageProjects()) {
                return@delete call.respond(HttpStatusCode.Forbidden)
            }

            val subprojectId =
                call.parameters["subprojectId"].asUuidOrNull()
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
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun Route.groupedHoursRoutes() {
    get("/hours/grouped") {
        val session =
            call.requirePlatformSession() ?: return@get

        val userParameter =
            call.request.queryParameters["userId"]
        val requestedUserId =
            if (userParameter == null) {
                session.id
            } else {
                userParameter.asUuidOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Некорректный userId")
                    )
            }

        if (
            requestedUserId != session.id &&
            !session.canViewAllHours()
        ) {
            return@get call.respond(HttpStatusCode.Forbidden)
        }

        val fromParameter =
            call.request.queryParameters["from"]
        val toParameter =
            call.request.queryParameters["to"]

        val from = fromParameter?.let(::parseDateOrNull)
        val to = toParameter?.let(::parseDateOrNull)

        if (fromParameter != null && from == null) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Некорректная дата from")
            )
        }

        if (toParameter != null && to == null) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Некорректная дата to")
            )
        }

        if (from != null && to != null && from > to) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "from позже to")
            )
        }

        val response = transaction {
            var condition: Op<Boolean> =
                TimeEntriesTable.userId eq requestedUserId

            if (from != null) {
                condition = condition and
                    (TimeEntriesTable.date greaterEq from)
            }

            if (to != null) {
                condition = condition and
                    (TimeEntriesTable.date lessEq to)
            }

            val rows = TimeEntriesTable
                .selectAll()
                .where { condition }
                .orderBy(TimeEntriesTable.date to SortOrder.DESC)
                .toList()

            val projects = ProjectsTable
                .selectAll()
                .associate {
                    it[ProjectsTable.id].value to
                        it[ProjectsTable.name]
                }

            val subprojects = SubprojectsTable
                .selectAll()
                .associate {
                    it[SubprojectsTable.id].value to
                        it[SubprojectsTable.name]
                }

            val groups = rows
                .groupBy {
                    it[TimeEntriesTable.projectId].value
                }
                .map { (projectId, projectRows) ->
                    val withoutSubproject = projectRows
                        .filter {
                            it[TimeEntriesTable.subprojectId] == null
                        }
                        .map {
                            timeEntryDto(
                                it,
                                null,
                                ""
                            )
                        }

                    val subprojectGroups = projectRows
                        .filter {
                            it[TimeEntriesTable.subprojectId] != null
                        }
                        .groupBy {
                            it[TimeEntriesTable.subprojectId]!!.value
                        }
                        .map { (subprojectId, subprojectRows) ->
                            HoursSubprojectGroupDto(
                                subprojectId =
                                    subprojectId.toString(),
                                subprojectName =
                                    subprojects[subprojectId]
                                        ?: "Подпроект",
                                totalHours =
                                    subprojectRows.sumOf {
                                        it[TimeEntriesTable.hours]
                                            .toDouble()
                                    },
                                entries = subprojectRows.map {
                                    timeEntryDto(
                                        it,
                                        subprojectId,
                                        subprojects[subprojectId]
                                            .orEmpty()
                                    )
                                }
                            )
                        }
                        .sortedBy {
                            it.subprojectName.lowercase()
                        }

                    HoursProjectGroupDto(
                        projectId = projectId.toString(),
                        projectName =
                            projects[projectId]
                                ?: projectRows.first()[
                                    TimeEntriesTable.projectName
                                ],
                        totalHours = projectRows.sumOf {
                            it[TimeEntriesTable.hours].toDouble()
                        },
                        subprojects = subprojectGroups,
                        entriesWithoutSubproject = withoutSubproject
                    )
                }
                .sortedBy { it.projectName.lowercase() }

            GroupedHoursResponseDto(
                userId = requestedUserId.toString(),
                totalHours = rows.sumOf {
                    it[TimeEntriesTable.hours].toDouble()
                },
                groups = groups
            )
        }

        call.respond(response)
    }
}

private fun Route.employeeBalanceRoutes() {
    route("/employee-balances/{userId}") {
        get {
            val session =
                call.requirePlatformSession() ?: return@get
            val userId =
                call.parameters["userId"].asUuidOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Некорректный userId")
                    )

            if (
                userId != session.id &&
                !session.canManagePayroll()
            ) {
                return@get call.respond(HttpStatusCode.Forbidden)
            }

            val response = transaction {
                if (
                    UsersTable
                        .selectAll()
                        .where { UsersTable.id eq userId }
                        .limit(1)
                        .none()
                ) {
                    return@transaction null
                }

                val rows = EmployeeBalanceTransactionsTable
                    .selectAll()
                    .where {
                        EmployeeBalanceTransactionsTable.userId eq
                            userId
                    }
                    .orderBy(
                        EmployeeBalanceTransactionsTable.createdAt to
                            SortOrder.DESC
                    )
                    .toList()

                EmployeeBalanceDto(
                    userId = userId.toString(),
                    balance = calculateBalance(rows),
                    transactions = rows.map(
                        ::balanceTransactionDto
                    )
                )
            }

            if (response == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(response)
            }
        }

        post("/repayments") {
            val session =
                call.requirePlatformSession() ?: return@post

            if (!session.canManagePayroll()) {
                return@post call.respond(HttpStatusCode.Forbidden)
            }

            val userId =
                call.parameters["userId"].asUuidOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Некорректный userId")
                    )

            val request = try {
                call.receive<BalanceRepaymentRequest>()
            } catch (e: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Некорректный JSON")
                )
            }

            if (
                !request.amount.isFinite() ||
                request.amount <= 0.0
            ) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Сумма должна быть больше нуля")
                )
            }

            val idempotencyKey = request.idempotencyKey
                ?.trim()
                ?.takeIf(String::isNotBlank)

            val result = transaction {
                val user = UsersTable
                    .selectAll()
                    .where { UsersTable.id eq userId }
                    .forUpdate()
                    .singleOrNull()
                    ?: return@transaction RepaymentResult.UserNotFound

                idempotencyKey?.let { key ->
                    EmployeeBalanceTransactionsTable
                        .selectAll()
                        .where {
                            EmployeeBalanceTransactionsTable
                                .idempotencyKey eq key
                        }
                        .singleOrNull()
                        ?.let {
                            return@transaction RepaymentResult.Created(
                                balanceTransactionDto(it)
                            )
                        }
                }

                val currentRows =
                    EmployeeBalanceTransactionsTable
                        .selectAll()
                        .where {
                            EmployeeBalanceTransactionsTable.userId eq
                                user[UsersTable.id].value
                        }
                        .toList()

                val balance = calculateBalance(currentRows)

                if (request.amount > balance + 0.005) {
                    return@transaction RepaymentResult.Insufficient(
                        balance
                    )
                }

                val salaryRecordId =
                    request.salaryRecordId.asUuidOrNull()

                if (
                    request.salaryRecordId?.isNotBlank() == true &&
                    salaryRecordId == null
                ) {
                    return@transaction RepaymentResult.InvalidSalary
                }

                val id = UUID.randomUUID()

                EmployeeBalanceTransactionsTable.insert {
                    it[EmployeeBalanceTransactionsTable.id] = id
                    it[EmployeeBalanceTransactionsTable.userId] =
                        userId
                    it[
                        EmployeeBalanceTransactionsTable.salaryRecordId
                    ] = salaryRecordId
                    it[transactionType] = "REPAYMENT"
                    it[amount] = request.amount.toMoney()
                    it[comment] = request.comment.trim()
                    it[createdBy] = session.id
                    it[createdAt] = System.currentTimeMillis()
                    it[reversedTransactionId] = null
                    it[
                        EmployeeBalanceTransactionsTable.idempotencyKey
                    ] = idempotencyKey
                }

                RepaymentResult.Created(
                    EmployeeBalanceTransactionsTable
                        .selectAll()
                        .where {
                            EmployeeBalanceTransactionsTable.id eq id
                        }
                        .single()
                        .let(::balanceTransactionDto)
                )
            }

            when (result) {
                RepaymentResult.UserNotFound ->
                    call.respond(HttpStatusCode.NotFound)

                RepaymentResult.InvalidSalary ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Некорректный salaryRecordId")
                    )

                is RepaymentResult.Insufficient ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf(
                            "error" to
                                "Сумма превышает доступный баланс",
                            "balance" to result.balance
                        )
                    )

                is RepaymentResult.Created ->
                    call.respond(
                        HttpStatusCode.Created,
                        result.dto
                    )
            }
        }
    }
}

private fun Route.tnpaRoutes(storageRoot: File) {
    route("/tnpa") {
        get {
            val session =
                call.requirePlatformSession() ?: return@get

            if (!session.canReadTnpa()) {
                return@get call.respond(HttpStatusCode.Forbidden)
            }

            val projectParameter =
                call.request.queryParameters["projectId"]
            val subprojectParameter =
                call.request.queryParameters["subprojectId"]

            val projectId =
                projectParameter.asUuidOrNull()
            val subprojectId =
                subprojectParameter.asUuidOrNull()

            if (projectParameter != null && projectId == null) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Некорректный projectId")
                )
            }

            if (
                subprojectParameter != null &&
                subprojectId == null
            ) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Некорректный subprojectId")
                )
            }

            val result = transaction {
                var condition: Op<Boolean> =
                    TnpaDocumentsTable.deletedAt.isNull()

                projectId?.let {
                    condition = condition and
                        (TnpaDocumentsTable.projectId eq it)
                }

                subprojectId?.let {
                    condition = condition and
                        (TnpaDocumentsTable.subprojectId eq it)
                }

                TnpaDocumentsTable
                    .selectAll()
                    .where { condition }
                    .orderBy(
                        TnpaDocumentsTable.uploadedAt to
                            SortOrder.DESC
                    )
                    .map(::tnpaDocumentDto)
            }

            call.respond(result)
        }

        post {
            val session =
                call.requirePlatformSession() ?: return@post

            if (!session.canManageTnpa()) {
                return@post call.respond(HttpStatusCode.Forbidden)
            }

            val upload = try {
                call.receiveTnpaUpload(storageRoot)
            } catch (e: UploadValidationException) {
                return@post call.respond(
                    e.status,
                    mapOf("error" to e.message)
                )
            }

            val id = UUID.randomUUID()
            val directory = File(
                storageRoot,
                "${upload.projectId}/" +
                    "${upload.subprojectId ?: "root"}"
            ).apply { mkdirs() }

            val storedName = "$id.bin"
            val finalFile = File(directory, storedName)

            try {
                withContext(Dispatchers.IO) {
                    try {
                        Files.move(
                            upload.temporaryFile.toPath(),
                            finalFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (
                        _: AtomicMoveNotSupportedException
                    ) {
                        Files.move(
                            upload.temporaryFile.toPath(),
                            finalFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                }

                val result = transaction {
                    if (
                        ProjectsTable
                            .selectAll()
                            .where {
                                ProjectsTable.id eq upload.projectId
                            }
                            .limit(1)
                            .none()
                    ) {
                        return@transaction TnpaCreateResult.ProjectMissing
                    }

                    if (
                        upload.subprojectId != null &&
                        SubprojectsTable
                            .selectAll()
                            .where {
                                (SubprojectsTable.id eq
                                    upload.subprojectId) and
                                    (SubprojectsTable.projectId eq
                                        upload.projectId) and
                                    (SubprojectsTable.isActive eq true)
                            }
                            .limit(1)
                            .none()
                    ) {
                        return@transaction TnpaCreateResult.InvalidSubproject
                    }

                    TnpaDocumentsTable.insert {
                        it[TnpaDocumentsTable.id] = id
                        it[projectId] = upload.projectId
                        it[subprojectId] = upload.subprojectId
                        it[originalName] = upload.originalName
                        it[TnpaDocumentsTable.storedName] =
                            storedName
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

                    TnpaCreateResult.Created(
                        TnpaDocumentsTable
                            .selectAll()
                            .where {
                                TnpaDocumentsTable.id eq id
                            }
                            .single()
                            .let(::tnpaDocumentDto)
                    )
                }

                when (result) {
                    TnpaCreateResult.ProjectMissing -> {
                        finalFile.delete()
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Проект не найден")
                        )
                    }

                    TnpaCreateResult.InvalidSubproject -> {
                        finalFile.delete()
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to
                                    "Некорректный подпроект"
                            )
                        )
                    }

                    is TnpaCreateResult.Created ->
                        call.respond(
                            HttpStatusCode.Created,
                            result.dto
                        )
                }
            } catch (e: Throwable) {
                finalFile.delete()
                throw e
            }
        }

        get("/{documentId}/download") {
            val session =
                call.requirePlatformSession() ?: return@get

            if (!session.canReadTnpa()) {
                return@get call.respond(HttpStatusCode.Forbidden)
            }

            val documentId =
                call.parameters["documentId"].asUuidOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Некорректный documentId")
                    )

            val document = transaction {
                TnpaDocumentsTable
                    .selectAll()
                    .where {
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

            if (!document.third.isFile) {
                return@get call.respond(HttpStatusCode.NotFound)
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
            call.response.header(
                HttpHeaders.ContentType,
                document.second
            )
            call.respondFile(document.third)
        }

        delete("/{documentId}") {
            val session =
                call.requirePlatformSession() ?: return@delete

            if (!session.canManageTnpa()) {
                return@delete call.respond(HttpStatusCode.Forbidden)
            }

            val documentId =
                call.parameters["documentId"].asUuidOrNull()
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

private suspend fun ApplicationCall.receiveTnpaUpload(
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
                        if (
                            part.name != "file" ||
                            temporaryFile != null
                        ) {
                            return@forEachPart
                        }

                        originalName = sanitizeFileName(
                            part.originalFileName ?: "document"
                        )
                        mimeType =
                            part.contentType?.toString()
                                ?: "application/octet-stream"

                        val temporaryDirectory = File(
                            storageRoot,
                            ".tmp"
                        ).apply { mkdirs() }

                        val temporary = File.createTempFile(
                            "tnpa-",
                            ".upload",
                            temporaryDirectory
                        )
                        val digest =
                            MessageDigest.getInstance("SHA-256")

                        withContext(Dispatchers.IO) {
                            part.streamProvider().use { input ->
                                temporary.outputStream().use { output ->
                                    val buffer =
                                        ByteArray(DEFAULT_BUFFER_SIZE)

                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break

                                        sizeBytes += read

                                        if (
                                            sizeBytes >
                                            MAX_TNPA_FILE_BYTES
                                        ) {
                                            throw UploadValidationException(
                                                HttpStatusCode
                                                    .PayloadTooLarge,
                                                "Файл превышает 1 ГБ"
                                            )
                                        }

                                        digest.update(
                                            buffer,
                                            0,
                                            read
                                        )
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                        }

                        temporaryFile = temporary
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

        val requiredFile = temporaryFile
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
            temporaryFile = requiredFile,
            sizeBytes = sizeBytes,
            sha256 = sha256
        )
    } catch (e: Throwable) {
        temporaryFile?.delete()
        throw e
    }
}

private suspend fun ApplicationCall.requirePlatformSession():
    PlatformSession? {
    val token = request.headers["X-Session-Token"]
    val session = SessionManager.validate(token)

    if (session == null) {
        respond(
            HttpStatusCode.Unauthorized,
            mapOf("error" to "Session expired")
        )
        return null
    }

    return PlatformSession(
        id = session.userId,
        role = session.role.trim().lowercase()
    )
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

private fun timeEntryDto(
    row: ResultRow,
    subprojectId: UUID?,
    subprojectName: String
) = TimeEntry(
    id = row[TimeEntriesTable.id].value.toString(),
    userId = row[TimeEntriesTable.userId].value.toString(),
    projectId = row[TimeEntriesTable.projectId].value.toString(),
    subprojectId = subprojectId?.toString(),
    subprojectName = subprojectName,
    projectName = row[TimeEntriesTable.projectName],
    date = row[TimeEntriesTable.date].toString(),
    hours = row[TimeEntriesTable.hours],
    country = row[TimeEntriesTable.country],
    comment = row[TimeEntriesTable.comment].orEmpty(),
    synced = row[TimeEntriesTable.synced]
)

private fun balanceTransactionDto(
    row: ResultRow
): EmployeeBalanceTransactionDto {
    val creatorId =
        row[EmployeeBalanceTransactionsTable.createdBy].value

    val creatorName = UsersTable
        .selectAll()
        .where { UsersTable.id eq creatorId }
        .limit(1)
        .singleOrNull()
        ?.get(UsersTable.name)
        .orEmpty()

    return EmployeeBalanceTransactionDto(
        id = row[
            EmployeeBalanceTransactionsTable.id
        ].value.toString(),
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
        comment = row[
            EmployeeBalanceTransactionsTable.comment
        ],
        createdBy = creatorId.toString(),
        createdByName = creatorName,
        createdAt = row[
            EmployeeBalanceTransactionsTable.createdAt
        ],
        reversedTransactionId = row[
            EmployeeBalanceTransactionsTable.reversedTransactionId
        ]?.toString()
    )
}

private fun calculateBalance(rows: List<ResultRow>): Double =
    rows.sumOf { row ->
        val amount = row[
            EmployeeBalanceTransactionsTable.amount
        ].toDouble()

        when (
            row[
                EmployeeBalanceTransactionsTable.transactionType
            ]
        ) {
            "WITHHOLDING",
            "ADJUSTMENT_INCREASE" -> amount

            "REPAYMENT",
            "ADJUSTMENT_DECREASE" -> -amount

            else -> 0.0
        }
    }.toMoneyDouble()

private fun tnpaDocumentDto(row: ResultRow): TnpaDocumentDto {
    val projectId = row[TnpaDocumentsTable.projectId].value
    val subprojectId =
        row[TnpaDocumentsTable.subprojectId]?.value
    val uploaderId = row[TnpaDocumentsTable.uploadedBy].value

    val projectName = ProjectsTable
        .selectAll()
        .where { ProjectsTable.id eq projectId }
        .limit(1)
        .singleOrNull()
        ?.get(ProjectsTable.name)
        .orEmpty()

    val subprojectName = subprojectId?.let { id ->
        SubprojectsTable
            .selectAll()
            .where { SubprojectsTable.id eq id }
            .limit(1)
            .singleOrNull()
            ?.get(SubprojectsTable.name)
    }.orEmpty()

    val uploaderName = UsersTable
        .selectAll()
        .where { UsersTable.id eq uploaderId }
        .limit(1)
        .singleOrNull()
        ?.get(UsersTable.name)
        .orEmpty()

    return TnpaDocumentDto(
        id = row[TnpaDocumentsTable.id].value.toString(),
        projectId = projectId.toString(),
        projectName = projectName,
        subprojectId = subprojectId?.toString(),
        subprojectName = subprojectName,
        originalName = row[TnpaDocumentsTable.originalName],
        mimeType = row[TnpaDocumentsTable.mimeType],
        sizeBytes = row[TnpaDocumentsTable.sizeBytes],
        checksumSha256 =
            row[TnpaDocumentsTable.checksumSha256],
        description = row[TnpaDocumentsTable.description],
        uploadedBy = uploaderId.toString(),
        uploaderName = uploaderName,
        uploadedAt = row[TnpaDocumentsTable.uploadedAt],
        downloadUrl =
            "/api/v1/tnpa/" +
                "${row[TnpaDocumentsTable.id].value}/download",
        deletedAt = row[TnpaDocumentsTable.deletedAt]
    )
}

private fun String?.asUuidOrNull(): UUID? =
    this
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }

private fun parseDateOrNull(value: String): LocalDate? =
    runCatching {
        LocalDate.parse(value.trim())
    }.getOrNull()

private fun PlatformSession.canManageProjects(): Boolean =
    role in setOf("superadmin", "admin")

private fun PlatformSession.canViewAllHours(): Boolean =
    role in setOf("superadmin", "admin", "director")

private fun PlatformSession.canManagePayroll(): Boolean =
    role in setOf("superadmin", "admin", "director")

private fun PlatformSession.canReadTnpa(): Boolean = true

private fun PlatformSession.canManageTnpa(): Boolean =
    role in setOf("superadmin", "admin")

private fun sanitizeFileName(value: String): String =
    value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001F]"), "_")
        .take(500)
        .ifBlank { "document" }

private fun ByteArray.toHex(): String =
    joinToString("") { byte -> "%02x".format(byte) }

private fun Double.toMoney(): BigDecimal =
    BigDecimal.valueOf(this).setScale(
        2,
        java.math.RoundingMode.HALF_UP
    )

private fun Double.toMoneyDouble(): Double =
    toMoney().toDouble()

private sealed interface SubprojectCreateResult {
    data object NotFound : SubprojectCreateResult
    data object Duplicate : SubprojectCreateResult
    data class Created(
        val dto: SubprojectDto
    ) : SubprojectCreateResult
}

private sealed interface SubprojectUpdateResult {
    data object NotFound : SubprojectUpdateResult
    data object Duplicate : SubprojectUpdateResult
    data class Updated(
        val dto: SubprojectDto
    ) : SubprojectUpdateResult
}

private sealed interface RepaymentResult {
    data object UserNotFound : RepaymentResult
    data object InvalidSalary : RepaymentResult
    data class Insufficient(
        val balance: Double
    ) : RepaymentResult

    data class Created(
        val dto: EmployeeBalanceTransactionDto
    ) : RepaymentResult
}

private sealed interface TnpaCreateResult {
    data object ProjectMissing : TnpaCreateResult
    data object InvalidSubproject : TnpaCreateResult
    data class Created(
        val dto: TnpaDocumentDto
    ) : TnpaCreateResult
}

private class UploadValidationException(
    val status: HttpStatusCode,
    override val message: String
) : RuntimeException(message)