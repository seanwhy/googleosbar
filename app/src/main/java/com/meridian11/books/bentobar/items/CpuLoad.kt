package com.meridian11.books.bentobar.items

/**
 * Each core's load from its idle-state residency, one reading at a time (pure; [CpuSampler] feeds it).
 *
 * The kernel adds an idle period to a core's counters only when the core wakes from it. A core that sleeps through a
 * reading has added nothing yet, so the reading takes it for busy; when it wakes, the whole sleep lands in that one
 * reading, often more than the reading's own length. Clamping each reading to 0..1 kept the busy readings and threw
 * that surplus away, so a quiet device read several times its real load (a 12-core Googlebook: 17.7% where
 * /proc/stat said 2.0%). Here what a reading could not show is owed to the next ones instead: over any stretch the
 * readings add up to what the counters say, and each one still stays within 0..1. A core that never sleeps (one
 * thread spinning on it) has no idle period to wait for and reads busy at once, as it is.
 */
class CpuLoad(private val cores: Int) {
    private var last: LongArray? = null
    private var lastAt = 0L
    /** For each core, idle time (µs) its counters reported beyond what its readings could show, still to be shown. */
    private val owed = DoubleArray(cores)

    var perCore = DoubleArray(cores); private set
    var total = 0.0; private set

    /**
     * Takes each core's idle time so far ([idle], µs, the sum over its idle states) at [at] (µs, a clock that runs while
     * the device sleeps). False when there is no new reading: the first call, or one too soon or too long after the last
     * (the sampler stopped meanwhile); either way what was owed is let go.
     */
    fun add(at: Long, idle: LongArray): Boolean {
        val before = last
        val dt = (at - lastAt).toDouble()
        last = idle.copyOf()
        lastAt = at
        if (cores == 0 || before == null || before.size != cores || idle.size != cores || dt < MIN_US || dt > MAX_US) {
            owed.fill(0.0)
            return false
        }
        perCore = DoubleArray(cores) { i ->
            val slept = idle[i] - before[i]
            if (slept < 0) {
                // A counter that went back (the core's idle driver started again) says nothing about this reading.
                owed[i] = 0.0
                return@DoubleArray perCore[i]
            }
            val busy = dt - slept - owed[i]
            val shown = (busy / dt).coerceIn(0.0, 1.0)
            // Only a sleep longer than what is left of the reading leaves something owed; never more than a few
            // seconds, so a core parked for minutes does not then read idle for minutes once it works.
            owed[i] = (shown * dt - busy).coerceIn(0.0, MAX_OWED_US)
            shown
        }
        total = perCore.average()
        return true
    }

    companion object {
        const val MIN_US = 200_000.0
        const val MAX_US = 10_000_000.0
        /** The longest sleep seen across readings on a quiet Googlebook was about four seconds. */
        const val MAX_OWED_US = 5_000_000.0
    }
}
