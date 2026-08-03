package com.proles.server.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import java.io.File

object FirebaseService {
    private var initialized = false

    fun init() {
        if (initialized) return

        val keyFile = File("serviceAccountKey.json")
        if (!keyFile.exists()) {
            println("❌ Firebase: файл serviceAccountKey.json НЕ НАЙДЕН в ${keyFile.absolutePath}")
            println("📂 Текущая рабочая директория: ${System.getProperty("user.dir")}")
            println("📁 Содержимое директории:")
            File(System.getProperty("user.dir")).listFiles()?.forEach {
                println("   - ${it.name} (${it.length()} bytes)")
            }
            return
        }

        println("✅ Firebase: файл найден: ${keyFile.absolutePath} (${keyFile.length()} bytes)")

        try {
            val serviceAccount = keyFile.inputStream()
            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build()
            FirebaseApp.initializeApp(options)
            initialized = true
            println("✅ Firebase Admin SDK инициализирован успешно")
        } catch (e: Exception) {
            println("❌ Firebase инициализация упала: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }

    suspend fun sendPush(
        token: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ): Boolean {
        if (!initialized) {
            println("⚠️ Firebase не инициализирован, пуш не отправлен")
            return false
        }

        println("📤 FCM: отправка пуша '$title' на токен ${token.take(20)}...")

        return try {
            val message = Message.builder()
                .setToken(token)
                .setNotification(
                    Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build()
                )
                .putAllData(data)
                .build()

            val messageId = FirebaseMessaging.getInstance().send(message)
            println("✅ FCM: пуш отправлен успешно, messageId=$messageId")
            true
        } catch (e: Exception) {
            println("❌ FCM ошибка: ${e.javaClass.simpleName}: ${e.message}")
            if (e.message?.contains("registration-token-not-registered") == true) {
                println("⚠️ Токен невалиден - нужно перерегистрировать на клиенте")
            }
            e.printStackTrace()
            false
        }
    }
}