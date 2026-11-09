package com.meridian11.books.bentobar.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * The bar layout and settings, as one JSON document in SharedPreferences. The accessibility
 * service and the settings screens run in the same process and share this [StateFlow], so every
 * edit shows up in the status bar right away.
 */
object Store {
    private const val TAG = "BentoBar"
    private const val KEY = "bar"
    // A value this version doesn't know (a later version's enum constant) falls back to the field's default
    // instead of making the whole layout unreadable.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    /** A stored layout that could not be read is kept here as it was, not overwritten by the next edit. */
    private const val KEY_UNREADABLE = "bar_unreadable"
    private lateinit var prefs: SharedPreferences
    private val state = MutableStateFlow(BarConfig())
    val config: StateFlow<BarConfig> get() = state.asStateFlow()

    /**
     * The user's answer to the accessibility disclosure. Google Play's User Data policy wants an app
     * that isn't an accessibility tool to say what it uses the AccessibilityService API for and get
     * consent, with a way to decline, before it sends anyone to turn its service on. Kept beside the
     * layout, not in it: Copy settings must not carry consent to another install.
     */
    enum class Consent { NOT_ASKED, DECLINED, AGREED }

    private const val KEY_CONSENT = "accessibility_consent"
    private val consentState = MutableStateFlow(Consent.NOT_ASKED)
    val consent: StateFlow<Consent> get() = consentState.asStateFlow()

    fun setConsent(answer: Consent) {
        consentState.value = answer
        prefs.edit().putString(KEY_CONSENT, answer.name).apply()
    }

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("bentobar", Context.MODE_PRIVATE)
        consentState.value = prefs.getString(KEY_CONSENT, null)?.let { runCatching { Consent.valueOf(it) }.getOrNull() } ?: Consent.NOT_ASKED
        val raw = prefs.getString(KEY, null)
        val read = raw?.let(::decode)
        if (raw != null && read == null) {
            // Not logged as it is: it can hold a city or a note. Kept, so that starting fresh loses nothing for good.
            Log.w(TAG, "settings unreadable, starting fresh; kept as they were")
            prefs.edit().putString(KEY_UNREADABLE, raw).apply()
        }
        state.value = read ?: Defaults.config()
        // v1 → v2: timed hiding of revealed items became opt-in; 8 s was only the old default.
        if (state.value.version < 2) {
            val c = state.value
            state.value = c.copy(version = 2, autoCollapseSec = if (c.autoCollapseSec == 8) 0 else c.autoCollapseSec)
            save(state.value)
        }
        if (state.value.version < 3) { state.value = state.value.migrateToV3(); save(state.value) }
        val aiTypes = listOf("ai_antigravity", "ai_codex", "ai_grok", "ai_grok_bot")
        val cleaned = state.value.items.filterNot { it.type == "ai_usage" }
        if (cleaned.none { it.type in aiTypes }) {
            state.value = state.value.copy(
                items = cleaned + aiTypes.map { ItemConfig(newId(), it, Section.SHOWN) }
            )
            save(state.value)
        } else if (cleaned.size != state.value.items.size) {
            state.value = state.value.copy(items = cleaned)
            save(state.value)
        }
        if (state.value.items.none { it.type == "cloud_clipboard" }) {
            state.value = state.value.copy(
                items = state.value.items + ItemConfig(newId(), "cloud_clipboard", Section.SHOWN, display = Display.ICON)
            )
            save(state.value)
        }
        if (state.value.items.none { it.type == "remote_keyboard" }) {
            state.value = state.value.copy(
                items = state.value.items + ItemConfig(newId(), "remote_keyboard", Section.SHOWN)
            )
            save(state.value)
        }
        if (raw == null) save(state.value)
    }

    fun update(change: (BarConfig) -> BarConfig) {
        val next = synchronized(this) {
            val n = change(state.value)
            if (n == state.value) return
            state.value = n
            n
        }
        save(next)
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

    /** A stored layout as this version reads it: a value it doesn't know is that field's default; null if it isn't a layout at all. */
    internal fun decode(raw: String): BarConfig? = runCatching { json.decodeFromString(BarConfig.serializer(), raw) }.getOrNull()

    fun export(): String = json.encodeToString(BarConfig.serializer(), state.value)

    /**
     * Replaces the layout and the look with a pasted one. What belongs to this install stays as it
     * is here: the permissions switched off in Setup (a pasted layout must not switch calendar reading
     * back on), whether the bar is shown, presenting, and the first-run state.
     */
    fun import(text: String): Boolean {
        val c = parseLayout(text) ?: return false
        update { c.keepingLocal(it) }
        return true
    }

    /**
     * A layout from Copy settings, or null if [text] isn't one: it must be a JSON object with an
     * items list, or any JSON at all (`{}`, another app's settings) would replace the bar with an
     * empty one. An older layout comes through the same migrations; a repeated item id keeps its first.
     */
    fun parseLayout(text: String): BarConfig? = runCatching {
        val root = json.parseToJsonElement(text)
        if ((root as? kotlinx.serialization.json.JsonObject)?.get("items") !is kotlinx.serialization.json.JsonArray) return null
        json.decodeFromJsonElement(BarConfig.serializer(), root).let {
            if (it.version < 2) it.copy(version = 2, autoCollapseSec = if (it.autoCollapseSec == 8) 0 else it.autoCollapseSec) else it
        }.migrateToV3().let { it.copy(items = it.items.distinctBy { item -> item.id }) }
    }.getOrNull()

    private fun save(c: BarConfig) {
        prefs.edit().putString(KEY, json.encodeToString(BarConfig.serializer(), c)).apply()
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
