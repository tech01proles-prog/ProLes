package com.example.prolestimesheet.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.model.Notification
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    viewModel: TimesheetViewModel,
    onBack: () -> Unit,
    initialPayload: String? = null,  // 🆕 ДОБАВЛЕНО
    modifier: Modifier = Modifier
) {
    val notifications by viewModel.notifications.collectAsState()
    var selectedNotification by remember { mutableStateOf<Notification?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadNotifications()
    }

    // 🆕 АВТООТКРЫТИЕ: ищем уведомление по payload
    LaunchedEffect(notifications, initialPayload) {
        if (initialPayload.isNullOrBlank()) return@LaunchedEffect
        if (notifications.isEmpty()) return@LaunchedEffect

        kotlinx.coroutines.delay(200)

        val target = notifications.firstOrNull { it.payload == initialPayload }
        if (target != null) {
            android.util.Log.d("Notifications", "🎯 Найдено: ${target.id}")
            selectedNotification = target
            if (!target.isRead) {
                viewModel.markNotificationRead(target.id)
            }
        } else {
            android.util.Log.d("Notifications", "⚠️ Payload не найден в списке")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Уведомления") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    val hasUnread = notifications.any { !it.isRead }
                    if (hasUnread) {
                        // Кнопка "Прочитать все"
                        IconButton(
                            onClick = {
                                // 🔥 Оптимистичное обновление: сразу помечаем все как прочитанные
                                notifications.forEach { notif ->
                                    if (!notif.isRead) {
                                        viewModel.markNotificationRead(notif.id)
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.DoneAll, "Прочитать все")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (notifications.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.NotificationsOff, null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Уведомлений пока нет",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                items(notifications, key = { it.id }) { notif ->
                    NotificationCard(
                        notification = notif,
                        onClick = {
                            selectedNotification = notif
                            if (!notif.isRead) viewModel.markNotificationRead(notif.id)
                        }
                    )
                }
            }
        }
    }

    // Диалог детальной информации
    selectedNotification?.let { notif ->
        AlertDialog(
            onDismissRequest = { selectedNotification = null },
            title = { Text(notif.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NotificationTypeIcon(notif.type)
                        Column {
                            Text(
                                notif.senderName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                formatTimestamp(notif.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        notif.message,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedNotification = null }) { Text("OK") }
            }
        )
    }
}


@Composable
private fun NotificationCard(
    notification: Notification,
    onClick: () -> Unit
) {
    val bgColor = if (notification.isRead)
        MaterialTheme.colorScheme.surface
    else
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            NotificationTypeIcon(notification.type)

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        notification.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    if (!notification.isRead) {
                        Box(
                            modifier = Modifier.size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                        )
                    }
                }
                Text(
                    notification.senderName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    notification.message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    formatTimestamp(notification.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NotificationTypeIcon(type: String) {
    val (icon, color) = when (type) {
        "VACATION" -> Icons.Default.BeachAccess to Color(0xFF0277BD)
        "TRIP" -> Icons.Default.Train to Color(0xFF5C6BC0)
        "DAYOFF_WEEKDAY" -> Icons.Default.EventNote to Color(0xFFFF6F00)
        "GENERAL" -> Icons.Default.Notifications to MaterialTheme.colorScheme.primary
        else -> Icons.Default.Info to Color.Gray
    }
    Box(
        modifier = Modifier.size(40.dp).background(color.copy(alpha = 0.15f), MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
    }
}

private fun formatTimestamp(millis: Long): String {
    val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))
    return sdf.format(Date(millis))
}