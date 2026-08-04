package com.example.prolestimesheet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.draggableItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.model.BusinessTrip
import com.example.prolestimesheet.model.Project
import com.example.prolestimesheet.model.User
import com.example.prolestimesheet.model.Waypoint
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.HorizontalDivider
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessTripsScreen(
    viewModel: TimesheetViewModel,
    userId: String,
    isAdminView: Boolean = false,
    canViewAll: Boolean = false,  // 🆕
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val user by viewModel.user.collectAsState()
    val trips by viewModel.trips.collectAsState()
    val employees by viewModel.employees.collectAsState()
    val projects by viewModel.projects.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingTrip by remember { mutableStateOf<BusinessTrip?>(null) }
    // 🔥 Если нет прав на просмотр всех — принудительно выключаем isAdminView
    val effectiveIsAdminView = isAdminView && canViewAll

    val filteredTrips = remember(trips, userId, effectiveIsAdminView) {
        if (effectiveIsAdminView) trips else trips.filter { it.userId == userId }
    }.sortedByDescending { it.date }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (effectiveIsAdminView) "Командировки сотрудника" else "Мои командировки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                }
            )
        },
        floatingActionButton = {
            if (!isAdminView) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, "Добавить")
                }
            }
        }
    ) { padding ->
        if (filteredTrips.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Train, null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Командировок пока нет",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                items(filteredTrips, key = { it.id }) { trip ->
                    val empName = employees.find { it.id == trip.userId }?.name ?: "Неизвестный"
                    TripCard(
                        trip = trip,
                        employeeName = empName,
                        isAdminView = effectiveIsAdminView,
                        onEdit = { editingTrip = trip },
                        onDelete = { viewModel.removeBusinessTrip(trip.id) }
                    )
                }
            }
        }
    }

    // Диалоги
    if (showAddDialog) {
        TripDialog(
            trip = null,
            userId = userId,
            userName = user?.name ?: "Вы",
            employees = employees,
            projects = projects,
            allTrips = trips,
            onDismiss = { showAddDialog = false },
            onConfirm = { newTrip ->
                viewModel.addBusinessTrip(newTrip)
                showAddDialog = false
            }
        )
    }

    editingTrip?.let { trip ->
        TripDialog(
            trip = trip,
            userId = userId,
            userName = user?.name ?: "Вы",
            employees = employees,
            projects = projects,
            allTrips = trips,
            onDismiss = { editingTrip = null },
            onConfirm = { updated ->
                viewModel.updateBusinessTrip(updated)
                editingTrip = null
            }
        )
    }
}

@Composable
private fun TripCard(
    trip: BusinessTrip,
    employeeName: String,
    isAdminView: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val (typeLabel, typeColor, typeIcon) = when (trip.type) {
        "DEPARTURE" -> Triple("🚆 Отъезд", Color(0xFF0277BD), Icons.Default.Train)
        "TRANSFER" -> Triple("🔄 Переезд", Color(0xFFFF6F00), Icons.Default.SwapHoriz)
        "COMPLETION" -> Triple("✅ Завершение", Color(0xFF2E7D32), Icons.Default.CheckCircle)
        else -> Triple(trip.type, Color.Gray, Icons.AutoMirrored.Filled.Help)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = typeColor.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            // Заголовок
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(typeIcon, null, tint = typeColor, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        typeLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = typeColor
                    )
                }
                Text(
                    "${trip.date.dayOfMonth}.${trip.date.monthNumber}.${trip.date.year}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            // Детали
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Проект", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(trip.projectName, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (trip.city.isNotBlank()) {
                    Column(Modifier.weight(1f)) {
                        Text("Город", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(trip.city, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (isAdminView) {
                Spacer(Modifier.height(4.dp))
                Text("👤 $employeeName", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (trip.transport.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("🚗 ${trip.transport}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (trip.participants.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("👥 Участников: ${trip.participants.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (trip.notes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("💬 ${trip.notes}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }

            if (!isAdminView) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Изменить")
                    }
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Удалить")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TripDialog(
    trip: BusinessTrip?,
    userId: String,
    userName: String,
    employees: List<User>,
    projects: List<Project>,
    allTrips: List<BusinessTrip>,
    onDismiss: () -> Unit,
    onConfirm: (BusinessTrip) -> Unit
) {
    val isNew = trip == null
    val today = kotlinx.datetime.Clock.System.todayIn(kotlinx.datetime.TimeZone.currentSystemDefault())

    // 🔥 Активная командировка ТЕКУЩЕГО пользователя (без COMPLETION)
    val activeTrip = remember(userId, allTrips) {
        allTrips.filter { it.userId == userId && it.type != "COMPLETION" }
            .maxByOrNull { it.date }
    }

    // 🔥 Все занятые пользователи (у кого есть активная командировка)
    val busyUserIds = remember(allTrips) {
        allTrips
            .groupBy { it.userId }
            .mapNotNull { (_, trips) -> trips.maxByOrNull { it.date } }
            .filter { it.type != "COMPLETION" }
            .map { it.userId }
            .toSet()
    }

    // 🔥 Доступные типы: зависит от наличия активной командировки
    val availableTypes = remember(activeTrip) {
        if (activeTrip != null) listOf("TRANSFER", "COMPLETION")
        else listOf("DEPARTURE")
    }

    var type by remember { mutableStateOf(trip?.type ?: availableTypes.firstOrNull() ?: "DEPARTURE") }

    // При смене доступных типов — сбрасываем type на первый доступный
    LaunchedEffect(availableTypes) {
        if (type !in availableTypes) {
            type = availableTypes.firstOrNull() ?: "DEPARTURE"
        }
    }

    // 🔥 Проект: для TRANSFER и COMPLETION подтягивается из активной командировки
    var projectId by remember { mutableStateOf(trip?.projectId ?: activeTrip?.projectId ?: "") }
    var projectName by remember { mutableStateOf(trip?.projectName ?: activeTrip?.projectName ?: "") }
    var date by remember { mutableStateOf(trip?.date ?: today) }
    var city by remember { mutableStateOf(trip?.city ?: "") }
    // 🆕 Пункты следования (waypoints)
    var waypoints by remember { mutableStateOf(trip?.waypoints ?: emptyList()) }

    // 🔥 Для COMPLETION проект и город read-only из активной командировки
    LaunchedEffect(type, activeTrip) {
        if (type == "COMPLETION" && activeTrip != null) {
            projectId = activeTrip.projectId
            projectName = activeTrip.projectName
            city = activeTrip.city
        }
    }

    // 🔥 Участники: для TRANSFER подтягиваем из активной командировки
    var selectedParticipants by remember {
        mutableStateOf<Set<String>>(
            trip?.participants?.toSet()
                ?: if (type == "TRANSFER" && activeTrip != null) activeTrip.participants.toSet() + userId
                else setOf(userId)
        )
    }

    // При смене типа на TRANSFER — подтягиваем участников из активной
    LaunchedEffect(type, activeTrip) {
        if (type == "TRANSFER" && activeTrip != null && isNew) {
            val previousParticipants = activeTrip.participants.toSet() + userId
            if (selectedParticipants != previousParticipants) {
                selectedParticipants = previousParticipants
            }
        }
    }

    var showParticipantPicker by remember { mutableStateOf(false) }
    var transport by remember { mutableStateOf(trip?.transport ?: "") }
    var notes by remember { mutableStateOf(trip?.notes ?: "") }
    var showProjectPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    val formattedDate = "%02d.%02d.%04d".format(date.dayOfMonth, date.monthNumber, date.year)

    // 🔥 Активные участники предыдущей командировки (для TRANSFER можно их брать)
    val previousParticipants = remember(activeTrip) {
        activeTrip?.participants?.toSet() ?: emptySet()
    }

    // 🔥 Фильтр доступных участников:
    // - не текущий пользователь (он уже есть)
    // - не выбраны
    // - НЕ заняты в других командировках, ИЛИ были в предыдущей (для переезда)
    val availableToAdd = remember(employees, selectedParticipants, busyUserIds, previousParticipants, type) {
        employees.filter { emp ->
            emp.id !in selectedParticipants &&
                    emp.id != userId &&
                    (emp.id !in busyUserIds || (type == "TRANSFER" && emp.id in previousParticipants))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (type) {
                    "DEPARTURE" -> "🚆 Новая командировка"
                    "TRANSFER" -> "🔄 Переезд (завершение текущей + начало новой)"
                    "COMPLETION" -> "✅ Завершение командировки"
                    else -> "Командировка"
                },
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 🔥 СЕГМЕНТИРОВАННЫЙ ВЫБОР ТИПА (только доступные)
                Text("Тип командировки", style = MaterialTheme.typography.labelLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(
                        "DEPARTURE" to "🚆 Отъезд",
                        "TRANSFER" to "🔄 Переезд",
                        "COMPLETION" to "✅ Завершение"
                    )
                        .filter { (t, _) -> t in availableTypes }  // 🔥 Фильтруем только доступные
                        .forEach { (t, label) ->
                            FilterChip(
                                selected = type == t,
                                onClick = { type = t },  // Убрали проверку isAvailable
                                label = {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = when (t) {
                                        "DEPARTURE" -> Color(0xFF1976D2).copy(alpha = 0.2f)
                                        "TRANSFER" -> Color(0xFFFF9800).copy(alpha = 0.2f)
                                        "COMPLETION" -> Color(0xFF388E3C).copy(alpha = 0.2f)
                                        else -> MaterialTheme.colorScheme.secondaryContainer
                                    }
                                )
                            )
                        }
                }

                // 🔥 ОТКУДА (только для TRANSFER — read-only из активной)
                if (type == "TRANSFER" && activeTrip != null) {
                    Text("Откуда:", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = "📍 ${activeTrip.projectName} (${activeTrip.city})",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    )
                }

                // 🔥 КУДА (выбор проекта) — для TRANSFER можно сменить
                Text(
                    if (type == "COMPLETION") "Проект (завершение):" else "Проект:",
                    style = MaterialTheme.typography.labelLarge
                )
                OutlinedButton(
                    onClick = { if (type != "COMPLETION") showProjectPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = type != "COMPLETION"
                ) {
                    Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        projectName.ifBlank { "Выберите проект" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 🔥 ДАТА
                Text(
                    when (type) {
                        "DEPARTURE" -> "Дата выезда:"
                        "TRANSFER" -> "Дата переезда:"
                        "COMPLETION" -> "Дата возвращения:"
                        else -> "Дата:"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(formattedDate)
                }

                // 🔥 ГОРОД (кроме COMPLETION — там read-only)
                if (type != "COMPLETION") {
                    Text(
                        if (type == "TRANSFER") "Город назначения:" else "Город:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    OutlinedTextField(
                        value = city,
                        onValueChange = { city = it },
                        placeholder = { Text("Например, Москва") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else if (city.isNotBlank()) {
                    // Для COMPLETION показываем read-only город из активной
                    Text("Город завершения:", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = city,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 🔥 ТРАНСПОРТ
                Text(
                    when (type) {
                        "DEPARTURE" -> "Транспорт:"
                        "TRANSFER" -> "Чем переезжаете:"
                        "COMPLETION" -> "Обратный транспорт:"
                        else -> "Транспорт:"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
                OutlinedTextField(
                    value = transport,
                    onValueChange = { transport = it },
                    placeholder = { Text("Поезд №123") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // 🔥 ЗАМЕТКИ
                Text("Заметки:", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    placeholder = { Text("Дополнительная информация") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 3
                )

                // 🆕 ПУНКТЫ СЛЕДОВАНИЯ (Drag-and-Drop интерфейс)
                HorizontalDivider()
                Text("📍 Пункты следования", style = MaterialTheme.typography.labelLarge)
                
                if (waypoints.isEmpty()) {
                    Text(
                        "Нет промежуточных пунктов",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                } else {
                    WaypointsDragDropList(
                        waypoints = waypoints,
                        onReorder = { newOrder -> waypoints = newOrder },
                        onRemove = { index -> waypoints = waypoints.filterIndexed { i, _ -> i != index } }
                    )
                }
                
                AssistChip(
                    onClick = {
                        waypoints = waypoints + Waypoint(
                            id = java.util.UUID.randomUUID().toString(),
                            city = "",
                            address = "",
                            order = waypoints.size
                        )
                    },
                    label = { Text("Добавить пункт") },
                    leadingIcon = {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    }
                )

                // 🔥 УЧАСТНИКИ (в самом низу)
                HorizontalDivider()
                Text("Участники командировки", style = MaterialTheme.typography.labelLarge)

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Текущий пользователь (неудаляемый)
                    AssistChip(
                        onClick = {},
                        label = { Text(userName.ifBlank { "Вы" }) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Person,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )

                    // Добавленные участники (удаляемые)
                    selectedParticipants.filter { it != userId }.forEach { participantId ->
                        val participant = employees.find { it.id == participantId }
                        if (participant != null) {
                            InputChip(
                                selected = true,
                                onClick = { selectedParticipants = selectedParticipants - participantId },
                                label = { Text(participant.name, maxLines = 1) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        "Удалить",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }

                    // Кнопка "+" (если есть кого добавить)
                    if (availableToAdd.isNotEmpty()) {
                        AssistChip(
                            onClick = { showParticipantPicker = true },
                            label = { Text("Добавить") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Add,
                                    null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }

                // 💡 Подсказка если все сотрудники заняты
                if (availableToAdd.isEmpty() && employees.size > 1) {
                    Text(
                        "ℹ️ Все остальные сотрудники уже в активных командировках",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newTrip = BusinessTrip(
                        id = trip?.id ?: java.util.UUID.randomUUID().toString(),
                        userId = userId,
                        projectId = projectId,
                        projectName = projectName,
                        type = type,
                        date = date,
                        city = city.trim(),
                        waypoints = waypoints,  // 🆕 Пункты следования
                        participants = selectedParticipants.toList(),
                        transport = transport.trim(),
                        notes = notes.trim(),
                        createdAt = trip?.createdAt ?: System.currentTimeMillis()
                    )
                    onConfirm(newTrip)
                },
                enabled = projectId.isNotBlank() || type == "COMPLETION"
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )

    // Диалог выбора проекта
    if (showProjectPicker) {
        AlertDialog(
            onDismissRequest = { showProjectPicker = false },
            title = { Text("Куда едете?") },
            text = {
                LazyColumn {
                    items(projects.filter { it.isActive }) { proj ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                projectId = proj.id
                                projectName = proj.name
                                showProjectPicker = false
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(proj.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // Диалог выбора участников (с учётом занятости)
    if (showParticipantPicker) {
        AlertDialog(
            onDismissRequest = { showParticipantPicker = false },
            title = { Text("Добавить участника") },
            text = {
                if (availableToAdd.isEmpty()) {
                    Text("Нет доступных сотрудников (все заняты в других командировках)")
                } else {
                    LazyColumn {
                        items(availableToAdd) { emp ->
                            val wasInPrevious = emp.id in previousParticipants
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedParticipants = selectedParticipants + emp.id
                                    showParticipantPicker = false
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    null,
                                    tint = if (wasInPrevious) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(emp.name, style = MaterialTheme.typography.bodyMedium)
                                    if (wasInPrevious && type == "TRANSFER") {
                                        Text(
                                            "из предыдущей командировки",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // Диалог выбора даты
    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.let {
                java.time.LocalDate.of(it.year, it.monthNumber, it.dayOfMonth)
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val jd = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        date = LocalDate(jd.year, jd.monthValue, jd.dayOfMonth)
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Отмена") }
            }
        ) { DatePicker(state = pickerState) }
    }
}
// 🆕 Drag-and-Drop список для пунктов следования
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WaypointsDragDropList(
    waypoints: List<Waypoint>,
    onReorder: (List<Waypoint>) -> Unit,
    onRemove: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(
            items = waypoints,
            key = { it.id }
        ) { waypoint ->
            val index = waypoints.indexOfFirst { it.id == waypoint.id }
            WaypointItem(
                waypoint = waypoint,
                index = index,
                onValueChange = { updated ->
                    val newWaypoints = waypoints.toMutableList()
                    newWaypoints[index] = updated
                    onReorder(newWaypoints)
                },
                onRemove = { onRemove(index) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WaypointItem(
    waypoint: Waypoint,
    index: Int,
    onValueChange: (Waypoint) -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "#${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(30.dp)
            )
            
            Column(Modifier.weight(1f)) {
                OutlinedTextField(
                    value = waypoint.city,
                    onValueChange = { onValueChange(waypoint.copy(city = it)) },
                    label = { Text("Город") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = waypoint.address,
                    onValueChange = { onValueChange(waypoint.copy(address = it)) },
                    label = { Text("Адрес") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
            
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    "Удалить",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
