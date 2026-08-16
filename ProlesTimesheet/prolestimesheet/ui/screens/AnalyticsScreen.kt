package com.example.prolestimesheet.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.todayIn
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    viewModel: TimesheetViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToExpenses: (() -> Unit)? = null,
    onNavigateToIncomes: (() -> Unit)? = null,
    onNavigateToProjects: (() -> Unit)? = null,
    onNavigateToEmployees: (() -> Unit)? = null
) {
    val user by viewModel.user.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val employees by viewModel.employees.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val expenses by viewModel.expenses.collectAsState()
    val trips by viewModel.trips.collectAsState()
    val incomes by viewModel.incomes.collectAsState()  // 🆕

    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val currentMonth = YearMonth.of(today.year, today.monthNumber)
    val previousMonth = currentMonth.minusMonths(1)

    // 🔥 ПРИНУДИТЕЛЬНАЯ ПЕРЕЗАГРУЗКА всех данных для админа
    LaunchedEffect(Unit) {
        if (user?.role in listOf("admin", "director", "superadmin")) {
            viewModel.repository.loadAllEntries()
            viewModel.repository.loadAllExpenses()
        }
    }

    // 📊 ВСЕ записи за текущий/прошлый месяц (без фильтра по userId!)
    val currentMonthEntries = entries.filter {
        it.date.year == currentMonth.year && it.date.monthNumber == currentMonth.monthValue
    }
    val previousMonthEntries = entries.filter {
        it.date.year == previousMonth.year && it.date.monthNumber == previousMonth.monthValue
    }
    val currentMonthExpenses = expenses.filter {
        it.date.year == currentMonth.year && it.date.monthNumber == currentMonth.monthValue
    }
    val previousMonthExpenses = expenses.filter {
        it.date.year == previousMonth.year && it.date.monthNumber == previousMonth.monthValue
    }

    // 📈 Метрики
    val totalHoursCurrent = currentMonthEntries.sumOf { it.hours.toDouble() }
    val totalHoursPrevious = previousMonthEntries.sumOf { it.hours.toDouble() }
    val hoursDelta = totalHoursCurrent - totalHoursPrevious

    val totalExpensesCurrent = currentMonthExpenses.sumOf { it.amount }
    val totalExpensesPrevious = previousMonthExpenses.sumOf { it.amount }
    val expensesDelta = totalExpensesCurrent - totalExpensesPrevious

    // 🆕 Доходы за текущий/прошлый месяц (только ручные доходы, без зарплаты)
    val currentMonthIncomes = incomes.filter {
        it.date.year == currentMonth.year && it.date.monthNumber == currentMonth.monthValue
    }
    val previousMonthIncomes = incomes.filter {
        it.date.year == previousMonth.year && it.date.monthNumber == previousMonth.monthValue
    }
    val totalIncomesCurrent = currentMonthIncomes.sumOf { it.amount }
    val totalIncomesPrevious = previousMonthIncomes.sumOf { it.amount }
    val incomesDelta = totalIncomesCurrent - totalIncomesPrevious
    val incomesDaysDelta = currentMonthIncomes.size - previousMonthIncomes.size

    // 🆕 Топ проектов по доходам
    val topProjectsByIncome = currentMonthIncomes
        .groupBy { it.projectId to it.projectName }
        .map { (_, projIncomes) ->
            Triple(
                projIncomes.first().projectName.ifBlank { "Без проекта" },
                projIncomes.sumOf { it.amount },
                projIncomes.first().projectId
            )
        }
        .sortedByDescending { it.second }
        .take(5)

    // 🆕 Доходы по валютам
    val incomesByCurrency = currentMonthIncomes
        .groupBy { it.currency }
        .mapValues { (_, list) -> list.sumOf { it.amount } }
        .filterValues { it > 0 }

    val activeProjects = projects.count { it.isActive }

    // 🔥 Топ-5 сотрудников по часам (ВСЕ сотрудники)
    val topEmployees = currentMonthEntries
        .groupBy { it.userId }
        .map { (userId, userEntries) ->
            val emp = employees.find { it.id == userId }
            Triple(emp?.name ?: "Неизвестный", userEntries.sumOf { it.hours.toDouble() }, userId)
        }
        .sortedByDescending { it.second }
        .take(5)

    // 🔥 Топ-5 проектов по часам (ВСЕ проекты)
    val topProjects = currentMonthEntries
        .groupBy { it.projectId }
        .map { (projectId, projEntries) ->
            val proj = projects.find { it.id == projectId }
            Triple(proj?.name ?: "Без проекта", projEntries.sumOf { it.hours.toDouble() }, projectId)
        }
        .sortedByDescending { it.second }
        .take(5)

    // Расходы по типам (ВСЕ)
    val expensesByType = currentMonthExpenses
        .groupBy { if (it.type == "ROAD") "🚗 Дорога" else "📦 ${it.name.ifBlank { "Другое" }}" }
        .mapValues { (_, list) -> list.sumOf { it.amount } }
        .toList()
        .sortedByDescending { it.second }

    // Расходы по валютам (ВСЕ)
    val expensesByCurrency = currentMonthExpenses
        .groupBy { it.currency }
        .mapValues { (_, list) -> list.sumOf { it.amount } }
        .filterValues { it > 0 }

    // Командировки за месяц (ВСЕ)
    val monthTrips = trips.filter {
        it.date.year == currentMonth.year && it.date.monthNumber == currentMonth.month.number
    }

    // Топ городов (ВСЕ)
    val topCities = monthTrips
        .filter { it.city.isNotBlank() }
        .groupBy { it.city }
        .map { (city, tripsList) -> Pair(city, tripsList.size) }
        .sortedByDescending { it.second }
        .take(5)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("📊 Аналитика", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${currentMonth.month} ${currentMonth.year} • ${entries.size} записей • ${expenses.size} расходов",
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
            // 📊 KPI карточки (сетка 2×2)
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KpiCard(
                        icon = Icons.AutoMirrored.Filled.TrendingUp,
                        title = "Доходы",
                        value = "%.0f".format(totalIncomesCurrent),
                        subtitle = "₽ за месяц",
                        delta = incomesDelta,
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToIncomes
                    )
                    KpiCard(
                        icon = Icons.Default.AttachMoney,
                        title = "Расходы",
                        value = "%.0f".format(totalExpensesCurrent),
                        subtitle = "₽ за месяц",
                        delta = expensesDelta,
                        color = Color(0xFFFF2600),
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToExpenses
                    )
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KpiCard(
                        icon = Icons.Default.AccessTime,
                        title = "Часы",
                        value = "%.0f".format(totalHoursCurrent),
                        subtitle = "ч. за месяц",
                        delta = hoursDelta,
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.weight(1f)
                    )
                    KpiCard(
                        icon = Icons.Default.Receipt,
                        title = "Записей дох.",
                        value = "${currentMonthIncomes.size}",
                        subtitle = "за месяц",
                        delta = incomesDaysDelta.toDouble(),
                        color = Color(0xFF388E3C),
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToIncomes
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KpiCard(
                        icon = Icons.Default.Folder,
                        title = "Проекты",
                        value = "$activeProjects",
                        subtitle = "активных",
                        delta = null,
                        color = Color(0xFF0277BD),
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToProjects
                    )
                    KpiCard(
                        icon = Icons.Default.People,
                        title = "Сотрудники",
                        value = "${employees.size}",
                        subtitle = "в системе",
                        delta = null,
                        color = Color(0xFF6A1B9A),
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToEmployees
                    )
                }
            }

            // 📊 Сравнение с прошлым месяцем
            item {
                Text("📈 Сравнение с прошлым месяцем",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        ComparisonRow("Часы", totalHoursCurrent, totalHoursPrevious, "ч.")
                        Spacer(Modifier.height(8.dp))
                        ComparisonRow("Расходы", totalExpensesCurrent, totalExpensesPrevious, "₽")
                        Spacer(Modifier.height(8.dp))
                        ComparisonRow("Доходы", totalIncomesCurrent, totalIncomesPrevious, "₽")
                        Spacer(Modifier.height(8.dp))
                        ComparisonRow("Записей",
                            currentMonthEntries.size.toDouble(),
                            previousMonthEntries.size.toDouble(), "шт.")
                    }
                }
            }

            // 👥 Топ-5 сотрудников
            item {
                Text("👥 Топ сотрудников по часам",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (topEmployees.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text("Нет данных за этот месяц",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                val maxHours = topEmployees.maxOf { it.second }
                items(topEmployees) { (name, hours, _) ->
                    HorizontalBarCard(
                        label = name,
                        value = hours,
                        maxValue = maxHours,
                        displayValue = "${"%.1f".format(hours)} ч.",
                        color = Color(0xFF6A1B9A)
                    )
                }
            }

            // 📁 Топ-5 проектов
            item {
                Text("📁 Топ проектов по часам",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (topProjects.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text("Нет данных за этот месяц",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                val maxProjHours = topProjects.maxOf { it.second }
                items(topProjects) { (name, hours, _) ->
                    HorizontalBarCard(
                        label = name,
                        value = hours,
                        maxValue = maxProjHours,
                        displayValue = "${"%.1f".format(hours)} ч.",
                        color = Color(0xFF0277BD)
                    )
                }
            }

            // 💰 Расходы по категориям
            item {
                Text("💰 Расходы по категориям",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (expensesByType.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text("Нет расходов за этот месяц",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                val maxExpAmount = expensesByType.maxOf { it.second }
                items(expensesByType) { (type, amount) ->
                    HorizontalBarCard(
                        label = type,
                        value = amount,
                        maxValue = maxExpAmount,
                        displayValue = "${"%.0f".format(amount)} ₽",
                        color = Color(0xFFFF6F00)
                    )
                }
            }

            // 💱 Расходы по валютам
            if (expensesByCurrency.isNotEmpty()) {
                item {
                    Text("💱 Расходы по валютам",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            expensesByCurrency.forEach { (currency, amount) ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    Arrangement.SpaceBetween
                                ) {
                                    Text(currency, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${"%.2f".format(amount)} ${currencySymbol(currency)}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 🆕 💵 Топ проектов по доходам
            item {
                Text(
                    "💵 Топ проектов по доходам",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (topProjectsByIncome.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Нет доходов за этот месяц",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val maxIncomeAmount = topProjectsByIncome.maxOf { it.second }
                items(topProjectsByIncome) { (name, amount, _) ->
                    HorizontalBarCard(
                        label = name,
                        value = amount,
                        maxValue = maxIncomeAmount,
                        displayValue = "${"%.0f".format(amount)} ₽",
                        color = Color(0xFF2E7D32)
                    )
                }
            }

// 🆕 💱 Доходы по валютам
            if (incomesByCurrency.isNotEmpty()) {
                item {
                    Text(
                        "💱 Доходы по валютам",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            incomesByCurrency.forEach { (currency, amount) ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    Arrangement.SpaceBetween
                                ) {
                                    Text(currency, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${"%.2f".format(amount)} ${currencySymbol(currency)}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2E7D32)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 🚆 Командировки
            item {
                Text("🚆 Командировки",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text("Всего поездок:", style = MaterialTheme.typography.bodyLarge)
                            Text("${monthTrips.size}",
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text("Участников:", style = MaterialTheme.typography.bodyLarge)
                            Text("${monthTrips.flatMap { it.participants }.distinct().size}",
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 🏙 Топ городов
            if (topCities.isNotEmpty()) {
                item {
                    Text("🏙 Топ городов",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                items(topCities) { (city, count) ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF5C6BC0).copy(alpha = 0.08f)
                        )
                    ) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            Arrangement.SpaceBetween,
                            Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📍", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                Text(city, style = MaterialTheme.typography.bodyLarge)
                            }
                            Text("$count",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF5C6BC0))
                        }
                    }
                }
            }

            // 📊 Статусы проектов
            item {
                Text("📊 Проекты по статусам",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            item {
                val statuses = (projects.groupBy { it.status }.mapValues { it.value.size }).filter { it.key != "ACTIVE" }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        statuses.forEach { (status, count) ->
                            val (label, color) = when (status) {
                                "new" -> "🆕 Новый" to Color(0xFF2196F3)
                                "in_progress" -> "🔄 В работе" to Color(0xFFFF9800)
                                "completed" -> "✅ Завершён" to Color(0xFF4CAF50)
                                "cancelled" -> "❌ Отменён" to Color(0xFFF44336)
                                else -> status to Color.Gray
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                Arrangement.SpaceBetween
                            ) {
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                                Surface(
                                    color = color.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        "$count",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = color
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 📈 Общая статистика
            item {
                Text("📈 Общая статистика",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        StatRow("Всего записей", "${entries.size}")
                        StatRow("Всего часов",
                            "${"%.0f".format(entries.sumOf { it.hours.toDouble() })} ч.")
                        StatRow(
                            "Всего доходов",
                            "${"%.0f".format(incomes.sumOf { it.amount })} ₽"
                        )
                        StatRow("Всего расходов",
                            "${"%.0f".format(expenses.sumOf { it.amount })} ₽")
                        StatRow("Всего командировок", "${trips.size}")
                        StatRow("Активных проектов", "$activeProjects")
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 🔹 Вспомогательные компоненты
// ═══════════════════════════════════════════════════════════

@Composable
private fun KpiCard(
    icon: ImageVector, title: String, value: String, subtitle: String,
    delta: Double?, color: Color, modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))
    ) {
        Column(Modifier.padding(12.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.labelMedium,
                    color = color, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (delta != null) {
                val isPositive = delta >= 0
                val arrow = if (isPositive) "↑" else "↓"
                val deltaColor = if (isPositive) Color(0xFF2E7D32) else Color(0xFFC62828)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$arrow ${"%.1f".format(kotlin.math.abs(delta))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = deltaColor, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Text("vs прошлый", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HorizontalBarCard(
    label: String, value: Double, maxValue: Double, displayValue: String, color: Color
) {
    val progress = if (maxValue > 0) (value / maxValue).coerceIn(0.0, 1.0) else 0.0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.05f))
    ) {
        Column(Modifier.padding(12.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, maxLines = 1,
                    modifier = Modifier.weight(1f))
                Text(displayValue, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold, color = color)
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = 0.15f))
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(progress.toFloat()).fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(color.copy(alpha = 0.7f), color)
                            )
                        )
                )
            }
        }
    }
}

@Composable
private fun ComparisonRow(label: String, current: Double, previous: Double, unit: String) {
    val delta = current - previous
    val percent = if (previous > 0) (delta / previous * 100) else 0.0
    val isPositive = delta >= 0
    Column {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${"%.1f".format(current)} $unit",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                if (previous > 0) {
                    Spacer(Modifier.width(8.dp))
                    val arrow = if (isPositive) "↑" else "↓"
                    val color = if (isPositive) Color(0xFF2E7D32) else Color(0xFFC62828)
                    Text("$arrow${"%.1f".format(kotlin.math.abs(percent))}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = color, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

fun currencySymbol(code: String): String = when (code.uppercase()) {
    "RUB" -> "₽"
    "BYN" -> "Br"
    "USD" -> "$"
    "EUR" -> "€"
    else -> code
}