package com.example.prolestimesheet.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.prolestimesheet.data.LocalDataStore
import com.example.prolestimesheet.model.*
import com.example.prolestimesheet.network.ApiClient
import com.example.prolestimesheet.network.SalaryComponentDto
import com.example.prolestimesheet.repository.TimeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import java.util.UUID

class TimesheetViewModel(val repository: TimeRepository) : ViewModel() {

    // 🔥 Защита от повторного вызова tryAutoLogin
    private var isAutoLoginInProgress = false
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val projects: StateFlow<List<Project>> = repository.projectsFlow
    val entries: StateFlow<List<TimeEntry>> = repository.entriesFlow
    val user: StateFlow<User?> = repository.userFlow
    val vacations: StateFlow<List<VacationPeriod>> = repository.vacationsFlow

    private val _selectedDate = MutableStateFlow(Clock.System.todayIn(TimeZone.currentSystemDefault()))
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    val employees: StateFlow<List<User>> = repository.employeesFlow

    val dayOffs: StateFlow<List<DayOff>> = repository.dayOffsFlow

    val profiles: StateFlow<List<User>> = repository.profilesFlow

    val expenses: StateFlow<List<Expense>> = repository.expensesFlow

    val incomes: StateFlow<List<Income>> = repository.incomesFlow

    val notifications: StateFlow<List<Notification>> = repository.notificationsFlow
    // 🔥 Реактивный unreadCount — Compose автоматически отслеживает изменения
    val unreadCount: StateFlow<Int> = notifications
        .map { list -> list.count { !it.isRead } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // 🆕 Состояние: уведомление, которое нужно открыть после восстановления сессии
    private val _pendingNotification = MutableStateFlow<Pair<String, String>?>(null)

    val roles: StateFlow<List<Role>> = repository.rolesFlow

    val userPermissions: StateFlow<com.example.prolestimesheet.model.UserEffectivePermissions> = repository.userPermissionsFlow

    // ═══════════════════════════════════════════════════════════
    // 💰 SALARY COMPONENTS & PAYROLL (делегирование к Repository)
    // ═══════════════════════════════════════════════════════════
    // 💰 Методы для зарплаты
    val salaryComponents: StateFlow<List<com.example.prolestimesheet.network.SalaryComponentDto>> = repository.salaryComponentsFlow
    val salaryBreakdown: StateFlow<com.example.prolestimesheet.network.SalaryBreakdownResponse?> = repository.salaryBreakdownFlow  // ✅ Делегируем к Repository

    fun loadSalaryComponents(userId: String) {
        viewModelScope.launch {
            repository.loadSalaryComponents(userId)
        }
    }

    fun addSalaryComponent(
        component: SalaryComponentDto,
        userId: String
    ) {
        viewModelScope.launch {
            repository.addSalaryComponent(component, userId)
        }
    }

    // ✅ Правильный метод — делегирует к Repository
    fun deleteSalaryComponent(componentId: String, userId: String) {
        viewModelScope.launch {
            repository.deleteSalaryComponent(componentId, userId)
        }
    }

    fun calculateSalary(userId: String, year: Int, month: Int) {
        viewModelScope.launch {
            repository.calculateSalary(userId, year, month)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 🆕 МЕТОДЫ ПРОВЕРКИ ПРАВ ДОСТУПА
    // ═══════════════════════════════════════════════════════════

    /**
     * Может ли текущий пользователь просматривать раздел.
     */
    fun canView(permissionKey: String): Boolean {
        val user = user.value ?: return false
        if (user.role == "superadmin") return true
        return userPermissions.value[permissionKey]?.canView ?: false
    }

    /**
     * Может ли текущий пользователь создавать записи в разделе.
     */
    fun canCreate(permissionKey: String): Boolean {
        val user = user.value ?: return false
        if (user.role == "superadmin") return true
        return userPermissions.value[permissionKey]?.canCreate ?: false
    }

    /**
     * Может ли текущий пользователь редактировать записи в разделе.
     */
    fun canEdit(permissionKey: String): Boolean {
        val user = user.value ?: return false
        if (user.role == "superadmin") return true
        return userPermissions.value[permissionKey]?.canEdit ?: false
    }

    /**
     * Может ли текущий пользователь удалять записи в разделе.
     */
    fun canDelete(permissionKey: String): Boolean {
        val user = user.value ?: return false
        if (user.role == "superadmin") return true
        return userPermissions.value[permissionKey]?.canDelete ?: false
    }

    fun loadRoles() {
        viewModelScope.launch { repository.loadRoles() }
    }

    fun updateRolePermissions(roleId: String, permissions: List<com.example.prolestimesheet.network.RolePermissionUpdateDto>) {
        repository.updateRolePermissions(roleId, permissions)
    }

    suspend fun loadUserPermissions(userId: String): List<UserEffectivePermissionDto> {
        return repository.loadUserPermissions(userId)
    }

    fun updateUserPermissions(
        userId: String,
        overrides: List<UserPermissionOverrideDto>
    ) {
        repository.updateUserPermissions(userId, overrides)
    }

    suspend fun loadPermissions(): List<PermissionInfo> {
        return repository.loadPermissions()
    }

    fun loadNotifications() { viewModelScope.launch { repository.loadNotifications() } }
    fun markNotificationRead(id: String) { viewModelScope.launch { repository.markNotificationRead(id) } }

    val pendingSyncCount: StateFlow<Int> = repository.pendingSyncCount


    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState()
        object Authenticated : UiState()
    }

    fun forceLogout(context: Context) {
        viewModelScope.launch {
            android.util.Log.d("ViewModel", "🚪 forceLogout: переключаем UI на Idle")

            // 1. СНАЧАЛА переключаем UI
            _uiState.value = UiState.Idle

            // 2. Очищаем через repository (там находятся _user, _entries и т.д.)
            repository.clearSession()

            android.util.Log.d("ViewModel", "✅ forceLogout: всё очищено")
        }
    }

    fun tryAutoLogin(context: Context) {
        // 🔥 Защита от повторного вызова
        if (isAutoLoginInProgress) {
            android.util.Log.d("ViewModel", "⏭️ tryAutoLogin уже выполняется, пропускаем")
            return
        }
        // Если уже аутентифицированы — не делаем ничего
        if (_uiState.value == UiState.Authenticated) {
            android.util.Log.d("ViewModel", "✅ Уже аутентифицированы, пропускаем")
            return
        }
        isAutoLoginInProgress = true
        viewModelScope.launch {
            try {
                val savedToken = LocalDataStore.getAuthToken(context)
                val savedUser = LocalDataStore.getSession(context)
                android.util.Log.d("ViewModel", "🔍 tryAutoLogin: token=${savedToken != null}, user=${savedUser != null}")

                // 🔥 Если нет локальной сессии — показываем LoginScreen
                if (savedToken == null || savedUser == null) {
                    _uiState.value = UiState.Idle
                    return@launch
                }

                _uiState.value = UiState.Loading

                // ВАЖНО: сначала устанавливаем токен в ApiClient
                ApiClient.setToken(savedToken)
                ApiClient.initFromSession(context)

                // 🆕 НЕ делаем сетевую проверку checkTokenValidity() при старте!
                // Она роняла пользователя при офлайне или перезапуске сервера.
                // Теперь валидация происходит автоматически через 401 interceptor
                // при любом запросе к API.

                android.util.Log.d("ViewModel", "✅ Токен есть локально, восстанавливаем сессию из кэша")

                // Восстанавливаем данные из локального кэша (быстрый старт)
                val cachedEntries = LocalDataStore.getEntries(context, savedUser.id)
                val cachedVacations = LocalDataStore.getVacations(context, savedUser.id)
                repository.restoreSession(savedUser, cachedEntries, cachedVacations)

                // 🔥 СРАЗУ показываем приложение (из кэша)
                _uiState.value = UiState.Authenticated
                android.util.Log.d("ViewModel", "✅ uiState = Authenticated (из кэша)")

                // 🔥 В ФОНЕ: пробуем синхронизировать свежие данные с сервера
                // Если сети нет — ничего не сломается, просто будут старые данные
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        android.util.Log.d("ViewModel", "🔄 Background sync: trying to refresh data...")
                        repository.loadProjects()
                        repository.loadEntries(savedUser.id)
                        repository.loadDayOffs(savedUser.id)
                        repository.loadUserPermissions()
                        android.util.Log.d("ViewModel", "✅ Background sync completed")
                    } catch (e: Exception) {
                        // Ожидаемо при офлайне — просто логируем, не роняем пользователя
                        android.util.Log.w("ViewModel", "⚠️ Background sync failed (offline?): ${e.message}")
                    }
                }

            } catch (e: Exception) {
                android.util.Log.e("ViewModel", "❌ Ошибка в tryAutoLogin", e)
                _uiState.value = UiState.Idle
            } finally {
                isAutoLoginInProgress = false
            }
        }
    }


    fun login(email: String, password: String, rememberMe: Boolean) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            repository.login(email, password, rememberMe).onSuccess { user ->
                _uiState.value = UiState.Authenticated
                repository.loadProjects()
            }.onFailure { _uiState.value = UiState.Idle }
            // После успешного восстановления сессии
            _pendingNotification.value?.let { (type, payload) ->
                Log.d("ViewModel", "🔔 Сессия восстановлена, можно переходить к уведомлениям")
                // Перезагружаем список уведомлений, чтобы новое точно было в списке
                loadNotifications()
            }
            repository.loadProjects()
        }
    }

    fun logout(context: Context) {
        viewModelScope.launch {
            android.util.Log.d("ViewModel", "🚪 Logout initiated")

            _uiState.value = UiState.Idle
            kotlinx.coroutines.delay(50)
            repository.clearSession()

            // 🔥 Сбрасываем флаг, чтобы при следующем запуске tryAutoLogin сработал
            isAutoLoginInProgress = false

            android.util.Log.d("ViewModel", "✅ Logout complete")
        }
    }

    fun selectDate(date: LocalDate) { _selectedDate.value = date }
    fun createUser(user: User) { viewModelScope.launch { repository.createUser(user) } }
    fun updateUser(user: User) { viewModelScope.launch { repository.updateUser(user) } }

    // 🗑 Удаление сотрудника
    fun deleteUser(userId: String) {
        viewModelScope.launch {
            repository.deleteUser(userId)
        }
    }

    // 🆕 Обновление метода добавления проекта
    fun addProject(project: Project) {
        if (!canCreate("projects")) return  // 🆕 Проверка по праву
        viewModelScope.launch {
            repository.addProject(project)
        }
    }

    fun updateProject(project: Project) {
        viewModelScope.launch {
            repository.updateProject(project)
        }
    }
    fun getTotalHoursForDate(date: LocalDate): Float {
        return entries.value.filter { it.date == date }.sumOf { it.hours.toDouble() }.toFloat()
    }

    fun canAddHours(date: LocalDate, additionalHours: Float): Boolean {
        return getTotalHoursForDate(date) + additionalHours <= 24f
    }

    fun addEntry(projectId: String, projectName: String, hours: Float, country: String): AddEntryResult {
        val currentUser = user.value ?: return AddEntryResult.Error("Пользователь не авторизован")
        val date = _selectedDate.value

        // 🔥 ЗАПРЕТ: нельзя добавлять часы за будущие даты
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        if (date > today) {
            return AddEntryResult.Error("Нельзя добавлять часы за будущие даты")
        }
        if (hours <= 0f) return AddEntryResult.Error("Часы должны быть больше 0")
        if (!canAddHours(date, hours)) return AddEntryResult.Error("Нельзя добавить более 24 часов в день")

        val existingEntry = entries.value.find {
            it.userId == currentUser.id && it.projectId == projectId && it.date == date
        }

        // 🔥 КРИТИЧНО: создаём entry для отправки на сервер
        // Сервер сам суммирует: existing.hours + entry.hours (в PUT /entries)
        // Поэтому отправляем ДЕЛЬТУ (новые часы), а не итоговую сумму
        val entryForServer = existingEntry?.copy(hours = hours) ?: TimeEntry(
            id = UUID.randomUUID().toString(),
            userId = currentUser.id,
            projectId = projectId,
            projectName = projectName,
            date = date,
            hours = hours,
            country = country
        )

        // 🔥 НЕМЕДЛЕННОЕ оптимистичное обновление UI (синхронно, до launch)
        // В UI показываем СУММУ (existing + new), а не дельту
        repository.updateEntriesOptimistic(existingEntry, hours, entryForServer)
        Log.d("ViewModel", "✅ UI updated: existing=${existingEntry?.hours ?: 0} + $hours, entry.id=${entryForServer.id.take(8)}")

        // 🔥 В ФОНЕ: синхронизация с сервером через SyncQueue
        viewModelScope.launch {
            try {
                repository.syncEntry(entryForServer)
            } catch (e: Exception) {
                Log.w("ViewModel", "⚠️ Sync failed (queued for later): ${e.message}")
            }
        }

        return AddEntryResult.Success
    }

    sealed class AddEntryResult {
        object Success : AddEntryResult()
        data class Error(val message: String) : AddEntryResult()
    }

    // 🏖 Умное добавление/удаление отпуска (новая логика)
    fun addVacation(start: LocalDate, end: LocalDate) {
        val currentUser = user.value ?: return
        viewModelScope.launch {
            val existingVacations = vacations.value

            // 1. Вычисляем все дни в выбранном диапазоне
            val selectedDays = (0..end.toEpochDays() - start.toEpochDays()).map {
                LocalDate.fromEpochDays(start.toEpochDays() + it)
            }.toSet()

            // 2. Проверяем: находится ли новый интервал ЦЕЛИКОМ внутри какого-либо существующего
            val isInsideExisting = existingVacations.any { vacation ->
                start >= vacation.start && end <= vacation.end
            }

            // 3. Вычисляем все дни в существующих отпусках
            val existingDays = existingVacations.flatMap { v ->
                (0..v.end.toEpochDays() - v.start.toEpochDays()).map {
                    LocalDate.fromEpochDays(v.start.toEpochDays() + it)
                }
            }.toSet()

            val newVacationDays: Set<LocalDate> = if (isInsideExisting) {
                // 🔴 Режим УДАЛЕНИЯ: удаляем пересечение
                val daysToRemove = selectedDays.intersect(existingDays)
                existingDays - daysToRemove
            } else {
                // 🟢 Режим ДОПОЛНЕНИЯ: объединяем все дни
                existingDays + selectedDays
            }

            // 4. Преобразуем набор дней обратно в непрерывные периоды
            val allDays = newVacationDays.sorted()

            val newVacations = mutableListOf<VacationPeriod>()
            if (allDays.isNotEmpty()) {
                var periodStart = allDays.first()
                var prevDay = allDays.first()

                for (i in 1 until allDays.size) {
                    val currentDay = allDays[i]
                    // Если разрыв больше 1 дня - создаём период
                    if (currentDay.toEpochDays() - prevDay.toEpochDays() > 1) {
                        newVacations.add(VacationPeriod(
                            id = UUID.randomUUID().toString(),
                            userId = currentUser.id,
                            start = periodStart,
                            end = prevDay
                        ))
                        periodStart = currentDay
                    }
                    prevDay = currentDay
                }
                // Добавляем последний период
                newVacations.add(VacationPeriod(
                    id = UUID.randomUUID().toString(),
                    userId = currentUser.id,
                    start = periodStart,
                    end = prevDay
                ))
            }

            // 5. Заменяем все отпуска на сервере
            repository.replaceAllVacations(currentUser.id, newVacations)
        }
    }

    // 🔥 Переключение выходного (полностью переписано под отдельную таблицу)
    fun toggleDayOff(date: LocalDate) {
        val currentUser = user.value ?: return
        viewModelScope.launch {
            val exists = dayOffs.value.any { it.date == date && it.userId == currentUser.id }
            if (exists) {
                repository.deleteDayOff(currentUser.id, date)
            } else {
                repository.syncDayOff(DayOff(userId = currentUser.id, date = date))
            }
        }
    }

    // 🆕 Получение актуального профиля сотрудника (свежая ставка, валюта)
    fun getFreshProfile(userId: String?): User? {
        if (userId.isNullOrBlank()) return null
        // Сначала ищем в profiles (они обновляются чаще), затем в employees
        return profiles.value.find { it.id == userId }
            ?: employees.value.find { it.id == userId }
            ?: user.value?.takeIf { it.id == userId }
    }

    fun deleteEntry(entryId: String) {
        // 🔥 НЕМЕДЛЕННОЕ оптимистичное удаление из UI (синхронно)
        val currentEntries = repository.entriesFlow.value
        val updatedEntries = currentEntries.filter { it.id != entryId }
        repository.updateEntriesList(updatedEntries)
        Log.d("ViewModel", "✅ UI updated: entry $entryId removed immediately")

        // 🔥 В ФОНЕ: синхронизация удаления с сервером
        viewModelScope.launch {
            repository.deleteEntry(entryId)
        }
    }

    fun deleteProject(projectId: String) {
        repository.deleteProject(projectId)
    }

    // ✅ Переключение статуса чека (для отдельного расхода)
    fun toggleReceiptSubmitted(expenseId: String, newStatus: Boolean) {
        viewModelScope.launch {
            repository.toggleReceiptSubmitted(expenseId, newStatus)
        }
    }

    // ➕ Добавление нового расхода
    fun addExpense(
        projectId: String,
        projectName: String,
        date: LocalDate,
        type: String, // "ROAD" или "OTHER"
        name: String = "", // название для OTHER
        amount: Double,
        currency: String = "RUB",
        comment: String = ""
    ) {
        val currentUser = user.value ?: return
        viewModelScope.launch {
            val expense = Expense(
                userId = currentUser.id,
                projectId = projectId.ifBlank { getOtherProjectId() },
                projectName = projectName.ifBlank { "ДРУГОЕ" },
                date = date,
                type = type,
                name = name,
                amount = amount,
                currency = currency,
                comment = comment
            )
            repository.addExpense(expense)
        }
    }

    // 🗑 Удаление расхода
    fun removeExpense(expenseId: String) {
        viewModelScope.launch {
            repository.removeExpense(expenseId)
        }
    }

    // 🔧 Вспомогательная функция для получения ID проекта "ДРУГОЕ"
    private fun getOtherProjectId(): String {
        val otherProject = projects.value.firstOrNull { it.name == "ДРУГОЕ" }
        return otherProject?.id ?: "00000000-0000-0000-0000-000000000001"
    }

    fun uploadReceiptPhoto(expenseId: String, imageBytes: ByteArray) {
        viewModelScope.launch {
            repository.uploadReceiptPhoto(expenseId, imageBytes)
        }
    }

    val trips: StateFlow<List<BusinessTrip>> = repository.tripsFlow

    fun addBusinessTrip(trip: BusinessTrip) { viewModelScope.launch { repository.addBusinessTrip(trip) } }
    fun updateBusinessTrip(trip: BusinessTrip) { viewModelScope.launch { repository.updateBusinessTrip(trip) } }
    fun removeBusinessTrip(tripId: String) { viewModelScope.launch { repository.removeBusinessTrip(tripId) } }

    // 📊 Экспорт зарплаты
    suspend fun fetchPayrollExport(year: Int, month: Int): com.example.prolestimesheet.network.PayrollExportResponse? {
        return repository.fetchPayrollExport(year, month)
    }


    fun addIncome(projectId: String?, projectName: String, date: kotlinx.datetime.LocalDate,
                  name: String, amount: Double, currency: String) {
        val currentUser = user.value ?: return
        viewModelScope.launch {
            val income = Income(
                userId = currentUser.id,
                projectId = projectId,
                projectName = projectName,
                date = date,
                name = name,
                amount = amount,
                currency = currency
            )
            repository.addIncome(income)
        }
    }

    fun removeIncome(incomeId: String) {
        viewModelScope.launch { repository.removeIncome(incomeId) }
    }

    // 🗑 Удаление билета
    fun deleteTicket(ticketId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                repository.deleteTicket(ticketId)
                onResult(true)
            } catch (e: Exception) {
                Log.e("ViewModel", "❌ Failed to delete ticket", e)
                onResult(false)
            }
        }
    }

    /**
     * 🔄 Изменение типа расхода (используется в AdminProjectExpensesScreen)
     */
    fun updateExpenseType(expenseId: String, newType: String) {
        viewModelScope.launch {
            repository.updateExpenseType(expenseId, newType)
        }
    }
}