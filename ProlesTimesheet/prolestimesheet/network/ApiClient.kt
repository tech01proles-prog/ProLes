package com.example.prolestimesheet.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.prolestimesheet.data.LocalDataStore
import com.example.prolestimesheet.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.statement.readRawBytes
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class IncomeDto(
    val id: String = "",
    val userId: String = "",
    val projectId: String? = null,
    val projectName: String = "",
    val date: String = "",
    val name: String = "",
    val amount: Double = 0.0,
    val currency: String = "RUB",
    val createdAt: Long = 0L
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class PayrollExportEmployeeDto(
    val userId: String,
    val name: String,
    val position: String,
    val role: String,
    val totalHours: Double,
    val workDays: Int,
    val fixed: Double,
    val piece: Double,
    val hourly: Double,
    val bonus: Double,
    val salaryTotal: Double,
    val expensesTotal: Double,
    val expensesByCurrency: Map<String, Double>,
    val tripsCount: Int
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class PayrollExportResponse(
    val year: Int,
    val month: Int,
    val generatedAt: Long,
    val employees: List<PayrollExportEmployeeDto>
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class SalaryBreakdownResponse(
    val id: String,
    val fixed: Double,
    val piece: Double,
    val hourly: Double,
    val bonus: Double,
    val total: Double
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class CalculateSalaryRequest(
    val userId: String,
    val year: Int,
    val month: Int
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class SalaryComponentDto(
    val id: String = "",
    val userId: String = "",
    val type: String = "",
    val amount: Double = 0.0,
    val projectId: String? = null,
    val ratePerHour: Double? = null,
    val ratePerUnit: Double? = null,
    val description: String = "",
    val effectiveFrom: String = "",
    val effectiveTo: String? = null,
    val isActive: Boolean = true
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ServerAuthResponse(val token: String, val expiresAt: Long, val user: ServerUserDto)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ServerUserDto(
    val id: String,
    val lastName: String = "",
    val firstName: String = "",
    val middleName: String = "",
    val name: String = "",
    val login: String = "",
    val role: String,
    val position: String = "",
    val defaultRateType: String = "HOURLY",
    val defaultRate: Double = 0.0,
    val defaultCurrency: String = "RUB"
)

data class AuthResponse(val token: String, val expiresAt: Long, val user: User)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TicketUploadRequest(
    val projectId: String,
    val description: String,
    val sendToAccountant: Boolean,
    val accountantEmail: String,
    val recipientIds: List<String>,
    val fileBase64: String,
    val fileName: String,
    val fileType: String,
    val amount: Double = 0.0,        // 🆕
    val currency: String = "RUB"     // 🆕
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TicketRecipientDto(
    val userId: String,
    val userName: String
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TicketDto(
    val id: String,
    val projectId: String,
    val projectName: String,
    val fileName: String,
    val fileType: String,
    val fileSize: Long,
    val description: String,
    val uploadedAt: Long,
    val viewedAt: Long? = null,
    val downloadUrl: String,
    val sendToAccountant: Boolean = false,
    val accountantEmail: String = "",
    val amount: Double = 0.0,        // 🆕
    val currency: String = "RUB",    // 🆕
    val recipients: List<TicketRecipientDto> = emptyList()
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class LoginRequest(val email: String, val password: String, val rememberMe: Boolean)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class DayOffRequest(
    val user_id: String,
    val date: String
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class SendNotificationRequest(
    val type: String,
    val title: String,
    val message: String,
    val payload: String = ""
)
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class RolePermissionUpdateDto(
    val permission: String,
    val canView: Boolean,
    val canCreate: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean
)

object ApiClient {
    const val BASE_URL = "http://138.16.177.245:8080/api/v1"
    var authToken: String? = null
        private set
    private var onSessionExpired: (() -> Unit)? = null

    private var sessionExpiredCallback: (() -> Unit)? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    val client = HttpClient(Android) {
        install(ContentNegotiation) { json(json) }
        install(Logging) {
            level = LogLevel.ALL
            logger = Logger.DEFAULT
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
        }

        request {
            authToken?.let { token ->
                header("X-Session-Token", token)
                Log.d("ApiClient", "🔑 Sending token: ${token.take(10)}...")
            }
        }

        defaultRequest {
            url(BASE_URL)
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
        }

        HttpResponseValidator {
            handleResponseExceptionWithRequest { cause, request ->
                validateResponse { response ->
                    if (response.status.value == 401) {
                        onSessionExpired()
                    }
                }
                if (cause is ClientRequestException) {
                    Log.w("ApiClient", "❌ HTTP ${cause.response.status}: ${request.url}")
                    if (cause.response.status == HttpStatusCode.Unauthorized) {
                        Log.w("ApiClient", "⚠️ Session expired! Clearing token.")
                        authToken = null
                        onSessionExpired?.invoke()
                    }
                }

            }
        }
    }

    fun setToken(token: String) {
        authToken = token
        Log.d("ApiClient", "💾 Token set: ${token.take(10)}...")
    }

    fun clearToken() {
        authToken = null
        Log.d("ApiClient", "🗑️ Token cleared")
    }

    fun setSessionExpiredCallback(callback: () -> Unit) {
        sessionExpiredCallback = callback
    }

    // Вызывайте этот метод из interceptors при получении 401
    internal fun onSessionExpired() {
        sessionExpiredCallback?.invoke()
    }

    suspend fun initFromSession(context: Context) {
        val token = LocalDataStore.getAuthToken(context)
        if (!token.isNullOrBlank()) {
            authToken = token
            Log.d("ApiClient", "✅ Token restored: ${token.take(10)}...")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 🔐 AUTH
    // ═══════════════════════════════════════════════════════════

    suspend fun login(context: Context, email: String, password: String, rememberMe: Boolean): Result<AuthResponse> {
        return try {
            Log.d("ApiClient", "🔐 Logging in: $email")
            val response = client.post("$BASE_URL/auth/login") {
                setBody(LoginRequest(email, password, rememberMe))
                authToken?.let { header("X-Session-Token", it) }
            }

            if (response.status == HttpStatusCode.OK) {
                val serverResp = response.body<ServerAuthResponse>()
                authToken = serverResp.token
                Log.d("ApiClient", "✅ Login success, token: ${serverResp.token.take(10)}...")

                LocalDataStore.saveAuthToken(context, serverResp.token, serverResp.expiresAt)

                val user = User(
                    id = serverResp.user.id,
                    lastName = serverResp.user.lastName,
                    firstName = serverResp.user.firstName,
                    middleName = serverResp.user.middleName,
                    name = serverResp.user.name,
                    login = serverResp.user.login,
                    role = serverResp.user.role,
                    position = serverResp.user.position,
                    defaultRateType = runCatching { RateType.valueOf(serverResp.user.defaultRateType) }.getOrDefault(RateType.HOURLY),
                    defaultRate = serverResp.user.defaultRate,
                    defaultCurrency = runCatching { Currency.valueOf(serverResp.user.defaultCurrency) }.getOrDefault(Currency.RUB)
                )

                Result.success(AuthResponse(
                    token = serverResp.token,
                    expiresAt = serverResp.expiresAt,
                    user = user
                ))
            } else {
                val err = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown error"
                Log.e("ApiClient", "❌ Login failed ${response.status}: $err")
                Result.failure(Exception("Server error ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 Login exception", e)
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 📦 PROJECTS
    // ═══════════════════════════════════════════════════════════

    suspend fun fetchProjects(): Result<List<Project>> {
        return try {
            val response = client.get("$BASE_URL/projects") {
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 fetchProjects: ${response.status}")
            if (response.status == HttpStatusCode.OK) {
                Result.success(response.body<List<Project>>())
            } else {
                val err = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Log.e("ApiClient", "❌ Error ${response.status}: $err")
                Result.failure(Exception("Server error ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 Exception", e)
            Result.failure(e)
        }
    }

    suspend fun submitProject(project: Project): Result<Project> {
        return try {
            val response = client.post("$BASE_URL/projects") {
                setBody(project)
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 submitProject: ${response.status}")
            if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created) {
                Result.success(response.body<Project>())
            } else {
                val err = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Result.failure(Exception("Server error ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateProject(project: Project): Result<Project> {
        return try {
            val response = client.put("$BASE_URL/projects") {
                setBody(project)
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 updateProject: ${response.status}")
            if (response.status == HttpStatusCode.OK) {
                Result.success(response.body<Project>())
            } else {
                val err = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Result.failure(Exception("Server error ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 updateProject exception", e)
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 👥 USERS
    // ═══════════════════════════════════════════════════════════

    suspend fun fetchUsers(): Result<List<User>> {
        return try {
            val response = client.get("$BASE_URL/users") {
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 fetchUsers: ${response.status}")
            if (response.status == HttpStatusCode.OK) {
                Result.success(response.body<List<User>>())
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 fetchUsers exception", e)
            Result.failure(e)
        }
    }

    suspend fun createUser(user: User): Result<User> {
        return try {
            val response = client.post("$BASE_URL/users") {
                setBody(user)
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status == HttpStatusCode.Created || response.status == HttpStatusCode.OK) {
                Result.success(response.body<User>())
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateUser(user: User): Result<User> {
        return try {
            val response = client.put("$BASE_URL/users") {
                setBody(user)
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status == HttpStatusCode.OK) {
                Result.success(response.body<User>())
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 🕐 TIME ENTRIES
    // ═══════════════════════════════════════════════════════════

    suspend fun fetchEntries(userId: String): Result<List<TimeEntry>> {
        return try {
            val response = client.get("$BASE_URL/entries?userId=$userId") {
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 fetchEntries: ${response.status} for $userId")
            if (response.status == HttpStatusCode.OK) {
                Result.success(response.body<List<TimeEntry>>())
            } else {
                val err = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Result.failure(Exception("Server error ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 🆕 Все записи всех сотрудников (для админа)
    suspend fun fetchAllEntries(): Result<List<TimeEntry>> {
        return try {
            val response = client.get("$BASE_URL/entries/all") {
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 fetchAllEntries: ${response.status}")
            if (response.status == HttpStatusCode.OK) {
                Result.success(response.body<List<TimeEntry>>())
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 fetchAllEntries exception", e)
            Result.failure(e)
        }
    }

    suspend fun updateEntry(entry: TimeEntry): Result<TimeEntry> {
        return try {
            val response = client.put("$BASE_URL/entries") {
                setBody(entry)
                contentType(ContentType.Application.Json)
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 updateEntry: ${response.status}, $entry")
            if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created) {
                Result.success(response.body<TimeEntry>())
            } else {
                val err = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Log.e("ApiClient", "❌ updateEntry error ${response.status}: $err")
                Result.failure(Exception("Server error ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 updateEntry exception", e)
            Result.failure(e)
        }
    }

    suspend fun deleteProject(projectId: String): Result<Unit> {
        return try {
            val response = client.delete("$BASE_URL/projects?projectId=$projectId") {
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status.value == 204) Result.success(Unit)
            else Result.failure(Exception("Delete failed: ${response.status}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteEntry(entryId: String): Result<Unit> {
        return try {
            val response = client.delete("$BASE_URL/entries?entryId=$entryId") {
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 deleteEntry: ${response.status}")
            if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NoContent) {
                Result.success(Unit)
            } else {
                val err = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Result.failure(Exception("Server error ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 deleteEntry exception", e)
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 🏖 VACATIONS
    // ═══════════════════════════════════════════════════════════

    suspend fun fetchVacations(userId: String): Result<List<VacationPeriod>> {
        return try {
            val response = client.get("$BASE_URL/vacations?userId=$userId") {
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 fetchVacations: ${response.status} for $userId")
            if (response.status == HttpStatusCode.OK) {
                Result.success(response.body<List<VacationPeriod>>())
            } else {
                val err = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Result.failure(Exception("Server error ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitVacation(vacation: VacationPeriod): Result<VacationPeriod> {
        return try {
            val response = client.post("$BASE_URL/vacations") {
                setBody(vacation)
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 submitVacation: ${response.status}")
            if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created) {
                Result.success(response.body<VacationPeriod>())
            } else {
                val err = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Result.failure(Exception("Server error ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteAllVacations(userId: String): Result<Unit> {
        return try {
            val response = client.delete("$BASE_URL/vacations?userId=$userId") {
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NoContent) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 🌞 DAY OFFS
    // ═══════════════════════════════════════════════════════════

    suspend fun fetchDayOffs(userId: String): Result<List<DayOff>> {
        return try {
            val response = client.get("$BASE_URL/dayoffs?userId=$userId") {
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status == HttpStatusCode.OK) {
                val requests = response.body<List<DayOffRequest>>()
                val dayOffs = requests.map { DayOff(userId = it.user_id, date = LocalDate.parse(it.date)) }
                Result.success(dayOffs)
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createDayOff(dayOff: DayOff): Result<Unit> {
        return try {
            val request = DayOffRequest(
                user_id = dayOff.userId,
                date = dayOff.date.toString()
            )
            val response = client.post("$BASE_URL/dayoffs") {
                setBody(request)
                contentType(ContentType.Application.Json)
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 createDayOff: ${response.status}")
            if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created) {
                Result.success(Unit)
            } else {
                val err = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Result.failure(Exception("Server error ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 createDayOff exception", e)
            Result.failure(e)
        }
    }

    suspend fun deleteDayOff(userId: String, date: LocalDate): Result<Unit> {
        return try {
            val response = client.delete("$BASE_URL/dayoffs?userId=$userId&date=$date") {
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 deleteDayOff: ${response.status}")
            if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NoContent) {
                Result.success(Unit)
            } else {
                val err = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Result.failure(Exception("Server error ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 deleteDayOff exception", e)
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 💰 EXPENSES
    // ═══════════════════════════════════════════════════════════

    suspend fun fetchExpenses(userId: String): Result<List<Expense>> {
        return try {
            val response = client.get("$BASE_URL/expenses?userId=$userId") {
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status == HttpStatusCode.OK) Result.success(response.body<List<Expense>>())
            else Result.failure(Exception("Server error ${response.status}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 🆕 Все расходы всех сотрудников (для админа)
    suspend fun fetchAllExpenses(): Result<List<Expense>> {
        return try {
            val response = client.get("$BASE_URL/expenses/all") {
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 fetchAllExpenses: ${response.status}")
            if (response.status == HttpStatusCode.OK) {
                Result.success(response.body<List<Expense>>())
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 fetchAllExpenses exception", e)
            Result.failure(e)
        }
    }

    suspend fun createExpense(expense: Expense): Result<Expense> {
        return try {
            val response = client.post("$BASE_URL/expenses") {
                setBody(expense)
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status == HttpStatusCode.Created) Result.success(response.body<Expense>())
            else Result.failure(Exception("Server error ${response.status}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateExpense(expense: Expense): Result<Expense> {
        return try {
            val response = client.put("$BASE_URL/expenses") {
                setBody(expense)
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status == HttpStatusCode.OK) Result.success(response.body<Expense>())
            else Result.failure(Exception("Server error ${response.status}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteExpense(expenseId: String): Result<Unit> {
        return try {
            val response = client.delete("$BASE_URL/expenses?expenseId=$expenseId") {
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status == HttpStatusCode.NoContent || response.status == HttpStatusCode.OK) Result.success(Unit)
            else Result.failure(Exception("Server error ${response.status}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 🆕 НОВЫЙ ПОДХОД: Base64 вместо multipart
    suspend fun uploadReceiptPhoto(expenseId: String, imageBytes: ByteArray): Result<String> {
        return try {
            // Конвертируем ByteArray в Base64 строку
            val base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes)

            val requestBody = mapOf(
                "expenseId" to expenseId,
                "imageBase64" to base64Image
            )

            Log.d("ApiClient", "📡 uploadReceiptPhoto: размер ${imageBytes.size / 1024} КБ, Base64: ${base64Image.length / 1024} КБ")

            val response = client.post("$BASE_URL/expenses/upload-receipt") {
                setBody(requestBody)
                authToken?.let { header("X-Session-Token", it) }
            }

            Log.d("ApiClient", "📡 uploadReceiptPhoto response: ${response.status}")

            if (response.status == HttpStatusCode.Created) {
                val body = response.body<Map<String, String>>()
                Result.success(body["url"] ?: "")
            } else {
                val error = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown error"
                Log.e("ApiClient", "❌ Upload failed: $error")
                Result.failure(Exception("Upload failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 uploadReceiptPhoto exception", e)
            Result.failure(e)
        }
    }


    // ═══════════════════════════════════════════════════════════
// 💵 INCOMES
// ═══════════════════════════════════════════════════════════
    suspend fun fetchIncomes(userId: String): Result<List<Income>> {
        return try {
            val response = client.get("$BASE_URL/incomes?userId=$userId") {
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status == HttpStatusCode.OK) {
                val dtos = response.body<List<IncomeDto>>()
                val incomes = dtos.map { dto ->
                    Income(
                        id = dto.id,
                        userId = dto.userId,
                        projectId = dto.projectId,
                        projectName = dto.projectName,
                        date = LocalDate.parse(dto.date),
                        name = dto.name,
                        amount = dto.amount,
                        currency = dto.currency,
                        createdAt = dto.createdAt
                    )
                }
                Result.success(incomes)
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 fetchIncomes exception", e)
            Result.failure(e)
        }
    }

    suspend fun fetchAllIncomes(): Result<List<Income>> {
        return try {
            val response = client.get("$BASE_URL/incomes/all") {
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status == HttpStatusCode.OK) {
                val dtos = response.body<List<IncomeDto>>()
                val incomes = dtos.map { dto ->
                    Income(
                        id = dto.id,
                        userId = dto.userId,
                        projectId = dto.projectId,
                        projectName = dto.projectName,
                        date = LocalDate.parse(dto.date),
                        name = dto.name,
                        amount = dto.amount,
                        currency = dto.currency,
                        createdAt = dto.createdAt
                    )
                }
                Result.success(incomes)
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 fetchAllIncomes exception", e)
            Result.failure(e)
        }
    }

    suspend fun createIncome(income: Income): Result<Income> {
        return try {
            val dto = IncomeDto(
                id = income.id,
                userId = income.userId,
                projectId = income.projectId,
                projectName = income.projectName,
                date = income.date.toString(),
                name = income.name,
                amount = income.amount,
                currency = income.currency,
                createdAt = income.createdAt
            )
            val response = client.post("$BASE_URL/incomes") {
                setBody(dto)
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status == HttpStatusCode.Created) {
                val result = response.body<IncomeDto>()
                Result.success(income.copy(id = result.id, createdAt = result.createdAt))
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteIncome(incomeId: String): Result<Unit> {
        return try {
            val response = client.delete("$BASE_URL/incomes?incomeId=$incomeId") {
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status == HttpStatusCode.NoContent || response.status == HttpStatusCode.OK) Result.success(Unit)
            else Result.failure(Exception("Server error ${response.status}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 🗑 Удаление билета
    suspend fun deleteTicket(ticketId: String): Result<Unit> {
        return try {
            val response = client.delete("$BASE_URL/tickets/$ticketId") {
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 deleteTicket: ${response.status}")
            if (response.status == HttpStatusCode.NoContent || response.status == HttpStatusCode.OK) {
                Result.success(Unit)
            } else {
                val err = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Result.failure(Exception("Server error ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 deleteTicket exception", e)
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 🚆 BUSINESS TRIPS
    // ═══════════════════════════════════════════════════════════

    // 🆕 Получить командировки пользователя (или все для админа)
    suspend fun fetchBusinessTrips(userId: String, all: Boolean = false): Result<List<BusinessTrip>> {
        return try {
            val url = if (all) "$BASE_URL/business-trips?all=true"
            else "$BASE_URL/business-trips?userId=$userId"
            val response = client.get(url) {
                authToken?.let { header("X-Session-Token", it) }
            }
            if (all)  Log.d("ApiClient", "📡 fetchAllBusinessTrips: ${response.status}")
            else Log.d("ApiClient", "📡 fetchBusinessTrips: ${response.status}")
            if (response.status == HttpStatusCode.OK) {
                Result.success(response.body<List<BusinessTrip>>())
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 fetchBusinessTrips exception", e)
            Result.failure(e)
        }
    }

    // 🆕 Создать командировку
    suspend fun createBusinessTrip(trip: BusinessTrip): Result<BusinessTrip> {
        return try {
            val response = client.post("$BASE_URL/business-trips") {
                setBody(trip)
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 createBusinessTrip: ${response.status}")
            if (response.status == HttpStatusCode.Created) {
                Result.success(response.body<BusinessTrip>())
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 createBusinessTrip exception", e)
            Result.failure(e)
        }
    }

    // 🆕 Обновить командировку
    suspend fun updateBusinessTrip(trip: BusinessTrip): Result<BusinessTrip> {
        return try {
            val response = client.put("$BASE_URL/business-trips") {
                setBody(trip)
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 updateBusinessTrip: ${response.status}")
            if (response.status == HttpStatusCode.OK) {
                Result.success(response.body<BusinessTrip>())
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 updateBusinessTrip exception", e)
            Result.failure(e)
        }
    }

    // 🆕 Удалить командировку
    suspend fun deleteBusinessTrip(tripId: String): Result<Unit> {
        return try {
            val response = client.delete("$BASE_URL/business-trips?tripId=$tripId") {
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 deleteBusinessTrip: ${response.status}")
            if (response.status == HttpStatusCode.NoContent || response.status == HttpStatusCode.OK) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 deleteBusinessTrip exception", e)
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 🔔 NOTIFICATIONS
    // ═══════════════════════════════════════════════════════════

    suspend fun markAllNotificationsRead(): Result<Unit> {
        return try {
            val response = client.post("$BASE_URL/notifications/mark-all-read") {
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status == HttpStatusCode.OK) Result.success(Unit)
            else Result.failure(Exception("Server error"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // 🆕 Получить уведомления текущего пользователя
    suspend fun fetchNotifications(): Result<List<Notification>> {
        return try {
            val response = client.get("$BASE_URL/notifications") {
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 fetchNotifications: ${response.status}")
            if (response.status == HttpStatusCode.OK) {
                Result.success(response.body<List<Notification>>())
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 fetchNotifications exception", e)
            Result.failure(e)
        }
    }

    // 🆕 Отправить уведомление (создать событие)
    suspend fun sendNotification(type: String, title: String, message: String, payload: String = ""): Result<Unit> {
        return try {
            val response = client.post("$BASE_URL/notifications") {
                setBody(SendNotificationRequest(type, title, message, payload))
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 sendNotification: ${response.status}")
            if (response.status == HttpStatusCode.Created) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 sendNotification exception", e)
            Result.failure(e)
        }
    }

    // 🆕 Отметить уведомление как прочитанное
    suspend fun markNotificationRead(id: String): Result<Unit> {
        return try {
            val response = client.post("$BASE_URL/notifications/mark-read") {
                setBody(mapOf("id" to id))
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 markNotificationRead: ${response.status}")
            if (response.status == HttpStatusCode.OK) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 markNotificationRead exception", e)
            Result.failure(e)
        }
    }


    suspend fun registerFcmToken(userId: String, token: String): Result<Unit> {
        return try {
            val requestBody = mapOf("token" to token)

            Log.d("ApiClient", "📡 registerFcmToken: отправка токена длиной ${token.length} символов")
            Log.d("ApiClient", "📡 URL: $BASE_URL/fcm/register")

            val response = client.post("$BASE_URL/fcm/register") {
                contentType(io.ktor.http.ContentType.Application.Json) // 🔥 ВАЖНО: явно указываем Content-Type
                setBody(requestBody)
                authToken?.let { header("X-Session-Token", it) }
            }

            Log.d("ApiClient", "📡 registerFcmToken response: ${response.status}")

            if (response.status == io.ktor.http.HttpStatusCode.OK) {
                Result.success(Unit)
            } else {
                val errorBody = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown error"
                android.util.Log.e("ApiClient", "❌ Ошибка от сервера: ${response.status} - $errorBody")
                Result.failure(Exception("Server error: ${response.status} - $errorBody"))
            }
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "💥 Exception в registerFcmToken", e)
            Result.failure(e)
        }
    }

    // 🔐 RBAC
    suspend fun fetchRoles(): Result<List<Role>> {
        return try {
            val response = client.get("$BASE_URL/rbac/roles") {
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status.value == 200) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateRolePermissions(roleId: String, permissions: List<RolePermissionUpdateDto>): Result<Unit> {
        return try {
            Log.d("ApiClient", "📤 updateRolePermissions: roleId=$roleId, count=${permissions.size}")
            val response = client.put("$BASE_URL/rbac/roles/$roleId") {
                contentType(ContentType.Application.Json)
                authToken?.let { header("X-Session-Token", it) }
                setBody(permissions)
            }
            Log.d("ApiClient", "📊 updateRolePermissions response: ${response.status}")
            if (response.status.value == 200) Result.success(Unit)
            else {
                val error = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Log.e("ApiClient", "❌ Update failed: ${response.status} - $error")
                Result.failure(Exception("Update failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 updateRolePermissions exception", e)
            Result.failure(e)
        }
    }

    suspend fun fetchUserPermissions(userId: String): Result<List<UserEffectivePermissionDto>> {
        return try {
            val response = client.get("$BASE_URL/rbac/users/$userId/permissions") {
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status.value == 200) Result.success(response.body())
            else Result.failure(Exception("Failed: ${response.status}"))
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 fetchUserPermissions exception", e)
            Result.failure(e)
        }
    }

    suspend fun updateUserPermissions(
        userId: String,
        overrides: List<UserPermissionOverrideDto>
    ): Result<Unit> {
        return try {
            Log.d("ApiClient", "📤 updateUserPermissions: userId=$userId, count=${overrides.size}")
            val response = client.put("$BASE_URL/rbac/users/$userId/permissions") {
                contentType(ContentType.Application.Json)
                authToken?.let { header("X-Session-Token", it) }
                setBody(overrides)
            }
            Log.d("ApiClient", "📊 updateUserPermissions response: ${response.status}")
            if (response.status.value == 200) Result.success(Unit)
            else Result.failure(Exception("Update failed: ${response.status}"))
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 updateUserPermissions exception", e)
            Result.failure(e)
        }
    }

    suspend fun fetchPermissions(): Result<List<PermissionInfo>> {
        return try {
            val response = client.get("$BASE_URL/rbac/permissions") {
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status.value == 200) Result.success(response.body())
            else Result.failure(Exception("Failed: ${response.status}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchMyTickets(): Result<List<TicketDto>> {
        return try {
            val response = client.get("$BASE_URL/tickets/my") {
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status.value == 200) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Failed: ${response.status}"))
            }
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "❌ fetchMyTickets failed", e)
            Result.failure(e)
        }
    }

    suspend fun downloadFile(context: Context, url: String, fileName: String) {
        try {
            // 🔥 ВАЖНО: убираем /api/v1 из URL для статических файлов
            // Сервер раздаёт файлы через staticFiles("/uploads"), а не через API
            val serverBase = BASE_URL.substringBefore("/api/v1")  // http://138.16.177.245:8080
            val fullUrl = "$serverBase$url"

            android.util.Log.d("ApiClient", "📥 Downloading: $fullUrl")

            val response = client.get(fullUrl) {
                // ⚠️ НЕ передаём токен для статических файлов — они публичные
                // authToken?.let { header("X-Session-Token", it) }
            }

            android.util.Log.d("ApiClient", "📊 Response status: ${response.status}")

            // Проверяем успешность ответа
            if (response.status.value !in 200..299) {
                android.util.Log.e("ApiClient", "❌ Download failed with status: ${response.status}")
                showErrorToast(context, "Файл не найден на сервере (ошибка ${response.status.value})")
                return
            }

            // 🔥 Ktor 3.0: используем readBytes() вместо body<ByteArray>()
            val bytes = response.readRawBytes()
            val mimeType = response.contentType()?.toString() ?: guessMimeType(fileName)

            android.util.Log.d("ApiClient", "✅ Downloaded ${bytes.size} bytes, mime=$mimeType")

            Log.d("ApiClient", "✅ Downloaded ${bytes.size} bytes, mime=$mimeType")

            // 2. Сохраняем через MediaStore (Android 10+) или прямой доступ (старые)
            val savedUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, fileName, mimeType, bytes)
            } else {
                saveViaDirectAccess(context, fileName, bytes)
            }

            if (savedUri != null) {
                android.util.Log.d("ApiClient", "✅ Saved to: $savedUri")

                // Показываем уведомление с возможностью открыть
                showDownloadNotification(context, fileName, savedUri)
            } else {
                showErrorToast(context, "Не удалось сохранить файл")
            }
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "❌ Download failed", e)
            showErrorToast(context, "Ошибка скачивания: ${e.message?.take(50)}")
        }
    }

    /**
     * Сохранение через MediaStore (Android 10+, API 29+)
     * Не требует разрешений WRITE_EXTERNAL_STORAGE
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): android.net.Uri? {
        val resolver = context.contentResolver
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Proles")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                outputStream.write(bytes)
            }
            contentValues.clear()
            contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(it, contentValues, null, null)
        }
        return uri
    }

    /**
     * Fallback для Android 9 и ниже (прямой доступ)
     */
    private fun saveViaDirectAccess(
        context: Context,
        fileName: String,
        bytes: ByteArray
    ): android.net.Uri? {
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val prolesDir = java.io.File(downloadsDir, "Proles").apply { mkdirs() }
        val file = java.io.File(prolesDir, fileName)
        file.writeBytes(bytes)
        return android.net.Uri.fromFile(file)
    }

    /**
     * Показывает уведомление о завершении скачивания
     */
    private fun showDownloadNotification(context: Context, fileName: String, uri: android.net.Uri) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            // Toast с возможностью открыть
            android.widget.Toast.makeText(
                context,
                "✅ Файл сохранён: $fileName",
                android.widget.Toast.LENGTH_LONG
            ).show()

            // Пробуем открыть файл автоматически
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, guessMimeType(fileName))
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                }
            } catch (e: Exception) {
                android.util.Log.w("ApiClient", "Не удалось открыть файл автоматически: ${e.message}")
            }
        }
    }

    /**
     * Определяет MIME-тип по расширению файла
     */
    private fun guessMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> "application/octet-stream"
        }
    }

    private fun showErrorToast(context: Context, message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                context,
                "❌ $message",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * ⚠️ DEPRECATED: больше не используется при автовходе.
     *
     * Валидация токена теперь происходит автоматически через interceptor:
     * - При любом запросе, возвращающем 401 → срабатывает onSessionExpired
     * - MainActivity слушает SessionEvents и делает forceLogout
     *
     * При автовходе достаточно наличия локального токена + пользователя.
     * Серверные сессии теперь персистентны (хранятся в БД, переживают перезапуск).
     */
    @Deprecated(
        "Больше не используется при автовходе. Валидация через 401 interceptor.",
        level = DeprecationLevel.WARNING
    )
    suspend fun checkTokenValidity(): Boolean {
        return try {
            val response = client.get("$BASE_URL/auth/me") {
                authToken?.let { header("X-Session-Token", it) }
            }
            response.status.value == 200
        } catch (e: Exception) {
            // 🔥 КРИТИЧНО: при сетевых ошибках НЕ считаем токен невалидным!
            // Иначе пользователь будет разлогинен при отсутствии интернета.
            android.util.Log.w("ApiClient", "⚠️ Token check skipped (offline?): ${e.message}")
            true  // ← Предполагаем валидность
        }
    }

    /**
     * 🆕 Быстрая проверка локальной сессии (без сети).
     * Используется для определения возможности автовхода.
     */
    suspend fun hasLocalSession(context: Context): Boolean {
        val token = LocalDataStore.getAuthToken(context)
        val user = LocalDataStore.getSession(context)
        return !token.isNullOrBlank() && user != null
    }


    // 🎫 БИЛЕТЫ
    suspend fun fetchAllTickets(): Result<List<TicketDto>> {
        return try {
            val response = client.get("$BASE_URL/tickets/all") {
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status.value == 200) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadTicket(
        context: android.content.Context,
        projectId: String,
        description: String,
        amount: Double,          // 🆕
        currency: String,        // 🆕
        sendToAccountant: Boolean,
        accountantEmail: String,
        recipientIds: List<String>,
        fileUri: android.net.Uri
    ): Boolean {
        return try {
            val contentResolver = context.contentResolver

            // Читаем файл в ByteArray
            val inputStream = contentResolver.openInputStream(fileUri) ?: return false
            val fileBytes = inputStream.readBytes()
            inputStream.close()

            // Получаем имя файла
            var fileName = "ticket"
            contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) fileName = cursor.getString(nameIndex)
                }
            }

            // Получаем MIME-тип
            val mimeType = contentResolver.getType(fileUri) ?: "application/octet-stream"

            // Кодируем в Base64
            val fileBase64 = java.util.Base64.getEncoder().encodeToString(fileBytes)

            // Формируем JSON
            val requestBody = TicketUploadRequest(
                projectId = projectId,
                description = description,
                sendToAccountant = sendToAccountant,
                accountantEmail = accountantEmail,
                recipientIds = recipientIds,
                fileBase64 = fileBase64,
                fileName = fileName,
                fileType = mimeType,
                amount = amount,        // 🆕
                currency = currency     // 🆕
            )

            android.util.Log.d("ApiClient", "📤 Sending ticket: ${fileBytes.size / 1024} KB as Base64")

            val response = client.post("$BASE_URL/tickets/upload") {
                contentType(io.ktor.http.ContentType.Application.Json)
                authToken?.let { header("X-Session-Token", it) }
                setBody(requestBody)
            }

            android.util.Log.d("ApiClient", "✅ Upload response: ${response.status}")
            response.status.value in 200..299
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "❌ Upload ticket failed", e)
            false
        }
    }

    // 🆕 Загрузка effective-прав текущего пользователя
    suspend fun fetchMyPermissions(): Result<com.example.prolestimesheet.model.UserEffectivePermissions> {
        return try {
            val response = client.get("$BASE_URL/rbac/my-permissions") {
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 fetchMyPermissions: ${response.status}")
            if (response.status.value == 200) {
                Result.success(response.body())
            } else {
                val error = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Log.e("ApiClient", "❌ fetchMyPermissions failed: $error")
                Result.failure(Exception("Server error ${response.status}: $error"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 fetchMyPermissions exception", e)
            Result.failure(e)
        }
    }

    // 🗑 Удаление сотрудника
    suspend fun deleteUser(userId: String): Result<Unit> {
        return try {
            val response = client.delete("$BASE_URL/users?userId=$userId") {
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 deleteUser: ${response.status}")
            if (response.status.value == 204 || response.status == HttpStatusCode.OK) {
                Result.success(Unit)
            } else {
                val error = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Log.e("ApiClient", "❌ deleteUser failed: $error")
                Result.failure(Exception("Server error ${response.status}: $error"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 deleteUser exception", e)
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 💰 PAYROLL: Расчёт зарплаты через сервер
    // ═══════════════════════════════════════════════════════════
    suspend fun fetchSalaryRecords(): Result<List<Map<String, Any>>> {
        return try {
            val response = client.get("$BASE_URL/payroll/records") {
                authToken?.let { header("X-Session-Token", it) }
            }
            if (response.status == HttpStatusCode.OK) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 💰 SALARY COMPONENTS
    // ═══════════════════════════════════════════════════════════

    suspend fun fetchSalaryComponents(userId: String): Result<List<SalaryComponentDto>> {
        return try {
            val response = client.get("$BASE_URL/payroll/components/$userId") {
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 fetchSalaryComponents: ${response.status}")
            if (response.status == HttpStatusCode.OK) {
                val list: List<SalaryComponentDto> = response.body()  // ✅ Явный тип
                Result.success(list)
            } else {
                Result.failure(Exception("Server error ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 fetchSalaryComponents exception", e)
            Result.failure(e)
        }
    }

    suspend fun createSalaryComponent(component: SalaryComponentDto): Result<Map<String, String>> {
        return try {
            Log.d("ApiClient", "📡 createSalaryComponent: type=${component.type}, userId=${component.userId}, amount=${component.amount}")
            if (component.userId.isBlank()) {
                Log.e("ApiClient", "❌ userId is blank!")
                return Result.failure(Exception("userId cannot be blank"))
            }
            val response = client.post("$BASE_URL/payroll/components") {
                contentType(ContentType.Application.Json)
                authToken?.let { header("X-Session-Token", it) }
                setBody(component)  // ✅ DTO, НЕ Map!
            }
            Log.d("ApiClient", "📡 createSalaryComponent response: ${response.status}")
            if (response.status == HttpStatusCode.Created || response.status == HttpStatusCode.OK) {
                Result.success(response.body())
            } else {
                val err = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Log.e("ApiClient", "❌ createSalaryComponent failed: $err")
                Result.failure(Exception("Server error ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 createSalaryComponent exception", e)
            Result.failure(e)
        }
    }

    suspend fun deleteSalaryComponent(componentId: String): Result<Unit> {
        return try {
            val response = client.delete("$BASE_URL/payroll/components/$componentId") {
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 deleteSalaryComponent: ${response.status}")
            if (response.status == HttpStatusCode.NoContent || response.status == HttpStatusCode.OK) {
                Result.success(Unit)
            } else {
                val err = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Log.e("ApiClient", "❌ deleteSalaryComponent failed: $err")
                Result.failure(Exception("Server error ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 deleteSalaryComponent exception", e)
            Result.failure(e)
        }
    }

    suspend fun calculateSalary(userId: String, year: Int, month: Int): Result<SalaryBreakdownResponse> {
        return try {
            val response = client.post("$BASE_URL/payroll/calculate") {
                contentType(ContentType.Application.Json)
                authToken?.let { header("X-Session-Token", it) }
                setBody(CalculateSalaryRequest(userId, year, month))
            }
            Log.d("ApiClient", "📡 calculateSalary response: ${response.status}")
            if (response.status == HttpStatusCode.Created || response.status == HttpStatusCode.OK) {
                val breakdown: SalaryBreakdownResponse = response.body()
                Log.d("ApiClient", "✅ Salary breakdown: fixed=${breakdown.fixed}, piece=${breakdown.piece}, hourly=${breakdown.hourly}, bonus=${breakdown.bonus}, total=${breakdown.total}")
                Result.success(breakdown)
            } else {
                val err = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Log.e("ApiClient", "❌ calculateSalary failed: $err")
                Result.failure(Exception("Server error ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 calculateSalary exception", e)
            Result.failure(e)
        }
    }

    suspend fun fetchPayrollExport(year: Int, month: Int): Result<PayrollExportResponse> {
        return try {
            val response = client.get("$BASE_URL/payroll/export?year=$year&month=$month") {
                authToken?.let { header("X-Session-Token", it) }
            }
            Log.d("ApiClient", "📡 fetchPayrollExport: ${response.status}")
            if (response.status == HttpStatusCode.OK) {
                val result: PayrollExportResponse = response.body()
                Log.d("ApiClient", "✅ Payroll export: ${result.employees.size} employees")
                Result.success(result)
            } else {
                val err = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown"
                Log.e("ApiClient", "❌ fetchPayrollExport failed: $err")
                Result.failure(Exception("Server error ${response.status}: $err"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "💥 fetchPayrollExport exception", e)
            Result.failure(e)
        }
    }
}
