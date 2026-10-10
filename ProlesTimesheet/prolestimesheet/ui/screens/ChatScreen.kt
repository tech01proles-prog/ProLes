package com.example.prolestimesheet.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.model.ChatConversation
import com.example.prolestimesheet.model.ChatMessage
import com.example.prolestimesheet.model.ChatUser
import com.example.prolestimesheet.network.ChatApi
import com.example.prolestimesheet.ui.theme.ProlesCanvas
import com.example.prolestimesheet.ui.theme.ProlesExpense
import com.example.prolestimesheet.ui.theme.ProlesMuted
import com.example.prolestimesheet.ui.theme.ProlesPrimary
import com.example.prolestimesheet.ui.theme.ProlesPrimarySoft
import com.example.prolestimesheet.ui.theme.ProlesText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.util.Date

@Composable
fun ChatScreen(
    userId: String,
    context: Context,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var users by remember { mutableStateOf<List<ChatUser>>(emptyList()) }
    var conversations by remember { mutableStateOf<List<ChatConversation>>(emptyList()) }
    var activeConversationId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var selectedFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editText by remember { mutableStateOf("") }
    var savingEdit by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var showNewDialogUsers by remember { mutableStateOf(false) }
    var sidebarError by remember { mutableStateOf<String?>(null) }

    // Back at message level returns to the dialog list; back at the list returns to the previous screen.
    BackHandler {
        if (activeConversationId != null) activeConversationId = null else onBack()
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        selectedFiles = selectedFiles + uris.distinct()
    }

    fun mergeMessages(incoming: List<ChatMessage>): List<ChatMessage> {
        val byId = LinkedHashMap<String, ChatMessage>()
        messages.forEach { byId[it.id] = it }
        incoming.forEach { byId[it.id] = it }
        return byId.values.sortedBy { it.createdAt }
    }

    suspend fun loadSidebar() {
        var failed = false
        ChatApi.users()
            .onSuccess { users = it }
            .onFailure { error ->
                failed = true
                Log.e("ChatScreen", "Failed to load chat users", error)
            }
        ChatApi.conversations()
            .onSuccess { list -> conversations = list.sortedByDescending { it.updatedAt } }
            .onFailure { error ->
                failed = true
                Log.e("ChatScreen", "Failed to load conversations", error)
            }
        sidebarError = if (failed) "Не удалось загрузить данные чата. Проверьте подключение и авторизацию." else null
    }

    suspend fun loadMessages(conversationId: String, older: Boolean = false) {
        ChatApi.messages(conversationId, limit = 50, cursor = if (older) nextCursor else null)
            .onSuccess { page ->
                val ordered = page.items.reversed()
                if (older) {
                    messages = (ordered + messages).distinctBy { it.id }.sortedBy { it.createdAt }
                } else {
                    messages = mergeMessages(ordered).takeLast(200)
                }
                nextCursor = page.nextCursor
                page.items.firstOrNull { it.senderId != userId }?.let { ChatApi.markRead(conversationId, it.id) }
            }
            .onFailure { error ->
                Log.e("ChatScreen", "Failed to load chat messages", error)
                Toast.makeText(context, "Не удалось загрузить сообщения: ${error.message ?: "ошибка сервера"}", Toast.LENGTH_SHORT).show()
            }
    }

    LaunchedEffect(Unit) {
        loading = true
        loadSidebar()
        loading = false
        while (true) {
            delay(2000)
            loadSidebar()
        }
    }

    LaunchedEffect(activeConversationId) {
        val conversationId = activeConversationId ?: return@LaunchedEffect
        messages = emptyList()
        nextCursor = null
        loadMessages(conversationId)
        while (true) {
            delay(800)
            ChatApi.messages(conversationId, limit = 50).onSuccess { page ->
                val incoming = page.items.reversed()
                val previousLastId = messages.lastOrNull()?.id
                messages = mergeMessages(incoming).takeLast(200)
                if (messages.lastOrNull()?.id != previousLastId) {
                    listState.animateScrollToItem(messages.lastIndex.coerceAtLeast(0))
                    messages.lastOrNull { it.senderId != userId }?.let { ChatApi.markRead(conversationId, it.id) }
                }
            }
        }
    }

    val activeConversation = conversations.firstOrNull { it.id == activeConversationId }
    val activePeer = activeConversation?.members?.firstOrNull { it.id != userId }

    fun openConversation(otherUser: ChatUser) {
        scope.launch {
            ChatApi.createDirectConversation(otherUser.id).onSuccess { conversation ->
                conversations = (conversations.filterNot { it.id == conversation.id } + conversation)
                    .sortedByDescending { it.updatedAt }
                activeConversationId = conversation.id
            }.onFailure { error ->
                Log.e("ChatScreen", "Failed to create/open direct conversation", error)
                Toast.makeText(context, "Не удалось открыть диалог: ${error.message ?: "ошибка сервера"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun send() {
        if (activeConversationId.isNullOrBlank() || sending || uploading) return
        val conversationId = activeConversationId ?: return
        if (text.isBlank() && selectedFiles.isEmpty()) return
        scope.launch {
            sending = true
            val messageText = text.trim()
            text = ""
            if (messageText.isNotBlank() && selectedFiles.isEmpty()) {
                ChatApi.sendMessage(conversationId, messageText).onSuccess { message ->
                    messages = mergeMessages(listOf(message))
                    scope.launch { listState.animateScrollToItem(messages.lastIndex.coerceAtLeast(0)) }
                }.onFailure { text = messageText; Toast.makeText(context, "Не удалось отправить сообщение", Toast.LENGTH_SHORT).show() }
            } else {
                uploading = selectedFiles.isNotEmpty()
                selectedFiles.forEachIndexed { index, uri ->
                    ChatApi.uploadAttachment(context, conversationId, uri, if (index == 0) messageText else "")
                        .onSuccess { message -> messages = mergeMessages(listOf(message)) }
                        .onFailure { Toast.makeText(context, "Не удалось отправить файл", Toast.LENGTH_SHORT).show() }
                }
                selectedFiles = emptyList()
                uploading = false
                scope.launch { listState.animateScrollToItem(messages.lastIndex.coerceAtLeast(0)) }
            }
            sending = false
            ChatApi.conversations().onSuccess { conversations = it.sortedByDescending(ChatConversation::updatedAt) }
        }
    }

    fun saveEdit() {
        val message = editMessage ?: return
        val conversationId = activeConversationId ?: return
        if (editText.isBlank() || savingEdit) return
        scope.launch {
            savingEdit = true
            ChatApi.updateMessage(conversationId, message.id, editText.trim())
                .onSuccess { updated -> messages = mergeMessages(listOf(updated)); editMessage = null }
                .onFailure { Toast.makeText(context, "Не удалось изменить сообщение", Toast.LENGTH_SHORT).show() }
            savingEdit = false
        }
    }

    fun deleteSelectedMessage() {
        val message = actionMessage ?: return
        val conversationId = activeConversationId ?: return
        scope.launch {
            deleting = true
            ChatApi.deleteMessage(conversationId, message.id)
                .onSuccess { deleted -> messages = mergeMessages(listOf(deleted)); actionMessage = null }
                .onFailure { Toast.makeText(context, "Не удалось удалить сообщение", Toast.LENGTH_SHORT).show() }
            deleting = false
        }
    }

    fun downloadAttachment(attachmentId: String) {
        val message = messages.firstOrNull { it.attachments.any { attachment -> attachment.id == attachmentId } } ?: return
        val attachment = message.attachments.firstOrNull { it.id == attachmentId } ?: return
        scope.launch {
            ChatApi.downloadAttachment(attachment).onSuccess { file ->
                val savedUri = saveChatFile(context, file.bytes, file.fileName, file.mimeType)
                if (savedUri != null) {
                    openChatFile(context, savedUri, file.mimeType)
                } else {
                    Toast.makeText(context, "Файл сохранён в папке «Загрузки/Proles»", Toast.LENGTH_LONG).show()
                }
            }.onFailure { Toast.makeText(context, "Не удалось скачать файл", Toast.LENGTH_LONG).show() }
        }
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = ProlesPrimary) }
        return
    }

    if (activeConversation == null) {
        ChatUserPicker(
            users = users,
            conversations = conversations,
            userId = userId,
            showNewDialogUsers = showNewDialogUsers,
            sidebarError = sidebarError,
            onToggleNewDialog = { showNewDialogUsers = !showNewDialogUsers },
            onRetry = {
                scope.launch {
                    loading = true
                    loadSidebar()
                    loading = false
                }
            },
            onConversationClick = { activeConversationId = it.id },
            onUserClick = ::openConversation,
            onBack = onBack,
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(ProlesCanvas),
    ) {
        Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { activeConversationId = null }) { Icon(Icons.Default.ArrowBack, "Диалоги") }
                Box(Modifier.size(42.dp).clip(CircleShape).background(ProlesPrimarySoft), contentAlignment = Alignment.Center) {
                    Text(activeConversation.title.take(1).uppercase(), fontWeight = FontWeight.Black, color = ProlesPrimary)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(activeConversation.title, fontWeight = FontWeight.Black, color = ProlesText)
                    Text(
                        if (activePeer?.online == true) "онлайн" else "не в сети",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (activePeer?.online == true) MaterialTheme.colorScheme.primary else ProlesMuted
                    )
                }
                IconButton(onClick = { scope.launch { loadMessages(activeConversation.id) } }) { Icon(Icons.Default.Refresh, "Обновить") }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (nextCursor != null) {
                item {
                    TextButton(onClick = { scope.launch { loadMessages(activeConversation.id, older = true) } }, modifier = Modifier.fillMaxWidth()) {
                        Text("Загрузить предыдущие")
                    }
                }
            }
            items(messages, key = { it.id }) { message ->
                ChatMessageBubble(
                    message = message,
                    mine = message.senderId == userId,
                    onLongPress = { if (message.senderId == userId && message.deletedAt == null) actionMessage = message },
                    onDownload = { attachment -> downloadAttachment(attachment.id) },
                )
            }
        }

        if (selectedFiles.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(selectedFiles, key = { it.toString() }) { uri ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ProlesPrimarySoft).padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(queryFileName(context, uri), Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = { selectedFiles = selectedFiles.filterNot { it == uri } }) {
                            Text("×", fontWeight = FontWeight.Black, color = ProlesExpense)
                        }
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !sending && !uploading) {
                    Icon(Icons.Default.AttachFile, "Файл", tint = ProlesPrimary)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 5,
                    placeholder = { Text("Сообщение...") },
                    enabled = !sending && !uploading,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Default),
                    shape = RoundedCornerShape(20.dp),
                )
                IconButton(onClick = ::send, enabled = !sending && !uploading && (text.isNotBlank() || selectedFiles.isNotEmpty())) {
                    if (sending || uploading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = ProlesPrimary)
                    else Icon(Icons.Default.Send, "Отправить", tint = ProlesPrimary)
                }
            }
        }
    }

    actionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { if (!deleting) actionMessage = null },
            title = { Text("Сообщение") },
            text = { Text("Выберите действие для своего сообщения") },
            confirmButton = {
                TextButton(onClick = { editMessage = message; editText = message.text; actionMessage = null }) {
                    Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Редактировать")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteSelectedMessage() }, enabled = !deleting) {
                    Icon(Icons.Default.Delete, null, tint = ProlesExpense); Spacer(Modifier.width(6.dp)); Text("Удалить", color = ProlesExpense)
                }
            },
        )
    }

    editMessage?.let {
        AlertDialog(
            onDismissRequest = { if (!savingEdit) editMessage = null },
            title = { Text("Редактировать сообщение") },
            text = {
                OutlinedTextField(value = editText, onValueChange = { editText = it }, minLines = 2, maxLines = 8, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton(onClick = ::saveEdit, enabled = !savingEdit && editText.isNotBlank()) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { editMessage = null }, enabled = !savingEdit) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ChatUserPicker(
    users: List<ChatUser>,
    conversations: List<ChatConversation>,
    userId: String,
    showNewDialogUsers: Boolean,
    sidebarError: String?,
    onToggleNewDialog: () -> Unit,
    onRetry: () -> Unit,
    onConversationClick: (ChatConversation) -> Unit,
    onUserClick: (ChatUser) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(ProlesCanvas)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
            Column(Modifier.weight(1f)) {
                Text("PRO-Chat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Text("Сообщения и файлы", style = MaterialTheme.typography.labelSmall, color = ProlesMuted)
            }
            TextButton(onClick = onToggleNewDialog) {
                Text(if (showNewDialogUsers) "Скрыть" else "＋ Новый диалог", fontWeight = FontWeight.Bold)
            }
        }
        LazyColumn {
            if (sidebarError != null) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(sidebarError, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onRetry) { Text("Повторить") }
                    }
                }
            }
            if (conversations.isEmpty()) {
                item {
                    Text(
                        if (sidebarError == null) "Пока нет диалогов. Нажмите «Новый диалог», чтобы начать общение."
                        else "Диалоги пока не удалось загрузить.",
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ProlesMuted
                    )
                }
            }
            if (conversations.isNotEmpty()) {
                item {
                    Text("Диалоги", Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ProlesMuted)
                }
                items(conversations, key = { "conversation-" + it.id }) { conversation ->
                    val peer = conversation.members.firstOrNull { it.id != userId }
                    Row(Modifier.fillMaxWidth().clickable { onConversationClick(conversation) }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(CircleShape).background(ProlesPrimarySoft), contentAlignment = Alignment.Center) {
                            Text(conversation.title.take(1).uppercase(), fontWeight = FontWeight.Black, color = ProlesPrimary)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(conversation.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (conversation.unreadCount > 0) {
                                    Text(conversation.unreadCount.toString(), style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.clip(CircleShape).background(ProlesExpense).padding(horizontal = 7.dp, vertical = 3.dp))
                                }
                            }
                            Text(conversation.lastMessage?.let {
                                if (it.deletedAt != null) "Сообщение удалено"
                                else it.text.ifBlank { if (it.attachments.isNotEmpty()) "📎 ${it.attachments.size} файл(а)" else "Нет сообщений" }
                            } ?: "Нет сообщений", maxLines = 1, style = MaterialTheme.typography.labelSmall, color = ProlesMuted)
                        }
                        if (peer?.online == true) Box(Modifier.size(9.dp).clip(CircleShape).background(ProlesPrimary))
                    }
                }
            }
            if (showNewDialogUsers) {
                item {
                    Text("Выберите сотрудника", Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ProlesMuted)
                }
                if (users.isEmpty()) {
                    item {
                        Text(
                            if (sidebarError == null) "Нет доступных сотрудников для нового диалога."
                            else "Не удалось получить список сотрудников.",
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = ProlesMuted
                        )
                    }
                }
                items(users, key = { "user-" + it.id }) { item ->
                    Row(Modifier.fillMaxWidth().clickable { onUserClick(item) }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(ProlesPrimarySoft), contentAlignment = Alignment.Center) {
                            Text(item.name.take(1).uppercase(), fontWeight = FontWeight.Black, color = ProlesPrimary)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.Bold)
                            Text(if (item.online) "онлайн" else "не в сети", style = MaterialTheme.typography.labelSmall, color = ProlesMuted)
                        }
                        Text("＋", color = ProlesPrimary, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatMessage,
    mine: Boolean,
    onLongPress: () -> Unit,
    onDownload: (com.example.prolestimesheet.model.ChatAttachment) -> Unit,
) {
    val statusIcon = when (message.deliveryStatus) {
        "READ" -> Icons.Default.DoneAll
        "DELIVERED" -> Icons.Default.DoneAll
        else -> Icons.Default.Check
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 330.dp).pointerInput(message.id) { detectTapGestures(onLongPress = { onLongPress() }) }
                .clip(RoundedCornerShape(20.dp))
                .background(if (mine) ProlesPrimary else MaterialTheme.colorScheme.surface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (!mine) Text(message.senderName, style = MaterialTheme.typography.labelSmall, color = ProlesPrimary, fontWeight = FontWeight.Bold)
            if (message.deletedAt != null) {
                Text("Сообщение удалено", style = MaterialTheme.typography.bodyMedium, color = ProlesMuted)
            } else {
                if (message.text.isNotBlank()) Text(message.text, style = MaterialTheme.typography.bodyLarge, color = if (mine) androidx.compose.ui.graphics.Color.White else ProlesText)
                message.attachments.forEach { attachment ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { onDownload(attachment) },
                        color = if (mine) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f) else ProlesPrimarySoft,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Download, null, tint = if (mine) androidx.compose.ui.graphics.Color.White else ProlesPrimary)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(attachment.originalName, maxLines = 1, color = if (mine) androidx.compose.ui.graphics.Color.White else ProlesText, fontWeight = FontWeight.SemiBold)
                                Text(formatChatSize(attachment.sizeBytes), style = MaterialTheme.typography.labelSmall, color = if (mine) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f) else ProlesMuted)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (message.editedAt != null && message.deletedAt == null) Text("изменено", style = MaterialTheme.typography.labelSmall, color = if (mine) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.65f) else ProlesMuted)
                if (mine) {
                    Spacer(Modifier.width(4.dp))
                    Icon(statusIcon, null, Modifier.size(13.dp), tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f))
                }
                Spacer(Modifier.width(4.dp))
                Text(formatChatTime(message.createdAt), style = MaterialTheme.typography.labelSmall, color = if (mine) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.65f) else ProlesMuted)
            }
        }
    }
}

private fun formatChatSize(bytes: Long): String = if (bytes < 1024L * 1024L) "${(bytes / 1024L).coerceAtLeast(1)} КБ" else "${DecimalFormat("0.0").format(bytes / 1024.0 / 1024.0)} МБ"

private fun formatChatTime(timestamp: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(Date(timestamp))

private fun queryFileName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index).orEmpty()
        }
    }
    return "Файл"
}

private fun saveChatFile(context: Context, bytes: ByteArray, fileName: String, mimeType: String): Uri? {
    return runCatching {
        val resolver = context.contentResolver
        val savedUri: Uri?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Proles")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать файл")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("Не удалось записать файл")
                val completed = android.content.ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, completed, null, null)
                savedUri = uri
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        } else {
            val dir = java.io.File(
                context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                "Proles"
            ).apply { mkdirs() }
            java.io.File(dir, fileName).writeBytes(bytes)
            savedUri = null
        }
        Toast.makeText(context, "Файл сохранён: $fileName", Toast.LENGTH_LONG).show()
        savedUri
    }.onFailure { error ->
        Toast.makeText(context, "Не удалось сохранить файл: ${error.message}", Toast.LENGTH_LONG).show()
    }.getOrNull()
}

private fun openChatFile(context: Context, uri: Uri, mimeType: String) {
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType.takeIf { it.isNotBlank() } ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(
            Intent.createChooser(viewIntent, "Открыть скачанный файл")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (error: Exception) {
        Log.w("ChatScreen", "No app available to open file", error)
        Toast.makeText(context, "Файл сохранён, но приложение для его открытия не найдено", Toast.LENGTH_LONG).show()
    }
}
