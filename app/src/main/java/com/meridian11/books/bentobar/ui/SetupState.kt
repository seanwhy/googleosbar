package com.meridian11.books.bentobar.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Immutable
import com.meridian11.books.bentobar.data.Uses
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.MediaAccess
import com.meridian11.books.bentobar.items.Notify
import com.meridian11.books.bentobar.items.Usage
import kotlinx.coroutines.flow.MutableStateFlow

/** What Setup shows as done, read in one go. */
@Immutable
data class SetupState(
    val serviceOn: Boolean = false,
    val notifications: Boolean = false,
    val liveUpdates: Boolean = false,
    val calendar: Boolean = false,
    val exactAlarms: Boolean = false,
    val advancedProtection: Boolean = false,
    /** Optional: Usage access, for the top apps in the Network and Storage menus. */
    val usageAccess: Boolean = false,
    /** Optional: Android's notification access, which Now playing needs for titles and artwork. */
    val mediaAccess: Boolean = false,
    /** Optional: Android's approximate location, which Weather's My location needs. */
    val location: Boolean = false,
    /** Optional: All files access, for Cloud Clipboard local file upload. */
    val fileAccess: Boolean = false,
    /** Installed from a download: Android guards the accessibility and notification access switches until they are allowed in App info. */
    val sideloaded: Boolean = false,
)

/**
 * The setup state as one observed value. Screens read [state] instead of asking Android while they
 * draw: a value read during composition is never re-read when nothing the composable depends on
 * changes (strong skipping), and on a Googlebook the settings that change it open in their own
 * window while BentoBar stays resumed, so onResume alone isn't enough. [refresh] runs on resume,
 * window focus, permission results, accessibility service changes, and when the service connects
 * or stops.
 */
object Setup {
    val state = MutableStateFlow(SetupState())

    fun refresh(context: Context) {
        val app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        val before = state.value
        state.value = SetupState(
            serviceOn = MainActivity.serviceOn(app),
            notifications = Notify.allowed(app),
            liveUpdates = Notify.liveUpdates && runCatching { nm?.canPostPromotedNotifications() == true }.getOrDefault(false),
            calendar = app.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
                Uses.on(Uses.CALENDAR),
            exactAlarms = app.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true && Uses.on(Uses.EXACT_ALARMS),
            advancedProtection = Env.advancedProtection(),
            usageAccess = Usage.granted(app),
            mediaAccess = MediaAccess.granted(app),
            location = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            fileAccess = com.meridian11.books.bentobar.items.CloudClipboardItem.hasAllFilesAccess(app),
            sideloaded = Env.sideloaded(),
        )
        // A running timer's alarm is exact only while exact alarms are allowed and used: follow a change.
        if (state.value.exactAlarms != before.exactAlarms) com.meridian11.books.bentobar.items.Timers.reschedule()
    }
}
