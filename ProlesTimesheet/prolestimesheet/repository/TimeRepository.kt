package com.example.prolestimesheet.repository

import android.content.Context
import android.util.Log
import com.example.prolestimesheet.data.LocalDataStore
import com.example.prolestimesheet.model.*
import com.example.prolestimesheet.network.ApiClient
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import com.example.prolestimesheet.data.sync.SyncRepository
import com.example.prolestimesheet.data.sync.SyncQueue
import com.example.prolestimesheet.network.SalaryBreakdownResponse
import com.example.prolestimesheet.network.SalaryComponentDto
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class TimeRepository(val context: Context, private val apiClient: ApiClient = ApiClient) {
    private val _salaryComponents = MutableStateFlow<List<SalaryComponentDto>>(emptyList())
    val salaryComponentsFlow: StateFlow<List<SalaryComponentDto>> = _salaryComponents.asStateFlow()

    private val _salaryBreakdown = MutableStateFlow<SalaryBreakdownResponse?>(null)
    val salaryBreakdownFlow: StateFlow<SalaryBreakdownResponse?> = _salaryBreakdown.asStateFlow()

    private val syncRepository = SyncRepository(context)
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    private val _entries = MutableStateFlow<List<TimeEntry>>(emptyList())
    private val _user = MutableStateFlow<User?>(null)
    private val _vacations = MutableStateFlow<List<VacationPeriod>>(emptyList())
    private val _profiles = MutableStateFlow<List<User>>(emptyList())

    val projectsFlow: StateFlow<List<Project>> = _projects.asStateFlow()
    val entriesFlow: StateFlow<List<TimeEntry>> = _entries.asStateFlow()
    val userFlow: StateFlow<User?> = _user.asStateFlow()
    val vacationsFlow: StateFlow<List<VacationPeriod>> = _vacations.asStateFlow()
    val profilesFlow: StateFlow<List<User>> = _profiles.asStateFlow()

    private val _employees = MutableStateFlow<List<User>>(emptyList())
    val employeesFlow: StateFlow<List<User>> = _employees.asStateFlow()

    private val _dayOffsFlow = MutableStateFlow<List<DayOff>>(emptyList())
    val dayOffsFlow: StateFlow<List<DayOff>> = _dayOffsFlow

    private val _expenses = MutableStateFlow<List<Expense>>(emptyList())
    val expensesFlow: StateFlow<List<Expense>> = _expenses.asStateFlow()

    private val _incomes = MutableStateFlow<List<Income>>(emptyList())
    val incomesFlow: StateFlow<List<Income>> = _incomes.asStateFlow()
    
    // 🔥 Делаем _incomes public для доступа из ViewModel
    val incomes: MutableStateFlow<List<Income>> = _incomes

    private val _trips = MutableStateFlow<List<BusinessTrip>>(emptyList())
    val tripsFlow: StateFlow<List<BusinessTrip>> = _trips.asStateFlow()

    private val _notifications = MutableStateFlow<List<Notification>>(emptyList())
    val notificationsFlow: StateFlow<List<Notification>> = _notifications.asStateFlow()

    private val _roles = MutableStateFlow<List<Role>>(emptyList())
    val rolesFlow: StateFlow<List<Role>> = _roles.asStateFlow()

    private val _userPermissions = MutableStateFlow<com.example.prolestimesheet.model.UserEffectivePermissions>(emptyMap())
    val userPermissionsFlow: StateFlow<com.example.prolestimesheet.model.UserEffectivePermissions> = _userPermissions.asStateFlow()

    val pendingSyncCount: StateFlow<Int> = SyncRepository(context).observePendingCount()
        .stateIn( kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main), SharingStarted.Lazily, 0)

    init {
        kotlinx.coroutines.runBlocking {
            _projects.value = LocalDataStore.getProjects(context)
            _profiles.value = LocalDataStore.getProfiles(context)
            _employees.value = LocalDataStore.getProfiles(context) // Переиспользуем getProfiles
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 💵 INCOMES
    // ═══════════════════════════════════════════════════════════
    suspend fun loadIncomes(userId: String) {
        // Сначала показываем кэш
        _incomes.value = LocalDataStore.getIncomes(context, userId)
        // Затем тянем актуальные с сервера — используем fetchIncomes для текущего пользователя
        val remote = apiClient.fetchIncomes(userId).getOrDefault(emptyList())
        _incomes.value = remote
        LocalDataStore.saveIncomes(context, userId, remote)
    }

    suspend fun loadAllIncomes() {
        val remote = apiClient.fetchAllIncomes().getOrDefault(emptyList())
        if (remote.isNotEmpty()) {
            _incomes.value = remote
            // Сохраняем в кэш для текущего пользователя
            _user.value?.let { user ->
                LocalDataStore.saveIncomes(context, user.id, remote.filter { it.userId == user.id })
            }
        }
    }

    suspend fun addIncome(income: Income) {
        apiClient.createIncome(income).onSuccess { created ->
            _incomes.value = listOf(created) + _incomes.value
            // Сохраняем в кэш
            LocalDataStore.saveIncomes(context, income.userId, _incomes.value.filter { it.userId == income.userId })
        }
    }

    suspend fun removeIncome(incomeId: String) {
        val income = _incomes.value.firstOrNull { it.id == incomeId }
        apiClient.deleteIncome(incomeId).onSuccess {
            _incomes.value = _incomes.value.filter { it.id != incomeId }
            // Обновляем кэш
            income?.let { inc ->
                LocalDataStore.saveIncomes(context, inc.userId, _incomes.value.filter { it.userId == inc.userId })
            }
        }
    }

    // 🗑 Удаление билета
    suspend fun deleteTicket(ticketId: String) {
        apiClient.deleteTicket(ticketId)
    }


    /**
     * 🆕 Загружает effective-права ТЕКУЩЕГО пользователя с сервера.
     * Вызывается при login() и restoreSession().
     */
    suspend fun loadUserPermissions() {
        // Сначала показываем кэш (быстрый старт)
        _userPermissions.value = LocalDataStore.getUserPermissions(context)
        // Затем тянем свежие с сервера
        apiClient.fetchMyPermissions()
            .onSuccess { permissions ->
                _userPermissions.value = permissions
                LocalDataStore.saveUserPermissions(context, permissions)
                android.util.Log.d("Repository", "✅ User permissions loaded: ${permissions.size} entries")
            }
            .onFailure { e ->
                android.util.Log.w("Repository", "⚠️ Failed to load permissions, using cache: ${e.message}")
            }
    }
    /**
     * 🆕 Загружает effective-права текущего пользователя с сервера.
     * Вызывается при login() и restoreSession().
     */
    suspend fun loadUserPermissions(userId: String): List<UserEffectivePermissionDto> {
        return apiClient.fetchUserPermissions(userId).getOrDefault(emptyList())
    }

    suspend fun loadRoles() {
        apiClient.fetchRoles().onSuccess { _roles.value = it }
    }

    fun updateRolePermissions(roleId: String, permissions: List<com.example.prolestimesheet.network.RolePermissionUpdateDto>) {
        CoroutineScope(Dispatchers.IO).launch {
            android.util.Log.d("Repository", "🔄 Updating role $roleId with ${permissions.size} permissions")
            apiClient.updateRolePermissions(roleId, permissions)
                .onSuccess {
                    android.util.Log.d("Repository", "✅ Role permissions updated")
                    loadRoles()  // Перезагружаем список
                }
                .onFailure { e ->
                    android.util.Log.e("Repository", "❌ Failed to update role permissions", e)
                }
        }
    }


    fun updateUserPermissions(
        userId: String,
        overrides: List<UserPermissionOverrideDto>
    ) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            apiClient.updateUserPermissions(userId, overrides)
                .onSuccess {
                    android.util.Log.d("Repository", "✅ User permissions updated for $userId")
                }
                .onFailure { e ->
                    android.util.Log.e("Repository", "❌ Failed to update user permissions", e)
                }
        }
    }

    suspend fun loadPermissions(): List<PermissionInfo> {
        return apiClient.fetchPermissions().getOrDefault(emptyList())
    }


    suspend fun login(email: String, password: String, rememberMe: Boolean): Result<User> = runCatching {
        val resp = apiClient.login(context, email, password, rememberMe).getOrThrow()
        // Сохраняем токен и дату истечения, приходящие от сервера
        LocalDataStore.saveAuthToken(context, resp.token, resp.expiresAt)
        LocalDataStore.saveSession(context, resp.user, resp.expiresAt)

        apiClient.setToken(resp.token)
        _user.value = resp.user
        val entries = apiClient.fetchEntries(resp.user.id).getOrDefault(emptyList())
        _entries.value = entries
        LocalDataStore.saveEntries(context, resp.user.id, entries)  // 🆕 Кэш

        val vacations = apiClient.fetchVacations(resp.user.id).getOrDefault(emptyList())
        _vacations.value = vacations
        LocalDataStore.saveVacations(context, resp.user.id, vacations)  // 🆕 Кэш

        val projects = apiClient.fetchProjects().getOrDefault(emptyList())
        _projects.value = projects
        LocalDataStore.saveProjects(context, projects)  // 🆕 Кэш

        loadDayOffs(resp.user.id)

        loadNotifications()

        // 🔥 Регистрируем FCM токен
        getFcmToken()?.let { token ->
            registerFcmToken(resp.user.id, token)
        }
        loadUserPermissions()  // 🆕 Загружаем права
        // 🔥 ИСПРАВЛЕНО: для админа сразу грузим ВСЕ данные (минуя loadExpenses)
        if (resp.user.role == "admin" || resp.user.role == "director" || resp.user.role == "superadmin") {
            loadEmployees()
            // 🔥 Грузим все записи и расходы БЕЗ throttling
            val allEntries = apiClient.fetchAllEntries().getOrDefault(emptyList())
            if (allEntries.isNotEmpty()) _entries.value = allEntries
            val allExp = apiClient.fetchAllExpenses().getOrDefault(emptyList())
            if (allExp.isNotEmpty()) _expenses.value = allExp
            // 🆕 Все командировки (для аналитики)
            loadAllBusinessTrips()

            // 🆕 Доходы всех сотрудников (для аналитики)
            val allInc = apiClient.fetchAllIncomes().getOrDefault(emptyList())
            if (allInc.isNotEmpty()) {
                _incomes.value = allInc
                // Сохраняем в кэш для текущего пользователя
                LocalDataStore.saveIncomes(context, resp.user.id, allInc.filter { it.userId == resp.user.id })
            }
            Log.d("Repository", "✅ Admin data loaded: ${allEntries.size} entries, ${allExp.size} expenses")
        } else {
            loadBusinessTrips(resp.user.id)
            // Для обычного сотрудника — только свои расходы
            loadExpenses(resp.user.id)
            // 🆕 Загружаем доходы сотрудника
            loadIncomes(resp.user.id)
        }
        resp.user
    }

    private var lastAllEntriesLoadTime = 0L

    suspend fun loadAllEntries() {
        val now = System.currentTimeMillis()
        if (now - lastAllEntriesLoadTime < 5000) return
        lastAllEntriesLoadTime = now

        val remote = apiClient.fetchAllEntries().getOrDefault(emptyList())
        if (remote.isNotEmpty()) {
            _entries.value = remote
        }
    }

    private var lastAllExpensesLoadTime = 0L

    suspend fun loadAllExpenses() {
        val now = System.currentTimeMillis()
        if (now - lastAllExpensesLoadTime < 5000) return
        lastAllExpensesLoadTime = now

        val remote = apiClient.fetchAllExpenses().getOrDefault(emptyList())
        if (remote.isNotEmpty()) {
            _expenses.value = remote
        }
    }

    // 🗑 Очистка
    suspend fun clearSession() {
        android.util.Log.d("Repository", "🧹 clearSession: очищаем все данные")
        LocalDataStore.clearSession(context)
        LocalDataStore.clearAuthToken(context)
        _user.value = null
        _entries.value = emptyList()
        _vacations.value = emptyList()

        // 🔥 Очищаем очередь синхронизации
        SyncQueue.clearQueue(context)
        LocalDataStore.clearUserPermissions(context)  // 🆕
        apiClient.clearToken()
        Log.d("Repository", "✅ clearSession: всё очищено")
    }

    suspend fun restoreSession(user: User, entries: List<TimeEntry>, vacations: List<VacationPeriod>) {
        // 🔥 СНАЧАЛА: мгновенно показываем кэш
        _user.value = user
        _entries.value = entries
        _vacations.value = vacations
        _profiles.value = LocalDataStore.getProfiles(context)
        _employees.value = LocalDataStore.getProfiles(context)

        // 🔥 Загружаем dayOffs из кэша (быстрый старт)
        _dayOffsFlow.value = LocalDataStore.getDayOffs(context, user.id)

        // 🔥 ИСПРАВЛЕНО: для админа сразу показываем кэш, потом обновляем
        if (user.role == "admin" || user.role == "director" || user.role == "superadmin") {
            // Админские данные тоже из кэша, если есть
            val cachedEntries = LocalDataStore.getEntries(context, user.id)
            if (cachedEntries.isNotEmpty()) _entries.value = cachedEntries

            // 🆕 Доходы всех сотрудников из кэша
            val cachedIncomes = LocalDataStore.getIncomes(context, user.id)
            if (cachedIncomes.isNotEmpty()) _incomes.value = cachedIncomes
        } else {
            // Для сотрудника — свои расходы из кэша
            loadExpenses(user.id)
            // 🆕 Доходы из кэша
            val cachedIncomes = LocalDataStore.getIncomes(context, user.id)
            if (cachedIncomes.isNotEmpty()) _incomes.value = cachedIncomes
        }

        // 🔥 В ФОНЕ: обновляем данные с сервера (если сеть есть)
        CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                loadNotifications()
                loadBusinessTrips(user.id)
                loadDayOffs(user.id)  // Перезагрузит и обновит кэш
                loadUserPermissions()
                loadIncomes(user.id)  // 🆕 Обновляем доходы с сервера

                if (user.role == "admin" || user.role == "director" || user.role == "superadmin") {
                    loadEmployees()
                    val allEntries = apiClient.fetchAllEntries().getOrDefault(emptyList())
                    if (allEntries.isNotEmpty()) _entries.value = allEntries
                    val allExp = apiClient.fetchAllExpenses().getOrDefault(emptyList())
                    if (allExp.isNotEmpty()) _expenses.value = allExp
                    loadAllBusinessTrips()
                    Log.d("Repository", "✅ Admin data refreshed from server")
                }
            } catch (e: Exception) {
                // При офлайне — логируем, но не ломаем работу
                Log.w("Repository", "⚠️ Background refresh failed (offline?): ${e.message}")
            }
        }
    }

    private var lastEmployeesLoadTime = 0L

    suspend fun loadEmployees() {
        val now = System.currentTimeMillis()
        if (now - lastEmployeesLoadTime < 5000) return
        lastEmployeesLoadTime = now

        val remote = apiClient.fetchUsers().getOrDefault(emptyList())
        if (remote.isNotEmpty()) {
            _profiles.value = remote
            _employees.value = remote
            LocalDataStore.saveProfiles(context, remote)
        } else {
            val cached = LocalDataStore.getProfiles(context)
            _profiles.value = cached
            _employees.value = cached
        }
    }

    suspend fun loadDayOffs(userId: String) {
        // Сначала показываем кэш, чтобы UI не моргал
        _dayOffsFlow.value = LocalDataStore.getDayOffs(context, userId)
        // Затем тянем актуальные с сервера
        val remote = apiClient.fetchDayOffs(userId).getOrDefault(emptyList())
        _dayOffsFlow.value = remote
        LocalDataStore.saveDayOffs(context, userId, remote)
    }

    // 🌞 ИСПРАВЛЕНО: Синхронизация выходного
    suspend fun syncDayOff(dayOff: DayOff) {
        ApiClient.createDayOff(dayOff)
        _user.value?.let {
            loadDayOffs(it.id)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun deleteProject(projectId: String) {
        // Оптимистичное удаление
        _projects.value = _projects.value.filter { it.id != projectId }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            apiClient.deleteProject(projectId).onSuccess {
                loadProjects()
            }.onFailure { e ->
                Log.e("Repository", "❌ Ошибка удаления проекта", e)
                loadProjects() // откат
            }
        }
    }

    // 🗑 ИСПРАВЛЕНО: Удаление выходного
    suspend fun deleteDayOff(userId: String, date: kotlinx.datetime.LocalDate) {
        ApiClient.deleteDayOff(userId, date)
        loadDayOffs(userId) // Перезагружаем список
    }

    private var lastProjectsLoadTime = 0L

    suspend fun loadProjects() {
        val now = System.currentTimeMillis()
        if (now - lastProjectsLoadTime < 5000) { // Не чаще чем раз в 5 секунд
            return
        }
        lastProjectsLoadTime = now

        val remote = apiClient.fetchProjects().getOrDefault(emptyList())
        if (remote.isNotEmpty()) {
            _projects.value = remote
            LocalDataStore.saveProjects(context, remote)
        }
    }

    suspend fun loadEntries(userId: String) {
        val remote = apiClient.fetchEntries(userId).getOrDefault(emptyList())
        if (remote.isNotEmpty()) {
            _entries.value =  remote
            LocalDataStore.saveEntries(context, userId, remote)
        }
    }
    // ➕ ДОБАВЛЕНИЕ ПРОЕКТА — принимает полный объект Project
    @OptIn(DelicateCoroutinesApi::class)
    fun addProject(project: Project) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            // Убеждаемся, что у проекта есть ID (на случай если забыли сгенерить)
            val projectToSubmit = if (project.id.isBlank()) {
                project.copy(id = UUID.randomUUID().toString())
            } else project

            apiClient.submitProject(projectToSubmit)
                .onSuccess {
                    Log.d("Repository", "✅ Project created: ${project.name}")
                    loadProjects()
                }
                .onFailure { e ->
                    Log.e("Repository", "❌ Failed to create project: ${e.message}")
                }
        }
    }

    // ✏️ ОБНОВЛЕНИЕ ПРОЕКТА
    @OptIn(DelicateCoroutinesApi::class)
    fun updateProject(project: Project) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            apiClient.updateProject(project)
            loadProjects()
        }
    }

    /**
     * 🎯 Устанавливает полный список записей (для оптимистичных удалений).
     */
    fun updateEntriesList(entries: List<TimeEntry>) {
        _entries.value = entries
    }

    /**
     * 🎯 Немедленное оптимистичное обновление записей в UI.
     * При добавлении часов к существующей записи — СУММИРУЕТ часы в UI,
     * но на сервер отправляется дельта (сервер сам суммирует).
     *
     * @param existingEntry существующая запись (null если новая)
     * @param deltaHours дельта часов (сколько добавить)
     * @param entryForServer entry для отправки на сервер (с дельтой)
     */
    fun updateEntriesOptimistic(
        existingEntry: TimeEntry?,
        deltaHours: Float,
        entryForServer: TimeEntry
    ) {
        val current = _entries.value

        if (existingEntry != null) {
            // 🔄 ОБНОВЛЕНИЕ: суммируем часы в UI
            val totalHours = existingEntry.hours + deltaHours
            val updatedEntry = existingEntry.copy(hours = totalHours)

            _entries.value = current.map {
                if (it.id == existingEntry.id) updatedEntry else it
            }
            android.util.Log.d("Repository", "✅ Optimistic UPDATE: ${existingEntry.hours} + $deltaHours = $totalHours")
        } else {
            // ➕ СОЗДАНИЕ: добавляем новую запись
            // Проверяем, нет ли уже записи с тем же слотом (userId+projectId+date)
            val sameSlotIndex = current.indexOfFirst {
                it.userId == entryForServer.userId &&
                        it.projectId == entryForServer.projectId &&
                        it.date == entryForServer.date
            }

            _entries.value = if (sameSlotIndex >= 0) {
                // Заменяем запись в том же слоте (суммируем часы)
                val existing = current[sameSlotIndex]
                val updated = existing.copy(hours = existing.hours + deltaHours)
                current.toMutableList().apply { set(sameSlotIndex, updated) }
            } else {
                // Добавляем новую запись
                current + entryForServer
            }
            android.util.Log.d("Repository", "✅ Optimistic CREATE: $deltaHours hours, entry.id=${entryForServer.id.take(8)}")
        }
    }

    /**
     * 🔄 Синхронизация записи с сервером через SyncQueue.
     * Оптимистичное обновление уже сделано в updateEntriesOptimistic().
     * Здесь только добавляем в очередь для фоновой отправки.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun syncEntry(entry: TimeEntry) {
        // Определяем тип операции: UPDATE если есть существующая запись в том же слоте
        val isUpdate = _entries.value.any {
            it.userId == entry.userId &&
                    it.projectId == entry.projectId &&
                    it.date == entry.date &&
                    it.id != entry.id  // Если id отличается — это обновление
        } || _entries.value.any { it.id == entry.id }

        if (isUpdate) {
            SyncQueue.enqueueUpdateEntry(context, entry)
        } else {
            SyncQueue.enqueueCreateEntry(context, entry)
        }

        // 🔥 УБРАНО: больше не делаем loadEntries в фоне после каждого изменения!
        // Это вызывало гонку и перезатирало оптимистичное обновление.
        // Синхронизация с сервером происходит через SyncQueue + WorkManager.
        android.util.Log.d("Repository", "📤 Entry queued for sync: id=${entry.id.take(8)}, type=${if (isUpdate) "UPDATE" else "CREATE"}")
    }


    @OptIn(DelicateCoroutinesApi::class)
    fun createUser(user: User) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            apiClient.createUser(user).onSuccess { loadEmployees() }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun updateUser(user: User) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            apiClient.updateUser(user).onSuccess { loadEmployees() }
        }
    }

    // 🗑 Удаление сотрудника
    @OptIn(DelicateCoroutinesApi::class)
    fun deleteUser(userId: String) {
        // Оптимистичное удаление из UI
        _employees.value = _employees.value.filter { it.id != userId }
        _profiles.value = _profiles.value.filter { it.id != userId }

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            apiClient.deleteUser(userId)
                .onSuccess {
                    android.util.Log.d("Repository", "✅ User $userId deleted")
                    loadEmployees()  // Перезагружаем для синхронизации
                }
                .onFailure { e ->
                    android.util.Log.e("Repository", "❌ Failed to delete user", e)
                    loadEmployees()  // Откат
                }
        }
    }

    // 🔄 Полная замена отпусков пользователя (для toggle-логики)
    suspend fun replaceAllVacations(userId: String, newVacations: List<VacationPeriod>) {
        // 1. Удаляем все существующие отпуска с сервера
        apiClient.deleteAllVacations(userId)

        // 2. Создаём новые периоды
        val created = mutableListOf<VacationPeriod>()
        for (vacation in newVacations) {
            apiClient.submitVacation(vacation).onSuccess { created.add(it) }
        }

        // 3. Обновляем локальное состояние и кэш
        _vacations.value = created
        LocalDataStore.saveVacations(context, userId, created)
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun deleteEntry(entryId: String) {
        // 🔥 Оптимистичное удаление теперь делается во ViewModel (синхронно)
        // Здесь только добавляем в очередь

        SyncQueue.enqueueDeleteEntry(context, entryId)
    }

    suspend fun loadExpenses(userId: String) {
        val remote = apiClient.fetchExpenses(userId).getOrDefault(emptyList())
        _expenses.value = remote
    }

    suspend fun toggleReceiptSubmitted(expenseId: String, newStatus: Boolean) {
        val expense = _expenses.value.firstOrNull { it.id == expenseId } ?: return
        val updated = expense.copy(receiptSubmitted = newStatus)
        // Оптимистичное обновление UI
        _expenses.value = _expenses.value.map { if (it.id == expenseId) updated else it }
        // Синхронизация с сервером
        apiClient.updateExpense(updated).onFailure {
            // Откат при ошибке
            _expenses.value = _expenses.value.map { if (it.id == expenseId) expense else it }
        }
    }

    suspend fun addExpense(expense: Expense) {
        apiClient.createExpense(expense).onSuccess { created ->
            _expenses.value = listOf(created) + _expenses.value
        }
    }

    suspend fun removeExpense(expenseId: String) {
        apiClient.deleteExpense(expenseId).onSuccess {
            _expenses.value = _expenses.value.filter { it.id != expenseId }
        }
    }

    suspend fun loadBusinessTrips(userId: String) {
        _trips.value = apiClient.fetchBusinessTrips(userId).getOrDefault(emptyList())
    }

    // 🆕 Загрузка ВСЕХ командировок (для админа/аналитики)
    suspend fun loadAllBusinessTrips() {
        val remote = apiClient.fetchBusinessTrips(userId = "", all = true).getOrDefault(emptyList())
        if (remote.isNotEmpty()) {
            _trips.value = remote
            Log.d("ApiClient", "✅ All business trips loaded: ${remote.size} trips")
        }
    }

    suspend fun addBusinessTrip(trip: BusinessTrip) {
        apiClient.createBusinessTrip(trip).onSuccess { created ->
            _trips.value = listOf(created) + _trips.value
        }
    }

    suspend fun updateBusinessTrip(trip: BusinessTrip) {
        apiClient.updateBusinessTrip(trip).onSuccess { updated ->
            _trips.value = _trips.value.map { if (it.id == updated.id) updated else it }
        }
    }

    suspend fun removeBusinessTrip(tripId: String) {
        apiClient.deleteBusinessTrip(tripId).onSuccess {
            _trips.value = _trips.value.filter { it.id != tripId }
        }
    }

    suspend fun loadNotifications() {
        _notifications.value = apiClient.fetchNotifications().getOrDefault(emptyList())
    }

    suspend fun markNotificationRead(id: String) {
        val notif = _notifications.value.firstOrNull { it.id == id } ?: return
        val updated = notif.copy(isRead = true)
        _notifications.value = _notifications.value.map { if (it.id == id) updated else it }
        apiClient.markNotificationRead(id)
    }

    suspend fun markAllNotificationsRead() {
        _notifications.value = _notifications.value.map { it.copy(isRead = true) }
        apiClient.markAllNotificationsRead()
    }

    // При создании отпуска/командировки/выходного вызывать:
    suspend fun sendEventNotification(type: String, title: String, message: String, payload: String = "") {
        apiClient.sendNotification(type, title, message, payload)
        loadNotifications()
    }

    suspend fun uploadReceiptPhoto(expenseId: String, imageBytes: ByteArray, userFullName: String) {
        apiClient.uploadReceiptPhoto(expenseId, imageBytes, userFullName).onSuccess {
            val expense = _expenses.value.firstOrNull { it.id == expenseId } ?: return@onSuccess
            val updated = expense.copy(hasReceiptPhoto = true, receiptSubmitted = true)
            _expenses.value = _expenses.value.map { if (it.id == expenseId) updated else it }
        }.onFailure { e ->
            Log.e("Repository", "❌ Upload failed", e)
        }
    }

    // 🔥 Получение FCM токена
    suspend fun getFcmToken(): String? {
        return try {
            // 🔥 ИСПРАВЛЕНО: переключаемся на IO dispatcher
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.google.android.gms.tasks.Tasks.await(
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                )
            }
        } catch (e: Exception) {
            Log.e("Repository", "❌ Ошибка получения FCM токена", e)
            null
        }
    }

    // 🔥 Отправка токена на сервер
    private suspend fun registerFcmToken(userId: String, token: String) {
        Log.d("Repository", "🔥 Начинаем регистрацию FCM токена для userId=$userId")

        val token = getFcmToken()
        if (token.isNullOrBlank()) {
            Log.w("Repository", "⚠️ FCM токен пустой, пропускаем регистрацию")
            return
        }

        Log.d("Repository", "🔑 FCM токен получен: ${token.take(30)}... (длина ${token.length})")
        Log.d("Repository", "📤 Отправляем токен на сервер...")

        val result = apiClient.registerFcmToken(userId, token)
        result.onSuccess {
            Log.d("Repository", "✅✅✅ FCM токен успешно зарегистрирован на сервере!")
        }.onFailure { e ->
            Log.e("Repository", "❌❌❌ Ошибка отправки на сервер: ${e.message}", e)
        }
    }


    // ═══════════════════════════════════════════════════════════
    // 💰 SALARY COMPONENTS
    // ═══════════════════════════════════════════════════════════
    suspend fun loadSalaryComponents(userId: String) {
        apiClient.fetchSalaryComponents(userId)
            .onSuccess { components ->
                _salaryComponents.value = components
                Log.d("Repository", "✅ Loaded ${components.size} salary components")
            }
            .onFailure { e ->
                Log.e("Repository", "❌ Failed to load salary components", e)
            }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun addSalaryComponent(component: SalaryComponentDto, userId: String) {  // ✅ SalaryComponentDto
        CoroutineScope(Dispatchers.IO).launch {
            apiClient.createSalaryComponent(component)
                .onSuccess {
                    loadSalaryComponents(userId)
                    Log.d("Repository", "✅ Salary component added: ${component.type}")
                }
                .onFailure { e ->
                    Log.e("Repository", "❌ Failed to add salary component", e)
                }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun deleteSalaryComponent(componentId: String, userId: String) {
        // Оптимистичное удаление
        _salaryComponents.value = _salaryComponents.value.filter {
            it.id != componentId
        }
        CoroutineScope(Dispatchers.IO).launch {
            apiClient.deleteSalaryComponent(componentId)
                .onSuccess {
                    loadSalaryComponents(userId)
                    Log.d("Repository", "✅ Component $componentId deleted")
                }
                .onFailure { e ->
                    Log.e("Repository", "❌ Failed to delete component", e)
                    loadSalaryComponents(userId) // Откат
                }
        }
    }

    suspend fun calculateSalary(userId: String, year: Int, month: Int): com.example.prolestimesheet.network.SalaryBreakdownResponse? {
        val result = apiClient.calculateSalary(userId, year, month).getOrNull()
        _salaryBreakdown.value = result  // ✅ Обновляем Flow!
        return result
    }

    // 📊 Экспорт зарплаты
    suspend fun fetchPayrollExport(year: Int, month: Int): com.example.prolestimesheet.network.PayrollExportResponse? {
        return apiClient.fetchPayrollExport(year, month).getOrNull()
    }

    /**
     * 🔄 Обновление типа расхода
     */
    suspend fun updateExpenseType(expenseId: String, newType: String) {
        val expense = _expenses.value.firstOrNull { it.id == expenseId } ?: return
        val updated = expense.copy(type = newType)
        // Оптимистичное обновление
        _expenses.value = _expenses.value.map { if (it.id == expenseId) updated else it }
        // Синхронизация с сервером
        apiClient.updateExpense(updated).onFailure {
            // Откат
            _expenses.value = _expenses.value.map { if (it.id == expenseId) expense else it }
        }
    }

}