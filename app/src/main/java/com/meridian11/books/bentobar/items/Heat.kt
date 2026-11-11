package com.meridian11.books.bentobar.items

import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.StringRes
import com.meridian11.books.bentobar.R

/**
 * How hot Android says the device is, in one place for the Heat item and for the CPU item's rule:
 * the thermal status, the battery's temperature and the heat level, each as Android reports it or as
 * a test staged it (debug builds: `./bento debug heat stage 3`), so that a staged status moves both
 * items.
 *
 * The status and the temperature are the battery sampler's readings; the ticker takes them for the
 * types that name that sampler in their `samples`. The one thing asked here is the heat level, and
 * only from [sample], which is the Heat item's once-a-second call while one is live.
 */
object Heat {
    /** The existing words for Android's statuses, by [HeatRules.step]: the menu's subtitle, the tooltip, the spoken line. */
    private val THERMAL = intArrayOf(R.string.battery_thermal_normal, R.string.battery_thermal_warm, R.string.battery_thermal_moderate,
        R.string.battery_thermal_severe, R.string.battery_thermal_critical, R.string.battery_thermal_shutdown)

    // What the test hook staged, shown instead of Android's until `heat off`; null: nothing staged.
    @Volatile private var stagedStatus: Int? = null
    /** Not a number: "this device reports no heat level". */
    @Volatile private var stagedLevel: Float? = null
    @Volatile private var stagedTempC: Double? = null

    /** Main thread only, as everything that changes here. */
    private val log = HeatLevel()

    /** Android's thermal status: 0 (none), 1 light, 2 moderate (it starts to slow the device), 3 severe, up to 6 (shutdown). */
    fun status(): Int = stagedStatus ?: Env.battery.thermal

    /** The battery's temperature in °C. 0: the battery doesn't say. */
    fun tempC(): Double = stagedTempC ?: Env.battery.tempC

    /** The heat level: 1 is where Android slows the device down noticeably, and it can go beyond. Null on a device that reports none. */
    val level: Float? get() = log.level

    /** The levels of the last five minutes, one every five seconds, the oldest first. */
    fun chart(): List<Double> = log.chart()

    /** [status] in words: "Normal", "Hot, slowing a little". */
    @StringRes fun words(status: Int): Int = THERMAL[HeatRules.step(status)]

    /**
     * The Heat item's call, once a second while one is live: asks Android for the heat level when
     * five seconds have passed since the last time. By the real clock, not the one a test can move:
     * the five seconds are Android's limit on the call, not an age that anything shows.
     */
    fun sample() {
        val now = SystemClock.elapsedRealtime()
        if (log.due(now)) log.took(stagedLevel ?: read(), now)
    }

    /**
     * Android's thermal headroom, as it is now: a quick call into the system, like the samplers'.
     * Not a number on a device that reports none; whatever the call throws counts as that too.
     */
    private fun read(): Float = runCatching { Env.app.getSystemService(PowerManager::class.java)?.getThermalHeadroom(0) }.getOrNull() ?: Float.NaN

    /** No Heat item is live any more: the chart starts over with the next one. */
    fun idle() = log.idle()

    /**
     * One reading of the battery's sampler, now. For a menu that shows the status and can open while
     * no item had that sampler running: what it last read could be from long ago. Main thread.
     */
    fun look() { runCatching { Env.battery.sample(Env.app, SystemClock.elapsedRealtime()) } }

    /**
     * The test hook (debug builds): `stage 0..6` a thermal status, `level 0.84` a heat level (`level
     * none`: a device that reports none), `temp 41.3` a battery temperature (`temp 0`: a battery that
     * says nothing), `off` ends all three. Without a word: what is shown now.
     */
    fun debug(args: List<String>): String? {
        val value = args.getOrNull(1)
        when (args.firstOrNull()) {
            null -> return line()
            "stage" -> stagedStatus = value?.toIntOrNull()?.takeIf { it in HeatRules.NONE..HeatRules.SHUTDOWN } ?: return null
            "level" -> {
                val level = if (value == "none") Float.NaN else value?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: return null
                stagedLevel = level
                // At once, not at the next asking: a staged number is the level, and "none" takes the level away.
                if (level.isNaN()) log.forget() else log.took(level, SystemClock.elapsedRealtime())
            }
            "temp" -> stagedTempC = value?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            "off" -> {
                if (stagedLevel != null) log.forget()
                stagedStatus = null; stagedLevel = null; stagedTempC = null
            }
            else -> return null
        }
        Ticker.refresh()
        return line()
    }

    private fun line(): String {
        fun mark(staged: Any?) = if (staged != null) " (staged)" else ""
        return "status=${status()}${mark(stagedStatus)} level=${level?.let { HeatRules.percentText(it) } ?: "none"}${mark(stagedLevel)} " +
            "temp=${tempC()}${mark(stagedTempC)}"
    }
}
