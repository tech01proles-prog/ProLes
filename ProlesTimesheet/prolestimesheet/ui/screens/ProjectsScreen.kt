package com.example.prolestimesheet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.prolestimesheet.model.Project
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    viewModel: TimesheetViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allprojects by viewModel.projects.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingProject by remember { mutableStateOf<Project?>(null) }
    val projects = allprojects.filter { it.projectNumber != "OTHER" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("📁 Проекты", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${projects.size} проектов • ${projects.count { it.isActive }} активных",
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
            if (viewModel.canCreate("projects")) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, "Добавить проект")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (projects.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.FolderOpen, null,
                                modifier = Modifier.size(64.dp),
                                tint = Color.Gray
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Проектов пока нет", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            } else {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onEdit = { editingProject = project },
                        onDelete = { viewModel.deleteProject(project.id) },
                        canEdit = viewModel.canEdit("projects"),
                        canDelete = viewModel.canDelete("projects")
                    )
                }
            }
        }
    }

    // Диалог добавления
    if (showAddDialog) {
        ProjectEditDialog(
            project = null,
            onDismiss = { showAddDialog = false },
            onSave = { newProject ->
                viewModel.addProject(newProject)  // ✅ Передаём полный объект
                showAddDialog = false
            }
        )
    }

    // Диалог редактирования
    editingProject?.let { project ->
        ProjectEditDialog(
            project = project,
            onDismiss = { editingProject = null },
            onSave = { updated ->
                viewModel.updateProject(updated)
                editingProject = null
            }
        )
    }
}

/**
 * 📋 Карточка проекта (канбан-вид для мобильного)
 */
/**
 * 📋 Карточка проекта (канбан-вид для мобильного)
 * Отображает ВСЕ поля проекта структурированно
 */
@Composable
private fun ProjectCard(
    project: Project,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    canEdit: Boolean,
    canDelete: Boolean
) {
    val statusColor = when (project.status) {
        "new" -> Color(0xFF2196F3)
        "in_progress" -> Color(0xFFFF9800)
        "completed" -> Color(0xFF4CAF50)
        "cancelled" -> Color(0xFFF44336)
        else -> Color.Gray
    }
    val statusLabel = when (project.status) {
        "new" -> "🆕 Новый"
        "in_progress" -> "🔄 В работе"
        "completed" -> "✅ Завершён"
        "cancelled" -> "❌ Отменён"
        else -> project.status
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // ═══════════════════════════════════════════════════
            // 🔷 БЛОК 1: Заголовок + Статус
            // ═══════════════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    // Номера проекта (если есть)
                    val projectNumbers = buildString {
                        if (project.projectNumber.isNotBlank()) {
                            append("№${project.projectNumber}")
                            if (project.subProjectNumber.isNotBlank()) {
                                append(" / ${project.subProjectNumber}")
                            }
                        }
                    }
                    if (projectNumbers.isNotBlank()) {
                        Text(
                            projectNumbers,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    color = statusColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        statusLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }

            // ═══════════════════════════════════════════════════
            // 🔷 БЛОК 2: Основная информация (Клиент, Город)
            // ═══════════════════════════════════════════════════
            val hasMainInfo = project.client.isNotBlank() ||
                    project.location.isNotBlank()

            if (hasMainInfo) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Левая колонка: Клиент
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(28.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (project.client.isNotBlank()) {
                                    InfoRow(
                                        icon = Icons.Default.Business,
                                        value = project.client
                                    )
                                }
                            }
                            // Правая колонка: Город
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(28.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (project.location.isNotBlank()) {
                                    InfoRow(
                                        icon = Icons.Default.LocationOn,
                                        value = project.location
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════
            // 🔷 БЛОК 3: Бизнес-информация (Товар, Кол-во, Срок, Договор)
            // ═══════════════════════════════════════════════════
            val hasBusinessInfo = project.productService.isNotBlank() ||
                    project.quantity > 1 ||
                    !project.deliveryDate.isNullOrBlank() ||
                    project.contract.isNotBlank()

            if (hasBusinessInfo) {
                // Строка 1: Товар + Договор (если есть оба)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Левая колонка: Товар
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (project.productService.isNotBlank()) {
                            Text(
                                "🎯 ${project.productService}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    // Правая колонка: Договор
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (project.contract.isNotBlank()) {
                            Text(
                                "📄 ${project.contract}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Строка 2: Кол-во + Срок (chips)
                val hasQuantityOrDate = project.quantity > 1 || !project.deliveryDate.isNullOrBlank()
                if (hasQuantityOrDate) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Левая колонка: Кол-во
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (project.quantity > 0) {
                                MiniChip("📦 ${project.quantity} шт.", Color(0xFF0277BD))
                            }
                        }
                        // Правая колонка: Срок поставки
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (!project.deliveryDate.isNullOrBlank()) {
                                val dateDisplay = runCatching {
                                    val date = kotlinx.datetime.LocalDate.parse(project.deliveryDate!!)
                                    "${date.dayOfMonth}.${date.monthNumber.toString().padStart(2, '0')}.${date.year}"
                                }.getOrDefault(project.deliveryDate!!)
                                MiniChip("📅 $dateDisplay", Color(0xFF6A1B9A))
                            }
                        }
                    }
                }
            }

            // Дополнительные финансовые поля (сетка 2×2)
            if (project.cost > 0 || project.productionCost > 0 ||
                project.transportToClient > 0 || project.sellingPrice > 0) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    // Строка 1: Себестоимость + Производство
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(24.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (project.cost > 0) {
                                SmallFinRow("Себестоимость", project.cost)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(24.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (project.productionCost > 0) {
                                SmallFinRow("Производство", project.productionCost)
                            }
                        }
                    }

                    // Строка 2: Транспорт + Цена продажи
                    val hasSecondRow = project.transportToClient > 0 || project.sellingPrice > 0
                    if (hasSecondRow) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (project.transportToClient > 0) {
                                    SmallFinRow("Транспорт", project.transportToClient)
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (project.sellingPrice > 0) {
                                    SmallFinRow("Цена продажи", project.sellingPrice)
                                }
                            }
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════
            // 🔷 БЛОК 5: Заметки (если есть)
            // ═══════════════════════════════════════════════════
            if (project.notes.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFFFF8E1),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("📝 ", fontSize = 14.sp)
                        Text(
                            project.notes,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ═══════════════════════════════════════════════════
            // 🔷 БЛОК 6: Действия (Редактировать / Удалить)
            // ═══════════════════════════════════════════════════
            if (canEdit || canDelete) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!project.isActive) {
                        Surface(
                            color = Color(0xFFFFEBEE),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                "⏸ Неактивный",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFC62828)
                            )
                        }
                    }
                    if (canEdit) {
                        TextButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Редактировать")
                        }
                    }
                    if (canDelete) {
                        TextButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Удалить")
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════
// 🔹 Вспомогательные компоненты
// ═══════════════════════════════════════════════════

/**
 * Строка с иконкой + метка + значение
 */
@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Маленький цветной чип (для кол-ва, даты)
 */
@Composable
private fun MiniChip(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

/**
 * Компактная финансовая статистика
 */
@Composable
private fun FinStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color)
    }
}

/**
 * Маленькая финансовая строка (для доп. полей)
 */
@Composable
private fun SmallFinRow(label: String, amount: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(
            "${formatMoney(amount)} ₽",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Форматирует деньги: 1500000 → "1.5M", 15000 → "15K", 1500 → "1 500"
 */
private fun formatMoney(amount: Double): String {
    return when {
        amount >= 1_000_000 -> "${"%.1f".format(amount / 1_000_000)}M"
        amount >= 10_000 -> "${"%.0f".format(amount / 1_000)}K"
        else -> "%,.0f".format(amount).replace(",", " ")
    }
}
/**
 * 📝 Диалог добавления/редактирования проекта
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectEditDialog(
    project: Project?,
    onDismiss: () -> Unit,
    onSave: (Project) -> Unit
) {
    var name by remember { mutableStateOf(project?.name ?: "") }
    var projectNumber by remember { mutableStateOf(project?.projectNumber ?: "") }
    var subProjectNumber by remember { mutableStateOf(project?.subProjectNumber ?: "") }
    var client by remember { mutableStateOf(project?.client ?: "") }
    var location by remember { mutableStateOf(project?.location ?: "") }
    var productService by remember { mutableStateOf(project?.productService ?: "") }
    var quantity by remember { mutableStateOf(project?.quantity?.toString() ?: "1") }
    var deliveryDate by remember { mutableStateOf(project?.deliveryDate ?: "") }
    var contract by remember { mutableStateOf(project?.contract ?: "") }
    var status by remember { mutableStateOf(project?.status ?: "new") }
    var isActive by remember { mutableStateOf(project?.isActive ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (project == null) "➕ Новый проект" else "✏️ Редактировать проект") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1️⃣ Название *
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // 2️⃣ № проекта и Подпроект (в одну строку)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = projectNumber,
                        onValueChange = { projectNumber = it },
                        label = { Text("№ проекта *") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = subProjectNumber,
                        onValueChange = { subProjectNumber = it },
                        label = { Text("Подпроект №") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                // 3️⃣ Клиент (заказчик)
                OutlinedTextField(
                    value = client,
                    onValueChange = { client = it },
                    label = { Text("Клиент (заказчик)") },
                    placeholder = { Text("ООО Ромашка") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // 4️⃣ Город / Локация
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Город / Локация") },
                    placeholder = { Text("Москва") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                HorizontalDivider()

                // 5️⃣ Товар или услуга
                OutlinedTextField(
                    value = productService,
                    onValueChange = { productService = it },
                    label = { Text("Товар или услуга") },
                    placeholder = { Text("Поставка оборудования") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // 6️⃣ Кол-во и Срок поставки (в одну строку)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = {
                            if (it.isEmpty() || it.all { c -> c.isDigit() }) quantity = it
                        },
                        label = { Text("Кол-во") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = deliveryDate,
                        onValueChange = { deliveryDate = it },
                        label = { Text("Срок поставки") },
                        placeholder = { Text("2026-12-31") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                // 7️⃣ Договор
                OutlinedTextField(
                    value = contract,
                    onValueChange = { contract = it },
                    label = { Text("Договор") },
                    placeholder = { Text("№123 от 01.01.2026") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                HorizontalDivider()

                // 8️⃣ Статус
                var statusExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = statusExpanded,
                    onExpandedChange = { statusExpanded = !statusExpanded }
                ) {
                    OutlinedTextField(
                        value = when (status) {
                            "new" -> "🆕 Новый"
                            "in_progress" -> "🔄 В работе"
                            "completed" -> "✅ Завершён"
                            "cancelled" -> "❌ Отменён"
                            else -> status
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Статус") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = statusExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = statusExpanded,
                        onDismissRequest = { statusExpanded = false }
                    ) {
                        listOf(
                            "new" to "🆕 Новый",
                            "in_progress" to "🔄 В работе",
                            "completed" to "✅ Завершён",
                            "cancelled" to "❌ Отменён"
                        ).forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { status = value; statusExpanded = false }
                            )
                        }
                    }
                }

                // 9️⃣ Активный проект
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isActive, onCheckedChange = { isActive = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Активный проект")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        val updated = (project ?: Project(
                            id = java.util.UUID.randomUUID().toString(),
                            name = name,
                            isActive = isActive
                        )).copy(
                            name = name,
                            projectNumber = projectNumber,
                            subProjectNumber = subProjectNumber,
                            client = client,
                            location = location,
                            productService = productService,
                            quantity = quantity.toIntOrNull() ?: 1,
                            deliveryDate = deliveryDate.takeIf { it.isNotBlank() },
                            contract = contract,
                            status = status,
                            isActive = isActive
                        )
                        onSave(updated)
                    }
                },
                enabled = name.isNotBlank()
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}