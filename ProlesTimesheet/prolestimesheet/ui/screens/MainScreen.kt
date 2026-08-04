package com.example.prolestimesheet.ui.screens

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.model.TimeEntry
import com.example.prolestimesheet.model.User
import com.example.prolestimesheet.navigation.PendingNavigationHolder
import com.example.prolestimesheet.network.NetworkMonitor
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel

// 🔥 Обновлённый список экранов
sealed class Screen(val title: String, val icon: ImageVector) {
    object Home : Screen("Главная", Icons.Default.Home)
    object Timesheet : Screen("Табель", Icons.Default.CalendarMonth)
    object Profile : Screen("Профиль", Icons.Default.Person)
    object Admin : Screen("Управление", Icons.Default.Dashboard)
    object Payroll : Screen("Расчёт ЗП", Icons.Default.Payments)
    object Projects : Screen("Проекты", Icons.Default.Folder)
    object Employees : Screen("Сотрудники", Icons.Default.People)
    object Analytics : Screen("Аналитика", Icons.Default.BarChart)
    object CostCalculation : Screen("Себестоимость", Icons.Default.Calculate)
    object MyExpenses : Screen("Мои расходы", Icons.AutoMirrored.Filled.ReceiptLong)
    object AdminExpenses : Screen("Расходы сотрудников", Icons.AutoMirrored.Filled.ReceiptLong)
    object EmployeeProfile : Screen("Профиль сотрудника", Icons.Default.Person)
    object BusinessTrips : Screen("Командировки", Icons.Default.Train)
    object Notifications : Screen("Уведомления", Icons.Default.Notifications)
    object Permissions : Screen("Права доступа", Icons.Default.AdminPanelSettings)
    object MyTickets : Screen("Мои билеты", Icons.Default.ConfirmationNumber)
    object AdminTickets : Screen("Билеты", Icons.Default.UploadFile)
    object AdminProjectExpenses : Screen("Расходы по проектам", Icons.Default.AttachMoney)
    object EmployeeStats : Screen("Моя статистика", Icons.Default.Analytics)
}

@Composable
fun MainScreen(
    viewModel: TimesheetViewModel,
    entries: List<TimeEntry>,
    user: User?,
    onLogout: () -> Unit
) {
    var selectedEmployeeId by remember { mutableStateOf<String?>(null) }

    var selectedScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    // 🆕 История навигации (back stack)
    val navigationStack = remember { mutableStateListOf<Screen>() }

    // 🔥 Состояние для диалога отпуска (управляется из MainScreen)
    var showVacationDialog by remember { mutableStateOf(false) }

    // 🆕 Состояние для предустановленного фильтра в AdminExpensesScreen
    var adminExpensesInitialEmployeeId by remember { mutableStateOf<String?>(null) }

    // 🆕 Динамическое меню: видимость экранов определяется правами
    val userPermissions by viewModel.userPermissions.collectAsState()
    val visibleScreens = remember(user?.role, userPermissions) {
        val base = listOf(Screen.Home, Screen.Timesheet, Screen.Profile)
        val hasAnyAdminPermission = user?.role == "superadmin" ||
                userPermissions.values.any { it.canView }  // есть хотя бы одно право на просмотр
        if (hasAnyAdminPermission) base + Screen.Admin else base
    }

    // 🆕 АВТОПЕРЕХОД: слушаем StateFlow и user одновременно
    val pending by PendingNavigationHolder.pending.collectAsState()

    LaunchedEffect(pending, user) {
        if (pending != null && user != null) {
            val (type, _) = pending!!
            Log.d("MainScreen", "🚀 Автопереход на Notifications: type=$type, user=${user.name}")

            // Задержка, чтобы UI отрисовался (и tryAutoLogin успел восстановить данные)
            kotlinx.coroutines.delay(500)

            // ✅ Используем ПРАВИЛЬНЫЙ navigationStack (а не screenStack!)
            navigationStack.add(selectedScreen)
            selectedScreen = Screen.Notifications

            // Очищаем Singleton (consume = get+clear)
            PendingNavigationHolder.consume()
        }
    }

    // Сброс на "Главную", если текущий экран стал недоступен
    LaunchedEffect(visibleScreens) {
        if (selectedScreen !in visibleScreens) selectedScreen = Screen.Home
    }

    // 🆕 Функция навигации с добавлением в историю
    fun navigateTo(screen: Screen) {
        if (screen != selectedScreen) {
            navigationStack.add(selectedScreen) // Сохраняем текущий экран
            selectedScreen = screen
        }
    }

    // 🆕 Функция возврата назад
    fun goBack() {
        if (navigationStack.isNotEmpty()) {
            selectedScreen = navigationStack.removeAt(navigationStack.lastIndex)
        }
    }

    // 🆕 Перехватываем системную кнопку "Назад"
    BackHandler(enabled = navigationStack.isNotEmpty()) {
        goBack()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                visibleScreens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = selectedScreen == screen,
                        onClick = {
                            // 🆕 При клике на нижнее меню - очищаем историю (это "главная" навигация)
                            navigationStack.clear()
                            selectedScreen = screen
                        }
                    )
                }
            }
        },
        // 🆕 Убираем белые полосы — Scaffold не добавляет insets для системных баров
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (selectedScreen) {
                Screen.MyTickets -> MyTicketsScreen(viewModel = viewModel, onBack = { goBack() })
                Screen.AdminTickets -> AdminTicketsScreen(viewModel = viewModel, onBack = { goBack() })

                Screen.Permissions -> PermissionsScreen(
                    viewModel = viewModel,
                    onBack = { goBack() }
                )

                Screen.Timesheet -> TimesheetScreen(
                    viewModel = viewModel,
                    showVacationDialog = showVacationDialog,
                    onVacationDialogDismissed = { showVacationDialog = false },
                    onRequestVacation = { showVacationDialog = true }  // 🆕 Передаём триггер
                )

                Screen.Home -> HomeScreen(
                    user = user,
                    viewModel = viewModel,
                    onRequestVacation = { showVacationDialog = true },
                    onNavigateToTimesheet = { navigateTo(Screen.Timesheet) },
                    onNavigateToProfile = { navigateTo(Screen.Profile) },
                    onNavigateToNotifications = { navigateTo(Screen.Notifications) },
                    onNavigateToMyTickets = { navigateTo(Screen.MyTickets) },
                )

                Screen.Profile -> ProfileScreen(
                    user = user,
                    viewModel = viewModel,
                    onLogout = onLogout,
                    onNavigateToMyExpenses = { navigateTo(Screen.MyExpenses) },
                    onNavigateToMyTrips = { navigateTo(Screen.BusinessTrips) },
                    onNavigateToNotifications = { navigateTo(Screen.Notifications) },
                    onNavigateToMyTickets = { navigateTo(Screen.MyTickets) },  // 🆕
                    onNavigateToStats = { navigateTo(Screen.EmployeeStats) }  // 🆕 Статистика сотрудника
                )

                Screen.Admin -> AdminManagementScreen(
                    viewModel = viewModel,  // 🆕
                    onNavigateToPayroll = { navigateTo(Screen.Payroll) },
                    onNavigateToAnalytics = { navigateTo(Screen.Analytics) },
                    onNavigateToProjects = { navigateTo(Screen.Projects) },
                    onNavigateToEmployees = { navigateTo(Screen.Employees) },
                    onNavigateToAdminExpenses = { navigateTo(Screen.AdminExpenses) },
                    onNavigateToTickets = { navigateTo(Screen.AdminTickets) },
                    onNavigateToPermissions = { navigateTo(Screen.Permissions) },  // 🆕
                    onNavigateToCostCalculation = { navigateTo(Screen.CostCalculation) },
                    onNavigateToProjectExpenses = { navigateTo(Screen.AdminProjectExpenses) }
                )

                Screen.AdminProjectExpenses -> AdminProjectExpensesScreen(
                    viewModel = viewModel,
                    onBack = { goBack() }
                )

                Screen.Projects -> ProjectsScreen(
                    viewModel = viewModel,
                    onBack = { goBack() }
                )

                Screen.Payroll -> PayrollScreen(
                    viewModel = viewModel,
                    onBack = { goBack() } // 🆕 Передаем возврат назад
                )

                Screen.Employees -> EmployeesScreen(
                    viewModel = viewModel,
                    onBack = { goBack() },
                    onNavigateToEmployeeProfile = { id ->
                        selectedEmployeeId = id
                        navigateTo(Screen.EmployeeProfile)
                    }
                )

                Screen.EmployeeProfile -> EmployeeProfileScreen(
                    viewModel = viewModel,
                    employeeId = selectedEmployeeId ?: "",
                    onBack = {
                        selectedEmployeeId = null
                        goBack()
                    },
                    onNavigateToExpenses = { empId ->
                        adminExpensesInitialEmployeeId = empId
                        navigateTo(Screen.AdminExpenses)
                    },
                    onNavigateToTrips = { empId ->
                        navigateTo(Screen.BusinessTrips)
                    },
                    canViewExpensesAll = viewModel.canView("expenses_all"),  // 🆕
                    canViewTripsAll = viewModel.canView("business_trips_all")  // 🆕
                )

                Screen.Analytics -> AnalyticsScreen(
                    viewModel = viewModel,
                    onBack = { goBack() },
                    onNavigateToExpenses = { navigateTo(Screen.AdminExpenses) },
                    onNavigateToIncomes = { /* Можно добавить экран доходов */ },
                    onNavigateToProjects = { navigateTo(Screen.Projects) },
                    onNavigateToEmployees = { navigateTo(Screen.Employees) }
                )

                Screen.CostCalculation -> CostCalculationScreen(
                    viewModel = viewModel,
                    onBack = { goBack() }
                )

                Screen.MyExpenses -> EmployeeExpensesScreen(
                    viewModel = viewModel,
                    userId = user?.id ?: "",
                    isAdminView = false,
                    canViewAll = viewModel.canView("expenses_all"),  // 🆕
                    onBack = { goBack() }
                )

                Screen.AdminExpenses -> AdminExpensesScreen(
                    viewModel = viewModel,
                    initialEmployeeId = adminExpensesInitialEmployeeId ?: "",
                    onBack = {
                        adminExpensesInitialEmployeeId = null
                        goBack()
                    }
                )

                Screen.BusinessTrips -> BusinessTripsScreen(
                    viewModel = viewModel,
                    userId = user?.id ?: "",
                    isAdminView = false,
                    canViewAll = viewModel.canView("business_trips_all"),
                    onBack = { goBack() }
                )

                Screen.Notifications -> NotificationsScreen(
                    viewModel = viewModel,
                    onBack = { goBack() },
                    initialPayload = PendingNavigationHolder.get()?.second  // передаём payload для автооткрытия
                )

                Screen.EmployeeStats -> EmployeeStatsScreen(
                    viewModel = viewModel,
                    userId = user?.id ?: "",
                    onBack = { goBack() }
                )
            }
        }
    }

    // 🔥 Диалог отпуска (если вызван с Главной)
    if (showVacationDialog && selectedScreen == Screen.Home) {
        val vacations by viewModel.vacations.collectAsState() // 🆕 Получаем список
        VacationPickerDialog(
            existingVacations = vacations, // 🆕 Передаём
            onDismiss = { showVacationDialog = false },
            onConfirm = { start, end ->
                viewModel.addVacation(start, end)
                showVacationDialog = false
            }
        )
    }
}

@Composable
fun SyncIndicator(viewModel: TimesheetViewModel) {
    val pendingCount by viewModel.pendingSyncCount.collectAsState()
    val isOnline by NetworkMonitor.isOnline.collectAsState()

    if (pendingCount > 0) {
        Row(
            modifier = Modifier
                .background(
                    if (isOnline) Color(0xFF4CAF50).copy(alpha = 0.2f)
                    else Color(0xFFFFA000).copy(alpha = 0.2f),
                    RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isOnline) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF4CAF50)
                )
            } else {
                Icon(
                    Icons.Default.CloudOff,
                    null,
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFFFFA000)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "$pendingCount",
                style = MaterialTheme.typography.labelSmall,
                color = if (isOnline) Color(0xFF2E7D32) else Color(0xFFE65100)
            )
        }
    }
}