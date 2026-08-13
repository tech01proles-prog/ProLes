package com.example.prolestimesheet.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.prolestimesheet.model.DayOff
import com.example.prolestimesheet.model.Project
import com.example.prolestimesheet.model.TimeEntry
import com.example.prolestimesheet.model.VacationPeriod
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.todayIn
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.ui.graphics.RectangleShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimesheetScreen(
    viewModel: TimesheetViewModel,
    showVacationDialog: Boolean = false,
    onVacationDialogDismissed: () -> Unit = {},
    onRequestVacation: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val projects by viewModel.projects.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val vacations by viewModel.vacations.collectAsState()
    val user by viewModel.user.collectAsState()
    val dayOffs by viewModel.dayOffs.collectAsState()
    val expenses by viewModel.expenses.collectAsState()
    val employees by viewModel.employees.collectAsState()
    val trips by viewModel.trips.collectAsState()

    var currentMonth by remember { mutableStateOf(YearMonth.of(selectedDate.year, selectedDate.monthNumber)) }
    LaunchedEffect(selectedDate) { currentMonth = YearMonth.of(selectedDate.year, selectedDate.monthNumber) }

    var selectedProject by remember { mutableStateOf<Project?>(null) }
    var hours by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var totalH by remember { mutableStateOf("") }
    var selectedCountry by remember { mutableStateOf("RF") }
    var showProjectDropdown by remember { mutableStateOf(false) }
    var showExpenseDialog by remember { mutableStateOf(false) }
    var showTripDialog by remember { mutableStateOf(false) }
    var showIncomeDialog by remember { mutableStateOf(false) }


    val dayEntries = remember(entries, selectedDate, user?.id) {
        entries.filter { it.date == selectedDate && it.userId == user?.id }
    }
    val isOnVacation = remember(vacations, selectedDate) {
        vacations.any { v -> selectedDate >= v.start && selectedDate <= v.end }
    }

    LaunchedEffect(selectedDate, dayEntries) {
        totalH = (dayEntries.sumOf { it.hours.toDouble() }).toString()
        selectedCountry = dayEntries.firstOrNull()?.country ?: "RF"
    }

    fun clearAll() {
        selectedProject = null
        hours = ""
        comment = ""
        totalH = ""
        selectedCountry = "RF"
    }

    // 🆕 Определяем выходные (суббота/воскресенье) и пользовательские dayOff
    val isWeekend = selectedDate.dayOfWeek == DayOfWeek.SATURDAY ||
            selectedDate.dayOfWeek == DayOfWeek.SUNDAY
    val isUserDayOff = dayOffs.any { it.date == selectedDate && it.userId == user?.id }

    // Инвертированная логика:
    // - Будний день + запись в day_offs = выходной
    // - Выходной (сб/вс) + запись в day_offs = РАБОЧИЙ (исключение)
    val isCurrentlyDayOff = if (isWeekend) !isUserDayOff else isUserDayOff

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Табель | ${user?.firstName ?: "Гость"}") },
                actions = {
                    IconButton(onClick = onRequestVacation) {
                        Icon(
                            Icons.Default.BeachAccess,
                            contentDescription = "Оформить отпуск",
                            tint = if (isOnVacation) Color(0xFF0277BD) else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp, 2.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // 📅 Навигация по месяцам
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                IconButton(onClick = { currentMonth = currentMonth.minusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                }
                Text(
                    "${currentMonth.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru"))} ${currentMonth.year}",
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = { currentMonth = currentMonth.plusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Вперед")
                }
            }
            Spacer(Modifier.height(8.dp))

            // 🗓 Календарь
            CalendarGrid(
                month = currentMonth,
                selectedDate = selectedDate,
                entries = entries,
                vacations = vacations,
                dayOffs = dayOffs,
                userId = user?.id ?: "",
                onDateSelected = { viewModel.selectDate(it) }
            )
            Spacer(Modifier.height(4.dp))

            // ═══════════════════════════════════════════════════════
            // 📦 СТРОКА 1: Выбор проекта (на всю ширину)
            // ═══════════════════════════════════════════════════════
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { showProjectDropdown = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isOnVacation
                ) {
                    Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(selectedProject?.name ?: "Добавить часы", fontSize = 16.sp, maxLines = 1)
                }
            }

            Spacer(Modifier.height(1.dp))

            // ═══════════════════════════════════════════════════════
// 💵💰 СТРОКА 2: Доход + Расход (склеенные кнопки)
// ═══════════════════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // 🔹 Левая кнопка: ДОХОД
                Button(
                    onClick = { showIncomeDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32),  // ✅ Насыщенный зелёный
                        contentColor = Color.White           // ✅ Белый текст и иконка
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.TrendingUp, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Доход", fontSize = 14.sp, maxLines = 1)
                }

                // 🔹 Правая кнопка: РАСХОД
                Button(
                    onClick = { showExpenseDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFC62828),  // ✅ Насыщенный красный
                        contentColor = Color.White           // ✅ Белый текст и иконка
                    )
                ) {
                    Icon(Icons.Default.Receipt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Расход", fontSize = 14.sp, maxLines = 1)
                }
            }

            Spacer(Modifier.height(1.dp))

// ═══════════════════════════════════════════════════════
// 🚆🌴 СТРОКА: Командировка + Выходной (склеенные кнопки)
// ═══════════════════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // 🔹 Левая кнопка: КОМАНДИРОВКА
                Button(
                    onClick = { showTripDialog = true },
                    modifier = Modifier.weight(1f),
                    enabled = !isOnVacation,
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF5C6BC0),  // ✅ Насыщенный фиолетовый
                        contentColor = Color.White           // ✅ Белый текст и иконка
                    )
                ) {
                    Icon(Icons.Default.Train, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Командировка", maxLines = 1)
                }

                // 🔹 Правая кнопка: ВЫХОДНОЙ / РАБОЧИЙ
                val dayOffColor = if (isCurrentlyDayOff) Color(0xFFD32F2F) else Color(0xFFFF8A65)
                Button(
                    onClick = { viewModel.toggleDayOff(selectedDate) },
                    modifier = Modifier.weight(1f),
                    enabled = !isOnVacation,
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = dayOffColor,        // ✅ Насыщенный оранжевый/красный
                        contentColor = Color.White           // ✅ Белый текст и иконка
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.EventNote, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            isWeekend && !isUserDayOff -> "Рабочий"
                            isWeekend && isUserDayOff -> "Выходной"
                            !isWeekend && !isUserDayOff -> "Выходной"
                            else -> "Рабочий"
                        },
                        maxLines = 1
                    )
                }
            }

            // ═══════════════════════════════════════════════════════
            // 🎯 Плавное появление блока часов при выбранном проекте
            // ═══════════════════════════════════════════════════════
            AnimatedVisibility(
                visible = selectedProject != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(2.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        Arrangement.spacedBy(8.dp),
                        Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = hours,
                                onValueChange = {
                                    if (it.isEmpty() || it.all { c -> c.isDigit() || c == '.' || c == ',' }) {
                                        hours = it.replace(',', '.')
                                    }
                                },
                                label = { Text("Часы") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "${totalH.ifEmpty { "0" }} /24",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 2.dp)
                            )
                        }

                        IconButton(onClick = {
                            selectedCountry = if (selectedCountry == "RF") "RB" else "RF"
                        }) {
                            Text(
                                if (selectedCountry == "RF") "🇷🇺" else "🇧🇾",
                                fontSize = 22.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Поле комментария
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text("Комментарий") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2
                    )

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            if (selectedProject != null && hours.isNotEmpty()) {
                                viewModel.addEntry(
                                    selectedProject!!.id,
                                    selectedProject!!.name,
                                    hours.toFloatOrNull() ?: 0f,
                                    selectedCountry,
                                    comment
                                )
                            }
                            clearAll()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = hours.isNotEmpty()
                                && hours.toFloatOrNull() != null
                                && hours.toDouble() + (totalH.toDoubleOrNull() ?: 0.0) <= 24
                    ) { Text("Сохранить запись") }
                }
            }

            Spacer(Modifier.height(2.dp))

            // 📋 Сводка за день — раздел 1: Проекты
            Text("📋 Проекты", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))

            val projectEntries = dayEntries.filter { it.hours > 0f }
            if (projectEntries.isEmpty()) {
                Text(
                    "Нет записей по проектам",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                projectEntries.forEach { entry ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.projectName.ifBlank { entry.projectId },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${"%.1f".format(entry.hours)} ч",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = { viewModel.deleteEntry(entry.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Удалить",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 💰 Сводка за день — раздел 2: Расходы
            Text("💰 Расходы", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))

            val dayExpenses = expenses.filter { it.date == selectedDate && it.userId == user?.id }
            if (dayExpenses.isEmpty()) {
                Text(
                    "Расходов нет",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                dayExpenses.forEach { expense ->
                    val bgColor = if (expense.hasReceiptPhoto) Color(0xFFE8F5E9) else Color(
                        0xFFFFEBEE
                    )
                    val typeLabel = if (expense.type == "ROAD") "🚗" else "📦"

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        colors = CardDefaults.cardColors(containerColor = bgColor),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // 📦 Тип расхода (иконка)
                            Text(
                                typeLabel,
                                fontSize = 14.sp,
                                modifier = Modifier.width(20.dp),
                                textAlign = TextAlign.Center
                            )

                            // 📋 Проект + Название (в столбик, компактно)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    expense.projectName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (expense.type == "ROAD") "Дорога" else expense.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // 💰 Сумма
                            Text(
                                "${"%.0f".format(expense.amount)} ${expense.currency}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(70.dp),
                                textAlign = TextAlign.End
                            )

                            // 🗑 Удалить
                            IconButton(
                                onClick = { viewModel.removeExpense(expense.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    "Удалить",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 💵 Сводка за день — раздел 3: Доходы
            Text("💵 Доходы", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))

            val incomes by viewModel.incomes.collectAsState()
            val dayIncomes = incomes.filter { it.date == selectedDate && it.userId == user?.id }

            if (dayIncomes.isEmpty()) {
                Text(
                    "Доходов нет",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                dayIncomes.forEach { income ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("💵", fontSize = 14.sp, modifier = Modifier.width(20.dp), textAlign = TextAlign.Center)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    income.projectName.ifBlank { "Без проекта" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    income.name.ifBlank { "Доход" },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                "${"%.0f".format(income.amount)} ${income.currency}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32),
                                modifier = Modifier.width(70.dp),
                                textAlign = TextAlign.End
                            )
                            IconButton(
                                onClick = { viewModel.removeIncome(income.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Delete, "Удалить", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // 📁 Красивый диалог выбора проекта (вместо DropdownMenu)
    if (showProjectDropdown) {
        AlertDialog(
            onDismissRequest = { showProjectDropdown = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Folder,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Выберите проект", style = MaterialTheme.typography.titleLarge)
                }
            },
            text = {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val activeProjects = projects.filter { it.isActive && it.name != "ДРУГОЕ" }
                    if (activeProjects.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Нет активных проектов",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(activeProjects) { proj ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedProject = proj
                                        showProjectDropdown = false
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                            shape = MaterialTheme.shapes.small
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Folder,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        proj.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    // Подсказка: клиент/город, если есть
                                    val subtitle = buildList {
                                        if (!proj.client.isNullOrBlank()) add(proj.client)
                                        if (!proj.location.isNullOrBlank()) add(proj.location)
                                    }.joinToString(" • ")
                                    if (subtitle.isNotBlank()) {
                                        Text(
                                            subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                // Галочка, если это текущий выбранный проект
                                if (selectedProject?.id == proj.id) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        "Выбран",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showProjectDropdown = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // 🏖 Диалог отпуска
    if (showVacationDialog) {
        VacationPickerDialog(
            existingVacations = vacations,
            onDismiss = onVacationDialogDismissed,
            onConfirm = { start, end ->
                viewModel.addVacation(start, end)
                onVacationDialogDismissed()
            }
        )
    }

    // 🚆 Диалог командировки
    if (showTripDialog) {
        TripDialog(
            trip = null,
            userId = user?.id ?: "",
            userName = user?.name ?: "Вы",  // 🆕 ДОБАВЛЕНО
            employees = employees,
            projects = projects,
            allTrips = trips,
            onDismiss = { showTripDialog = false },
            onConfirm = { newTrip ->
                viewModel.addBusinessTrip(newTrip)
                showTripDialog = false
            }
        )
    }

    // 💰 Единый диалог расходов с переключателем типа
    if (showExpenseDialog) {
        var expenseType by remember { mutableStateOf("ROAD") }
        var amount by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var currency by remember { mutableStateOf("RUB") }

        // 🆕 Состояния для смены проекта в диалоге
        var dialogProjectId by remember { mutableStateOf(selectedProject?.id ?: "") }
        var dialogProjectName by remember { mutableStateOf(selectedProject?.name ?: "") }
        var showProjectPicker by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showExpenseDialog = false },
            title = {
                Text(
                    if (expenseType == "ROAD") "🚗 Расход на дорогу" else "📦 Прочий расход",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 🔀 Переключатель типа расхода
                    Text("Тип расхода", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = expenseType == "ROAD",
                            onClick = { expenseType = "ROAD" },
                            label = { Text("🚗 Дорога") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = expenseType == "OTHER",
                            onClick = { expenseType = "OTHER" },
                            label = { Text("📦 Другое") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // 🆕 Поле проекта с возможностью смены
                    OutlinedButton(
                        onClick = { showProjectPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(dialogProjectName.ifBlank { "Выберите проект" })
                    }

                    // 🆕 Плавная анимация поля "Название расхода"
                    AnimatedContent(
                        targetState = expenseType,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(300)) togetherWith
                                    fadeOut(animationSpec = tween(200))
                        },
                        label = "expenseTypeAnimation"
                    ) { type ->
                        if (type == "OTHER") {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Название расхода") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }

                    // 💵 Сумма + Валюта
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = amount,
                            onValueChange = {
                                if (it.isEmpty() || it.all { c -> c.isDigit() || c == '.' || c == ',' }) {
                                    amount = it.replace(',', '.')
                                }
                            },
                            label = { Text("Сумма") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(2f),
                            singleLine = true
                        )

                        var curExpanded by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { curExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(currency) }
                            DropdownMenu(
                                expanded = curExpanded,
                                onDismissRequest = { curExpanded = false }
                            ) {
                                listOf("RUB", "BYN", "USD", "EUR").forEach { cur ->
                                    DropdownMenuItem(
                                        text = { Text(cur) },
                                        onClick = {
                                            currency = cur
                                            curExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (amount.isNotEmpty() && dialogProjectId.isNotBlank()) {
                            val isOtherValid = expenseType == "ROAD" || name.isNotBlank()
                            if (isOtherValid) {
                                viewModel.addExpense(
                                    projectId = dialogProjectId,
                                    projectName = dialogProjectName,
                                    date = selectedDate,
                                    type = expenseType,
                                    name = if (expenseType == "OTHER") name else "",
                                    amount = amount.toDouble(),
                                    currency = currency
                                )
                                showExpenseDialog = false
                            }
                        }
                    },
                    enabled = amount.isNotEmpty()
                            && dialogProjectId.isNotBlank()
                            && (expenseType == "ROAD" || name.isNotBlank())
                ) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { showExpenseDialog = false }) { Text("Отмена") }
            }
        )

        // 🆕 Диалог выбора проекта
        if (showProjectPicker) {
            AlertDialog(
                onDismissRequest = { showProjectPicker = false },
                title = { Text("Выберите проект") },
                text = {
                    LazyColumn {
                        items(projects.filter { it.isActive }) { proj ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        dialogProjectId = proj.id
                                        dialogProjectName = proj.name
                                        showProjectPicker = false
                                    }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(proj.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = {}
            )
        }
    }

    // 💵 Диалог добавления дохода
    if (showIncomeDialog) {
        var incomeName by remember { mutableStateOf("") }
        var incomeAmount by remember { mutableStateOf("") }
        var incomeCurrency by remember { mutableStateOf("RUB") }
        var incomeProjectId by remember { mutableStateOf<String?>(null) }
        var incomeProjectName by remember { mutableStateOf("Без проекта") }
        var showIncomeProjectPicker by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showIncomeDialog = false },
            title = { Text("💵 Новый доход") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { showIncomeProjectPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(incomeProjectName)
                    }
                    OutlinedTextField(
                        value = incomeName,
                        onValueChange = { incomeName = it },
                        label = { Text("Название (опционально)") },
                        placeholder = { Text("Например: Аванс, Премия") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = incomeAmount,
                            onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() || c == '.' || c == ',' }) incomeAmount = it.replace(',', '.') },
                            label = { Text("Сумма") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(2f),
                            singleLine = true
                        )
                        var curExp by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { curExp = true }, modifier = Modifier.fillMaxWidth()) { Text(incomeCurrency) }
                            DropdownMenu(expanded = curExp, onDismissRequest = { curExp = false }) {
                                listOf("RUB", "BYN", "USD", "EUR").forEach { cur ->
                                    DropdownMenuItem(text = { Text(cur) }, onClick = { incomeCurrency = cur; curExp = false })
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val amount = incomeAmount.toDoubleOrNull() ?: 0.0
                        if (amount > 0) {
                            viewModel.addIncome(
                                projectId = incomeProjectId,
                                projectName = incomeProjectName,
                                date = selectedDate,
                                name = incomeName,
                                amount = amount,
                                currency = incomeCurrency
                            )
                            showIncomeDialog = false
                        }
                    },
                    enabled = (incomeAmount.toDoubleOrNull() ?: 0.0) > 0
                ) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { showIncomeDialog = false }) { Text("Отмена") } }
        )

        if (showIncomeProjectPicker) {
            AlertDialog(
                onDismissRequest = { showIncomeProjectPicker = false },
                title = { Text("Выберите проект (опционально)") },
                text = {
                    LazyColumn {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    incomeProjectId = null
                                    incomeProjectName = "Без проекта"
                                    showIncomeProjectPicker = false
                                }.padding(vertical = 12.dp)
                            ) { Text("Без проекта", style = MaterialTheme.typography.bodyMedium) }
                        }
                        items(projects.filter { it.isActive }) { proj ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    incomeProjectId = proj.id
                                    incomeProjectName = proj.name
                                    showIncomeProjectPicker = false
                                }.padding(vertical = 12.dp)
                            ) { Text(proj.name, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                },
                confirmButton = {}
            )
        }
    }

}

// ═══════════════════════════════════════════════════════
// 🗓 CalendarGrid с красными выходными по умолчанию
// ═══════════════════════════════════════════════════════
@Composable
fun CalendarGrid(
    month: YearMonth,
    selectedDate: LocalDate,
    entries: List<TimeEntry>,
    vacations: List<VacationPeriod>,
    dayOffs: List<DayOff>,
    userId: String,
    onDateSelected: (LocalDate) -> Unit
) {
    val daysInMonth = month.lengthOfMonth()
    val firstDayOffset = (java.time.LocalDate.of(month.year, month.monthValue, 1).dayOfWeek.value - 1) % 7
    val cells = mutableListOf<LocalDate?>()
    for (i in 0 until 35) {
        if (i < firstDayOffset) cells.add(null)
        else {
            val day = i - firstDayOffset + 1
            if (day <= daysInMonth) cells.add(LocalDate(month.year, month.monthValue, day))
            else cells.add(null)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEachIndexed { index, day ->
                Text(
                    day,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index >= 5) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        for (week in 0 until 5) {
            Row(Modifier.fillMaxWidth().height(45.dp)) {
                for (dayInWeek in 0 until 7) {
                    val date = cells.getOrNull(week * 7 + dayInWeek)
                    val isSelected = date == selectedDate
                    val hasHours = entries.any { it.date == date && it.userId == userId && it.hours > 0f }
                    val isVacation = date != null && vacations.any { v -> date >= v.start && date <= v.end }
                    val isUserDayOff = dayOffs.any { it.date == date && it.userId == userId }

                    // 🆕 Определяем выходной (суббота или воскресенье)
                    val isWeekend = date != null && (
                            date.dayOfWeek == DayOfWeek.SATURDAY ||
                                    date.dayOfWeek == DayOfWeek.SUNDAY
                            )
                    // День считается выходным, если он выходной ИЛИ явно помечен как dayOff
                    val isDayOff = if (isWeekend) !isUserDayOff else isUserDayOff

                    val today = kotlinx.datetime.Clock.System.todayIn(kotlinx.datetime.TimeZone.currentSystemDefault())
                    val isFutureDate = date != null && date > today

                    val bg = when {
                        isSelected -> MaterialTheme.colorScheme.primary
                        isVacation -> Color(0xFF81D4FA).copy(alpha = 0.45f)
                        isDayOff -> Color(0xFFFFCDD2).copy(alpha = 0.6f)
                        hasHours -> Color(0xFFA5D6A7)
                        else -> Color.Transparent
                    }

                    val txt = when {
                        isSelected -> MaterialTheme.colorScheme.onPrimary
                        isVacation || isDayOff -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        else -> MaterialTheme.colorScheme.onSurface
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .padding(2.dp)
                            .clickable(
                                enabled = date != null && !isFutureDate,
                                onClick = { date?.let { onDateSelected(it) } }
                            )
                            .background(
                                if (isFutureDate) Color.LightGray.copy(alpha = 0.2f) else bg,
                                MaterialTheme.shapes.small
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        date?.let {
                            Text(
                                it.dayOfMonth.toString(),
                                color = if (isFutureDate) Color.Gray else txt,
                                textAlign = TextAlign.Center,
                                fontWeight = if (isDayOff && !isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════
// 🗓 VacationCalendar (без изменений)
// ═══════════════════════════════════════════════════════
@Composable
fun VacationCalendar(
    month: YearMonth,
    startDate: LocalDate?,
    endDate: LocalDate?,
    existingVacations: List<VacationPeriod>,
    onDateClick: (LocalDate) -> Unit
) {
    val daysInMonth = month.lengthOfMonth()
    val firstDayOfMonth = java.time.LocalDate.of(month.year, month.monthValue, 1)
    val firstDayOffset = (firstDayOfMonth.dayOfWeek.value - 1) % 7
    val cells = mutableListOf<LocalDate?>()
    for (i in 0 until 35) {
        if (i < firstDayOffset) cells.add(null)
        else {
            val day = i - firstDayOffset + 1
            if (day <= daysInMonth) cells.add(LocalDate(month.year, month.monthValue, day))
            else cells.add(null)
        }
    }

    val existingDays = remember(existingVacations) {
        existingVacations.flatMap { v ->
            (0..v.end.toEpochDays() - v.start.toEpochDays()).map {
                LocalDate.fromEpochDays(v.start.toEpochDays() + it)
            }
        }.toSet()
    }

    val isRangeInsideExisting = remember(startDate, endDate, existingVacations) {
        if (startDate == null || endDate == null) false
        else existingVacations.any { vacation ->
            startDate >= vacation.start && endDate <= vacation.end
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blinkAlpha"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { day ->
                Text(
                    day,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        for (week in 0 until 5) {
            Row(Modifier.fillMaxWidth().height(40.dp)) {
                for (dayInWeek in 0 until 7) {
                    val cellIndex = week * 7 + dayInWeek
                    val date = cells.getOrNull(cellIndex)

                    val isSelectedStart = date == startDate
                    val isSelectedEnd = date == endDate
                    val inSelectedRange = date != null && startDate != null && endDate != null &&
                            date >= startDate && date <= endDate
                    val isExistingVacation = date != null && date in existingDays
                    val willBeRemoved = inSelectedRange && isExistingVacation && isRangeInsideExisting
                    val willBeAdded = inSelectedRange && !isExistingVacation

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .padding(1.dp)
                            .clickable(enabled = date != null) { date?.let { onDateClick(it) } }
                            .background(
                                when {
                                    willBeRemoved -> Color(0xFFD32F2F).copy(alpha = blinkAlpha)
                                    willBeAdded -> Color(0xFF81C784).copy(alpha = 0.5f)
                                    isSelectedStart || isSelectedEnd -> MaterialTheme.colorScheme.primary
                                    isExistingVacation -> Color(0xFF81D4FA).copy(alpha = 0.4f)
                                    else -> Color.Transparent
                                },
                                MaterialTheme.shapes.small
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        date?.let {
                            Text(
                                text = it.dayOfMonth.toString(),
                                color = when {
                                    willBeRemoved -> Color.White
                                    isSelectedStart || isSelectedEnd -> MaterialTheme.colorScheme.onPrimary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isSelectedStart || isSelectedEnd) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════
// 🏖 VacationPickerDialog (без изменений)
// ═══════════════════════════════════════════════════════
@Composable
fun VacationPickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
    existingVacations: List<VacationPeriod> = emptyList(),
    existingVacation: VacationPeriod? = null
) {
    var startDate by remember { mutableStateOf(existingVacation?.start) }
    var endDate by remember { mutableStateOf(existingVacation?.end) }
    var currentMonth by remember { mutableStateOf(YearMonth.now()) }

    val isRangeInsideExisting = remember(startDate, endDate, existingVacations) {
        if (startDate == null || endDate == null) false
        else existingVacations.any { vacation ->
            startDate!! >= vacation.start && endDate!! <= vacation.end
        }
    }

    val previewText = remember(startDate, endDate, existingVacations, isRangeInsideExisting) {
        if (startDate == null || endDate == null) {
            "👆 Нажмите на дату начала отпуска"
        } else {
            val selectedDays = (0..endDate!!.toEpochDays() - startDate!!.toEpochDays()).map {
                LocalDate.fromEpochDays(startDate!!.toEpochDays() + it)
            }.toSet()
            val existingDays = existingVacations.flatMap { v ->
                (0..v.end.toEpochDays() - v.start.toEpochDays()).map {
                    LocalDate.fromEpochDays(v.start.toEpochDays() + it)
                }
            }.toSet()

            buildString {
                append("🎉 Выбрано: $startDate — $endDate\n")
                append("(${endDate!!.toEpochDays() - startDate!!.toEpochDays() + 1} дн.)\n")
                if (isRangeInsideExisting) {
                    val daysToRemove = (selectedDays.intersect(existingDays)).size
                    append("🗑️ Режим удаления: $daysToRemove дн. будет удалено")
                } else {
                    val daysToAdd = (selectedDays - existingDays).size
                    append("✅ Режим дополнения: $daysToAdd новых дн. будет добавлено")
                }
            }.trimEnd()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existingVacation != null) "Изменить отпуск" else "Оформить отпуск") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    IconButton(onClick = { currentMonth = currentMonth.minusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                    Text(
                        "${currentMonth.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru"))} ${currentMonth.year}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    IconButton(onClick = { currentMonth = currentMonth.plusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Вперед")
                    }
                }

                VacationCalendar(
                    month = currentMonth,
                    startDate = startDate,
                    endDate = endDate,
                    existingVacations = existingVacations,
                    onDateClick = { date ->
                        when {
                            date == startDate || date == endDate -> {
                                startDate = date
                                endDate = null
                            }
                            startDate == null -> {
                                startDate = date
                                endDate = null
                            }
                            endDate == null -> {
                                if (date < startDate!!) {
                                    endDate = startDate
                                    startDate = date
                                } else {
                                    endDate = date
                                }
                            }
                            else -> {
                                startDate = date
                                endDate = null
                            }
                        }
                    }
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LegendItem(Color(0xFF81D4FA).copy(alpha = 0.4f), "Существующий")
                    LegendItem(Color(0xFF81C784).copy(alpha = 0.5f), "Будет добавлено")
                    if (isRangeInsideExisting) {
                        LegendItem(Color(0xFFD32F2F).copy(alpha = 0.6f), "Будет удалено")
                    }
                }

                Text(
                    text = if (startDate != null && endDate != null) previewText
                    else "👆 Нажмите на дату начала отпуска",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (startDate != null && endDate != null && startDate!! <= endDate!!) {
                        onConfirm(startDate!!, endDate!!)
                    }
                },
                enabled = startDate != null && endDate != null && startDate!! <= endDate!!
            ) { Text("Подтвердить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
        modifier = Modifier.widthIn(max = 400.dp)
    )
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, MaterialTheme.shapes.small)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
