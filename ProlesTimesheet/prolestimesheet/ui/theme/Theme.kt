package com.example.prolestimesheet.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA5D6A7),
    secondary = Color(0xFF388E3C),
    surface = Color.White,
    surfaceVariant = Color(0xFFF5F5F5),
    onSurface = Color(0xFF1B1B1F),
    outline = Color(0xFF757575)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4CAF50),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1B5E20),
    secondary = Color(0xFF66BB6A),
    surface = Color(0xFF121212),
    surfaceVariant = Color(0xFF1E1E24),
    onSurface = Color(0xFFE6E6E6),
    outline = Color(0xFF9E9E9E)
)

@Composable
fun ProlesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}