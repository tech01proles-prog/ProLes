package com.example.prolestimesheet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.ui.theme.*
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel

@Composable
fun LoginScreen(viewModel: TimesheetViewModel, modifier: Modifier = Modifier) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var rememberMe by remember { mutableStateOf(true) }

    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState) {
        if (uiState is TimesheetViewModel.UiState.Idle && error == null) {
            error = "Ошибка входа. Проверьте логин/пароль или подключение к серверу."
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ProlesCanvas)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF059669),
                            Color(0xFF0EA5A4),
                            Color(0xFF4F46E5)
                        )
                    ),
                    RoundedCornerShape(bottomStart = 42.dp, bottomEnd = 42.dp)
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))
            Surface(
                modifier = Modifier.size(74.dp),
                shape = RoundedCornerShape(22.dp),
                color = Color.White.copy(alpha = 0.18f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("PL", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("ProLes", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text("Табель, расходы и командировки", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.82f))

            Spacer(Modifier.height(36.dp))

            ProlesCard(
                shape = RoundedCornerShape(24.dp),
                containerColor = Color.White
            ) {
                Text("Добро пожаловать", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Войдите в рабочий кабинет",
                    style = MaterialTheme.typography.bodySmall,
                    color = ProlesMuted
                )
                Spacer(Modifier.height(18.dp))

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; error = null },
                    label = { Text("Email или логин") },
                    leadingIcon = { Icon(Icons.Default.Person, null, tint = ProlesPrimary) },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Пароль") },
                    leadingIcon = { Icon(Icons.Default.Lock, null, tint = ProlesSecondary) },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
                    Text("Запомнить меня на 14 дней", style = MaterialTheme.typography.bodySmall)
                }

                if (error != null) {
                    Surface(
                        color = ProlesExpenseSoft,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            error!!,
                            modifier = Modifier.padding(10.dp),
                            color = ProlesExpense,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Button(
                    onClick = {
                        if (email.isBlank() || password.isBlank()) {
                            error = "Заполните все поля"
                        } else {
                            error = null
                            viewModel.login(email, password, rememberMe)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = uiState !is TimesheetViewModel.UiState.Loading,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ProlesPrimary,
                        contentColor = Color.White
                    )
                ) {
                    if (uiState is TimesheetViewModel.UiState.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Войти")
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Защищённый рабочий вход",
                style = MaterialTheme.typography.labelSmall,
                color = ProlesMuted
            )
        }
    }
}
