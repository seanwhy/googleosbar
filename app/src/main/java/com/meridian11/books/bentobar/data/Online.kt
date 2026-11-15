package com.meridian11.books.bentobar.data

import androidx.compose.runtime.Immutable
import com.meridian11.books.bentobar.net.Host
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Whether BentoBar may ask an online service, and the user's own key for one that needs it. Five
 * items go online, Weather, Air quality, Exchange rates, Flight and Stocks, and each only after the user
 * set it up on this install: that is what [turnOn] and [saveKey] record, and [turnOff] takes back.
 *
 * All of it belongs to this install and never travels. It is kept in one small file in Android's
 * no-backup directory, beside the layout but no part of it: a pasted layout, a restored backup and a
 * device transfer bring neither a switch nor a key. A key never leaves this object except through
 * [key], for the one request that needs it; it is in no state, no log and no string.
 */
object Online {
    /** The services, each with the hosts it stands for (see [Host]). */
    enum class Service(val id: String, val hosts: Set<Host>, val needsKey: Boolean) {
        OPEN_METEO("openMeteo", setOf(Host.OPEN_METEO, Host.OPEN_METEO_AIR, Host.OPEN_METEO_GEOCODING), needsKey = false),
        AIRLABS("airLabs", setOf(Host.AIRLABS), needsKey = true),
        FINNHUB("finnhub", setOf(Host.FINNHUB), needsKey = true),
        FRANKFURTER("frankfurter", setOf(Host.FRANKFURTER), needsKey = false),
    }

    /**
     * What the screens observe. [setUp]: set up on this install at some time, so Setup's switch can
     * turn it back on. [keyed]: has a key. Never the key itself.
     */
    @Immutable
    data class State(val on: Set<Service> = emptySet(), val setUp: Set<Service> = emptySet(), val keyed: Set<Service> = emptySet())

    @Serializable
    private class Saved(val on: List<String> = emptyList(), val setUp: List<String> = emptyList(), val keys: Map<String, String> = emptyMap())

    private const val FILE = "online.json"
    private val json = Json { ignoreUnknownKeys = true }
    private var file: File? = null
    private val keys = HashMap<Service, String>()
    private val current = MutableStateFlow(State())
    val state: StateFlow<State> get() = current

    /**
     * Told when a service is switched off or loses its key, after its fetched data is gone. The app
     * wires it to the item types that use the service (`ItemType.forgetFetched`).
     */
    @Volatile var onOff: (Service) -> Unit = {}

    /** [noBackupDir]: `Context.getNoBackupFilesDir()`. Reads what was saved; a file that can't be read counts as none. */
    @Synchronized
    fun init(noBackupDir: File) {
        Kept.init(noBackupDir)
        file = File(noBackupDir, FILE)
        keys.clear()
        val saved = try {
            file?.takeIf { it.isFile }?.let { json.decodeFromString(Saved.serializer(), it.readText(Charsets.UTF_8)) }
        } catch (e: Exception) {
            null
        } ?: Saved()
        // A service only a retired item asked (Crypto's Kraken, Value from a URL's "Your own addresses") is no service any
        // more: its switch is read as nothing and the file is written again without it. The directory of what it sent, if
        // there is one, is deleted, and so is what a retired type kept for its items (a Shelf's texts, the addresses of
        // Value from a URL): no code is left that would read or delete them.
        val retired = Retired.services.filter { it in saved.on || it in saved.setUp || it in saved.keys }
        for (id in Retired.services) runCatching { File(noBackupDir, "fetched/$id").deleteRecursively() }
        for (name in Retired.kept) runCatching { File(noBackupDir, "kept/$name").deleteRecursively() }
        fun services(ids: List<String>) = Service.entries.filterTo(HashSet()) { it.id in ids }
        for (s in Service.entries) saved.keys[s.id]?.trim()?.takeIf { it.isNotEmpty() }?.let { keys[s] = it }
        val keyed = keys.keys.toSet()
        // A service that needs a key and has none can't be on, whatever the file says; one that is on was set up here.
        val on = services(saved.on).filterTo(HashSet()) { !it.needsKey || it in keyed }
        current.value = State(
            on = on,
            setUp = services(saved.setUp).filterTo(HashSet()) { !it.needsKey } + keyed + on,
            keyed = keyed,
        )
        if (retired.isNotEmpty()) save(lost = false)
    }

    fun on(service: Service): Boolean = service in current.value.on

    /** Set up on this install: a city was searched or My location chosen (Weather), a key is saved (Flight). */
    fun setUp(service: Service): Boolean = service in current.value.setUp

    fun hasKey(service: Service): Boolean = service in current.value.keyed

    fun serviceOf(host: Host): Service = Service.entries.first { host in it.hosts }

    /**
     * Whether [service] may be asked right now: it is switched on for this install, something that shows items is on
     * screen ([running]), and the layout has an item of a type that asks it ([asks], by the item's type). The one rule
     * behind `Http.allowed`, which the app wires to it.
     */
    fun mayAsk(service: Service, running: Boolean, items: List<ItemConfig>, asks: (type: String) -> Service?): Boolean =
        on(service) && running && items.any { asks(it.type) == service }

    /**
     * On, by the user's own act: Search, Use my location or "Turn on" in the item, or Setup's switch for a
     * service that was set up here. False (and nothing changes) for a service that needs a key and has none.
     */
    @Synchronized
    fun turnOn(service: Service): Boolean {
        if (service.needsKey && service !in keys) return false
        val s = current.value
        if (service in s.on && service in s.setUp) return true
        current.value = s.copy(on = s.on + service, setUp = s.setUp + service)
        save(lost = false)
        return true
    }

    /** Off: requests stop at once, what the service sent is deleted, and its item types are told. A key stays. */
    fun turnOff(service: Service) {
        synchronized(this) {
            val s = current.value
            if (service !in s.on) return
            current.value = s.copy(on = s.on - service)
            save(lost = true)
        }
        forget(service)
    }

    /** The user's key for [service], or "" when there is none. For the request only: never log it, never show it. */
    @Synchronized
    fun key(service: Service): String = keys[service].orEmpty()

    /** Saves the user's key (trimmed) and turns the service on. False for an empty one. */
    @Synchronized
    fun saveKey(service: Service, key: String): Boolean {
        val k = key.trim()
        if (k.isEmpty()) return false
        keys[service] = k
        val s = current.value
        current.value = s.copy(on = s.on + service, setUp = s.setUp + service, keyed = s.keyed + service)
        save(lost = false)
        return true
    }

    /** Removes the key: the service is off, not set up any more, and what it sent is deleted. */
    fun removeKey(service: Service) {
        synchronized(this) {
            if (keys.remove(service) == null && service !in current.value.keyed) return
            val s = current.value
            current.value = s.copy(on = s.on - service, setUp = s.setUp - service, keyed = s.keyed - service)
            save(lost = true)
        }
        forget(service)
    }

    private fun forget(service: Service) {
        runCatching { Kept.fetched(service).clear() }
        onOff(service)
    }

    /**
     * The whole file, written beside itself and renamed, so it is never half written. If that fails (a
     * full disk) and something was [lost] (a switch turned off, a key removed), the file is removed
     * instead, which needs no room: left as it was, it would bring that back at the next start. After
     * turning something on the old file stays: nothing it says is untrue, and a full disk must not
     * cost every other switch and key. What holds now holds for this run. Nothing of it is logged (a key is in it).
     */
    private fun save(lost: Boolean) {
        val target = file ?: return
        val part = File(target.parentFile, target.name + ".part")
        val saved = try {
            val s = current.value
            val text = json.encodeToString(Saved.serializer(), Saved(s.on.map { it.id }, s.setUp.map { it.id }, keys.mapKeys { it.key.id }))
            target.parentFile?.mkdirs()
            part.writeText(text, Charsets.UTF_8)
            part.renameTo(target)
        } catch (e: Exception) {
            false
        }
        if (!saved) {
            runCatching { part.delete() }
            if (lost) runCatching { target.delete() }
        }
    }
}
