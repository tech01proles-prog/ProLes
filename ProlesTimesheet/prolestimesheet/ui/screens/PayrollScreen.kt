package com.example.prolestimesheet.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import java.time.YearMonth
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import com.example.prolestimesheet.utils.PayrollExporter
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayrollScreen(
    viewModel: TimesheetViewModel,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val employees by viewModel.employees.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val salaryBreakdown by viewModel.salaryBreakdown.collectAsState()
    var isCalculating by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }

    var selectedEmployee by remember { mutableStateOf(employees.firstOrNull()) }
    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }

    // 📊 Функция экспорта
    suspend fun exportPayroll(format: String) {
        isExporting = true
        try {
            val data = viewModel.fetchPayrollExport(selectedMonth.year, selectedMonth.monthValue)
            if (data != null) {
                val uri = if (format == "csv") {
                    PayrollExporter.exportToCsv(viewModel.repository.context, data, selectedMonth.year, selectedMonth.monthValue)
                } else {
                    PayrollExporter.exportToPdf(viewModel.repository.context, data, selectedMonth.year, selectedMonth.monthValue)
                }
                if (uri != null) {
                    val mimeType = if (format == "csv") {
                        "application/vnd.ms-excel"  // ✅ Лучше открывается в Excel
                    } else {
                        "application/pdf"
                    }

                    Log.d("ApiClient", "📤 Exporting: format=$format, mimeType=$mimeType, uri=$uri")

                    PayrollExporter.shareFile(viewModel.repository.context, uri, mimeType)
                }
            }
        } catch (e: Exception) {
            Log.e("PayrollScreen", "❌ Export failed", e)
        } finally {
            isExporting = false
        }
    }

    // Автовыбор первого сотрудника
    LaunchedEffect(employees) {
        if (selectedEmployee == null && employees.isNotEmpty()) {
            selectedEmployee = employees.first()
        }
    }

    // Запускаем расчёт при смене сотрудника/месяца
    LaunchedEffect(selectedEmployee, selectedMonth.year, selectedMonth) {
        selectedEmployee?.let { emp ->
            isCalculating = true
            viewModel.calculateSalary(emp.id, selectedMonth.year, selectedMonth.monthValue)
            // Ждём обновления salaryBreakdown (максимум 5 секунд)
            withTimeoutOrNull(5000) {
                viewModel.salaryBreakdown.firstOrNull { it != null }
            }
            isCalculating = false
        }
    }

    // 🔥 Логируем только когда данные действительно есть
    LaunchedEffect(salaryBreakdown) {
        if (salaryBreakdown != null) {
            Log.d("ApiClient","✅ $salaryBreakdown")
            val fixed = salaryBreakdown?.fixed ?: 0.0
            val piece = salaryBreakdown?.piece ?: 0.0
            val hourly = salaryBreakdown?.hourly ?: 0.0
            val bonus = salaryBreakdown?.bonus ?: 0.0
            val total = salaryBreakdown?.total ?: 0.0
            Log.d("ApiClient", "✅ UI updated: fixed=$fixed, piece=$piece, hourly=$hourly, bonus=$bonus, total=$total")
        }
    }


    val monthEntries = remember(entries, selectedEmployee, selectedMonth) {
        entries.filter { e ->
            e.userId == selectedEmployee?.id &&
                    e.date.year == selectedMonth.year &&
                    e.date.monthNumber == selectedMonth.monthValue
        }
    }

    val totalHours = monthEntries.sumOf { it.hours.toDouble() }

    // Серверный расчёт (salaryBreakdown — это Map<String, Any>)
    val fixed = salaryBreakdown?.fixed ?: 0.0
    val piece = salaryBreakdown?.piece ?: 0.0
    val hourly = salaryBreakdown?.hourly ?: 0.0
    val bonus = salaryBreakdown?.bonus ?: 0.0
    val total = salaryBreakdown?.total ?: 0.0

    LazyColumn(
        modifier = modifier.padding(16.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Text("💰 Расчёт зарплаты", style = MaterialTheme.typography.headlineLarge) }
        // 📊 Кнопки экспорта (теперь внутри item{})
        if (viewModel.canView("payroll")) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            CoroutineScope(Dispatchers.IO).launch {
                                exportPayroll("csv")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isExporting
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.TableChart, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Excel/CSV")
                    }

                    OutlinedButton(
                        onClick = {
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                exportPayroll("pdf")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isExporting
                    ) {
                        Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("PDF")
                    }
                }
            }
        }

        // 👤 Выбор сотрудника
        item {
            Text("Сотрудник", style = MaterialTheme.typography.labelLarge)
            var expanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedEmployee?.name ?: "Выберите сотрудника", maxLines = 1)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    employees.forEach { emp ->
                        DropdownMenuItem(
                            text = { Text(emp.name) },
                            onClick = { selectedEmployee = emp; expanded = false }
                        )
                    }
                }
            }
        }

        // 📅 Выбор месяца
        item {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                IconButton(onClick = { selectedMonth = selectedMonth.minusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                }
                Text(
                    text = "${selectedMonth.month} ${selectedMonth.year}",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                IconButton(onClick = { selectedMonth = selectedMonth.plusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Вперёд")
                }
            }
        }

        // 🔄 Индикатор расчёта
        if (isCalculating) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Расчёт зарплаты...")
                    }
                }
            }
        }

        // 📦 Карточка с итогами
        if (selectedEmployee != null && !isCalculating) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text("Отработано часов:", style = MaterialTheme.typography.bodyLarge)
                            Text("${"%.1f".format(totalHours)} ч.", style = MaterialTheme.typography.titleLarge)
                        }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))

                        // Детализация по компонентам
                        if (fixed > 0) {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text("💵 Оклад:", style = MaterialTheme.typography.bodyLarge)
                                Text("${"%.2f".format(fixed)} ₽", style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color(0xFF2E7D32))
                            }
                        }
                        if (hourly > 0) {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text("⌚ Почасовая:", style = MaterialTheme.typography.bodyLarge)
                                Text("${"%.2f".format(hourly)} ₽", style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color(0xFF0277BD))
                            }
                        }
                        if (piece > 0) {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text("📋 Сдельная:", style = MaterialTheme.typography.bodyLarge)
                                Text("${"%.2f".format(piece)} ₽", style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color(0xFF6A1B9A))
                            }
                        }
                        if (bonus > 0) {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text("🎁 Премия:", style = MaterialTheme.typography.bodyLarge)
                                Text("+ ${"%.2f".format(bonus)} ₽", style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color(0xFFFF6F00))
                            }
                        }

                        if (fixed == 0.0 && hourly == 0.0 && piece == 0.0 && bonus == 0.0) {
                            Text(
                                "Компоненты зарплаты не настроены",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))

                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text("ИТОГО К ВЫПЛАТЕ:", style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Text("${"%.2f".format(total)} ₽", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 📋 Детализация по дням
        if (monthEntries.isNotEmpty()) {
            item { Text("📋 Детализация по дням", style = MaterialTheme.typography.titleSmall) }
            items(monthEntries.sortedBy { it.date }) { entry ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp).fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = entry.projectName.ifBlank { "Без проекта" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    maxLines = 1
                                )
                                Text(
                                    text = "${entry.date.dayOfMonth}.${entry.date.monthNumber}.${entry.date.year} • ${entry.country}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (entry.hours > 0f) {
                                Text(
                                    text = "${"%.1f".format(entry.hours)} ч.",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                            }
                        }
                        if (entry.comment.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "💬 ${entry.comment}",
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}