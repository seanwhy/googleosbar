package com.meridian11.books.bentobar.items

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.StorageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.SectionLabel
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * Usage access (PACKAGE_USAGE_STATS), an optional special app access the user turns on in Android
 * settings. Android can't show apps which other apps use the CPU or memory, but with Usage access
 * it shares each app's data use (NetworkStatsManager) and storage (StorageStatsManager). This file
 * reads only those two, for the Network and Storage menus.
 *
 * The same switch also covers which apps were used and when. Only a Screen time item reads any of
 * that (ScreenTimeLoad): when the screen went on and off today, and, while its menu is open, which
 * apps were in front and for how long. None of it is written anywhere or logged.
 *
 * Off unless the user turns it on. The queries run on one low-priority background thread, only
 * while a Network or Storage menu is open, and are cached ([data] about 10 s, [storage] about
 * 60 s), never on the 1 Hz tick.
 */
object Usage {
    private const val TAG = "BentoBar"
    private const val TOP = 5
    private const val ICON_PX = 64

    fun granted(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        when (ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)) {
            AppOpsManager.MODE_ALLOWED -> true
            // Default mode defers to the permission itself (only granted from adb, for development).
            AppOpsManager.MODE_DEFAULT -> context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }.getOrDefault(false)

    /** Opens Usage access settings, on BentoBar's own page where Settings supports it. */
    fun openSettings(context: Context) {
        val app = context.applicationContext
        try {
            app.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + app.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Env.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: SecurityException) {
            Env.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    /** One row of a top list: an app (with its icon) or a group such as "Android system" (with a glyph). */
    @Immutable
    data class AppUse(val label: String, val bytes: Long, val icon: ImageBitmap?, val sym: String = Sym.APPS)

    /**
     * A loaded list. [noEthernet]: on Ethernet, but Android wouldn't report Ethernet use per app. [launcherAppsOnly]: the list
     * counts only the apps that have a launcher entry (the manifest's package visibility), which the menu says under it.
     */
    @Immutable
    data class Top(val apps: List<AppUse>, val noEthernet: Boolean = false, val launcherAppsOnly: Boolean = false)

    private val worker by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "BentoBar-usage").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }

    /** A cached, background-loaded top list. [request] reloads only when older than [ttlMs]. */
    class TopList(private val ttlMs: Long, private val load: (Context) -> Top) {
        private val _state = MutableStateFlow<Top?>(null)
        val state: StateFlow<Top?> get() = _state
        @Volatile private var loadedAt = 0L
        @Volatile private var loading = false

        fun request() {
            val now = SystemClock.elapsedRealtime()
            if (loading || (_state.value != null && now - loadedAt < ttlMs)) return
            loading = true
            worker.execute {
                val top = try { load(Env.app) } catch (e: Exception) { Log.w(TAG, "usage query failed", e); Top(emptyList()) }
                // Usage access may have been turned off while this ran: drop the result then.
                if (granted(Env.app)) { _state.value = top; loadedAt = SystemClock.elapsedRealtime() }
                loading = false
            }
        }

        /** Forgets the list (Usage access was turned off). */
        fun clear() { _state.value = null; loadedAt = 0 }
    }

    /** Apps by data used today (Wi-Fi, Ethernet where Android reports it, and mobile data). */
    val data = TopList(10_000) { loadData(it) }

    /** Apps by size: app, data and cache. */
    val storage = TopList(60_000) { loadStorage(it) }

    @Suppress("DEPRECATION") // querySummary takes the legacy ConnectivityManager.TYPE_* network types
    private fun loadData(context: Context): Top {
        val nsm = context.getSystemService(NetworkStatsManager::class.java) ?: return Top(emptyList())
        val pm = context.packageManager
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = System.currentTimeMillis()
        val types = buildList {
            add(ConnectivityManager.TYPE_WIFI)
            add(ConnectivityManager.TYPE_ETHERNET)
            if (pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) add(ConnectivityManager.TYPE_MOBILE)
        }
        // Summary buckets are a couple of hours wide, so "today" can start a little before midnight.
        val perUid = HashMap<Int, Long>()
        var ethernetFailed = false
        for (type in types) {
            val ok = runCatching {
                nsm.querySummary(type, null, start, end).use { stats ->
                    val b = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(b)
                        perUid.merge(b.uid, b.rxBytes + b.txBytes, Long::plus)
                    }
                }
            }
            if (ok.isFailure) {
                Log.i(TAG, "no per-app stats for network type $type: ${ok.exceptionOrNull()}")
                if (type == ConnectivityManager.TYPE_ETHERNET) ethernetFailed = true
            }
        }
        // Group what can't be named as one app: system uids, tethering, removed apps, and apps
        // BentoBar can't see (it lists launcher apps only, not every package).
        data class Group(val label: String, var bytes: Long, val pkg: String?, val sym: String)
        val groups = LinkedHashMap<String, Group>()
        fun add(key: String, bytes: Long, make: () -> Group) { groups.getOrPut(key, make).bytes += bytes }
        for ((uid, bytes) in perUid) {
            if (bytes <= 0) continue
            when {
                uid == NetworkStats.Bucket.UID_TETHERING -> add("tethering", bytes) { Group(context.getString(R.string.usage_tethering), 0, null, Sym.WIFI) }
                uid == NetworkStats.Bucket.UID_REMOVED -> add("removed", bytes) { Group(context.getString(R.string.usage_removed_apps), 0, null, Sym.DELETE) }
                uid < Process.FIRST_APPLICATION_UID -> add("system", bytes) { Group(context.getString(R.string.usage_android_system), 0, null, Sym.SETTINGS) }
                else -> {
                    val pkg = runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
                    if (pkg == null) add("other", bytes) { Group(context.getString(R.string.usage_other_apps), 0, null, Sym.APPS) }
                    else add("pkg:$pkg", bytes) { Group(label(pm, pkg), 0, pkg, Sym.APPS) }
                }
            }
        }
        val top = groups.values.sortedByDescending { it.bytes }.take(TOP)
            .map { g -> AppUse(g.label, g.bytes, g.pkg?.let { icon(pm, it) }, g.sym) }
        val onEthernet = runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }.getOrDefault(false)
        return Top(top, noEthernet = ethernetFailed && onEthernet)
    }

    // QueryPermissionsNeeded: BentoBar asks for no QUERY_ALL_PACKAGES, on purpose. Package visibility shows the apps with a launcher entry
    // (see the manifest's <queries>), so a package without one is not listed; the menu says so under the list (launcherAppsOnly).
    @SuppressLint("QueryPermissionsNeeded")
    private fun loadStorage(context: Context): Top {
        val ssm = context.getSystemService(StorageStatsManager::class.java) ?: return Top(emptyList())
        val pm = context.packageManager
        val user = Process.myUserHandle()
        val sizes = pm.getInstalledApplications(0).mapNotNull { ai ->
            try {
                val s = ssm.queryStatsForPackage(ai.storageUuid, ai.packageName, user)
                // dataBytes already includes the cache (getCacheBytes is a part of it), so app + data
                // is the total, as in Android's own App info › Storage.
                ai.packageName to (s.appBytes + s.dataBytes)
            } catch (e: Exception) { null }
        }
        return Top(sizes.sortedByDescending { it.second }.take(TOP)
            .map { (pkg, bytes) -> AppUse(label(pm, pkg), bytes, icon(pm, pkg)) }, launcherAppsOnly = true)
    }

    private fun label(pm: PackageManager, pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private const val MAX_ICONS = 64
    private val icons = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX_ICONS
    }

    private fun icon(pm: PackageManager, pkg: String): ImageBitmap? = synchronized(icons) {
        icons[pkg] ?: runCatching {
            val d = pm.getApplicationIcon(pkg)
            val b = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, ICON_PX, ICON_PX)
            d.draw(Canvas(b))
            b.asImageBitmap()
        }.getOrNull()?.also { icons[pkg] = it }
    }
}

/**
 * The per-app section of the Network and Storage menus. With Usage access: the top apps from
 * [list], refreshed every [refreshMs] while the menu is open. Without: one quiet opt-in row.
 */
@Composable
fun TopAppsSection(list: Usage.TopList, refreshMs: Long, title: String, optIn: String, empty: String, host: MenuHost) {
    var granted by remember { mutableStateOf(Usage.granted(Env.app)) }
    LaunchedEffect(list) {
        while (true) {
            granted = Usage.granted(Env.app)
            if (granted) list.request() else list.clear()
            delay(refreshMs)
        }
    }
    val top by list.state.collectAsState()
    MenuDivider()
    if (!granted) {
        Text(optIn, style = MaterialTheme.typography.bodyMedium)
        MenuNote(stringResource(R.string.usage_explain))
        MenuEntry(Sym.TOGGLE_ON, stringResource(R.string.usage_turn_on)) { host.close(); Usage.openSettings(Env.app) }
        return
    }
    SectionLabel(title)
    val t = top
    when {
        t == null -> MenuNote(stringResource(R.string.usage_loading))
        t.apps.isEmpty() -> MenuNote(empty)
        else -> t.apps.forEach { AppUseRow(it) }
    }
    if (t?.noEthernet == true) MenuNote(stringResource(R.string.usage_no_ethernet))
    if (t?.launcherAppsOnly == true && t.apps.isNotEmpty()) MenuNote(stringResource(R.string.usage_storage_launcher_only))
}

@Composable
private fun AppUseRow(a: Usage.AppUse) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (a.icon != null) Image(a.icon, null, Modifier.size(20.dp))
            else SymIcon(a.sym, size = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Text(a.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(Fmt.bytes(a.bytes.toDouble()), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Medium)
    }
}
