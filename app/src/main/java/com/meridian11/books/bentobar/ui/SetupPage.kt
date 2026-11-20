package com.meridian11.books.bentobar.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.MeetingChoices
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.Uses
import com.meridian11.books.bentobar.items.Access
import com.meridian11.books.bentobar.items.Accesses
import com.meridian11.books.bentobar.items.Calendar
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.ItemType
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.items.MediaAccess
import com.meridian11.books.bentobar.items.Meetings
import com.meridian11.books.bentobar.items.Ticker
import com.meridian11.books.bentobar.items.Usage
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The request codes of the two permissions Setup asks Android for. */
private const val ASK_NOTIFICATIONS = 3
private const val ASK_CALENDAR = 4

/** The rules of the Setup page that are no drawing: pure, so they are tested (`SetupRulesTest`). */
internal object SetupRules {
    /**
     * "When BentoBar is on, this window switches to Bar by itself": at the moment the service comes on, and only from
     * Setup, where someone was turning it on. A window on another page stays there.
     */
    fun switchesToBar(was: Boolean, running: Boolean, page: Page): Boolean = !was && running && page == Page.SETUP

    /**
     * Whether a refusal Android won't ask about again is said in Setup's own row, under the permission. Elsewhere it
     * is the window's message with "Open settings"; never both.
     */
    fun saidInItsRow(page: Page, permission: String): Boolean =
        page == Page.SETUP && permission in setOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CALENDAR, Manifest.permission.CAMERA)
}

/**
 * The accesses Setup has a row for: those the given [types] need ([ItemType.needs]), each once, in [Access]'s order.
 * None while no registered type needs one.
 */
internal fun accessRows(types: List<ItemType>): List<Access> = Access.entries.filter { a -> types.any { a in it.needs } }

/**
 * Setup, from [setup] (observed, so a row changes the moment something does): turning BentoBar on, then the
 * permissions some items use, which calendar events count as meetings, then the online services. Before BentoBar is on, Turn on is the page's one filled
 * button and the rest is "Later, for some items".
 *
 * [declined]: the answer to the disclosure was No thanks, which Turn on asks about again. [wontAsk]: the permissions
 * Android refused without showing its dialog, since this window opened. [types]: the registered item types, whose
 * [ItemType.needs] give the rows for the newer items' accesses ([accessRows]).
 */
@Composable
fun SetupPage(setup: SetupState, declined: Boolean, wontAsk: Set<String>, types: List<ItemType> = Items.all, onTurnOn: () -> Unit) {
    val activity = LocalActivity.current ?: return
    val running = setup.serviceOn
    PageColumn(Modifier.testTag("setup")) {
        PageSection(stringResource(R.string.setup_turn_on_title)) {
            if (running) Good(stringResource(R.string.setup_on))
            else Text(stringResource(R.string.setup_needed), style = Bento.type.small.copy(fontWeight = FontWeight.SemiBold), color = Bento.colors.warnInk)
        }
        PageCard {
            Column(Modifier.padding(top = Space.l, bottom = 18.dp)) {
                Body(stringResource(R.string.setup_turn_on_text))
                AccessibilityUses()
                Spacer(Modifier.height(Space.l))
                if (running) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    BentoButton(stringResource(R.string.setup_accessibility_settings), { MainActivity.openAccessibility(activity) }, icon = Sym.OPEN_IN_NEW)
                    BentoButton(stringResource(R.string.setup_app_info), { MainActivity.openAppInfo(activity) })
                } else TurnOn(setup, declined, onTurnOn) { MainActivity.openAppInfo(activity) }
                Text(stringResource(R.string.setup_review_note), Modifier.padding(top = Space.m), style = Bento.type.help, color = Bento.colors.ink2)
            }
        }

        if (running) PageSection(stringResource(R.string.setup_permissions), stringResource(R.string.setup_permissions_sub))
        else PageSection(stringResource(R.string.setup_later), stringResource(R.string.setup_later_sub))
        PageCard {
            val stop = stringResource(R.string.setup_stop_using)
            val manage = stringResource(R.string.setup_open_setting)
            Permission("notifications", stringResource(R.string.setup_notifications_title), stringResource(R.string.setup_notifications_text), setup.notifications, running, stop,
                onAllow = { setRuntimeUse(activity, Uses.NOTIFICATIONS, Manifest.permission.POST_NOTIFICATIONS, ASK_NOTIFICATIONS, true) },
                onManage = { setRuntimeUse(activity, Uses.NOTIFICATIONS, Manifest.permission.POST_NOTIFICATIONS, ASK_NOTIFICATIONS, false) })
            if (!setup.notifications && Manifest.permission.POST_NOTIFICATIONS in wontAsk) WontAsk(activity)
            RowLine()
            val live = {
                if (!Env.launch(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)))
                    Env.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            }
            Permission("live", stringResource(R.string.setup_live_title), stringResource(R.string.setup_live_text), setup.liveUpdates, running, manage,
                onAllow = { live() }, onManage = { live() })
            RowLine()
            Permission("calendar", stringResource(R.string.setup_calendar_title), stringResource(R.string.setup_calendar_text), setup.calendar, running, stop,
                onAllow = { setRuntimeUse(activity, Uses.CALENDAR, Manifest.permission.READ_CALENDAR, ASK_CALENDAR, true) },
                onManage = { setRuntimeUse(activity, Uses.CALENDAR, Manifest.permission.READ_CALENDAR, ASK_CALENDAR, false) })
            if (!setup.calendar && Manifest.permission.READ_CALENDAR in wontAsk) WontAsk(activity)
            RowLine()
            Permission("alarms", stringResource(R.string.setup_alarms_title), stringResource(R.string.setup_alarms_text), setup.exactAlarms, running, stop,
                onAllow = { setExactAlarmsUse(activity, true) }, onManage = { setExactAlarmsUse(activity, false) })
            RowLine()
            Permission("usage", stringResource(R.string.setup_usage_title), stringResource(R.string.usage_explain), setup.usageAccess, running, manage,
                onAllow = { Usage.openSettings(activity) }, onManage = { Usage.openSettings(activity) })
            RowLine()
            // Installed from a download, Android guards this switch like the accessibility one: say how, and offer App info.
            val guarded = !setup.mediaAccess && setup.sideloaded
            val listener = stringResource(R.string.setup_listener_text)
            Permission("listener", stringResource(R.string.setup_listener_title), if (guarded) listener + "\n" + stringResource(R.string.media_access_restricted) else listener,
                setup.mediaAccess, running, manage, onAllow = { MediaAccess.openSettings(activity) }, onManage = { MediaAccess.openSettings(activity) },
                appInfo = if (guarded) ({ MainActivity.openAppInfo(activity) }) else null)
            // The newer items' accesses, one row for each that an item needs: given, its way to Android's setting; not given,
            // the way to give it. Notification icons' switch is guarded as Now playing's is, on an install from a download.
            for (access in accessRows(types)) {
                RowLine()
                val given = access in setup.access
                val explain = stringResource(Accesses.explain(access))
                val sideGuarded = access == Access.NOTICES && !given && setup.sideloaded
                Permission("access-${access.name.lowercase()}", stringResource(Accesses.title(access)),
                    if (sideGuarded) explain + "\n" + stringResource(R.string.media_access_restricted) else explain, given, running, manage,
                    onAllow = { Accesses.open(activity, access) }, onManage = { Accesses.open(activity, access) },
                    appInfo = if (sideGuarded) ({ MainActivity.openAppInfo(activity) }) else null)
                if (access == Access.CAMERA && !given && Manifest.permission.CAMERA in wontAsk) WontAsk(activity)
            }
            RowLine()
            Permission("files", stringResource(R.string.setup_files_title), stringResource(R.string.setup_files_text),
                setup.fileAccess, running, manage,
                onAllow = { MainActivity.requestFilePermissions(activity) },
                onManage = { MainActivity.openAllFilesAccess(activity) })
        }

        // Which events count as meetings: the install's choices, whether or not a Next meeting item is in the bar.
        val choices by MeetingChoices.state.collectAsState()
        val read = shownCalendars(setup.calendar)
        CalendarEventsCard(setup.calendar || !read.isNullOrEmpty(), read.orEmpty(), choices, reading = read == null) { next -> MeetingChoices.update { next } }

        // Observed where it is kept: an item's menu can change it while this page is open.
        val online by Online.state.collectAsState()
        PageSection(stringResource(R.string.setup_online_title))
        PageCard {
            Body(stringResource(R.string.setup_online_text), Modifier.padding(top = Space.l, bottom = Space.s))
            Online.Service.entries.forEach { service ->
                val row = onlineRow(service)
                RowLine()
                OnlineSwitch(service, online, stringResource(row.label), stringResource(row.first))
                // The key outlives the item it was typed into: without this, taking it off the device
                // would mean adding that item again first.
                if (row.removeKey != null && service in online.keyed)
                    BentoButton(stringResource(row.removeKey), { Online.removeKey(service); Ticker.refresh() }, Modifier.padding(bottom = Space.m), kind = ButtonKind.DANGER)
            }
            RowLine()
            Text(stringResource(R.string.setup_online_off_note), Modifier.padding(vertical = Space.m), style = Bento.type.help, color = Bento.colors.ink3)
        }
    }
}

/** How long the calendars are left to settle after the provider told of a change: a sync tells of many in a row. */
private const val CALENDARS_SETTLE_MS = 300L

/**
 * The provider's calendars for Setup's list, observed while the list is composed ("Settings state is observed, not read
 * while drawing"): read off the main thread when it is composed, when calendar access changes, when the window comes back
 * ([Setup.returns]: the calendars may have been changed in another window), and when the provider's calendars change.
 * Null until the first read is back.
 */
@Composable
private fun shownCalendars(access: Boolean): List<Meetings.Cal>? {
    var calendars by remember { mutableStateOf<List<Meetings.Cal>?>(null) }
    var changes by remember { mutableIntStateOf(0) }
    val returns by Setup.returns.collectAsState()
    // Told only while the list is shown, and only with access: Android refuses an observer without it.
    DisposableEffect(access) {
        val stop = if (access) Calendar.watchCalendars { changes++ } else null
        onDispose { stop?.invoke() }
    }
    LaunchedEffect(access, returns, changes) {
        if (calendars != null) delay(CALENDARS_SETTLE_MS)
        calendars = withContext(Dispatchers.IO) { Calendar.calendars() }
    }
    return calendars
}

/**
 * Setup's "Calendar events": which events count as meetings, for every Next meeting item, the Live Update chip and Live
 * activity ([MeetingChoices]). [allowed]: the calendars can be read; [calendars]: all of them, of which only those the user
 * can change are listed (the others never count); [reading]: the first read of them is on its way. [onChange] gets the
 * choices as they are to be.
 */
@Composable
internal fun CalendarEventsCard(allowed: Boolean, calendars: List<Meetings.Cal>, choices: MeetingChoices.State, reading: Boolean = false,
                                onChange: (MeetingChoices.State) -> Unit) {
    val c = Bento.colors
    PageSection(stringResource(R.string.events_title), stringResource(R.string.events_sub))
    PageCard(Modifier.testTag("calendar-events")) {
        WideSettingRow(stringResource(R.string.events_calendars), help = stringResource(R.string.events_calendars_help)) {
            val listed = calendars.filter { it.editable }
            when {
                // The first read is on its way: saying "none" for a moment would be untrue.
                reading -> Spacer(Modifier.height(36.dp))
                !allowed -> Text(stringResource(R.string.events_calendars_need_access), Modifier.padding(top = Space.s), style = Bento.type.small, color = c.ink2)
                listed.isEmpty() -> Text(stringResource(R.string.events_calendars_none), Modifier.padding(top = Space.s), style = Bento.type.small, color = c.ink2)
                else -> {
                    // Each account's calendars under its name, but with one account no name at all; its primary calendar first.
                    val accounts = listed.groupBy { it.accountName }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    val groups = accounts.map { (account, cals) ->
                        (if (accounts.size > 1) account else null) to cals.sortedWith(compareByDescending<Meetings.Cal> { it.own && it.flagPrimary }
                            .thenByDescending { it.own }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }).map { cal ->
                            val name = cal.name.ifBlank { cal.owner }
                            CheckItem(cal.ident, name, Color(cal.color or 0xFF000000.toInt()), stringResource(R.string.events_calendar_said, name, account))
                        }
                    }
                    CheckList(groups, { it !in choices.calendarsOff },
                        { ident, on -> onChange(choices.copy(calendarsOff = if (on) choices.calendarsOff - ident else choices.calendarsOff + ident)) },
                        Modifier.fillMaxWidth().testTag("calendar-list"))
                }
            }
        }
        RowLine()
        ChoiceSetting(stringResource(R.string.events_only_me), listOf(
            MeetingChoices.OnlyMe.NEVER to stringResource(R.string.events_only_me_never),
            MeetingChoices.OnlyMe.CALL to stringResource(R.string.events_only_me_call),
            MeetingChoices.OnlyMe.ALWAYS to stringResource(R.string.events_only_me_always),
        ), choices.onlyMe, help = stringResource(R.string.events_only_me_help)) { onChange(choices.copy(onlyMe = it)) }
        RowLine()
        SwitchSetting(stringResource(R.string.events_unanswered), choices.unanswered, help = stringResource(R.string.events_unanswered_help)) {
            onChange(choices.copy(unanswered = it))
        }
        RowLine()
        SwitchSetting(stringResource(R.string.events_free), choices.free, help = stringResource(R.string.events_free_help)) {
            onChange(choices.copy(free = it))
        }
        RowLine()
        // Saved after a pause or when the focus goes: the choices as they are then, not as they were at the first key.
        val latest by rememberUpdatedState(choices)
        val limits = pluralStringResource(R.plurals.events_words_limits, Meetings.MAX_WORDS, Meetings.MAX_WORDS, Meetings.WORD_CHARS)
        TextSetting(stringResource(R.string.events_words), choices.words.joinToString(", "), Modifier.testTag("calendar-words"),
            help = stringResource(R.string.events_words_help) + " " + limits, placeholder = stringResource(R.string.events_words_hint),
            maxLength = Meetings.MAX_WORDS * (Meetings.WORD_CHARS + 2)) {
            onChange(latest.copy(words = Meetings.words(it)))
        }
    }
}

/** A word in the accent with its tick: on, allowed. Told by the tick and the word, not by the color. */
@Composable
private fun Good(word: String) {
    val c = Bento.colors
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        SymIcon(Sym.CHECK, size = 16.sp, color = c.accent)
        Spacer(Modifier.width(6.dp))
        Text(word, style = Bento.type.small.copy(fontWeight = FontWeight.Medium), color = c.accent)
    }
}

/**
 * What stands where the buttons are while BentoBar is off: Turn on (through the disclosure and the user's consent,
 * `MainActivity.turnOn`), App info, and how to allow the switch on an install from a download. With Advanced
 * Protection on, its notice takes Turn on's place: the service can't run, so there is nothing to turn on.
 */
@Composable
private fun TurnOn(setup: SetupState, declined: Boolean, onTurnOn: () -> Unit, onAppInfo: () -> Unit) {
    val c = Bento.colors
    if (setup.advancedProtection) {
        Row(Modifier.fillMaxWidth().testTag("advanced-protection").background(c.warnGround, RoundedCornerShape(Radius.medium)).padding(Space.m)) {
            SymIcon(Sym.WARNING, size = 18.sp, color = c.warnInk)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.setup_aap_title), style = Bento.type.small.copy(fontWeight = FontWeight.SemiBold), color = c.warnInk)
                Text(stringResource(R.string.setup_aap_text), style = Bento.type.small, color = c.warnInk)
            }
        }
        BentoButton(stringResource(R.string.setup_app_info), onAppInfo, Modifier.padding(top = Space.m))
        return
    }
    if (declined) NoticeLine(stringResource(R.string.setup_declined), Modifier.padding(bottom = Space.l).testTag("declined"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s), itemVerticalAlignment = Alignment.CenterVertically) {
        BentoButton(stringResource(R.string.setup_turn_on), onTurnOn, Modifier.testTag("setup-turn-on"), kind = ButtonKind.FILLED)
        BentoButton(stringResource(R.string.setup_app_info), onAppInfo)
        Text(stringResource(R.string.setup_switches), Modifier.padding(start = Space.s).widthIn(max = 420.dp), style = Bento.type.small, color = c.ink2)
    }
    Column(Modifier.fillMaxWidth().padding(top = Space.l).background(c.sunk, RoundedCornerShape(Radius.medium)).padding(horizontal = 14.dp, vertical = Space.m)) {
        Text(stringResource(R.string.setup_restricted_intro), style = Bento.type.help.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
        listOf(R.string.setup_restricted_1, R.string.setup_restricted_2, R.string.setup_restricted_3).forEachIndexed { i, step ->
            Row(Modifier.padding(top = Space.xs)) {
                Text("%d".format(i + 1), Modifier.width(22.dp), style = Bento.type.help.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                Text(stringResource(step), style = Bento.type.help, color = c.ink2)
            }
        }
    }
}

/**
 * One permission: what it is for, and at the row's end "Allowed" or the button that asks for it. An allowed one keeps
 * a quiet way out beside the word ([manage]: stop using it, or the system's own setting). Before BentoBar is on
 * ([prominent] false) Allow is a word in the accent, so Turn on stays the page's one button with a ground.
 */
@Composable
private fun Permission(tag: String, name: String, help: String, allowed: Boolean, prominent: Boolean, manage: String,
                       onAllow: () -> Unit, onManage: () -> Unit, appInfo: (() -> Unit)? = null) {
    SettingRow(name, Modifier.testTag("permission:$tag"), help) {
        if (allowed) {
            Good(stringResource(R.string.setup_allowed))
            Spacer(Modifier.width(Space.xs))
            // Six rows end in the same words: a screen reader hears which one each is.
            val label = stringResource(R.string.setup_action_for, manage, name)
            BentoButton(manage, onManage, Modifier.semantics { contentDescription = label }, kind = ButtonKind.QUIET)
        } else {
            if (appInfo != null) BentoButton(stringResource(R.string.setup_app_info), appInfo, kind = ButtonKind.QUIET)
            val allow = stringResource(R.string.common_allow)
            val label = stringResource(R.string.setup_action_for, allow, name)
            BentoButton(allow, onAllow, Modifier.semantics { contentDescription = label }, kind = if (prominent) ButtonKind.TONAL else ButtonKind.TEXT)
        }
    }
}

/** Under a permission Android refused without asking: where it can still be allowed. */
@Composable
private fun WontAsk(activity: Activity) {
    NoticeLine(stringResource(R.string.setup_denied_settings), Modifier.padding(bottom = Space.m).testTag("wont-ask")) {
        BentoButton(stringResource(R.string.common_open_settings), { MainActivity.openAppInfo(activity) }, kind = ButtonKind.WARN)
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
    SwitchSetting(label, on, help = if (!on && !setUp) first else null, enabled = on || setUp) { want ->
        if (want) Online.turnOn(service) else Online.turnOff(service)
        Ticker.refresh()
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
