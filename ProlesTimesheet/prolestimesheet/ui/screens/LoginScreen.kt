package com.example.prolestimesheet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel

@Composable
fun LoginScreen(viewModel: TimesheetViewModel, modifier: Modifier = Modifier) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var rememberMe by remember { mutableStateOf(true) }  // ✅ По умолчанию включено

    // 🔥 Наблюдаем за состоянием авторизации
    val uiState by viewModel.uiState.collectAsState()

    // 🔥 Показываем ошибку, если состояние стало ошибкой
    LaunchedEffect(uiState) {
        if (uiState is TimesheetViewModel.UiState.Idle && error == null) {
            // Если после попытки входа состояние вернулось в Idle — значит, была ошибка
            error = "Ошибка входа. Проверьте логин/пароль или подключение к серверу."
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Вход в Пролескомпани", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(value = email, onValueChange = { email = it; error = null }, label = { Text("Email или логин") }, isError = error != null, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = password, onValueChange = { password = it; error = null }, label = { Text("Пароль") }, isError = error != null, modifier = Modifier.fillMaxWidth())

        if (error != null) { Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(8.dp)) }

        // 🔥 Чекбокс "Запомнить меня"
        Row(Modifier.fillMaxWidth(), Arrangement.Start, Alignment.CenterVertically) {
            Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
            Spacer(Modifier.width(8.dp))
            Text("Запомнить меня (14 дней)", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(32.dp))
        // 🔥 Показ ошибки
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }

        // 🔥 Кнопка с блокировкой при загрузке
        Button(
            onClick = {
                if (email.isBlank() || password.isBlank()) {
                    error = "Заполните все поля"
                } else {
                    error = null
                    viewModel.login(email, password, rememberMe)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState !is TimesheetViewModel.UiState.Loading
        ) {
            if (uiState is TimesheetViewModel.UiState.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
            } else {
                Text("Войти")
            }
        }
    }
}