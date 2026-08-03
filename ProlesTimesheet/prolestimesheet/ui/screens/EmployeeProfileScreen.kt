package com.example.prolestimesheet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
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
import androidx.compose.ui.unit.sp
import com.example.prolestimesheet.model.User
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import com.example.prolestimesheet.ui.components.NoAccessView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.FlowRow
import com.example.prolestimesheet.model.Project
import com.example.prolestimesheet.network.SalaryComponentDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeeProfileScreen(
    viewModel: TimesheetViewModel,
    employeeId: String,
    onBack: () -> Unit,
    onNavigateToExpenses: (String) -> Unit,
    onNavigateToTrips: (String) -> Unit = {},
    canViewExpensesAll: Boolean = false,  // 🆕
    canViewTripsAll: Boolean = false,     // 🆕
    modifier: Modifier = Modifier
) {
    val employees by viewModel.employees.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val expenses by viewModel.expenses.collectAsState()
    val trips by viewModel.trips.collectAsState()
    val projects by viewModel.projects.collectAsState()  //
    val employee = employees.firstOrNull { it.id == employeeId }

    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val currentYear = today.year
    val currentMonth = today.monthNumber

// 🔹 Фильтрация за текущий КАЛЕНДАРНЫЙ месяц
    val monthEntries = entries.filter {
        it.userId == employeeId &&
                it.date.year == currentYear &&
                it.date.monthNumber == currentMonth
    }

    val monthExpenses = expenses.filter {
        it.userId == employeeId &&
                it.date.year == currentYear &&
                it.date.monthNumber == currentMonth
    }

    val monthTrips = trips.filter {
        it.userId == employeeId &&
                it.date.year == currentYear &&
                it.date.monthNumber == currentMonth
    }

    val totalHours = monthEntries.sumOf { it.hours.toDouble() }
    val workDays = monthEntries.map { it.date }.distinct().size
    val totalExpenses = monthExpenses.sumOf { it.amount }

    // 🔹 Текущая дата и календарный месяц
    val monthDisplayName = Month.of(currentMonth)
        .getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru"))
        .replaceFirstChar { it.uppercaseChar() }

    val expensesWithReceipt = monthExpenses.count { it.receiptSubmitted }
    val expensesWithoutReceipt = monthExpenses.count { !it.receiptSubmitted }
    val expensesWithPhoto = monthExpenses.count { it.hasReceiptPhoto }
    val totalExpensesCount = expensesWithReceipt + expensesWithoutReceipt

    // 🔹 Разбивка расходов по валютам
    val expensesByCurrency = monthExpenses
        .groupBy { it.currency }
        .mapValues { (_, list) -> list.sumOf { it.amount } }
        .filterValues { it > 0.0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Профиль сотрудника") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { padding ->
        // 🔥 Проверка прав: если нет canView — показываем экран "Нет доступа"
        if (!viewModel.canView("employees")) {
            NoAccessView(onBack = onBack)
            return@Scaffold
        }
        Column(
            modifier = modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ════════════════════════════════════════════════════
            // 🎨 HERO-карточка с градиентом
            // ════════════════════════════════════════════════════
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF667eea),
                                Color(0xFF764ba2)
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Аватар (инициалы в круге)
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(Color.White.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        val initials = buildInitials(employee)
                        Text(
                            text = initials,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = employee?.name?.ifBlank { null }
                                ?: buildFullName(employee)
                                ?: "Неизвестный",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = employee?.position?.ifBlank { "Должность не указана" }
                                ?: "Должность не указана",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // ════════════════════════════════════════════════════
            // 📊 Статистика за календарный месяц
            // ════════════════════════════════════════════════════
            Text(
                "📊 Статистика за $monthDisplayName $currentYear",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // 🔹 Строка 1: Часы + Дни
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStatCard(
                    icon = Icons.Default.AccessTime,
                    title = "Часы",
                    value = "${"%.1f".format(totalHours)} ч.",
                    subtitle = "отработано",
                    color = Color(0xFF2E7D32),
                    modifier = Modifier.weight(1f)
                )
                MiniStatCard(
                    icon = Icons.Default.CalendarToday,
                    title = "Дни",
                    value = "$workDays",
                    subtitle = "с записями",
                    color = Color(0xFF0277BD),
                    modifier = Modifier.weight(1f)
                )
            }

            // 🔹 Строка 2: Расходы + Среднее
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStatCard(
                    icon = Icons.Default.Receipt,
                    title = "Расходы",
                    value = "${"%.0f".format(totalExpenses)} ₽",
                    subtitle = if (expensesByCurrency.isEmpty()) "нет расходов"
                    else expensesByCurrency.entries.take(2).joinToString(" • ") { (currency, amount) ->
                        "${"%.0f".format(amount)} ${currencySymbol(currency)}"
                    } + if (expensesByCurrency.size > 2) " +${expensesByCurrency.size - 2}" else "",
                    color = Color(0xFFFF6F00),
                    modifier = Modifier.weight(1f)
                )
                // 🔹 Карточка командировок за месяц
                MiniStatCard(
                    icon = Icons.Default.Train,
                    title = "Командировки",
                    value = "${monthTrips.size}",
                    subtitle = "за месяц",
                    color = Color(0xFF5C6BC0),
                    modifier = Modifier.weight(1f)
                )
            }

            // 🔹 Строка 3: Чеки (статус сдачи)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStatCard(
                    icon = Icons.Default.Receipt,
                    title = "Чеки сданы",
                    value = "$expensesWithReceipt",
                    subtitle = "из $totalExpensesCount",
                    color = Color(0xFF2E7D32),
                    modifier = Modifier.weight(1f)
                )
                MiniStatCard(
                    icon = Icons.Default.Warning,
                    title = "Без чеков",
                    value = "$expensesWithoutReceipt",
                    subtitle = if (expensesWithoutReceipt > 0) "⚠ требуют внимания" else "✓ всё сдано",
                    color = if (expensesWithoutReceipt > 0) Color(0xFFC62828) else Color(0xFF2E7D32),
                    modifier = Modifier.weight(1f)
                )
            }


            // ═══════════════════════════════════════════════════════════
            // 💰 Компоненты зарплаты
            // ═══════════════════════════════════════════════════════════
            val salaryComponents by viewModel.salaryComponents.collectAsState()
            var showAddComponentDialog by remember { mutableStateOf(false) }

            LaunchedEffect(employeeId) {
                viewModel.loadSalaryComponents(employeeId)
            }

            Text(
                "💰 Компоненты зарплаты",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (salaryComponents.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Компоненты не настроены",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Добавьте оклад, почасовую или сдельную оплату",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                salaryComponents.forEach { comp ->
                    val type = comp.type
                    val amount = (comp.amount as? Number)?.toDouble() ?: 0.0
                    val ratePerHour = (comp.ratePerHour as? Number)?.toDouble()
                    val ratePerUnit = (comp.ratePerUnit as? Number)?.toDouble()
                    val projectId = comp.projectId
                    val description = comp.description
                    val componentId = comp.id

                    val projectName = projectId?.let { pid ->
                        projects.find { it.id == pid }?.name
                    }

                    // 🔥 Рассчитываем реальную сумму для HOURLY/PIECE
                    val calculatedAmount = when (type) {
                        "HOURLY" -> {
                            val hours = monthEntries
                                .filter { projectId == null || it.projectId == projectId }
                                .sumOf { it.hours.toDouble() }
                            (ratePerHour ?: 0.0) * hours
                        }
                        "PIECE" -> {
                            val entryCount = monthEntries
                                .filter { (projectId == null || it.projectId == projectId) && it.hours > 0f }
                                .size
                            if (entryCount > 0) { (ratePerUnit ) } else { 0.0 }
                        }
                        else -> amount
                    }

                    val (icon, label, color) = when (type) {
                        "FIXED" -> Triple("💵", "Оклад", Color(0xFF2E7D32))
                        "HOURLY" -> Triple("⌚", "Почасовая", Color(0xFF0277BD))
                        "PIECE" -> Triple("📋", "Сдельная", Color(0xFF6A1B9A))
                        "BONUS" -> Triple("🎁", "Премия", Color(0xFFFF6F00))
                        else -> Triple("❓", type, Color.Gray)
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = color.copy(alpha = 0.08f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(icon, fontSize = 24.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                if (projectName != null) {
                                    Text(
                                        "📁 $projectName",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (description.isNotBlank()) {
                                    Text(
                                        description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                if (ratePerHour != null && type == "HOURLY") {
                                    val hours = monthEntries
                                        .filter { projectId == null || it.projectId == projectId }
                                        .sumOf { it.hours.toDouble() }
                                    Text(
                                        "Ставка: ${"%.2f".format(ratePerHour)} ₽/час × ${"%.1f".format(hours)} ч",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = color
                                    )
                                }
                                if (ratePerUnit != null && type == "PIECE") {
                                    Text(
                                        "Ставка: ${"%.2f".format(ratePerUnit)} ₽/ед ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = color
                                    )
                                }
                            }
                            Text(
                                "${"%.2f".format(calculatedAmount)} ₽",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = color
                            )
                            if (viewModel.canEdit("payroll")) {
                                IconButton(
                                    onClick = { viewModel.deleteSalaryComponent(componentId, employeeId) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        "Удалить компонент",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (viewModel.canEdit("payroll")) {
                Button(
                    onClick = { showAddComponentDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Добавить компонент")
                }
            }

            // ═══════════════════════════════════════════════════════════
            // 🔘 Кнопки навигации (одинаковый размер)
            // ═══════════════════════════════════════════════════════════
            // Кнопка: Просмотреть все расходы
            if (canViewExpensesAll) {  // 🆕
                Button(
                    onClick = { onNavigateToExpenses(employeeId) },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ReceiptLong,
                        null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Расходы сотрудника",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            if (canViewTripsAll) {  // 🆕
                Button(
                    onClick = { onNavigateToTrips(employeeId) },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Icon(
                        Icons.Default.Train,
                        null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Командировки сотрудника",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            // Диалог добавления компонента
            if (showAddComponentDialog) {
                AddSalaryComponentDialog(
                    employeeId = employeeId,
                    projects = projects,
                    onDismiss = { showAddComponentDialog = false },
                    onConfirm = { component ->
                        viewModel.addSalaryComponent(component, employeeId)
                        showAddComponentDialog = false
                    }
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// 🔹 Вспомогательные компоненты
// ════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddSalaryComponentDialog(
    employeeId: String,
    projects: List<Project>,
    onDismiss: () -> Unit,
    onConfirm: (SalaryComponentDto) -> Unit
) {
    var type by remember { mutableStateOf("FIXED") }
    var amount by remember { mutableStateOf("") }
    var ratePerHour by remember { mutableStateOf("") }
    var ratePerUnit by remember { mutableStateOf("") }
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить компонент зарплаты") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Тип компонента", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "FIXED" to "💵 Оклад",
                        "HOURLY" to "⌚ Почасовая",
                        "PIECE" to "📋 Сдельная",
                        "BONUS" to "🎁 Премия"
                    ).forEach { (t, label) ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (type == t) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = if (type == t) {
                                {
                                    Icon(
                                        Icons.Default.Check,
                                        null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                if (type in listOf("HOURLY", "PIECE")) {
                    Text("Привязать к проекту (необязательно)", style = MaterialTheme.typography.labelMedium)
                    var expanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                selectedProjectId?.let { pid -> projects.find { it.id == pid }?.name } ?: "Все проекты",
                                maxLines = 1
                            )
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Все проекты") },
                                onClick = { selectedProjectId = null; expanded = false }
                            )
                            projects.filter { it.isActive }.forEach { proj ->
                                DropdownMenuItem(
                                    text = { Text(proj.name) },
                                    onClick = { selectedProjectId = proj.id; expanded = false }
                                )
                            }
                        }
                    }
                }

                when (type) {
                    "FIXED", "BONUS" -> {
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { if (it.all { c -> c.isDigit() || c == '.' || c == ',' }) amount = it.replace(',', '.') },
                            label = { Text("Сумма (₽)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }
                    "HOURLY" -> {
                        OutlinedTextField(
                            value = ratePerHour,
                            onValueChange = { if (it.all { c -> c.isDigit() || c == '.' || c == ',' }) ratePerHour = it.replace(',', '.') },
                            label = { Text("Ставка за час (₽)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }
                    "PIECE" -> {
                        OutlinedTextField(
                            value = ratePerUnit,
                            onValueChange = { if (it.all { c -> c.isDigit() || c == '.' || c == ',' }) ratePerUnit = it.replace(',', '.') },
                            label = { Text("Ставка за единицу (₽)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Описание (необязательно)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val today = kotlinx.datetime.Clock.System.todayIn(kotlinx.datetime.TimeZone.currentSystemDefault()).toString()
                    val component = com.example.prolestimesheet.network.SalaryComponentDto(
                        userId = employeeId,
                        type = type,
                        amount = amount.toDoubleOrNull() ?: 0.0,
                        projectId = selectedProjectId,
                        ratePerHour = if (type == "HOURLY" && ratePerHour.isNotBlank()) ratePerHour.toDoubleOrNull() else null,
                        ratePerUnit = if (type == "PIECE" && ratePerUnit.isNotBlank()) ratePerUnit.toDoubleOrNull() else null,
                        description = description,
                        effectiveFrom = today,
                        effectiveTo = null
                    )
                    onConfirm(component)
                },
                enabled = when (type) {
                    "FIXED", "BONUS" -> amount.isNotBlank()
                    "HOURLY" -> ratePerHour.isNotBlank()
                    "PIECE" -> ratePerUnit.isNotBlank()
                    else -> false
                }
            ) { Text("Добавить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun MiniStatCard(
    icon: ImageVector,
    title: String,
    value: String,
    subtitle: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(90.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon, null,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    fontWeight = FontWeight.Medium
                )
            }
            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// 🔹 Вспомогательные функции
// ════════════════════════════════════════════════════════════

private fun buildInitials(user: User?): String {
    if (user == null) return "?"
    val first = user.firstName.takeIf { it.isNotBlank() }?.firstOrNull()?.uppercaseChar()
    val last = user.lastName.takeIf { it.isNotBlank() }?.firstOrNull()?.uppercaseChar()
    return when {
        first != null && last != null -> "$first$last"
        first != null -> "$first"
        last != null -> "$last"
        else -> user.name.takeIf { it.isNotBlank() }?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    }
}

private fun buildFullName(user: User?): String? {
    if (user == null) return null
    val parts = listOf(user.lastName, user.firstName, user.middleName)
        .filter { it.isNotBlank() }
    return parts.joinToString(" ").ifBlank { null }
}

private fun currencySymbolFromString(code: String): String = when (code.uppercase()) {
    "RUB" -> "₽"
    "BYN" -> "Br"
    "USD" -> "$"
    "EUR" -> "€"
    else -> code
}