package com.example.prolestimesheet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Calculate
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel

@Composable
fun AdminManagementScreen(
    viewModel: TimesheetViewModel,
    onNavigateToPayroll: () -> Unit,
    onNavigateToAnalytics: () -> Unit,
    onNavigateToProjects: () -> Unit,
    onNavigateToEmployees: () -> Unit,
    onNavigateToAdminExpenses: () -> Unit,
    onNavigateToCostCalculation: () -> Unit,
    onNavigateToTickets: () -> Unit,       // 🆕
    onNavigateToPermissions: () -> Unit,    // 🆕
    onNavigateToProjectExpenses: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    val userPermissions by viewModel.userPermissions.collectAsState()

    // 🆕 Helper: проверка права на просмотр раздела
    @Composable
    fun canView(permissionKey: String): Boolean {
        val user = viewModel.user.collectAsState().value ?: return false
        if (user.role == "superadmin") return true
        return userPermissions[permissionKey]?.canView ?: false
    }

    Column(
        modifier = modifier
            .padding(12.dp)
            .fillMaxSize()
            .verticalScroll(scroll)
    ) {
        Text("Панель управления", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Администратор", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (canView("analytics")) {
                ManagementTile(
                    title = "Аналитика",
                    subtitle = "Статистика",
                    icon = Icons.Default.BarChart,
                    gradientColors = listOf(Color(0xFF667eea), Color(0xFF764ba2)),
                    onClick = onNavigateToAnalytics,
                    modifier = Modifier.weight(1f)
                )
            }
            if (canView("expenses_all")) {
                ManagementTile(
                    title = "Расходы",
                    subtitle = "Сотрудники",
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    gradientColors = listOf(Color(0xFFf093fb), Color(0xFFf5576c)),
                    onClick = onNavigateToAdminExpenses,
                    modifier = Modifier.weight(1f)
                )
            }
            // 💰 Расходы по проектам (только для admin/director/superadmin)
            if (canView("expenses")) {
                ManagementTile(
                    title = "Расходы",
                    subtitle = "Проекты",
                    icon = Icons.Default.AttachMoney,
                    gradientColors = listOf(Color(0xFF11998e), Color(0xFF38ef7d)),  // 💚 Изумрудный (финансы)
                    onClick = onNavigateToProjectExpenses,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canView("employees")) {
                ManagementTile(
                    title = "Сотр-ки",
                    subtitle = "Профили",
                    icon = Icons.Default.People,
                    gradientColors = listOf(Color(0xFF43e97b), Color(0xFF38f9d7)),
                    onClick = onNavigateToEmployees,
                    modifier = Modifier.weight(1f)
                )
            }
            if (canView("permissions")) {
                ManagementTile(
                    title = "Доступ",
                    subtitle = "Роли",
                    icon = Icons.Default.AdminPanelSettings,
                    gradientColors = listOf(Color(0xFF5C6BC0), Color(0xFF3949AB)),
                    onClick = onNavigateToPermissions,
                    modifier = Modifier.weight(1f)
                )
            }
            if (canView("payroll")) {
                ManagementTile(
                    title = "Зарплата",
                    subtitle = "Финансы",
                    icon = Icons.Default.Payments,
                    gradientColors = listOf(Color(0xFF667eea), Color(0xFF764ba2)),
                    onClick = onNavigateToPayroll,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canView("projects")) {
                ManagementTile(
                    title = "Проекты",
                    subtitle = "Управление",
                    icon = Icons.Default.Folder,
                    gradientColors = listOf(Color(0xFF4facfe), Color(0xFF00f2fe)),
                    onClick = onNavigateToProjects,
                    modifier = Modifier.weight(1f)
                )
            }
            if (canView("cost_calculation")) {
                ManagementTile(
                    title = "С/С",
                    subtitle = "Расчёт",
                    icon = Icons.Default.Calculate,
                    gradientColors = listOf(Color(0xFFfa709a), Color(0xFFfee140)),
                    onClick = onNavigateToCostCalculation,
                    modifier = Modifier.weight(1f)
                )
            }
            if (canView("tickets")) {
                ManagementTile(
                    title = "Билеты",
                    subtitle = "Загрузка",
                    icon = Icons.Default.ConfirmationNumber,
                    gradientColors = listOf(Color(0xFFFF8A65), Color(0xFFFF5722)),
                    onClick = onNavigateToTickets,
                    modifier = Modifier.weight(1f)
                )
            }

        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ManagementTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    gradientColors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(gradientColors))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(6.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center, fontSize = 12.sp)
            }
        }
    }
}