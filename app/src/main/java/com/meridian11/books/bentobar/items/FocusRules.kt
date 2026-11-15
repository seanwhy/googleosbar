package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.util.Fmt

/**
 * Focus without Android (`FocusRulesTest`): a session of Do Not Disturb, how long it has left, when it is over, what each
 * road to its end does with it, and what a click does. Times are wall-clock milliseconds, as they are kept: a restart, the
 * alarm and the tick read the same clock.
 */
object FocusRules {
    /** One session BentoBar switched on. [endsAt] 0: until it is turned off. [dark]: with the dark theme (Android 15 and up). */
    data class Session(val startedAt: Long, val endsAt: Long, val dark: Boolean)

    private const val MINUTE = 60_000L

    /** What "+10 min" adds. */
    const val MORE = 10

    /** A session of [minutes] from [now]; 0 minutes: until it is turned off. */
    fun start(now: Long, minutes: Int, dark: Boolean): Session = Session(now, if (minutes <= 0) 0L else now + minutes * MINUTE, dark)

    /** Time left, never below 0; null when it has no end. */
    fun left(s: Session, now: Long): Long? = if (s.endsAt == 0L) null else (s.endsAt - now).coerceAtLeast(0L)

    fun over(s: Session?, now: Long): Boolean = s != null && s.endsAt != 0L && now >= s.endsAt

    /** [minutes] more; a session with no end has nothing to move. */
    fun extend(s: Session, minutes: Int): Session = if (s.endsAt == 0L) s else s.copy(endsAt = s.endsAt + minutes * MINUTE)

    /** The bar's words: time left as the timer's face has it ("24:10", "1:02:00"), or [on] for a session with no end. */
    fun text(s: Session, now: Long, on: String): String = left(s, now)?.let { Fmt.clock(it) } ?: on

    /** What a [Settle] moment does with the session: leave it, end it, or set its alarm again. */
    enum class Then { KEEP, END, REARM }

    /**
     * The item leaving ends a session. The service ending ends one with no end (nothing else would) and one already over; a
     * timed one is left to its alarm, which needs no service. The service starting ends one that is over, or one with no
     * end whose item is no longer [placed]; else the alarm is set again, which a reboot lost.
     */
    fun settle(s: Session?, why: Settle, now: Long, placed: Boolean): Then = when {
        s == null -> Then.KEEP
        why == Settle.LEFT_LAYOUT -> Then.END
        why == Settle.SERVICE_ENDING -> if (s.endsAt == 0L || over(s, now)) Then.END else Then.KEEP
        over(s, now) || (s.endsAt == 0L && !placed) -> Then.END
        else -> Then.REARM
    }

    /**
     * The session as Android shows it: the rule switched off in Android's own settings ([ruleOn] false) is the user ending it
     * there, so it is forgotten and nothing is switched. Null: Android can't say (below Android 15), and it is kept.
     */
    fun seen(s: Session?, ruleOn: Boolean?): Session? = if (ruleOn == false) null else s

    /** A click starts a session, opens the menu while one runs, or, without the access, opens the menu that asks for it. */
    enum class Click { START, MENU, ASK }

    fun click(s: Session?, granted: Boolean): Click = when {
        s != null -> Click.MENU
        !granted -> Click.ASK
        else -> Click.START
    }

    /** The option's word as minutes; 0: until turned off. Anything else is the default's 50. */
    fun minutes(word: String): Int = word.toIntOrNull()?.takeIf { it >= 0 } ?: 50

    /** As kept on disk: three numbers. */
    fun write(s: Session): String = "${s.startedAt} ${s.endsAt} ${if (s.dark) 1 else 0}"

    /** What [write] wrote, or null for anything else. */
    fun read(text: String?): Session? {
        val parts = text?.trim()?.split(' ') ?: return null
        if (parts.size != 3) return null
        val started = parts[0].toLongOrNull() ?: return null
        val ends = parts[1].toLongOrNull() ?: return null
        val dark = when (parts[2]) { "1" -> true; "0" -> false; else -> return null }
        return Session(started, ends, dark)
    }
}
