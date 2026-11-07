package com.meridian11.books.bentobar.util

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fonts: the Material Symbols subset from assets, and the device's own UI families when present. */
object Fonts {
    lateinit var symbols: FontFamily private set
    lateinit var symbolsFilled: FontFamily private set
    /** The status bar's look: Google Sans on Google devices, the platform sans-serif elsewhere. */
    lateinit var bar: FontFamily private set
    lateinit var barTypeface: Typeface private set
    /**
     * The weight of the status bar's own text. A text style has to name it: Compose asks the typeface
     * for the style's weight, and for a style without one that is 400, whatever weight the typeface
     * was made with. (The strip drew regular text next to the system's semibold clock that way.)
     */
    var barWeight: FontWeight = FontWeight.Medium; private set

    @Synchronized
    fun init(context: Context) {
        if (::symbols.isInitialized) return
        val am = context.applicationContext.assets
        symbols = FontFamily(ComposeTypeface(Typeface.createFromAsset(am, "fonts/MaterialSymbolsRounded.ttf")))
        symbolsFilled = FontFamily(ComposeTypeface(Typeface.createFromAsset(am, "fonts/MaterialSymbolsRounded_Fill.ttf")))
        // Googlebooks name their UI font "google-sans-flex" (variable). The status bar draws its clock in
        // the emphasized label style: weight 600 with rounded ends, which has a family name of its own.
        // Older builds had "google-sans-text-medium". Without any of them, BentoBar fell back to the
        // generic sans-serif, which looked technical next to the system's clock.
        val flex = systemFamily("variable-label-large-emphasized") ?: systemFamily("google-sans-flex")
        if (flex != null) barWeight = FontWeight.SemiBold
        barTypeface = flex?.let { Typeface.create(it, barWeight.weight, false) }
            ?: systemFamily("google-sans-text-medium") ?: systemFamily("google-sans-medium")
            ?: systemFamily("google-sans")?.let { Typeface.create(it, 500, false) }
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        bar = FontFamily(ComposeTypeface(barTypeface))
    }

    /** A named system family, or null when the device doesn't have it (Typeface.create falls back silently). */
    private fun systemFamily(name: String): Typeface? {
        val t = Typeface.create(name, Typeface.NORMAL)
        return if (t == Typeface.DEFAULT || t == Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)) null else t
    }
}

/**
 * One Material Symbol. [size] is the glyph's em size; symbols fill their em box. The glyph is a
 * private-use character, so a screen reader would read nonsense: give [contentDescription] when
 * the icon carries meaning on its own (an icon-only button); without it the icon is silent.
 */
private const val PATH_ANTIGRAVITY =
    "M12 2.5C15.2 2.5 17.0 6.2 18.6 12.2C19.5 15.6 20.6 18.2 21.6 19.6C22.1 20.3 21.6 21.3 20.7 21.3C19.8 21.3 18.8 20.4 17.6 18.7C16.1 16.5 14.3 14.2 12 14.2C9.7 14.2 7.9 16.5 6.4 18.7C5.2 20.4 4.2 21.3 3.3 21.3C2.4 21.3 1.9 20.3 2.4 19.6C3.4 18.2 4.5 15.6 5.4 12.2C7.0 6.2 8.8 2.5 12 2.5Z"

private const val PATH_CODEX_OPENAI =
    "M22.2819 9.8211a5.9847 5.9847 0 0 0-.5157-4.9108 6.0462 6.0462 0 0 0-6.5098-2.9A6.0651 6.0651 0 0 0 4.9807 4.1818a5.9847 5.9847 0 0 0-3.9977 2.9 6.0462 6.0462 0 0 0 .7427 7.0966 5.98 5.98 0 0 0 .511 4.9107 6.051 6.051 0 0 0 6.5146 2.9001A5.9847 5.9847 0 0 0 13.2599 24a6.0557 6.0557 0 0 0 5.7718-4.2058 5.9894 5.9894 0 0 0 3.9977-2.9001 6.0557 6.0557 0 0 0-.7475-7.0729zm-9.022 12.6081a4.4755 4.4755 0 0 1-2.8764-1.0408l.1419-.0804 4.7783-2.7582a.7948.7948 0 0 0 .3927-.6813v-6.7369l2.02 1.1686a.071.071 0 0 1 .038.052v5.5826a4.504 4.504 0 0 1-4.4945 4.4944zm-9.6607-4.1254a4.4708 4.4708 0 0 1-.5346-3.0137l.142.0852 4.783 2.7582a.7712.7712 0 0 0 .7806 0l5.8428-3.3685v2.3324a.0804.0804 0 0 1-.0332.0615L9.74 19.9502a4.4992 4.4992 0 0 1-6.1408-1.6464zM2.3408 7.8956a4.485 4.485 0 0 1 2.3655-1.9728V11.6a.7664.7664 0 0 0 .3879.6765l5.8144 3.3543-2.0201 1.1685a.0757.0757 0 0 1-.071 0l-4.8303-2.7865A4.504 4.504 0 0 1 2.3408 7.872zm16.5963 3.8558L13.1038 8.364 15.1192 7.2a.0757.0757 0 0 1 .071 0l4.8303 2.7913a4.4944 4.4944 0 0 1-.6765 8.1042v-5.6772a.79.79 0 0 0-.407-.667zm2.0107-3.0231l-.142-.0852-4.7735-2.7818a.7759.7759 0 0 0-.7854 0L9.409 9.2297V6.8974a.0662.0662 0 0 1 .0284-.0615l4.8303-2.7866a4.4992 4.4992 0 0 1 6.6802 4.66zM8.3065 12.863l-2.02-1.1638a.0804.0804 0 0 1-.038-.0567V6.0742a4.4992 4.4992 0 0 1 7.3757-3.4537l-.142.0805L8.704 5.459a.7948.7948 0 0 0-.3975.6813zm1.0976-2.3654l2.602-1.4998 2.6069 1.4998v2.9994l-2.5974 1.4997-2.6067-1.4997Z"

@Composable
fun SymIcon(
    sym: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 20.sp,
    filled: Boolean = false,
    color: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    if (sym.startsWith("brand:")) {
        val parsedPath = remember(sym) {
            when (sym) {
                "brand:antigravity" -> PathParser().parsePathString(PATH_ANTIGRAVITY).toPath().apply { fillType = PathFillType.EvenOdd }
                "brand:codex" -> PathParser().parsePathString(PATH_CODEX_OPENAI).toPath().apply { fillType = PathFillType.EvenOdd }
                else -> null
            }
        }
        Canvas(
            modifier = modifier
                .size((size.value + 1f).dp)
                .clearAndSetSemantics { if (contentDescription != null) this.contentDescription = contentDescription }
        ) {
            val s = this.size.minDimension / 24f
            when (sym) {
                "brand:antigravity", "brand:codex" -> {
                    parsedPath?.let { p ->
                        scale(scaleX = s, scaleY = s, pivot = Offset.Zero) {
                            drawPath(path = p, color = color)
                        }
                    }
                }
                "brand:grok" -> {
                    drawCircle(
                        color = color,
                        radius = 7.2f * s,
                        center = Offset(12f * s, 12f * s),
                        style = Stroke(width = 2.4f * s)
                    )
                    drawLine(
                        color = color,
                        start = Offset(4.2f * s, 19.8f * s),
                        end = Offset(19.8f * s, 4.2f * s),
                        strokeWidth = 2.8f * s,
                        cap = StrokeCap.Round
                    )
                }
                "brand:grok_bot" -> {
                    // Antenna
                    drawLine(
                        color = color,
                        start = Offset(12f * s, 2.5f * s),
                        end = Offset(12f * s, 6.2f * s),
                        strokeWidth = 2.2f * s,
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = color,
                        radius = 1.4f * s,
                        center = Offset(12f * s, 2.2f * s)
                    )
                    // Bot Visor Head
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(4f * s, 6.5f * s),
                        size = Size(16f * s, 13f * s),
                        cornerRadius = CornerRadius(4.2f * s, 4.2f * s),
                        style = Stroke(width = 2.2f * s)
                    )
                    // Left eye dot
                    drawCircle(
                        color = color,
                        radius = 1.6f * s,
                        center = Offset(9f * s, 13f * s)
                    )
                    // Right Grok slash eye
                    drawLine(
                        color = color,
                        start = Offset(13.2f * s, 15.2f * s),
                        end = Offset(16.8f * s, 10.8f * s),
                        strokeWidth = 2.3f * s,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
        return
    }
    Text(
        text = sym,
        modifier = modifier.clearAndSetSemantics { if (contentDescription != null) this.contentDescription = contentDescription },
        color = color,
        style = TextStyle(
            fontFamily = if (filled) Fonts.symbolsFilled else Fonts.symbols,
            fontSize = size,
            lineHeight = size,
            textAlign = TextAlign.Center,
        ),
        maxLines = 1,
    )
}
