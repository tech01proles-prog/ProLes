package com.example.prolestimesheet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.model.User
import com.example.prolestimesheet.ui.theme.*
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun HomeScreen(
    user: User?,
    onRequestVacation: () -> Unit,
    viewModel: TimesheetViewModel? = null,
    onNavigateToTimesheet: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToMyTickets: () -> Unit,
    onNavigateToNotifications: () -> Unit = {},
    onNavigateToNotificationSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    val unreadCount by viewModel?.unreadCount?.collectAsState(initial = 0)
        ?: remember { mutableStateOf(0) }
    val entries = viewModel?.entries?.collectAsState(initial = emptyList())?.value ?: emptyList()
    val vacations = viewModel?.vacations?.collectAsState(initial = emptyList())?.value ?: emptyList()
    val dayOffs = viewModel?.dayOffs?.collectAsState(initial = emptyList())?.value ?: emptyList()

    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val todayEntries = entries.filter { it.date == today && it.userId == user?.id }
    val todayHours = todayEntries.sumOf { it.hours.toDouble() }
    val isOnVacation = vacations.any { today >= it.start && today <= it.end }
    val isWeekend = today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY
    val isUserDayOff = dayOffs.any { it.date == today && it.userId == user?.id }
    val isDayOff = if (isWeekend) !isUserDayOff else isUserDayOff

    val weekStart = today.minus(today.dayOfWeek.ordinal, DateTimeUnit.DAY)
    val weekEntries = entries.filter { it.date in weekStart..today && it.userId == user?.id }
    val weekHours = weekEntries.sumOf { it.hours.toDouble() }
    val targetWeekHours = 40.0
    val weekProgress = (weekHours / targetWeekHours).coerceIn(0.0, 1.0)

    val firstName = user?.firstName?.ifBlank { user.name }?.ifBlank { "Сотрудник" } ?: "Сотрудник"
    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> "Доброе утро"
        in 12..17 -> "Добрый день"
        in 18..22 -> "Добрый вечер"
        else -> "Доброй ночи"
    }
    val dateLabel = "%s, %d %s".format(
        today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("ru")),
        today.dayOfMonth,
        today.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru")).lowercase()
    ).replaceFirstChar { it.uppercase() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ProlesCanvas)
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Главная", style = MaterialTheme.typography.headlineMedium)
                Text(dateLabel, style = MaterialTheme.typography.bodySmall, color = ProlesMuted)
            }
            IconButton(onClick = onNavigateToNotifications) {
                BadgedBox(
                    badge = {
                        if (unreadCount > 0) {
                            Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) }
                        }
                    }
                ) {
                    Icon(Icons.Default.NotificationsNone, contentDescription = "Уведомления")
                }
            }
            IconButton(onClick = onNavigateToNotificationSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Настройки")
            }
            Surface(
                modifier = Modifier.size(40.dp).clickable(onClick = onNavigateToProfile),
                shape = CircleShape,
                color = ProlesPrimarySoft
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(firstName.take(1).uppercase(), color = ProlesPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }

        ProlesCard(shape = RoundedCornerShape(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = ProlesPrimarySoft
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.WbSunny, null, tint = ProlesPrimary, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(greeting, style = MaterialTheme.typography.bodySmall, color = ProlesMuted)
                    Text(firstName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Сегодня " + when {
                            isOnVacation -> "отпуск"
                            isDayOff -> "выходной"
                            else -> "рабочий день"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ProlesMuted
                    )
                }
                Text("%.1f ч".format(todayHours), style = MaterialTheme.typography.headlineMedium, color = ProlesPrimary)
            }
        }

        ProlesCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProlesIconBadge(Icons.Default.AccessTime, ProlesPrimary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            isOnVacation -> "Вы в отпуске"
                            isDayOff -> "Сегодня выходной"
                            todayHours > 0 -> "Хороший прогресс"
                            else -> "День только начался"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        when {
                            isOnVacation -> "Рабочие часы сегодня не требуются"
                            isDayOff -> "Можно отдохнуть"
                            else -> "Записей сегодня: %d".format(todayEntries.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ProlesMuted
                    )
                }
                if (!isOnVacation && !isDayOff) {
                    Text("%.1f ч".format(todayHours), style = MaterialTheme.typography.headlineMedium, color = ProlesPrimary)
                }
            }
        }

        ProlesCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Эта неделя", style = MaterialTheme.typography.titleMedium)
                Text("%.1f / 40 ч".format(weekHours), style = MaterialTheme.typography.labelLarge, color = ProlesPrimary)
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { weekProgress.toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)),
                color = ProlesPrimary,
                trackColor = ProlesRestSoft
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "%d%% отработано · осталось %.1f ч".format(
                    (weekProgress * 100).toInt(),
                    (40.0 - weekHours).coerceAtLeast(0.0)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = ProlesMuted
            )
        }

        Text("Быстрые действия", style = MaterialTheme.typography.titleLarge)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProlesQuickAction(
                Icons.Default.AddCircleOutline,
                "Внести часы",
                "Табель за сегодня",
                ProlesPrimary,
                onNavigateToTimesheet,
                modifier = Modifier.weight(1f)
            )
            ProlesQuickAction(
                Icons.Default.ConfirmationNumber,
                "Мои билеты",
                "Документы поездок",
                ProlesSecondary,
                onNavigateToMyTickets,
                modifier = Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProlesQuickAction(
                Icons.Default.EventAvailable,
                "Отпуск / Отгул",
                "Баланс и заявки",
                ProlesSecondary,
                onRequestVacation,
                modifier = Modifier.weight(1f)
            )
            ProlesQuickAction(
                Icons.Default.NotificationsNone,
                "Уведомления",
                if (unreadCount > 0) "%d непрочитанных".format(unreadCount) else "Все прочитаны",
                ProlesExpense,
                onNavigateToNotifications,
                badge = unreadCount,
                modifier = Modifier.weight(1f)
            )
        }

        ProlesCard(containerColor = ProlesSecondarySoft.copy(alpha = 0.55f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProlesIconBadge(Icons.Default.Lightbulb, ProlesSecondary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Мысль дня", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Лучший способ предсказать будущее — создать его своими действиями.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ProlesMuted
                    )
                }
            }
        }
    }
}
