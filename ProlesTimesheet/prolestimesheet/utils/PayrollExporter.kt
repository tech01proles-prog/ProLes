package com.example.prolestimesheet.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * 📊 Экспорт расчётов зарплаты в CSV и PDF
 */
object PayrollExporter {

    private const val TAG = "PayrollExporter"

    /**
     * 📄 Экспорт в CSV (открывается в Excel)
     */
    fun exportToCsv(
        context: Context,
        data: com.example.prolestimesheet.network.PayrollExportResponse,
        year: Int,
        month: Int
    ): Uri? {
        return try {
            val fileName = "payroll_${year}_${month.toString().padStart(2, '0')}.csv"
            val file = File(context.getExternalFilesDir(null), fileName)

            val monthName = Month.of(month).getDisplayName(TextStyle.FULL, Locale("ru"))

            FileOutputStream(file).use { fos ->
                fos.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))

                val sb = StringBuilder()
                sb.appendLine("# Расчёт зарплаты за $monthName $year")
                sb.appendLine("# Дата генерации: ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(java.util.Date())}")
                sb.appendLine()
                sb.appendLine("Сотрудник;Должность;Роль;Часы;Рабочих дней;Оклад;Почасовая;Сдельная;Премия;Итого ЗП;Расходы;Командировок")

                data.employees.forEach { emp ->
                    sb.appendLine(
                        "${escapeCsv(emp.name)};" +
                                "${escapeCsv(emp.position)};" +
                                "${translateRole(emp.role)};" +
                                "${"%.1f".format(emp.totalHours)};" +
                                "${emp.workDays};" +
                                "${"%.2f".format(emp.fixed)};" +
                                "${"%.2f".format(emp.hourly)};" +
                                "${"%.2f".format(emp.piece)};" +
                                "${"%.2f".format(emp.bonus)};" +
                                "${"%.2f".format(emp.salaryTotal)};" +
                                "${"%.2f".format(emp.expensesTotal)};" +
                                "${emp.tripsCount}"
                    )
                }

                sb.appendLine()
                val totalHours = data.employees.sumOf { it.totalHours }
                val totalSalary = data.employees.sumOf { it.salaryTotal }
                val totalExpenses = data.employees.sumOf { it.expensesTotal }
                sb.appendLine("ИТОГО;;;${"%.1f".format(totalHours)};;;;" +
                        ";;${"%.2f".format(totalSalary)};${"%.2f".format(totalExpenses)};")

                fos.write(sb.toString().toByteArray(Charsets.UTF_8))
            }

            Log.d(TAG, "✅ CSV exported to: ${file.absolutePath}")

            val uri =
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

            Log.d(TAG, "📄 CSV URI: $uri")
            Log.d(TAG, "📄 CSV file exists: ${file.exists()}, size: ${file.length()} bytes")

            uri
        } catch (e: Exception) {
            Log.e(TAG, "❌ CSV export failed", e)
            null
        }
    }

    /**
     * 📕 Экспорт в PDF
     */
    fun exportToPdf(
        context: Context,
        data: com.example.prolestimesheet.network.PayrollExportResponse,
        year: Int,
        month: Int
    ): Uri? {
        return try {
            val fileName = "payroll_${year}_${month.toString().padStart(2, '0')}.pdf"
            val file = File(context.getExternalFilesDir(null), fileName)

            val document = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(842, 595, 1).create()
            val page = document.startPage(pageInfo)
            val canvas: Canvas = page.canvas

            val titlePaint = Paint().apply { textSize = 20f; isFakeBoldText = true }
            val headerPaint = Paint().apply { textSize = 11f; isFakeBoldText = true }
            val textPaint = Paint().apply { textSize = 10f }
            val smallPaint = Paint().apply { textSize = 8f; color = android.graphics.Color.GRAY }

            val monthName = Month.of(month).getDisplayName(TextStyle.FULL, Locale("ru"))

            var y = 40f
            canvas.drawText("Расчёт зарплаты за $monthName $year", 40f, y, titlePaint)
            y += 20f
            canvas.drawText(
                "Дата: ${java.text.SimpleDateFormat("dd.MM.yyyy", Locale("ru")).format(java.util.Date())}",
                40f, y, smallPaint
            )
            y += 30f

            val columns = listOf("Сотрудник", "Должность", "Часы", "Дни", "Оклад", "Почас.", "Сдельн.", "Премия", "Итого ЗП", "Расходы")
            val widths = listOf(120f, 100f, 50f, 40f, 70f, 60f, 60f, 60f, 80f, 70f)
            var x = 40f
            columns.forEachIndexed { i, col ->
                canvas.drawText(col, x, y, headerPaint)
                x += widths[i]
            }
            y += 5f
            canvas.drawLine(40f, y, 760f, y, Paint().apply { strokeWidth = 1f })
            y += 15f

            data.employees.forEach { emp ->
                val values = listOf(
                    emp.name.take(20),
                    emp.position.take(15),
                    "%.1f".format(emp.totalHours),
                    emp.workDays.toString(),
                    "%.0f".format(emp.fixed),
                    "%.0f".format(emp.hourly),
                    "%.0f".format(emp.piece),
                    "%.0f".format(emp.bonus),
                    "%.0f".format(emp.salaryTotal),
                    "%.0f".format(emp.expensesTotal)
                )

                x = 40f
                values.forEachIndexed { i, v ->
                    canvas.drawText(v, x, y, textPaint)
                    x += widths[i]
                }
                y += 15f
            }

            y += 10f
            canvas.drawLine(40f, y - 5, 760f, y - 5, Paint().apply { strokeWidth = 1f })
            val totalSalary = data.employees.sumOf { it.salaryTotal }
            val totalExpenses = data.employees.sumOf { it.expensesTotal }
            canvas.drawText(
                "ИТОГО: ЗП ${"%.2f".format(totalSalary)} ₽  |  Расходы ${"%.2f".format(totalExpenses)} ₽",
                40f, y + 15, headerPaint
            )

            document.finishPage(page)
            FileOutputStream(file).use { document.writeTo(it) }
            document.close()

            Log.d(TAG, "✅ PDF exported to: ${file.absolutePath}")

            val uri =
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

            Log.d(TAG, "📕 PDF URI: $uri")
            Log.d(TAG, "📕 PDF file exists: ${file.exists()}, size: ${file.length()} bytes")

            uri
        } catch (e: Exception) {
            Log.e(TAG, "❌ PDF export failed", e)
            null
        }
    }

    private fun escapeCsv(s: String): String {
        return if (s.contains(";") || s.contains("\"") || s.contains("\n")) {
            "\"${s.replace("\"", "\"\"")}\""
        } else s
    }

    private fun translateRole(role: String): String = when (role) {
        "admin" -> "Админ"
        "director" -> "Директор"
        "employee" -> "Сотрудник"
        "logist" -> "Логист"
        "tech" -> "Тех.спец."
        "office" -> "Офис"
        "superadmin" -> "Суперадмин"
        else -> role
    }

    /**
     * Открывает файл через системный Intent (с fallback на ACTION_VIEW)
     */
    fun shareFile(context: Context, uri: Uri, mimeType: String) {
        try {
            Log.d(TAG, "📤 shareFile START")
            Log.d(TAG, "  uri: $uri")
            Log.d(TAG, "  mimeType: $mimeType")
            Log.d(TAG, "  context: ${context.javaClass.simpleName}")

            // 🔥 КРИТИЧНО: определяем, нужен ли FLAG_ACTIVITY_NEW_TASK
            val needsNewTask = context !is android.app.Activity
            Log.d(TAG, "  needsNewTask: $needsNewTask")

            // 1. Сначала пробуем
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Отчёт по зарплате")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (needsNewTask) {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            val chooser = android.content.Intent.createChooser(shareIntent, "Отправить отчёт").apply {
                if (needsNewTask) {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            Log.d(TAG, "📤 Запускаем chooser...")
            context.startActivity(chooser)
            Log.d(TAG, "✅ Chooser запущен успешно")

        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "⚠️ Chooser не найден, пробуем ACTION_VIEW: ${e.message}")
            openFileDirectly(context, uri, mimeType)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Share failed: ${e.javaClass.simpleName}: ${e.message}", e)
            // Fallback: пробуем открыть напрямую
            openFileDirectly(context, uri, mimeType)
        }
    }

    /**
     * Fallback: открывает файл напрямую через ACTION_VIEW
     */
    private fun openFileDirectly(context: Context, uri: Uri, mimeType: String) {
        try {
            Log.d(TAG, "📤 Fallback: ACTION_VIEW для $mimeType")
            val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is android.app.Activity) {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            if (viewIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(viewIntent)
                Log.d(TAG, "✅ ACTION_VIEW запущен успешно")
            } else {
                Log.e(TAG, "❌ Нет приложения для открытия $mimeType")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        context,
                        "Файл сохранён, но нет приложения для открытия.\nПроверьте папку Android/data/com.example.prolestimesheet/files/",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fallback тоже не сработал: ${e.message}", e)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context,
                    "Файл сохранён, но не удалось открыть.\nОшибка: ${e.message?.take(30)}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}