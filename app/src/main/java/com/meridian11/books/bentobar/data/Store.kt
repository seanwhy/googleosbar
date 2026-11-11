package com.meridian11.books.bentobar.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * The bar layout and settings, as one JSON document in SharedPreferences. The accessibility
 * service and the settings screens run in the same process and share this [StateFlow], so every
 * edit shows up in the status bar right away.
 */
object Store {
    private const val TAG = "BentoBar"
    private const val KEY = "bar"
    /** A stored layout that could not be read is kept here as it was, not overwritten by the next edit, until Backup's offer of it is answered. */
    private const val KEY_UNREADABLE = "bar_unreadable"
    private lateinit var prefs: Prefs
    private val state = MutableStateFlow(BarConfig())
    val config: StateFlow<BarConfig> get() = state.asStateFlow()

    /**
     * The user's answer to the accessibility disclosure. Google Play's User Data policy wants an app
     * that isn't an accessibility tool to say what it uses the AccessibilityService API for and get
     * consent, with a way to decline, before it sends anyone to turn its service on. Kept beside the
     * layout, not in it, and in Android's no-backup directory like the online switches (data/Online.kt):
     * Copy settings, a backup and a device transfer must not carry consent to another install.
     */
    enum class Consent { NOT_ASKED, DECLINED, AGREED }

    private const val KEY_CONSENT = "accessibility_consent" // where 1.7 kept it, in the backed-up prefs
    private const val CONSENT_FILE = "consent"
    private var consentFile: File? = null
    private val consentState = MutableStateFlow(Consent.NOT_ASKED)
    val consent: StateFlow<Consent> get() = consentState.asStateFlow()

    fun setConsent(answer: Consent) {
        consentState.value = answer
        consentFile?.let { runCatching { it.writeText(answer.name, Charsets.UTF_8) } }
    }

    /**
     * The saved answer in [noBackupDir]. With none there, the answer an earlier version kept in the prefs ([legacy]) is
     * moved over once, and [legacyMoved] is called when the file holds it, so the caller may drop the old key; a restored backup that still holds one counts as that install's own answer.
     */
    internal fun loadConsent(noBackupDir: File, legacy: String?, legacyMoved: () -> Unit = {}): Consent {
        val file = File(noBackupDir, CONSENT_FILE).also { consentFile = it }
        fun parse(name: String?) = name?.trim()?.let { runCatching { Consent.valueOf(it) }.getOrNull() }
        parse(runCatching { file.takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull())?.let { legacyMoved(); return it }
        val moved = parse(legacy) ?: return Consent.NOT_ASKED
        // Only a file that now holds the answer lets the legacy key go: a failed write must lose nothing.
        if (runCatching { file.writeText(moved.name, Charsets.UTF_8) }.isSuccess) legacyMoved()
        return moved
    }

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        val app = context.applicationContext
        val file = SharedPrefs(app.getSharedPreferences("bentobar", Context.MODE_PRIVATE))
        consentState.value = loadConsent(app.noBackupFilesDir, file.string(KEY_CONSENT)) {
            file.edit { remove(KEY_CONSENT) } // now in the no-backup file
        }
        open(file)
        migrateCustomItems()
    }

    private fun migrateCustomItems() {
        val aiTypes = listOf("ai_antigravity", "ai_codex", "ai_grok", "ai_grok_bot")
        val cleaned = state.value.items.filterNot { it.type == "ai_usage" }
        var changed = false
        var current = state.value
        if (cleaned.none { it.type in aiTypes }) {
            current = current.copy(
                items = cleaned + aiTypes.map { ItemConfig(newId(), it, Section.SHOWN) }
            )
            changed = true
        } else if (cleaned.size != current.items.size) {
            current = current.copy(items = cleaned)
            changed = true
        }
        if (current.items.none { it.type == "cloud_clipboard" }) {
            current = current.copy(
                items = current.items + ItemConfig(newId(), "cloud_clipboard", Section.SHOWN, display = Display.ICON)
            )
            changed = true
        }
        if (current.items.none { it.type == "remote_keyboard" }) {
            current = current.copy(
                items = current.items + ItemConfig(newId(), "remote_keyboard", Section.SHOWN)
            )
            changed = true
        }
        if (changed) {
            state.value = current
            save(state.value)
        }
    }

    /**
     * Reads the layout kept in [file]: [init]'s work, and with a map instead of Android's preferences, a test's. What can't
     * be read is not lost: it is set aside ([KEY_UNREADABLE]) and Backup offers it ([setAside]). The stored layout itself
     * is left as it is until the next edit, so an update that can read it brings it back by itself.
     */
    @Synchronized
    internal fun open(file: Prefs, log: (String) -> Unit = { Log.w(TAG, it) }) {
        prefs = file
        val raw = file.string(KEY)
        val read = raw?.let(::readLayout)
        state.value = (read as? LayoutRead.Ok)?.config ?: Defaults.config()
        when {
            raw == null -> save(state.value)
            read is LayoutRead.Ok -> {
                if (read.changed) save(state.value)
                // It reads again, so a copy of this very layout has nothing left to offer.
                if (file.string(KEY_UNREADABLE) == raw) file.edit { remove(KEY_UNREADABLE) }
            }
            else -> {
                // Not logged as it is: it can hold a city or a note. Kept, so that starting fresh loses nothing for good.
                log(if (read is LayoutRead.Newer) "settings from a newer version (${read.version}), starting fresh; kept as they were"
                    else "settings unreadable, starting fresh; kept as they were")
                file.edit { put(KEY_UNREADABLE, raw) }
            }
        }
        setAsideState.value = file.string(KEY_UNREADABLE)?.let(::whyKept)
    }

    fun update(change: (BarConfig) -> BarConfig) {
        synchronized(this) {
            val n = change(state.value)
            if (n == state.value) return
            state.value = n
            // Inside the lock, so a later change can never be saved before this one.
            save(n)
        }
    }

    fun updateItem(id: String, change: (ItemConfig) -> ItemConfig) =
        update { c -> c.copy(items = c.items.map { if (it.id == id) change(it) else it }) }

    /**
     * A new item goes at the left of the bar, where a new item is easy to spot, but after the Next
     * meeting item, which stays at the far left: its width changes most, and at the bar's outer
     * edge that moves nothing else. Hidden and Off items go at the end of their section. [whenActive]:
     * with its "Show when" rule on (for the types that are added to When active).
     */
    fun add(type: String, section: Section = Section.SHOWN, options: Map<String, String> = emptyMap(), whenActive: Boolean = false): String {
        val id = newId()
        update { c ->
            val item = ItemConfig(id, type, section, whenActive = whenActive, options = options)
            val at = if (section != Section.SHOWN) -1
                else c.items.indexOfFirst { it.section == Section.SHOWN && it.type != PINNED_LEFT }
            c.copy(items = if (at < 0) c.items + item else c.items.toMutableList().apply { add(at, item) })
        }
        return id
    }

    /** A copy of [item] with all its settings, right after it; its new id. Duplicate in the item's settings. */
    fun duplicate(item: ItemConfig): String {
        val id = newId()
        update { c -> c.copy(items = duplicated(c.items, item, id)) }
        return id
    }

    /** [items] with a copy of [of] (every setting, the id [id]) right after it; as they were if it is gone. */
    internal fun duplicated(items: List<ItemConfig>, of: ItemConfig, id: String): List<ItemConfig> {
        val at = items.indexOfFirst { it.id == of.id }
        return if (at < 0) items else items.toMutableList().apply { add(at + 1, items[at].copy(id = id)) }
    }

    /** The item type kept at the bar's far left (see [add]). */
    const val PINNED_LEFT = "event"

    fun remove(id: String) = update { c -> c.copy(items = c.items.filterNot { it.id == id }) }

    /** Moves [id] into [section] at [index] within that section (end if out of range). */
    fun move(id: String, section: Section, index: Int) = update { c -> c.copy(items = c.items.moved(id, section, index)) }

    fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

    /** The layout and look for Copy settings: what is local to this install stays out (see [LOCAL_SETTINGS]). */
    fun export(): String = layoutJson.encodeToString(BarConfig.serializer(), state.value.withoutLocal())

    /**
     * Replaces the layout and the look with a pasted one, unless [readLayout] refuses it. What belongs to this install
     * stays as it is here: the permissions switched off in Setup (a pasted layout must not switch calendar reading back on),
     * whether the bar is shown, presenting, and the first-run state.
     */
    fun import(text: String): LayoutRead {
        val read = readLayout(text)
        if (read is LayoutRead.Ok) update { read.config.keepingLocal(it) }
        return read
    }

    /** A layout from Copy settings, or null if [readLayout] doesn't take [text]. */
    fun parseLayout(text: String): BarConfig? = (readLayout(text) as? LayoutRead.Ok)?.config

    /** A layout set aside because it couldn't be read: what Backup offers, until it is put back or discarded. */
    enum class SetAside { READABLE_NOW, FROM_NEWER_VERSION, UNREADABLE }

    private val setAsideState = MutableStateFlow<SetAside?>(null)
    val setAside: StateFlow<SetAside?> get() = setAsideState.asStateFlow()

    private fun whyKept(raw: String) = when (readLayout(raw)) {
        is LayoutRead.Ok -> SetAside.READABLE_NOW
        is LayoutRead.Newer -> SetAside.FROM_NEWER_VERSION
        LayoutRead.NotALayout -> SetAside.UNREADABLE
    }

    /** The layout that was set aside, as it was, for Backup's Copy. */
    fun setAsideText(): String? = prefs.string(KEY_UNREADABLE)

    /** Puts the layout that was set aside back, when this version can read it: only ever by the user's own press, never over their edits by itself. */
    fun restoreSetAside(): Boolean {
        val raw = prefs.string(KEY_UNREADABLE) ?: return false
        val read = readLayout(raw) as? LayoutRead.Ok ?: return false
        update { read.config.keepingLocal(it) }
        prefs.edit { remove(KEY_UNREADABLE) }
        setAsideState.value = null
        return true
    }

    /** Backup's offer is answered with no: gone, also from under a stored layout that was never edited since, or the next start would keep it again. */
    fun discardSetAside() {
        val raw = prefs.string(KEY_UNREADABLE) ?: return
        val stale = prefs.string(KEY) == raw
        prefs.edit {
            remove(KEY_UNREADABLE)
            if (stale) put(KEY, layoutJson.encodeToString(BarConfig.serializer(), state.value))
        }
        setAsideState.value = null
    }

    private fun save(c: BarConfig) {
        prefs.edit { put(KEY, layoutJson.encodeToString(BarConfig.serializer(), c)) }
    }
}

/** What a fresh install shows: a useful bar that still leaves room. */
object Defaults {
    /**
     * A calm first bar, left to right: the next meeting (only when one is near), the calendar, a
     * timer and keep awake. Everything else is one click away in Add.
     */
    fun config() = BarConfig(
        items = listOf(
            ItemConfig(Store.newId(), "event", Section.HIDDEN, whenActive = true),
            ItemConfig(Store.newId(), "calendar", Section.SHOWN, display = Display.ICON),
            ItemConfig(Store.newId(), "timer", Section.SHOWN, display = Display.ICON),
            ItemConfig(Store.newId(), "caffeine", Section.SHOWN, display = Display.ICON),
            ItemConfig(Store.newId(), "cloud_clipboard", Section.SHOWN, display = Display.ICON),
            ItemConfig(Store.newId(), "remote_keyboard", Section.SHOWN),
            ItemConfig(Store.newId(), "ai_antigravity", Section.SHOWN),
            ItemConfig(Store.newId(), "ai_codex", Section.SHOWN),
            ItemConfig(Store.newId(), "ai_grok", Section.SHOWN),
            ItemConfig(Store.newId(), "ai_grok_bot", Section.SHOWN),
        ),
    )
}
