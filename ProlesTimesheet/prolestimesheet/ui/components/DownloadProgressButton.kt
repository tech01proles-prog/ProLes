package com.example.prolestimesheet.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.network.DownloadManager
/**
 * Кнопка скачивания с прогресс-баром и поддержкой retry
 */
@Composable
fun DownloadProgressButton(
    url: String,
    fileName: String,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val downloads by DownloadManager.downloads.collectAsState()
    val state = downloads[url]

    val currentStatus = state?.status ?: DownloadManager.DownloadState.Status.IDLE

    AnimatedContent(
        targetState = currentStatus,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "downloadState"
    ) { status ->
        when (status) {
            DownloadManager.DownloadState.Status.IDLE -> {
                // 🆕 Начальное состояние — обычная кнопка "Скачать"
                Button(
                    onClick = onDownload,
                    modifier = modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Скачать")
                }
            }
            DownloadManager.DownloadState.Status.DOWNLOADING -> {
                DownloadingView(state!!, modifier)
            }
            DownloadManager.DownloadState.Status.PAUSED -> {
                PausedView(state!!, onRetry, modifier)
            }
            DownloadManager.DownloadState.Status.FAILED -> {
                FailedView(state!!, onRetry, modifier)
            }
            DownloadManager.DownloadState.Status.COMPLETED -> {
                // ✅ Очищаем состояние, чтобы кнопка вернулась в IDLE
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(3000)
                    DownloadManager.clearState(url)
                }
                Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Загружено",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF2E7D32)
                        )
                    }
                    OutlinedButton(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Скачать ещё раз")
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadingView(state: DownloadManager.DownloadState, modifier: Modifier) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.progressPercent / 100f,
        label = "progress"
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Загрузка... ${state.progressPercent}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                formatBytes(state.downloadedBytes, state.totalBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun PausedView(state: DownloadManager.DownloadState, onRetry: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.totalBytes > 0 && state.downloadedBytes > 0) {
            // Прогресс уже есть — показываем с кнопкой "Продолжить"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "⏸ Пауза (${state.resumeAttempts}/3 попыток)",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFF57C00)
                )
                Text(
                    formatBytes(state.downloadedBytes, state.totalBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LinearProgressIndicator(
                progress = { state.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Color(0xFFF57C00),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        } else {
            Text(
                "⚠️ ${state.errorMessage ?: "Ошибка"}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFF57C00),
                maxLines = 1
            )
        }

        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF57C00))
        ) {
            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Продолжить загрузку")
        }
    }
}

@Composable
private fun FailedView(state: DownloadManager.DownloadState, onRetry: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "❌ ${state.errorMessage ?: "Не удалось скачать"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2
        )
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Попробовать снова")
        }
    }
}

private fun formatBytes(downloaded: Long, total: Long): String {
    fun format(b: Long): String = when {
        b > 1_000_000 -> "%.1f МБ".format(b / 1_000_000.0)
        b > 1_000 -> "%.0f КБ".format(b / 1_000.0)
        else -> "$b Б"
    }
    return if (total > 0) "${format(downloaded)} / ${format(total)}" else format(downloaded)
}