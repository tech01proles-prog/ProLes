package com.example.prolestimesheet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.model.NotificationPreferences
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import com.example.prolestimesheet.ui.theme.ProlesCanvas

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    viewModel: TimesheetViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val serverPrefs by viewModel.notificationPreferences.collectAsState()
    var prefs by remember { mutableStateOf(serverPrefs) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverPrefs) { prefs = serverPrefs }
    LaunchedEffect(Unit) { viewModel.loadNotificationPreferences() }

    fun save() {
        saving = true
        message = null
        viewModel.saveNotificationPreferences(prefs) { ok ->
            saving = false
            message = if (ok) "✅ Настройки сохранены" else "❌ Не удалось сохранить настройки"
        }
    }

    Scaffold(
        containerColor = ProlesCanvas,
        modifier = modifier,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ProlesCanvas),
                title = { Text("Настройки уведомлений") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Настройки применяются к уведомлениям внутри ProLes и к FCM-пушам. Telegram — отдельный канал.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            message?.let {
                Text(it, color = if (it.startsWith("✅")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }

            val items = listOf(
                "tripEnabled" to "✈️ Командировки",
                "tripVisibleToAll" to "👥 Показывать командировки коллегам",
                "vacationEnabled" to "🏖 Отпуска",
                "dayoffEnabled" to "🌞 Выходные",
                "expenseEnabled" to "💸 Расходы",
                "payrollEnabled" to "💰 Зарплата",
                "ticketEnabled" to "🎫 Билеты",
                "tripChangeEnabled" to "🔄 Изменения поездок",
                "vacationDecisionEnabled" to "✅ Решение по отпуску",
                "ticketReceiptEnabled" to "🧷 Чек по билету",
                "chatMessageEnabled" to "💬 PRO-Chat"
            )

            items.forEach { (key, label) ->
                val checked = when (key) {
                    "tripEnabled" -> prefs.tripEnabled
                    "tripVisibleToAll" -> prefs.tripVisibleToAll
                    "vacationEnabled" -> prefs.vacationEnabled
                    "dayoffEnabled" -> prefs.dayoffEnabled
                    "expenseEnabled" -> prefs.expenseEnabled
                    "payrollEnabled" -> prefs.payrollEnabled
                    "ticketEnabled" -> prefs.ticketEnabled
                    "tripChangeEnabled" -> prefs.tripChangeEnabled
                    "vacationDecisionEnabled" -> prefs.vacationDecisionEnabled
                    "ticketReceiptEnabled" -> prefs.ticketReceiptEnabled
                    else -> prefs.chatMessageEnabled
                }
                Card {
                    ListItem(
                        headlineContent = { Text(label) },
                        trailingContent = {
                            Switch(
                                checked = checked,
                                onCheckedChange = { value ->
                                    prefs = when (key) {
                                        "tripEnabled" -> prefs.copy(tripEnabled = value)
                                        "tripVisibleToAll" -> prefs.copy(tripVisibleToAll = value)
                                        "vacationEnabled" -> prefs.copy(vacationEnabled = value)
                                        "dayoffEnabled" -> prefs.copy(dayoffEnabled = value)
                                        "expenseEnabled" -> prefs.copy(expenseEnabled = value)
                                        "payrollEnabled" -> prefs.copy(payrollEnabled = value)
                                        "ticketEnabled" -> prefs.copy(ticketEnabled = value)
                                        "tripChangeEnabled" -> prefs.copy(tripChangeEnabled = value)
                                        "vacationDecisionEnabled" -> prefs.copy(vacationDecisionEnabled = value)
                                        "ticketReceiptEnabled" -> prefs.copy(ticketReceiptEnabled = value)
                                        else -> prefs.copy(chatMessageEnabled = value)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            Card {
                ListItem(
                    headlineContent = { Text("🤖 Telegram") },
                    supportingContent = {
                        Text(
                            when {
                                prefs.telegramLinked -> "Аккаунт привязан"
                                prefs.telegramEnabled -> "Включён, но чат ещё не привязан"
                                else -> "Выключен"
                            }
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = prefs.telegramEnabled,
                            onCheckedChange = { prefs = prefs.copy(telegramEnabled = it) }
                        )
                    }
                )
            }

            if (prefs.telegramEnabled && !prefs.telegramLinked) {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Код привязки", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            prefs.telegramLinkCode ?: "Код появится после сохранения",
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                }
            }

            Card {
                ListItem(
                    headlineContent = { Text("📧 Email") },
                    supportingContent = { Text(prefs.email.ifBlank { "Email не задан" }) },
                    trailingContent = {
                        Switch(
                            checked = prefs.emailEnabled,
                            onCheckedChange = { prefs = prefs.copy(emailEnabled = it) }
                        )
                    }
                )
            }

            if (prefs.emailEnabled) {
                OutlinedTextField(
                    value = prefs.email,
                    onValueChange = { prefs = prefs.copy(email = it) },
                    label = { Text("Email") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            HorizontalDivider()
            Button(
                onClick = ::save,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saving) "Сохранение…" else "Сохранить")
            }

            if (prefs.telegramEnabled) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
