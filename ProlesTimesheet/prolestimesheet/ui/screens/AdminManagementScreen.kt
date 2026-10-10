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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.prolestimesheet.ui.theme.*
import com.example.prolestimesheet.ui.viewmodel.TimesheetViewModel

@Composable
fun AdminManagementScreen(
    viewModel: TimesheetViewModel,
    onNavigateToProfile: () -> Unit,
    onNavigateToTimesheet: () -> Unit,
    onNavigateToPayroll: () -> Unit,
    onNavigateToAnalytics: () -> Unit,
    onNavigateToProjects: () -> Unit,
    onNavigateToEmployees: () -> Unit,
    onNavigateToAdminExpenses: () -> Unit,
    onNavigateToCostCalculation: () -> Unit,
    onNavigateToTickets: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToProjectExpenses: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    val userPermissions by viewModel.userPermissions.collectAsState()

    @Composable
    fun canView(permissionKey: String): Boolean {
        val user = viewModel.user.collectAsState().value ?: return false
        if (user.role == "superadmin") return true
        return userPermissions[permissionKey]?.canView ?: false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ProlesCanvas)
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ProlesCard(
            shape = RoundedCornerShape(22.dp),
            containerColor = Color.Transparent
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF4F46E5),
                                Color(0xFF7C3AED),
                                Color(0xFFEC4899)
                            )
                        ),
                        RoundedCornerShape(22.dp)
                    )
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProlesIconBadge(Icons.Default.Dashboard, Color.White, size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Управление", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Основные инструменты команды",
                            color = Color.White.copy(alpha = 0.82f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Text(
            "Личные разделы",
            style = MaterialTheme.typography.titleMedium
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ManagementTile(
                "Мой профиль",
                "Личные данные и настройки",
                Icons.Default.Person,
                listOf(Color(0xFF0EA5E9), Color(0xFF2563EB)),
                onNavigateToProfile,
                Modifier.weight(1f)
            )
            ManagementTile(
                "Табель",
                "Рабочее время и часы",
                Icons.Default.CalendarMonth,
                listOf(Color(0xFF059669), Color(0xFF0D9488)),
                onNavigateToTimesheet,
                Modifier.weight(1f)
            )
        }

        Text(
            "Рабочее меню",
            style = MaterialTheme.typography.titleMedium
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canView("analytics")) {
                ManagementTile(
                    "Аналитика",
                    "Показатели и динамика",
                    Icons.Default.BarChart,
                    listOf(Color(0xFF4F46E5), Color(0xFF7C3AED)),
                    onNavigateToAnalytics,
                    Modifier.weight(1f)
                )
            }
            if (canView("expenses_all")) {
                ManagementTile(
                    "Расходы",
                    "По сотрудникам",
                    Icons.AutoMirrored.Filled.ReceiptLong,
                    listOf(Color(0xFFE11D48), Color(0xFFF97316)),
                    onNavigateToAdminExpenses,
                    Modifier.weight(1f)
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canView("employees")) {
                ManagementTile(
                    "Сотрудники",
                    "Профили и состав",
                    Icons.Default.People,
                    listOf(Color(0xFF059669), Color(0xFF14B8A6)),
                    onNavigateToEmployees,
                    Modifier.weight(1f)
                )
            }
            if (canView("permissions")) {
                ManagementTile(
                    "Доступ",
                    "Роли и права",
                    Icons.Default.AdminPanelSettings,
                    listOf(Color(0xFF2563EB), Color(0xFF06B6D4)),
                    onNavigateToPermissions,
                    Modifier.weight(1f)
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canView("projects")) {
                ManagementTile(
                    "Проекты",
                    "Объекты и подпроекты",
                    Icons.Default.Folder,
                    listOf(Color(0xFFF59E0B), Color(0xFFF97316)),
                    onNavigateToProjects,
                    Modifier.weight(1f)
                )
            }
            if (canView("tickets")) {
                ManagementTile(
                    "Билеты",
                    "Документы поездок",
                    Icons.Default.ConfirmationNumber,
                    listOf(Color(0xFF0EA5E9), Color(0xFF4F46E5)),
                    onNavigateToTickets,
                    Modifier.weight(1f)
                )
            }
        }

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
            .height(148.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(gradientColors))
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.18f)
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(9.dp).size(24.dp)
                    )
                }
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 2
                    )
                }
            }
        }
    }
}
