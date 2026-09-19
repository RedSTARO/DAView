package com.daview.app.platform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * The notification permission, asked for when a scan or a download starts.
 *
 * That is what the notification is for: it is the only thing between a long
 * scan and the process being reclaimed in the background. Asked on the very
 * first launch instead, the dialog arrived over a blank loading screen before
 * the app had shown anything, with nothing to say why it wanted it.
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

    fun request() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || asked) return
        val activity = owner ?: return
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        asked = true
        runCatching { launcher?.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}
