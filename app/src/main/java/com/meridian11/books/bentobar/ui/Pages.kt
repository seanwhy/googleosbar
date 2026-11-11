package com.meridian11.books.bentobar.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.meridian11.books.bentobar.BuildConfig
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ChipMode
import com.meridian11.books.bentobar.data.ColorMode
import com.meridian11.books.bentobar.data.Pill
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.LayoutRead
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Uses
import com.meridian11.books.bentobar.data.TextSize
import com.meridian11.books.bentobar.data.SPACING_DP
import com.meridian11.books.bentobar.data.spacingDp
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.ItemGroup
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.items.MediaAccess
import com.meridian11.books.bentobar.items.Notify
import com.meridian11.books.bentobar.items.Ticker
import com.meridian11.books.bentobar.items.Usage
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
        Column(Modifier.widthIn(max = 820.dp)) { content() }
        Spacer(Modifier.height(24.dp))
    }
}

/** A section heading on a page, with room above it: it belongs to what follows, not to the text before. */
@Composable
private fun PageLabel(text: String) {
    Spacer(Modifier.height(14.dp))
    SectionLabel(text)
}

// ---- Add -----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddPage(onAdded: (String) -> Unit) {
    val cfg by Store.config.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
        ItemGroup.entries.forEach { group ->
            SectionLabel(stringResource(group.title))
            group.caption?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Items.inGroup(group).forEach { type ->
                    val count = cfg.items.count { it.type == type.type }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.width(280.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center) {
                                    SymIcon(type.icon, size = 22.sp, filled = true, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(type.title, style = MaterialTheme.typography.titleMedium)
                                    if (count > 0) Text(pluralStringResource(R.plurals.add_in_your_bar, count, count), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(type.blurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                minLines = 2)
                            Spacer(Modifier.height(10.dp))
                            Row {
                                // An item that speaks up now and then goes to When active, with its rule on: it
                                // shows when it has something to say. Its second button puts it in the bar for good.
                                val quiet = type.addsWhenActive
                                FilledTonalButton(onClick = {
                                    onAdded(if (quiet) Store.add(type.type, Section.HIDDEN, type.defaultOptions(), whenActive = true)
                                    else Store.add(type.type, Section.SHOWN, type.defaultOptions()))
                                }) {
                                    SymIcon(Sym.ADD, size = 18.sp); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.add_add))
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { onAdded(Store.add(type.type, if (quiet) Section.SHOWN else Section.HIDDEN, type.defaultOptions())) }) {
                                    Text(stringResource(if (quiet) R.string.add_add_shown else R.string.add_add_hidden))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---- Look ----------------------------------------------------------------------------------

@Composable
fun LookPage() {
    val cfg by Store.config.collectAsState()
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    Page {
        SectionLabel(stringResource(R.string.look_placement))
        ChoiceRow(stringResource(R.string.look_position), listOf(Position.RIGHT to stringResource(R.string.look_position_right),
            Position.CENTER to stringResource(R.string.look_position_center), Position.LEFT to stringResource(R.string.look_position_left)),
            cfg.position) { p -> Store.update { it.copy(position = p) } }
        SliderRow(stringResource(R.string.look_spacing), cfg.spacingDp, SPACING_DP, { res.getString(R.string.common_dp, it) }) { v -> Store.update { it.copy(spacing = v) } }
        SwitchRow(stringResource(R.string.look_presenting), cfg.presenting, help = stringResource(R.string.look_presenting_help)) { on ->
            Store.update { it.copy(presenting = on) }
        }
        PageLabel(stringResource(R.string.look_look))
        ChoiceRow(stringResource(R.string.look_text_size), listOf(TextSize.SMALL to stringResource(R.string.look_text_small),
            TextSize.DEFAULT to stringResource(R.string.option_like_system), TextSize.LARGE to stringResource(R.string.look_text_large)), cfg.textSize) { v ->
            Store.update { it.copy(textSize = v) }
        }
        ChoiceRow(stringResource(R.string.look_color), listOf(ColorMode.AUTO to stringResource(R.string.look_color_auto),
            ColorMode.LIGHT to stringResource(R.string.look_color_light), ColorMode.DARK to stringResource(R.string.look_color_dark)), cfg.color,
            help = stringResource(R.string.look_color_help)) { v -> Store.update { it.copy(color = v) } }
        ChoiceRow(stringResource(R.string.look_background), listOf(Pill.NONE to stringResource(R.string.look_pill_none),
            Pill.SUBTLE to stringResource(R.string.look_pill_subtle), Pill.SOLID to stringResource(R.string.look_pill_solid)), cfg.pill) { v ->
            Store.update { it.copy(pill = v) }
        }
        SwitchRow(stringResource(R.string.look_hover_switch), cfg.hoverSwitchesPopups, help = stringResource(R.string.look_hover_switch_help)) { on ->
            Store.update { it.copy(hoverSwitchesPopups = on) }
        }
        SwitchRow(stringResource(R.string.look_no_animations), cfg.noAnimations, help = stringResource(R.string.look_no_animations_help)) { on ->
            Store.update { it.copy(noAnimations = on) }
        }
        SwitchRow(stringResource(R.string.look_system_tint), cfg.systemTint, help = stringResource(R.string.look_system_tint_help)) { on ->
            Store.update { it.copy(systemTint = on) }
        }
        PageLabel(stringResource(R.string.barmenu_hidden_items))
        ChoiceRow(stringResource(R.string.look_hidden_mode), listOf(HiddenMode.SHOW_ALL to stringResource(R.string.look_hidden_show_all),
            HiddenMode.CLICK to stringResource(R.string.look_hidden_click), HiddenMode.HOVER to stringResource(R.string.look_hidden_hover)),
            cfg.hiddenMode, help = stringResource(R.string.look_hidden_mode_help)) { v -> Store.update { it.copy(hiddenMode = v) } }
        // Folding back only applies when hidden items wait behind ‹.
        if (cfg.hiddenMode != HiddenMode.SHOW_ALL) ChoiceRow(stringResource(R.string.look_collapse), listOf(0 to stringResource(R.string.look_collapse_never),
            5 to pluralStringResource(R.plurals.look_collapse_after, 5, 5), 10 to pluralStringResource(R.plurals.look_collapse_after, 10, 10),
            30 to pluralStringResource(R.plurals.look_collapse_after, 30, 30)),
            if (cfg.autoCollapseSec in listOf(0, 5, 10, 30)) cfg.autoCollapseSec else 0) { v -> Store.update { it.copy(autoCollapseSec = v) } }
        PageLabel(stringResource(R.string.channel_live))
        ChoiceRow(stringResource(R.string.look_chip), listOf(ChipMode.OFF to stringResource(R.string.common_off),
            ChipMode.FALLBACK to stringResource(R.string.look_chip_fallback), ChipMode.ALWAYS to stringResource(R.string.look_chip_always)),
            cfg.chipMode, help = stringResource(R.string.look_chip_help)) { v ->
            Store.update { it.copy(chipMode = v) }
        }
        PageLabel(stringResource(R.string.look_backup))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(res.getString(R.string.look_clip_label), Store.export()))
                Toast.makeText(context, res.getString(R.string.look_copied), Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.look_copy)) }
            OutlinedButton(onClick = {
                // Its own text only (a layout always is): a copied file isn't opened on the main thread to find out.
                val text = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                val said = when (Store.import(text)) {
                    is LayoutRead.Ok -> R.string.look_restored
                    is LayoutRead.Newer -> R.string.look_paste_newer
                    LayoutRead.NotALayout -> R.string.look_not_settings
                }
                Toast.makeText(context, res.getString(said), Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.look_paste)) }
        }
        val aside by Store.setAside.collectAsState()
        aside?.let { why -> SetAsideOffer(why) }
    }
}

/**
 * A layout that could not be read was kept instead of being lost, and this is where it is offered, until it is put back or
 * discarded. Putting it back is only ever the user's press, never something done by itself over their edits.
 */
@Composable
private fun SetAsideOffer(why: Store.SetAside) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    Spacer(Modifier.height(10.dp))
    Text(stringResource(when (why) {
        Store.SetAside.READABLE_NOW -> R.string.look_kept_readable
        Store.SetAside.FROM_NEWER_VERSION -> R.string.look_kept_newer
        Store.SetAside.UNREADABLE -> R.string.look_kept_unreadable
    }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (why == Store.SetAside.READABLE_NOW) FilledTonalButton(onClick = { Store.restoreSetAside() }) { Text(stringResource(R.string.look_kept_restore)) }
        // Only an unreadable layout needs this: a readable one can be put back and copied as settings, a newer one waits for an update.
        if (why == Store.SetAside.UNREADABLE) OutlinedButton(onClick = {
            Store.setAsideText()?.let { text ->
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(res.getString(R.string.look_clip_label), text))
                Toast.makeText(context, res.getString(R.string.look_copied), Toast.LENGTH_SHORT).show()
            }
        }) { Text(stringResource(R.string.look_kept_copy)) }
        TextButton(onClick = { Store.discardSetAside() }) { Text(stringResource(R.string.look_kept_discard)) }
    }
}

// ---- Setup ---------------------------------------------------------------------------------

@Composable
private fun Step(n: Int, title: String, done: Boolean, optional: Boolean = false, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.padding(18.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape)
                .background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center) {
                if (done) SymIcon(Sym.CHECK, size = 18.sp, color = MaterialTheme.colorScheme.onPrimary)
                else Text("$n", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    if (optional) { Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.setup_optional), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
                    if (done) { Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.setup_done), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.height(4.dp))
                content()
            }
        }
    }
}

/**
 * Setup's steps, from [setup] (observed, so they flip to done the moment something changes). One
 * rule for buttons: a step that isn't done has a filled action button; a done step only offers a
 * plain link to manage it.
 */
@Composable
fun SetupPage(activity: Activity, setup: SetupState, onTurnOn: () -> Unit) {
    val running = setup.serviceOn
    Page {
        if (setup.advancedProtection) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(stringResource(R.string.setup_aap_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(stringResource(R.string.setup_aap_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        Step(1, stringResource(R.string.setup_turn_on_title), running) {
            Body(stringResource(R.string.setup_turn_on_text))
            Spacer(Modifier.height(6.dp))
            AccessibilityUses()
            Spacer(Modifier.height(10.dp))
            Row(Modifier.offset(x = if (running) (-12).dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) TextButton(onClick = { MainActivity.openAccessibility(activity) }) { Text(stringResource(R.string.setup_accessibility_settings)) }
                // Turning it on goes through the disclosure and the user's consent first (MainActivity.turnOn).
                else FilledTonalButton(onClick = onTurnOn, enabled = !setup.advancedProtection) { Text(stringResource(R.string.setup_turn_on)) }
                TextButton(onClick = { MainActivity.openAppInfo(activity) }) { Text(stringResource(R.string.setup_app_info)) }
            }
            if (!running) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.setup_restricted_intro), style = MaterialTheme.typography.bodyMedium)
                Bullet(stringResource(R.string.setup_restricted_1))
                Bullet(stringResource(R.string.setup_restricted_2))
                Bullet(stringResource(R.string.setup_restricted_3))
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.setup_review_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Step(2, stringResource(R.string.setup_notifications_title), setup.notifications) {
            UseSwitch(stringResource(R.string.setup_notifications_text), stringResource(R.string.setup_notifications_title), setup.notifications) { on ->
                setRuntimeUse(activity, Uses.NOTIFICATIONS, Manifest.permission.POST_NOTIFICATIONS, 3, on)
            }
        }
        Step(3, stringResource(R.string.setup_live_title), setup.liveUpdates, optional = true) {
            Body(stringResource(R.string.setup_live_text))
            val open = {
                if (!Env.launch(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)))
                    Env.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            }
            if (setup.liveUpdates) StepLink(stringResource(R.string.setup_open_setting)) { open() } else StepAction(stringResource(R.string.setup_turn_on)) { open() }
        }
        Step(4, stringResource(R.string.setup_calendar_title), setup.calendar, optional = true) {
            UseSwitch(stringResource(R.string.setup_calendar_text), stringResource(R.string.setup_calendar_title), setup.calendar) { on ->
                setRuntimeUse(activity, Uses.CALENDAR, Manifest.permission.READ_CALENDAR, 4, on)
            }
        }
        Step(5, stringResource(R.string.setup_alarms_title), setup.exactAlarms, optional = true) {
            UseSwitch(stringResource(R.string.setup_alarms_text), stringResource(R.string.setup_alarms_title), setup.exactAlarms) { on ->
                setExactAlarmsUse(activity, on)
            }
        }
        Step(6, stringResource(R.string.setup_usage_title), setup.usageAccess, optional = true) {
            Body(stringResource(R.string.usage_explain))
            if (setup.usageAccess) StepLink(stringResource(R.string.setup_open_setting)) { Usage.openSettings(activity) }
            else StepAction(stringResource(R.string.setup_turn_on)) { Usage.openSettings(activity) }
        }
        Step(7, stringResource(R.string.setup_listener_title), setup.mediaAccess, optional = true) {
            Body(stringResource(R.string.setup_listener_text))
            // Installed from a download, Android guards this switch like the accessibility one: say how, and offer App info.
            val guarded = !setup.mediaAccess && setup.sideloaded
            if (guarded) { Spacer(Modifier.height(4.dp)); Body(stringResource(R.string.media_access_restricted)) }
            if (setup.mediaAccess) StepLink(stringResource(R.string.setup_open_setting)) { MediaAccess.openSettings(activity) }
            else Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                StepAction(stringResource(R.string.setup_turn_on)) { MediaAccess.openSettings(activity) }
                if (guarded) TextButton(onClick = { MainActivity.openAppInfo(activity) }) { Text(stringResource(R.string.setup_app_info)) }
            }
        }
        // Observed where it is kept: an item's menu can change it while this page is open.
        val online by Online.state.collectAsState()
        Step(8, stringResource(R.string.setup_online_title), online.on.isNotEmpty(), optional = true) {
            Body(stringResource(R.string.setup_online_text))
            Spacer(Modifier.height(4.dp))
            Online.Service.entries.forEach { service ->
                val row = onlineRow(service)
                OnlineSwitch(service, online, stringResource(row.label), stringResource(row.first))
                // The key outlives the item it was typed into: without this, taking it off the device
                // would mean adding that item again first.
                if (row.removeKey != null && service in online.keyed) TextButton(onClick = { Online.removeKey(service); Ticker.refresh() }) {
                    Text(stringResource(row.removeKey), color = MaterialTheme.colorScheme.error)
                }
            }
            Text(stringResource(R.string.setup_online_off_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Step(9, "文件读取权限（云剪切板上传文件）", setup.fileAccess, optional = true) {
            Body("授予「所有文件访问权限」后，云剪切板可直接读取并上传您在文件管理器中复制的任意本地文件。")
            if (setup.fileAccess) {
                StepLink(stringResource(R.string.setup_open_setting)) { MainActivity.requestFilePermissions(activity) }
            } else {
                StepAction(stringResource(R.string.setup_turn_on)) { MainActivity.requestFilePermissions(activity) }
            }
        }
    }
}

/** Setup's row for an online service: every [Online.Service] has one, or this doesn't compile. */
internal data class OnlineRow(val label: Int, val first: Int, val removeKey: Int? = null)

internal fun onlineRow(service: Online.Service): OnlineRow = when (service) {
    Online.Service.OPEN_METEO -> OnlineRow(R.string.setup_online_weather, R.string.setup_online_weather_first)
    Online.Service.AIRLABS -> OnlineRow(R.string.setup_online_flights, R.string.setup_online_flights_first, R.string.flight_remove_key)
    Online.Service.FINNHUB -> OnlineRow(R.string.setup_online_stocks, R.string.setup_online_stocks_first, R.string.stocks_remove_key)
    Online.Service.FRANKFURTER -> OnlineRow(R.string.setup_online_fx, R.string.setup_online_fx_first)
}

/**
 * One online service's switch. It can be turned on here only once its item was set up on this
 * install (a city searched, a key saved): until then it is off and says where to start. Off stops
 * requests at once and deletes what the service sent.
 */
@Composable
private fun OnlineSwitch(service: Online.Service, state: Online.State, label: String, first: String) {
    val on = service in state.on
    val setUp = service in state.setUp
    SwitchRow(label, on, help = if (!on && !setUp) first else null, enabled = on || setUp) { want ->
        if (want) Online.turnOn(service) else Online.turnOff(service)
        Ticker.refresh()
    }
}

/**
 * "BentoBar uses this": a switch beside the step's explanation. It reads as on only when Android
 * granted the permission and the user hasn't switched it off here, so a fresh install starts off.
 */
@Composable
private fun UseSwitch(text: String, label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(on, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Body(text) }
        Spacer(Modifier.width(16.dp))
        Switch(checked = on, onCheckedChange = null, modifier = Modifier.semantics { contentDescription = label })
    }
}

/**
 * On: asks Android if needed. Off: BentoBar stops using it at once and gives the permission back
 * (revokeSelfPermissionOnKill), which Android completes the next time BentoBar's process restarts;
 * the accessibility service keeps it running, so in practice at the next update or reboot.
 */
private fun setRuntimeUse(activity: Activity, key: String, permission: String, request: Int, on: Boolean) {
    val granted = activity.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (on) {
        Store.update { it.copy(turnedOff = it.turnedOff - key) }
        if (!granted) activity.requestPermissions(arrayOf(permission), request)
    } else {
        Store.update { it.copy(turnedOff = it.turnedOff + key) }
        // Off means off now: drop events already read (the chip used them), not just new reads.
        if (key == Uses.CALENDAR) com.meridian11.books.bentobar.items.Calendar.forget()
        if (granted) activity.revokeSelfPermissionOnKill(permission)
        Notice.post(activity.getString(R.string.setup_use_off_runtime))
    }
    Setup.refresh(activity)
}

/** Exact alarms are a special access an app can't give back itself: off stops using it, and says where to remove it. */
private fun setExactAlarmsUse(activity: Activity, on: Boolean) {
    val open = { Env.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + activity.packageName))) }
    val can = activity.getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() == true
    if (on) {
        Store.update { it.copy(turnedOff = it.turnedOff - Uses.EXACT_ALARMS) }
        if (!can) open()
    } else {
        Store.update { it.copy(turnedOff = it.turnedOff + Uses.EXACT_ALARMS) }
        if (can) Notice.post(activity.getString(R.string.setup_use_off_exact))
    }
    Setup.refresh(activity)
}

/** A step's main action, while it isn't done. */
@Composable
private fun StepAction(label: String, onClick: () -> Unit) =
    FilledTonalButton(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) { Text(label) }

/** A done step's way to change it. */
@Composable
private fun StepLink(label: String, onClick: () -> Unit) =
    TextButton(onClick = onClick, modifier = Modifier.padding(top = 4.dp).offset(x = (-12).dp)) { Text(label) }

// ---- About ---------------------------------------------------------------------------------

private const val PRIVACY_URL = "https://meridian11.com/privacy/bentobar"
private const val OPEN_METEO_URL = "https://open-meteo.com/"

@Composable
fun AboutPage() {
    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(com.meridian11.books.bentobar.R.drawable.ic_bentobar),
                    contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        PageLabel(stringResource(R.string.about_privacy))
        Body(stringResource(R.string.about_privacy_text))
        Spacer(Modifier.height(4.dp))
        Items.all.filter { it.online != null }.mapNotNull { it.onlineNote }.forEach { Bullet(stringResource(it)) }
        Bullet(stringResource(R.string.about_privacy_clipboard))
        Bullet(stringResource(R.string.about_privacy_media))
        Bullet(stringResource(R.string.about_privacy_others))
        Spacer(Modifier.height(8.dp))
        Body(stringResource(R.string.about_privacy_backup))
        // Google Play asks for the privacy policy to be reachable from inside the app.
        TextButton(onClick = { Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }, modifier = Modifier.offset(x = (-12).dp)) {
            Text(stringResource(R.string.about_privacy_policy))
        }
        PageLabel(stringResource(R.string.about_how))
        Body(stringResource(R.string.about_how_text))
        PageLabel(stringResource(R.string.about_open_source))
        Body(stringResource(R.string.about_open_source_text))
        Spacer(Modifier.height(4.dp))
        Bullet(stringResource(R.string.about_credit_symbols))
        Bullet(stringResource(R.string.about_credit_compose))
        Bullet(stringResource(R.string.about_credit_kotlin))
        Bullet(stringResource(R.string.about_credit_openmeteo))
        Bullet(stringResource(R.string.about_credit_airlabs))
        Bullet(stringResource(R.string.about_credit_finnhub))
        Bullet(stringResource(R.string.about_credit_frankfurter))
        // Open-Meteo's licence asks for the credit and a link to it.
        TextButton(onClick = { Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(OPEN_METEO_URL))) }, modifier = Modifier.offset(x = (-12).dp)) {
            Text(stringResource(R.string.weather_open_site))
        }
        PageLabel(stringResource(R.string.about_who))
        Body(stringResource(R.string.about_who_text))
        Spacer(Modifier.height(8.dp))
        Body(stringResource(R.string.about_independent_text))
    }
}
