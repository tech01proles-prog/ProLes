package com.example.prolestimesheet.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.prolestimesheet.model.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.core.content.edit

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "proles_app_data")

object LocalDataStore {
    private val PROJECTS_KEY = stringPreferencesKey("projects_json")
    private fun entriesKey(userId: String) = stringPreferencesKey("entries_${userId}_json")
    private fun vacationsKey(userId: String) = stringPreferencesKey("vacations_${userId}_json")
    private val PROFILES_KEY = stringPreferencesKey("employee_profiles_json")
    private val USER_JSON_KEY = stringPreferencesKey("user_session_json")
    private val AUTH_TOKEN_KEY = stringPreferencesKey("auth_token")
    private val TOKEN_EXPIRES_KEY = longPreferencesKey("token_expires_at")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun getSavedUser(context: Context): User? {
        val prefs = context.getSharedPreferences("proles_prefs", Context.MODE_PRIVATE)
        val userJson = prefs.getString("saved_user", null) ?: return null
        return try {
            Json.decodeFromString<User>(userJson)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveSavedUser(context: Context, user: User) {
        val prefs = context.getSharedPreferences("proles_prefs", Context.MODE_PRIVATE)
        prefs.edit { putString("saved_user", Json.encodeToString(user)) }
    }

    suspend fun saveAuthToken(context: Context, token: String, expiresAt: Long) {
        context.dataStore.edit { prefs ->
            prefs[AUTH_TOKEN_KEY] = token
            prefs[TOKEN_EXPIRES_KEY] = expiresAt
        }
    }

    suspend fun getAuthToken(context: Context): String? {
        val prefs = context.dataStore.data.first()
        val token = prefs[AUTH_TOKEN_KEY]
        val expires = prefs[TOKEN_EXPIRES_KEY] ?: 0L
        return if (!token.isNullOrBlank() && System.currentTimeMillis() < expires) token else null
    }

    suspend fun clearAuthToken(context: Context) {
        context.dataStore.edit { prefs ->
            prefs.remove(AUTH_TOKEN_KEY)
            prefs.remove(TOKEN_EXPIRES_KEY)
        }
    }

    // ═══════════════════════════════════════════════════════════
// 🔐 SESSION: сохранение и чтение пользователя для автовхода
// ═══════════════════════════════════════════════════════════

    /**
     * Сохраняет пользователя при логине (используется TimeRepository.login)
     */
    suspend fun saveSession(context: Context, user: User, expiresAt: Long) {
        val prefs = context.getSharedPreferences("proles_prefs", Context.MODE_PRIVATE)
        val userJson = Json.encodeToString(user)
        prefs.edit()
            .putString("session_user", userJson)
            .putLong("session_expires_at", expiresAt)
            .apply()
        android.util.Log.d("LocalDataStore", "💾 Session saved for user: ${user.login}")
    }

    /**
     * Читает сохранённого пользователя при автовходе (используется TimesheetViewModel.tryAutoLogin)
     */
    fun getSession(context: Context): User? {
        val prefs = context.getSharedPreferences("proles_prefs", Context.MODE_PRIVATE)

        // Проверяем срок действия
        val expiresAt = prefs.getLong("session_expires_at", 0L)
        if (expiresAt > 0 && System.currentTimeMillis() > expiresAt) {
            android.util.Log.w("LocalDataStore", "⏰ Session expired at $expiresAt")
            return null
        }

        val userJson = prefs.getString("session_user", null) ?: return null
        return try {
            val user = Json.decodeFromString<User>(userJson)
            android.util.Log.d("LocalDataStore", "📖 Session restored for user: ${user.login}")
            user
        } catch (e: Exception) {
            android.util.Log.e("LocalDataStore", "❌ Failed to parse saved user", e)
            null
        }
    }

    /**
     * Очищает сохранённую сессию при logout
     */
    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences("proles_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("session_user")
            .remove("session_expires_at")
            .apply()
        android.util.Log.d("LocalDataStore", "🗑️ Session cleared")
    }

    suspend fun saveProjects(ctx: Context, projects: List<Project>) {
        ctx.dataStore.edit { it[PROJECTS_KEY] = json.encodeToString(projects) }
    }

    suspend fun getProjects(ctx: Context): List<Project> =
        ctx.dataStore.data.first()[PROJECTS_KEY]?.let { json.decodeFromString(it) } ?: emptyList()

    suspend fun saveEntries(ctx: Context, uid: String, entries: List<TimeEntry>) {
        ctx.dataStore.edit { it[entriesKey(uid)] = json.encodeToString(entries) }
    }

    suspend fun getEntries(ctx: Context, uid: String): List<TimeEntry> =
        ctx.dataStore.data.first()[entriesKey(uid)]?.let { json.decodeFromString(it) } ?: emptyList()

    suspend fun saveVacations(ctx: Context, uid: String, vacations: List<VacationPeriod>) {
        ctx.dataStore.edit { it[vacationsKey(uid)] = json.encodeToString(vacations) }
    }

    suspend fun getVacations(ctx: Context, uid: String): List<VacationPeriod> =
        ctx.dataStore.data.first()[vacationsKey(uid)]?.let { json.decodeFromString(it) } ?: emptyList()

    // 💵 Доходы (Incomes)
    private fun incomesKey(userId: String) = stringPreferencesKey("incomes_${userId}_json")

    suspend fun saveIncomes(ctx: Context, uid: String, incomes: List<Income>) {
        ctx.dataStore.edit { it[incomesKey(uid)] = json.encodeToString(incomes) }
    }

    suspend fun getIncomes(ctx: Context, uid: String): List<Income> =
        ctx.dataStore.data.first()[incomesKey(uid)]?.let { json.decodeFromString(it) } ?: emptyList()

    suspend fun saveProfiles(ctx: Context, profiles: List<User>) {
        ctx.dataStore.edit { it[PROFILES_KEY] = json.encodeToString(profiles) }
    }

    suspend fun getProfiles(ctx: Context): List<User> =
        ctx.dataStore.data.first()[PROFILES_KEY]?.let { json.decodeFromString(it) } ?: emptyList()

    // 🔹 Выходные
    private fun dayOffsKey(userId: String) = stringPreferencesKey("dayoffs_${userId}_json")

    suspend fun saveDayOffs(ctx: Context, uid: String, dayOffs: List<DayOff>) {
        ctx.dataStore.edit { it[dayOffsKey(uid)] = json.encodeToString(dayOffs) }
    }

    suspend fun getDayOffs(ctx: Context, uid: String): List<DayOff> =
        ctx.dataStore.data.first()[dayOffsKey(uid)]?.let { json.decodeFromString(it) } ?: emptyList()


    // ═══════════════════════════════════════════════════════════
    // 🔐 RBAC: кэш effective-прав пользователя
    // ═══════════════════════════════════════════════════════════
    private val USER_PERMISSIONS_KEY = stringPreferencesKey("user_permissions_json")

    suspend fun saveUserPermissions(ctx: Context, permissions: com.example.prolestimesheet.model.UserEffectivePermissions) {
        ctx.dataStore.edit { it[USER_PERMISSIONS_KEY] = json.encodeToString(permissions) }
        android.util.Log.d("LocalDataStore", "💾 User permissions saved: ${permissions.size} entries")
    }

    suspend fun getUserPermissions(ctx: Context): com.example.prolestimesheet.model.UserEffectivePermissions {
        return ctx.dataStore.data.first()[USER_PERMISSIONS_KEY]
            ?.let { json.decodeFromString(it) }
            ?: emptyMap()
    }

    suspend fun clearUserPermissions(ctx: Context) {
        ctx.dataStore.edit { it.remove(USER_PERMISSIONS_KEY) }
        android.util.Log.d("LocalDataStore", "🗑️ User permissions cleared")
    }

    // ═══════════════════════════════════════════════════════════
    // 📧 ACCOUNTANT EMAIL: сохранение последнего использованного
    // ═══════════════════════════════════════════════════════════
    suspend fun saveAccountantEmail(ctx: Context, email: String) {
        ctx.dataStore.edit { it[ACCOUNTANT_EMAIL_KEY] = email }
        android.util.Log.d("LocalDataStore", "💾 Accountant email saved: $email")
    }

    suspend fun getAccountantEmail(ctx: Context): String {
        return ctx.dataStore.data.first()[ACCOUNTANT_EMAIL_KEY] ?: ""
    }

    private val ACCOUNTANT_EMAIL_KEY = stringPreferencesKey("accountant_email")

}