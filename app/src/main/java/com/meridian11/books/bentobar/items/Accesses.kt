package com.meridian11.books.bentobar.items

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.ui.MainActivity

/**
 * The accesses the newer types need ([ItemType.needs]): whether Android has given each, where it is given, and what it is
 * called. Screens read `Setup.state.access` (observed), not [granted], while they draw.
 */
object Accesses {
    /** Asks Android: not for every frame. Never throws; what can't be read counts as not given. */
    fun granted(context: Context, access: Access): Boolean = runCatching {
        when (access) {
            Access.CAMERA -> context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            Access.NOTICES -> NoticeAccess.granted(context)
            Access.DO_NOT_DISTURB -> context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
            Access.WRITE_SETTINGS -> Settings.System.canWrite(context)
        }
    }.getOrDefault(false)

    /**
     * Where [access] is given. Not given: the camera's own dialog (asked in BentoBar's window, `MainActivity.requestPermission`;
     * refused for good, Android answers at once and the window says where it can still be allowed), or Android's settings
     * screen for the others. Given: the settings screen, App info for the camera, where it can be taken back.
     */
    fun open(context: Context, access: Access) {
        when (access) {
            Access.CAMERA ->
                if (granted(context, access)) MainActivity.openAppInfo(context)
                else MainActivity.requestPermission(context, Manifest.permission.CAMERA)
            Access.NOTICES -> NoticeAccess.openSettings(context)
            Access.DO_NOT_DISTURB -> Env.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            Access.WRITE_SETTINGS -> Env.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)))
        }
    }

    /** Its name, as Setup's row has it. */
    @StringRes fun title(access: Access): Int = when (access) {
        Access.CAMERA -> R.string.access_camera_title
        Access.NOTICES -> R.string.access_notices_title
        Access.DO_NOT_DISTURB -> R.string.access_do_not_disturb_title
        Access.WRITE_SETTINGS -> R.string.access_write_settings_title
    }

    /** What it is for, and what BentoBar does not do with it. */
    @StringRes fun explain(access: Access): Int = when (access) {
        Access.CAMERA -> R.string.access_camera_explain
        Access.NOTICES -> R.string.access_notices_explain
        Access.DO_NOT_DISTURB -> R.string.access_do_not_disturb_explain
        Access.WRITE_SETTINGS -> R.string.access_write_settings_explain
    }

    /** The button that leads to it, in an item's options and its popup. */
    @StringRes fun allow(access: Access): Int = when (access) {
        Access.CAMERA -> R.string.access_camera_allow
        Access.NOTICES -> R.string.access_notices_allow
        Access.DO_NOT_DISTURB -> R.string.access_do_not_disturb_allow
        Access.WRITE_SETTINGS -> R.string.access_write_settings_allow
    }
}
