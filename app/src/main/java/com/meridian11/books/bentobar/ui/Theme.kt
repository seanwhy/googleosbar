package com.meridian11.books.bentobar.ui

import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface as ComposeTypeface

/**
 * The settings window's colors: BentoBar's own, mint on deep teal, the same on every device. (The bar's popups sit
 * on the user's wallpaper and keep the device's colors, [BentoBarTheme].) `PaletteContrastTest` holds every pair
 * that carries text or an outline to the design's contrast table.
 */
@Immutable
class Palette(
    val dark: Boolean,
    /** The header row, and the ground under the system's caption. */
    val chrome: Color,
    /** The window's ground. */
    val surface: Color,
    /** Cards: the bar's, the panel's. */
    val panel: Color,
    /** Segment and switch tracks, tonal buttons, fields, keycaps. */
    val sunk: Color,
    /** Hairlines between rows. */
    val line: Color,
    /** Radio and switch outlines, a switch's track while off. */
    val edge: Color,
    /** Text; secondary text; help. */
    val ink: Color, val ink2: Color, val ink3: Color,
    /** The chosen state, links, the focus ring. */
    val accent: Color,
    /** A selected row. */
    val soft: Color,
    /** The one filled button on a screen, and what stands on it. */
    val primary: Color, val onPrimary: Color,
    /** A command that can't be taken back. */
    val danger: Color,
    /** Notices. */
    val warnGround: Color, val warnInk: Color,
    /** The part of a segment that is up. */
    val raised: Color,
    /** The preview's strip before the real bar's colors are read: its gradient and its text. */
    val strip: List<Color>, val stripInk: Color,
    /** The preview while the service is off. */
    val stripOff: Color, val stripOffInk: Color,
) {
    /** The thumb of something that scrolls. */
    val thumb: Color get() = ink.copy(alpha = 0.28f)

    /**
     * The palette as the Material roles today's pages and Material's own parts (the message bar, a dialog) read, so
     * a page not yet redesigned stands in the same colors as the header above it.
     */
    fun scheme(): ColorScheme {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        val other = if (dark) Light else Dark
        return base.copy(
            primary = primary, onPrimary = onPrimary, primaryContainer = soft, onPrimaryContainer = ink, inversePrimary = other.accent,
            secondary = accent, onSecondary = onPrimary, secondaryContainer = soft, onSecondaryContainer = ink,
            tertiary = accent, onTertiary = onPrimary, tertiaryContainer = warnGround, onTertiaryContainer = warnInk,
            background = surface, onBackground = ink, surface = surface, onSurface = ink,
            surfaceVariant = sunk, onSurfaceVariant = ink2, surfaceTint = Color.Transparent,
            inverseSurface = ink, inverseOnSurface = surface,
            error = danger, onError = panel, errorContainer = warnGround, onErrorContainer = warnInk,
            outline = edge, outlineVariant = line,
            surfaceBright = panel, surfaceDim = sunk,
            surfaceContainerLowest = panel, surfaceContainerLow = panel, surfaceContainer = panel,
            surfaceContainerHigh = sunk, surfaceContainerHighest = sunk,
        )
    }

    companion object {
        val Light = Palette(
            dark = false,
            chrome = Color(0xFFECEFEE), surface = Color(0xFFF4F6F5), panel = Color(0xFFFFFFFF), sunk = Color(0xFFECF0EE),
            line = Color(0xFFE1E6E4), edge = Color(0xFF7C8A87),
            ink = Color(0xFF101A19), ink2 = Color(0xFF3F4D4B), ink3 = Color(0xFF596765),
            accent = Color(0xFF0B6660), soft = Color(0xFFDCEDE8), primary = Color(0xFF133F43), onPrimary = Color(0xFFFFFFFF),
            danger = Color(0xFFB3261E), warnGround = Color(0xFFFFF1CC), warnInk = Color(0xFF6B4100), raised = Color(0xFFFFFFFF),
            strip = listOf(Color(0xFFDDE6F3), Color(0xFFD5E8E6), Color(0xFFE3EEE9)), stripInk = Color(0xFF1F1F1F),
            stripOff = Color(0xFF133F43), stripOffInk = Color(0xFFF2FAF7),
        )
        val Dark = Palette(
            dark = true,
            chrome = Color(0xFF1B1C1E), surface = Color(0xFF131416), panel = Color(0xFF1E2022), sunk = Color(0xFF292B2F),
            line = Color(0xFF2F3135), edge = Color(0xFF7A7F85),
            ink = Color(0xFFECEEF0), ink2 = Color(0xFFBEC2C7), ink3 = Color(0xFF9EA3AA),
            accent = Color(0xFF8FD0C0), soft = Color(0xFF1F3833), primary = Color(0xFFA6D3C6), onPrimary = Color(0xFF0B2A2C),
            danger = Color(0xFFFFB4AB), warnGround = Color(0xFF3B2F14), warnInk = Color(0xFFF3D38E), raised = Color(0xFF44474C),
            strip = listOf(Color(0xFF5B6F8A), Color(0xFF3F6F73), Color(0xFF4F7E78)), stripInk = Color(0xFFFFFFFF),
            stripOff = Color(0xFF133F43), stripOffInk = Color(0xFFF2FAF7),
        )
    }
}

/**
 * The settings window's text: six sizes, weights 400, 500 and 600, the system's font family (as the pages have it
 * today), no letter spacing, no italics, no capitals. In a window laid out from right to left ([rtl]) a text is set
 * to the right and reads in its own words' direction: the app's words are English, and read as a right-to-left
 * paragraph a sentence's full stop and the arrow's sign change sides ("‹ Behind", pointing the other way).
 */
@Immutable
class Type(private val family: FontFamily?, private val rtl: Boolean = false) {
    private fun style(size: Int, line: Int, weight: FontWeight) =
        TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = 0.sp,
            textDirection = if (rtl) TextDirection.Content else TextDirection.Unspecified, textAlign = if (rtl) TextAlign.Right else TextAlign.Unspecified)

    /** 26 / 600: the disclosure page's title. */
    val display = style(26, 32, FontWeight.SemiBold)
    /** 20 / 600: reserved for a page heading. */
    val heading = style(20, 26, FontWeight.SemiBold)
    /** 16 / 600: an item's name in Options, section titles, the wordmark. */
    val title = style(16, 22, FontWeight.SemiBold)
    /** 14 / 600: list and area titles, the chosen tab, buttons. */
    val strong = style(14, 20, FontWeight.SemiBold)
    /** 14 / 500: row and setting names, tabs. */
    val label = style(14, 20, FontWeight.Medium)
    /** 14 / 400: a field's text, a choice that is not the chosen one, About's tagline. */
    val body = style(14, 20, FontWeight.Normal)
    /** 13 / 400: the status line, notices, paragraphs; with [FontWeight.Medium] or SemiBold where the design says. */
    val small = style(13, 18, FontWeight.Normal)
    /** 12 / 400: help, keycaps, part labels. */
    val help = style(12, 16, FontWeight.Normal)
}

/** Numbers that tick keep their width. */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

/** Spacing: these five and nothing between. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
}

/** Corners: keycaps, small marks and a segment's parts; rows, buttons, segments, fields and notices; cards and the preview. */
object Radius {
    val small = 6.dp
    val medium = 10.dp
    val large = 16.dp
}

/** The least height (and width, for a square one) of what takes a click. */
object Target {
    val row = 48.dp
    val control = 40.dp
    val tab = 52.dp
}

/**
 * A width that holds words, in the text's size: the design's width at the standard text size and below, and as much
 * wider as the text is larger (twice as wide at twice the size).
 */
@Composable
@ReadOnlyComposable
internal fun Dp.ofText(): Dp = this * LocalDensity.current.fontScale.coerceAtLeast(1f)

/**
 * How many lines a text may take that the design draws on [few]: at the standard text size and below it keeps to them
 * and ends in an ellipsis; at a larger size it takes the lines it needs, since there the room was not made for it.
 */
@Composable
@ReadOnlyComposable
internal fun lines(few: Int = 1): Int = if (LocalDensity.current.fontScale > 1f) Int.MAX_VALUE else few

private val LocalPalette = staticCompositionLocalOf { Palette.Light }
private val LocalType = staticCompositionLocalOf { Type(null) }

/**
 * Whether what is drawn stands in the settings window. The few pieces an item's options share with its popup (a search
 * field) take the window's tokens there, and keep Material's shapes on the device's colors in the popup.
 */
internal val LocalSettingsWindow = staticCompositionLocalOf { false }

/** The settings window's tokens where a composable draws: `Bento.colors.accent`, `Bento.type.label`. */
object Bento {
    val colors: Palette @Composable @ReadOnlyComposable get() = LocalPalette.current
    val type: Type @Composable @ReadOnlyComposable get() = LocalType.current
}

/**
 * The settings window's theme: BentoBar's [Palette] and [Type], and Material 3 on the same colors for the pages
 * and parts that still read Material's roles.
 */
@Composable
fun SettingsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) Palette.Dark else Palette.Light
    val typography = remember { typography() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val type = remember(typography, rtl) { Type(typography.bodyMedium.fontFamily, rtl) }
    CompositionLocalProvider(LocalPalette provides palette, LocalType provides type, LocalSettingsWindow provides true) {
        MaterialTheme(colorScheme = remember(palette) { palette.scheme() }, typography = typography, content = content)
    }
}

/** The bar's popups: Material 3 with the device's dynamic colours and Google Sans where the device has it. */
@Composable
fun BentoBarTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    val typography = remember { typography() }
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

private fun family(vararg names: String): FontFamily? {
    for (n in names) {
        val t = Typeface.create(n, Typeface.NORMAL)
        if (t != Typeface.DEFAULT) return FontFamily(ComposeTypeface(t))
    }
    return null
}

private fun typography(): Typography {
    val base = Typography()
    // Google Sans Flex is the Googlebook's UI font; older builds have google-sans-text.
    val text = family("google-sans-flex") ?: family("google-sans-text") ?: family("google-sans") ?: return base
    val display = family("google-sans-flex") ?: family("google-sans") ?: text
    // Google Sans is spaced for 0 tracking; Material's defaults (0.25 to 0.5 sp) are tuned for Roboto
    // and made body text look loosely set.
    fun TextStyle.t() = copy(fontFamily = text, letterSpacing = 0.sp)
    fun TextStyle.d() = copy(fontFamily = display, letterSpacing = 0.sp)
    return base.copy(
        displayLarge = base.displayLarge.d(), displayMedium = base.displayMedium.d(), displaySmall = base.displaySmall.d(),
        headlineLarge = base.headlineLarge.d(), headlineMedium = base.headlineMedium.d(), headlineSmall = base.headlineSmall.d(),
        titleLarge = base.titleLarge.d(), titleMedium = base.titleMedium.t(), titleSmall = base.titleSmall.t(),
        bodyLarge = base.bodyLarge.t(), bodyMedium = base.bodyMedium.t(), bodySmall = base.bodySmall.t(),
        labelLarge = base.labelLarge.t(), labelMedium = base.labelMedium.t(), labelSmall = base.labelSmall.t(),
    )
}
