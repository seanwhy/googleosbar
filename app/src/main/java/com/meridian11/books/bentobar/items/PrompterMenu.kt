package com.meridian11.books.bentobar.items

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.ui.ChoiceRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.NotesRow
import com.meridian11.books.bentobar.ui.OptionHelp
import com.meridian11.books.bentobar.ui.SliderRow
import com.meridian11.books.bentobar.ui.SmallIconButton
import com.meridian11.books.bentobar.util.Sym
import kotlinx.coroutines.delay

/**
 * The prompter's popup: the script in a window [PrompterRules.WINDOW_DP] high, moving up so the line being read is at
 * the top, nearest the camera, and one row under it: play or pause (Space too, while the popup has the keys), slower,
 * faster, back to the start, edit, close. Nothing fades: the text moves.
 */
@Composable
internal fun PrompterMenu(item: ItemConfig, host: MenuHost) {
    val id = item.id
    val scripts by PrompterItem.scripts.collectAsState()
    val runs by PrompterItem.runs.collectAsState()
    val staged by PrompterItem.staged.collectAsState()
    LaunchedEffect(id) { PrompterItem.load(id) }
    val script = staged?.script ?: scripts[id]
    val speed = PrompterRules.speed.of(item)
    val going = staged?.going ?: runs[id] ?: PrompterItem.Going(PrompterRules.Run(), speed)
    // The speed the layout asks for, from this popup's buttons or the item's options: the place so far is kept at the old one.
    LaunchedEffect(id, speed) { PrompterItem.useSpeed(id, speed) }
    // Nothing moves where nobody reads it.
    DisposableEffect(id) { onDispose { PrompterItem.pauseOnLeaving(id) } }

    // Where the script's last line starts, once it is laid out: how far the script can move.
    var endDp by remember(id) { mutableStateOf<Float?>(null) }
    // The frame's time while it plays. Read only where the script is drawn, so a frame redraws the text and recomposes nothing.
    var now by remember { mutableLongStateOf(PrompterItem.clock()) }
    val current by rememberUpdatedState(going)
    val playing = going.run.playing
    LaunchedEffect(id, playing) {
        while (playing) {
            // An animation for as long as the script plays: a test's clock does not wait for it to end.
            withInfiniteAnimationFrameNanos { }
            now = PrompterItem.clock()
            val end = endDp ?: continue
            // At its end it stops there, paused; a script of one line at once, having nowhere to go.
            if (PrompterRules.offset(current.run, current.speed, now) >= end) { PrompterItem.stopAtEnd(id, end); break }
        }
    }
    val ended = !playing && endDp?.let { PrompterRules.ended(going.run.offsetDp, it) } == true
    val has = !script.isNullOrBlank()
    fun toggle() { if (has) PrompterItem.toggle(id, endDp) }

    // The popup stays when pressed beside, and gives the keys back to the app below then; a press inside takes them
    // again, and the focus comes back here with it, so Space is the prompter's.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val density = LocalDensity.current
    val sp = PrompterRules.textSp(PrompterRules.size.of(item))
    MenuCard(Sym.SUBTITLES, stringResource(R.string.item_prompter_title), stringResource(R.string.prompter_speed, speed)) {
        Column(
            Modifier.fillMaxWidth()
                // Before the buttons see it: Space on a focused button plays and pauses too, and presses nothing else.
                .onPreviewKeyEvent { e ->
                    if (e.key != Key.Spacebar) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) toggle()
                    true
                }
                .focusRequester(focus).focusable()
                .pointerInput(focus) {
                    awaitPointerEventScope {
                        while (true) if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) runCatching { focus.requestFocus() }
                    }
                },
        ) {
            Box(Modifier.fillMaxWidth().height(PrompterRules.WINDOW_DP.dp).clipToBounds()) {
                when {
                    script == null -> {}
                    script.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MenuNote(stringResource(R.string.prompter_empty)) }
                    else -> Text(script,
                        Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                            .graphicsLayer { translationY = -PrompterRules.shown(current.run, current.speed, now, endDp ?: 0f).dp.toPx() },
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = sp.sp, lineHeight = (sp * 1.35f).sp),
                        onTextLayout = { layout -> endDp = with(density) { layout.getLineTop(layout.lineCount - 1).toDp().value } })
                }
            }
            if (ended) MenuNote(stringResource(R.string.prompter_end))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallIconButton(if (playing) Sym.PAUSE else Sym.PLAY_ARROW, stringResource(when {
                    playing -> R.string.prompter_pause
                    ended -> R.string.prompter_play_again
                    else -> R.string.prompter_play
                }), enabled = has) { toggle() }
                SmallIconButton(Sym.REMOVE, stringResource(R.string.prompter_slower), enabled = has && speed > PrompterRules.speed.range.first) { PrompterItem.faster(item, -1) }
                SmallIconButton(Sym.ADD, stringResource(R.string.prompter_faster), enabled = has && speed < PrompterRules.speed.range.last) { PrompterItem.faster(item, 1) }
                SmallIconButton(Sym.REPLAY, stringResource(R.string.prompter_restart), enabled = has) { PrompterItem.restart(id) }
                Spacer(Modifier.weight(1f))
                SmallIconButton(Sym.EDIT, stringResource(R.string.prompter_edit)) { host.openItemSettings(id) }
                SmallIconButton(Sym.CLOSE, stringResource(R.string.prompter_close)) { host.close() }
            }
        }
    }
}

/** How long the typing rests before what was typed is kept. */
private const val SAVE_AFTER_MS = 800L

/** The prompter's options: the script, then how large and how fast it moves. */
@Composable
internal fun PrompterOptions(item: ItemConfig, set: (ItemConfig) -> Unit) {
    key(item.id) { PrompterNotes(item.id) }
    ChoiceRow(stringResource(R.string.prompter_size), listOf("s" to stringResource(R.string.prompter_size_s),
        "m" to stringResource(R.string.prompter_size_m), "l" to stringResource(R.string.prompter_size_l)), PrompterRules.size.of(item)) {
        set(PrompterRules.size.set(item, it))
    }
    SliderRow(stringResource(R.string.prompter_speed_label), PrompterRules.speed.of(item), PrompterRules.speed.range) { set(PrompterRules.speed.set(item, it)) }
}

/**
 * The script's field: read from where it is kept when the options open (off the main thread), in memory as it is typed,
 * and kept again off the main thread after a pause in typing and when the field leaves. Never in the item's options.
 */
@Composable
private fun PrompterNotes(id: String) {
    val scripts by PrompterItem.scripts.collectAsState()
    LaunchedEffect(id) { PrompterItem.load(id) }
    var typed by remember { mutableIntStateOf(0) }
    LaunchedEffect(typed) { if (typed > 0) { delay(SAVE_AFTER_MS); PrompterItem.save(id) } }
    DisposableEffect(id) { onDispose { PrompterItem.save(id) } }
    // Until it is read there is no field: what was typed into an empty one would meet the script on its way in.
    val script = scripts[id] ?: return
    NotesRow(stringResource(R.string.prompter_notes_label), script, PrompterRules.MOST_CHARS) { PrompterItem.edit(id, it); typed++ }
    OptionHelp(stringResource(R.string.prompter_notes_help))
}
