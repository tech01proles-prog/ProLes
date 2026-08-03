package com.example.prolestimesheet.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.model.Currency
import com.example.prolestimesheet.model.RateType
import com.example.prolestimesheet.model.User
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import java.util.UUID
import androidx.compose.material3.HorizontalDivider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeesScreen(
    viewModel: TimesheetViewModel,
    onBack: () -> Unit,
    onNavigateToEmployeeProfile: (String) -> Unit, // 🆕 навигация к профилю
    modifier: Modifier = Modifier
) {
    val employees by viewModel.employees.collectAsState()
    var editingUser by remember { mutableStateOf<User?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Сотрудники") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } }
            )
        },
        floatingActionButton = {
            if (viewModel.canCreate("employees")) {  // 🆕 Проверка прав
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, "Добавить")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(employees, key = { it.id }) { user ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToEmployeeProfile(user.id) } // 🆕 Клик → мини-профиль
                ) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Person, null,
                            modifier = Modifier.size(40.dp).padding(end = 12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                user.name.ifBlank {
                                    listOf(user.lastName, user.firstName, user.middleName)
                                        .filter { it.isNotBlank() }.joinToString(" ")
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                user.position.ifBlank { "Должность не указана" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Роль: ${user.role} | Ставка: ${user.defaultRate} ${user.defaultCurrency}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (viewModel.canEdit("employees")) {  // 🆕 Проверка прав
                            IconButton(onClick = { editingUser = user }) {
                                Icon(Icons.Default.Edit, "Редактировать", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }

    editingUser?.let { user ->
        EmployeeDialog(
            user = user, isNew = false,
            onDismiss = { editingUser = null },
            onConfirm = { updated ->
                viewModel.updateUser(updated)
                editingUser = null
            },
            onDelete = {  // 🆕 ДОБАВЛЕНО
                viewModel.deleteUser(user.id)
                editingUser = null
            }
        )
    }

    if (showAddDialog) {
        EmployeeDialog(
            user = User(
                id = UUID.randomUUID().toString(),
                name = "", role = "employee",
                defaultRateType = RateType.HOURLY,
                defaultRate = 0.0, defaultCurrency = Currency.RUB
            ),
            isNew = true,
            onDismiss = { showAddDialog = false },
            onConfirm = { newUser ->
                viewModel.createUser(newUser)
                showAddDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeeDialog(
    user: User,
    isNew: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (User) -> Unit,
    onDelete: (() -> Unit)? = null  // 🆕 ДОБАВЛЕНО
) {
    var lastName by remember { mutableStateOf(user.lastName) }
    var firstName by remember { mutableStateOf(user.firstName) }
    var middleName by remember { mutableStateOf(user.middleName) }
    var login by remember { mutableStateOf(user.login) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(user.position) }
    var role by remember { mutableStateOf(user.role) }
    var roleExpanded by remember { mutableStateOf(false) }
    var rateType by remember { mutableStateOf(user.defaultRateType) }
    var rate by remember { mutableStateOf(user.defaultRate.toString()) }
    var currency by remember { mutableStateOf(user.defaultCurrency) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Новый сотрудник" else "Редактировать сотрудника") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = lastName, onValueChange = { lastName = it },
                    label = { Text("Фамилия") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = firstName, onValueChange = { firstName = it },
                    label = { Text("Имя") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = middleName, onValueChange = { middleName = it },
                    label = { Text("Отчество") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = position, onValueChange = { position = it },
                    label = { Text("Должность") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                OutlinedTextField(value = login, onValueChange = { login = it },
                    label = { Text("Логин") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(if (isNew) "Пароль" else "Новый пароль (пусто = не менять)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null
                            )
                        }
                    }
                )

                ExposedDropdownMenuBox(
                    expanded = roleExpanded,
                    onExpandedChange = { roleExpanded = !roleExpanded }
                ) {
                    OutlinedTextField(
                        value = role, onValueChange = {}, readOnly = true,
                        label = { Text("Роль") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = roleExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = roleExpanded, onDismissRequest = { roleExpanded = false }) {
                        listOf("employee", "logist", "tech", "office", "director", "admin").forEach { r ->
                            DropdownMenuItem(text = { Text(r) }, onClick = { role = r; roleExpanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val fullName = listOf(lastName, firstName, middleName)
                    .filter { it.isNotBlank() }.joinToString(" ")
                val updated = user.copy(
                    lastName = lastName.trim(), firstName = firstName.trim(), middleName = middleName.trim(),
                    name = fullName, login = login.trim(), position = position.trim(), role = role,
                    defaultRateType = rateType, defaultRate = rate.toDoubleOrNull() ?: 0.0,
                    defaultCurrency = currency, newPassword = password.ifBlank { null }
                )
                onConfirm(updated)
            }, enabled = firstName.isNotBlank() && login.isNotBlank()) { Text("Сохранить") }
            // 🗑 КНОПКА УДАЛЕНИЯ (только для существующих сотрудников)
            if (!isNew && onDelete != null) {
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Удалить")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )

    // Диалог подтверждения удаления
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("⚠️ Удалить сотрудника?") },
            text = {
                Text("Сотрудник \"${user.name}\" будет удалён навсегда. Это действие нельзя отменить!")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete?.invoke()
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена") }
            }
        )
    }
}