package com.meridian11.books.bentobar.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.bar.SliderMath
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.util.SymIcon

/** A labelled group of single-choice chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(label: String, choices: List<Pair<T, String>>, selected: T, help: String? = null, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (help != null) Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            choices.forEach { (value, text) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) },
                    leadingIcon = if (value == selected) { { SymIcon(com.meridian11.books.bentobar.util.Sym.CHECK) } } else null)
            }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, help: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    // The whole row is the switch: clicking the label toggles it, and a screen reader hears its name.
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            if (help != null) Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
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
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = value }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { typed -> typed.take(maxLength).let { text = it; onChange(it) } },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            isError = error != null,
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        (error ?: help)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        }
    }
}

@Composable
fun SliderRow(label: String, value: Int, range: IntRange, format: (Int) -> String = { it.toString() }, onChange: (Int) -> Unit) {
    var v by remember { mutableFloatStateOf(value.toFloat()) }
    LaunchedEffect(value) { v = value.toFloat() }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(format(SliderMath.stop(v)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onChange(SliderMath.stop(v)) },
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = SliderMath.stops(range),
            // Read out as its value and unit ("12 dp"), not as a percentage of the track.
            modifier = Modifier.semantics { contentDescription = label; stateDescription = format(SliderMath.stop(v)) })
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
 * settings (Stocks'), its name over it.
 */
@Composable
fun ServiceKey(keyed: Boolean, words: KeyWords, onSave: (String) -> Boolean, onRemove: () -> Unit, onGetKey: () -> Unit,
               heading: String? = null, more: @Composable () -> Unit = {}) {
    var replacing by remember { mutableStateOf(false) }
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (heading != null) Text(heading, style = MaterialTheme.typography.labelLarge)
        if (keyed && !replacing) {
            Text(stringResource(words.saved), style = MaterialTheme.typography.bodyLarge)
            more()
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { replacing = true }) { Text(stringResource(words.replace)) }
                // At once, with no dialog and no Undo: an Undo would mean keeping the key after it was removed.
                TextButton(onClick = onRemove) { Text(stringResource(words.remove), color = MaterialTheme.colorScheme.error) }
            }
            Text(stringResource(words.removeHelp), style = MaterialTheme.typography.bodySmall, color = quiet)
        } else {
            if (!keyed) {
                Text(stringResource(words.consent), style = MaterialTheme.typography.bodyMedium)
                val getKey = stringResource(words.getKey)
                val opens = stringResource(R.string.common_opens_browser, getKey)
                TextButton(onClick = onGetKey, modifier = Modifier.semantics { contentDescription = opens }) { Text(getKey) }
            }
            KeyField(stringResource(words.label), stringResource(words.save), onSave = { if (onSave(it)) replacing = false },
                onCancel = if (replacing) { { replacing = false } } else null)
            Text(stringResource(words.help), style = MaterialTheme.typography.bodySmall, color = quiet, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
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
    var typed by remember { mutableStateOf("") }
    fun saveIt() {
        if (typed.isBlank()) return
        onSave(typed)
        typed = ""
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it.take(200) },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { saveIt() }),
            modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                // A hardware keyboard's Enter saves too. Down acts and up is swallowed, so the keyboard's own action can't act a second time.
                if (e.key == Key.Enter || e.key == Key.NumPadEnter) { if (e.type == KeyEventType.KeyDown) saveIt(); true } else false
            },
        )
        FilledTonalButton(onClick = { saveIt() }, enabled = typed.isNotBlank()) { Text(save, maxLines = 1) }
        if (onCancel != null) TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel), maxLines = 1) }
    }
}
