package com.example.prolestimesheet

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import com.example.prolestimesheet.data.LocalDataStore
import com.example.prolestimesheet.navigation.PendingNavigationHolder
import com.example.prolestimesheet.network.ApiClient
import com.example.prolestimesheet.repository.TimeRepository
import com.example.prolestimesheet.ui.screens.LoginScreen
import com.example.prolestimesheet.ui.screens.MainScreen
import com.example.prolestimesheet.ui.theme.ProlesTheme
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel
import kotlinx.coroutines.launch
import com.example.prolestimesheet.network.NetworkMonitor
import com.example.prolestimesheet.data.sync.SyncQueue
import androidx.compose.foundation.layout.Box
import com.example.prolestimesheet.services.ReminderService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 🔥 Правильный edge-to-edge с прозрачными системными барами
        enableEdgeToEdge()

        // 🆕 Делаем status bar и navigation bar прозрачными
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        // 🆕 Светлые иконки на тёмном фоне (или наоборот — зависит от темы)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false  // белые иконки (для тёмной темы)
            isAppearanceLightNavigationBars = false
        }

        // 🆕 Инициализируем NetworkMonitor
        NetworkMonitor.initialize(applicationContext)

        // 🆕 Планируем периодическую синхронизацию
        SyncQueue.schedulePeriodicSync(applicationContext)

        // 🆕 Планируем напоминания
        ReminderService.scheduleReminders(applicationContext)
        ReminderService.createNotificationChannel(applicationContext)

        // 🔥 Обработчик 401 теперь эмитит глобальное событие
        ApiClient.setSessionExpiredCallback {
            lifecycleScope.launch {
                android.util.Log.d("MainActivity", "🚨 Session expired callback triggered")
                LocalDataStore.clearSession(this@MainActivity)
                ApiClient.clearToken()
                com.example.prolestimesheet.events.SessionEvents.emitSessionExpired()

                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Сессия истекла. Пожалуйста, войдите снова.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        handleNotificationIntent(intent)

        setContent {
            ProlesTheme {
                // 🆕 Обеспечиваем правильный фон для системных баров
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    val repository = remember { TimeRepository(applicationContext) }
                    val viewModel: TimesheetViewModel = viewModel { TimesheetViewModel(repository) }
                    App(viewModel, applicationContext)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val openNotifications = intent?.getBooleanExtra("open_notifications_screen", false) ?: false
        val type = intent?.getStringExtra("notification_type")
        val payload = intent?.getStringExtra("notification_payload")

        if (openNotifications && !type.isNullOrBlank()) {
            Log.d("MainActivity", "🔔 Push клик: type=$type")
            PendingNavigationHolder.set(type = type, payload = payload ?: "")
            intent.removeExtra("open_notifications_screen")
            intent.removeExtra("notification_type")
            intent.removeExtra("notification_payload")
        }
    }
}

@Composable
fun App(viewModel: TimesheetViewModel, context: Context) {
    // 🔥 ВАЖНО: tryAutoLogin вызывается ТОЛЬКО ОДИН РАЗ при первом запуске
    LaunchedEffect(Unit) {
        viewModel.tryAutoLogin(context)
    }

    // 🔄 Восстанавливаем токен для ApiClient (тоже один раз)
    LaunchedEffect(Unit) {
        ApiClient.initFromSession(context)
    }

    // 🔥 Слушаем глобальное событие "сессия истекла"
    LaunchedEffect(Unit) {
        com.example.prolestimesheet.events.SessionEvents.sessionExpired.collect {
            android.util.Log.d("App", "🚨 Получено событие sessionExpired — forceLogout")
            viewModel.forceLogout(context)
        }
    }

    val uiState by viewModel.uiState.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val user by viewModel.user.collectAsState()

    when (uiState) {
        is TimesheetViewModel.UiState.Idle,
        is TimesheetViewModel.UiState.Loading -> LoginScreen(
            viewModel = viewModel,
            modifier = Modifier
        )
        is TimesheetViewModel.UiState.Authenticated ->
            MainScreen(
                viewModel = viewModel,
                entries = entries,
                user = user,
                onLogout = {
                    viewModel.logout(context)
                }
            )
    }
}