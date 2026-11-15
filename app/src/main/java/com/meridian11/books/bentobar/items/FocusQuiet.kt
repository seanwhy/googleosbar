package com.meridian11.books.bentobar.items

import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenDeviceEffects
import android.util.Log
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.Uses
import com.meridian11.books.bentobar.ui.MainActivity
import com.meridian11.books.bentobar.util.Now

/** Do Not Disturb as Focus uses it ([FocusKeeper]). [FocusQuietOnDevice] is Android's; a test gives a fake. */
interface FocusQuiet {
    /** BentoBar may switch Do Not Disturb (Android's "Do Not Disturb access"). */
    fun granted(): Boolean
    /** Switches it on, with the dark theme where Android has it; false: refused, or it did not come on. Never throws. */
    fun on(dark: Boolean): Boolean
    /** Switches it off. Never throws. */
    fun off()
    /** Whether it is on now; null when Android can't say (below Android 15) or wouldn't. Never throws. */
    fun isOn(): Boolean?
    /** Takes BentoBar's mode out of Android's list. Never throws. */
    fun remove()
}

/**
 * Focus on Android: one mode of BentoBar's own in Android's list (an `AutomaticZenRule`, "BentoBar Focus"), priority
 * only, which a session switches on and off. Android remembers it across a reboot, so it is made once and found again by
 * its condition. With Android 15 and up the dark theme can come with it (its device effects), and Android says whether it
 * is on; the user may switch it off there, which ends the session (`FocusRules.seen`).
 */
internal object FocusQuietOnDevice : FocusQuiet {
    private val app: Context get() = Env.app

    /** BentoBar's own condition, as Android makes one for an app ("condition://<package>/focus"): how the mode is found again. */
    private val conditionId: Uri by lazy { Condition.newId(app).appendPath("focus").build() }

    private fun nm(): NotificationManager? = app.getSystemService(NotificationManager::class.java)

    override fun granted(): Boolean = Accesses.granted(app, Access.DO_NOT_DISTURB)

    /** The mode's id among BentoBar's own, or null: there is none (never made, or the user deleted it). */
    private fun find(nm: NotificationManager): String? = nm.automaticZenRules.entries.firstOrNull { it.value.conditionId == conditionId }?.key

    override fun on(dark: Boolean): Boolean = try {
        val nm = nm()
        if (nm == null || !nm.isNotificationPolicyAccessGranted) false
        else {
            val id = find(nm)?.also { fit(nm, it, dark) } ?: nm.addAutomaticZenRule(rule(dark))
            nm.setAutomaticZenRuleState(id, condition(Condition.STATE_TRUE))
            // A mode the user turned off in Android's list (not deleted) takes the switch and stays off: say so.
            isOn() != false
        }
    } catch (e: Exception) {
        Log.w(Env.TAG, "can't switch Focus on: ${e.javaClass.simpleName}")
        false
    }

    override fun off() {
        try {
            val nm = nm() ?: return
            val id = find(nm) ?: return
            nm.setAutomaticZenRuleState(id, condition(Condition.STATE_FALSE))
        } catch (e: Exception) {
            Log.w(Env.TAG, "can't switch Focus off: ${e.javaClass.simpleName}")
        }
    }

    override fun isOn(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        return try {
            val nm = nm() ?: return null
            val id = find(nm) ?: return false
            when (nm.getAutomaticZenRuleState(id)) {
                Condition.STATE_TRUE -> true
                Condition.STATE_FALSE -> false
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun remove() {
        try {
            val nm = nm() ?: return
            if (!nm.isNotificationPolicyAccessGranted) return
            find(nm)?.let { nm.removeAutomaticZenRule(it) }
        } catch (e: Exception) {
            Log.w(Env.TAG, "can't remove the Focus mode: ${e.javaClass.simpleName}")
        }
    }

    private fun name() = app.getString(R.string.focus_rule_name)

    /** The mode as BentoBar makes it: priority only, BentoBar's window as its settings, the dark theme with it where Android has that. */
    private fun rule(dark: Boolean): AutomaticZenRule {
        val settings = ComponentName(app, MainActivity::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
            AutomaticZenRule.Builder(name(), conditionId).setConfigurationActivity(settings)
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                .setDeviceEffects(ZenDeviceEffects.Builder().setShouldUseNightMode(dark).build()).build()
        else AutomaticZenRule(name(), null, settings, conditionId, null, NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
    }

    /** The mode that is there, with the dark theme as this session wants it (Android 15 and up); the rest is as the user left it. */
    private fun fit(nm: NotificationManager, id: String, dark: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val r = nm.getAutomaticZenRule(id) ?: return
        val effects = r.deviceEffects
        if ((effects?.shouldUseNightMode() == true) == dark) return
        val changed = (if (effects != null) ZenDeviceEffects.Builder(effects) else ZenDeviceEffects.Builder()).setShouldUseNightMode(dark).build()
        nm.updateAutomaticZenRule(id, AutomaticZenRule.Builder(r).setDeviceEffects(changed).build())
    }

    /**
     * A switch made by the user: the click that starts a session, and its end, which they chose with its length. From
     * Android 15 on a mode switched by the app alone may stay as the user last left it by hand; one the user switches goes.
     */
    private fun condition(state: Int): Condition =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) Condition(conditionId, name(), state, Condition.SOURCE_USER_ACTION)
        else Condition(conditionId, name(), state)
}

/**
 * The alarm at a session's end ([FocusEnd]), for when nothing else is there to notice it: the bar hidden, the screen off,
 * the service off. Its own request code and action, beside the timer's. The wall clock, which the session keeps: a clock
 * change moves its end and its alarm alike. Exact when the user allowed exact alarms in Setup, as the timer's.
 */
internal object FocusAlarm {
    private const val REQUEST = 65

    fun arm(context: Context, at: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(context, REQUEST, Intent(context, FocusEnd::class.java).setAction(FocusEnd.END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        if (at == null) return
        // [at] is on the clock everything newer reads (a debug build's hook can move it): the alarm waits as long.
        val wall = System.currentTimeMillis() + (at - Now.wall())
        try {
            if (am.canScheduleExactAlarms() && Uses.on(Uses.EXACT_ALARMS)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wall, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wall, pi)
        } catch (e: SecurityException) {
            Log.w(Env.TAG, "Focus alarm not allowed: ${e.javaClass.simpleName}")
        }
    }
}
