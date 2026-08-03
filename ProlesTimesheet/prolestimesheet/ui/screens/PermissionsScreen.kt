package com.example.prolestimesheet.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.prolestimesheet.model.*
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.prolestimesheet.model.UserEffectivePermissionDto

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PermissionsScreen(
    viewModel: TimesheetViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val user by viewModel.user.collectAsState()
    val roles by viewModel.roles.collectAsState()
    val employees by viewModel.employees.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val isSuperAdmin = user?.role == "superadmin"

    // Загружаем данные при открытии
    LaunchedEffect(Unit) {
        viewModel.loadRoles()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("🔐 Права доступа", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (isSuperAdmin) "Superadmin: полный доступ" else "Только просмотр ролей",
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
        Column(modifier = modifier.padding(padding).fillMaxSize()) {
            // Вкладки
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("👥 Роли (${roles.size})") },
                    icon = { Icon(Icons.Default.Shield, null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("🧑‍💼 Сотрудники (${employees.size})") },
                    icon = { Icon(Icons.Default.People, null) }
                )
            }

            when (selectedTab) {
                0 -> RolesTab(
                    roles = roles,
                    viewModel = viewModel,
                    isSuperAdmin = isSuperAdmin,
                    onEditRole = { role ->
                        Log.d("PermissionsScreen", "💾 Saving role: ${role.name}")
                        val permissionsData = role.permissions.map { perm ->
                            com.example.prolestimesheet.network.RolePermissionUpdateDto(
                                permission = perm.permission,
                                canView = perm.canView,
                                canCreate = perm.canCreate,
                                canEdit = perm.canEdit,
                                canDelete = perm.canDelete
                            )
                        }
                        viewModel.updateRolePermissions(role.id, permissionsData)
                    }
                )
                1 -> UsersTab(
                    employees = employees,
                    viewModel = viewModel,
                    isSuperAdmin = isSuperAdmin
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 🔹 Вкладка "Роли"
// ═══════════════════════════════════════════════════════════
@Composable
private fun RolesTab(
    roles: List<Role>,
    viewModel: TimesheetViewModel,
    isSuperAdmin: Boolean,
    onEditRole: (Role) -> Unit
) {
    var editingRole by remember { mutableStateOf<Role?>(null) }

    if (roles.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Загрузка ролей...", style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(roles, key = { it.id }) { role ->
                RoleCard(
                    role = role,
                    isSuperAdmin = isSuperAdmin,
                    onClick = { if (isSuperAdmin) editingRole = role }
                )
            }
        }
    }

    // Диалог редактирования роли
    editingRole?.let { role ->
        RoleEditDialog(
            role = role,
            onDismiss = { editingRole = null },
            onSave = { updatedRole ->
                val permissionsData = updatedRole.permissions.map { perm ->
                    com.example.prolestimesheet.network.RolePermissionUpdateDto(
                        permission = perm.permission,
                        canView = perm.canView,
                        canCreate = perm.canCreate,
                        canEdit = perm.canEdit,
                        canDelete = perm.canDelete
                    )
                }
                viewModel.updateRolePermissions(updatedRole.id, permissionsData)
                editingRole = null
            }
        )
    }
}

@Composable
private fun RoleCard(
    role: Role,
    isSuperAdmin: Boolean,
    onClick: () -> Unit
) {
    val viewCount = role.permissions.count { it.canView }
    val createCount = role.permissions.count { it.canCreate }
    val editCount = role.permissions.count { it.canEdit }
    val deleteCount = role.permissions.count { it.canDelete }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isSuperAdmin, onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (role.name) {
                "superadmin" -> Color(0xFFE1BEE7).copy(alpha = 0.3f)
                "admin" -> Color(0xFFBBDEFB).copy(alpha = 0.3f)
                "director" -> Color(0xFFC8E6C9).copy(alpha = 0.3f)
                "employee" -> Color(0xFFFFF9C4).copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (role.name) {
                                "superadmin" -> Icons.Default.Security
                                "admin" -> Icons.Default.AdminPanelSettings
                                "director" -> Icons.Default.BusinessCenter
                                "employee" -> Icons.Default.Person
                                else -> Icons.Default.Shield
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            role.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        role.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isSuperAdmin) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Редактировать",
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Только просмотр",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // Сводка прав
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PermissionStat("👁 Просмотр", viewCount, Color(0xFF2196F3))
                PermissionStat("➕ Создание", createCount, Color(0xFF4CAF50))
                PermissionStat("✏️ Изменение", editCount, Color(0xFFFF9800))
                PermissionStat("🗑 Удаление", deleteCount, Color(0xFFF44336))
            }
        }
    }
}

@Composable
private fun PermissionStat(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 🔹 Диалог редактирования роли
// ═══════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleEditDialog(
    role: Role,
    onDismiss: () -> Unit,
    onSave: (Role) -> Unit
) {
    var permissions by remember { mutableStateOf(role.permissions) }

    // Маппинг permission key → display name
    val permissionNames = mapOf(
        "projects" to "📁 Проекты",
        "employees" to "👥 Сотрудники",
        "payroll" to "💰 Зарплата",
        "expenses_all" to "💸 Все расходы",
        "business_trips_all" to "🚆 Все командировки",
        "vacations_all" to "🏖 Все отпуска",
        "dayoffs_all" to "🌞 Все выходные",
        "notifications" to "🔔 Уведомления",
        "analytics" to "📊 Аналитика",
        "cost_calculation" to "🧮 Себестоимость",
        "tickets" to "🎫 Билеты",
        "permissions" to "🔐 Права доступа"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("✏️ ${role.displayName}", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Настройте права для этой роли",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Заголовок таблицы
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Раздел",
                        modifier = Modifier.width(140.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    PermissionHeader("👁")
                    PermissionHeader("➕")
                    PermissionHeader("✏️")
                    PermissionHeader("🗑")
                }

                Spacer(Modifier.height(8.dp))

                // Строки с правами
                permissions.forEach { perm ->
                    PermissionRow(
                        label = permissionNames[perm.permission] ?: perm.permission,
                        canView = perm.canView,
                        canCreate = perm.canCreate,
                        canEdit = perm.canEdit,
                        canDelete = perm.canDelete,
                        onViewChange = { newValue ->
                            permissions = permissions.map {
                                if (it.permission == perm.permission) it.copy(canView = newValue) else it
                            }
                        },
                        onCreateChange = { newValue ->
                            permissions = permissions.map {
                                if (it.permission == perm.permission) it.copy(canCreate = newValue) else it
                            }
                        },
                        onEditChange = { newValue ->
                            permissions = permissions.map {
                                if (it.permission == perm.permission) it.copy(canEdit = newValue) else it
                            }
                        },
                        onDeleteChange = { newValue ->
                            permissions = permissions.map {
                                if (it.permission == perm.permission) it.copy(canDelete = newValue) else it
                            }
                        }
                    )
                    HorizontalDivider(
                        Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Быстрые действия
                Text("⚡ Быстрые действия:", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            permissions = permissions.map { it.copy(canView = true) }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Вкл. всё 👁", style = MaterialTheme.typography.labelSmall) }

                    OutlinedButton(
                        onClick = {
                            permissions = permissions.map {
                                it.copy(canView = false, canCreate = false, canEdit = false, canDelete = false)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Выкл. всё", style = MaterialTheme.typography.labelSmall) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(role.copy(permissions = permissions)) }
            ) { Text("💾 Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun PermissionHeader(icon: String) {
    Text(
        icon,
        modifier = Modifier.width(40.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun PermissionRow(
    label: String,
    canView: Boolean,
    canCreate: Boolean,
    canEdit: Boolean,
    canDelete: Boolean,
    onViewChange: (Boolean) -> Unit,
    onCreateChange: (Boolean) -> Unit,
    onEditChange: (Boolean) -> Unit,
    onDeleteChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.width(140.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1
        )
        PermissionCheckbox(canView, onViewChange)
        PermissionCheckbox(canCreate, onCreateChange)
        PermissionCheckbox(canEdit, onEditChange)
        PermissionCheckbox(canDelete, onDeleteChange)
    }
}

@Composable
private fun PermissionCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier.width(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = if (checked) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
            )
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 🔹 Вкладка "Сотрудники" (override'ы)
// ═══════════════════════════════════════════════════════════
@Composable
private fun UsersTab(
    employees: List<User>,
    viewModel: TimesheetViewModel,
    isSuperAdmin: Boolean
) {
    var editingUser by remember { mutableStateOf<User?>(null) }
    var searchText by remember { mutableStateOf("") }

    val filteredEmployees = remember(employees, searchText) {
        if (searchText.isBlank()) employees
        else employees.filter {
            it.name.contains(searchText, ignoreCase = true) ||
                    it.login.contains(searchText, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Поиск
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            label = { Text("🔍 Поиск сотрудника") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            singleLine = true
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredEmployees, key = { it.id }) { emp ->
                EmployeeOverrideCard(
                    employee = emp,
                    canEdit = isSuperAdmin || emp.role != "superadmin",
                    onClick = { editingUser = emp }
                )
            }
        }
    }

    editingUser?.let { emp ->
        UserOverridesDialog(
            employee = emp,
            viewModel = viewModel,
            onDismiss = { editingUser = null }
        )
    }
}

@Composable
private fun EmployeeOverrideCard(
    employee: User,
    canEdit: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canEdit, onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            employee.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        employee.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "${employee.position} • ${employee.role}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (canEdit) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "Настроить",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Нельзя редактировать",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserOverridesDialog(
    employee: User,
    viewModel: TimesheetViewModel,
    onDismiss: () -> Unit
) {
    // 🎯 Загружаем effective-права с сервера (уже merged)
    var effectivePermissions by remember { mutableStateOf<List<UserEffectivePermissionDto>?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(employee.id) {
        isLoading = true
        effectivePermissions = viewModel.loadUserPermissions(employee.id)
        isLoading = false
    }

    // 🎯 Локальное состояние для UI (nullable для три-стейта)
// Инициализируем из effective-прав: если isOverride=true → значение, иначе null
    var uiOverrides by remember(effectivePermissions) {
        mutableStateOf<List<UserPermissionOverride>>(
            effectivePermissions?.map { eff ->
                UserPermissionOverride(
                    permission = eff.permission,
                    canView = if (eff.isOverride) eff.canView else null,
                    canCreate = if (eff.isOverride) eff.canCreate else null,
                    canEdit = if (eff.isOverride) eff.canEdit else null,
                    canDelete = if (eff.isOverride) eff.canDelete else null,
                    isOverride = eff.isOverride
                )
            } ?: emptyList()
        )
    }

    val permissionNames = mapOf(
        "projects" to "📁 Проекты",
        "employees" to "👥 Сотрудники",
        "payroll" to "💰 Зарплата",
        "expenses_all" to "💸 Все расходы",
        "business_trips_all" to "🚆 Все командировки",
        "vacations_all" to "🏖 Все отпуска",
        "dayoffs_all" to "🌞 Все выходные",
        "notifications" to "🔔 Уведомления",
        "analytics" to "📊 Аналитика",
        "cost_calculation" to "🧮 Себестоимость",
        "tickets" to "🎫 Билеты",
        "permissions" to "🔐 Права доступа"
    )

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = {
            Column {
                Text("⚙️ ${employee.name}", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Роль: ${employee.role} • Переопределения прав",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            if (isLoading || effectivePermissions == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Загрузка прав...")
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    // Легенда
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "💡 Клик переключает:  ✅ → ❌",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                LegendChip("✅ Да", Color(0xFF4CAF50))
                                LegendChip("❌ Нет", Color(0xFFF44336))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    // Для каждого permission показываем effective значение + isOverride
                    effectivePermissions!!.sortedBy { it.permission }.forEach { eff ->
                        val uiOverride = uiOverrides.firstOrNull { it.permission == eff.permission }
                        EffectivePermissionRow(
                            label = permissionNames[eff.permission] ?: eff.permission,
                            effective = eff,
                            uiOverride = uiOverride,
                            onToggle = { action ->
                                // 🔄 Логика переключения: ✅ → ❌
                                val current = uiOverrides.firstOrNull { it.permission == eff.permission }
                                val updated = when (action) {
                                    "view" -> {
                                        val next = getNextTriState(current?.canView, eff.canView, eff.isOverride)
                                        current?.copy(canView = next, isOverride = next != null)
                                    }
                                    "create" -> {
                                        val next = getNextTriState(current?.canCreate, eff.canCreate, eff.isOverride)
                                        current?.copy(canCreate = next, isOverride = next != null)
                                    }
                                    "edit" -> {
                                        val next = getNextTriState(current?.canEdit, eff.canEdit, eff.isOverride)
                                        current?.copy(canEdit = next, isOverride = next != null)
                                    }
                                    "delete" -> {
                                        val next = getNextTriState(current?.canDelete, eff.canDelete, eff.isOverride)
                                        current?.copy(canDelete = next, isOverride = next != null)
                                    }
                                    else -> current
                                }
                                if (updated != null) {
                                    uiOverrides = uiOverrides.map {
                                        if (it.permission == eff.permission) updated else it
                                    }
                                }
                            }
                        )
                        HorizontalDivider(
                            Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    // Кнопка сброса всех override'ов
                    OutlinedButton(
                        onClick = {
                            uiOverrides = uiOverrides.map {
                                UserPermissionOverride(
                                    permission = it.permission,
                                    canView = null,
                                    canCreate = null,
                                    canEdit = null,
                                    canDelete = null,
                                    isOverride = false
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Сбросить все (использовать роль)")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isSaving = true
                    // 🎯 Отправляем только overrides (где isOverride=true)
                    val dataToSave = uiOverrides
                        .filter { it.isOverride }
                        .map { override ->
                            UserPermissionOverrideDto(
                                permission = override.permission,
                                canView = override.canView,
                                canCreate = override.canCreate,
                                canEdit = override.canEdit,
                                canDelete = override.canDelete
                            )
                        }
                    Log.d("PermissionsScreen", "💾 Saving ${dataToSave.size} overrides for ${employee.name}")
                    viewModel.updateUserPermissions(employee.id, dataToSave)
                    onDismiss()
                },
                enabled = !isLoading && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("💾 Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Отмена") }
        }
    )
}

/**
 * 🔄 Логика три-стейта: роль → ✅ → ❌ → роль
 */
private fun getNextTriState(currentUi: Boolean?, effectiveValue: Boolean, isOverride: Boolean): Boolean? {
    return when {
        !isOverride -> true           // роль → ✅
        currentUi == true -> false     // ✅ → ❌
        currentUi == false -> null     // ❌ → роль
        else -> true
    }
}

@Composable
private fun EffectivePermissionRow(
    label: String,
    effective: UserEffectivePermissionDto,
    uiOverride: UserPermissionOverride?,
    onToggle: (action: String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1
        )
        // 🎯 Показываем UI-значение (nullable), а effective используем только для отображения когда null
        OverrideTriChip(
            uiValue = uiOverride?.canView,
            effectiveValue = effective.canView,
            onClick = { onToggle("view") }
        )
        OverrideTriChip(
            uiValue = uiOverride?.canCreate,
            effectiveValue = effective.canCreate,
            onClick = { onToggle("create") }
        )
        OverrideTriChip(
            uiValue = uiOverride?.canEdit,
            effectiveValue = effective.canEdit,
            onClick = { onToggle("edit") }
        )
        OverrideTriChip(
            uiValue = uiOverride?.canDelete,
            effectiveValue = effective.canDelete,
            onClick = { onToggle("delete") }
        )
    }
}

@Composable
private fun OverrideTriChip(
    uiValue: Boolean?,
    effectiveValue: Boolean,
    onClick: () -> Unit
) {
    // 🎯 Если uiValue == null → показываем effective value (роль)
    // Если uiValue != null → показываем override value
    val displayValue = uiValue ?: effectiveValue
    val isOverride = uiValue != null

    val (icon, color) = if (displayValue) {
        "✅" to Color(0xFF4CAF50)
    } else {
        "❌" to Color(0xFFF44336)
    }

    Surface(
        modifier = Modifier
            .width(40.dp)
            .padding(2.dp)
            .clickable(onClick = onClick),
        color = if (isOverride) color.copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp),
        border = if (isOverride) {
            androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFF9800))  // 🟠 Оранжевая обводка для override
        } else null
    ) {
        Text(
            icon,
            modifier = Modifier.padding(vertical = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun LegendChip(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun OverrideRow(
    label: String,
    override: UserPermissionOverride?,
    onToggle: (action: String, newValue: Boolean?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1
        )
        OverrideTriChip(
            value = override?.canView,
            onClick = {
                val next = when (override?.canView) {
                    null -> true
                    true -> false
                    false -> null
                }
                onToggle("view", next)
            }
        )
        OverrideTriChip(
            value = override?.canCreate,
            onClick = {
                val next = when (override?.canCreate) {
                    null -> true
                    true -> false
                    false -> null
                }
                onToggle("create", next)
            }
        )
        OverrideTriChip(
            value = override?.canEdit,
            onClick = {
                val next = when (override?.canEdit) {
                    null -> true
                    true -> false
                    false -> null
                }
                onToggle("edit", next)
            }
        )
        OverrideTriChip(
            value = override?.canDelete,
            onClick = {
                val next = when (override?.canDelete) {
                    null -> true
                    true -> false
                    false -> null
                }
                onToggle("delete", next)
            }
        )
    }
}

@Composable
private fun OverrideTriChip(value: Boolean?, onClick: () -> Unit) {
    val (icon, color) = when (value) {
        null -> "⚪" to Color.Gray
        true -> "✅" to Color(0xFF4CAF50)
        false -> "❌" to Color(0xFFF44336)
    }

    Surface(
        modifier = Modifier
            .width(40.dp)
            .padding(2.dp)
            .clickable(onClick = onClick),
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            icon,
            modifier = Modifier.padding(vertical = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodySmall
        )
    }
}