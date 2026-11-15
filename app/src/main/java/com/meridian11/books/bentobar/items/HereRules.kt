package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig

/**
 * What My location knows, as one value: where the device is as Android said it ([fix]) and from when
 * ([foundAt]: the fix's own time, which can be older than the moment it came), why there is no fix
 * while there is none ([why]), when Android was last asked ([askedAt]; null: not since the last wake)
 * and how many asks in a row found nothing new ([misses]). Times are the time since boot.
 */
data class Here(val fix: Fix? = null, val foundAt: Long = 0, val why: Locate = Locate.FINDING, val askedAt: Long? = null, val misses: Int = 0) {
    /** For logs and dumps: never where the device is. */
    override fun toString() = "Here(fix=${fix != null}, why=$why, misses=$misses)"
}

/**
 * My location's rules, on a [Here]: when the permission and the location switch are looked at, when
 * Android is asked, how long a fix is good for and what each answer makes of it; and whether the
 * layout wants where the device is at all. Pure: [WeatherHere] asks Android and does what these say,
 * as `ColorWatch` does for the bar's colors.
 */
object HereRules {
    private const val MIN_MS = 60_000L

    /** Whether location is allowed and on is looked at no more often than this (and at once after a wake). */
    const val LOOK_MS = 5_000L

    /** Android is asked again when a fix is this old: as a reading is asked for again. */
    const val EVERY_MS = WeatherRules.FRESH_MS

    /**
     * A fix is good for its half hour and the few minutes the next one may take to come; older (a night
     * with the screen off), it goes, so that no forecast is asked for where the device was then.
     */
    const val FIX_MS = EVERY_MS + 5 * MIN_MS

    /** After an ask that found nothing new: asked again after 1, 2, 5 and 15 minutes, then every half hour. */
    val BACK_OFF_MS = listOf(1L, 2L, 5L, 15L, 30L).map { it * MIN_MS }

    /** Whether to look at [now], having last looked at [lookedAt] (null: not since the last wake). */
    fun looks(now: Long, lookedAt: Long?): Boolean = lookedAt == null || now - lookedAt >= LOOK_MS

    /** What a look makes of a [Here]: what is known now, and whether Android is to be asked. */
    class Look(val here: Here, val ask: Boolean)

    /**
     * A look at [now]: [allowed], the permission; [on], location switched on; [asking], an ask on its
     * way. Not allowed: nothing is known (it was refused, or taken back). Location off: a fix within
     * its time stays, the weather of where the device was a moment ago being still the right weather;
     * without one, that is why. Otherwise a fix past its time goes, and Android is asked when [due].
     */
    fun look(h: Here, now: Long, allowed: Boolean, on: Boolean, asking: Boolean): Look {
        if (!allowed) return Look(Here(why = Locate.NOT_ALLOWED), ask = false)
        val fresh = fresh(h, now)
        if (!on) return Look(if (fresh.fix == null) fresh.copy(why = Locate.OFF) else fresh, ask = false)
        return Look(fresh, ask = !asking && due(fresh, now))
    }

    /**
     * [h] at [now], with a fix past its time ([FIX_MS]) gone. What the source is given is this, never an
     * older fix. When the asks since found nothing, that is why there is none now.
     */
    fun fresh(h: Here, now: Long): Here =
        if (h.fix != null && now - h.foundAt > FIX_MS) h.copy(fix = null, foundAt = 0, why = if (h.misses > 0) Locate.NONE else Locate.FINDING) else h

    /**
     * Whether Android is to be asked at [now]: not asked since the last wake; with a fix, once it is
     * [EVERY_MS] old, and after asks that found nothing new, as [backOff] says; without one after Android
     * had none, as [backOff] says too; else (a fix that went, location back on) at once.
     */
    fun due(h: Here, now: Long): Boolean {
        val asked = h.askedAt ?: return true
        return when {
            h.fix != null -> now - h.foundAt >= EVERY_MS && now - asked >= backOff(h.misses)
            h.why == Locate.NONE -> now - asked >= backOff(h.misses)
            else -> true
        }
    }

    /** How long after the [misses]th ask in a row that found nothing Android is asked again. */
    fun backOff(misses: Int): Long = BACK_OFF_MS[(misses - 1).coerceIn(0, BACK_OFF_MS.lastIndex)]

    /**
     * Android is being asked, at [now]. Without a fix, a first ask is [Locate.FINDING] ("Loading…"); an
     * ask after Android had none keeps [Locate.NONE], so the bar and an open menu don't change for each
     * ask of the back-off (on a Googlebook on Ethernet that is forever).
     */
    fun asked(h: Here, now: Long): Here =
        h.copy(askedAt = now, why = if (h.fix == null && h.why != Locate.NONE) Locate.FINDING else h.why)

    /**
     * Android said where the device is: [fix], taken at [at]. One already past its time counts as none;
     * one that is [EVERY_MS] old already (the same old fix again) is kept, but is nothing new.
     */
    fun found(h: Here, fix: Fix, at: Long, now: Long): Here =
        if (now - at > FIX_MS) none(h)
        else h.copy(fix = fix, foundAt = at, why = Locate.FINDING, misses = if (now - at >= EVERY_MS) h.misses + 1 else 0)

    /** Android had no location: one miss more; a fix known stays for its time, and without one, that is why. */
    fun none(h: Here): Here = if (h.fix != null) h.copy(misses = h.misses + 1) else h.copy(why = Locate.NONE, misses = h.misses + 1)

    /** The user did something that may let a place be found now (answered the permission, chose My location): without a fix, Android is asked at once. */
    fun woken(h: Here): Here = if (h.fix == null) h.copy(askedAt = null) else h

    /**
     * Whether where the device is is wanted: for an item of My location, while the Weather switch is
     * on. Only then is Android asked and a fix kept; else it is forgotten, with an ask on its way.
     */
    fun wanted(items: List<ItemConfig>, on: Boolean): Boolean =
        on && items.any { Items.of(it.type)?.usesMyLocation == true && WeatherRules.here(it) }
}
