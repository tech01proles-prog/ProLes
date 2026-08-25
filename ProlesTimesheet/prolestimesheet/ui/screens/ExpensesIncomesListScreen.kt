package com.example.prolestimesheet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.model.Expense
import com.example.prolestimesheet.model.Income
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import java.time.YearMonth

// Объединённая модель для расходов и доходов
private data class ExpenseIncomeItem(
    val id: String,
    val isExpense: Boolean,
    val date: LocalDate,
    val amount: Double,
    val currency: String,
    val name: String,
    val projectName: String?,
    val userId: String
)

// Справочник типов расходов
private val EXPENSE_TYPE_LABELS = mapOf(
    "CONTRACTORS" to ("Подрядчики" to "👷"),
    "MATERIALS" to ("Материалы" to "🧱"),
    "EQUIPMENT" to ("Оборудование" to "🔧"),
    "TRANSPORT" to ("Транспорт Доп." to "🚚"),
    "ROAD" to ("Транспорт" to "🚗"),
    "MANAGER_COMMISSION" to ("Комиссия менеджеру" to "💼"),
    "FINES" to ("Штрафы" to "⚠️"),
    "CREDIT" to ("Кредит" to "🏦"),
    "OTHER" to ("Другое" to "📦")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesIncomesListScreen(
    viewModel: TimesheetViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val user by viewModel.user.collectAsState()
    val expenses by viewModel.expenses.collectAsState()
    val incomes by viewModel.incomes.collectAsState()
    
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val currentYearMonth = YearMonth.of(today.year, today.monthNumber)
    
    // Состояние для выбранного месяца
    var selectedYearMonth by remember { mutableStateOf(currentYearMonth) }
    var showMonthPicker by remember { mutableStateOf(false) }
    
    // Состояние для фильтрации и сортировки
    var filterType by remember { mutableStateOf("all") } // all, income, expense
    var sortBy by remember { mutableStateOf("date") } // date, amount, type
    var sortDescending by remember { mutableStateOf(true) }
    
    // Фильтруем данные по выбранному месяцу и типу
    val filteredExpenses = expenses.filter {
        val itemMonth = YearMonth.of(it.date.year, it.date.monthNumber)
        itemMonth == selectedYearMonth && (user?.role in listOf("admin", "director", "superadmin") || it.userId == user?.id)
    }
    
    val filteredIncomes = incomes.filter {
        val itemMonth = YearMonth.of(it.date.year, it.date.monthNumber)
        itemMonth == selectedYearMonth && (user?.role in listOf("admin", "director", "superadmin") || it.userId == user?.id)
    }
    
    val combinedList = mutableListOf<ExpenseIncomeItem>()
    
    filteredExpenses.forEach { exp ->
        combinedList.add(
            ExpenseIncomeItem(
                id = exp.id,
                isExpense = true,
                date = exp.date,
                amount = exp.amount,
                currency = exp.currency,
                name = if (exp.type == "ROAD") "🚗 Дорога" else exp.name.ifBlank { "Другое" },
                projectName = exp.projectName,
                userId = exp.userId
            )
        )
    }
    
    filteredIncomes.forEach { inc ->
        combinedList.add(
            ExpenseIncomeItem(
                id = inc.id,
                isExpense = false,
                date = inc.date,
                amount = inc.amount,
                currency = inc.currency,
                name = inc.name.ifBlank { "Доход" },
                projectName = inc.projectName,
                userId = inc.userId
            )
        )
    }
    
    // Применяем фильтрацию по типу
    val typedList = when (filterType) {
        "income" -> combinedList.filter { !it.isExpense }
        "expense" -> combinedList.filter { it.isExpense }
        else -> combinedList
    }
    
    // Применяем сортировку
    val sortedList = when (sortBy) {
        "amount" -> typedList.sortedWith(compareBy({ it.amount }, { it.date }))
        "type" -> typedList.sortedWith(compareBy({ it.isExpense }, { it.date }))
        else -> typedList.sortedByDescending { it.date }
    }.let { if (sortDescending && sortBy != "date") it.reversed() else it }
    
    // Итоги за месяц
    val totalExpenses = filteredExpenses.sumOf { it.amount }
    val totalIncomes = filteredIncomes.sumOf { it.amount }
    val balance = totalIncomes - totalExpenses
    
    var showAddExpenseDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("💰 Расходы/Доходы", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${selectedYearMonth.month} ${selectedYearMonth.year}",
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
                    // Кнопка выбора месяца
                    IconButton(onClick = { showMonthPicker = true }) {
                        Icon(Icons.Default.CalendarMonth, "Выбрать месяц")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddExpenseDialog = true },
                containerColor = Color(0xFFC62828)
            ) {
                Icon(Icons.Default.Add, "Добавить расход")
            }
        }
    ) { padding ->
        Column(
            modifier = modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Панель с итогами
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Доходы", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF2E7D32))
                            Text("%.0f ₽".format(totalIncomes), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Расходы", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC62828))
                            Text("%.0f ₽".format(totalExpenses), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Баланс", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "%.0f ₽".format(balance),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (balance >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
                        )
                    }
                }
            }
            
            // Фильтры и сортировка
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Фильтр по типу
                FilterChip(
                    selected = filterType == "all",
                    onClick = { filterType = "all" },
                    label = { Text("Все") }
                )
                FilterChip(
                    selected = filterType == "income",
                    onClick = { filterType = "income" },
                    label = { Text("Доходы") }
                )
                FilterChip(
                    selected = filterType == "expense",
                    onClick = { filterType = "expense" },
                    label = { Text("Расходы") }
                )
                
                Spacer(Modifier.weight(1f))
                
                // Сортировка
                IconButton(onClick = {
                    sortBy = when (sortBy) {
                        "date" -> "amount"
                        "amount" -> "type"
                        else -> "date"
                    }
                }) {
                    Icon(
                        when (sortBy) {
                            "amount" -> Icons.Default.AttachMoney
                            "type" -> Icons.Default.Label
                            else -> Icons.Default.CalendarToday
                        },
                        "Сортировка: $sortBy",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                
                IconButton(onClick = { sortDescending = !sortDescending }) {
                    Icon(
                        if (sortDescending) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        "Порядок",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            // Список
            if (sortedList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Нет данных за выбранный период", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sortedList) { item ->
                        ExpenseIncomeListItem(item)
                    }
                }
            }
        }
        
        // Диалог выбора месяца
        if (showMonthPicker) {
            MonthPickerDialog(
                currentYearMonth = selectedYearMonth,
                onMonthSelected = { selectedYearMonth = it; showMonthPicker = false },
                onDismiss = { showMonthPicker = false }
            )
        }
        
        // Диалог добавления расхода
        if (showAddExpenseDialog) {
            AddExpenseDialog(
                defaultDate = today,
                onDismiss = { showAddExpenseDialog = false },
                onSave = { type, name, amount, currency, comment ->
                    viewModel.addExpense(
                        projectId = "",
                        projectName = null,
                        date = today,
                        type = type,
                        name = name,
                        amount = amount,
                        currency = currency,
                        comment = comment
                    )
                    showAddExpenseDialog = false
                }
            )
        }
    }
}

@Composable
private fun ExpenseIncomeListItem(item: ExpenseIncomeItem) {
    val backgroundColor = if (item.isExpense) {
        Color(0xFFFFEBEE)
    } else {
        Color(0xFFE8F5E9)
    }
    val iconColor = if (item.isExpense) {
        Color(0xFFC62828)
    } else {
        Color(0xFF2E7D32)
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (item.isExpense) Icons.Default.RemoveCircle else Icons.Default.AddCircle,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(32.dp)
                )
                Column {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!item.projectName.isNullOrBlank()) {
                        Text(
                            "📁 ${item.projectName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "${item.date.dayOfMonth}.${item.date.monthNumber}.${item.date.year}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (item.isExpense) "-%.0f".format(item.amount) else "+%.0f".format(item.amount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = iconColor
                )
                Text(
                    item.currency,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MonthPickerDialog(
    currentYearMonth: YearMonth,
    onMonthSelected: (YearMonth) -> Unit,
    onDismiss: () -> Unit
) {
    var tempYearMonth by remember { mutableStateOf(currentYearMonth) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите месяц") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { tempYearMonth = tempYearMonth.minusMonths(1) }) {
                        Icon(Icons.Default.ChevronLeft, "Предыдущий месяц")
                    }
                    Text(
                        "${tempYearMonth.month} ${tempYearMonth.year}",
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = { tempYearMonth = tempYearMonth.plusMonths(1) }) {
                        Icon(Icons.Default.ChevronRight, "Следующий месяц")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onMonthSelected(tempYearMonth) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExpenseDialog(
    defaultDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: (type: String, name: String, amount: Double, currency: String, comment: String) -> Unit
) {
    var selectedType by remember { mutableStateOf("OTHER") }
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("RUB") }
    var comment by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("➕ Новый расход") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Тип расхода
                Text("Тип расхода", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                var typeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = !typeExpanded }
                ) {
                    val currentLabel = EXPENSE_TYPE_LABELS[selectedType]?.let { "${it.second} ${it.first}" } ?: selectedType
                    OutlinedTextField(
                        value = currentLabel,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        EXPENSE_TYPE_LABELS.forEach { (key, value) ->
                            val (label, icon) = value
                            DropdownMenuItem(
                                text = { Text("$icon $label") },
                                onClick = {
                                    selectedType = key
                                    typeExpanded = false
                                }
                            )
                        }
                    }
                }

                // Название (опционально)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название (опционально)") },
                    placeholder = { Text("Например: Цемент 50 мешков") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Сумма + валюта
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = {
                            if (it.isEmpty() || it.all { c -> c.isDigit() || c == '.' || c == ',' }) {
                                amount = it.replace(',', '.')
                            }
                        },
                        label = { Text("Сумма *") },
                        modifier = Modifier.weight(2f),
                        singleLine = true
                    )
                    var curExpanded by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { curExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(currency) }
                        DropdownMenu(expanded = curExpanded, onDismissRequest = { curExpanded = false }) {
                            listOf("RUB", "BYN", "USD", "EUR").forEach { cur ->
                                DropdownMenuItem(
                                    text = { Text(cur) },
                                    onClick = { currency = cur; curExpanded = false }
                                )
                            }
                        }
                    }
                }

                // Комментарий
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Комментарий") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val amountValue = amount.toDoubleOrNull() ?: 0.0
                    if (amountValue > 0) {
                        onSave(selectedType, name, amountValue, currency, comment)
                    }
                },
                enabled = (amount.toDoubleOrNull() ?: 0.0) > 0
            ) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
