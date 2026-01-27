package sh.mapme.mapper.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Mapme.sh Brand Colors
val MapmeGreen = Color(0xFF22C55E)
val MapmeBlue = Color(0xFF3B82F6)
val MapmeOrange = Color(0xFFF97316)

private val DarkColorScheme = darkColorScheme(
    primary = MapmeGreen,
    secondary = MapmeBlue,
    tertiary = MapmeOrange,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
)

private val LightColorScheme = lightColorScheme(
    primary = MapmeGreen,
    secondary = MapmeBlue,
    tertiary = MapmeOrange,
    background = Color(0xFFF5F5F5),
    surface = Color.White,
)

@Composable
fun MapmeMapperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
