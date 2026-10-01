package com.example.prolestimesheet.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.model.Project
import com.example.prolestimesheet.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyTicketUploadDialog(
    projects: List<Project>,
    onDismiss: () -> Unit,
    onUploaded: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedProjectId by remember { mutableStateOf("") }
    var selectedProjectName by remember { mutableStateOf("") }
    var selectedSubprojectId by remember { mutableStateOf<String?>(null) }
    var selectedSubprojectName by remember { mutableStateOf("") }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }
    var selectedReceiptUri by remember { mutableStateOf<Uri?>(null) }
    var selectedReceiptFileName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("RUB") }
    var showProjectPicker by remember { mutableStateOf(false) }
    var showSubprojectPicker by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }

    fun displayName(uri: Uri, fallback: String): String {
        return context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        }?.takeIf { it.isNotBlank() } ?: fallback
    }

    val ticketPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedFileUri = uri
            selectedFileName = displayName(uri, "ticket")
        }
    }

    val receiptPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedReceiptUri = uri
            selectedReceiptFileName = displayName(uri, "receipt")
        }
    }

    AlertDialog(
        onDismissRequest = { if (!uploading) onDismiss() },
        icon = { Icon(Icons.Default.ConfirmationNumber, null) },
        title = { Text("Добавить билет") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    OutlinedButton(
                        onClick = { showProjectPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(selectedProjectName.ifBlank { "Выберите проект" }, maxLines = 1)
                    }
                }

                if (selectedProjectId.isNotBlank() &&
                    projects.firstOrNull { it.id == selectedProjectId }?.subprojects?.any { it.isActive } == true
                ) {
                    item {
                        OutlinedButton(
                            onClick = { showSubprojectPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.AccountTree, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(selectedSubprojectName.ifBlank { "Без подпроекта" }, maxLines = 1)
                        }
                    }
                }

                item {
                    OutlinedButton(
                        onClick = { ticketPickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            selectedFileName.ifBlank { "Выберите билет (PDF, фото, файл)" },
                            maxLines = 1
                        )
                    }
                }

                item {
                    OutlinedButton(
                        onClick = { receiptPickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ReceiptLong, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            selectedReceiptFileName.ifBlank { "Добавить чек к билету (необязательно)" },
                            maxLines = 1
                        )
                    }
                    if (selectedReceiptUri != null) {
                        TextButton(
                            onClick = {
                                selectedReceiptUri = null
                                selectedReceiptFileName = ""
                            }
                        ) {
                            Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Удалить чек")
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Описание") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 3
                    )
                }

                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = amount,
                            onValueChange = {
                                if (it.isEmpty() || it.all { ch -> ch.isDigit() || ch == '.' || ch == ',' }) {
                                    amount = it.replace(',', '.')
                                }
                            },
                            label = { Text("Стоимость") },
                            modifier = Modifier.weight(2f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )

                        var currencyExpanded by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { currencyExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(currency)
                            }
                            DropdownMenu(
                                expanded = currencyExpanded,
                                onDismissRequest = { currencyExpanded = false }
                            ) {
                                listOf("RUB", "BYN", "USD", "EUR").forEach { value ->
                                    DropdownMenuItem(
                                        text = { Text(value) },
                                        onClick = {
                                            currency = value
                                            currencyExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        "Для сотрудников: билет создаётся только от вашего аккаунта. Получатели и отправка бухгалтеру доступны в руководящем интерфейсе.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val fileUri = selectedFileUri ?: return@Button
                    if (selectedProjectId.isBlank()) {
                        Toast.makeText(context, "Выберите проект", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    uploading = true
                    scope.launch(Dispatchers.IO) {
                        val success = ApiClient.uploadTicket(
                            context = context,
                            projectId = selectedProjectId,
                            subprojectId = selectedSubprojectId,
                            description = description,
                            amount = amount.toDoubleOrNull() ?: 0.0,
                            currency = currency,
                            sendToAccountant = false,
                            accountantEmail = "",
                            recipientIds = emptyList(),
                            fileUri = fileUri,
                            receiptUri = selectedReceiptUri
                        )
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            uploading = false
                            if (success) {
                                Toast.makeText(context, "✅ Билет загружен", Toast.LENGTH_SHORT).show()
                                onUploaded()
                            } else {
                                Toast.makeText(context, "❌ Не удалось загрузить билет", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                enabled = !uploading && selectedProjectId.isNotBlank() && selectedFileUri != null
            ) {
                Text(if (uploading) "Загрузка…" else "Загрузить")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !uploading
            ) {
                Text("Отмена")
            }
        }
    )

    if (showProjectPicker) {
        AlertDialog(
            onDismissRequest = { showProjectPicker = false },
            title = { Text("Выберите проект") },
            text = {
                LazyColumn {
                    items(projects.filter { it.isActive }) { project ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedProjectId = project.id
                                    selectedProjectName = project.name
                                    selectedSubprojectId = null
                                    selectedSubprojectName = ""
                                    showProjectPicker = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(project.name)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showSubprojectPicker) {
        AlertDialog(
            onDismissRequest = { showSubprojectPicker = false },
            title = { Text("Выберите подпроект") },
            text = {
                LazyColumn {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedSubprojectId = null
                                    selectedSubprojectName = ""
                                    showSubprojectPicker = false
                                }
                                .padding(vertical = 12.dp)
                        ) {
                            Text("Без подпроекта")
                        }
                    }
                    items(
                        projects.firstOrNull { it.id == selectedProjectId }
                            ?.subprojects
                            ?.filter { it.isActive }
                            .orEmpty()
                    ) { subproject ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedSubprojectId = subproject.id
                                    selectedSubprojectName = subproject.name
                                    showSubprojectPicker = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AccountTree, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(subproject.name)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}
