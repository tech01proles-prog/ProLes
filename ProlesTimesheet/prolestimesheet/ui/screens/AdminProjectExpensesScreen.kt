package com.example.prolestimesheet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.model.Expense
import com.example.prolestimesheet.model.Project
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * 💰 Экран управления расходами по проектам (для админа).
 * Поддерживает расширенный список типов расходов.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminProjectExpensesScreen(
    viewModel: TimesheetViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val projects by viewModel.projects.collectAsState()
    val expenses by viewModel.expenses.collectAsState()
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())

    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    val selectedProject = projects.find { it.id == selectedProjectId }
    val projectExpenses = expenses
        .filter { it.projectId == selectedProjectId }
        .sortedByDescending { it.date }

    val totalAmount = projectExpenses.sumOf { it.amount }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("💰 Расходы по проектам", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${projectExpenses.size} записей • ${"%.0f".format(totalAmount)} ₽",
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
        },
        floatingActionButton = {
            if (selectedProjectId != null) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, "Добавить расход")
                }
            }
        }
    ) { padding ->
        Column(modifier = modifier.padding(padding).fillMaxSize()) {
            // Выпадающий список проектов
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("📁 Выберите проект", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))

                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = selectedProject?.name ?: "— Выберите проект —",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            projects.filter { it.isActive }.forEach { proj ->
                                DropdownMenuItem(
                                    text = { Text(proj.name) },
                                    onClick = {
                                        selectedProjectId = proj.id
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Список расходов
            if (selectedProjectId == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(64.dp), tint = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        Text("Выберите проект для управления расходами")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (projectExpenses.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text("Расходов пока нет. Нажмите + чтобы добавить.", color = Color.Gray)
                            }
                        }
                    } else {
                        items(projectExpenses, key = { it.id }) { expense ->
                            ExtendedExpenseCard(
                                expense = expense,
                                onDelete = { viewModel.removeExpense(expense.id) },
                                onTypeChange = { newType ->
                                    viewModel.updateExpenseType(expense.id, newType)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Диалог добавления расхода
    if (showAddDialog && selectedProject != null) {
        AddExpenseDialog(
            project = selectedProject,
            defaultDate = today,
            onDismiss = { showAddDialog = false },
            onSave = { type, name, amount, currency, comment ->
                viewModel.addExpense(
                    projectId = selectedProject.id,
                    projectName = selectedProject.name,
                    date = today,
                    type = type,
                    name = name,
                    amount = amount,
                    currency = currency,
                    comment = comment
                )
                showAddDialog = false
            }
        )
    }
}

/**
 * 💳 Карточка расхода с возможностью смены типа
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtendedExpenseCard(
    expense: Expense,
    onDelete: () -> Unit,
    onTypeChange: (String) -> Unit
) {
    val typeInfo = EXPENSE_TYPE_LABELS[expense.type] ?: (expense.type to "📦")
    val (typeLabel, typeIcon) = typeInfo

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (expense.receiptSubmitted) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Тип с возможностью смены
                var expanded by remember { mutableStateOf(false) }
                Box {
                    AssistChip(
                        onClick = { expanded = true },
                        label = { Text(typeLabel) },
                        leadingIcon = { Text(typeIcon) }
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        EXPENSE_TYPE_LABELS.forEach { (key, value) ->
                            val (label, icon) = value
                            DropdownMenuItem(
                                text = { Text("$icon $label") },
                                onClick = {
                                    onTypeChange(key)
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                // Сумма
                Text(
                    "${"%.0f".format(expense.amount)} ${expense.currency}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(4.dp))

            // Дата + название
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${expense.date.dayOfMonth}.${expense.date.monthNumber}.${expense.date.year}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    if (expense.name.isNotBlank()) {
                        Text(
                            expense.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (expense.comment.isNotBlank()) {
                        Text(
                            expense.comment,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            maxLines = 2
                        )
                    }
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, "Удалить", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * ➕ Диалог добавления расхода с расширенным списком типов
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExpenseDialog(
    project: Project,
    defaultDate: kotlinx.datetime.LocalDate,
    onDismiss: () -> Unit,
    onSave: (type: String, name: String, amount: Double, currency: String, comment: String) -> Unit
) {
    var selectedType by remember { mutableStateOf("ROAD") }
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("RUB") }
    var comment by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("➕ Новый расход") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Проект: ${project.name}", style = MaterialTheme.typography.labelMedium, color = Color.Gray)

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

/**
 * 🎯 Справочник типов расходов (расширенный список)
 * Ключ -> (Название на русском, Эмодзи-иконка)
 */
private val EXPENSE_TYPE_LABELS = mapOf(
    "CONTRACTORS" to ("Подрядчики" to "👷"),
    "ROAD" to ("Дорога" to "🚗"),
    "PER_DIEM" to ("Суточные" to "💵"),
    "CASH" to ("Наличные" to "💰"),
    "CARD" to ("Карта" to "💳"),
    "OTHER" to ("Прочее" to "📦")
)