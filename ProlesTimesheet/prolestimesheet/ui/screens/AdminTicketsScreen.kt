package com.example.prolestimesheet.ui.screens

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.model.Project
import com.example.prolestimesheet.model.User
import com.example.prolestimesheet.network.ApiClient
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import com.example.prolestimesheet.data.LocalDataStore
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.ButtonDefaults
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminTicketsScreen(
    viewModel: TimesheetViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects by viewModel.projects.collectAsState()
    val employees by viewModel.employees.collectAsState()

    var tickets by remember { mutableStateOf<List<com.example.prolestimesheet.network.TicketDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isUploading by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }

    // Загрузка списка билетов
    LaunchedEffect(Unit) {
        loadTickets { list ->
            tickets = list
            isLoading = false
        }
    }

    fun refresh() {
        isLoading = true
        loadTickets { list ->
            tickets = list
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("🎫 Билеты", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${tickets.size} загружено",
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
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, "Обновить")
                    }
                }
            )
        },
        floatingActionButton = {
            if (viewModel.canCreate("tickets")) {  // 🆕
                FloatingActionButton(
                    onClick = { showUploadDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.UploadFile, "Загрузить билет")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = modifier.padding(padding).fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Загрузка билетов...")
                    }
                }
                tickets.isEmpty() -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.ConfirmationNumber,
                            null,
                            modifier = Modifier.size(80.dp),
                            tint = Color.Gray
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Билетов пока нет",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "Нажмите + чтобы загрузить первый билет",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(tickets, key = { it.id }) { ticket ->
                            AdminTicketCard(
                                ticket = ticket,
                                context = context,
                                viewModel = viewModel,  // 🆕 передаём viewModel для удаления
                                onRefresh = { refresh() }  // 🆕 callback для обновления списка
                            )
                        }
                    }
                }
            }

            if (isUploading) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.5f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Card(
                            modifier = Modifier.padding(32.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text("Загрузка билета...")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showUploadDialog) {
        var savedAccountantEmail by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            savedAccountantEmail = com.example.prolestimesheet.data.LocalDataStore.getAccountantEmail(context)
            Log.d("AdminTickets", "📧 Loaded saved accountant email: $savedAccountantEmail")
        }
        UploadTicketDialog(
            projects = projects,
            employees = employees,
            initialAccountantEmail = savedAccountantEmail,
            onDismiss = { showUploadDialog = false },
            onUpload = { projectId, description, amount, currency, sendToAccountant, accountantEmail, recipientIds, fileUri ->
                isUploading = true
                showUploadDialog = false
                scope.launch {
                    // 🆕 Сохраняем email бухгалтера
                    if (sendToAccountant && accountantEmail.isNotBlank()) {
                        com.example.prolestimesheet.data.LocalDataStore.saveAccountantEmail(context, accountantEmail)
                    }

                    val success = ApiClient.uploadTicket(
                        context = context,
                        projectId = projectId,
                        description = description,
                        amount = amount,
                        currency = currency,
                        sendToAccountant = sendToAccountant,
                        accountantEmail = accountantEmail,
                        recipientIds = recipientIds,
                        fileUri = fileUri
                    )
                    isUploading = false
                    if (success) {
                        // 🔥 ИСПРАВЛЕНО: загружаем билеты напрямую, без refresh()
                        loadTickets { list ->
                            tickets = list
                            isLoading = false
                        }
                        android.widget.Toast.makeText(
                            context,
                            if (amount > 0) "✅ Билет загружен + расход ${"%.2f".format(amount)} $currency"
                            else "✅ Билет успешно загружен",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            "❌ Ошибка загрузки билета",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }
}

private fun loadTickets(onLoaded: (List<com.example.prolestimesheet.network.TicketDto>) -> Unit) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        ApiClient.fetchAllTickets()
            .onSuccess { tickets ->
                android.util.Log.d("AdminTickets", "✅ Loaded ${tickets.size} tickets")
                onLoaded(tickets)
            }
            .onFailure { e ->
                android.util.Log.e("AdminTickets", "❌ Failed to load tickets", e)
                onLoaded(emptyList())
            }
    }
}

@Composable
private fun AdminTicketCard(
    ticket: com.example.prolestimesheet.network.TicketDto,
    context: android.content.Context,
    viewModel: TimesheetViewModel,  // 🆕
    onRefresh: () -> Unit           // 🆕
) {
    val projectName = ticket.projectName
    val fileName = ticket.fileName
    val fileType = ticket.fileType
    val fileSize = ticket.fileSize
    val description = ticket.description
    val uploadedAt = ticket.uploadedAt
    val sendToAccountant = ticket.sendToAccountant
    val accountantEmail = ticket.accountantEmail
    val recipients = ticket.recipients

    val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    val formattedDate = dateFormat.format(Date(uploadedAt))
    val formattedSize = when {
        fileSize > 1_000_000 -> "%.1f МБ".format(fileSize / 1_000_000.0)
        fileSize > 1_000 -> "%.1f КБ".format(fileSize / 1_000.0)
        else -> "$fileSize Б"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Заголовок
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        projectName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        formattedDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = when {
                        fileType.contains("pdf") -> Color(0xFFE53935).copy(alpha = 0.15f)
                        fileType.contains("image") -> Color(0xFF1E88E5).copy(alpha = 0.15f)
                        else -> Color(0xFF757575).copy(alpha = 0.15f)
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            when {
                                fileType.contains("pdf") -> Icons.Default.PictureAsPdf
                                fileType.contains("image") -> Icons.Default.Image
                                else -> Icons.Default.Description
                            },
                            null,
                            tint = when {
                                fileType.contains("pdf") -> Color(0xFFE53935)
                                fileType.contains("image") -> Color(0xFF1E88E5)
                                else -> Color(0xFF757575)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Имя файла + размер
//            Text(fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
//            Text(
//                "$formattedSize • $fileType",
//                style = MaterialTheme.typography.bodySmall,
//                color = Color.Gray
//            )

            // Описание
            if (description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        description,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 💰 Стоимость билета (если есть)
            if (ticket.amount > 0.0) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFFFF3E0),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.AttachMoney,
                                null,
                                tint = Color(0xFFE65100),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Стоимость:",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFE65100)
                            )
                        }
                        Text(
                            "${"%.2f".format(ticket.amount)} ${ticket.currency}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE65100)
                        )
                    }
                }
            }

            // Получатели
            if (recipients.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "👥 Получатели (${recipients.size}):",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                Spacer(Modifier.height(4.dp))
                recipients.take(5).forEach { recipient ->
                    val name = recipient.userName
                    Row(
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(20.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    name.take(1).uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(name, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (recipients.size > 5) {
                    Text(
                        "... и ещё ${recipients.size - 5}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                    )
                }
            }

            // Бухгалтер
            if (sendToAccountant && accountantEmail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Email,
                        null,
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFF4CAF50)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Отправлено: $accountantEmail",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 🗑 Кнопка удаления билета
            var showDeleteConfirm by remember { mutableStateOf(false) }
            var isDeleting by remember { mutableStateOf(false) }

            if (viewModel.canDelete("tickets")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        if (isDeleting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("Удалить")
                    }
                }
            }

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Удалить билет?") },
                    text = { Text("Файл «${ticket.fileName}» будет безвозвратно удалён.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDeleteConfirm = false
                                isDeleting = true
                                viewModel.deleteTicket(ticket.id) { success ->
                                    isDeleting = false
                                    if (success) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "🗑️ Билет удалён",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        onRefresh()  // 🆕 обновляем список через callback
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            "❌ Ошибка удаления",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
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

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            com.example.prolestimesheet.ui.components.DownloadProgressButton(
                url = ticket.downloadUrl,
                fileName = fileName,
                onDownload = {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val uri = com.example.prolestimesheet.network.DownloadManager.download(
                            context, ticket.downloadUrl, fileName,
                            when {
                                fileType.contains("pdf") -> "application/pdf"
                                fileType.contains("image") -> "image/jpeg"
                                else -> "application/octet-stream"
                            }
                        )
                        if (uri != null) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(
                                    context, "✅ $fileName сохранён", android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                },
                onRetry = {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        com.example.prolestimesheet.network.DownloadManager.retry(
                            context, ticket.downloadUrl, fileName,
                            when {
                                fileType.contains("pdf") -> "application/pdf"
                                fileType.contains("image") -> "image/jpeg"
                                else -> "application/octet-stream"
                            }
                        )
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun UploadTicketDialog(
    projects: List<Project>,
    employees: List<User>,
    initialAccountantEmail: String = "",  // 🆕
    onDismiss: () -> Unit,
    onUpload: (
        projectId: String,
        description: String,
        amount: Double,          // 🆕
        currency: String,        // 🆕
        sendToAccountant: Boolean,
        accountantEmail: String,
        recipientIds: List<String>,
        fileUri: Uri
    ) -> Unit
) {
    val context = LocalContext.current

    var selectedProjectId by remember { mutableStateOf("") }
    var selectedProjectName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }              // 🆕
    var currency by remember { mutableStateOf("RUB") }         // 🆕
    var sendToAccountant by remember { mutableStateOf(false) }
    var accountantEmail by remember { mutableStateOf("") }
    var selectedRecipients by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }

    var showProjectPicker by remember { mutableStateOf(false) }
    var showRecipientPicker by remember { mutableStateOf(false) }

    // Launcher для выбора файла
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedFileUri = uri
            selectedFileName = getFileName(context, uri) ?: "Выбранный файл"
        }
    }

    // 🆕 Автоматически подставляем сохранённый email при открытии
    LaunchedEffect(initialAccountantEmail) {
        if (initialAccountantEmail.isNotBlank() && accountantEmail.isBlank()) {
            accountantEmail = initialAccountantEmail
            sendToAccountant = true  // Включаем галочку, если есть сохранённый email
        }
    }


    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📤 Загрузить билет") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Проект
                Text("Проект *", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(
                    onClick = { showProjectPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        selectedProjectName.ifBlank { "Выберите проект" },
                        maxLines = 1
                    )
                }

                // Файл
                Text("Билет *", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        selectedFileName.ifBlank { "Выберите файл (PDF, JPG...)" },
                        maxLines = 1
                    )
                }

                // Описание
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Описание") },
                    placeholder = { Text("Например: Билет Москва-Питер 15.07") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 3
                )

                // 💰 СТОИМОСТЬ БИЛЕТА
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { newValue ->
                            if (newValue.isEmpty() || newValue.all { it.isDigit() || it == '.' || it == ',' }) {
                                amount = newValue.replace(',', '.')
                            }
                        },
                        label = { Text("Стоимость") },
                        placeholder = { Text("0.00") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(2f),
                        singleLine = true
                    )
                    var currencyExpanded by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { currencyExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(currency) }
                        DropdownMenu(
                            expanded = currencyExpanded,
                            onDismissRequest = { currencyExpanded = false }
                        ) {
                            listOf("RUB", "BYN", "USD", "EUR").forEach { cur ->
                                DropdownMenuItem(
                                    text = { Text(cur) },
                                    onClick = {
                                        currency = cur
                                        currencyExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Получатели
                Text(
                    "👥 Получатели (${selectedRecipients.size})",
                    style = MaterialTheme.typography.labelMedium
                )

                if (selectedRecipients.isNotEmpty()) {
                    // Отображение выбранных получателей
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
//                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        selectedRecipients.toList().forEach { recipientId ->
                            val recipient = employees.find { it.id == recipientId }
                            if (recipient != null) {
                                InputChip(
                                    selected = true,
                                    onClick = {
                                        selectedRecipients = selectedRecipients.toMutableSet().apply { remove(recipientId) }.toSet()
                                    },
                                    label = { Text(recipient.name, maxLines = 1) },
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
                    }
                }

                val availableToAdd = employees.filter { it.id !in selectedRecipients }
                if (availableToAdd.isNotEmpty()) {
                    AssistChip(
                        onClick = { showRecipientPicker = true },
                        label = { Text("Добавить получателя") },
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

                HorizontalDivider()

                // Бухгалтер
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = sendToAccountant,
                        onCheckedChange = { sendToAccountant = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Отправить бухгалтеру",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Бухгалтер получит билет на почту",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }

                if (sendToAccountant) {
                    OutlinedTextField(
                        value = accountantEmail,
                        onValueChange = { accountantEmail = it },
                        label = { Text("Email бухгалтера") },
                        placeholder = { Text("accounting@company.ru") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedProjectId.isNotBlank() && selectedFileUri != null) {
                        onUpload(
                            selectedProjectId,
                            description,
                            amount.toDoubleOrNull() ?: 0.0,        // 🆕
                            currency,                              // 🆕
                            sendToAccountant,
                            accountantEmail,
                            selectedRecipients.toList(),
                            selectedFileUri!!
                        )
                    }
                },
                enabled = selectedProjectId.isNotBlank() && selectedFileUri != null
            ) { Text("Загрузить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )

    // Диалог выбора проекта
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
                                    selectedProjectId = proj.id
                                    selectedProjectName = proj.name
                                    showProjectPicker = false
                                }
                                .padding(vertical = 12.dp),
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

    // Диалог выбора получателей
    if (showRecipientPicker) {
        val availableToAdd = employees.filter { it.id !in selectedRecipients }
        AlertDialog(
            onDismissRequest = { showRecipientPicker = false },
            title = { Text("Добавить получателя") },
            text = {
                LazyColumn {
                    items(availableToAdd) { emp ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedRecipients = selectedRecipients + emp.id
                                    showRecipientPicker = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        emp.name.take(1).uppercase(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(emp.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    emp.position,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

/**
 * Получает реальное имя файла из Uri
 */
private fun getFileName(context: android.content.Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    result = it.getString(nameIndex)
                }
            }
        }
    }
    if (result == null) {
        result = uri.path?.let { path ->
            val cut = path.lastIndexOf('/')
            if (cut != -1) path.substring(cut + 1) else path
        }
    }
    return result
}