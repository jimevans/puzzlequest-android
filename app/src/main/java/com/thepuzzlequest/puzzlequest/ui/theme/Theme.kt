package com.thepuzzlequest.puzzlequest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.thepuzzlequest.puzzlequest.R

// The brand's look (brand/README.md in the server repository): Material's own
// controls, in the brand's colors, with the brand's display face for headings
// and clocks, as on the website and in the iOS app. Body text, controls and
// navigation keep the system's, so the app feels at home on Android.

/** The brand's colors. */
object Brand {
    /** Banners and dark surfaces. */
    val Aubergine = Color(0xFF2A1638)

    /** Quieter text on aubergine. */
    val AubergineSoft = Color(0xFFC9B6D6)

    /** Buttons, links and the transit line. */
    val Purple = Color(0xFF6A2C91)

    /** The purple in dark mode, where the purple itself is too dark to read. */
    val Lavender = Color(0xFFB98CE0)

    /** The stop card's button (with aubergine text), the finish line and the board. */
    val Gold = Color(0xFFE8B33A)

    /** The logo's piece. */
    val PaleGold = Color(0xFFF8ECC4)
}

// Filled buttons are white on purple, or in dark mode aubergine on lavender,
// where white wouldn't be readable (2.7:1).
private val LightColors =
    lightColorScheme(
        primary = Brand.Purple,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFEEDDF8),
        onPrimaryContainer = Brand.Aubergine,
        tertiary = Brand.Gold,
        onTertiary = Brand.Aubergine,
    )

private val DarkColors =
    darkColorScheme(
        primary = Brand.Lavender,
        onPrimary = Brand.Aubergine,
        primaryContainer = Color(0xFF4A2A63),
        onPrimaryContainer = Color(0xFFEEDDF8),
        tertiary = Brand.Gold,
        onTertiary = Brand.Aubergine,
    )

/** Barlow Condensed Bold, the brand's display face (SIL Open Font License, in assets/licenses). */
val BarlowCondensed = FontFamily(Font(R.font.barlow_condensed_bold, FontWeight.Bold))

/**
 * Headings in the brand's face. Sizes are in sp, so they grow and shrink with
 * the phone's font size; a condensed face looks smaller than the system's at
 * the same size, so it's set a little larger.
 */
object BrandType {
    val Large =
        TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 42.sp, lineHeight = 46.sp)
    val Title =
        TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp)
}

@Composable
fun PuzzleQuestTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // No dynamic color (Android 12's colors from the wallpaper): the app
    // keeps the brand's.
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
