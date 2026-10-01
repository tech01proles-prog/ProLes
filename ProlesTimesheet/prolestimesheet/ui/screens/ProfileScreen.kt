package com.example.prolestimesheet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.example.prolestimesheet.model.User
import com.example.prolestimesheet.ui.theme.*
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.math.roundToInt

enum class StatsPeriod { MONTH, ALL }

@Composable
fun ProfileScreen(
    user: User?,
    viewModel: TimesheetViewModel,
    onLogout: () -> Unit,
    onNavigateToMyExpenses: () -> Unit,
    onNavigateToMyTrips: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToNotificationSettings: () -> Unit,
    onNavigateToMyTickets: () -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToExpensesIncomes: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    val expenses by viewModel.expenses.collectAsState()
    val incomes by viewModel.incomes.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val unreadCount by viewModel.unreadCount.collectAsState()
    var statsPeriod by remember { mutableStateOf(StatsPeriod.MONTH) }

    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val monthNames = listOf(
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
    )
    val monthLabel = monthNames[today.monthNumber - 1]
    val myEntries = entries.filter { it.userId == user?.id }
    val myExpenses = expenses.filter { it.userId == user?.id }
    val myIncomes = incomes.filter { it.userId == user?.id }

    val periodEntries = if (statsPeriod == StatsPeriod.MONTH) {
        myEntries.filter { it.date.year == today.year && it.date.monthNumber == today.monthNumber }
    } else myEntries
    val periodExpenses = if (statsPeriod == StatsPeriod.MONTH) {
        myExpenses.filter { it.date.year == today.year && it.date.monthNumber == today.monthNumber }
    } else myExpenses
    val periodIncomes = if (statsPeriod == StatsPeriod.MONTH) {
        myIncomes.filter { it.date.year == today.year && it.date.monthNumber == today.monthNumber }
    } else myIncomes

    val hours = periodEntries.sumOf { it.hours.toDouble() }
    val workDays = periodEntries.map { it.date }.distinct().size
    val expenseAmount = periodExpenses.sumOf { it.amount }
    val incomeAmount = periodIncomes.sumOf { it.amount }
    val avgHours = if (workDays > 0) hours / workDays else 0.0
    val receiptRate = if (periodExpenses.isNotEmpty()) {
        periodExpenses.count { it.receiptSubmitted }.toDouble() / periodExpenses.size
    } else 1.0
    val rhythm = (avgHours / 8.0 * 100.0).coerceIn(0.0, 100.0)
    val withoutReceipt = periodExpenses.count { !it.receiptSubmitted }
    val tripCount = viewModel.trips.value.count { it.userId == user?.id }
    val firstName = user?.firstName?.ifBlank { user.name }?.ifBlank { "Сотрудник" } ?: "Сотрудник"
    val fullName = listOf(user?.firstName, user?.lastName, user?.middleName)
        .filter { !it.isNullOrBlank() }.joinToString(" ")
        .ifBlank { firstName }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ProlesCanvas)
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Профиль", style = MaterialTheme.typography.headlineMedium)
                Text(user?.position?.ifBlank { "Сотрудник" } ?: "Сотрудник", style = MaterialTheme.typography.bodySmall, color = ProlesMuted)
            }
            IconButton(onClick = onNavigateToNotificationSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Настройки")
            }
            Surface(modifier = Modifier.size(40.dp), shape = CircleShape, color = ProlesPrimarySoft) {
                Box(contentAlignment = Alignment.Center) {
                    Text(firstName.take(1).uppercase(), color = ProlesPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        }

        ProlesCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.size(52.dp), shape = CircleShape, color = ProlesPrimarySoft) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, null, tint = ProlesPrimary, modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(fullName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        (user?.role ?: "employee").replaceFirstChar { it.uppercase() } +
                                " · " + (user?.position?.ifBlank { "Без должности" } ?: "Без должности"),
                        style = MaterialTheme.typography.bodySmall,
                        color = ProlesMuted
                    )
                }
                Surface(shape = CircleShape, color = ProlesPrimarySoft) {
                    Text("В штате", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = ProlesPrimary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        ProlesSectionTitle(title = "Статистика")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = statsPeriod == StatsPeriod.MONTH,
                onClick = { statsPeriod = StatsPeriod.MONTH },
                modifier = Modifier.weight(1f),
                label = { Text("Месяц") },
                leadingIcon = { Icon(Icons.Default.DateRange, null, modifier = Modifier.size(16.dp)) }
            )
            FilterChip(
                selected = statsPeriod == StatsPeriod.ALL,
                onClick = { statsPeriod = StatsPeriod.ALL },
                modifier = Modifier.weight(1f),
                label = { Text("Всё время") },
                leadingIcon = { Icon(Icons.Default.AllInclusive, null, modifier = Modifier.size(16.dp)) }
            )
        }

        ProlesCard(containerColor = ProlesPrimary) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Личная аналитика", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (statsPeriod == StatsPeriod.MONTH) monthLabel else "За всё время",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.14f)) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(rhythm.roundToInt().toString() + "%", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text("ритм", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalyticsValue("Отработано", "%.0f ч".format(hours), Modifier.weight(1f))
                AnalyticsValue("Расходы", "%.0f ₽".format(expenseAmount), Modifier.weight(1f))
                AnalyticsValue("Доходы", "%.0f ₽".format(incomeAmount), Modifier.weight(1f))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProlesMetricCard(Icons.Default.AccessTime, "Часы", "%.1f ч".format(hours), "за период", ProlesPrimary, Modifier.weight(1f))
            ProlesMetricCard(Icons.Default.CalendarToday, "Дни", workDays.toString(), "с записями", ProlesSecondary, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProlesMetricCard(Icons.Default.ReceiptLong, "Расходы", "%.0f ₽".format(expenseAmount), periodExpenses.size.toString() + " операций", ProlesExpense, Modifier.weight(1f))
            ProlesMetricCard(Icons.AutoMirrored.Filled.TrendingUp, "Доходы", "%.0f ₽".format(incomeAmount), periodIncomes.size.toString() + " операций", ProlesPrimary, Modifier.weight(1f))
        }

        ProlesCard(containerColor = ProlesSecondarySoft.copy(alpha = 0.55f)) {
            Text("Контроль документов", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                if (withoutReceipt > 0) withoutReceipt.toString() + " расхода без подтверждающего документа"
                else (receiptRate * 100).roundToInt().toString() + "% расходов подтверждено",
                style = MaterialTheme.typography.bodySmall,
                color = ProlesMuted
            )
        }

        ProlesSectionTitle(title = "Быстрый доступ")
        ProlesQuickAction(Icons.AutoMirrored.Filled.ReceiptLong, "Расходы и доходы", myExpenses.size.plus(myIncomes.size).toString() + " операций", ProlesExpense, onNavigateToExpensesIncomes)
        ProlesQuickAction(Icons.Default.Train, "Командировки", tripCount.toString() + " поездок", ProlesSecondary, onNavigateToMyTrips)
        ProlesQuickAction(Icons.Default.NotificationsNone, "Уведомления", if (unreadCount > 0) unreadCount.toString() + " непрочитанных" else "Все прочитаны", ProlesSecondary, onNavigateToNotifications, badge = unreadCount)
        ProlesQuickAction(Icons.Default.ConfirmationNumber, "Мои билеты", "Документы поездок", ProlesPrimary, onNavigateToMyTickets)
        ProlesQuickAction(Icons.Default.Analytics, "Статистика", "Доходы, расходы и активность", ProlesSecondary, onNavigateToStats)
        ProlesQuickAction(Icons.Default.ReceiptLong, "Мои расходы", "Чеки и операции", ProlesExpense, onNavigateToMyExpenses)

        ProlesSecondaryButton(
            text = "Настройки уведомлений",
            onClick = onNavigateToNotificationSettings,
            icon = Icons.Default.Settings
        )

        ProlesDestructiveButton(
            text = "Выйти из аккаунта",
            onClick = onLogout,
            icon = Icons.AutoMirrored.Filled.Logout
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun AnalyticsValue(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text(title, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.titleSmall)
    }
}
