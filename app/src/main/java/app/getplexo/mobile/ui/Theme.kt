@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.getplexo.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.getplexo.mobile.R
import app.getplexo.mobile.core.ThemeMode

/**
 * Plexo's desktop design, in four finishes: the graphite "instrument panel", a
 * warm paper light theme, and an OLED black theme. The accent and the link
 * colours stay the same so a network keeps its identity in every theme.
 */
data class PlexoPalette(
    val ink: Color,
    val panel: Color,
    val raised: Color,
    val track: Color,
    val stroke: Color,
    val strokeSubtle: Color,
    val strokeStrong: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val textFaint: Color,
    val amber: Color,
    val onAmber: Color,
    val mint: Color,
    val violet: Color,
    val coral: Color,
    val steel: Color,
    val cyan: Color,
    val isLight: Boolean,
)

private val DarkPalette = PlexoPalette(
    ink = Color(0xFF1B1E20),
    panel = Color(0xFF202325),
    raised = Color(0xFF242729),
    track = Color(0xFF262A2D),
    stroke = Color(0xFF2B2F33),
    strokeSubtle = Color(0xFF23262A),
    strokeStrong = Color(0xFF33383C),
    textPrimary = Color(0xFFEAE7E2),
    textMuted = Color(0xFFA9ADB2),
    textFaint = Color(0xFF8D9196),
    amber = Color(0xFFD8A44C),
    onAmber = Color(0xFF1B1408),
    mint = Color(0xFF4EA89A),
    violet = Color(0xFF9C8FD6),
    coral = Color(0xFFEDA3A3),
    steel = Color(0xFF7E93BD),
    cyan = Color(0xFF6DB8C9),
    isLight = false,
)

private val LightPalette = PlexoPalette(
    ink = Color(0xFFF4F0E9),
    panel = Color(0xFFFCFAF6),
    raised = Color(0xFFFFFFFF),
    track = Color(0xFFE8E1D6),
    stroke = Color(0xFFDCD3C5),
    strokeSubtle = Color(0xFFE7DFD3),
    strokeStrong = Color(0xFFC8BCA9),
    textPrimary = Color(0xFF202325),
    textMuted = Color(0xFF5D6469),
    textFaint = Color(0xFF7A8187),
    amber = Color(0xFFA96F12),
    onAmber = Color(0xFFFFFBF3),
    mint = Color(0xFF2F7F72),
    violet = Color(0xFF6C5CA8),
    coral = Color(0xFFB2453E),
    steel = Color(0xFF4E6696),
    cyan = Color(0xFF2E7D8C),
    isLight = true,
)

private val BlackPalette = DarkPalette.copy(
    ink = Color(0xFF000000),
    panel = Color(0xFF0A0A0B),
    raised = Color(0xFF121214),
    track = Color(0xFF19191C),
    stroke = Color(0xFF232327),
    strokeSubtle = Color(0xFF17171A),
    strokeStrong = Color(0xFF323237),
    textPrimary = Color(0xFFF3F0EB),
    textMuted = Color(0xFFA2A6AB),
    textFaint = Color(0xFF85898E),
)

val LocalPlexoPalette = staticCompositionLocalOf { DarkPalette }

private fun paletteFor(mode: ThemeMode, systemDark: Boolean): PlexoPalette = when (mode) {
    ThemeMode.SYSTEM -> if (systemDark) DarkPalette else LightPalette
    ThemeMode.LIGHT -> LightPalette
    ThemeMode.DARK -> DarkPalette
    ThemeMode.BLACK -> BlackPalette
}

// Composables read these names exactly like the old fixed colours; the value now
// follows the selected theme. Draw lambdas must capture them before Canvas.
val Ink: Color @Composable get() = LocalPlexoPalette.current.ink
val Panel: Color @Composable get() = LocalPlexoPalette.current.panel
val PanelRaised: Color @Composable get() = LocalPlexoPalette.current.raised
val Track: Color @Composable get() = LocalPlexoPalette.current.track
val Stroke: Color @Composable get() = LocalPlexoPalette.current.stroke
val StrokeSubtle: Color @Composable get() = LocalPlexoPalette.current.strokeSubtle
val StrokeStrong: Color @Composable get() = LocalPlexoPalette.current.strokeStrong
val TextPrimary: Color @Composable get() = LocalPlexoPalette.current.textPrimary
val TextMuted: Color @Composable get() = LocalPlexoPalette.current.textMuted
val TextFaint: Color @Composable get() = LocalPlexoPalette.current.textFaint
val Mint: Color @Composable get() = LocalPlexoPalette.current.mint
val Violet: Color @Composable get() = LocalPlexoPalette.current.violet
val Amber: Color @Composable get() = LocalPlexoPalette.current.amber
val OnAmber: Color @Composable get() = LocalPlexoPalette.current.onAmber
val Coral: Color @Composable get() = LocalPlexoPalette.current.coral
val Steel: Color @Composable get() = LocalPlexoPalette.current.steel
val Cyan: Color @Composable get() = LocalPlexoPalette.current.cyan

private val Manrope = FontFamily(
    Font(
        R.font.manrope_variable,
        weight = FontWeight.W400,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.manrope_variable,
        weight = FontWeight.W500,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.manrope_variable,
        weight = FontWeight.W600,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.manrope_variable,
        weight = FontWeight.W700,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

private val RobotoMono = FontFamily(
    Font(
        R.font.roboto_mono_variable,
        weight = FontWeight.W400,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.roboto_mono_variable,
        weight = FontWeight.W500,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.roboto_mono_variable,
        weight = FontWeight.W600,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
)

private val PlexoTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.W700,
        fontSize = 24.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.W600,
        fontSize = 16.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.W600,
        fontSize = 14.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Manrope,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Manrope,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = RobotoMono,
        fontWeight = FontWeight.W500,
        fontSize = 10.sp,
        letterSpacing = 2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = RobotoMono,
        fontWeight = FontWeight.W500,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = RobotoMono,
        fontWeight = FontWeight.W600,
        fontSize = 30.sp,
        letterSpacing = (-1).sp,
    ),
)

val MonoSmall = TextStyle(
    fontFamily = RobotoMono,
    fontWeight = FontWeight.W400,
    fontSize = 11.5.sp,
    letterSpacing = 0.sp,
)

val MonoMedium = TextStyle(
    fontFamily = RobotoMono,
    fontWeight = FontWeight.W600,
    fontSize = 15.sp,
    letterSpacing = (-0.2).sp,
)

@Composable
fun PlexoTheme(
    mode: ThemeMode = ThemeMode.DARK,
    systemDark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = paletteFor(mode, systemDark)
    val scheme = if (palette.isLight) {
        lightColorScheme(
            primary = palette.amber,
            onPrimary = palette.onAmber,
            secondary = palette.mint,
            onSecondary = Color.White,
            tertiary = palette.steel,
            background = palette.ink,
            onBackground = palette.textPrimary,
            surface = palette.panel,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.raised,
            onSurfaceVariant = palette.textMuted,
            outline = palette.strokeStrong,
            error = palette.coral,
        )
    } else {
        darkColorScheme(
            primary = palette.amber,
            onPrimary = palette.onAmber,
            secondary = palette.mint,
            onSecondary = Color(0xFF10201D),
            tertiary = palette.steel,
            background = palette.ink,
            onBackground = palette.textPrimary,
            surface = palette.panel,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.raised,
            onSurfaceVariant = palette.textMuted,
            outline = palette.strokeStrong,
            error = palette.coral,
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalPlexoPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = PlexoTypography,
            content = content,
        )
    }
}
