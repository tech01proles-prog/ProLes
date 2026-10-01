package com.example.prolestimesheet.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val ProlesCanvas = Color(0xFFF6F7FF)
val ProlesSurface = Color(0xFFFFFFFF)
val ProlesBorder = Color(0xFFD9E0EE)
val ProlesText = Color(0xFF172033)
val ProlesMuted = Color(0xFF667085)
val ProlesPrimary = Color(0xFF7C3AED)
val ProlesPrimarySoft = Color(0xFFF3E8FF)
val ProlesSecondary = Color(0xFF0284C7)
val ProlesSecondarySoft = Color(0xFFE0F2FE)
val ProlesExpense = Color(0xFFE11D48)
val ProlesExpenseSoft = Color(0xFFFFE4E6)
val ProlesRestSoft = Color(0xFFFFF7ED)
val ProlesRestText = Color(0xFF7C2D12)

fun incomeTypeLabel(type: String): String = when (type.uppercase()) {
    "HOUSEHOLD" -> "🏠 Хоз.нужды"
    "CARD" -> "💳 По карте"
    "CASH" -> "💵 Наличными"
    else -> type
}

@Composable
fun ProlesCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    containerColor: Color = ProlesSurface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(containerColor)
        .border(1.dp, ProlesBorder, shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(16.dp)
    Column(modifier = base, content = content)
}

@Composable
fun ProlesSectionTitle(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(action, color = ProlesPrimary)
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun ProlesIconBadge(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 36.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun ProlesPill(
    text: String,
    selected: Boolean = false,
    tint: Color = ProlesPrimary,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) tint else tint.copy(alpha = 0.08f)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = if (selected) Color.White else tint,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
fun ProlesMetricCard(
    icon: ImageVector,
    label: String,
    value: String,
    subtitle: String? = null,
    tint: Color = ProlesPrimary,
    modifier: Modifier = Modifier
) {
    ProlesCard(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        ProlesIconBadge(icon = icon, tint = tint, size = 32.dp)
        Spacer(Modifier.height(10.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = ProlesMuted)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = ProlesText)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ProlesMuted)
        }
    }
}

@Composable
fun ProlesQuickAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit,
    badge: Int = 0,
    modifier: Modifier = Modifier
) {
    ProlesCard(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.Top) {
            ProlesIconBadge(icon, tint, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = ProlesText)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ProlesMuted, maxLines = 2)
            }
            if (badge > 0) {
                Surface(shape = CircleShape, color = ProlesExpense) {
                    Text(
                        if (badge > 99) "99+" else badge.toString(),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
fun ProlesCompactAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(78.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = tint.copy(alpha = 0.055f)
        ),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.16f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProlesIconBadge(icon, tint, size = 32.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = ProlesText,
                    maxLines = 1
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = ProlesMuted,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
fun ProlesPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ProlesPrimary,
            contentColor = Color.White
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

@Composable
fun ProlesSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ProlesBorder)
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = ProlesText)
    }
}

@Composable
fun ProlesDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = ProlesExpenseSoft,
            contentColor = ProlesExpense
        ),
        border = BorderStroke(1.dp, ProlesExpense.copy(alpha = 0.15f))
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

@Composable
fun ProlesHero(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradient: List<Color> = listOf(Color(0xFF059669), Color(0xFF0EA5E9), Color(0xFF4F46E5)),
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(gradient), shape)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White.copy(alpha = 0.18f)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(10.dp).size(24.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.82f))
            }
        }
    }
}
