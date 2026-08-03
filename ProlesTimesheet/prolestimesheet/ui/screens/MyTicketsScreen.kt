package com.example.prolestimesheet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.prolestimesheet.network.ApiClient
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyTicketsScreen(
    viewModel: TimesheetViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tickets by remember { mutableStateOf<List<com.example.prolestimesheet.network.TicketDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        android.util.Log.d("MyTickets", "🔄 Loading tickets...")
        loadTickets { list ->
            android.util.Log.d("MyTickets", "📋 Received ${list.size} tickets")
            tickets = list
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🎫 Мои билеты") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = modifier.padding(padding).fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator()
            } else if (tickets.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.ConfirmationNumber,
                        null,
                        modifier = Modifier.size(80.dp),
                        tint = Color.Gray
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("У вас пока нет билетов", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Ждите.. ждите...",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(tickets) { ticket ->
                        TicketCard(
                            ticket = ticket,
                            onDownload = { /* больше не нужен */ },
                            context = context  // 🆕 передаём контекст
                        )
                    }
                }
            }
        }
    }
}


private fun loadTickets(onLoaded: (List<com.example.prolestimesheet.network.TicketDto>) -> Unit) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        ApiClient.fetchMyTickets()
            .onSuccess { tickets ->
                android.util.Log.d("MyTickets", "✅ Loaded ${tickets.size} tickets")
                onLoaded(tickets)
            }
            .onFailure { e ->
                android.util.Log.e("MyTickets", "❌ Failed to load tickets", e)
                onLoaded(emptyList())
            }
    }
}

@Composable
private fun TicketCard(
    ticket: com.example.prolestimesheet.network.TicketDto,
    onDownload: () -> Unit,
    context: android.content.Context
) {
    val projectName = ticket.projectName
    val fileName = ticket.fileName
    val fileType = ticket.fileType
    val description = ticket.description
    val uploadedAt = ticket.uploadedAt

    val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
    val formattedDate = dateFormat.format(Date(uploadedAt))

    val mimeType = when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                    Text(formattedDate, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Icon(
                    when {
                        fileType.contains("pdf") -> Icons.Default.PictureAsPdf
                        fileType.contains("image") -> Icons.Default.Image
                        else -> Icons.Default.Description
                    },
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            if (description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            // 🆕 НОВАЯ КНОПКА С ПРОГРЕССОМ
            com.example.prolestimesheet.ui.components.DownloadProgressButton(
                url = ticket.downloadUrl,
                fileName = fileName,
                onDownload = {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val uri = com.example.prolestimesheet.network.DownloadManager.download(
                            context, ticket.downloadUrl, fileName, mimeType
                        )
                        if (uri != null) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(
                                    context, "✅ $fileName сохранён", android.widget.Toast.LENGTH_LONG
                                ).show()
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mimeType)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                },
                onRetry = {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        com.example.prolestimesheet.network.DownloadManager.retry(
                            context, ticket.downloadUrl, fileName, mimeType
                        )
                    }
                }
            )
        }
    }
}