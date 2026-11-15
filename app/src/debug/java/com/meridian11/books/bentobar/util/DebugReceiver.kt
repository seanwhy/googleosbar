package com.meridian11.books.bentobar.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.meridian11.books.bentobar.bar.BarService
import com.meridian11.books.bentobar.data.Defaults
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.putAllIn
import com.meridian11.books.bentobar.data.putInArea
import com.meridian11.books.bentobar.items.Caffeine
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.ItemType
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.items.Ticker
import com.meridian11.books.bentobar.items.Timers
import com.meridian11.books.bentobar.net.Host
import com.meridian11.books.bentobar.net.Http

/**
 * Test hooks for development over adb, in debug builds only (src/debug: release builds don't
 * include them). The receiver requires android.permission.DUMP, which only the shell (adb) and the
 * system hold, so apps on the device can't use it.
 *
 *   adb shell am broadcast -a com.meridian11.books.bentobar.DEBUG -p com.meridian11.books.bentobar --es c 'dump'
 *
 * Hooks for a single item type live with the type (`ItemType.debug`): a command that isn't one of
 * the receiver's own and starts with a type's id goes there (`heat stage 3`, `flight show air`), and
 * `item TYPE …` reaches a type whose id is also a command here (`item timer …`).
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Env.init(context)
        val args = intent.getStringExtra("c").orEmpty().trim().split(Regex("\\s+"))
        if (args[0] == "winshot") return winshot(args.drop(1).joinToString(" ").ifEmpty { "BentoBar menu" })
        val bar = BarService.current?.controller()
        val out = try {
            when (args[0]) {
                // Every window's id, type, title and place. Here, in the debug source set only: the release app reads no window's title
                // but one (see BarService.check), and a test pins that.
                "windows" -> (BarService.current ?: error("service not running")).windows.joinToString(" | ") { w ->
                    val r = android.graphics.Rect().also { w.getBoundsInScreen(it) }
                    "${w.id}:t${w.type}:${w.title}:${r.toShortString()}"
                }
                "cfg" -> Store.export()
                "import" -> { // a whole layout, base64 JSON (e.g. to restore one saved with cfg)
                    val json = String(android.util.Base64.decode(args[1], android.util.Base64.DEFAULT))
                    if (Store.import(json) is com.meridian11.books.bentobar.data.LayoutRead.Ok) "imported" else "not a BentoBar layout"
                }
                "reset" -> { Store.update { Defaults.config().copy(onboarded = it.onboarded) }; "reset" }
                // add TYPE [section] [area]: with no area, the layout's home area.
                "add" -> Store.add(args[1], args.getOrNull(2)?.let { Section.valueOf(it.uppercase()) } ?: Section.SHOWN, area = args.getOrNull(3)?.let { area(it) })
                "remove" -> { Store.remove(args[1]); "ok" }
                "set" -> {
                    val (k, v) = args[2].split('=', limit = 2)
                    val item = Store.config.value.items.firstOrNull { it.id == args[1] || it.type == args[1] } ?: error("no item")
                    when (k) {
                        "section" -> Store.updateItem(item.id) { it.copy(section = Section.valueOf(v.uppercase())) }
                        "area" -> Store.update { it.putInArea(item.id, area(v)) }
                        "whenActive" -> Store.updateItem(item.id) { it.copy(whenActive = v.toBoolean()) }
                        "display" -> Store.updateItem(item.id) { it.copy(display = com.meridian11.books.bentobar.data.Display.valueOf(v.uppercase())) }
                        else -> Store.updateItem(item.id) { it.with(k, v.ifEmpty { null }) }
                    }
                    Ticker.refresh(); "ok"
                }
                "cpuprobe" -> { // which CPU-load sources this app may read
                    val out = StringBuilder()
                    val shm = context.getSystemService(android.os.health.SystemHealthManager::class.java)
                    // Debug builds only (this file is not in release); the headroom calls are API 36 and minSdk is 34, so on an
                    // older device they throw and runCatching reports that, which is the probe's answer.
                    @android.annotation.SuppressLint("NewApi")
                    val cpu = runCatching {
                        out.append("minInterval=${shm.cpuHeadroomMinIntervalMillis} window=${shm.cpuHeadroomCalculationWindowRange} ")
                        out.append("cpuHeadroom=${shm.getCpuHeadroom(android.os.CpuHeadroomParams.Builder().build())} ")
                    }.onFailure { out.append("cpuHeadroom=$it ") }
                    @android.annotation.SuppressLint("NewApi") // as above
                    val gpu = runCatching { out.append("gpuHeadroom=${shm.getGpuHeadroom(android.os.GpuHeadroomParams.Builder().build())} ") }
                        .onFailure { out.append("gpuHeadroom=$it ") }
                    val extra = (args.getOrNull(1)?.split(",") ?: emptyList())
                    for (f in listOf("/proc/stat", "/proc/loadavg", "/proc/uptime", "/proc/pressure/cpu", "/sys/devices/system/cpu/present",
                        "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq",
                        "/sys/devices/system/cpu/cpu0/cpuidle/state0/time", "/sys/devices/system/cpu/cpu0/cpuidle/state1/time",
                        "/sys/devices/system/cpu/cpu0/cpuidle/state0/name", "/sys/devices/system/cpu/cpu5/cpuidle/state2/time",
                        "/sys/devices/system/cpu/cpufreq/policy0/stats/time_in_state", "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                        "/sys/class/kgsl/kgsl-3d0/gpubusy", "/proc/self/stat") + extra) {
                        out.append("| $f: ").append(runCatching { java.io.File(f).bufferedReader().use { it.readLine() }?.take(60) }
                            .getOrElse { "DENIED ${it.javaClass.simpleName}" })
                    }
                    out.toString()
                }
                "state" -> { // what an item shows right now
                    val item = Store.config.value.items.firstOrNull { it.type == args[1] || it.id == args[1] } ?: error("no item")
                    // What a discreet type shows (a track's title) is not for a log: its length says enough for a test.
                    val discreet = Items.of(item.type)?.discreet == true
                    Ticker.stateOf(item).let { "${if (discreet) "text of ${it.text?.length ?: 0}" else it.text} icon=${iconName(it.icon)} active=${it.active} tone=${it.tone}" }
                }
                "now" -> { // the staged clock for everything that reads Now: +3h, +90m, +45s, -2h, or off
                    val a = args.getOrElse(1) { "off" }
                    Now.ahead = if (a == "off") 0 else span(a)
                    Ticker.refresh()
                    "the clock is ${Now.ahead / 1000} s ahead"
                }
                "net" -> { // how many requests went out, per host, and whether each service may be asked right now
                    if (args.getOrNull(1) == "reset") Http.resetCounts()
                    Host.entries.joinToString(" ") { "${it.domain}=${Http.sent(it)}" } + " | " +
                        Online.Service.entries.joinToString(" ") { s ->
                            "${s.id}: on=${Online.on(s)} setUp=${Online.setUp(s)} mayAsk=${Http.allowed(s.hosts.first())}"
                        }
                }
                "online" -> { // online weather|flights|stocks|fx on|off (on only works once the item was set up here, as in Setup)
                    val s = when (args[1]) {
                        "weather" -> Online.Service.OPEN_METEO; "flights" -> Online.Service.AIRLABS; "stocks" -> Online.Service.FINNHUB; "fx" -> Online.Service.FRANKFURTER
                        else -> error("weather, flights, stocks or fx")
                    }
                    if (args.getOrNull(2) == "on") Online.turnOn(s) else Online.turnOff(s)
                    "${s.id} on=${Online.on(s)}"
                }
                "item" -> Items.of(args[1])?.let { ask(it, args.drop(2)) } ?: "no hook for ${args.drop(1).joinToString(" ")}"
                "finish" -> { com.meridian11.books.bentobar.ui.MainActivity.current?.finish(); "ok" }
                "bar" -> { Store.update { it.copy(enabled = args.getOrNull(1) != "off") }; "ok" }
                "look" -> {
                    Store.update { c ->
                        when (args[1]) {
                            "position" -> c.putAllIn(area(args[2])) // every item into one area, which is what a Position was
                            "pill" -> c.copy(pill = com.meridian11.books.bentobar.data.Pill.valueOf(args[2].uppercase()))
                            "color" -> c.copy(color = com.meridian11.books.bentobar.data.ColorMode.valueOf(args[2].uppercase()))
                            "size" -> c.copy(textSize = com.meridian11.books.bentobar.data.TextSize.valueOf(args[2].uppercase()))
                            "chips" -> c.copy(chipMode = com.meridian11.books.bentobar.data.ChipMode.valueOf(args[2].uppercase()))
                            "chevron" -> c.copy(hiddenMode = if (args[2] == "on") com.meridian11.books.bentobar.data.HiddenMode.CLICK else com.meridian11.books.bentobar.data.HiddenMode.SHOW_ALL)
                            "hover" -> c.copy(hiddenMode = if (args[2] == "on") com.meridian11.books.bentobar.data.HiddenMode.HOVER else com.meridian11.books.bentobar.data.HiddenMode.CLICK)
                            "collapse" -> c.copy(autoCollapseSec = args[2].toInt())
                            "spacing" -> c.copy(spacing = args[2].toInt())
                            "switch" -> c.copy(hoverSwitchesPopups = args[2] == "on")
                            "animations" -> c.copy(noAnimations = args[2] == "off")
                            "tint" -> c.copy(systemTint = args[2] == "on")
                            else -> c
                        }
                    }; "ok"
                }
                "timer" -> { // minutes, or seconds with an "s" suffix
                    val a = args.getOrElse(1) { "1" }
                    Timers.startTimer(if (a.endsWith("s")) a.dropLast(1).toLong() * 1000 else a.toLong() * 60_000L); "ok"
                }
                "stopwatch" -> { Timers.startStopwatch(); "ok" }
                "pomodoro" -> { Timers.startPomodoro(); "ok" }
                "stop" -> { Timers.stop(); "ok" }
                "awake" -> { if (args.getOrNull(1) == "off") Caffeine.off() else Caffeine.on(args.getOrNull(1)?.toIntOrNull()); "ok" }
                else -> Items.of(args[0])?.let { ask(it, args.drop(1)) } ?: bar?.debug(args) ?: "bar not running"
            }
        } catch (e: Exception) {
            "failed: $e"
        }
        Log.i("BentoBar", "debug ${args.joinToString(" ")} -> $out")
    }

    /** What the type's hook says to [words], or, for a state it refused, what it can stage: a hook answers null, and the usage is said here. */
    private fun ask(type: ItemType, words: List<String>): String? = type.debug(words)
        ?: type.stages.takeIf { it.isNotEmpty() }?.let { "not staged: no such state, or no such item. It takes: ${it.joinToString(" | ")}" }

    /** An area by the word the user reads; "center" is the stored name, which older scripts send. */
    private fun area(word: String): Position = when (word.lowercase()) {
        "left" -> Position.LEFT
        "middle", "center" -> Position.CENTER
        "right" -> Position.RIGHT
        else -> error("left, middle or right")
    }

    /** "+3h", "+90m", "-45s" as milliseconds. */
    private fun span(text: String): Long {
        val n = text.dropLast(1).toLong()
        return n * when (text.last()) { 'h' -> 3_600_000L; 'm' -> 60_000L; 's' -> 1_000L; else -> error("h, m or s") }
    }

    /** A symbol's name for the log (the glyph itself is a private-use character). */
    private fun iconName(sym: String?): String = if (sym.isNullOrEmpty()) "none" else
        Sym::class.java.declaredFields.firstOrNull { it.type == String::class.java && runCatching { it.get(null) }.getOrNull() == sym }?.name?.lowercase() ?: "?"

    /**
     * One of BentoBar's own windows as a PNG, returned base64 in the broadcast result (for README
     * screenshots). Window screenshots include the window's shadow and transparency but not the
     * mouse pointer, and never show other apps.
     */
    private fun winshot(title: String) {
        val svc = BarService.current ?: run { resultData = "service not running"; return }
        // The settings window (its own surface, without the caption), found by its title: no other
        // app's window content is read to find it.
        val label = svc.applicationInfo.loadLabel(svc.packageManager).toString()
        val w = (if (title == "app") svc.windows.firstOrNull {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.title?.toString() == label
        } else svc.windows.firstOrNull {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.title?.toString() == title
        }) ?: run { resultData = "no BentoBar window titled $title"; return }
        val pending = goAsync()
        svc.takeScreenshotOfWindow(w.id, java.util.concurrent.Executors.newSingleThreadExecutor(),
            object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                    // Full size into the app's cache, and its path as the result: a broadcast result
                    // over about 1 MB fails (TransactionTooLarge) and the broadcast never finishes.
                    // Pull it with `adb exec-out run-as <package> cat cache/winshot.png`.
                    val hb = r.hardwareBuffer
                    try {
                        val bmp = android.graphics.Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        val file = java.io.File(svc.cacheDir, "winshot.png")
                        pending.resultData = if (bmp == null) "failed: no bitmap" else {
                            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            "file:cache/winshot.png ${bmp.width}x${bmp.height}"
                        }
                    } catch (e: Exception) {
                        pending.resultData = "failed: $e"
                    } finally {
                        hb.close()
                        pending.finish()
                    }
                }
                override fun onFailure(code: Int) { pending.resultData = "failed $code"; pending.finish() }
            })
    }
}
