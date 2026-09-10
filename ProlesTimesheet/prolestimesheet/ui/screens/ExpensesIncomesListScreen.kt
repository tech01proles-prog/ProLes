package com.example.prolestimesheet.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.model.Expense
import com.example.prolestimesheet.model.Income
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import java.time.YearMonth

private data class BalanceItem(
    val id: String,
    val isExpense: Boolean,
    val date: LocalDate,
    val amount: Double,
    val currency: String,
    val name: String,
    val projectName: String?,
    val expense: Expense? = null
)

private enum class BalanceFilter { ALL, INCOME, EXPENSE }

private val EXPENSE_TYPE_LABELS = mapOf(
    "CONTRACTORS" to ("Подрядчики" to "👷"),
    "ROAD" to ("Дорога" to "🚗"),
    "PER_DIEM" to ("Суточные" to "💵"),
    "CASH" to ("Наличные" to "💰"),
    "CARD" to ("Карта" to "💳"),
    "OTHER" to ("Прочее" to "📦")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesIncomesListScreen(
    viewModel: TimesheetViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val user by viewModel.user.collectAsState()
    val expenses by viewModel.expenses.collectAsState()
    val incomes by viewModel.incomes.collectAsState()

    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val currentYearMonth = remember(today) { YearMonth.of(today.year, today.monthNumber) }
    var selectedYearMonth by remember { mutableStateOf(currentYearMonth) }
    var filter by remember { mutableStateOf(BalanceFilter.ALL) }
    var sortAscending by remember { mutableStateOf(false) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var selectedExpenseForReceipt by remember { mutableStateOf<Expense?>(null) }

    val visibleExpenses = expenses.filter {
        val ym = YearMonth.of(it.date.year, it.date.monthNumber)
        ym == selectedYearMonth && it.userId == user?.id
    }
    val visibleIncomes = incomes.filter {
        val ym = YearMonth.of(it.date.year, it.date.monthNumber)
        ym == selectedYearMonth && it.userId == user?.id
    }

    val items = buildList {
        visibleExpenses.forEach { exp ->
            add(
                BalanceItem(
                    id = exp.id,
                    isExpense = true,
                    date = exp.date,
                    amount = exp.amount,
                    currency = exp.currency,
                    name = if (exp.type == "ROAD") "🚗 Дорога" else exp.name.ifBlank { "Прочий расход" },
                    projectName = exp.projectName,
                    expense = exp
                )
            )
        }
        visibleIncomes.forEach { inc ->
            add(
                BalanceItem(
                    id = inc.id,
                    isExpense = false,
                    date = inc.date,
                    amount = inc.amount,
                    currency = inc.currency,
                    name = inc.name.ifBlank { "Доход" },
                    projectName = inc.projectName
                )
            )
        }
    }.filter {
        when (filter) {
            BalanceFilter.ALL -> true
            BalanceFilter.INCOME -> !it.isExpense
            BalanceFilter.EXPENSE -> it.isExpense
        }
    }.sortedWith(
        if (sortAscending) compareBy<BalanceItem> { it.date }.thenBy { it.id }
        else compareByDescending<BalanceItem> { it.date }.thenByDescending { it.id }
    )

    val totalIncome = visibleIncomes.groupBy { it.currency }
        .mapValues { (_, list) -> list.sumOf { it.amount } }
    val totalExpense = visibleExpenses.groupBy { it.currency }
        .mapValues { (_, list) -> list.sumOf { it.amount } }
    val balanceByCurrency = (totalIncome.keys + totalExpense.keys).distinct().associateWith { currency ->
        (totalIncome[currency] ?: 0.0) - (totalExpense[currency] ?: 0.0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Сальдо", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${selectedYearMonth.month} ${selectedYearMonth.year}",
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
                    IconButton(onClick = { showMonthPicker = true }) {
                        Icon(Icons.Default.CalendarMonth, "Выбрать месяц")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            BalanceSummaryCard(
                income = totalIncome,
                expense = totalExpense,
                balance = balanceByCurrency
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(selected = filter == BalanceFilter.ALL, onClick = { filter = BalanceFilter.ALL }, label = { Text("Все") })
                FilterChip(selected = filter == BalanceFilter.INCOME, onClick = { filter = BalanceFilter.INCOME }, label = { Text("Доходы") })
                FilterChip(selected = filter == BalanceFilter.EXPENSE, onClick = { filter = BalanceFilter.EXPENSE }, label = { Text("Расходы") })
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { sortAscending = !sortAscending }) {
                    Icon(
                        if (sortAscending) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        "Порядок",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Нет операций за выбранный период",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        BalanceRow(
                            item = item,
                            onReceiptClick = { item.expense?.let { selectedExpenseForReceipt = it } },
                            onDelete = { if (item.isExpense) viewModel.removeExpense(item.id) else viewModel.removeIncome(item.id) }
                        )
                    }
                }
            }
        }

        if (showMonthPicker) {
            MonthPickerDialog(
                currentYearMonth = selectedYearMonth,
                onMonthSelected = { selectedYearMonth = it; showMonthPicker = false },
                onDismiss = { showMonthPicker = false }
            )
        }

        selectedExpenseForReceipt?.let { expense ->
            ReceiptAttachmentsDialog(
                expense = expense,
                viewModel = viewModel,
                onDismiss = { selectedExpenseForReceipt = null }
            )
        }
    }
}

@Composable
private fun BalanceSummaryCard(
    income: Map<String, Double>,
    expense: Map<String, Double>,
    balance: Map<String, Double>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Сальдо за выбранный месяц", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            BalanceAmountsLine("Доходы", income, Color(0xFF2E7D32))
            Spacer(Modifier.height(4.dp))
            BalanceAmountsLine("Расходы", expense, Color(0xFFC62828))
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            BalanceAmountsLine("Сальдо", balance, if (balance.values.all { it >= 0 }) Color(0xFF2E7D32) else Color(0xFFC62828), bold = true)
        }
    }
}

@Composable
private fun BalanceAmountsLine(label: String, values: Map<String, Double>, color: Color, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
        Text(
            text = if (values.isEmpty()) "0" else values.entries.joinToString(" + ") { (currency, amount) -> "${"%.0f".format(amount)} $currency" },
            color = color,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun BalanceRow(
    item: BalanceItem,
    onReceiptClick: () -> Unit,
    onDelete: () -> Unit
) {
    val expense = item.expense
    val isExpense = item.isExpense
    val background = if (isExpense) Color(0xFFFFF7F7) else Color(0xFFF4FBF5)
    val accent = if (isExpense) Color(0xFFC62828) else Color(0xFF2E7D32)
    val receiptColor = when {
        !isExpense -> MaterialTheme.colorScheme.onSurfaceVariant
        expense?.hasReceiptPhoto == true -> Color(0xFF2E7D32)
        else -> Color(0xFFC62828)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${item.date.dayOfMonth.toString().padStart(2, '0')}.${item.date.monthNumber.toString().padStart(2, '0')}.${item.date.year}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (!item.projectName.isNullOrBlank()) {
                    Text(
                        text = item.projectName!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(10.dp))
            if (isExpense) {
                IconButton(
                    onClick = onReceiptClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ReceiptLong,
                        contentDescription = if (expense?.hasReceiptPhoto == true) {
                            "Чек прикреплён"
                        } else {
                            "Прикрепить чек"
                        },
                        tint = receiptColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(10.dp))
            
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.width(86.dp)
            ) {
                Text(
                    text = if (isExpense) "-${"%.0f".format(item.amount)}" else "+${"%.0f".format(item.amount)}",
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = item.currency,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    "Удалить",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptAttachmentsDialog(
    expense: Expense,
    viewModel: TimesheetViewModel,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var selectedCount by remember { mutableStateOf(0) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    fun createCameraUri(): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "receipt_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Proles")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    fun uploadBytes(bytes: ByteArray, fileName: String, mimeType: String) {
        busy = true
        viewModel.uploadExpenseAttachment(
            expenseId = expense.id,
            fileBytes = bytes,
            fileName = fileName,
            mimeType = mimeType,
            context = context,
            onFinished = { success ->
                busy = false
                if (success) selectedCount++
            }
        )
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri
        cameraUri = null
        if (success && uri != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            viewModel.uploadExpenseUris(expense.id, listOf(uri), context) { uploaded ->
                busy = false
                selectedCount += uploaded
            }
        } else {
            uri?.let { context.contentResolver.delete(it, null, null) }
            busy = false
            Toast.makeText(context, "Съёмка отменена", Toast.LENGTH_SHORT).show()
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)) { uris ->
        if (uris.isNotEmpty()) {
            busy = true
            viewModel.uploadExpenseUris(expense.id, uris, context) { uploaded ->
                busy = false
                selectedCount += uploaded
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            busy = true
            viewModel.uploadExpenseUris(expense.id, uris, context) { uploaded ->
                busy = false
                selectedCount += uploaded
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Чеки и вложения") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Можно добавить несколько фото и файлов. Они будут привязаны к расходу сразу после успешной загрузки.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (expense.hasReceiptPhoto) {
                    Text("✅ У расхода уже есть чек. Можно добавить ещё вложения.", color = Color(0xFF2E7D32), fontWeight = FontWeight.Medium)
                }
                if (selectedCount > 0) {
                    Text("Загружено сейчас: $selectedCount", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { busy = true; cameraUri = createCameraUri(); if (cameraUri != null) cameraLauncher.launch(cameraUri) else { busy = false; Toast.makeText(context, "❌ Не удалось подготовить камеру", Toast.LENGTH_SHORT).show() } },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CameraAlt, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Камера")
                    }
                    OutlinedButton(
                        onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Фото")
                    }
                }
                OutlinedButton(
                    onClick = { filePicker.launch("*/*") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AttachFile, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Фото / PDF / DOC и другие файлы")
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Загрузка…")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Готово") }
        }
    )
}

@Composable
private fun MonthPickerDialog(
    currentYearMonth: YearMonth,
    onMonthSelected: (YearMonth) -> Unit,
    onDismiss: () -> Unit
) {
    var tempYearMonth by remember { mutableStateOf(currentYearMonth) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите месяц") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { tempYearMonth = tempYearMonth.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, "Предыдущий") }
                Text("${tempYearMonth.month} ${tempYearMonth.year}", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { tempYearMonth = tempYearMonth.plusMonths(1) }) { Icon(Icons.Default.ChevronRight, "Следующий") }
            }
        },
        confirmButton = { Button(onClick = { onMonthSelected(tempYearMonth) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
