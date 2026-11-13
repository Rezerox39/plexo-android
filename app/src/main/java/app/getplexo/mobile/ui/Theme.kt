package app.getplexo.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0A0B0F)
val Panel = Color(0xFF14161F)
val PanelRaised = Color(0xFF1B1E2A)
val Stroke = Color(0xFF262A38)
val TextPrimary = Color(0xFFF2F4F8)
val TextMuted = Color(0xFF98A1B2)
val Mint = Color(0xFF34D399)
val Violet = Color(0xFF818CF8)
val Amber = Color(0xFFF59E0B)
val Coral = Color(0xFFF87171)

private val Scheme = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF04150F),
    secondary = Violet,
    onSecondary = Color(0xFF0B0A1F),
    tertiary = Amber,
    background = Ink,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = PanelRaised,
    onSurfaceVariant = TextMuted,
    outline = Stroke,
    error = Coral,
)

private val PlexoTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun PlexoTheme(content: @Composable () -> Unit) {
    // Plexo is designed dark only; there is no light palette.
    MaterialTheme(
        colorScheme = Scheme,
        typography = PlexoTypography,
        content = content,
    )
}
