@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.getplexo.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.getplexo.mobile.R

/**
 * The Android app follows the desktop Plexo "v2" design: a graphite instrument
 * panel, warm off-white type, amber as the brand accent, and teal / steel / amber
 * for the individual links. Values are the exact ones in the desktop renderer's
 * main.css, not an approximation.
 */
val Ink = Color(0xFF1B1E20) // --bg
val Panel = Color(0xFF202325) // --bg-secondary
val PanelRaised = Color(0xFF242729) // --input-bg
val Track = Color(0xFF262A2D) // --track-bg
val Stroke = Color(0xFF2B2F33) // --border
val StrokeSubtle = Color(0xFF23262A) // --border-subtle
val StrokeStrong = Color(0xFF33383C) // --border-strong

val TextPrimary = Color(0xFFEAE7E2) // --text
val TextMuted = Color(0xFFA9ADB2) // --text-secondary
val TextFaint = Color(0xFF8D9196) // --text-tertiary

val Mint = Color(0xFF4EA89A) // --color-wifi / --color-success
val Violet = Color(0xFF9C8FD6) // --swatch-violet
val Amber = Color(0xFFD8A44C) // --color-accent / --color-usb
val Coral = Color(0xFFEDA3A3) // --color-danger
val Steel = Color(0xFF7E93BD) // --color-ethernet
val Cyan = Color(0xFF6DB8C9) // --swatch-cyan

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

private val Scheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1B1408),
    secondary = Mint,
    onSecondary = Color(0xFF10201D),
    tertiary = Steel,
    background = Ink,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = PanelRaised,
    onSurfaceVariant = TextMuted,
    outline = StrokeStrong,
    error = Coral,
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
    // Section labels are the desktop's tracked-out mono: 10 px, 0.2em.
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
    // Every number in Plexo (speeds, bytes, ETA, percentages) is Roboto Mono.
    displaySmall = TextStyle(
        fontFamily = RobotoMono,
        fontWeight = FontWeight.W600,
        fontSize = 30.sp,
        letterSpacing = (-1).sp,
    ),
)

/** Number style for the small mono readouts under charts, progress and links. */
val MonoSmall = TextStyle(
    fontFamily = RobotoMono,
    fontWeight = FontWeight.W400,
    fontSize = 11.5.sp,
    letterSpacing = 0.sp,
)

/** Medium mono emphasis for values that sit next to a mono label. */
val MonoMedium = TextStyle(
    fontFamily = RobotoMono,
    fontWeight = FontWeight.W600,
    fontSize = 15.sp,
    letterSpacing = (-0.2).sp,
)

@Composable
fun PlexoTheme(content: @Composable () -> Unit) {
    // Plexo is designed dark only, like the desktop's graphite panel.
    MaterialTheme(
        colorScheme = Scheme,
        typography = PlexoTypography,
        content = content,
    )
}
