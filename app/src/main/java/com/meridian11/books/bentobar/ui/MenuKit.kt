package com.meridian11.books.bentobar.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.Failure
import com.meridian11.books.bentobar.util.Dates
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon
import kotlinx.coroutines.delay
import java.time.ZoneId

/** The frame every drop-down menu shares: a header with icon and title, then content. */
@Composable
fun MenuCard(
    icon: String,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** A drawable instead of the [icon] symbol (BentoBar's own mark). */
    iconRes: Int? = null,
    /** The subtitle's color, where it says something out of the ordinary (a time being planned). */
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** The head's symbol on the card itself, in the text's color, with no tinted box around it (the right-click menu). */
    plain: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Each child is a block that drops into place as the popup opens: the header first, then the content's own children.
    DropColumn(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.popIn(0f, 0.85f).size(30.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (plain) Color.Transparent else MaterialTheme.colorScheme.primaryContainer).testTag("menu-symbol"),
                contentAlignment = Alignment.Center,
            ) {
                if (plain) SymIcon(icon, size = 20.sp)
                else if (iconRes != null) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(iconRes), null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                else SymIcon(icon, size = 17.sp, filled = true, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fadeIn(20f).semantics { heading() })
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fadeIn(36f))
            }
            if (trailing != null) Box(Modifier.fadeIn(48f)) { trailing() }
        }
        content()
    }
}

/**
 * "Label ........ value", with tabular figures where the value has figures. In this font they also
 * widen the spaces, which pulled a value of words alone ("Hot, slowing a little") apart.
 */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).fadeIn(0f))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = if (value.any(Char::isDigit)) "tnum" else null),
            fontWeight = FontWeight.Medium, color = valueColor, modifier = Modifier.fadeIn(24f))
    }
}

/** A short line of small, quiet text in a menu (an explanation or a status). */
@Composable
fun MenuNote(text: String) = Text(text, style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))

/** "updated 2:40 PM", or what the last try came to beside that time: the end of an online item's menu. In the device's own time, as the system writes it. */
@Composable
fun updatedNote(fetchedAt: Long, failure: Failure?): String {
    val clock = Dates.format(Dates.timeSkeleton(DateFormat.is24HourFormat(LocalContext.current)), fetchedAt, ZoneId.systemDefault())
    return stringResource(when (failure) {
        null -> R.string.common_updated
        Failure.OFFLINE -> R.string.common_updated_offline
        else -> R.string.common_updated_no_answer
    }, clock)
}

/** The line under a search field ("Searching…", "Nothing found"): read out when it changes, without taking the focus. */
@Composable
fun SearchStatus(text: String) = Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { MenuNote(text) }

@Composable
fun MenuDivider() = HorizontalDivider(
    // On glass a tint of the text colour rather than an opaque line (visual.md); it arrives with the block below it.
    Modifier.layoutId(JOINS_BELOW).padding(vertical = 8.dp),
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSystemInDarkTheme()) 0.16f else 0.12f),
)

/** The accent as a menu draws it. On glass halfway to the text's colour: the accent alone fell to 2:1 over a dark window behind (visual.md §5). */
@Composable
private fun menuAccent(): Color = if (LocalMenuGlass.current?.blur == true) androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.onSurface, 0.5f) else MaterialTheme.colorScheme.primary

@Composable
fun SectionLabel(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = menuAccent(),
    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp).semantics { heading() })

/** The name of one choice in a menu, over its [MenuSegment]: small, as written, in the quieter ink. */
@Composable
fun ChoiceLabel(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp))

/**
 * A choice of a few values in a menu, in the menu's own colours: parts on one sunk track, the chosen one raised and
 * heavier (never by color alone). Each part is as wide as its words, with the room left over shared out evenly, so a
 * long choice beside two short ones is not cut short.
 */
@Composable
fun MenuSegment(labels: List<String>, selected: Int, onChoose: (Int) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val raised = if (isSystemInDarkTheme()) ink.copy(alpha = 0.20f) else Color.White.copy(alpha = if (LocalMenuGlass.current?.blur == true) 0.86f else 1f)
    val shape = RoundedCornerShape(7.dp)
    androidx.compose.ui.layout.Layout({
        labels.forEachIndexed { i, label ->
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            val focused by source.collectIsFocusedAsState()
            val up = i == selected
            Box(Modifier.heightIn(min = 34.dp).focusRing(focused, shape).clip(shape)
                .background(if (up) raised else if (hovered || focused) ink.copy(alpha = 0.08f) else Color.Transparent)
                .hoverable(source)
                .selectable(selected = up, interactionSource = source, indication = null, role = Role.RadioButton) { onChoose(i) }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (up) FontWeight.SemiBold else FontWeight.Medium),
                    color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }, Modifier.fillMaxWidth().background(ink.copy(alpha = if (isSystemInDarkTheme()) 0.10f else 0.07f), RoundedCornerShape(10.dp)).padding(3.dp).selectableGroup()) { measurables, cs ->
        val n = measurables.size
        val gap = 2.dp.roundToPx()
        val wants = measurables.map { it.maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity) }
        val room = if (cs.hasBoundedWidth) (cs.maxWidth - gap * (n - 1)).coerceAtLeast(0) else wants.sum()
        val widths = (if (wants.sum() <= room) wants.map { it + (room - wants.sum()) / n.coerceAtLeast(1) }
            else wants.map { (it.toLong() * room / wants.sum()).toInt() }).toMutableList()
        // What rounding left over goes to the last part, so the parts end where the track does.
        if (n > 0) widths[n - 1] += room - widths.sum()
        val height = measurables.mapIndexed { i, m -> m.minIntrinsicHeight(widths[i]) }.maxOrNull() ?: 0
        val placed = measurables.mapIndexed { i, m -> m.measure(androidx.compose.ui.unit.Constraints.fixed(widths[i].coerceAtLeast(0), height)) }
        layout(room + gap * (n - 1).coerceAtLeast(0), height) {
            var x = 0
            placed.forEach { p -> p.placeRelative(x, 0); x += p.width + gap }
        }
    }
}

/** A small line chart of recent values, filled underneath. Two series share one scale. */
@Composable
fun Sparkline(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    second: List<Double>? = null,
    secondColor: Color = MaterialTheme.colorScheme.tertiary,
    max: Double? = null,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    // Its well fades in, then the line draws in from its oldest reading to its newest, its fill clipped to the line's
    // head (§7.2); from its first reading, not the well's edge: a short history would otherwise pop in at the end.
    val part = rememberPart(30f)
    Canvas(modifier.fadeIn(0f).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(track).part(part)) {
        val top = max ?: maxOf(values.maxOrNull() ?: 0.0, second?.maxOrNull() ?: 0.0, 1e-9)
        val drawn = if (part == null) 1f else MenuMotion.draw(part.ms())
        if (drawn <= 0f) return@Canvas
        val longest = maxOf(values.size, second?.size ?: 0)
        val first = size.width - size.width / (60 - 1).coerceAtLeast(longest - 1) * (longest - 1).coerceAtLeast(0)
        val head = if (drawn >= 1f) size.width else first + (size.width - first) * drawn
        fun series(vs: List<Double>, c: Color, fill: Boolean) {
            if (vs.size < 2) return
            val stepX = size.width / (60 - 1).coerceAtLeast(vs.size - 1)
            val startX = size.width - stepX * (vs.size - 1)
            val path = Path()
            vs.forEachIndexed { i, v ->
                val x = startX + i * stepX
                val y = size.height - 4f - ((v / top).coerceIn(0.0, 1.0) * (size.height - 10f)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (fill) {
                val area = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(startX, size.height); close() }
                drawPath(area, Brush.verticalGradient(listOf(c.copy(alpha = 0.35f), c.copy(alpha = 0.02f))))
            }
            drawPath(path, c, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        clipRect(right = head) {
            series(values, color, true)
            if (second != null) series(second, secondColor, false)
        }
        drawLine(color.copy(alpha = 0.15f), Offset(0f, size.height - 1), Offset(size.width, size.height - 1))
    }
}

/**
 * A full-width row button, like a desktop menu entry. [sub]: a second, quieter line under the label
 * (a place's region); the row is then at least 48 dp high. [image]: a picture, 20 dp, in the
 * symbol's place (an app's icon). [trailing]: one control of its own at the row's end (a × to remove
 * a city, a player's play button), which takes its own clicks. [checked]: the entry is an on/off rule, and says so to a screen
 * reader; on, it has a tick at its end, which is for the eye and not read out as well. Null: a plain entry.
 * Without an [icon] (and no [image]) the line is bare: its words start where the line does.
 */
@Composable
fun MenuEntry(icon: String?, label: String, detail: String? = null, enabled: Boolean = true, modifier: Modifier = Modifier,
              sub: String? = null, image: ImageBitmap? = null, trailing: (@Composable () -> Unit)? = null, checked: Boolean? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier.fillMaxWidth().heightIn(min = if (sub != null) 48.dp else 40.dp).focusRing(focused, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
            .background(if ((hovered || focused) && enabled) MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (LocalMenuGlass.current?.blur == true) 0.12f else 0.10f) else Color.Transparent)
            .hoverable(source)
            // An Enter that is being held when the focus arrives here (a search's results come in and the
            // first takes the focus) is not this entry's: its repeats are swallowed, so its release presses nothing.
            .onPreviewKeyEvent { e -> (e.key == Key.Enter || e.key == Key.NumPadEnter) && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 }
            .then(if (checked == null) Modifier.clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
                else Modifier.toggleable(value = checked, enabled = enabled, interactionSource = source, indication = null, role = Role.Checkbox, onValueChange = { onClick() }))
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        if (image != null) Image(image, null, Modifier.popIn(0f, 0.8f).size(20.dp))
        else if (icon != null) Box(Modifier.popIn(0f, 0.8f)) { SymIcon(icon, size = 18.sp, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else c) }
        if (image != null || icon != null) Spacer(Modifier.width(10.dp))
        if (sub == null) Text(label, style = MaterialTheme.typography.bodyMedium, color = c, modifier = Modifier.weight(1f).fadeIn(20f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        else Column(Modifier.weight(1f).fadeIn(20f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // At most half the row: a long detail would otherwise squeeze the label away; a short one takes only its own width.
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.layout { m, c -> val p = m.measure(c.copy(maxWidth = c.maxWidth / 2)); layout(p.width, p.height) { p.place(0, 0) } }
                .fadeIn(36f))
        if (checked == true) SymIcon(Sym.CHECK, Modifier.padding(start = 8.dp).fadeIn(36f), size = 18.sp, color = menuAccent())
        if (trailing != null) Box(Modifier.fadeIn(36f)) { trailing() }
    }
}

/**
 * A small icon button in a menu: ×, ‹, ›, play. It looks 28 dp, and takes clicks and the keyboard's
 * focus over the 48 dp minimum. Not [enabled], it is dimmed and takes neither.
 */
@Composable
fun SmallIconButton(sym: String, label: String, color: Color = LocalContentColor.current, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(Modifier.minimumInteractiveComponentSize().clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current,
        enabled = enabled, role = Role.Button, onClick = onClick)
        .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(28.dp).focusRing(focused, CircleShape).clip(CircleShape), contentAlignment = Alignment.Center) {
            SymIcon(sym, size = 18.sp, contentDescription = label, color = if (enabled) color else color.copy(alpha = 0.38f))
        }
    }
}

/**
 * Previous, play or pause, next: the media buttons of the Sound menu and of Now playing. What the
 * player doesn't offer is dimmed, not hidden, so the three keep their places. [arrangement]: Sound
 * leaves them at the start, Now playing centers them.
 */
@Composable
fun MediaButtons(playing: Boolean, canPrevious: Boolean = true, canPlayPause: Boolean = true, canNext: Boolean = true,
                 arrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
                 /** In a popup, the buttons pop in one after another from this offset in their block (§7.2). */
                 entranceMs: Float = 0f,
                 onPrevious: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement) {
        FilledTonalIconButton(onClick = onPrevious, enabled = canPrevious, modifier = Modifier.popIn(entranceMs, 0.8f)) {
            SymIcon(Sym.SKIP_PREVIOUS, size = 22.sp, contentDescription = stringResource(R.string.sound_previous))
        }
        FilledTonalIconButton(onClick = onPlayPause, enabled = canPlayPause, modifier = Modifier.popIn(entranceMs + 10f, 0.8f)) {
            SymIcon(if (playing) Sym.PAUSE else Sym.PLAY_ARROW, size = 22.sp,
                contentDescription = stringResource(if (playing) R.string.common_pause else R.string.sound_play))
        }
        FilledTonalIconButton(onClick = onNext, enabled = canNext, modifier = Modifier.popIn(entranceMs + 20f, 0.8f)) {
            SymIcon(Sym.SKIP_NEXT, size = 22.sp, contentDescription = stringResource(R.string.sound_next))
        }
    }
}

/**
 * A field in a menu to find something with: a city, a place, a flight number. It takes the focus
 * when it appears, so the menu opens ready to type. Two kinds:
 *
 * With [submit] (a button's label: "Search", "Track"): typing only tells [onChange]; Enter and the
 * button call [onEnter], while [canSubmit] holds. Nothing is looked up before that.
 *
 * Without [submit]: results follow the typing (the caller draws them from [onChange]), Enter
 * ([onEnter]) takes the first, and with [onClose] a × at the field's end closes the search.
 *
 * [initial] is the text it starts with, selected with [selectAll] so that typing replaces it.
 * [error]: the text can't be used; the field is marked and the words stand under it. What else stands
 * under the field (a status, the results) is the caller's. [focus]: to bring the focus back to the
 * field from a result.
 */
@Composable
fun SearchField(label: String, placeholder: String = "", initial: String = "", submit: String? = null, selectAll: Boolean = false,
                error: String? = null, canSubmit: Boolean = true, keyboard: KeyboardType = KeyboardType.Text,
                focus: FocusRequester = remember { FocusRequester() }, onClose: (() -> Unit)? = null,
                onChange: (String) -> Unit, onEnter: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(initial, if (selectAll) TextRange(0, initial.length) else TextRange(initial.length))) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun enter() { if (canSubmit) onEnter(field.text) }
    // The field itself has the focus, not the × inside it: Enter on the × is the ×'s.
    var own by remember { mutableStateOf(false) }
    val keys = Modifier.focusRequester(focus).onFocusChanged { own = it.isFocused }.onPreviewKeyEvent { e ->
        // A hardware keyboard's Enter doesn't always arrive as the keyboard's action. Down acts and
        // up is swallowed, so that action can't act a second time; nor does a key held down, whose
        // repeats would search again and again and then pick the first result that came.
        if (own && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
            if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) enter()
            true
        } else false
    }
    val change = { next: TextFieldValue -> val changed = next.text != field.text; field = next; if (changed) onChange(next.text) }
    val options = KeyboardOptions(keyboardType = keyboard, imeAction = if (submit != null) ImeAction.Search else ImeAction.Done)
    val actions = KeyboardActions(onGo = { enter() }, onDone = { enter() }, onSearch = { enter() })
    // In the settings window: the window's own sunk field and button. In a popup: Material's, on the device's colors.
    if (LocalSettingsWindow.current) {
        Column(Modifier.fillMaxWidth().padding(vertical = Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                BentoField(field, change, label, Modifier.weight(1f).then(keys), placeholder = placeholder, marked = error != null,
                    keyboardOptions = options, keyboardActions = actions,
                    trailing = if (submit == null && onClose != null) { { BentoIconButton(Sym.CLOSE, stringResource(R.string.common_close_search), onClose) } } else null)
                if (submit != null) BentoButton(submit, { enter() }, kind = ButtonKind.FILLED, enabled = canSubmit)
            }
            if (error != null) Text(error, Modifier.padding(top = Space.xs), style = Bento.type.help, color = Bento.colors.warnInk)
        }
        return
    }
    // Room under the field too: a result's focus ring otherwise touches the field's border.
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = field,
                onValueChange = change,
                label = { Text(label) },
                placeholder = if (placeholder.isEmpty()) null else { { Text(placeholder) } },
                trailingIcon = if (submit == null && onClose != null) { { SmallIconButton(Sym.CLOSE, stringResource(R.string.common_close_search), onClick = onClose) } } else null,
                singleLine = true,
                isError = error != null,
                keyboardOptions = options,
                keyboardActions = actions,
                modifier = Modifier.weight(1f).then(keys),
            )
            if (submit != null) FilledTonalButton(onClick = { enter() }, enabled = canSubmit) { Text(submit, maxLines = 1) }
        }
        if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp))
    }
}

/**
 * The card of an online item whose service is switched off: what it is ([title]) and why ([subtitle]), the service's own
 * words on what going online means ([consent]), and the way back on ([turnOn]). Each service's not-set-up card stays its own.
 */
@Composable
fun OffGate(icon: String, title: String, subtitle: String, consent: String, turnOn: String, onTurnOn: () -> Unit) {
    MenuCard(icon, title, subtitle) {
        MenuNote(consent)
        MenuEntry(Sym.TOGGLE_ON, turnOn, onClick = onTurnOn)
    }
}

/** The two focus places of a search with results: the field, and the first result that can be taken. */
class SearchFocus(val field: FocusRequester, val first: FocusRequester) {
    fun toField() { runCatching { field.requestFocus() } }
    fun toFirst() { runCatching { first.requestFocus() } }
}

/**
 * A search field and what it found, with the keyboard's way between them, made once: when results arrive ([hasResults],
 * looked at each time [arrival] changes) the focus moves to the first that can be taken, so Enter, Enter is search and take; Down
 * from the field goes there and Up from there comes back. [field] draws the field with [SearchFocus.field]; [results] draws the
 * rest, passing `firstResult` as the modifier of that first entry.
 */
@Composable
fun SearchWithResults(hasResults: Boolean, arrival: Any?, field: @Composable SearchFocus.() -> Unit, results: @Composable SearchFocus.(firstResult: Modifier) -> Unit) {
    val focus = remember { SearchFocus(FocusRequester(), FocusRequester()) }
    LaunchedEffect(arrival) { if (hasResults) focus.toFirst() }
    Box(Modifier.onPreviewKeyEvent { e ->
        if (!hasResults || e.key != Key.DirectionDown) false
        else { if (e.type == KeyEventType.KeyDown) focus.toFirst(); true }
    }) { focus.field() }
    focus.results(Modifier.focusRequester(focus.first).onPreviewKeyEvent { e ->
        if (e.key != Key.DirectionUp) false else { if (e.type == KeyEventType.KeyDown) focus.toField(); true }
    })
}

/**
 * One row of a list the user orders: its title and, if any, a quieter line under it, then Up, Down and Remove. [first] and
 * [last]: the ends of the list, where the arrow that leads nowhere is dimmed. The list itself, and what a press does, are the caller's.
 */
@Composable
fun OrderedListRow(title: String, sub: String? = null, upLabel: String, downLabel: String, removeLabel: String, first: Boolean, last: Boolean, minHeight: androidx.compose.ui.unit.Dp = 48.dp,
                   onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = minHeight).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onUp, enabled = !first, modifier = Modifier.semantics { contentDescription = upLabel }) { SymIcon(Sym.ARROW_UPWARD) }
        IconButton(onClick = onDown, enabled = !last, modifier = Modifier.semantics { contentDescription = downLabel }) { SymIcon(Sym.ARROW_DOWNWARD) }
        IconButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = removeLabel }) { SymIcon(Sym.CLOSE) }
    }
}

/** How long each of several values shows in the bar, as a row of choices of seconds ("3 s", "5 s") and, from a minute, minutes. */
@Composable
fun TurnChooser(label: String, help: String, choices: List<Int>, selected: Int, onChoose: (Int) -> Unit) {
    val res = LocalContext.current.resources
    ChoiceRow(label, choices.map { sec ->
        sec to if (sec % 60 == 0) res.getQuantityString(R.plurals.common_minutes_short, sec / 60, sec / 60) else res.getQuantityString(R.plurals.common_seconds_short, sec, sec)
    }, selected, help = help, onSelect = onChoose)
}

/** An entry that puts [text] on the clipboard and reads "Copied", with a check, for two seconds; no toast. */
@Composable
fun CopyEntry(label: String, icon: String = Sym.CONTENT_COPY, text: () -> String) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2_000); copied = false } }
    MenuEntry(if (copied) Sym.CHECK else icon, if (copied) stringResource(R.string.common_copied) else label) {
        Env.copy(text())
        copied = true
    }
}

/** A square-ish tile with an icon over a label, for grids of actions. */
@Composable
fun ActionTile(icon: String, label: String, selected: Boolean = false, onClick: () -> Unit) {
    // Tiles pop in in a diagonal from the top left, 15 ms a step (four to a row in a menu's width).
    val order = LocalTileOrder.current
    val i = remember(order) { order?.next() ?: 0 }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val bg = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        hovered || focused -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Column(
        Modifier.popIn(15f * (i / 4 + i % 4), 0.9f).width(84.dp).focusRing(focused, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(bg)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 9.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SymIcon(icon, size = 20.sp, filled = selected,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TileGrid(content: @Composable () -> Unit) {
    val order = remember { TileOrder() }
    androidx.compose.runtime.CompositionLocalProvider(LocalTileOrder provides order) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** The order tiles come in a grid, as they are first composed (so as they are laid out). */
class TileOrder { private var n = 0; fun next() = n++ }
val LocalTileOrder = androidx.compose.runtime.staticCompositionLocalOf<TileOrder?> { null }

/**
 * Pill buttons in a row (presets such as 5, 10, 25 min). With [selected] they are a choice: that one
 * has a check before its label and is said to be selected (how an item is shown, the day a flight is
 * looked up for).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(labels: List<String>, selected: Int? = null, onClick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l ->
            val popIn = Modifier.popIn(10f * minOf(i, 6), 0.92f)
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            val focused by source.collectIsFocusedAsState()
            val chosen = selected == i
            Row(popIn.heightIn(min = 32.dp).focusRing(focused, RoundedCornerShape(50)).clip(RoundedCornerShape(50))
                    .background(if (hovered) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.secondaryContainer)
                    .hoverable(source)
                    .then(if (selected == null) Modifier.clickable(interactionSource = source, indication = null) { onClick(i) }
                        else Modifier.selectable(selected = chosen, interactionSource = source, indication = null, role = Role.RadioButton) { onClick(i) })
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                // The mark is for the eye: a screen reader hears "selected" from the role, and "check mark" on top of it was noise.
                if (chosen) Text("✓ ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.clearAndSetSemantics { })
                Text(l, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/** A thin progress track. */
@Composable
fun Meter(fraction: Float, color: Color = MaterialTheme.colorScheme.primary) {
    Box(Modifier.fadeIn(0f).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        // The fill grows from its start and stops on its value (§7.2).
        Box(Modifier.drawIn(30f).fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(50)).background(color))
    }
}

/** Recomposes the caller once a second while the ticker runs. */
@Composable
fun rememberTick(): Long {
    val t by com.meridian11.books.bentobar.items.Ticker.tick.collectAsState()
    return t
}

/** One small bar per CPU core (0..1 each). */
@Composable
fun CoreBars(values: DoubleArray, color: Color = MaterialTheme.colorScheme.primary) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    // Each bar fills from the bottom, 8 ms after the one before it (§7.2: no wave in a block longer than 60 ms).
    val part = rememberPart(30f)
    Canvas(Modifier.part(part).fillMaxWidth().height(36.dp)) {
        if (values.isEmpty()) return@Canvas
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            val x = i * (w + gap)
            drawRoundRect(track, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height),
                androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            val h = (v.coerceIn(0.0, 1.0) * size.height).toFloat() * (if (part == null) 1f else MenuMotion.draw(part.ms(plus = 8f * i)))
            if (h > 0.5f) drawRoundRect(color, androidx.compose.ui.geometry.Offset(x, size.height - h), androidx.compose.ui.geometry.Size(w, h),
                androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        }
    }
}

/** Menus draw their own hover; this adds a ring when the keyboard focuses an entry (Tab, arrows). */
@Composable
private fun Modifier.focusRing(focused: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (focused) this.border(2.dp, MaterialTheme.colorScheme.primary, shape) else this
