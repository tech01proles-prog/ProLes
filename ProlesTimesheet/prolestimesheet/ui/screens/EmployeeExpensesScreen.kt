package com.example.prolestimesheet.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.model.Expense
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import androidx.activity.result.PickVisualMediaRequest
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.material.icons.automirrored.filled.ReceiptLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeeExpensesScreen(
    viewModel: TimesheetViewModel,
    userId: String,
    isAdminView: Boolean = false,
    canViewAll: Boolean = false,  // 🆕
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 🔥 Если нет прав на просмотр всех — принудительно выключаем isAdminView
    val effectiveIsAdminView = isAdminView && canViewAll

    val expenses by viewModel.expenses.collectAsState()
    val employees by viewModel.employees.collectAsState()
    val context = LocalContext.current

    // 🆕 Находим пользователя COMPANY
    val companyUser = remember(employees) { employees.find { it.name == "COMPANY" } }
    val companyUserId = companyUser?.id

    // 🆕 Состояние для отслеживания процесса загрузки фото
    var uploadingExpenseId by remember { mutableStateOf<String?>(null) }

    // 📷 Текущий расход, для которого делается фото
    var currentPhotoExpenseId by remember { mutableStateOf<String?>(null) }
    var showPhotoSourceDialog by remember { mutableStateOf(false) }

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

    fun uploadPhotoUri(uri: Uri, expenseId: String) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                Toast.makeText(context, "❌ Не удалось прочитать фото", Toast.LENGTH_SHORT).show()
                return
            }
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            } ?: "receipt_${System.currentTimeMillis()}.jpg"
            uploadingExpenseId = expenseId
            viewModel.uploadExpenseAttachment(expenseId, bytes, fileName, mimeType, context)
            Toast.makeText(context, "📤 Фото отправляется (${bytes.size / 1024} КБ)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "❌ Ошибка чтения фото: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    // 📸 Лаунчер камеры
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = cameraUri
        val expenseId = currentPhotoExpenseId
        cameraUri = null
        currentPhotoExpenseId = null
        if (success && uri != null && expenseId != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            uploadPhotoUri(uri, expenseId)
        } else {
            uri?.let { context.contentResolver.delete(it, null, null) }
            Toast.makeText(context, "Съёмка отменена", Toast.LENGTH_SHORT).show()
        }
    }

    // 🖼️ Лаунчер галереи (современный Photo Picker для Android 13+)
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val expenseId = currentPhotoExpenseId
        currentPhotoExpenseId = null
        if (uri != null && expenseId != null) {
            uploadPhotoUri(uri, expenseId)
        } else if (uri == null) {
            Toast.makeText(context, "Выбор отменён", Toast.LENGTH_SHORT).show()
        }
    }

    // 🖼️ Fallback для старых Android (< 13)
    val legacyGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val expenseId = currentPhotoExpenseId
        currentPhotoExpenseId = null
        if (uri != null && expenseId != null) {
            uploadPhotoUri(uri, expenseId)
        } else if (uri == null) {
            Toast.makeText(context, "Выбор отменён", Toast.LENGTH_SHORT).show()
        }
    }

    // Фильтрация и сортировка
    val filteredExpenses = remember(expenses, userId) {
        val list = if (effectiveIsAdminView) expenses else expenses.filter { it.userId == userId }
        list.sortedByDescending { it.date }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (effectiveIsAdminView) "Расходы сотрудника" else "Мои расходы") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    if (effectiveIsAdminView) {
                        // Подсказка для админа
                        val withPhoto = filteredExpenses.count { it.hasReceiptPhoto }
                        val total = filteredExpenses.size
                        Text(
                            "📷 $withPhoto / $total",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = modifier.padding(padding).fillMaxSize()) {
            // 🆕 Админ-заголовок с колонками (если админ-режим)
            if (effectiveIsAdminView && filteredExpenses.isNotEmpty()) {
                AdminExpensesHeader()
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                items(filteredExpenses, key = { it.id }) { expense ->
                    // 🆕 Проверяем, является ли расход расходом компании
                    val isCompanyExpense = companyUserId != null && expense.userId == companyUserId
                    ExpenseRow(
                        expense = expense,
                        isAdminView = effectiveIsAdminView,
                        isCompanyExpense = isCompanyExpense,
                        isUploading = uploadingExpenseId == expense.id,
                        onToggleReceipt = { newStatus ->
                            viewModel.toggleReceiptSubmitted(expense.id, newStatus)
                        },
                        onTakePhoto = {
                            currentPhotoExpenseId = expense.id
                            showPhotoSourceDialog = true
                        },
                        onDelete = { viewModel.removeExpense(expense.id) }  // 🆕 ДОБАВЛЕНО
                    )
                }

                if (filteredExpenses.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.ReceiptLong,
                                    null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Нет расходов",
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 🔄 Слушаем изменение uploadingExpenseId для сброса после загрузки
    LaunchedEffect(expenses) {
        if (uploadingExpenseId != null) {
            val expense = expenses.find { it.id == uploadingExpenseId }
            if (expense?.hasReceiptPhoto == true) {
                uploadingExpenseId = null
            }
        }
    }

    // 📸 Диалог выбора источника фото
    if (showPhotoSourceDialog && currentPhotoExpenseId != null) {
        AlertDialog(
            onDismissRequest = { showPhotoSourceDialog = false },
            title = { Text("Добавить фото чека") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            showPhotoSourceDialog = false
                            cameraUri = createCameraUri()
                            if (cameraUri != null) cameraLauncher.launch(cameraUri) else Toast.makeText(context, "❌ Не удалось подготовить камеру", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CameraAlt, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("📷 Сделать фото")
                    }
                    OutlinedButton(
                        onClick = {
                            showPhotoSourceDialog = false
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            } else {
                                legacyGalleryLauncher.launch("image/*")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PhotoLibrary, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("🖼️ Выбрать из галереи")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPhotoSourceDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

// 🔹 Заголовок таблицы для админа
@Composable
private fun AdminExpensesHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Дата • Проект • Тип",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "ФОТО",
            modifier = Modifier.width(50.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Сумма",
            modifier = Modifier.width(80.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Чек",
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// 🔹 Строка расхода
@Composable
private fun ExpenseRow(
    expense: Expense,
    isAdminView: Boolean,
    isCompanyExpense: Boolean = false,  // 🆕
    isUploading: Boolean,
    onToggleReceipt: (Boolean) -> Unit,
    onTakePhoto: () -> Unit,
    onDelete: () -> Unit  // 🆕 ДОБАВЛЕНО
) {
    // 🆕 Зеленый фон, если есть фото чека
    val bgColor = when {
        expense.hasReceiptPhoto -> Color(0xFFE8F5E9) // ✅ Зеленый
        expense.receiptSubmitted -> Color(0xFFE3F2FD)  // 🔵 Голубой
        else -> Color(0xFFFFEBEE)  // 🔴 Красный
    }
    val typeLabel = if (expense.type == "ROAD") "🚗 Дорога" else "📦 ${expense.name}"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 🔹 Левая часть: дата, проект, тип
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${expense.date}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 🆕 Индикатор расхода компании
                if (isCompanyExpense) {
                    Text(
                        text = "🏢 Компания",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFA000),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            // 🔹 Средняя часть: зависит от режима
            // 📷 Кнопка камеры (только для сотрудника, не для админа)
            if (!isAdminView) {
                if (isUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).padding(end = 4.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    IconButton(
                        onClick = onTakePhoto,
                        modifier = Modifier.size(32.dp),
                        enabled = !expense.hasReceiptPhoto
                    ) {
                        Icon(
                            imageVector = if (expense.hasReceiptPhoto) Icons.Default.CheckCircle else Icons.Default.CameraAlt,
                            contentDescription = if (expense.hasReceiptPhoto) "Фото загружено" else "Сфотографировать чек",
                            tint = if (expense.hasReceiptPhoto) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            // 🖼 Колонка ФОТО (только для админа)
            if (isAdminView) {
                Box(
                    modifier = Modifier.width(50.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (expense.hasReceiptPhoto) {
                        Surface(
                            color = Color(0xFF2E7D32).copy(alpha = 0.15f),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.padding(2.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ReceiptLong,
                                "Чек загружен",
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.HideImage,
                            "Фото нет",
                            tint = Color.Gray.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            // 💰 Сумма
            Text(
                text = "${"%.0f".format(expense.amount)} ${expense.currency}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(if (isAdminView) 80.dp else 70.dp).padding(end = 4.dp),
                textAlign = TextAlign.End
            )
            // 🆕 КНОПКА УДАЛЕНИЯ (вместо чекбокса)
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(if (isAdminView) 28.dp else 32.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    "Удалить расход",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

