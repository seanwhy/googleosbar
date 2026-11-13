package com.meridian11.books.bentobar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.meridian11.books.bentobar.R

/** A paragraph of explanation, in Setup, About and the disclosure. */
@Composable
internal fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun Bullet(text: String) = Row(Modifier.padding(start = 4.dp, top = 4.dp)) {
    // Same size and color as the paragraphs around it, so a list reads as part of the text.
    Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.width(8.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * What BentoBar does with its accessibility service and what it doesn't, in one place for Setup's
 * first step and the disclosure, so the two can't come to say different things.
 */
@Composable
internal fun AccessibilityUses() {
    Bullet(stringResource(R.string.setup_turn_on_draws))
    Bullet(stringResource(R.string.setup_turn_on_reads))
    Bullet(stringResource(R.string.setup_turn_on_windows))
    Bullet(stringResource(R.string.setup_turn_on_copies))
    Bullet(stringResource(R.string.setup_turn_on_runs))
    Spacer(Modifier.height(6.dp))
    Body(stringResource(R.string.setup_turn_on_doesnt))
}

/**
 * The prominent disclosure Google Play requires for the AccessibilityService API, with consent:
 * shown in the app before it sends anyone to Accessibility settings (and on first opening, so
 * nobody has to find Setup to see it). It says why the service is needed, what it reads and what
 * happens to that. Two answers, Agree and No thanks. Closing it any other way (Escape, Back) is no
 * answer: nothing is recorded and nothing is opened, and it never closes by itself.
 */
@Composable
fun AccessibilityDisclosure(onAgree: () -> Unit, onDecline: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // A click beside the dialog doesn't close it: easy to do by accident with a mouse.
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { androidx.compose.material3.Icon(painterResource(R.drawable.ic_bentobar), contentDescription = null) },
        title = { Text(stringResource(R.string.disclosure_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Body(stringResource(R.string.disclosure_intro))
                Spacer(Modifier.height(6.dp))
                AccessibilityUses()
                Spacer(Modifier.height(10.dp))
                Body(stringResource(R.string.disclosure_next))
            }
        },
        confirmButton = { Button(onClick = onAgree) { Text(stringResource(R.string.disclosure_agree)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.disclosure_decline)) } },
    )
}
