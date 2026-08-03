package com.example.prolestimesheet.network

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable

object DownloadManager {

    private const val TAG = "DownloadManager"
    private const val MAX_RESUME_ATTEMPTS = 3

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    data class DownloadState(
        val url: String,
        val fileName: String,
        val status: Status,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = -1,
        val resumeAttempts: Int = 0,
        val errorMessage: String? = null
    ) {
        val progressPercent: Int
            get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0

        enum class Status {
            IDLE, DOWNLOADING, PAUSED, COMPLETED, FAILED
        }
    }

    suspend fun download(
        context: Context,
        url: String,
        fileName: String,
        mimeType: String
    ): Uri? = withContext(Dispatchers.IO) {
        val serverBase = ApiClient.BASE_URL.substringBefore("/api/v1")
        val fullUrl = serverBase + url
        val stateKey = url  // 🔥 КЛЮЧ: используем относительный URL

        Log.d(TAG, "📥 Starting download: $fullUrl (stateKey=$stateKey)")

        val cacheDir = File(context.cacheDir, "downloads").apply { mkdirs() }
        val partialFile = File(cacheDir, "$fileName.partial")
        val finalFile = File(cacheDir, fileName)

        var resumeAttempts = if (partialFile.exists()) {
            _downloads.value[stateKey]?.resumeAttempts ?: 0
        } else {
            0
        }

        while (true) {
            try {
                val existingBytes = if (partialFile.exists() && resumeAttempts < MAX_RESUME_ATTEMPTS) {
                    partialFile.length()
                } else {
                    if (partialFile.exists()) {
                        Log.w(TAG, "🔄 Max resume attempts, starting fresh")
                        partialFile.delete()
                        resumeAttempts = 0
                    }
                    0L
                }

                updateState(
                    stateKey,
                    DownloadState(
                        url = url,
                        fileName = fileName,
                        status = DownloadState.Status.DOWNLOADING,
                        downloadedBytes = existingBytes,
                        totalBytes = -1,
                        resumeAttempts = resumeAttempts
                    )
                )

                val result = executeDownload(fullUrl, url, stateKey, partialFile, existingBytes)

                when (result) {
                    is DownloadResult.Success -> {
                        val totalSize = result.totalBytes
                        Log.d(TAG, "✅ Download complete: $totalSize bytes")

                        partialFile.renameTo(finalFile)
                        val bytes = finalFile.readBytes()
                        val savedUri = saveToMediaStore(context, fileName, mimeType, bytes)
                        finalFile.delete()

                        updateState(
                            stateKey,
                            DownloadState(
                                url = url,
                                fileName = fileName,
                                status = DownloadState.Status.COMPLETED,
                                downloadedBytes = totalSize,
                                totalBytes = totalSize
                            )
                        )

                        return@withContext savedUri
                    }
                    is DownloadResult.ResumableError -> {
                        resumeAttempts++
                        Log.w(TAG, "⚠️ Resumable error (attempt $resumeAttempts/$MAX_RESUME_ATTEMPTS): ${result.message}")

                        updateState(
                            stateKey,
                            DownloadState(
                                url = url,
                                fileName = fileName,
                                status = DownloadState.Status.PAUSED,
                                downloadedBytes = partialFile.length(),
                                totalBytes = result.totalBytes,
                                resumeAttempts = resumeAttempts,
                                errorMessage = result.message
                            )
                        )
                    }
                    is DownloadResult.FatalError -> {
                        Log.e(TAG, "❌ Fatal error: ${result.message}")
                        updateState(
                            stateKey,
                            DownloadState(
                                url = url,
                                fileName = fileName,
                                status = DownloadState.Status.FAILED,
                                errorMessage = result.message
                            )
                        )
                        return@withContext null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Unexpected error", e)
                resumeAttempts++

                updateState(
                    stateKey,
                    DownloadState(
                        url = url,
                        fileName = fileName,
                        status = DownloadState.Status.PAUSED,
                        downloadedBytes = if (partialFile.exists()) partialFile.length() else 0L,
                        resumeAttempts = resumeAttempts,
                        errorMessage = e.message
                    )
                )

                if (resumeAttempts >= MAX_RESUME_ATTEMPTS) {
                    Log.w(TAG, "🔄 Max attempts reached, retrying from scratch")
                    partialFile.delete()
                    resumeAttempts = 0
                }
            }
        }

        return@withContext null
    }

    suspend fun retry(context: Context, url: String, fileName: String, mimeType: String): Uri? {
        return download(context, url, fileName, mimeType)
    }

    private suspend fun executeDownload(
        fullUrl: String,
        url: String,
        stateKey: String,
        partialFile: File,
        existingBytes: Long
    ): DownloadResult {
        return try {
            val response = ApiClient.client.get(fullUrl) {
                if (existingBytes > 0) {
                    header("Range", "bytes=$existingBytes-")
                    Log.d(TAG, "📤 Requesting resume from byte $existingBytes")
                }
            }

            val statusCode = response.status.value
            Log.d(TAG, "📊 Response status: $statusCode")

            when (statusCode) {
                200, 206 -> {
                    val isResume = statusCode == 206
                    val totalBytes = parseTotalBytes(
                        response.headers["Content-Range"],
                        response.contentLength() ?: -1L,
                        existingBytes,
                        isResume
                    )

                    RandomAccessFile(partialFile, "rw").use { raf ->
                        if (!isResume) {
                            raf.setLength(0)
                        }
                        raf.seek(if (isResume) existingBytes else 0)

                        val channel = response.bodyAsChannel()
                        var downloaded = if (isResume) existingBytes else 0L
                        val buffer = ByteArray(8 * 1024)

                        while (!channel.isClosedForRead) {
                            val read = channel.readAvailable(buffer)
                            if (read <= 0) break

                            raf.write(buffer, 0, read)
                            downloaded += read

                            updateState(
                                stateKey,
                                DownloadState(
                                    url = url,
                                    fileName = partialFile.name,
                                    status = DownloadState.Status.DOWNLOADING,
                                    downloadedBytes = downloaded,
                                    totalBytes = totalBytes
                                )
                            )
                        }
                    }

                    DownloadResult.Success(partialFile.length())
                }
                416 -> DownloadResult.FatalError("Invalid range (file may have changed)")
                in 500..599 -> DownloadResult.ResumableError(
                    "Server error: $statusCode",
                    parseTotalBytesFromContentRange(response.headers["Content-Range"])
                )
                else -> DownloadResult.FatalError("HTTP $statusCode: ${response.status.description}")
            }
        } catch (e: Exception) {
            DownloadResult.ResumableError(e.message ?: "Network error", -1)
        }
    }

    private fun parseTotalBytes(
        contentRange: String?,
        contentLength: Long,
        existingBytes: Long,
        isResume: Boolean
    ): Long {
        if (contentRange != null) {
            val match = Regex("""bytes\s+\d+-\d+/(\d+)""").find(contentRange)
            if (match != null) {
                val totalStr = match.groupValues[1]
                return totalStr.toLongOrNull() ?: -1
            }
        }
        return if (isResume) existingBytes + contentLength else contentLength
    }

    private fun parseTotalBytesFromContentRange(contentRange: String?): Long {
        if (contentRange == null) return -1
        val match = Regex("""bytes\s+\d+-\d+/(\d+)""").find(contentRange) ?: return -1
        val totalStr = match.groupValues[1]
        return totalStr.toLongOrNull() ?: -1
    }

    private fun updateState(key: String, state: DownloadState) {
        _downloads.value = _downloads.value.toMutableMap().apply {
            put(key, state)
        }
    }

    fun clearState(url: String) {
        _downloads.value = _downloads.value.toMutableMap().apply {
            remove(url)
        }
    }

    private suspend fun saveToMediaStore(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Uri? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(
                        android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/Proles"
                    )
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                uri?.let {
                    resolver.openOutputStream(it)?.use { out -> out.write(bytes) }
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(it, contentValues, null, null)
                }
                uri
            } else {
                val dir = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ),
                    "Proles"
                ).apply { mkdirs() }
                val file = File(dir, fileName)
                file.writeBytes(bytes)
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Save to MediaStore failed", e)
            null
        }
    }

    private sealed class DownloadResult {
        data class Success(val totalBytes: Long) : DownloadResult()
        data class ResumableError(val message: String, val totalBytes: Long) : DownloadResult()
        data class FatalError(val message: String) : DownloadResult()
    }
}