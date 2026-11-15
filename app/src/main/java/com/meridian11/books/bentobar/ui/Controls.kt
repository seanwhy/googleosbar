package com.meridian11.books.bentobar.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.SliderMath
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon

/** The bar's three areas as choices, in the order they stand in the status bar. */
@Composable
fun areaChoices(): List<Pair<Position, String>> = listOf(Position.LEFT to stringResource(R.string.areas_left),
    Position.CENTER to stringResource(R.string.areas_middle), Position.RIGHT to stringResource(R.string.areas_right))

/** How a choice is set where room is short (an item's options in the panel). */
/** The hidden-items arrow as a screen reader says it: a sign is read as "single left-pointing angle quotation mark", or not at all. */
object ArrowWords {
    /**
     * [text] with each arrow sign as [arrow], the words for it ("the hidden-items arrow"), or null when it holds no
     * sign. An article in front of the sign is not said twice ("the ‹ menu"). The sign is ‹; [both] takes › for it
     * too, which is the Left area's arrow in a row of the list and anywhere else a step in a path ("Setup › Online services").
     */
    fun spoken(text: String, arrow: String, both: Boolean = false): String? {
        val signs = if (both) listOf("‹", "›") else listOf("‹")
        if (signs.none { it in text }) return null
        val article = arrow.substringBefore(' ', "")
        return signs.fold(text) { t, sign -> (if (article.isEmpty()) t else t.replace("$article $sign", arrow)).replace(sign, arrow) }
    }
}

/** [text] as it is read out where it holds the arrow's sign, else null: for [said]. */
@Composable
internal fun arrowInWords(text: String, both: Boolean = false): String? = ArrowWords.spoken(text, stringResource(R.string.chevron_spoken), both)

/** What a screen reader says in this text's place; with no [words], the text itself. */
internal fun Modifier.said(words: String?): Modifier = if (words == null) this else semantics { contentDescription = words }

object ChoiceRules {
    /**
     * A choice of two or three short values is a segment, as Show as and Visibility are; anything longer is a list of
     * radio buttons, which never cuts a word short. [labels] are the choices as they are shown.
     */
    fun asSegment(labels: List<String>): Boolean = labels.size in 2..3 && labels.sumOf { it.length } <= SEGMENT_CHARS

    /** What three parts hold beside the panel's label column, at the default text size, without cutting a word. */
    const val SEGMENT_CHARS = 20
}

@Composable
internal fun Hairline() {
    Box(Modifier.fillMaxWidth().padding(vertical = Space.s).height(1.dp).background(Bento.colors.line))
}

/**
 * One setting in the panel: its label in a column at the left (with its keys under it, if it has any), the control beside it.
 */
@Composable
internal fun OptionRow(label: String, keys: List<String> = emptyList(), content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(OPTION_LABEL.ofText()).padding(end = Space.s)) {
            Text(label, style = Bento.type.small, color = Bento.colors.ink2, maxLines = lines(2), overflow = TextOverflow.Ellipsis)
            if (keys.isNotEmpty()) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(Space.xs)) { keys.forEach { Keycap(it) } }
        }
        content()
    }
}

/** A line of help under a setting in the panel. */
@Composable
internal fun OptionHelp(text: String, warn: Boolean = false) {
    Text(text, Modifier.fillMaxWidth().padding(bottom = Space.s).said(arrowInWords(text)), style = Bento.type.help, color = if (warn) Bento.colors.warnInk else Bento.colors.ink2)
}

/**
 * A single choice as a list of radio buttons, one under the other: the chosen one has the filled ring and the heavier
 * words (never told by color alone). With nothing [selected] none is chosen (Look, while the items are in several areas).
 */
@Composable
fun <T> RadioList(choices: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Bento.colors
    Column(modifier.selectableGroup()) {
        choices.forEach { (value, text) ->
            val chosen = value == selected
            val interaction = remember { MutableInteractionSource() }
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp)
                .states(interaction, radius = Radius.small)
                .selectable(chosen, interaction, indication = null, role = Role.RadioButton) { if (!chosen) onSelect(value) }
                .padding(end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(18.dp)) {
                    // Chosen: a heavy ring around a small open middle. Not chosen: a thin one.
                    val w = (if (chosen) 5.5.dp else 1.5.dp).toPx()
                    drawCircle(if (chosen) c.accent else c.ink3, (size.minDimension - w) / 2, style = Stroke(w))
                }
                Spacer(Modifier.width(10.dp))
                Text(text, Modifier.said(arrowInWords(text)), style = if (chosen) Bento.type.strong else Bento.type.body, color = c.ink)
            }
        }
    }
}

/** A labelled single choice in an item's options: a segment for two or three short values, else a list of radio buttons ([ChoiceRules]). */
@Composable
fun <T> ChoiceRow(label: String, choices: List<Pair<T, String>>, selected: T, help: String? = null, onSelect: (T) -> Unit) {
    val segment = ChoiceRules.asSegment(choices.map { it.second })
    Column(Modifier.fillMaxWidth()) {
        if (segment) OptionRow(label) {
            Segment(choices.map { SegmentPart(it.second) }, choices.indexOfFirst { it.first == selected },
                { onSelect(choices[it].first) }, Modifier.weight(1f).semantics { contentDescription = label }, equal = false)
        } else {
            // The list's name stands over it: the values then have the panel's whole width.
            Text(label, Modifier.padding(top = Space.m, bottom = Space.xs), style = Bento.type.small, color = Bento.colors.ink2)
            RadioList(choices, selected, onSelect, Modifier.fillMaxWidth().padding(bottom = Space.xs).semantics { contentDescription = label })
        }
        if (help != null) OptionHelp(help)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, help: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = Bento.colors
    val interaction = remember { MutableInteractionSource() }
    // The whole row is the switch: clicking the label toggles it, and a screen reader hears its name.
    Row(Modifier.fillMaxWidth().heightIn(min = Target.row)
        .states(interaction, radius = Radius.small)
        .toggleable(checked, interaction, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Bento.type.label, color = if (enabled) c.ink else c.ink3)
            if (help != null) Text(help, style = Bento.type.help, color = c.ink2)
        }
        Spacer(Modifier.width(Space.l))
        SwitchMark(checked, enabled = enabled)
    }
}

/**
 * A text field on the window's tokens: sunk, no outline, [placeholder] while it is empty; [label] is its spoken name.
 * [marked]: what it holds can't be used (the words that say why stand under it). [secret]: it shows dots. [lines]: more
 * than one, and it is that many lines high, takes line breaks, and scrolls within itself past twice that.
 */
@Composable
internal fun BentoField(value: TextFieldValue, onValue: (TextFieldValue) -> Unit, label: String, modifier: Modifier = Modifier,
                        placeholder: String = "", marked: Boolean = false, secret: Boolean = false,
                        keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
                        trailing: (@Composable () -> Unit)? = null, lines: Int = 1) {
    val several = lines > 1
    val c = Bento.colors
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(value, onValue,
        modifier.heightIn(min = Target.control)
            .states(interaction, ground = false)
            .background(c.sunk, RoundedCornerShape(Radius.medium))
            .then(if (marked) Modifier.border(1.dp, c.warnInk, RoundedCornerShape(Radius.medium)) else Modifier)
            .semantics { contentDescription = label },
        textStyle = Bento.type.body.copy(color = c.ink), singleLine = !several, minLines = lines, maxLines = if (several) 2 * lines else 1,
        cursorBrush = SolidColor(c.accent), interactionSource = interaction,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        decorationBox = { inner ->
            Row(Modifier.padding(start = Space.m, end = if (trailing == null) Space.m else Space.xs).then(if (several) Modifier.padding(vertical = Space.s) else Modifier),
                verticalAlignment = if (several) Alignment.Top else Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = if (several) Alignment.TopStart else Alignment.CenterStart) {
                    if (value.text.isEmpty()) Text(placeholder.ifEmpty { label }, Modifier.clearAndSetSemantics { }, style = Bento.type.body, color = c.ink3,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                trailing?.invoke()
            }
        })
}

/**
 * A text field that saves as you type. While it has the focus it keeps what is typed: a caller may
 * store something else (nothing for a blank, a shortened text) and the field must not change under
 * the cursor. It shows the stored [value] again when the focus goes, and whenever the value changes
 * while it doesn't have it. [maxLength]: no more can be typed than the caller keeps.
 */
@Composable
fun TextRow(label: String, value: String, help: String? = null, placeholder: String = "", numeric: Boolean = false,
            /** Shown instead of [help], with the field marked, while the text can't be used. */
            error: String? = null, maxLength: Int = Int.MAX_VALUE, onChange: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused && field.text != value) field = TextFieldValue(value, TextRange(value.length)) }
    Column(Modifier.fillMaxWidth()) {
        OptionRow(label) {
            BentoField(field, { typed ->
                val text = typed.text.take(maxLength)
                val changed = text != field.text
                field = if (text == typed.text) typed else typed.copy(text = text, selection = TextRange(text.length))
                if (changed) onChange(text)
            }, label, Modifier.weight(1f).onFocusChanged { focused = it.isFocused }, placeholder = placeholder, marked = error != null,
                keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default)
        }
        (error ?: help)?.let { OptionHelp(it, warn = error != null) }
    }
}

/**
 * A text of several lines that is the item's own and stays on this device (a prompter's script), in [TextRow]'s look: its
 * label above a field as wide as the options, [NOTES_LINES] lines high and scrolling within itself past twice that. Like
 * [TextRow] it keeps what is typed while it has the focus and shows [value] again when the focus goes; no more than [max]
 * characters can be typed. The settings window only: a popup never holds one. Where the text is kept is the caller's
 * business, and never the layout (`Kept.own`).
 */
@Composable
fun NotesRow(label: String, value: String, max: Int, onChange: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused && field.text != value) field = TextFieldValue(value, TextRange(value.length)) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, Modifier.padding(bottom = Space.xs), style = Bento.type.small, color = Bento.colors.ink2,
            maxLines = lines(2), overflow = TextOverflow.Ellipsis)
        BentoField(field, { typed ->
            val text = typed.text.take(max)
            val changed = text != field.text
            field = if (text == typed.text) typed else typed.copy(text = text, selection = TextRange(text.length))
            if (changed) onChange(text)
        }, label, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }, lines = NOTES_LINES)
    }
}

/** How many lines high a [NotesRow]'s field is. */
private const val NOTES_LINES = 6

/**
 * A slider on the window's tokens: a thin line and a round thumb, on whole stops. [onValue] follows the thumb,
 * [onDone] comes when it is let go. Read out as [label] and [said] (its value and unit, "12 dp"), not as a share of the track.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun BentoSlider(value: Float, onValue: (Float) -> Unit, onDone: () -> Unit, range: IntRange, label: String, said: String, modifier: Modifier = Modifier) {
    val c = Bento.colors
    val interaction = remember { MutableInteractionSource() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Slider(value = value, onValueChange = onValue, onValueChangeFinished = onDone,
        valueRange = range.first.toFloat()..range.last.toFloat(), steps = SliderMath.stops(range), interactionSource = interaction,
        thumb = { Box(Modifier.size(SLIDER_THUMB).states(interaction, radius = SLIDER_THUMB / 2, ground = false).background(c.accent, CircleShape)) },
        track = { state ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val done = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(4.dp).drawBehind {
                val round = CornerRadius(size.height / 2)
                val w = size.width * done
                drawRoundRect(c.edge, size = size, cornerRadius = round)
                drawRoundRect(c.accent, Offset(if (rtl) size.width - w else 0f, 0f), Size(w, size.height), round)
            })
        },
        modifier = modifier.semantics { contentDescription = label; stateDescription = said })
}

/** A slider's value at the line's end, as wide as its widest so the line keeps its length while the thumb is carried. Spoken by the slider. */
@Composable
internal fun SliderValue(text: String, widest: String) {
    Box(Modifier.clearAndSetSemantics { }, contentAlignment = Alignment.CenterEnd) {
        Text(widest, style = Bento.type.small.tabular, color = Color.Transparent, maxLines = 1)
        Text(text, style = Bento.type.small.tabular, color = Bento.colors.ink, maxLines = 1)
    }
}

@Composable
fun SliderRow(label: String, value: Int, range: IntRange, format: (Int) -> String = { it.toString() }, onChange: (Int) -> Unit) {
    var v by remember { mutableFloatStateOf(value.toFloat()) }
    LaunchedEffect(value) { v = value.toFloat() }
    OptionRow(label) {
        BentoSlider(v, { v = it }, { onChange(SliderMath.stop(v)) }, range, label, format(SliderMath.stop(v)), Modifier.weight(1f))
        Spacer(Modifier.width(Space.m))
        SliderValue(format(SliderMath.stop(v)), listOf(format(range.first), format(range.last)).maxBy { it.length })
    }
}

/**
 * The words of a [ServiceKey], as text resources: each service's own ("AirLabs key", "Remove Finnhub
 * key"). [consent]: what is sent and to whom, before a key is saved; [help]: under the field.
 */
class KeyWords(
    @StringRes val label: Int, @StringRes val consent: Int, @StringRes val getKey: Int, @StringRes val save: Int, @StringRes val help: Int,
    @StringRes val saved: Int, @StringRes val replace: Int, @StringRes val remove: Int, @StringRes val removeHelp: Int,
)

/**
 * A service's key in an item's settings (Flight's, Stocks'), where a long paste is easier than in a
 * drop-down. Without one: what is sent and to whom, where a key is got ([onGetKey], in the browser),
 * and a field that shows dots. With one ([keyed]): that it is saved, what [more] adds (Flight: the
 * lookups left), and the two ways to change that. A saved key is never shown again, here or anywhere.
 * [onSave] says whether the key was taken. [heading]: where the key is one part of the item's
 * settings (Stocks'), its name over it. [refused]: the service's words for a key it doesn't take, as a
 * notice whose one button replaces it.
 */
@Composable
fun ServiceKey(keyed: Boolean, words: KeyWords, onSave: (String) -> Boolean, onRemove: () -> Unit, onGetKey: () -> Unit,
               heading: String? = null, refused: String? = null, more: @Composable () -> Unit = {}) {
    var replacing by remember { mutableStateOf(false) }
    val c = Bento.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (heading != null) Text(heading, Modifier.padding(bottom = Space.xs), style = Bento.type.strong, color = c.ink)
        if (keyed && !replacing) {
            Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                SymIcon(Sym.KEY, size = 18.sp, color = c.ink2)
                Spacer(Modifier.width(Space.s))
                Text(stringResource(words.saved), style = Bento.type.strong, color = c.ink)
            }
            more()
            if (refused != null) NoticeLine(refused, Modifier.padding(top = Space.s)) {
                BentoButton(stringResource(words.replace), { replacing = true }, kind = ButtonKind.WARN)
            }
            Row(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                if (refused == null) BentoButton(stringResource(words.replace), { replacing = true })
                // At once, with no dialog and no Undo: an Undo would mean keeping the key after it was removed.
                BentoButton(stringResource(words.remove), onRemove, kind = ButtonKind.TEXT)
            }
            Text(stringResource(words.removeHelp), Modifier.padding(top = Space.xs), style = Bento.type.help, color = c.ink2)
        } else {
            if (!keyed) {
                Text(stringResource(words.consent), style = Bento.type.small, color = c.ink)
                val getKey = stringResource(words.getKey)
                val opens = stringResource(R.string.common_opens_browser, getKey)
                BentoButton(getKey, onGetKey, Modifier.offset(x = -Space.m).semantics { contentDescription = opens }, kind = ButtonKind.TEXT, icon = Sym.OPEN_IN_NEW)
            }
            KeyField(stringResource(words.label), stringResource(words.save), onSave = { if (onSave(it)) replacing = false },
                onCancel = if (replacing) { { replacing = false } } else null)
            Text(stringResource(words.help), Modifier.padding(top = Space.xs), style = Bento.type.help, color = c.ink2)
        }
    }
}

/**
 * Where the key is pasted: [label] names it, [save] is the button. A password field: it shows dots,
 * and no keyboard learns it. What is typed is held only while it is typed (never with the window's
 * saved state), and is gone from here the moment it is saved.
 */
@Composable
private fun KeyField(label: String, save: String, onSave: (String) -> Unit, onCancel: (() -> Unit)?) {
    var typed by remember { mutableStateOf(TextFieldValue("")) }
    fun saveIt() {
        if (typed.text.isBlank()) return
        onSave(typed.text)
        typed = TextFieldValue("")
    }
    Column(Modifier.fillMaxWidth()) {
        BentoField(typed, { typed = if (it.text.length > 200) it.copy(text = it.text.take(200), selection = TextRange(200)) else it }, label,
            Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                // A hardware keyboard's Enter saves too. Down acts and up is swallowed, so the keyboard's own action can't act a second time.
                if (e.key == Key.Enter || e.key == Key.NumPadEnter) { if (e.type == KeyEventType.KeyDown) saveIt(); true } else false
            }, secret = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { saveIt() }))
        Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            BentoButton(save, { saveIt() }, enabled = typed.text.isNotBlank())
            if (onCancel != null) BentoButton(stringResource(R.string.common_cancel), onCancel, kind = ButtonKind.TEXT)
        }
    }
}

// The settings window's controls, on its tokens (Theme.kt). The rows above are an item's own options in the panel, built
// from the same pieces; the bar's popups have their own (MenuKit), in Material's shapes on the device's colors.

/** How much of a control's veil lies over it under the pointer and while it is pressed ([states]). */
internal const val VEIL_HOVER = 0.06f
internal const val VEIL_PRESSED = 0.10f

/**
 * How much of the danger colour is the red button's ground over the card it stands on, at rest: the trash's with a row
 * over it. Under the pointer and pressed the control's veil, in the same colour, lies on that.
 */
internal const val DANGER_VEIL = 0.10f

/**
 * What a control that takes a click shows of itself: a faint veil under the pointer and a press, and the focus ring
 * (2 dp of accent, 2 dp off the control) while it has the keyboard. [inset] takes that much off the top and bottom of
 * what is drawn, for a control whose target is taller than it looks (a tab). Nothing opens or changes on hover.
 */
@Composable
fun Modifier.states(interaction: InteractionSource, radius: Dp = Radius.medium, inset: Dp = 0.dp, ground: Boolean = true,
                    veil: Color = Bento.colors.ink): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val ring = Bento.colors.accent
    return drawWithContent {
        val top = inset.toPx()
        val r = radius.toPx()
        drawContent()
        val depth = if (!ground) 0f else if (pressed) VEIL_PRESSED else if (hovered) VEIL_HOVER else 0f
        if (depth > 0f) drawRoundRect(veil.copy(alpha = depth), Offset(0f, top), Size(size.width, size.height - 2 * top), CornerRadius(r))
        if (focused) {
            val w = 2.dp.toPx()
            val out = 2.dp.toPx() + w / 2
            drawRoundRect(ring, Offset(-out, top - out), Size(size.width + 2 * out, size.height - 2 * top + 2 * out), CornerRadius(r + out), style = Stroke(w))
        }
    }
}

/** A switch as it looks: the track, and the thumb that travels. What is around it takes the click (`toggleable`), as with Material's. */
@Composable
fun SwitchMark(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Bento.colors
    val at by animateFloatAsState(if (checked) 1f else 0f, Motion.spec(tween(Motion.LIFT)), label = "switch")
    Canvas(modifier.size(40.dp, 24.dp).alpha(if (enabled) 1f else DIMMED)) {
        drawRoundRect(lerp(c.edge, c.accent, at), cornerRadius = CornerRadius(size.height / 2))
        drawCircle(lerp(c.panel, c.onPrimary, at), 9.dp.toPx(), Offset(12.dp.toPx() + at * 16.dp.toPx(), size.height / 2))
    }
}

/**
 * Filled: the one such button on a screen. Tonal: every other command. Text: a quiet one in a line (Undo). Quiet: one
 * that stands back beside a row's own words (a permission's way back on Setup). Danger: what deletes (Remove from the
 * bar, Discard, a key taken off the device): a tonal button's shape and height on a ground of the danger colour over
 * the card ([DANGER_VEIL]), its words and glyph in that colour. Warn: the one button of an amber notice, in the
 * notice's own ink.
 */
enum class ButtonKind { FILLED, TONAL, TEXT, QUIET, DANGER, WARN }

@Composable
fun BentoButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.TONAL, icon: String? = null, enabled: Boolean = true) {
    val c = Bento.colors
    val interaction = remember { MutableInteractionSource() }
    val ground = when (kind) {
        ButtonKind.FILLED -> c.primary
        ButtonKind.TONAL -> c.sunk
        ButtonKind.WARN -> c.warnInk.copy(alpha = 0.14f)
        ButtonKind.DANGER -> c.danger.copy(alpha = DANGER_VEIL)
        else -> Color.Transparent
    }
    val ink = when (kind) {
        ButtonKind.FILLED -> c.onPrimary
        ButtonKind.TONAL -> c.ink
        ButtonKind.TEXT -> c.accent
        ButtonKind.QUIET -> c.ink2
        ButtonKind.DANGER -> c.danger
        ButtonKind.WARN -> c.warnInk
    }
    val pad = when (kind) { ButtonKind.FILLED -> 18.dp; ButtonKind.TONAL, ButtonKind.WARN, ButtonKind.DANGER -> Space.l; else -> Space.m }
    Row(modifier.heightIn(min = Target.control).alpha(if (enabled) 1f else DIMMED)
        .states(interaction, veil = ink)
        .background(ground, RoundedCornerShape(Radius.medium))
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = pad), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            SymIcon(icon, size = 18.sp, color = ink)
            Spacer(Modifier.width(Space.s))
        }
        Text(text, style = Bento.type.strong, color = ink, maxLines = lines())
    }
}

/** A square tonal button with one glyph (Order's arrows); [description] is its spoken name. Not [filled], it has no ground of its own (an area's +). */
@Composable
fun BentoIconButton(icon: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, filled: Boolean = true) {
    val c = Bento.colors
    val interaction = remember { MutableInteractionSource() }
    Box(modifier.size(Target.control).alpha(if (enabled) 1f else DIMMED)
        .states(interaction)
        .background(if (filled) c.sunk else Color.Transparent, RoundedCornerShape(Radius.medium))
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        SymIcon(icon, size = 18.sp, color = if (filled) c.ink else c.ink2, contentDescription = description)
    }
}

/** A quiet button that opens a short menu of [choices] and shows the [chosen] one (an item's Area, with one area in use). */
@Composable
fun <T> MenuButton(choices: List<Pair<T, String>>, chosen: T, onChoose: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Bento.colors
    val interaction = remember { MutableInteractionSource() }
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.width(132.dp.ofText()).heightIn(min = Target.control)
            .states(interaction)
            .background(c.sunk, RoundedCornerShape(Radius.medium))
            .clickable(interaction, indication = null, role = Role.DropdownList) { open = true }
            .padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(choices.firstOrNull { it.first == chosen }?.second.orEmpty(), Modifier.weight(1f), style = Bento.type.strong, color = c.ink, maxLines = lines())
            SymIcon(Sym.EXPAND_MORE, size = 18.sp, color = c.ink2)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.raised) {
            choices.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label, style = if (value == chosen) Bento.type.strong else Bento.type.body, color = c.ink) },
                    onClick = { open = false; if (value != chosen) onChoose(value) })
            }
        }
    }
}

/** One part of a [Segment]. A part that can't be chosen right now is dimmed; its reason is said in words beside the segment. */
/** [spoken] is read out in the label's place, for a label with a sign in it (‹). */
class SegmentPart(val label: String, val icon: String? = null, val enabled: Boolean = true, val spoken: String? = null)

/**
 * A segmented choice: parts on a sunk track, the chosen one raised and heavier (never by color alone). The raised part
 * travels to the chosen one. [role] is a tab for the panel switch and a radio button for a choice of values; [chosen]
 * outside the parts raises none. The parts are [equal] in width, or each as wide as its words with the room left over
 * shared out evenly, so a long choice beside a short one is not cut short. From right to left the parts are mirrored
 * like any row, unless they are [sides] of the screen (Left, Middle, Right), which stand where they are in the bar.
 */
@Composable
fun Segment(parts: List<SegmentPart>, chosen: Int, onChoose: (Int) -> Unit, modifier: Modifier = Modifier,
            role: Role = Role.RadioButton, track: Color = Bento.colors.sunk, equal: Boolean = true, sides: Boolean = false) {
    val c = Bento.colors
    val mirrored = !sides && LocalLayoutDirection.current == LayoutDirection.Rtl
    val type = Bento.type
    val at by animateFloatAsState(chosen.toFloat(), Motion.spec(tween(Motion.NEIGHBOURS)), label = "segment")
    // Where each part was placed and how wide, for the raised part that is drawn behind them.
    var spans by remember { mutableStateOf(listOf<Pair<Int, Int>>()) }
    Layout({
        parts.forEachIndexed { i, part ->
            val interaction = remember { MutableInteractionSource() }
            val up = i == chosen
            val ink = if (part.enabled) c.ink else c.ink3
            Row(Modifier.heightIn(min = SEGMENT_PART)
                .states(interaction, radius = Radius.small, ground = !up)
                .selectable(up, interaction, indication = null, enabled = part.enabled, role = role) { onChoose(i) }
                .then(if (part.spoken != null) Modifier.clearAndSetSemantics { contentDescription = part.spoken } else Modifier)
                .padding(horizontal = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (part.icon != null) {
                    SymIcon(part.icon, size = 15.sp, color = ink)
                    Spacer(Modifier.width(6.dp))
                }
                Text(part.label, style = type.small.copy(fontWeight = if (!part.enabled) FontWeight.Normal else if (up) FontWeight.SemiBold else FontWeight.Medium),
                    color = ink, maxLines = lines(), overflow = TextOverflow.Ellipsis)
            }
        }
    }, modifier.heightIn(min = Target.control).background(track, RoundedCornerShape(Radius.medium)).padding(3.dp).selectableGroup().drawBehind {
        if (chosen !in parts.indices || spans.size != parts.size) return@drawBehind
        val from = spans[at.toInt().coerceIn(parts.indices)]
        val to = spans[kotlin.math.ceil(at).toInt().coerceIn(parts.indices)]
        val f = at - at.toInt()
        val w = from.second + (to.second - from.second) * f
        // The parts' places are counted from the start: from right to left that is the right edge.
        val x = (from.first + (to.first - from.first) * f).let { if (mirrored) size.width - it - w else it }
        val r = CornerRadius(Radius.small.toPx())
        // The contact shadow that lifts it off the track.
        drawRoundRect(Color.Black.copy(alpha = if (c.dark) 0.40f else 0.14f), Offset(x, 1.dp.toPx()), Size(w, size.height), r)
        drawRoundRect(c.raised, Offset(x, 0f), Size(w, size.height), r)
    }) { measurables, cs ->
        val n = measurables.size
        val gap = SEGMENT_GAP.roundToPx()
        val wants = measurables.map { it.maxIntrinsicWidth(cs.maxHeight) }
        val room = if (cs.hasBoundedWidth) (cs.maxWidth - gap * (n - 1)).coerceAtLeast(0) else wants.sum()
        val widths = when {
            n == 0 -> emptyList()
            equal -> List(n) { room / n }
            wants.sum() <= room -> wants.map { it + (room - wants.sum()) / n }
            else -> wants.map { (it.toLong() * room / wants.sum()).toInt() }
        }.toMutableList()
        // What rounding left over goes to the last part, so the parts end where the track does.
        if (n > 0) widths[n - 1] += room - widths.sum()
        // As tall as its tallest part needs (words on two lines at a large text size), and every part as tall as that.
        val height = (measurables.indices.maxOfOrNull { measurables[it].minIntrinsicHeight(widths[it].coerceAtLeast(0)) } ?: 0).coerceIn(cs.minHeight, cs.maxHeight)
        val placed = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i].coerceAtLeast(0), height)) }
        var x = 0
        val now = placed.map { p -> (x to p.width).also { x += p.width + gap } }
        if (now != spans) spans = now
        layout(room + gap * (n - 1).coerceAtLeast(0), height) {
            placed.forEachIndexed { i, p -> if (sides) p.place(now[i].first, (height - p.height) / 2) else p.placeRelative(now[i].first, (height - p.height) / 2) }
        }
    }
}

/**
 * A filter's field: sunk, a search glyph, [label] as its placeholder and spoken name, and at its end the keycaps of the
 * keys that go with it, as its page binds them: [find], which brings the keyboard here (Ctrl F), until it has it, and
 * [clear] (Esc) only while Esc has something to do: the field holds something to empty, or it has the keyboard and its
 * page has something for Esc to end from here ([ends]: an item to let go). A cap never stands for a key that would do
 * nothing. Esc is the field's own while there is something to empty, unless its page has something nearer for Esc to
 * end ([held]: a drag), which the page's own handler then gets.
 */
@Composable
fun FilterField(text: String, onText: (String) -> Unit, label: String, modifier: Modifier = Modifier, focus: FocusRequester = remember { FocusRequester() },
    find: List<String> = emptyList(), clear: List<String> = emptyList(), held: Boolean = false, ends: Boolean = false) {
    val c = Bento.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // The field's own value: text put in from outside (a letter typed elsewhere in the window) leaves the caret at its end.
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (value.text != text) value = TextFieldValue(text, TextRange(text.length))
    BasicTextField(value, { value = it; if (it.text != text) onText(it.text) },
        modifier.fillMaxWidth().heightIn(min = Target.control)
            .states(interaction, ground = false)
            .background(c.sunk, RoundedCornerShape(Radius.medium))
            .focusRequester(focus)
            .onPreviewKeyEvent { !held && it.type == KeyEventType.KeyDown && it.key == Key.Escape && text.isNotEmpty() && run { onText(""); true } }
            .semantics { contentDescription = label },
        textStyle = Bento.type.body.copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.accent), interactionSource = interaction,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        decorationBox = { inner ->
            Row(Modifier.padding(start = Space.m, end = Space.s), verticalAlignment = Alignment.CenterVertically) {
                SymIcon(Sym.SEARCH, size = 16.sp, color = c.ink3)
                Spacer(Modifier.width(Space.s))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text(label, style = Bento.type.body, color = c.ink3, maxLines = lines(), overflow = TextOverflow.Ellipsis)
                    inner()
                }
                Spacer(Modifier.width(Space.s))
                (if (text.isNotEmpty() || focused && ends) clear else if (focused) emptyList() else find).forEachIndexed { i, cap ->
                    if (i > 0) Spacer(Modifier.width(3.dp))
                    Keycap(cap)
                }
            }
        })
}

/** A key's name as a cap, in a line of help or at a field's end (where the field's ground is the cap's own). */
@Composable
fun Keycap(text: String, modifier: Modifier = Modifier) {
    val c = Bento.colors
    val cap = modifier.background(c.sunk, RoundedCornerShape(Radius.small)).padding(horizontal = 5.dp)
    val arrows = arrowSymbols(text)
    val style = Bento.type.help.copy(fontWeight = FontWeight.Medium)
    if (arrows == null) { Text(text, cap, style = style, color = c.ink3, maxLines = 1); return }
    // A cap of arrows is as high as a cap of words, says the same to a screen reader, and points the same way in every language.
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val line = remember(style, density) { with(density) { measurer.measure("", style).size.height.toDp() } }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(cap.height(line).semantics { this.text = AnnotatedString(text) }, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            arrows.forEach { SymIcon(it, size = ARROW_SIZE, color = c.ink3) }
        }
    }
}

/**
 * A keycap's arrows as the window's own symbols, or null for a cap of words. A text font draws its arrows as small as
 * its small letters, and not every font has them; the symbols are the ones the buttons beside them wear.
 */
internal fun arrowSymbols(text: String): List<String>? =
    text.split(' ').filter { it.isNotEmpty() }.map { ARROWS[it] ?: return null }.takeIf { it.isNotEmpty() }

private val ARROWS = mapOf("\u2190" to Sym.ARROW_BACK, "\u2192" to Sym.ARROW_FORWARD, "\u2191" to Sym.ARROW_UPWARD, "\u2193" to Sym.ARROW_DOWNWARD)
private val ARROW_SIZE = 14.sp

/**
 * Where the thumb of something that scrolls stands in a [view] of that many px, with [more] px that don't fit and [at]
 * px scrolled: from its top to its end. Null where everything fits. As long as the share that shows, never shorter than [least].
 */
internal fun thumbOf(view: Float, more: Int, at: Int, least: Float): ClosedFloatingPointRange<Float>? {
    if (view <= 0f || more <= 0 || more == Int.MAX_VALUE) return null
    val length = (view * view / (view + more)).coerceIn(minOf(least, view), view)
    val top = (view - length) * at.coerceIn(0, more) / more
    return top..(top + length)
}

/**
 * A thin thumb at the end edge of something that scrolls, drawn while there is more than fits. It takes no pointer and
 * a screen reader hears nothing of it. It goes before `verticalScroll`, so it stands still while the content moves.
 */
fun Modifier.scrollThumb(state: ScrollState, color: Color): Modifier = drawWithContent {
    drawContent()
    val inset = THUMB_INSET.toPx()
    val thumb = thumbOf(size.height - 2 * inset, state.maxValue, state.value, THUMB_LEAST.toPx()) ?: return@drawWithContent
    val width = THUMB_WIDTH.toPx()
    val x = if (layoutDirection == LayoutDirection.Rtl) THUMB_GAP.toPx() else size.width - THUMB_GAP.toPx() - width
    drawRoundRect(color, Offset(x, inset + thumb.start), Size(width, thumb.endInclusive - thumb.start), CornerRadius(width / 2))
}

private val THUMB_WIDTH = 4.dp
private val THUMB_GAP = 3.dp
private val THUMB_LEAST = 24.dp
/** Clear of a card's round corners. */
private val THUMB_INSET = Space.s

private val SEGMENT_GAP = 2.dp
private val SEGMENT_PART = 34.dp
internal val OPTION_LABEL = 96.dp
private val SLIDER_THUMB = 16.dp
private const val DIMMED = 0.45f
