package com.daview.app.platform

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * The notification permission, asked for when a scan or a download starts.
 *
 * This permission controls notification visibility. The services still have
 * to enter the foreground with a notification when permission is denied.
 *
 * The launcher has to be registered while the activity is being created, so the
 * activity registers it and the request is made from wherever the scan starts.
 */
object NotificationPermission {

    private var launcher: ActivityResultLauncher<String>? = null
    private var owner: ComponentActivity? = null

    /** Asked once per launch at most; a refusal is not argued with. */
    private var asked = false

    fun register(activity: ComponentActivity) {
        owner = activity
        launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    }

    fun unregister(activity: ComponentActivity) {
        if (owner === activity) {
            owner = null
            launcher = null
        }
    }

    /** Updates an existing foreground notification; never replaces startForeground(). */
    internal fun updateForegroundNotification(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Permission can be revoked after the check. Skip this update and
            // keep watching the work so the service can still stop when done.
            Log.w("DAView", "Foreground notification update denied", e)
        }
    }

    fun request() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || asked) return
        val activity = owner ?: return
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        asked = true
        // Said before the system asks, which gives no reason of its own.
        runCatching {
            android.widget.Toast.makeText(
                activity,
                "扫描和下载在后台运行时，进度显示在通知栏，需要通知权限。",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        runCatching { launcher?.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}
