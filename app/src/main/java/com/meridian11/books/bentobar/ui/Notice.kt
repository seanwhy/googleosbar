package com.meridian11.books.bentobar.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Short messages. Without an action it's a toast, which fades on its own. With one (Undo, Open
 * settings) it's the settings window's one snackbar ([rememberNotices]), shown for a fixed time: a snackbar with an action
 * and no duration stays until dismissed. A new message replaces the one that is up rather than waiting behind it: a third
 * quick Add's Undo must not arrive twenty seconds later and undo something that has changed since.
 */
object Notice {
    class Message(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val onGone: (() -> Unit)? = null)

    val messages = MutableSharedFlow<Message>(extraBufferCapacity = 4)

    /**
     * [onAction] comes last so that a trailing lambda, `post(text, label) { … }`, is the action, as it reads. [onGone] must be
     * passed by name: as the last parameter it once took that trailing lambda, and an added item was removed when its message ran out.
     * [onGone]: when the message has run its time or was acted on. Not when it is replaced or the window goes (a recreated window may offer it again).
     */
    fun post(text: String, action: String? = null, onGone: (() -> Unit)? = null, onAction: (() -> Unit)? = null) {
        if (action == null) android.widget.Toast.makeText(com.meridian11.books.bentobar.items.Env.app, text, android.widget.Toast.LENGTH_SHORT).show()
        else messages.tryEmit(Message(text, action, onAction, onGone))
    }
}

/** The snackbar state the settings window's one host draws, with every [Notice] going to it. Call it above what posts them: a message is seen only by someone already listening. */
@Composable
fun rememberNotices(): SnackbarHostState {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        // A plain collect, which subscribes the moment this coroutine first runs. collectLatest subscribes from a coroutine of its own, queued
        // behind whatever else this composition started (BarPage's post on a recreated window), and that post was lost with no one yet listening.
        var showing: Job? = null
        Notice.messages.collect { m ->
            showing?.cancelAndJoin()
            showing = launch {
                if (snackbar.showSnackbar(m.text, m.action, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) m.onAction?.invoke()
                m.onGone?.invoke()
            }
        }
    }
    return snackbar
}

/**
 * A notice that stays in the page where it applies (an area that has no room right now, a permission an item waits
 * for): amber, with its icon, so it is never told by color alone. [action] is a button at its end, if it has one, or
 * under the words where they are long ([stacked]).
 */
@Composable
fun NoticeLine(text: String, modifier: Modifier = Modifier, stacked: Boolean = false, action: (@Composable () -> Unit)? = null) {
    val c = Bento.colors
    Row(modifier.fillMaxWidth().heightIn(min = Target.control).background(c.warnGround, RoundedCornerShape(Radius.medium))
        .padding(start = Space.m, top = Space.s, end = Space.s, bottom = Space.s), verticalAlignment = if (stacked) Alignment.Top else Alignment.CenterVertically) {
        SymIcon(Sym.WARNING, size = 18.sp, color = c.warnInk)
        Spacer(Modifier.width(10.dp))
        if (stacked) Column(Modifier.weight(1f)) {
            Text(text, style = Bento.type.small, color = c.warnInk)
            if (action != null) {
                Spacer(Modifier.height(Space.s))
                action()
            }
        } else {
            Text(text, Modifier.weight(1f), style = Bento.type.small, color = c.warnInk)
            if (action != null) {
                Spacer(Modifier.width(Space.s))
                action()
            }
        }
    }
}
