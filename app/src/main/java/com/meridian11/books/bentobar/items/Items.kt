package com.meridian11.books.bentobar.items

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.util.Now

/** Every item type, in catalog order. */
object Items {
    val all: List<ItemType> = listOf(
        CpuItem, NetworkItem, MemoryItem, BatteryItem, StorageItem, HeatItem, DevicesItem, DisplaysItem,
        CalendarItem, EventItem, ClockItem, TimerItem, CountdownItem,
        CaffeineItem, MediaItem, SoundItem, ClipboardItem, CloudClipboardItem, RemoteKeyboardItem, ToolsItem,
        AntigravityItem, CodexItem, GrokItem, GrokBotItem,
        ShortcutItem, FolderItem, AppItem, TextItem, SpacerItem,
        WeatherItem, AirItem, FlightItem, StocksItem, FxItem, AiUsageItem,
    )
    private val byType = all.associateBy { it.type }

    fun of(type: String): ItemType? = byType[type]

    /** The types of [group], in catalog order: what Add lists under its heading. */
    fun inGroup(group: ItemGroup): List<ItemType> = all.filter { it.group == group }
}

/** The app's [TickLoop]: the main thread, its clocks, the [Store], [Items] and [Env]. */
object Ticker : TickLoop(TickerOnDevice.wiring)

private object TickerOnDevice {
    private const val TAG = "BentoBar"
    private val main by lazy { Handler(Looper.getMainLooper()) }

    val wiring = TickLoop.Wiring(
        post = { main.post(it) },
        onMain = { Looper.myLooper() == Looper.getMainLooper() },
        schedule = { delayMs, tick -> main.postAtTime(tick, TAG, SystemClock.uptimeMillis() + delayMs) },
        cancel = { main.removeCallbacksAndMessages(TAG) },
        elapsed = SystemClock::elapsedRealtime,
        wall = System::currentTimeMillis,
        sampleClock = Now::elapsed,
        powerSave = Env::powerSave,
        config = { Store.config.value },
        type = Items::of,
        sampleEnv = Env::tick,
        clocks = { Timers.check(); Caffeine.check() },
        failedText = { Env.str(R.string.item_failed, it.title) },
        log = { what, e -> if (e == null) Log.w(TAG, what) else Log.w(TAG, what, e) },
        location = { items, sampled -> WeatherHere.follow(items, sampled, Online.on(Online.Service.OPEN_METEO)) },
    )
}
