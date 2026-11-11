package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.util.Sym
import kotlin.math.roundToInt

/**
 * The rules of Device batteries that need no Android: what kind of device an input device is, which
 * devices are listed and in what order, which one the bar shows, and what it says. Pure Kotlin,
 * unit-tested (`DevicesRulesTest`). What Android's `InputDevice` and `BatteryState` say comes in as
 * the plain numbers they are.
 */
object DevicesRules {
    /** The devices are asked for their battery once a minute; Android tells of a device that comes or goes, not of a level that changes. */
    const val EVERY_MS = 60_000L
    /** No second reading within a second of the last: a device that connects reports in two or three times in a row. */
    const val FLOOR_MS = 1_000L
    /** A device that has just connected is asked once more after this long: it often tells its level a moment later. */
    const val SETTLE_MS = 5_000L
    /** Opening the menu reads the levels again, unless the last reading is younger than this. */
    const val MENU_FLOOR_MS = 5_000L
    /** The tones' own numbers, as for the Googlebook's battery: a warning below 20%, an alert below 10%, whatever the rule's number is. */
    const val WARN_BELOW = 20
    const val ALERT_BELOW = 10
    /** A name is the device's own word about itself: it is shown as one line of at most this many characters. */
    const val NAME_MAX = 60

    // InputDevice's sources (a class in the low byte, the kind above it) and keyboard type, and BatteryState's statuses.
    private const val SOURCE_GAMEPAD = 0x401
    private const val SOURCE_STYLUS = 0x4002
    private const val SOURCE_JOYSTICK = 0x1000010
    private const val KEYBOARD_ALPHABETIC = 2
    private const val STATUS_CHARGING = 2
    private const val STATUS_FULL = 5

    enum class Kind(val glyph: String) { MOUSE(Sym.MOUSE), KEYBOARD(Sym.KEYBOARD), STYLUS(Sym.STYLUS), GAMEPAD(Sym.SPORTS_ESPORTS) }

    /** One input device as Android lists it, with what its battery says. A physical device can be several of these. */
    data class Seen(val descriptor: String, val name: String, val sources: Int, val keyboardType: Int,
                    val present: Boolean, val capacity: Float, val status: Int)

    /** One device with a battery. [percent]: null where it has a battery and doesn't say how full ("Level unknown"). [name] can be empty. */
    data class Device(val descriptor: String, val name: String, val kind: Kind, val percent: Int?, val charging: Boolean)

    /** The sentences a screen reader hears, and the word for a device without a name: from the resources in the app, from the copy deck in the tests. */
    class Words(
        val none: () -> String,
        val level: (name: String, percent: Int) -> String,
        val low: (name: String, percent: Int) -> String,
        val charging: (name: String, percent: Int) -> String,
        val unknown: (name: String) -> String,
        val unnamed: () -> String,
    )

    /** What the item shows in the bar. */
    data class Bar(val glyph: String, val filled: Boolean, val text: String?, val tone: Tone, val active: Boolean, val desc: String, val tooltip: String?)

    private fun Int.has(source: Int) = this and source == source

    /**
     * The glyph a device gets: a stylus; a gamepad or joystick; a keyboard with letters, also when it
     * has a pointer; and a mouse for every pointer and for anything else. In that order, because a
     * controller has keys and a touchpad too, and a mouse reports its buttons as keys.
     */
    fun kind(sources: Int, keyboardType: Int): Kind = when {
        sources.has(SOURCE_STYLUS) -> Kind.STYLUS
        sources.has(SOURCE_GAMEPAD) || sources.has(SOURCE_JOYSTICK) -> Kind.GAMEPAD
        keyboardType == KEYBOARD_ALPHABETIC -> Kind.KEYBOARD
        else -> Kind.MOUSE
    }

    /** A battery's level (0 to 1) as a whole percentage, rounded: 0.29 is 28.999998 in a float. Null where it is no number. */
    fun percent(capacity: Float): Int? = if (!capacity.isFinite() || capacity < 0f) null else (capacity * 100).roundToInt().coerceAtMost(100)

    /** On its charger: charging, or full. No warning then, as for the Googlebook's own battery. */
    fun charging(status: Int): Boolean = status == STATUS_CHARGING || status == STATUS_FULL

    /**
     * The devices to list, in the menu's order: one for each descriptor that has a battery. A device
     * that shows up as keys and as a pointer is two input devices with one descriptor: it is one
     * device, with the shorter of the names ("Keychron K3", not "Keychron K3 Mouse") and whatever
     * either half knows of the battery. Should the halves disagree (Android gives two devices it
     * can't tell apart one descriptor too), the row is the one that matters more: the lowest that is
     * not charging, as in the bar. A device without a battery is left out: the built-in keyboard, a
     * wired mouse, a receiver that passes no level on.
     */
    fun devices(seen: List<Seen>): List<Device> {
        val byDescriptor = LinkedHashMap<String, MutableList<Seen>>()
        // Without a descriptor nothing says that two of them are one: each stands for itself.
        seen.forEachIndexed { i, s -> byDescriptor.getOrPut(s.descriptor.ifEmpty { "\u0000$i" }) { ArrayList() } += s }
        return order(byDescriptor.values.mapNotNull { parts ->
            val batteries = parts.filter { it.present }.map { percent(it.capacity) to charging(it.status) }
            if (batteries.isEmpty()) return@mapNotNull null
            val known = batteries.filter { it.first != null }
            val (percent, charging) = known.filter { !it.second }.minByOrNull { it.first ?: 0 } ?: known.minByOrNull { it.first ?: 0 }
                ?: (null to batteries.any { it.second })
            Device(
                descriptor = parts.first().descriptor,
                name = parts.map { NowPlayingRules.oneLine(it.name, NAME_MAX) }.filter { it.isNotEmpty() }.minByOrNull { it.length }.orEmpty(),
                kind = kind(parts.fold(0) { all, p -> all or p.sources }, parts.maxOf { it.keyboardType }),
                percent = percent,
                charging = charging,
            )
        })
    }

    /** The menu's order: the lowest level first; devices without a level last, by name. */
    fun order(devices: List<Device>): List<Device> =
        devices.sortedWith(compareBy<Device> { it.percent == null }.thenBy { it.percent ?: 0 }.thenBy { it.name.lowercase() }.thenBy { it.descriptor })

    /**
     * The device the bar shows: the lowest one that is not charging, so that a mouse charging at 8%
     * doesn't hide a keyboard dying at 12%; if all are charging, the lowest of them. A device that
     * doesn't say its level never counts. Null: there is none to show.
     */
    fun shown(devices: List<Device>): Device? {
        val known = devices.filter { it.percent != null }
        return known.filter { !it.charging }.minByOrNull { it.percent ?: 0 } ?: known.minByOrNull { it.percent ?: 0 }
    }

    /** Low: under 20% and not charging. The menu's row is drawn in the error color then, and the sentence says "low". */
    fun low(device: Device): Boolean = device.percent != null && device.percent < WARN_BELOW && !device.charging

    /** The device's name as it is shown; a device that has none is called by the word for that. */
    fun name(device: Device, words: Words): String = device.name.ifEmpty { words.unnamed() }

    /** A level as the bar and the menu write it: "85%". */
    fun percentText(percent: Int): String = com.meridian11.books.bentobar.util.Fmt.percent(percent)

    /** One device as a sentence: the bar's spoken description, and its row's in the menu. */
    fun desc(device: Device, words: Words): String {
        val name = name(device, words)
        val percent = device.percent ?: return words.unknown(name)
        return when {
            device.charging -> words.charging(name, percent)
            percent < WARN_BELOW -> words.low(name, percent)
            else -> words.level(name, percent)
        }
    }

    /**
     * The item in the bar, for [devices] in the menu's order. It comes out while the device shown is
     * below [lowPct] (the rule's number) and not charging; the tones keep their own 20% and 10%, so
     * with the rule at 50% a device at 35% comes out in the normal tone. With no level to show it is
     * the outlined mouse without text, and says what is known: that no device reports a battery, or
     * that the one there is doesn't say its level.
     */
    fun bar(devices: List<Device>, lowPct: Int, words: Words): Bar {
        val device = shown(devices)
        val percent = device?.percent ?: return Bar(Sym.MOUSE, filled = false, text = null, tone = Tone.NORMAL, active = false,
            desc = devices.firstOrNull()?.let { desc(it, words) } ?: words.none(), tooltip = null)
        val tone = when {
            device.charging -> Tone.NORMAL
            percent < ALERT_BELOW -> Tone.ALERT
            percent < WARN_BELOW -> Tone.WARN
            else -> Tone.NORMAL
        }
        return Bar(device.kind.glyph, filled = true, text = percentText(percent), tone = tone, active = !device.charging && percent < lowPct,
            desc = desc(device, words), tooltip = name(device, words))
    }

    /**
     * Whether a reading of this age is too old to show again when the bar comes back: more than two
     * of the minute's readings old. A short absence keeps the list (no blink in the bar while it is
     * read again); after a long one a device may be long gone.
     */
    fun tooOld(ageMs: Long?): Boolean = ageMs != null && ageMs > 2 * EVERY_MS

    private val STAGED_KINDS = mapOf("mouse" to Kind.MOUSE, "keyboard" to Kind.KEYBOARD, "stylus" to Kind.STYLUS, "gamepad" to Kind.GAMEPAD, "controller" to Kind.GAMEPAD)
    private val STAGED_NAMES = mapOf(Kind.MOUSE to "Mouse", Kind.KEYBOARD to "Keyboard", Kind.STYLUS to "Stylus", Kind.GAMEPAD to "Controller")

    /**
     * The test hook's devices, from its words: `mouse=15 keyboard=85c stylus=?` (c: charging; ?: a
     * battery without a level, also written `unknown`, which no shell takes for a pattern), with a
     * name of its own after a colon where one is wanted, underscores for its spaces
     * (`mouse:MX_Master_3S=8`). `none`: no device at all. Null: not understood.
     */
    fun staged(tokens: List<String>): List<Device>? {
        if (tokens == listOf("none")) return emptyList()
        if (tokens.isEmpty()) return null
        return order(tokens.mapIndexed { i, token ->
            val what = token.substringBefore('=', "")
            val level = token.substringAfter('=', "")
            val kind = STAGED_KINDS[what.substringBefore(':').lowercase()] ?: return null
            val percent = when (val number = level.removeSuffix("c")) {
                "?", "unknown" -> null
                else -> number.toIntOrNull()?.takeIf { it in 0..100 } ?: return null
            }
            val name = NowPlayingRules.oneLine(what.substringAfter(':', "").replace('_', ' '), NAME_MAX)
            Device("staged:$i", name.ifEmpty { STAGED_NAMES.getValue(kind) }, kind, percent, charging = level.endsWith('c'))
        })
    }
}

/**
 * When the devices are read besides once a minute: at once when Android says that one was connected,
 * changed or removed, and once more a moment later, when a device that has just connected knows its
 * level. [read] asks for a reading and says whether it was taken; one that was held back (the loader
 * takes no second one within a second) is asked for again on the next [tick]. Pure: the item gives
 * it the loader and the clock.
 */
class DevicesWatch(private val read: () -> Boolean) {
    private var pending = false
    private var changedAt = NEVER

    /** Android reported a device connected, changed or removed. */
    fun changed(now: Long) {
        changedAt = now
        pending = !read()
    }

    /** Once a second while an item is live. */
    fun tick(now: Long) {
        if (pending) pending = !read()
        else if (changedAt != NEVER && now - changedAt !in 0 until DevicesRules.SETTLE_MS) { changedAt = NEVER; pending = !read() }
    }

    /** No item is live any more: nothing is left to read. */
    fun clear() { pending = false; changedAt = NEVER }

    private companion object { const val NEVER = Long.MIN_VALUE }
}
