package com.example.prolestimesheet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.model.Project
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import com.example.prolestimesheet.ui.components.NoAccessView
import com.example.prolestimesheet.ui.components.WipBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CostCalculationScreen(
    viewModel: TimesheetViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val projects by viewModel.projects.collectAsState()
    var editingProject by remember { mutableStateOf<Project?>(null) }
    val scrollState = rememberScrollState()
    val expenses by viewModel.expenses.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Расчёт себестоимости проектов") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { padding ->
        // 🔥 Проверка прав
        if (!viewModel.canView("cost_calculation")) {
            NoAccessView(onBack = onBack)
            return@Scaffold
        }
        Column(modifier = modifier.padding(padding).fillMaxSize()) {
            WipBanner()

            // 🔹 Заголовок таблицы (с горизонтальным скроллом)
            TableHeader(scrollState)
            HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outlineVariant)

            // 🔹 Тело таблицы (с горизонтальным скроллом)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(projects, key = { it.id }) { project ->
                    // 🆕 Расходы теперь берутся из отдельного потока
                    val projectExpenses = expenses.filter { it.projectId == project.id }
                    val transportOverhead = projectExpenses.filter { it.type == "ROAD" }.sumOf { it.amount }
                    val externalCosts = projectExpenses.filter { it.type == "OTHER" }.sumOf { it.amount }

                    // 💰 Расчеты
                    val productionCost = project.productionCost
                    val transportToClient = project.transportToClient
                    val fullCost = productionCost + transportOverhead + transportToClient + externalCosts
                    val sellingPrice = project.sellingPrice
                    val margin = sellingPrice - fullCost
                    val marginPercent = if (sellingPrice > 0) (margin / sellingPrice * 100) else 0.0

                    ProjectCostRow(
                        project = project,
                        transportOverhead = transportOverhead,
                        externalCosts = externalCosts,
                        fullCost = fullCost,
                        margin = margin,
                        marginPercent = marginPercent,
                        scrollState = scrollState,
                        onClick = { editingProject = project }
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }

                if (projects.isEmpty()) {
                    item {
                        Text(
                            "Нет проектов",
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // 🔹 Диалог редактирования
    editingProject?.let { project ->
        EditProjectCostDialog(
            project = project,
            onDismiss = { editingProject = null },
            onConfirm = { updated ->
                viewModel.updateProject(updated)
                editingProject = null
            }
        )
    }
}

// 🔹 Заголовок таблицы
@Composable
private fun TableHeader(scrollState: androidx.compose.foundation.ScrollState) {
    Row(
        modifier = Modifier
            .horizontalScroll(scrollState)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
            .padding(vertical = 8.dp)
    ) {
        HeaderCell("Проект", 120.dp)
        HeaderCell("Дата", 80.dp)
        HeaderCell("Покупатель", 100.dp)
        HeaderCell("Наименование", 140.dp)
        HeaderCell("Себ-сть произв.", 100.dp)
        HeaderCell("Транспорт сверху", 110.dp)
        HeaderCell("Транспорт до клиента", 120.dp)
        HeaderCell("Внешние затраты", 100.dp)
        HeaderCell("Себ-сть полн.", 100.dp)
        HeaderCell("Продажная цена", 110.dp)
        HeaderCell("Марж доход", 100.dp)
        HeaderCell("в %", 50.dp)
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp) {
    Text(
        text = text,
        modifier = Modifier.width(width).padding(horizontal = 4.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
        maxLines = 2
    )
}

// 🔹 Строка проекта
@Composable
private fun ProjectCostRow(
    project: Project,
    transportOverhead: Double,
    externalCosts: Double,
    fullCost: Double,
    margin: Double,
    marginPercent: Double,
    scrollState: androidx.compose.foundation.ScrollState,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .horizontalScroll(scrollState)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(Color.Transparent)
            .padding(vertical = 8.dp)
    ) {
        Cell(project.projectCode.ifBlank { "-" }, 120.dp, TextAlign.Center)
        Cell(project.completionDate ?: "-", 80.dp, TextAlign.Center)
        Cell(project.customer.ifBlank { "-" }, 100.dp, TextAlign.Start)
        Cell(project.name, 140.dp, TextAlign.Start)
        MoneyCell(project.productionCost, 100.dp)
        MoneyCell(transportOverhead, 110.dp)
        MoneyCell(project.transportToClient, 120.dp)
        MoneyCell(externalCosts, 100.dp)
        MoneyCell(fullCost, 100.dp, FontWeight.Bold)
        MoneyCell(project.sellingPrice, 110.dp)
        MoneyCell(margin, 100.dp, FontWeight.Bold)
        PercentCell(marginPercent, 50.dp)
    }
}

@Composable
private fun Cell(text: String, width: Dp, align: TextAlign) {
    Text(
        text = text,
        modifier = Modifier.width(width).padding(horizontal = 4.dp),
        textAlign = align,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1
    )
}

@Composable
private fun MoneyCell(amount: Double, width: Dp, fontWeight: FontWeight = FontWeight.Normal) {
    Text(
        text = "₽${"%.0f".format(amount)}",
        modifier = Modifier.width(width).padding(horizontal = 4.dp),
        textAlign = TextAlign.End,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = fontWeight
    )
}

@Composable
private fun PercentCell(percent: Double, width: Dp) {
    val color = if (percent >= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
    Text(
        text = "${"%.1f".format(percent)}%",
        modifier = Modifier.width(width).padding(horizontal = 4.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        color = color
    )
}

// 🔹 Диалог редактирования
@Composable
private fun EditProjectCostDialog(
    project: Project,
    onDismiss: () -> Unit,
    onConfirm: (Project) -> Unit
) {
    var projectCode by remember { mutableStateOf(project.projectCode) }
    var completionDate by remember { mutableStateOf(project.completionDate ?: "") }
    var customer by remember { mutableStateOf(project.customer) }
    var productionCost by remember { mutableStateOf(project.productionCost.toString()) }
    var transportToClient by remember { mutableStateOf(project.transportToClient.toString()) }
    var sellingPrice by remember { mutableStateOf(project.sellingPrice.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактировать проект") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = projectCode,
                    onValueChange = { projectCode = it },
                    label = { Text("Код проекта") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = completionDate,
                    onValueChange = { completionDate = it },
                    label = { Text("Дата завершения (ГГГГ-ММ-ДД)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = customer,
                    onValueChange = { customer = it },
                    label = { Text("Покупатель") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = productionCost,
                    onValueChange = { if (it.all { c -> c.isDigit() || c == '.' || c == ',' }) productionCost = it.replace(',', '.') },
                    label = { Text("Себестоимость производства") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = transportToClient,
                    onValueChange = { if (it.all { c -> c.isDigit() || c == '.' || c == ',' }) transportToClient = it.replace(',', '.') },
                    label = { Text("Транспорт до клиента") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = sellingPrice,
                    onValueChange = { if (it.all { c -> c.isDigit() || c == '.' || c == ',' }) sellingPrice = it.replace(',', '.') },
                    label = { Text("Продажная цена (без НДС)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val updated = project.copy(
                        projectCode = projectCode.trim(),
                        completionDate = completionDate.trim().ifBlank { null },
                        customer = customer.trim(),
                        productionCost = productionCost.toDoubleOrNull() ?: 0.0,
                        transportToClient = transportToClient.toDoubleOrNull() ?: 0.0,
                        sellingPrice = sellingPrice.toDoubleOrNull() ?: 0.0
                    )
                    onConfirm(updated)
                },
                enabled = projectCode.isNotBlank() && completionDate.isNotBlank() && customer.isNotBlank()
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

