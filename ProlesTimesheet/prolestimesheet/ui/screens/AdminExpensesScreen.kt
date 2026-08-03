package com.example.prolestimesheet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.model.Expense
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn

// ═══════════════════════════════════════════════════════════
// 📊 Модель сортировки
// ═══════════════════════════════════════════════════════════
enum class ExpenseSortType(val label: String) {
    DATE_DESC("📅 Сначала новые"),
    DATE_ASC("📅 Сначала старые"),
    AMOUNT_DESC("💰 Сумма ↓"),
    AMOUNT_ASC("💰 Сумма ↑"),
    EMPLOYEE_ASC("👤 Сотрудник А-Я"),
    EMPLOYEE_DESC("👤 Сотрудник Я-А"),
    RECEIPT_YES_FIRST("✅ Сначала с чеком"),
    RECEIPT_NO_FIRST("❌ Сначала без чека"),
    PROJECT_ASC("📁 Проект А-Я")
}

// ═══════════════════════════════════════════════════════════
// 📅 Вспомогательные функции для работы с датами
// ═══════════════════════════════════════════════════════════
private fun LocalDate.toEpochMillis(): Long {
    val zoneId = java.time.ZoneId.systemDefault()
    return java.time.LocalDate.of(this.year, this.monthNumber, this.dayOfMonth)
        .atStartOfDay(zoneId).toInstant().toEpochMilli()
}

private fun Long.toKotlinxLocalDate(): LocalDate {
    val zoneId = java.time.ZoneId.systemDefault()
    val javaDate = java.time.Instant.ofEpochMilli(this).atZone(zoneId).toLocalDate()
    return LocalDate(javaDate.year, javaDate.monthValue, javaDate.dayOfMonth)
}

private fun currentMonthRange(): Pair<LocalDate, LocalDate> {
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val firstDay = LocalDate(today.year, today.monthNumber, 1)
    return firstDay to today
}

private fun previousMonthRange(): Pair<LocalDate, LocalDate> {
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val firstDayCurrent = LocalDate(today.year, today.monthNumber, 1)
    // ✅ ИСПРАВЛЕНО: используем DateTimeUnit.DAY
    val lastDayPrev = firstDayCurrent.minus(1, DateTimeUnit.DAY)
    val firstDayPrev = LocalDate(lastDayPrev.year, lastDayPrev.monthNumber, 1)
    return firstDayPrev to lastDayPrev
}

// ═══════════════════════════════════════════════════════════
// 🎛 Главный экран
// ═══════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminExpensesScreen(
    viewModel: TimesheetViewModel,
    initialEmployeeId: String? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val employees by viewModel.employees.collectAsState()
    val allExpenses by viewModel.expenses.collectAsState()
    val projects by viewModel.projects.collectAsState()

    // Состояния фильтра
    val (defaultStart, defaultEnd) = remember { currentMonthRange() }
    var selectedEmployeeIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedProjectIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var filterStartDate by remember { mutableStateOf<LocalDate?>(defaultStart) }
    var filterEndDate by remember { mutableStateOf<LocalDate?>(defaultEnd) }
    var sortType by remember { mutableStateOf(ExpenseSortType.DATE_DESC) }

    // Применение initialEmployeeId (один раз при входе из мини-профиля)
    var initialApplied by remember { mutableStateOf(false) }
    // 🔥 ПРИНУДИТЕЛЬНАЯ ПЕРЕЗАГРУЗКА всех записей для админа
    LaunchedEffect(Unit) {
        val user = viewModel.user.value
        if (user?.role in listOf("admin", "director", "superadmin")) {
            android.util.Log.d("AdminExpenses", "🔄 Загружаем все расходы и записи для админа")
            viewModel.repository.loadAllEntries()
            viewModel.repository.loadAllExpenses()
        }
        android.util.Log.d("AdminExpenses", "📊 Всего расходов: ${allExpenses.size}")
    }

    // Применение initialEmployeeId (один раз при входе из мини-профиля)
    LaunchedEffect(initialEmployeeId) {
        if (!initialApplied && !initialEmployeeId.isNullOrBlank()) {
            selectedEmployeeIds = setOf(initialEmployeeId)
            val (start, end) = currentMonthRange()
            filterStartDate = start
            filterEndDate = end
            sortType = ExpenseSortType.DATE_DESC
            initialApplied = true
        }
    }

    // Диалоги
    var showFilterDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    // Применяем фильтры (локальные val для Smart Cast)
    val filteredExpenses = remember(
        allExpenses, selectedEmployeeIds, selectedProjectIds,
        filterStartDate, filterEndDate
    ) {
        val start = filterStartDate
        val end = filterEndDate
        allExpenses.filter { expense ->
            val employeeMatch = selectedEmployeeIds.isEmpty() || expense.userId in selectedEmployeeIds
            val projectMatch = selectedProjectIds.isEmpty() || expense.projectId in selectedProjectIds
            val dateMatch = (start == null || expense.date >= start) &&
                    (end == null || expense.date <= end)
            employeeMatch && projectMatch && dateMatch
        }
    }

    // Применяем сортировку
    val sortedExpenses = remember(filteredExpenses, sortType, employees) {
        when (sortType) {
            ExpenseSortType.DATE_DESC -> filteredExpenses.sortedByDescending { it.date }
            ExpenseSortType.DATE_ASC -> filteredExpenses.sortedBy { it.date }
            ExpenseSortType.AMOUNT_DESC -> filteredExpenses.sortedByDescending { it.amount }
            ExpenseSortType.AMOUNT_ASC -> filteredExpenses.sortedBy { it.amount }
            ExpenseSortType.EMPLOYEE_ASC -> filteredExpenses.sortedBy { exp ->
                employees.find { it.id == exp.userId }?.name ?: ""
            }
            ExpenseSortType.EMPLOYEE_DESC -> filteredExpenses.sortedByDescending { exp ->
                employees.find { it.id == exp.userId }?.name ?: ""
            }
            ExpenseSortType.RECEIPT_YES_FIRST -> filteredExpenses.sortedByDescending { it.receiptSubmitted }
            ExpenseSortType.RECEIPT_NO_FIRST -> filteredExpenses.sortedBy { it.receiptSubmitted }
            ExpenseSortType.PROJECT_ASC -> filteredExpenses.sortedBy { it.projectName }
        }
    }

    // Статистика
    val totalAmount = sortedExpenses.sumOf { it.amount }
    val withReceipt = sortedExpenses.count { it.hasReceiptPhoto }
    val withoutReceipt = sortedExpenses.count { !it.receiptSubmitted }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Расходы сотрудников", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${sortedExpenses.size} из ${allExpenses.size} • ${employees.size} сотр. • ${projects.size} пр.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    // 🔍 Кнопка фильтра с badge
                    IconButton(onClick = { showFilterDialog = true }) {
                        BadgedBox(
                            badge = {
                                val hasActiveFilters = selectedEmployeeIds.isNotEmpty() ||
                                        selectedProjectIds.isNotEmpty() ||
                                        filterStartDate != defaultStart ||
                                        filterEndDate != defaultEnd
                                if (hasActiveFilters) {
                                    Badge { Text("•") }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.FilterList,
                                "Фильтр",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    // 🔃 Кнопка сортировки
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                Icons.Default.Sort,
                                "Сортировка",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            ExpenseSortType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = type.label,
                                            fontWeight = if (type == sortType) FontWeight.Bold else FontWeight.Normal,
                                            color = if (type == sortType) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        sortType = type
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (type == sortType) {
                                            Icon(
                                                Icons.Default.Check,
                                                null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // 🔹 Сводка
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatItem("Всего", "${"%.0f".format(totalAmount)} ₽", MaterialTheme.colorScheme.primary)
                    StatItem("С чеком", "$withReceipt", Color(0xFF2E7D32))
                    StatItem("Без чека", "$withoutReceipt", Color(0xFFC62828))
                }
            }

            // 🔹 Активные фильтры (чипы)
            val s = filterStartDate
            val e = filterEndDate
            if (selectedEmployeeIds.isNotEmpty() ||
                selectedProjectIds.isNotEmpty() ||
                s != defaultStart ||
                e != defaultEnd
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 🧑 Фильтр по сотрудникам
                    if (selectedEmployeeIds.isNotEmpty()) {
                        val names = selectedEmployeeIds.mapNotNull { id ->
                            employees.find { it.id == id }?.name?.split(" ")?.firstOrNull()
                        }
                        val label = when (names.size) {
                            1 -> names.first()
                            in 2..3 -> names.joinToString(", ")
                            else -> "${names.size} сотр."
                        }
                        FilterChip(
                            selected = true,
                            onClick = { selectedEmployeeIds = emptySet() },
                            label = { Text("👤 $label", maxLines = 1) },
                            trailingIcon = {
                                Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }

                    // 📁 Фильтр по проектам
                    if (selectedProjectIds.isNotEmpty()) {
                        val projNames = selectedProjectIds.mapNotNull { id ->
                            projects.find { it.id == id }?.name?.take(10)
                        }
                        val label = when (projNames.size) {
                            1 -> projNames.first()
                            in 2..2 -> projNames.joinToString(", ")
                            else -> "${projNames.size} пр."
                        }
                        FilterChip(
                            selected = true,
                            onClick = { selectedProjectIds = emptySet() },
                            label = { Text("📁 $label", maxLines = 1) },
                            trailingIcon = {
                                Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }

                    // 📅 Фильтр по дате
                    if (s != null || e != null) {
                        val dateLabel = buildString {
                            append("📅 ")
                            append(s?.let { "${it.dayOfMonth}.${it.monthNumber}" } ?: "...")
                            append(" — ")
                            append(e?.let { "${it.dayOfMonth}.${it.monthNumber}" } ?: "...")
                        }
                        FilterChip(
                            selected = true,
                            onClick = {
                                val (startDef, endDef) = currentMonthRange()
                                filterStartDate = startDef
                                filterEndDate = endDef
                            },
                            label = { Text(dateLabel, maxLines = 1) },
                            trailingIcon = {
                                Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }

            // 🔹 Заголовок таблицы для админа
            if (sortedExpenses.isNotEmpty()) {
                AdminExpensesHeader()
            }

            // 🔹 Список расходов
            if (sortedExpenses.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.ReceiptLong,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Нет расходов по выбранным фильтрам",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    items(sortedExpenses, key = { it.id }) { expense ->
                        val empName = employees.find { it.id == expense.userId }?.name ?: "Неизвестный"
                        val empShort = empName.split(" ").take(2).joinToString(" ")

                        AdminExpenseRow(
                            expense = expense,
                            employeeName = empShort,
                            onToggleReceipt = { newStatus ->
                                viewModel.toggleReceiptSubmitted(expense.id, newStatus)
                            },
                            onDelete = { viewModel.removeExpense(expense.id) }  // 🆕 ДОБАВЛЕНО
                        )
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // 🎛 Диалог фильтра
    // ═══════════════════════════════════════════════════════
    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = { Text("Фильтр расходов") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // ── Период ──
                    Text("Период", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = filterStartDate == currentMonthRange().first &&
                                    filterEndDate == currentMonthRange().second,
                            onClick = {
                                val (s, e) = currentMonthRange()
                                filterStartDate = s
                                filterEndDate = e
                            },
                            label = { Text("Текущий", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = filterStartDate == previousMonthRange().first &&
                                    filterEndDate == previousMonthRange().second,
                            onClick = {
                                val (s, e) = previousMonthRange()
                                filterStartDate = s
                                filterEndDate = e
                            },
                            label = { Text("Прошлый", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = filterStartDate == null && filterEndDate == null,
                            onClick = {
                                filterStartDate = null
                                filterEndDate = null
                            },
                            label = { Text("Всё", style = MaterialTheme.typography.labelSmall) }
                        )
                    }

                    // Ручной выбор дат
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showStartDatePicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                filterStartDate?.let { "С ${it.dayOfMonth}.${it.monthNumber}" } ?: "От",
                                maxLines = 1
                            )
                        }
                        OutlinedButton(
                            onClick = { showEndDatePicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                filterEndDate?.let { "По ${it.dayOfMonth}.${it.monthNumber}" } ?: "До",
                                maxLines = 1
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // ── Сотрудники ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Сотрудники", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                selectedEmployeeIds = employees.map { it.id }.toSet()
                            }) { Text("Все", style = MaterialTheme.typography.labelSmall) }
                            TextButton(onClick = { selectedEmployeeIds = emptySet() }) {
                                Text("Сброс", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    employees.sortedBy { it.name }.forEach { emp ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedEmployeeIds = if (emp.id in selectedEmployeeIds) {
                                        selectedEmployeeIds - emp.id
                                    } else {
                                        selectedEmployeeIds + emp.id
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = emp.id in selectedEmployeeIds,
                                onCheckedChange = { checked ->
                                    selectedEmployeeIds = if (checked) {
                                        selectedEmployeeIds + emp.id
                                    } else {
                                        selectedEmployeeIds - emp.id
                                    }
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(emp.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // ── Проекты ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Проекты", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                selectedProjectIds = projects.map { it.id }.toSet()
                            }) { Text("Все", style = MaterialTheme.typography.labelSmall) }
                            TextButton(onClick = { selectedProjectIds = emptySet() }) {
                                Text("Сброс", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    projects.sortedBy { it.name }.forEach { proj ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedProjectIds = if (proj.id in selectedProjectIds) {
                                        selectedProjectIds - proj.id
                                    } else {
                                        selectedProjectIds + proj.id
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = proj.id in selectedProjectIds,
                                onCheckedChange = { checked ->
                                    selectedProjectIds = if (checked) {
                                        selectedProjectIds + proj.id
                                    } else {
                                        selectedProjectIds - proj.id
                                    }
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                proj.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFilterDialog = false }) { Text("Применить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    selectedEmployeeIds = emptySet()
                    selectedProjectIds = emptySet()
                    val (s, e) = currentMonthRange()
                    filterStartDate = s
                    filterEndDate = e
                }) { Text("Сбросить всё") }
            }
        )
    }

    // ═══════════════════════════════════════════════════════
    // 📅 DatePicker для начала периода
    // ═══════════════════════════════════════════════════════
    if (showStartDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = filterStartDate?.toEpochMillis()
                ?: Clock.System.todayIn(TimeZone.currentSystemDefault()).toEpochMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        filterStartDate = it.toKotlinxLocalDate()
                    }
                    showStartDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) { Text("Отмена") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // ═══════════════════════════════════════════════════════
    // 📅 DatePicker для конца периода
    // ═══════════════════════════════════════════════════════
    if (showEndDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = filterEndDate?.toEpochMillis()
                ?: Clock.System.todayIn(TimeZone.currentSystemDefault()).toEpochMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        filterEndDate = it.toKotlinxLocalDate()
                    }
                    showEndDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) { Text("Отмена") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 🔹 Заголовок таблицы для админа
// ═══════════════════════════════════════════════════════════
@Composable
private fun AdminExpensesHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Дата • Проект • Сотрудник",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "ФОТО",
            modifier = Modifier.width(50.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Сумма",
            modifier = Modifier.width(80.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 🔹 Строка расхода для админа
// ═══════════════════════════════════════════════════════════
@Composable
private fun AdminExpenseRow(
    expense: Expense,
    employeeName: String,
    onToggleReceipt: (Boolean) -> Unit,
    onDelete: () -> Unit  // 🆕 ДОБАВЛЕНО
) {
    // 🆕 Зеленый фон, если есть фото чека
    val bgColor = when {
        expense.hasReceiptPhoto -> Color(0xFFE8F5E9)  // ✅ Зеленый
        else -> Color(0xFFFFEBEE)  // 🔴 Красный
    }
    val typeLabel = if (expense.type == "ROAD") "🚗 Дорога" else "📦 ${expense.name}"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 🔹 Левая часть: дата, проект, тип, сотрудник
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${expense.date} • $employeeName",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${expense.projectName} • $typeLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 🖼 Колонка ФОТО
            Box(
                modifier = Modifier.width(50.dp),
                contentAlignment = Alignment.Center
            ) {
                if (expense.hasReceiptPhoto) {
                    Surface(
                        color = Color(0xFF2E7D32).copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(
                            Icons.Default.Image,
                            "Фото загружено",
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(20.dp).padding(2.dp)
                        )
                    }
                } else {
                    Icon(
                        Icons.Default.HideImage,
                        "Фото нет",
                        tint = Color.Gray.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            // 💰 Сумма
            Text(
                text = "${"%.0f".format(expense.amount)} ${expense.currency}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(80.dp).padding(end = 4.dp),
                textAlign = TextAlign.End
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 🔹 Элемент сводки
// ═══════════════════════════════════════════════════════════
@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}