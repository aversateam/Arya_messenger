package dev.arya.messenger.ui.theme

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) {
    LIGHT("روشن"),
    DARK("تاریک"),
    SYSTEM("همراه با سیستم")
}

/** Small SharedPreferences-backed store for the user's theme choice. */
object AryaPrefs {
    private const val FILE = "arya_settings"
    private const val KEY_THEME = "theme_mode"

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var loaded = false

    private val _theme = MutableStateFlow(ThemeMode.SYSTEM)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private fun settings(context: Context): SharedPreferences = prefs ?: synchronized(this) {
        prefs ?: context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .also { prefs = it }
    }

    private fun ensure(context: Context) {
        if (loaded) return
        loaded = true
        _theme.value = try {
            ThemeMode.valueOf(settings(context).getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }
    }

    fun themeMode(context: Context): ThemeMode {
        ensure(context)
        return _theme.value
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        ensure(context)
        settings(context).edit().putString(KEY_THEME, mode.name).apply()
        _theme.value = mode
    }
}

private val Ink = Color(0xFF182B2A)
private val Teal = Color(0xFF356B62)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E8E3),
    onPrimaryContainer = Color(0xFF0E2A26),
    secondary = Color(0xFF4F6B66),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2ECE9),
    onSecondaryContainer = Color(0xFF0C1F1C),
    background = Color(0xFFF6F7F4),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEAF0ED),
    onSurfaceVariant = Color(0xFF4A5B58),
    outline = Color(0xFF7A8B87),
    outlineVariant = Color(0xFFCAD5D1),
    tertiaryContainer = Color(0xFFFFE2B8),
    onTertiaryContainer = Color(0xFF3E2A00),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CC2B7),
    onPrimary = Color(0xFF06231F),
    primaryContainer = Color(0xFF1F4A43),
    onPrimaryContainer = Color(0xFFCFE8E2),
    secondary = Color(0xFFA9C4BE),
    onSecondary = Color(0xFF102925),
    secondaryContainer = Color(0xFF27413D),
    onSecondaryContainer = Color(0xFFDCE9E5),
    background = Color(0xFF0F1514),
    onBackground = Color(0xFFDFE8E5),
    surface = Color(0xFF141C1A),
    onSurface = Color(0xFFDFE8E5),
    surfaceVariant = Color(0xFF1E2927),
    onSurfaceVariant = Color(0xFFA9BBB6),
    outline = Color(0xFF556560),
    outlineVariant = Color(0xFF33413E),
    tertiaryContainer = Color(0xFF4A3A12),
    onTertiaryContainer = Color(0xFFFFDFA8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun AryaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val mode by AryaPrefs.theme.collectAsState(initial = AryaPrefs.themeMode(context))
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }
    val colors = if (dark) DarkColors else LightColors

    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        // Keep the window itself in sync so areas not yet drawn by Compose match the theme.
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(if (dark) 0xFF0F1514.toInt() else 0xFFF6F7F4.toInt())
        )
        window.statusBarColor = if (dark) 0xFF141C1A.toInt() else 0xFFF6F7F4.toInt()
        window.navigationBarColor = if (dark) 0xFF0F1514.toInt() else 0xFFF6F7F4.toInt()
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
