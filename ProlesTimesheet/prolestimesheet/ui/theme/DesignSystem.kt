package com.example.prolestimesheet.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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

val ProlesCanvas = Color(0xFFF8FAFC)
val ProlesSurface = Color(0xFFFFFFFF)
val ProlesBorder = Color(0xFFE2E8F0)
val ProlesText = Color(0xFF0F172A)
val ProlesMuted = Color(0xFF64748B)
val ProlesPrimary = Color(0xFF059669)
val ProlesPrimarySoft = Color(0xFFECFDF5)
val ProlesSecondary = Color(0xFF4F46E5)
val ProlesSecondarySoft = Color(0xFFEEF2FF)
val ProlesExpense = Color(0xFFE11D48)
val ProlesExpenseSoft = Color(0xFFFFF1F2)
val ProlesRestSoft = Color(0xFFF1F5F9)
val ProlesRestText = Color(0xFF475569)

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
        modifier = modifier.fillMaxWidth().height(48.dp),
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
