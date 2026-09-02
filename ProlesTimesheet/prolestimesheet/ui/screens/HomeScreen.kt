package com.example.prolestimesheet.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.prolestimesheet.model.User
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.*
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
    onNavigateToMyTickets: () -> Unit,  // 🆕 ДОБАВЛЕНО
    onNavigateToNotifications: () -> Unit = {}, // 🆕 ДОБАВЛЕНО
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    // 🆕 Реактивный счётчик непрочитанных уведомлений
    val unreadCount by viewModel?.unreadCount?.collectAsState(initial = 0)
        ?: remember { mutableStateOf(0) }
    val currentHour = LocalTime.now().hour
    val greeting = when (currentHour) {
        in 5..11 -> "Доброе утро" to "☀️"
        in 12..17 -> "Добрый день" to "🌤"
        in 18..22 -> "Добрый вечер" to "🌆"
        else -> "Доброй ночи" to "🌙"
    }

    // 🆕 Анимированная плавающая иконка
    val infiniteTransition = rememberInfiniteTransition(label = "float")
    val floatOffset by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 10f,
        animationSpec = infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "float"
    )

    // 🆕 Получаем данные для карточек (явное указание типов)
    val entries = viewModel?.entries?.collectAsState(initial = emptyList())?.value ?: emptyList()
    val vacations = viewModel?.vacations?.collectAsState(initial = emptyList())?.value ?: emptyList()
    val dayOffs = viewModel?.dayOffs?.collectAsState(initial = emptyList())?.value ?: emptyList()

    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val todayEntries = entries.filter { it.date == today && it.userId == user?.id }
    val todayHours = todayEntries.sumOf { it.hours.toDouble() }
    val isOnVacation = vacations.any { today >= it.start && today <= it.end }

    // 🆕 Инвертированная логика выходных
    val isWeekend = today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY
    val isUserDayOff = dayOffs.any { it.date == today && it.userId == user?.id }
    val isDayOff = if (isWeekend) !isUserDayOff else isUserDayOff

    // 🆕 Статистика недели
    val weekStart = today.minus(today.dayOfWeek.ordinal, DateTimeUnit.DAY)
    val weekEntries = entries.filter { it.date in weekStart..today && it.userId == user?.id }
    val weekHours = weekEntries.sumOf { it.hours.toDouble() }
    val targetWeekHours = 40.0
    val weekProgress = (weekHours / targetWeekHours).coerceIn(0.0, 1.0)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 🌟 Hero-блок с градиентом и приветствием
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF667eea), Color(0xFF764ba2), Color(0xFFf093fb)),
                        start = Offset(0f, 0f),
                        end = Offset(1000f, 1000f)
                    )
                )
                .padding(24.dp)
        ) {
            // 🎈 Декоративный плавающий круг
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .align(Alignment.TopEnd)
                    .offset(y = floatOffset.dp)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
            )

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(greeting.second, fontSize = 32.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(greeting.first, color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = user?.firstName?.ifBlank { user?.name ?: "Сотрудник" } ?: user?.name ?: "Сотрудник",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("ru")) +
                            ", ${today.dayOfMonth} " +
                            today.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru")).lowercase(),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // 📅 Карточка "Сегодня"
        TodayStatusCard(
            hours = todayHours,
            isVacation = isOnVacation,
            isDayOff = isDayOff,
            entriesCount = todayEntries.size
        )

        // 📊 Прогресс недели
        WeekProgressCard(hours = weekHours, target = targetWeekHours, progress = weekProgress)

        // ⚡ Компактные быстрые действия
        Text("Быстрые действия", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactActionChip(
                icon = Icons.Default.BeachAccess,
                title = "Отпуск",
                gradient = listOf(Color(0xFF4FC3F7), Color(0xFF0288D1)),
                onClick = onRequestVacation,
                modifier = Modifier.weight(1f)
            )
            CompactActionChip(
                icon = Icons.Default.ConfirmationNumber,
                title = "Мои билеты",
                gradient = listOf(Color(0xFFFF8A65), Color(0xFFFF5722)),
                onClick = onNavigateToMyTickets,
                modifier = Modifier.weight(1f)
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactActionChip(
                icon = Icons.Default.AddCircle,
                title = "Часы",
                gradient = listOf(Color(0xFF81C784), Color(0xFF388E3C)),
                onClick = onNavigateToTimesheet,
                modifier = Modifier.weight(1f)
            )
            // 🆕 Плитка уведомлений с badge
            CompactActionChip(
                icon = Icons.Default.Notifications,
                title = "Уведомления",
                gradient = listOf(Color(0xFFFFB74D), Color(0xFFFF7043)),
                onClick = onNavigateToNotifications,
                modifier = Modifier.weight(1f),
                badgeCount = unreadCount
            )
        }

        // 💡 Мотивационная карточка
        MotivationCard()
    }
}

// 🔹 Карточка статуса дня
@Composable
private fun TodayStatusCard(hours: Double, isVacation: Boolean, isDayOff: Boolean, entriesCount: Int) {
    data class StatusInfo(val color: Color, val icon: ImageVector, val title: String, val subtitle: String)
    val statusInfo = when {
        isVacation -> StatusInfo(Color(0xFF4FC3F7), Icons.Default.BeachAccess, "Вы в отпуске", "Наслаждайтесь отдыхом! 🏖")
        isDayOff -> StatusInfo(Color(0xFFFF8A65), Icons.Default.EventNote, "Сегодня выходной", "Можно отдохнуть 🌞")
        hours > 0 -> StatusInfo(Color(0xFF81C784), Icons.Default.CheckCircle, "Отличный прогресс!", "Записей сегодня: $entriesCount")
        else -> StatusInfo(Color(0xFFFFB74D), Icons.Default.Schedule, "День только начался", "Не забудьте отметить часы")
    }

    val bgColor = statusInfo.color
    val icon = statusInfo.icon
    val title = statusInfo.title
    val subtitle = statusInfo.subtitle

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor.copy(alpha = 0.2f))
    ) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(bgColor.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = bgColor, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!isVacation && !isDayOff) {
                Text(
                    "${"%.1f".format(hours)} ч",
                    style = MaterialTheme.typography.headlineMedium,
                    color = bgColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// 🔹 Прогресс недели
@Composable
private fun WeekProgressCard(hours: Double, target: Double, progress: Double) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Эта неделя", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${"%.1f".format(hours)} / ${"%.0f".format(target)} ч",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress.toFloat() },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${(progress * 100).toInt()}% выполнено",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// 🔹 Компактная плитка (с badge для уведомлений)
@Composable
private fun CompactActionChip(
    icon: ImageVector,
    title: String,
    gradient: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier,
    badgeCount: Int = 0
) {
    Card(
        modifier = modifier.height(56.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Brush.linearGradient(gradient)).padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            // 🆕 Badge с количеством непрочитанных
            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(20.dp)
                        .background(Color(0xFFD32F2F), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// 🔹 Мотивационная карточка
@Composable
private fun MotivationCard() {
    val quotes = listOf(
        "«Маленькие шаги каждый день приводят к большим результатам» 💪",
        "«Профессионализм — это делать вещи хорошо, даже когда никто не смотрит» ⭐",
        "«Лучший способ предсказать будущее — создать его» 🚀",
        "«Каждый день — это новая возможность стать лучше» 🌱"
    )
    val quote = remember { quotes.random() }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
    ) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("💡", fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Text(quote, style = MaterialTheme.typography.bodyMedium,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}