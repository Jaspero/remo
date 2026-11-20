package co.jaspero.remo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object RemoColors {
    val Background = Color(0xFF0D0E12)
    val Surface = Color(0xFF17191F)
    val Raised = Color(0xFF22252D)
    val RaisedPressed = Color(0xFF343844)
    val Outline = Color(0xFF2E323C)
    val Accent = Color(0xFF8AB4FF)
    val Text = Color(0xFFECEEF3)
    val Muted = Color(0xFF8B91A0)
    val Power = Color(0xFFFF5A5F)
    val Good = Color(0xFF3DDC97)
    val Warn = Color(0xFFFFB547)
}

@Composable
fun RemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RemoColors.Accent,
            onPrimary = Color(0xFF0B1B3A),
            background = RemoColors.Background,
            onBackground = RemoColors.Text,
            surface = RemoColors.Surface,
            onSurface = RemoColors.Text,
            surfaceVariant = RemoColors.Raised,
            onSurfaceVariant = RemoColors.Muted,
            surfaceContainer = RemoColors.Surface,
            surfaceContainerHigh = RemoColors.Raised,
            surfaceContainerLow = RemoColors.Surface,
            outline = RemoColors.Outline,
            error = RemoColors.Power,
        ),
        content = content,
    )
}
