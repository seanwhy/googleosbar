package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.util.Units
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The rules of the Heat item that need no Android: what the bar shows for each of Android's thermal
 * statuses, when the item comes out and how long it stays, the temperature in the user's unit, and
 * the heat level as a number. Pure Kotlin, unit-tested (`HeatRulesTest`); [HeatHold], [HeatLevel] and
 * the CPU item's [CpuRule] below are too.
 */
object HeatRules {
    // Android's thermal statuses (PowerManager.THERMAL_STATUS_…). Shutdown reads as emergency does.
    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val SEVERE = 3
    const val CRITICAL = 4
    const val EMERGENCY = 5
    const val SHUTDOWN = 6

    /** The item stays this long after the device cooled below its rule's level, so that a status that flickers doesn't flicker the bar. */
    const val HOLD_MS = 60_000L
    /** The heat level is asked this often and never faster: Android refuses calls in quick succession. */
    const val LEVEL_EVERY_MS = 5_000L

    /**
     * The words, from the resources in the app and from the copy deck in the tests. [bar]: the word in
     * the bar for a step from 1 (Warm) to 5 (Too hot). [thermal]: the words of the menu's subtitle, the
     * tooltip and the spoken line, for a step from 0 (Normal) to 5.
     */
    class Words(
        val bar: (step: Int) -> String,
        val thermal: (step: Int) -> String,
        val tempWord: (temp: String, word: String) -> String,
        val desc: (thermal: String) -> String,
        val descTemp: (thermal: String, degrees: Int) -> String,
    )

    /** What the item shows in the bar; its glyph is the thermometer in every state. */
    data class Bar(val text: String?, val tone: Tone, val active: Boolean, val desc: String, val tooltip: String)

    /** Android's status as one of six steps, 0 (none) to 5 (emergency or shutdown); a number Android doesn't define counts as the nearest it does. */
    fun step(status: Int): Int = status.coerceIn(NONE, EMERGENCY)

    fun tone(status: Int): Tone = when (step(status)) {
        NONE, LIGHT -> Tone.NORMAL
        MODERATE, SEVERE -> Tone.WARN
        else -> Tone.ALERT
    }

    /**
     * Whether the item is out: the status is at the rule's [level] or above (1 warm, 2 hot, 3 very
     * hot: Android's light, moderate and severe), or was less than a minute ago. [sinceAtLevel]: how
     * long ago it last was, in milliseconds; null: not while anyone looked.
     */
    fun active(status: Int, level: Int, sinceAtLevel: Long?): Boolean =
        step(status) >= level.coerceIn(LIGHT, SEVERE) || (sinceAtLevel != null && sinceAtLevel in 0 until HOLD_MS)

    /**
     * The item in the bar. With [showTemp] the battery's temperature stands in the word's place while
     * nothing is wrong, and from moderate on the word joins it ("41° · Hot"): a warning tone never
     * stands on a bare number. A battery that says nothing reads 0: then the item is as with Show:
     * State. While the item stays after cooling it has the text and tone of the status it has by then.
     */
    fun bar(status: Int, level: Int, sinceAtLevel: Long?, showTemp: Boolean, tempC: Double, fahrenheit: Boolean, words: Words): Bar {
        val step = step(status)
        val word = if (step == NONE) null else words.bar(step)
        val thermal = words.thermal(step)
        val temp = if (showTemp && tempC != 0.0 && tempC.isFinite()) Units.degrees(tempC, fahrenheit) else null
        val text = when {
            temp == null -> word
            word != null && step >= MODERATE -> words.tempWord(temp, word)
            else -> temp
        }
        return Bar(text, tone(status), active(status, level, sinceAtLevel),
            desc = if (temp != null) words.descTemp(thermal, Units.whole(tempC, fahrenheit)) else words.desc(thermal), tooltip = thermal)
    }

    /** Show: Battery temperature, instead of the state's word. */
    fun showsTemp(item: ItemConfig): Boolean = item.opt("show", "state") == "temp"

    /** The item's "Temperature unit": `c`, `f`, or `system` for anything else. */
    fun unit(item: ItemConfig): String = item.opt("unit", "system").let { if (it == "c" || it == "f") it else "system" }

    /**
     * What Android's thermal headroom says, as a level from 0 up (1 is where Android slows the device
     * down for good; it can go beyond). Null where it is no number: the device reports none, or it
     * was asked too soon.
     */
    fun reading(headroom: Float): Float? = if (!headroom.isFinite()) null else headroom.coerceAtLeast(0f)

    /** A level as a whole percentage, rounded (0.84 is 83.99999 in a float); it can pass 100. */
    fun percent(level: Float): Int = (level * 100).roundToInt()

    fun percentText(level: Float): String = com.meridian11.books.bentobar.util.Fmt.percent(percent(level))

    /** At 100% or beyond, by the number that is shown: the menu draws the level in the error color. */
    fun over(level: Float): Boolean = percent(level) >= 100

    /** The battery's temperature for the menu, with one decimal, in the user's unit: "41.3", "106.3". */
    fun oneDecimal(tempC: Double, fahrenheit: Boolean): String = String.format(Locale.ROOT, "%.1f", if (fahrenheit) Units.toFahrenheit(tempC) else tempC)
}

/**
 * The minute the Heat item stays after cooling: when the status was last at or above each of the
 * rule's three levels. One for all Heat items, whatever level each one's rule has.
 */
class HeatHold {
    private val seenAt = LongArray(HeatRules.SEVERE + 1) { NEVER }

    /** Once a second while an item is live: the status as it is at [now]. */
    fun note(status: Int, now: Long) {
        val step = HeatRules.step(status)
        for (level in HeatRules.LIGHT..HeatRules.SEVERE) if (step >= level) seenAt[level] = now
    }

    /** How long ago the status was last at or above [level], in milliseconds; null: never, or the clock went back. */
    fun since(level: Int, now: Long): Long? {
        val at = seenAt[level.coerceIn(HeatRules.LIGHT, HeatRules.SEVERE)]
        return if (at == NEVER || now < at) null else now - at
    }

    fun clear() = seenAt.fill(NEVER)

    private companion object { const val NEVER = Long.MIN_VALUE }
}

/**
 * Android's heat level as it is read: when it may be asked again, the last number, and the numbers
 * of the last five minutes for the chart (one every five seconds). A reading that fails once or
 * twice (Android answers with no number when asked too soon) keeps the last one; after three in a
 * row the device counts as reporting none, and the menu leaves the level out.
 */
class HeatLevel(private val everyMs: Long = HeatRules.LEVEL_EVERY_MS) {
    var level: Float? = null; private set
    private var history = History(POINTS)
    private var askedAt = NEVER
    private var misses = 0

    /**
     * Whether Android may be asked at [now]: on every fifth of the ticker's seconds. Those are a few
     * milliseconds long or short, so a tick that comes up to half a second early is taken: held to
     * the full five seconds, every other reading would wait for the sixth, and the chart's sixty
     * points would span six minutes. Still nine times Android's own limit of half a second.
     */
    fun due(now: Long): Boolean = askedAt == NEVER || now - askedAt !in 0 until everyMs - EARLY_MS

    /** Android was asked at [now] and answered [reading]. */
    fun took(reading: Float, now: Long) {
        askedAt = now
        val value = HeatRules.reading(reading)
        if (value != null) {
            level = value
            misses = 0
            history.add(value.toDouble())
        } else if (++misses > KEEP_OVER) {
            level = null
            history = History(POINTS)
        }
    }

    fun chart(): List<Double> = history.toList()

    /**
     * No Heat item is live any more. The chart starts over when one is again: its points are five
     * seconds apart or they are none, and a line across the gap would say otherwise. When Android was
     * last asked is kept: it refuses a second call in quick succession, whatever happened between.
     */
    fun idle() { history = History(POINTS) }

    /** The level is gone at once, and its chart with it: a test staged a device that reports none, or ended its staging. */
    fun forget() {
        level = null
        misses = 0
        history = History(POINTS)
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val POINTS = 60
        const val KEEP_OVER = 2
        const val EARLY_MS = 500L
    }
}

/**
 * The CPU item's rule: out at or above its own number, as in 0.8, and with "Also show when the device
 * runs hot" also from Android's moderate status on. Layouts saved before the switch existed don't
 * have its key, which reads as off: for them nothing changes.
 */
object CpuRule {
    /** The option's key. Turned off, the key is removed again, so the layout is as it was. */
    const val ALSO_HOT = "hot"
    /** From here on the load itself is drawn in the warning tone, as in 0.8. */
    private const val HIGH = 0.9

    /** [hot]: the device runs hot and the item says so, with the thermometer in the chip's place. [high]: the load is 90% or more. */
    data class Shown(val active: Boolean, val hot: Boolean, val high: Boolean) {
        val warn: Boolean get() = hot || high
    }

    fun alsoHot(item: ItemConfig): Boolean = item.optBool(ALSO_HOT, false)

    /** [load]: 0 to 1, or null on a device that doesn't share it. [limitPct]: the rule's number. [status]: Android's thermal status. */
    fun shown(load: Double?, limitPct: Int, alsoHot: Boolean, status: Int): Shown {
        val hot = alsoHot && status >= HeatRules.MODERATE
        return Shown(active = hot || (load != null && load >= limitPct / 100.0), hot = hot, high = load != null && load >= HIGH)
    }
}
