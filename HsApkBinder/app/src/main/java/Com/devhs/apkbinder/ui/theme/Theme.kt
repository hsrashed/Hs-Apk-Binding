package Com.devhs.apkbinder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val Scheme = darkColorScheme(
    primary = NeonTeal,
    onPrimary = BgDeep,
    secondary = NeonBlue,
    onSecondary = BgDeep,
    background = BgDeep,
    onBackground = TextPrimary,
    surface = BgSurface,
    onSurface = TextPrimary,
    surfaceVariant = BgElevated,
    onSurfaceVariant = TextMuted,
    outline = StrokeSoft,
    error = TerminalRed
)

@Composable
fun HsTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
