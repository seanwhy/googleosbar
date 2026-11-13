package com.meridian11.books.bentobar.items

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.meridian11.books.bentobar.BuildConfig
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.BarService
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.net.Http
import com.meridian11.books.bentobar.util.Now

/**
 * Process-wide context for items: the app context, the accessibility service while it runs,
 * and the shared samplers. [tick] is called once a second by [Ticker] before item states are read.
 */
@SuppressLint("StaticFieldLeak") // application context only
object Env {
    const val TAG = "BentoBar"
    lateinit var app: Context private set

    /** Whether the accessibility service is connected. The service itself stays inside bar/: items never hold it. */
    val running: Boolean get() = BarService.current != null

    val net = NetSampler()
    val cpu = CpuSampler()
    val mem = MemSampler()
    val battery = BatterySampler()
    val storage = StorageSampler()

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        // The words of Fmt's formats, from the app's text: read each time, so a change of language is followed.
        com.meridian11.books.bentobar.util.Fmt.words = com.meridian11.books.bentobar.util.Fmt.Words(
            percent = { str(R.string.common_percent, it) },
            byteUnit = { app.resources.getStringArray(R.array.fmt_units_bytes)[it] },
            rateUnit = { app.resources.getStringArray(R.array.fmt_units_rate)[it] },
            daysHours = { d, h -> str(R.string.fmt_days_hours, d, h) },
            hoursMinutes = { h, m -> str(R.string.fmt_hours_minutes, h, m) },
            minutes = { str(R.string.fmt_minutes, it) },
            seconds = { str(R.string.fmt_seconds, it) },
            aloud = { d, h, m, s ->
                val parts = listOfNotNull(
                    (d.takeIf { it > 0 })?.let { plural(R.plurals.common_days, it.toInt()) },
                    (h.takeIf { it > 0 })?.let { plural(R.plurals.common_hours, it.toInt()) },
                    (m.takeIf { it > 0 })?.let { plural(R.plurals.common_minutes, it.toInt()) },
                    (s.takeIf { it > 0 || (d == 0L && h == 0L && m == 0L) })?.let { plural(R.plurals.common_seconds, it.toInt()) })
                parts.reduce { a, b -> str(R.string.fmt_spoken_pair, a, b) }
            },
        )
        Store.init(app)
        // The switches for the three online services and the user's keys: in the no-backup directory, which
        // Android leaves out of backups and device transfers. Then the rules for going online at all.
        Online.init(app.noBackupFilesDir)
        wireOnline()
        FlightItem.sweepAtStart()
        NowPlaying.init(app)
        // Before Timers: a timer that finished while BentoBar wasn't running ends in init, and its
        // chip update asks the calendar.
        Calendar.init(app) { Ticker.refresh(EventItem.type) }
        com.meridian11.books.bentobar.util.Fonts.init(app)
        Timers.init(app)
        Caffeine.init(app)
        ChargeOptimization.init(app)
    }

    /**
     * When BentoBar may ask an online service, in one place. A request goes out only if all of this
     * holds: the service is switched on for this install (the user set its item up here), an item of
     * a type that uses the service is outside Off, and something that shows items is on screen. And
     * never from the main thread, and not at all without a network.
     */
    private fun wireOnline() {
        Http.allowed = { host ->
            val service = Online.serviceOf(host)
            Online.on(service) && Ticker.running &&
                Store.config.value.items.any { it.section != Section.OFF && Items.of(it.type)?.online == service }
        }
        Http.clock = Now::elapsed
        // The one read of a key for a request: the transport adds it, so no item's code ever holds one.
        Http.key = { host -> Online.key(Online.serviceOf(host)).ifEmpty { null } }
        Http.connected = {
            runCatching {
                val cm = app.getSystemService(ConnectivityManager::class.java)
                val network = cm?.activeNetwork
                Http.usable(network != null, network?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) } == true)
            }.getOrDefault(true)
        }
        Http.onMain = { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() }
        // The host, the path and what came of it, in debug builds only; never a query (a key, a city) and never a reply.
        if (BuildConfig.DEBUG) Http.log = { Log.i(TAG, it) }
        // A service switched off, or its key removed: whatever it sent is dropped, in memory and by the types that kept more.
        Online.onOff = { service ->
            Background.onMain {
                Background.forget(service)
                Items.all.filter { it.online == service }.forEach { type ->
                    // Only the kind of failure: these types handle a city, a flight number and a key.
                    try { type.forgetFetched() } catch (e: Exception) { Log.w(TAG, "forgetting what ${type.type} fetched failed: ${e.javaClass.simpleName}") }
                }
                Ticker.refresh()
            }
        }
    }

    /** Samples only what the configured items use ([types]); menus ask for theirs while open. */
    fun tick(now: Long, types: Set<String>) {
        if ("network" in types) net.sample(now)
        if ("cpu" in types) cpu.sample(now)
        if ("memory" in types) mem.sample(app, now)
        if ("battery" in types) battery.sample(app, now)
        if ("storage" in types) storage.sample(now)
    }

    /** Starts an activity from a non-activity context. [quiet]: no toast when nothing can open it (the caller has a fallback). */
    fun launch(intent: Intent, quiet: Boolean = false): Boolean = try {
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no activity for $intent")
        if (!quiet) Toast.makeText(app, app.getString(R.string.toast_nothing_can_open), Toast.LENGTH_SHORT).show()
        false
    } catch (e: Exception) {
        // Not allowed (SecurityException), or a link Android refuses to hand over, such as a file://
        // one typed into a Text item (FileUriExposedException): a click must never take the bar down.
        Log.w(TAG, "can't open $intent", e)
        if (!quiet) Toast.makeText(app, app.getString(R.string.toast_nothing_can_open), Toast.LENGTH_SHORT).show()
        false
    }

    /** A string in the app's language, for code outside Compose (item states, notifications, tiles). */
    fun str(@StringRes id: Int): String = app.getString(id)
    fun str(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)

    /** A plural string; the count is also the first format argument unless [args] are given. */
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        if (args.isEmpty()) app.resources.getQuantityString(id, count, count) else app.resources.getQuantityString(id, count, *args)

    fun global(action: Int): Boolean = BarService.global(action)

    fun toggleChargeOptimization(): Boolean = BarService.toggleChargeOptimization()

    /** Puts [text] on the clipboard (a flight's status, the times of a planned moment). */
    fun copy(text: String) {
        runCatching { app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(app.getString(R.string.app_name), text)) }
            .onFailure { Log.w(TAG, "can't copy: ${it.javaClass.simpleName}") }
    }

    /**
     * Installed from a download rather than from Google Play: Android then guards some switches
     * (accessibility, notification access) as "restricted settings" until the user allows them in App
     * info, and the screens that send someone to such a switch say how.
     */
    fun sideloaded(): Boolean = runCatching {
        app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName != "com.android.vending"
    }.getOrDefault(true)

    /**
     * Advanced Protection (Android 17) turns off accessibility services that aren't assistive
     * tools, BentoBar's included; its chip and tiles keep working.
     */
    fun advancedProtection(): Boolean = android.os.Build.VERSION.SDK_INT >= 36 && runCatching {
        app.getSystemService(android.security.advancedprotection.AdvancedProtectionManager::class.java)?.isAdvancedProtectionEnabled == true
    }.getOrDefault(false)

    private var powerSaveAt = 0L
    private var powerSave = false

    /** Battery saver, re-read at most every 30 s. Items then refresh every 2 s instead of 1. */
    fun powerSave(now: Long): Boolean {
        if (now - powerSaveAt > 30_000) {
            powerSaveAt = now
            powerSave = app.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        }
        return powerSave
    }
}

/** A fixed-size history of samples for the small charts in menus. */
class History(val size: Int = 60) {
    private val values = DoubleArray(size)
    private var count = 0
    private var head = 0
    fun add(v: Double) { values[head] = v; head = (head + 1) % size; if (count < size) count++ }
    fun toList(): List<Double> = List(count) { values[(head - count + it + size) % size] }
    fun last(): Double = if (count == 0) 0.0 else values[(head - 1 + size) % size]
}

/** Device-wide network throughput from TrafficStats (all interfaces since boot). */
class NetSampler {
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L
    var down = 0.0; private set
    var up = 0.0; private set
    val downHistory = History()
    val upHistory = History()
    var rxTotal = 0L; private set
    var txTotal = 0L; private set

    fun sample(now: Long) {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return
        reading(rx, tx, now)
    }

    /**
     * The device's byte counters at [now]. Too soon after the last reading ([MIN_GAP_MS]) a counter step says nothing: 5 KB in
     * 10 ms would read as the 500 KB/s that makes the Network item pop out. It is left for the next reading to measure.
     */
    internal fun reading(rx: Long, tx: Long, now: Long) {
        rxTotal = rx; txTotal = tx
        if (lastRx >= 0 && now - lastAt < MIN_GAP_MS) return
        if (lastRx >= 0) {
            val dt = (now - lastAt) / 1000.0
            // Counters can reset (interface restarts): treat drops as zero.
            down = ((rx - lastRx).coerceAtLeast(0)) / dt
            up = ((tx - lastTx).coerceAtLeast(0)) / dt
            // A gap (screen was off) would average over minutes; start fresh instead.
            if (dt > 5) { down = 0.0; up = 0.0 }
            downHistory.add(down); upHistory.add(up)
        }
        lastRx = rx; lastTx = tx; lastAt = now
    }

    private companion object { const val MIN_GAP_MS = 500L }

    /** "Wi-Fi", "Ethernet", … plus metered state, from the default network. */
    fun describe(context: Context): String = runCatching { describeOrThrow(context) }.getOrDefault(context.getString(R.string.network_menu_title))

    private fun describeOrThrow(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return context.getString(R.string.common_unknown)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return context.getString(R.string.network_kind_offline)
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.string.network_kind_wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> R.string.network_kind_ethernet
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> R.string.network_kind_mobile
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> R.string.network_kind_bluetooth
            caps.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> R.string.network_kind_usb
            else -> R.string.network_kind_connected
        }
        // "Wi-Fi · VPN · metered": separate words joined with the typographic dot.
        return listOfNotNull(
            context.getString(kind),
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) context.getString(R.string.network_kind_vpn) else null,
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) context.getString(R.string.network_kind_metered) else null,
        ).joinToString(" · ")
    }
}

/**
 * Device-wide CPU load. Apps can't read /proc/stat (Android 8+) and SystemHealthManager's CPU
 * headroom is unsupported on Googlebooks, but each core's idle-state residency in sysfs
 * (cpuN/cpuidle/stateM/time, microseconds) is readable: load = 1 − idle time / wall time.
 * Also reads each cluster's clock (cpufreq) and, on Qualcomm, the GPU's busy counter.
 */
class CpuSampler {
    private val root = java.io.File("/sys/devices/system/cpu")
    private var cores = IntArray(0)
    /** Idle-state residency files, kept open: sysfs regenerates a value on each read from offset 0. */
    private var idleFiles: Array<Array<java.io.RandomAccessFile>> = emptyArray()
    private var policies: List<Pair<String, java.io.File>> = emptyList()
    private var maxKhz: List<Long> = emptyList()
    private var lastIdle = LongArray(0)
    private var lastAt = 0L
    private var initialised = false
    private val buf = ByteArray(32)
    /** False when this device doesn't expose idle-state counters. */
    var available = true; private set
    var total = 0.0; private set
    var perCore = DoubleArray(0); private set
    val history = History()
    val gpuHistory = History()
    /** GPU busy fraction, or null when the device doesn't expose it. */
    var gpu: Double? = null; private set
    /** Clocks and GPU are read only while someone looks at them (the CPU menu). */
    @Volatile var detail = 0

    data class Cluster(val cores: String, val curKhz: Long, val maxKhz: Long)
    var clusters: List<Cluster> = emptyList(); private set

    private fun readLong(f: java.io.File): Long? = try {
        f.bufferedReader().use { it.readLine() }?.trim()?.toLongOrNull()
    } catch (e: Exception) { null }

    private fun readLong(f: java.io.RandomAccessFile): Long {
        f.seek(0)
        val n = f.read(buf)
        var v = 0L
        for (i in 0 until n) { val c = buf[i].toInt(); if (c in 48..57) v = v * 10 + (c - 48) else if (v > 0 || c == 10) break }
        return v
    }

    private fun init() {
        initialised = true
        val present = runCatching { java.io.File(root, "present").readText().trim() }.getOrDefault("0")
        cores = present.split(',').flatMap { part ->
            val (a, b) = if ('-' in part) part.split('-').map { it.trim().toInt() } else listOf(part.trim().toInt(), part.trim().toInt())
            (a..b).toList()
        }.toIntArray()
        idleFiles = Array(cores.size) { i ->
            (0 until 8).mapNotNull { st ->
                runCatching { java.io.RandomAccessFile(java.io.File(root, "cpu${cores[i]}/cpuidle/state$st/time"), "r") }.getOrNull()
            }.toTypedArray()
        }
        available = idleFiles.isNotEmpty() && idleFiles.all { it.isNotEmpty() }
        // Clock domains (policies) are fixed: find them once.
        policies = (0 until 32).mapNotNull { p ->
            val dir = java.io.File(root, "cpufreq/policy$p")
            if (!java.io.File(dir, "scaling_cur_freq").exists()) return@mapNotNull null
            val related = runCatching { java.io.File(dir, "related_cpus").readText().trim().split(' ').mapNotNull { it.toIntOrNull() } }
                .getOrDefault(listOf(p))
            val span = if (related.size > 1) "${related.first()}–${related.last()}" else "${related.firstOrNull() ?: p}"
            span to java.io.File(dir, "scaling_cur_freq")
        }
        maxKhz = policies.map { (_, f) -> readLong(java.io.File(f.parentFile, "cpuinfo_max_freq")) ?: 0L }
    }

    fun sample(now: Long) {
        if (!initialised) init()
        if (!available) return
        val t = android.os.SystemClock.elapsedRealtimeNanos() / 1000
        val idle = LongArray(cores.size) { i -> var sum = 0L; for (f in idleFiles[i]) sum += runCatching { readLong(f) }.getOrDefault(0L); sum }
        if (lastAt > 0) {
            val dt = (t - lastAt).toDouble()
            if (dt in 200_000.0..10_000_000.0) {
                perCore = DoubleArray(cores.size) { i -> (1 - (idle[i] - lastIdle[i]) / dt).coerceIn(0.0, 1.0) }
                total = perCore.average()
                history.add(total)
            }
        }
        lastIdle = idle
        lastAt = t
        if (detail > 0) {
            clusters = policies.mapIndexed { i, (span, f) -> Cluster(span, readLong(f) ?: 0L, maxKhz[i]) }
            gpu = runCatching {
                val parts = java.io.File("/sys/class/kgsl/kgsl-3d0/gpubusy").readText().trim().split(Regex("\\s+")).map { it.toDouble() }
                // "busy total" over the last window; an idle GPU reports "0 0".
                if (parts.size < 2) null else if (parts[1] > 0) (parts[0] / parts[1]).coerceIn(0.0, 1.0) else 0.0
            }.getOrNull()
            gpu?.let { gpuHistory.add(it) }
        }
    }
}

class MemSampler {
    var total = 0L; private set
    var avail = 0L; private set
    var low = false; private set
    /** Available memory below which Android counts as low on memory and starts closing apps. */
    var threshold = 0L; private set
    /** The RAM the device is sold with (more than [total], which excludes memory the kernel and hardware keep). */
    var advertised = 0L; private set
    val usedHistory = History()
    val used get() = if (total == 0L) 0.0 else (total - avail).toDouble() / total
    private val info = ActivityManager.MemoryInfo()

    fun sample(context: Context, now: Long) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        am.getMemoryInfo(info)
        total = info.totalMem; avail = info.availMem; low = info.lowMemory
        threshold = info.threshold; advertised = info.advertisedMem
        usedHistory.add(used)
    }
}

class BatterySampler {
    var level = 0.0; private set
    var charging = false; private set
    var plugged = 0; private set
    var status = BatteryManager.BATTERY_STATUS_UNKNOWN; private set
    var voltageMv = 0; private set
    var currentUa = 0L; private set
    var tempC = 0.0; private set
    var health = BatteryManager.BATTERY_HEALTH_UNKNOWN; private set
    var cycles = -1; private set
    var chargeTimeMs = -1L; private set
    var thermal = PowerManager.THERMAL_STATUS_NONE; private set
    val wattHistory = History()
    /** Watts into (+) or out of (−) the battery. */
    var watts = 0.0; private set
    private var lastIntentAt = 0L

    fun sample(context: Context, now: Long) {
        val bm = context.getSystemService(BatteryManager::class.java)
        if (lastIntentAt == 0L || now - lastIntentAt >= 5_000) {
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            lastIntentAt = now
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            level = if (lvl >= 0 && scale > 0) lvl.toDouble() / scale else 0.0
            status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            voltageMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            cycles = i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
            chargeTimeMs = bm?.computeChargeTimeRemaining() ?: -1
        }
        currentUa = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0L
        if (currentUa == Long.MIN_VALUE) currentUa = 0
        // Sign conventions differ between devices; trust the charging status for the direction.
        val w = kotlin.math.abs(currentUa) / 1e6 * (voltageMv / 1000.0)
        watts = if (plugged != 0 && charging) w else -w
        wattHistory.add(kotlin.math.abs(watts))
        thermal = context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: thermal
    }

    fun statusText(): String = Env.str(when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> R.string.battery_status_charging
        BatteryManager.BATTERY_STATUS_DISCHARGING -> R.string.battery_status_discharging
        BatteryManager.BATTERY_STATUS_FULL -> R.string.battery_status_full
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> R.string.battery_status_not_charging
        else -> R.string.common_unknown
    })

    fun sourceText(): String = Env.str(when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> R.string.battery_source_charger
        BatteryManager.BATTERY_PLUGGED_USB -> R.string.battery_source_usb
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> R.string.battery_source_wireless
        BatteryManager.BATTERY_PLUGGED_DOCK -> R.string.battery_source_dock
        0 -> R.string.battery_source_battery
        else -> R.string.battery_source_power
    })

    fun healthText(): String = Env.str(when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> R.string.battery_health_good
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> R.string.battery_health_overheat
        BatteryManager.BATTERY_HEALTH_DEAD -> R.string.battery_health_dead
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> R.string.battery_health_over_voltage
        BatteryManager.BATTERY_HEALTH_COLD -> R.string.battery_health_cold
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> R.string.battery_health_failure
        else -> R.string.common_unknown
    })

    fun thermalText(): String = Env.str(when (thermal) {
        PowerManager.THERMAL_STATUS_NONE -> R.string.battery_thermal_normal
        PowerManager.THERMAL_STATUS_LIGHT -> R.string.battery_thermal_warm
        PowerManager.THERMAL_STATUS_MODERATE -> R.string.battery_thermal_moderate
        PowerManager.THERMAL_STATUS_SEVERE -> R.string.battery_thermal_severe
        PowerManager.THERMAL_STATUS_CRITICAL -> R.string.battery_thermal_critical
        PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> R.string.battery_thermal_shutdown
        else -> R.string.common_unknown
    })
}

class StorageSampler {
    var total = 0L; private set
    var free = 0L; private set
    private var lastAt = 0L

    fun sample(now: Long) {
        if (lastAt != 0L && now - lastAt < 30_000) return
        lastAt = now
        runCatching {
            val fs = StatFs(Environment.getDataDirectory().path)
            total = fs.totalBytes; free = fs.availableBytes
        }
    }

}
