package com.meridian11.books.bentobar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R

/** How wide the disclosure's column is at most: a text to read, so narrower than a page of settings. */
private val DISCLOSURE_WIDTH = 600.dp

/** A paragraph of explanation, in Setup, About and the disclosure. */
@Composable
internal fun Body(text: String, modifier: Modifier = Modifier, style: TextStyle = reading()) =
    Text(text, modifier, style = style, color = Bento.colors.ink2)

/** Text to be read rather than scanned (a paragraph, a list): the small size with more air between its lines. */
@Composable
internal fun reading(): TextStyle = Bento.type.small.copy(lineHeight = 20.sp)

/** One line of a list: the dot is a character, so it grows with the text and stays on its first line. */
@Composable
internal fun Bullet(text: String, style: TextStyle = reading()) = Row(Modifier.padding(top = Space.xs)) {
    Text("•", style = style, color = Bento.colors.accent)
    Spacer(Modifier.width(10.dp))
    Text(text, style = style, color = Bento.colors.ink)
}

/**
 * What BentoBar does with its accessibility service and what it doesn't, in one place for Setup's
 * card and the disclosure, so the two can't come to say different things.
 */
@Composable
internal fun AccessibilityUses(style: TextStyle = reading()) {
    Column(Modifier.padding(top = Space.xs)) {
        Bullet(stringResource(R.string.setup_turn_on_draws), style)
        Bullet(stringResource(R.string.setup_turn_on_reads), style)
        Bullet(stringResource(R.string.setup_turn_on_windows), style)
        Bullet(stringResource(R.string.setup_turn_on_copies), style)
        Bullet(stringResource(R.string.setup_turn_on_runs), style)
    }
    Body(stringResource(R.string.setup_turn_on_doesnt), Modifier.padding(top = Space.m), style)
}

/**
 * The prominent disclosure Google Play requires for the AccessibilityService API, with consent:
 * shown in the app before it sends anyone to Accessibility settings (and on first opening, so
 * nobody has to find Setup to see it). It says why the service is needed, what it reads and what
 * happens to that. Two answers, Agree and No thanks, and no third control. Leaving it any other way
 * (Escape, Back, another tab) is no answer: [onDismiss] records nothing and opens nothing, and the
 * page never goes by itself.
 *
 * A page under the Setup tab, not a dialog: the window's header stays, with the status and no
 * control of its own, so the two buttons are the only ones to press.
 */
@Composable
fun AccessibilityDisclosure(onAgree: () -> Unit, onDecline: () -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val text = Bento.type.body
    PageColumn(Modifier.testTag("disclosure"), width = DISCLOSURE_WIDTH) {
        Spacer(Modifier.height(56.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mark(null, 48.dp)
            Spacer(Modifier.width(Space.l))
            Text(stringResource(R.string.disclosure_title), Modifier.semantics { heading() }, style = Bento.type.display, color = Bento.colors.ink)
        }
        Spacer(Modifier.height(Space.xl))
        Body(stringResource(R.string.disclosure_intro), style = text)
        AccessibilityUses(text)
        Body(stringResource(R.string.disclosure_next), Modifier.padding(top = Space.m), text)
        Spacer(Modifier.height(Space.xl))
        // No thanks first, Agree last and at the end: the order they are read and reached in.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m, Alignment.End), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            val size = Modifier.heightIn(min = Target.row).widthIn(min = 148.dp)
            BentoButton(stringResource(R.string.disclosure_decline), onDecline, size.testTag("disclosure-decline"))
            BentoButton(stringResource(R.string.disclosure_agree), onAgree, size.testTag("disclosure-agree"), kind = ButtonKind.FILLED)
        }
    }
}
