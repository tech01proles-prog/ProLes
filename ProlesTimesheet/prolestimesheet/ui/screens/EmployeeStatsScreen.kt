package com.example.prolestimesheet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.DateTimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeeStatsScreen(
    viewModel: TimesheetViewModel,
    userId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val user by viewModel.user.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val expenses by viewModel.expenses.collectAsState()
    val incomes by viewModel.incomes.collectAsState()
    val projects by viewModel.projects.collectAsState()

    // Фильтрация только по данным текущего сотрудника
    val myEntries = remember(entries, userId) { entries.filter { it.userId == userId } }
    val myExpenses = remember(expenses, userId) { expenses.filter { it.userId == userId } }
    val myIncomes = remember(incomes, userId) { incomes.filter { it.userId == userId } }

    // Период (текущий месяц по умолчанию)
    var selectedPeriod by remember { mutableStateOf("month") }
    
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val cutoffDate = remember(selectedPeriod, today) {
        when (selectedPeriod) {
            "week" -> today.minus(7, DateTimeUnit.DAY)
            "month" -> LocalDate(today.year, today.monthNumber, 1)
            "year" -> LocalDate(today.year, 1, 1)
            else -> LocalDate(today.year, today.monthNumber, 1)
        }
    }

    // Фильтрация по периоду
    val periodEntries = remember(myEntries, cutoffDate) {
        myEntries.filter { it.date >= cutoffDate }
    }
    val periodExpenses = remember(myExpenses, cutoffDate) {
        myExpenses.filter { it.date >= cutoffDate }
    }
    val periodIncomes = remember(myIncomes, cutoffDate) {
        myIncomes.filter { it.date >= cutoffDate }
    }

    // KPI
    val totalHours = periodEntries.sumOf { it.hours.toDouble() }
    val totalExpensesAmount = periodExpenses.sumOf { it.amount }
    val totalIncomesAmount = periodIncomes.sumOf { it.amount }
    val workDays = periodEntries.map { it.date }.distinct().size
    val activeProjectsCount = periodEntries.map { it.projectId }.distinct().size

    // Расходы по типам
    val expensesByType = remember(periodExpenses) {
        periodExpenses
            .groupBy { if (it.type == "ROAD") "🚗 Дорога" else "📦 ${it.name.ifBlank { "Другое" }}" }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }
    }

    // Доходы по проектам
    val incomesByProject = remember(periodIncomes, projects) {
        periodIncomes
            .groupBy { it.projectId to it.projectName }
            .map { (proj, projIncomes) ->
                Triple(
                    proj.second.ifBlank { "Без проекта" },
                    projIncomes.sumOf { it.amount },
                    proj.first
                )
            }
            .sortedByDescending { it.second }
    }

    // Часы по проектам
    val hoursByProject = remember(periodEntries, projects) {
        periodEntries
            .groupBy { it.projectId }
            .map { (projectId, projEntries) ->
                val projName = projects.find { it.id == projectId }?.name ?: "Без проекта"
                Triple(projName, projEntries.sumOf { it.hours.toDouble() }, projectId)
            }
            .sortedByDescending { it.second }
    }

    // Расходы vs Доходы по проектам (для сравнения)
    val projectComparison = remember(periodIncomes, periodExpenses, projects) {
        val projectIds = (periodIncomes.map { it.projectId } + periodExpenses.map { it.projectId })
            .filterNotNull().distinct()
        
        projectIds.map { projectId ->
            val projectName = projects.find { it.id == projectId }?.name ?: "Без проекта"
            val income = periodIncomes.filter { it.projectId == projectId }.sumOf { it.amount }
            val expense = periodExpenses.filter { it.projectId == projectId }.sumOf { it.amount }
            Triple(projectName, income, expense)
        }.sortedByDescending { it.second }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("📊 Моя статистика", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${periodEntries.size} записей • ${periodExpenses.size} расходов",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Переключатель периода
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ) {
                        Row {
                            listOf("week" to "Неделя", "month" to "Месяц", "year" to "Год").forEach { (value, label) ->
                                Button(
                                    onClick = { selectedPeriod = value },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (selectedPeriod == value) 
                                            MaterialTheme.colorScheme.primary 
                                        else Color.Transparent
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    modifier = Modifier
                                ) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selectedPeriod == value) 
                                            MaterialTheme.colorScheme.onPrimary 
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // KPI карточки
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(
                        icon = Icons.Default.AccessTime,
                        title = "Часы",
                        value = "${"%.1f".format(totalHours)} ч.",
                        subtitle = "за период",
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        icon = Icons.Default.AttachMoney,
                        title = "Доходы",
                        value = "${"%.0f".format(totalIncomesAmount)} ₽",
                        subtitle = "за период",
                        color = Color(0xFF388E3C),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(
                        icon = Icons.Default.Receipt,
                        title = "Расходы",
                        value = "${"%.0f".format(totalExpensesAmount)} ₽",
                        subtitle = "за период",
                        color = Color(0xFFFF6F00),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        icon = Icons.Default.Work,
                        title = "Проекты",
                        value = "$activeProjectsCount",
                        subtitle = "активных",
                        color = Color(0xFF0277BD),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(
                        icon = Icons.Default.CalendarToday,
                        title = "Дни",
                        value = "$workDays",
                        subtitle = "с записями",
                        color = Color(0xFF5C6BC0),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        icon = Icons.Default.TrendingUp,
                        title = "Баланс",
                        value = "${"%.0f".format(totalIncomesAmount - totalExpensesAmount)} ₽",
                        subtitle = "доход - расход",
                        color = if (totalIncomesAmount >= totalExpensesAmount) Color(0xFF2E7D32) else Color(0xFFC62828),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Сравнение доходов и расходов по проектам
            if (projectComparison.isNotEmpty()) {
                item {
                    Text(
                        "💰 Доходы vs Расходы по проектам",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(projectComparison) { (name, income, expense) ->
                    ProjectComparisonCard(name, income, expense)
                }
            }

            // Доходы по проектам
            if (incomesByProject.isNotEmpty()) {
                item {
                    Text(
                        "📈 Доходы по проектам",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                val maxIncome = incomesByProject.maxOfOrNull { it.second } ?: 1.0
                items(incomesByProject) { (name, amount, _) ->
                    HorizontalBarCard(
                        label = name,
                        value = amount,
                        maxValue = maxIncome,
                        displayValue = "${"%.0f".format(amount)} ₽",
                        color = Color(0xFF388E3C)
                    )
                }
            }

            // Часы по проектам
            if (hoursByProject.isNotEmpty()) {
                item {
                    Text(
                        "⏱ Часы по проектам",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                val maxHours = hoursByProject.maxOfOrNull { it.second } ?: 1.0
                items(hoursByProject) { (name, hours, _) ->
                    HorizontalBarCard(
                        label = name,
                        value = hours,
                        maxValue = maxHours,
                        displayValue = "${"%.1f".format(hours)} ч.",
                        color = Color(0xFF2E7D32)
                    )
                }
            }

            // Расходы по типам
            if (expensesByType.isNotEmpty()) {
                item {
                    Text(
                        "💸 Расходы по категориям",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                val maxExpense = expensesByType.maxOfOrNull { it.second } ?: 1.0
                items(expensesByType) { (type, amount) ->
                    HorizontalBarCard(
                        label = type,
                        value = amount,
                        maxValue = maxExpense,
                        displayValue = "${"%.0f".format(amount)} ₽",
                        color = Color(0xFFFF6F00)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    title: String,
    value: String,
    subtitle: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.08f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.labelMedium, color = color)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProjectComparisonCard(
    name: String,
    income: Double,
    expense: Double
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            
            // Доходы
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("Доход:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF388E3C))
                Text("${"%.0f".format(income)} ₽", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF388E3C))
            }
            
            // Расходы
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("Расход:", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF6F00))
                Text("${"%.0f".format(expense)} ₽", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFFFF6F00))
            }
            
            // Баланс
            val balance = income - expense
            Spacer(Modifier.height(4.dp))
            Divider()
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("Баланс:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text(
                    "${"%.0f".format(balance)} ₽",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (balance >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
            }
        }
    }
}

@Composable
private fun HorizontalBarCard(
    label: String,
    value: Double,
    maxValue: Double,
    displayValue: String,
    color: Color
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(displayValue, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color)
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = (value / maxValue).coerceIn(0.0..1.0).toFloat(),
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}
