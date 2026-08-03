package com.example.prolestimesheet.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.prolestimesheet.MainActivity
import com.example.prolestimesheet.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import androidx.core.content.edit

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "🔑 Новый FCM токен: $token")

        // Сохраняем токен локально
        getSharedPreferences("fcm_prefs", MODE_PRIVATE)
            .edit {
                putString("fcm_token", token)
            }

        // TODO: Отправить токен на сервер (после логина)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d("FCM", "📩 Получено сообщение: ${message.data}")

        // FCM может прийти в data или notification
        val title = message.notification?.title ?: message.data["title"] ?: "Уведомление"
        val body = message.notification?.body ?: message.data["message"] ?: ""
        val type = message.data["type"] ?: "GENERAL"
        val payload = message.data["payload"] ?: ""

        showNotification(title, body, type, payload)
    }

    private fun showNotification(title: String, body: String, type: String, payload: String) {
        val channelId = "proles_notifications"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Уведомления Пролес",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о событиях в системе"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("notification_type", type)
            putExtra("notification_payload", payload)
            putExtra("open_notifications_screen", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}