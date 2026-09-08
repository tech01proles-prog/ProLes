package com.example.prolestimesheet.ui.screens

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.prolestimesheet.model.RateType
import com.example.prolestimesheet.model.User
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel


// 🔹 Модель периода статистики
enum class StatsPeriod { MONTH, ALL }

// 🔹 Компонент карточки статистики (исправленный)
@Composable
private fun StatCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,        // 🔥 Явный заголовок карточки
    value: String,
    subtitle: String,
    color: Color,  // 🔥 Переименовано для ясности
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(75.dp),  // 🔥 Уменьшена высота: 90 → 75
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.15f)  // 🔥 Чуть более насыщенный фон
        )
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxSize(),  // 🔥 Чуть меньше отступы
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 🔥 Верх: иконка + подпись ЧТО это
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)  // 🔥 Иконка чуть меньше
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = title,  // 🔥 Теперь это явный заголовок: "Часы", "Дни" и т.д.
                    style = MaterialTheme.typography.labelMedium,  // 🔥 Крупнее
                    color = color,  // 🔥 Контрастный цвет
                    fontWeight = FontWeight.Medium
                )
            }
            // 🔥 Низ: значение + пояснение
            Column {
                Text(
                    text = value,  // 🔥 Главное число — крупно и жирно
                    style = MaterialTheme.typography.titleLarge,  // 🔥 Было titleMedium → стало titleLarge
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface  // 🔥 Чёрный/белый для контраста
                )
                Text(
                    text = subtitle,  // 🔥 Пояснение мелким, но читаемым шрифтом
                    style = MaterialTheme.typography.labelSmall,  // 🔥 Было bodySmall → labelSmall для компактности
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)  // 🔥 Чуть ярче
                )
            }
        }
    }
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun ProfileScreen(
    user: User?,
    viewModel: TimesheetViewModel,
    onLogout: () -> Unit,
    onNavigateToMyExpenses: () -> Unit,
    onNavigateToMyTrips: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToMyTickets: () -> Unit,  // 🆕
    onNavigateToStats: () -> Unit,  // 🆕 Статистика сотрудника
    onNavigateToExpensesIncomes: () -> Unit,  // 🆕 Расходы/Доходы
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    // 🆕 Получаем актуальный профиль с сервера
    val freshProfile = viewModel.getFreshProfile(user?.id) ?: user
    val expenses by viewModel.expenses.collectAsState()
    val incomes by viewModel.incomes.collectAsState()

    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(scroll)
    ) {
        // 🔹 Заголовок
        Text("Мой профиль", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))

        // 🔹 Верхний блок: Фото + Имя + Должность (в одну строку)
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 🔹 Фото/Аватар (слева)
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                // 🔹 Имя + Должность (справа)
                Column {
                    Text(
                        user?.firstName ?: "Ты кто?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        user?.position?.ifBlank { "Должность не указана" } ?: "Должность не указана",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

//        // 🔹 Сводка по ставке (только для чтения)
//        Text("Текущая ставка", style = MaterialTheme.typography.titleMedium)
//        Spacer(Modifier.height(8.dp))
//
//        Card(modifier = Modifier.fillMaxWidth()) {
//            Row(
//                modifier = Modifier.padding(16.dp).fillMaxWidth(),
//                horizontalArrangement = Arrangement.SpaceBetween,
//                verticalAlignment = Alignment.CenterVertically
//            ) {
//                Column {
//                    Text("Тип", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
//                    Text(
//                        when(freshProfile?.defaultRateType ?: RateType.HOURLY) {
//                            RateType.PER_DAY -> "Дневная"
//                            RateType.HOURLY -> "Почасовая"
//                            RateType.PER_PROJECT -> "Договорная"
//                            RateType.FIXED -> "Оклад"
//                        },
//                        style = MaterialTheme.typography.bodyMedium,
//                        fontWeight = FontWeight.Medium
//                    )
//                }
//                Column(horizontalAlignment = Alignment.End) {
//                    Text("Размер", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
//                    Text(
//                        "${"%.2f".format(freshProfile?.defaultRate ?: 0.0)} ${freshProfile?.defaultCurrency?.name ?: "RUB"}",
//                        style = MaterialTheme.typography.bodyMedium,
//                        fontWeight = FontWeight.Medium,
//                        color = MaterialTheme.colorScheme.primary
//                    )
//                }
//            }
//        }
//
//        Spacer(Modifier.height(24.dp))

        // 📊 Статистика сотрудника
        Text(
            "Статистика",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        // Переключатель периода
        var statsPeriod by remember { mutableStateOf(StatsPeriod.MONTH) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatsPeriod.entries.forEach { period ->
                FilterChip(
                    selected = statsPeriod == period,
                    onClick = { statsPeriod = period },
                    label = {
                        Text(
                            when (period) {
                                StatsPeriod.MONTH -> "Месяц"
                                StatsPeriod.ALL -> "Всё время"
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = when (period) {
                                StatsPeriod.MONTH -> Icons.Default.DateRange
                                StatsPeriod.ALL -> Icons.Default.AllInclusive
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        val entries by viewModel.entries.collectAsState()
        val myEntriesAll = entries.filter { it.userId == user?.id }
        val myExpensesAll = expenses.filter { it.userId == user?.id }
        val myIncomesAll = incomes.filter { it.userId == user?.id }

        val periodEntries = if (statsPeriod == StatsPeriod.MONTH) {
            myEntriesAll.filter { it.date.year == currentYear && it.date.monthNumber == currentMonth }
        } else {
            myEntriesAll
        }
        val periodExpensesList = if (statsPeriod == StatsPeriod.MONTH) {
            myExpensesAll.filter { it.date.year == currentYear && it.date.monthNumber == currentMonth }
        } else {
            myExpensesAll
        }
        val periodIncomesList = if (statsPeriod == StatsPeriod.MONTH) {
            myIncomesAll.filter { it.date.year == currentYear && it.date.monthNumber == currentMonth }
        } else {
            myIncomesAll
        }

        val periodHours = periodEntries.sumOf { it.hours.toDouble() }
        val periodWorkDays = periodEntries.map { it.date }.distinct().size
        val periodExpensesAmount = periodExpensesList.sumOf { it.amount }
        val periodIncomesAmount = periodIncomesList.sumOf { it.amount }
        val periodExpensesWithReceipt = periodExpensesList.count { it.receiptSubmitted }
        val periodExpensesWithoutReceipt = periodExpensesList.count { !it.receiptSubmitted }
        val avgHoursPerDay = if (periodWorkDays > 0) periodHours / periodWorkDays else 0.0
        val receiptRate = if (periodExpensesList.isNotEmpty()) {
            periodExpensesWithReceipt.toDouble() / periodExpensesList.size.toDouble()
        } else {
            1.0
        }
        val rhythmScore = (avgHoursPerDay / 8.0 * 100.0).coerceAtMost(100.0)
        val periodLabel = if (statsPeriod == StatsPeriod.MONTH) monthDisplayName else "за всё время"

        // 🌲 Визуальная сводка в стиле web analytics
        ProfileAnalyticsSummaryCard(
            periodLabel = periodLabel,
            hours = periodHours,
            workDays = periodWorkDays,
            expenses = periodExpensesAmount,
            incomes = periodIncomesAmount,
            avgHoursPerDay = avgHoursPerDay,
            rhythmScore = rhythmScore,
            receiptRate = receiptRate
        )

        Spacer(Modifier.height(10.dp))

        // Четыре главные метрики
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                icon = Icons.Default.AccessTime,
                title = "Часы",
                value = "${"%.1f".format(periodHours)} ч.",
                subtitle = "отработано",
                color = Color(0xFF2E7D32),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = Icons.Default.CalendarToday,
                title = "Дни",
                value = "$periodWorkDays",
                subtitle = if (statsPeriod == StatsPeriod.MONTH) "в этом месяце" else "с записями",
                color = Color(0xFF0277BD),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                icon = Icons.Default.TrendingDown,
                title = "Расходы",
                value = "${"%.0f".format(periodExpensesAmount)} ₽",
                subtitle = if (periodExpensesList.isEmpty()) "нет операций"
                else "${periodExpensesList.size} операций",
                color = Color(0xFFEF6C00),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                title = "Доходы",
                value = "${"%.0f".format(periodIncomesAmount)} ₽",
                subtitle = if (periodIncomesList.isEmpty()) "нет поступлений"
                else "${periodIncomesList.size} операций",
                color = Color(0xFF2E7D32),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Короткие аналитические выводы
        ProfileAnalyticsInsightRow(
            avgHoursPerDay = avgHoursPerDay,
            rhythmScore = rhythmScore,
            receiptRate = receiptRate,
            expensesWithoutReceipt = periodExpensesWithoutReceipt
        )


        // ═══════════════════════════════════════════════════════════
        // 🔘 Быстрый доступ — сетка 2×2
        // ═══════════════════════════════════════════════════════════
        Text(
            "Быстрый доступ",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Строка 1: Расходы/Доходы + Командировки
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NavigationTile(
                title = "Сальдо",
                subtitle = "${userExpenses.size + incomes.filter { it.userId == user?.id }.size} операций",
                emoji = "💰",
                gradientColors = listOf(Color(0xFFB3E5FC), Color(0xFF81D4FA)),
                onClick = onNavigateToExpensesIncomes,
                modifier = Modifier.weight(1f)
            )
            NavigationTile(
                title = "Командировки",
                subtitle = "${viewModel.trips.value.count { it.userId == user?.id }} поездок",
                emoji = "🚆",
                gradientColors = listOf(Color(0xFFE1BEE7), Color(0xFFCE93D8)),
                onClick = onNavigateToMyTrips,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        // Строка 2: Уведомления + Билеты
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val unreadCount by viewModel.unreadCount.collectAsState()
            NavigationTile(
                title = "Уведомления",
                subtitle = if (unreadCount > 0) "$unreadCount непрочитанных" else "Все прочитаны",
                emoji = "🔔",
                gradientColors = listOf(Color(0xFFFFE0B2), Color(0xFFFFCC80)),
                badgeCount = unreadCount,
                onClick = onNavigateToNotifications,
                modifier = Modifier.weight(1f)
            )
            NavigationTile(
                title = "Билеты",
                subtitle = "Документы",
                emoji = "🎫",
                gradientColors = listOf(Color(0xFFC8E6C9), Color(0xFFA5D6A7)),
                onClick = onNavigateToMyTickets,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        // Строка 3: Статистика + Выход
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NavigationTile(
                title = "Статистика",
                subtitle = "Доходы и расходы",
                emoji = "📊",
                gradientColors = listOf(Color(0xFF81C784), Color(0xFF64B5F6)),
                onClick = onNavigateToStats,
                modifier = Modifier.weight(1f)
            )
            // Пустая плашка для симметрии (можно заменить на другую функцию)
            NavigationTile(
                title = "Выход",
                subtitle = "Завершить сессию",
                emoji = "🚪",
                gradientColors = listOf(Color(0xFFEF9A9A), Color(0xFFE57373)),
                onClick = onLogout,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        // 🔹 Кнопка выхода (внизу, полная ширина)
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Выйти из аккаунта")
        }
    }
}


@Composable
private fun ProfileAnalyticsSummaryCard(
    periodLabel: String,
    hours: Double,
    workDays: Int,
    expenses: Double,
    incomes: Double,
    avgHoursPerDay: Double,
    rhythmScore: Double,
    receiptRate: Double
) {
    val summary = when {
        workDays == 0 -> "Пока нет рабочих записей за выбранный период."
        avgHoursPerDay >= 8.0 && receiptRate >= 0.9 -> "Ритм стабильный: хорошая загрузка и порядок с расходными документами."
        avgHoursPerDay >= 8.0 -> "Загрузка высокая. Стоит проверить несколько операций без подтверждающих документов."
        avgHoursPerDay >= 6.0 -> "Рабочий ритм ровный, но есть резерв для более плотной загрузки."
        else -> "Рабочая нагрузка пока невысокая — период стоит оценивать вместе с графиком задач."
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1D976C),
                            Color(0xFF2196F3)
                        )
                    )
                )
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Личная аналитика",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            periodLabel,
                            color = Color.White.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.18f)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "${rhythmScore.roundToInt()}%",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "ритм",
                                color = Color.White.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnalyticsMiniMetric(
                        modifier = Modifier.weight(1f),
                        title = "Рабочий ритм",
                        value = if (workDays > 0) "${"%.1f".format(avgHoursPerDay)} ч/д" else "—",
                        tint = Color.White
                    )
                    AnalyticsMiniMetric(
                        modifier = Modifier.weight(1f),
                        title = "Расходы",
                        value = formatRubles(expenses),
                        tint = Color.White
                    )
                    AnalyticsMiniMetric(
                        modifier = Modifier.weight(1f),
                        title = "Доходы",
                        value = formatRubles(incomes),
                        tint = Color.White
                    )
                }

                Text(
                    summary,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AnalyticsMiniMetric(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    tint: Color
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White.copy(alpha = 0.12f))
            .padding(10.dp)
    ) {
        Text(
            title,
            color = tint.copy(alpha = 0.78f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            color = tint,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ProfileAnalyticsInsightRow(
    avgHoursPerDay: Double,
    rhythmScore: Double,
    receiptRate: Double,
    expensesWithoutReceipt: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        InsightChip(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Speed,
            title = "Темп",
            value = if (avgHoursPerDay > 0) "${"%.1f".format(avgHoursPerDay)} ч/день" else "Нет данных",
            color = if (avgHoursPerDay >= 8.0) Color(0xFF2E7D32) else Color(0xFF0277BD)
        )
        InsightChip(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Assessment,
            title = "Ритм",
            value = "${rhythmScore.roundToInt()}% от 8 ч",
            color = if (rhythmScore >= 90) Color(0xFF2E7D32) else Color(0xFF6A1B9A)
        )
        InsightChip(
            modifier = Modifier.weight(1f),
            icon = if (expensesWithoutReceipt > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
            title = "Чеки",
            value = if (expensesWithoutReceipt > 0) "$expensesWithoutReceipt без чека"
                    else "${(receiptRate * 100).roundToInt()}% подтверждено",
            color = if (expensesWithoutReceipt > 0) Color(0xFFC62828) else Color(0xFF2E7D32)
        )
    }
}

@Composable
private fun InsightChip(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    color: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.08f)
        ),
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 9.dp, vertical = 9.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(7.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    value,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatRubles(value: Double): String {
    return when {
        value >= 1_000_000 -> "${"%.1f".format(value / 1_000_000.0)} млн ₽"
        value >= 1_000 -> "${"%.1f".format(value / 1_000.0)} тыс. ₽"
        else -> "${"%.0f".format(value)} ₽"
    }
}

@Composable
private fun ExpenseBreakdownCard(
    totalAmount: Double,
    currencyBreakdown: Map<String, Double>
) {
    val totalText = if (currencyBreakdown.isEmpty()) "0 ₽"
    else currencyBreakdown.entries.joinToString(" + ") { (c, a) ->
        "${"%.0f".format(a)} $c"
    }

    Card(
        modifier = Modifier
            .height(75.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF6A1B9A).copy(alpha = 0.15f))
    ) {
        Column(modifier = Modifier
            .padding(8.dp)
            .fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DirectionsCar, null, tint = Color(0xFF6A1B9A), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Расходы", style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF6A1B9A), fontWeight = FontWeight.Medium)
            }
            Column {
                Text(totalText, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (currencyBreakdown.size > 1) "${currencyBreakdown.size} валют(ы)"
                    else "все расходы",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// 🔹 Плитка навигации (с эмодзи и пастельным градиентом)
// ════════════════════════════════════════════════════════════
@Composable
private fun NavigationTile(
    title: String,
    subtitle: String,
    emoji: String,
    gradientColors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0
) {
    Card(
        modifier = modifier
            .aspectRatio(1.8f)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(gradientColors))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Эмодзи вместо иконки
                Text(
                    text = emoji,
                    fontSize = 28.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF2C3E50),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(2.dp))  // ✅ Было 4.dp
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2C3E50).copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp,   // ✅ Было 12.sp
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Badge с количеством непрочитанных
            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-8).dp)
                        .size(24.dp)
                        .background(Color(0xFFE53935), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}