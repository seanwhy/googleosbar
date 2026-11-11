package com.meridian11.books.bentobar.items

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.SPACER_DP
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.spacerWidthDp
import com.meridian11.books.bentobar.ui.ActionTile
import com.meridian11.books.bentobar.ui.ChoiceRow
import com.meridian11.books.bentobar.ui.ChipRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.SectionLabel
import com.meridian11.books.bentobar.ui.SliderRow
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.ui.TextRow
import com.meridian11.books.bentobar.ui.TileGrid
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.ClockAnchor
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Sym
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/**
 * Keep-awake: while on, BarService sets FLAG_KEEP_SCREEN_ON on the visible strip (a wake lock while the strip is hidden). [until]
 * is wall time (it survives a restart); [anchor] keeps setting the clock from changing how long it lasts.
 */
object Caffeine {
    const val FOREVER = Long.MAX_VALUE
    val until = MutableStateFlow(0L)
    internal var anchor = ClockAnchor(0, 0, -1)

    fun init(context: Context) {
        val p = context.getSharedPreferences("caffeine", Context.MODE_PRIVATE)
        anchor = ClockAnchor(p.getLong("anchorWall", 0), p.getLong("anchorElapsed", 0), p.getInt("anchorBoot", -1))
        until.value = p.getLong("until", 0)
        check()
    }

    fun on(minutes: Int?) = set(if (minutes == null) FOREVER else System.currentTimeMillis() + minutes * 60_000L)
    fun off() = set(0)
    fun toggle(minutes: Int?) = if (active()) off() else on(minutes)

    /** On in real time, as [left] counts it: after a clock change [until] is out of date until the next [check], and while the bar is hidden no tick is there to do that. */
    fun active(now: ClockAnchor = ClockAnchor.now(Env.app)) = until.value.let { it == FOREVER || (it != 0L && anchor.left(it, now) > 0) }

    /** Real time until [deadline] (an [until]), so a clock change can't move when keep awake lets go. */
    fun left(deadline: Long) = anchor.left(deadline, ClockAnchor.now(Env.app))

    /** Follows a clock change, and turns itself off when the time is up; called every tick. */
    fun check() {
        val v = until.value
        if (v == 0L || v == FOREVER) return
        val jump = anchor.jump(ClockAnchor.now(Env.app))
        if (jump != 0L) set(v + jump)
        if (!active()) off()
    }

    private fun set(v: Long) {
        until.value = v
        anchor = ClockAnchor.now(Env.app)
        Env.app.getSharedPreferences("caffeine", Context.MODE_PRIVATE).edit().putLong("until", v)
            .putLong("anchorWall", anchor.wall).putLong("anchorElapsed", anchor.elapsed).putInt("anchorBoot", anchor.boot).apply()
    }
}

object CaffeineItem : ItemType("caffeine", R.string.item_caffeine_title, Sym.COFFEE, R.string.item_caffeine_desc) {
    override val group = ItemGroup.TOOLS
    override val canBeActive = true
    override val trigger = Trigger(R.string.trigger_caffeine, R.string.trigger_caffeine_short)
    private val durations = listOf(15, 30, 60, 120, 240)

    override fun state(item: ItemConfig): ItemState {
        val on = Caffeine.active()
        val left = Caffeine.until.value - System.currentTimeMillis()
        val forever = Caffeine.until.value == Caffeine.FOREVER
        val text = when {
            !on -> null
            forever -> Env.str(R.string.common_on)
            else -> Fmt.duration(left)
        }
        return ItemState(icon = Sym.COFFEE, filled = on, text = text, active = on, widthKey = if (on && !forever) "left" else null,
            desc = Env.str(if (on) R.string.caffeine_on_desc else R.string.caffeine_off_desc))
    }

    override fun onClick(item: ItemConfig): Boolean {
        if (!Env.running) return false // menu explains why it can't work
        Caffeine.toggle(item.opt("minutes", "").toIntOrNull())
        return true
    }

    // Caffeine.until is a StateFlow read as a plain value: rememberTick() below recomposes the menu every tick, which is
    // what keeps the countdown current, so there is nothing for collectAsState to add.
    @SuppressLint("StateFlowValueCalledInComposition")
    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { _, _ ->
        rememberTick()
        val on = Caffeine.active()
        MenuCard(Sym.COFFEE, stringResource(R.string.item_caffeine_title), when {
            !Env.running -> stringResource(R.string.caffeine_needs_service)
            !on -> stringResource(R.string.caffeine_off_subtitle)
            Caffeine.until.value == Caffeine.FOREVER -> stringResource(R.string.caffeine_until_off_subtitle)
            else -> stringResource(R.string.caffeine_for_subtitle, Fmt.duration(Caffeine.until.value - System.currentTimeMillis()))
        }) {
            SectionLabel(stringResource(R.string.caffeine_keep_on_for))
            ChipRow(durations.map {
                if (it < 60) pluralStringResource(R.plurals.common_minutes_short, it, it)
                else pluralStringResource(R.plurals.common_hours_short, it / 60, it / 60)
            } + stringResource(R.string.caffeine_until_i_turn_off)) { i ->
                Caffeine.on(durations.getOrNull(i))
            }
            if (on) {
                MenuDivider()
                MenuEntry(Sym.CLOSE, stringResource(R.string.common_turn_off)) { Caffeine.off() }
            }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        ChoiceRow(stringResource(R.string.caffeine_click_keeps_on), listOf("" to stringResource(R.string.caffeine_until_turned_off),
            "30" to pluralStringResource(R.plurals.common_minutes_short, 30, 30), "60" to pluralStringResource(R.plurals.common_hours, 1, 1),
            "120" to pluralStringResource(R.plurals.common_hours, 2, 2)),
            item.opt("minutes", "")) { set(item.with("minutes", it.ifBlank { null })) }
    }
}

object ToolsItem : ItemType("tools", R.string.item_tools_title, Sym.HANDYMAN, R.string.item_tools_desc) {
    override val group = ItemGroup.TOOLS
    override val menuWidthDp = 392
    private data class Tool(val icon: String, @StringRes val label: Int, val run: () -> Unit)

    private fun global(action: Int) = { Env.global(action); Unit }
    private fun settings(action: String) = { Env.launch(Intent(action)); Unit }

    private val system = listOf(
        Tool(Sym.SCREENSHOT_MONITOR, R.string.tools_screenshot, global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)),
        Tool(Sym.LOCK, R.string.tools_lock, global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)),
        Tool(Sym.DESKTOP_WINDOWS, R.string.tools_overview, global(AccessibilityService.GLOBAL_ACTION_RECENTS)),
        Tool(Sym.APPS, R.string.tools_all_apps, global(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS)),
        Tool(Sym.NOTIFICATIONS, R.string.tools_notifications, global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)),
        Tool(Sym.TOGGLE_ON, R.string.tools_quick_settings, global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)),
        Tool(Sym.POWER_SETTINGS_NEW, R.string.tools_power, global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)),
    )
    private val shortcuts = listOf(
        Tool(Sym.WIFI, R.string.tools_wifi, settings(Settings.ACTION_WIFI_SETTINGS)),
        Tool(Sym.DESKTOP_WINDOWS, R.string.tools_display, settings(Settings.ACTION_DISPLAY_SETTINGS)),
        Tool(Sym.VOLUME_UP, R.string.tools_sound, settings(Settings.ACTION_SOUND_SETTINGS)),
        Tool(Sym.KEYBOARD, R.string.tools_keyboard, settings(Settings.ACTION_HARD_KEYBOARD_SETTINGS)),
        Tool(Sym.BOLT, R.string.tools_battery, settings(Intent.ACTION_POWER_USAGE_SUMMARY)),
        Tool(Sym.HARD_DRIVE, R.string.tools_storage, settings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)),
        Tool(Sym.APPS, R.string.tools_apps, settings(Settings.ACTION_APPLICATION_SETTINGS)),
        Tool(Sym.TOUCH_APP, R.string.tools_accessibility, settings(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
        Tool(Sym.BUILD, R.string.tools_developer, settings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)),
        Tool(Sym.SETTINGS, R.string.tools_settings, settings(Settings.ACTION_SETTINGS)),
    )

    // Just the icon: the name told nothing the icon (and its tooltip) doesn't, and took the room of an item.
    override val iconOnly = true
    override fun state(item: ItemConfig) = ItemState(icon = Sym.HANDYMAN, desc = Env.str(R.string.item_tools_title))

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        MenuCard(Sym.HANDYMAN, stringResource(R.string.item_tools_title)) {
            if (item.optBool("system", true)) {
                SectionLabel(stringResource(R.string.tools_section_system))
                TileGrid { system.forEach { t -> ActionTile(t.icon, stringResource(t.label)) { host.afterClose(t.run) } } }
            }
            if (item.optBool("settings", true)) {
                SectionLabel(stringResource(R.string.tools_settings))
                TileGrid { shortcuts.forEach { t -> ActionTile(t.icon, stringResource(t.label)) { host.close(); t.run() } } }
            }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        SwitchRow(stringResource(R.string.tools_system_actions), item.optBool("system", true), help = stringResource(R.string.tools_system_actions_help)) {
            set(item.with("system", it.toString()))
        }
        SwitchRow(stringResource(R.string.tools_settings_shortcuts), item.optBool("settings", true)) { set(item.with("settings", it.toString())) }
    }
}

/** Launchable apps, for the app items. */
object Apps {
    data class App(val component: ComponentName, val label: String)

    private val icons = SlowCache<String, Bitmap>(128)
    private val labels = SlowCache<ComponentName, String>(256)

    /** Which item types want to hear of an app changing: while one is live, and not otherwise. Main thread. */
    private val watchers = Holders(0, { _, _ -> }, {}, ::startWatching, ::stopWatching)

    /** [who] (an item type going live) shows apps: what was kept of one that changes is dropped from now on. */
    fun watch(who: String) = watchers.hold(who)

    fun unwatch(who: String) = watchers.release(who)

    /** Installed, updated, turned off or removed. No permission asked: these broadcasts come to any app that can see the package, and this one sees the launchable apps. */
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            changed(intent.data?.schemeSpecificPart ?: return)
        }
    }

    /** What changed with no receiver up (the bar away overnight, say): nothing else would tell the caches, which have no expiry. No permission. */
    private val changes = PackageChanges { since ->
        Env.app.packageManager.getChangedPackages(since)?.let { it.sequenceNumber to it.packageNames }
    }

    private fun changed(pkg: String) {
        forget(pkg)
        AppItem.packageChanged(pkg)
    }

    private fun startWatching() {
        Env.app.registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, Context.RECEIVER_NOT_EXPORTED)
        // After registering: a change in between is then heard by one or both, never by neither.
        changes.sinceLastAsked().forEach(::changed)
    }

    private fun stopWatching() = Env.app.unregisterReceiver(packages)

    /** What was kept of [pkg] is out of date; [icon] and [label] read it again. */
    fun forget(pkg: String) {
        icons.forget { it.startsWith("$pkg/") }
        labels.forget { it.packageName == pkg }
    }

    fun list(context: Context): List<App> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map {
                val cn = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                val l = it.loadLabel(pm).toString()
                labels.put(cn, l)
                App(cn, l)
            }
            .filter { it.component.packageName != context.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    /** [label] if it's been read already: cheap enough to call while composing. */
    fun cachedLabel(cn: ComponentName): String? = labels.peek(cn)

    fun label(context: Context, cn: ComponentName): String = labels.get(cn) {
        runCatching {
            context.packageManager.getActivityInfo(cn, 0).loadLabel(context.packageManager).toString()
        }.getOrDefault(cn.packageName)
    } ?: cn.packageName

    /** [icon] if it's been drawn already: cheap enough to call while composing. */
    fun cachedIcon(cn: ComponentName, px: Int, mono: Boolean): Bitmap? = icons.peek(iconKey(cn, px, mono))

    private fun iconKey(cn: ComponentName, px: Int, mono: Boolean) = "${cn.flattenToShortString()}/$px/$mono"

    /** The app's icon; [mono] uses the themed (monochrome) layer when the app has one. Read and drawn outside the cache's lock, which the main thread reads under. */
    fun icon(context: Context, cn: ComponentName, px: Int, mono: Boolean): Bitmap? = icons.get(iconKey(cn, px, mono)) {
        runCatching {
            val d: Drawable = context.packageManager.getActivityIcon(cn)
            val drawable = if (mono && d is AdaptiveIconDrawable && d.monochrome != null) d.monochrome!! else d
            val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            if (mono && d is AdaptiveIconDrawable && d.monochrome != null) {
                // The monochrome layer is drawn on the adaptive canvas (108 dp with a 72 dp safe zone).
                val pad = (px * 0.25f).toInt()
                drawable.setBounds(-pad, -pad, px + pad, px + pad)
            } else drawable.setBounds(0, 0, px, px)
            drawable.draw(Canvas(b))
            b
        }.getOrNull()
    }

    fun launch(cn: ComponentName) = Env.launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn))

    fun parse(list: String): List<ComponentName> = list.split(',').mapNotNull { ComponentName.unflattenFromString(it.trim()) }
}

@Composable
fun AppPicker(selected: List<ComponentName>, multi: Boolean, onChange: (List<ComponentName>) -> Unit) {
    var query by remember { mutableStateOf("") }
    // Every launchable app with its label, and their icons, are read off the main thread: on a
    // Googlebook with many apps, doing it while composing stalled the settings window.
    val all by produceState<List<Apps.App>?>(null) { value = withContext(Dispatchers.IO) { Apps.list(Env.app) } }
    TextRow(stringResource(R.string.apps_find), query, placeholder = stringResource(R.string.apps_search)) { query = it }
    val shown = all.orEmpty().filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }.take(40)
    Column {
        if (all == null) CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp)
        shown.forEach { app ->
            val on = app.component in selected
            val toggle = { onChange(if (multi) (if (on) selected - app.component else selected + app.component) else listOf(app.component)) }
            // One control per app, named by it: TalkBack reads "Gmail, checkbox, checked", not a bare checkbox.
            Row(Modifier.fillMaxWidth().toggleable(on, role = Role.Checkbox, onValueChange = { toggle() }).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val icon by produceState(Apps.cachedIcon(app.component, 64, false), app.component) {
                    if (value == null) value = withContext(Dispatchers.IO) { Apps.icon(Env.app, app.component, 64, false) }
                }
                icon?.let { Image(it.asImageBitmap(), null, Modifier.size(28.dp)) } ?: Spacer(Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text(app.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Checkbox(checked = on, onCheckedChange = null)
            }
        }
    }
}

object AppItem : ItemType("app", R.string.item_app_title, Sym.ROCKET_LAUNCH, R.string.item_app_desc) {
    override val group = ItemGroup.TOOLS
    override val refreshMs = 60_000L
    internal val mono = Opt.bool("mono", true)

    private data class Key(val cn: ComponentName, val mono: Boolean)
    private data class Resolved(val label: String, val icon: Bitmap?)

    /** How long what was read of an app stays fresh, for what changes with no change to its package (the device's language, say); [Apps] hears of those. */
    private const val RELOAD_MS = 60 * 60_000L

    /** Label and icon are resolved off the main thread, so a cold cache never stalls a status-bar tick. A reload reads the app again, not what is kept of it. */
    private val resolved = Background.refresher<Key, Resolved>(type, every = { _, _ -> RELOAD_MS }) { key, last ->
        if (last != null) Apps.forget(key.cn.packageName)
        Resolved(Apps.label(Env.app, key.cn), Apps.icon(Env.app, key.cn, 64, key.mono))
    }

    override fun onLive() = Apps.watch(type)
    override fun onIdle() = Apps.unwatch(type)

    /** [pkg] changed: an item of it reads it again, and shows what it had until that is done. */
    fun packageChanged(pkg: String) {
        Store.config.value.items.filter { it.type == type }.forEach { item ->
            val cn = cn(item)?.takeIf { it.packageName == pkg } ?: return@forEach
            val key = Key(cn, mono.of(item))
            if (resolved.peek(key) != null) resolved.refresh(key)
        }
    }

    private fun cn(item: ItemConfig) = item.options["app"]?.let { ComponentName.unflattenFromString(it) }

    override fun state(item: ItemConfig): ItemState {
        val cn = cn(item) ?: return ItemState(icon = Sym.ROCKET_LAUNCH, text = Env.str(R.string.app_pick), desc = Env.str(R.string.app_no_app_desc))
        val themed = mono.of(item)
        val key = Key(cn, themed)
        resolved.want(key)
        val r = resolved.peek(key)
        val label = r?.label ?: Apps.cachedLabel(cn) ?: cn.packageName
        val icon = r?.icon ?: Apps.cachedIcon(cn, 64, themed)
        return ItemState(image = icon, icon = if (icon == null) Sym.ROCKET_LAUNCH else null, text = label, desc = Env.str(R.string.app_open_desc, label))
    }

    override fun onClick(item: ItemConfig): Boolean {
        val cn = cn(item) ?: return false
        Apps.launch(cn)
        return true
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        SwitchRow(stringResource(R.string.app_match_color), mono.of(item), help = stringResource(R.string.app_match_color_help)) {
            set(mono.set(item, it))
        }
        AppPicker(listOfNotNull(cn(item)), multi = false) { set(item.with("app", it.firstOrNull()?.flattenToString())) }
    }
}

object FolderItem : ItemType("folder", R.string.item_folder_title, Sym.GRID_VIEW, R.string.item_folder_desc) {
    override val group = ItemGroup.TOOLS
    override val menuWidthDp = 340
    private val label = Opt.text("label", 16)
    // The menu draws what it finds at opening, so a folder's apps only need the caches to be told: by the receiver while
    // this is live, and when it comes back by what Android says changed in between (Apps.startWatching).
    override fun onLive() = Apps.watch(type)
    override fun onIdle() = Apps.unwatch(type)
    override fun state(item: ItemConfig) = ItemState(icon = Sym.GRID_VIEW, text = label.of(item).ifBlank { Env.str(R.string.common_apps) }, desc = Env.str(R.string.item_folder_title))

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        val apps = Apps.parse(item.opt("apps", ""))
        val fallback = stringResource(R.string.common_apps)
        MenuCard(Sym.GRID_VIEW, label.of(item).ifBlank { fallback }) {
            if (apps.isEmpty()) Text(stringResource(R.string.folder_no_apps), style = MaterialTheme.typography.bodyMedium)
            TileGrid {
                apps.forEach { cn ->
                    val label by produceState(Apps.cachedLabel(cn) ?: cn.packageName, cn) {
                        if (Apps.cachedLabel(cn) == null) value = withContext(Dispatchers.IO) { Apps.label(Env.app, cn) }
                    }
                    val icon by produceState(Apps.cachedIcon(cn, 96, false), cn) {
                        if (value == null) value = withContext(Dispatchers.IO) { Apps.icon(Env.app, cn, 96, false) }
                    }
                    Column(Modifier.width(76.dp).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        icon?.let {
                            Image(it.asImageBitmap(), label,
                                Modifier.size(44.dp).clickableHand { host.close(); Apps.launch(cn) })
                        } ?: Spacer(Modifier.size(44.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
            MenuDivider()
            MenuEntry(Sym.EDIT, stringResource(R.string.folder_choose_apps)) { host.openItemSettings(item.id) }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        // The default name is the placeholder, not the text: emptied, the field stays empty while it is typed in.
        TextRow(stringResource(R.string.folder_name), label.of(item), placeholder = stringResource(R.string.common_apps), maxLength = 16) {
            set(label.set(item, it))
        }
        AppPicker(Apps.parse(item.opt("apps", "")), multi = true) { list ->
            set(item.with("apps", list.joinToString(",") { it.flattenToString() }.ifBlank { null }))
        }
    }
}

object TextItem : ItemType("text", R.string.item_text_title, Sym.TEXT_FIELDS, R.string.item_text_desc) {
    override val group = ItemGroup.TOOLS
    /** The words, which a user may empty on purpose: then the item is just its icon. */
    private val text = Opt.text("text", 40, clearedIsUnset = false)
    private fun words(item: ItemConfig) = text.stored(item) ?: Env.str(R.string.text_default)
    override fun state(item: ItemConfig) = ItemState(
        icon = item.options["icon"]?.let { iconChoices.toMap()[it] },
        text = words(item), desc = words(item),
    )

    val iconChoices = listOf("none" to "", "star" to Sym.STAR, "heart" to Sym.FAVORITE, "bookmark" to Sym.BOOKMARK,
        "label" to Sym.LABEL, "pin" to Sym.PUSH_PIN, "rocket" to Sym.ROCKET_LAUNCH, "idea" to Sym.EMOJI_OBJECTS, "link" to Sym.LINK)

    /** The icon choices' names (keys above are stored, these are shown). */
    private val iconNames = mapOf("none" to R.string.option_none, "star" to R.string.text_icon_star, "heart" to R.string.text_icon_heart,
        "bookmark" to R.string.text_icon_bookmark, "label" to R.string.text_icon_label, "pin" to R.string.text_icon_pin,
        "rocket" to R.string.text_icon_rocket, "idea" to R.string.text_icon_idea, "link" to R.string.text_icon_link)

    override fun onClick(item: ItemConfig): Boolean {
        val link = item.options["link"]?.takeIf { it.isNotBlank() } ?: return true
        Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(if (link.contains("://")) link else "https://$link")))
        return true
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        TextRow(stringResource(R.string.text_text), text.stored(item) ?: stringResource(R.string.text_default), help = stringResource(R.string.text_text_help), maxLength = 40) {
            set(text.set(item, it))
        }
        ChoiceRow(stringResource(R.string.text_icon), iconChoices.map { it.first to stringResource(iconNames.getValue(it.first)) },
            item.opt("icon", "none")) { set(item.with("icon", if (it == "none") null else it)) }
        TextRow(stringResource(R.string.text_opens), item.opt("link", ""), placeholder = "https://…", help = stringResource(R.string.text_opens_help)) {
            set(item.with("link", it.trim().ifBlank { null }))
        }
    }

    override fun defaultOptions() = mapOf("text" to Env.str(R.string.text_default))
}

object SpacerItem : ItemType(SpacerItem.TYPE, R.string.item_spacer_title, Sym.SPACE_BAR, R.string.item_spacer_desc) {
    /** Its name in a layout, and what the strip tells a spacer by ([com.meridian11.books.bentobar.bar.StripContent]). */
    const val TYPE = "spacer"
    // Drawn as a gap whatever it is set to show as: neither the settings nor its right-click menu offer "Show as".
    override val iconOnly = true
    override val group = ItemGroup.TOOLS
    override val refreshMs = 60_000L

    override fun state(item: ItemConfig) = ItemState(gapDp = item.spacerWidthDp, divider = item.optBool("line", false), desc = Env.str(R.string.spacer_desc))

    override fun onClick(item: ItemConfig) = true

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        val res = androidx.compose.ui.platform.LocalResources.current
        SliderRow(stringResource(R.string.spacer_width), item.spacerWidthDp, SPACER_DP, { res.getString(R.string.common_dp, it) }) { set(item.with("width", it.toString())) }
        SwitchRow(stringResource(R.string.spacer_line), item.optBool("line", false)) { set(item.with("line", it.toString())) }
    }
}

/** A clickable with the hand pointer, for small image buttons. */
fun Modifier.clickableHand(onClick: () -> Unit): Modifier =
    clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
