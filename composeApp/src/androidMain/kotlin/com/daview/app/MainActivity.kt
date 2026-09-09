package com.daview.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.daview.app.platform.AndroidContextHolder
import com.daview.app.platform.AndroidFilePicker
import com.daview.app.platform.createSettingsStore
import com.daview.app.ui.PipRequest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidContextHolder.context = applicationContext
        // Has to happen while the activity is being created; registering an
        // activity-result launcher any later throws.
        AndroidFilePicker.register(this)
        requestNotificationPermission()
        enableEdgeToEdge()
        // What shows between the window appearing and the first composed frame.
        // The theme's own value is the dark ground, so a light-theme user would
        // otherwise get a near-black flash on every cold start.
        applyStartupBackground()
        setContent { App() }
    }

    /**
     * Asked for up front rather than at the moment a scan starts: the scan's
     * notification is the only thing standing between it and being killed in
     * the background, and a permission dialog in the middle of that is worse
     * than one at launch. Denial is not fatal — the scan still runs while the
     * app is open.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
            .launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Reads the same key [com.daview.app.data.AppState] reads, before there is
     * a composition to ask. Absent or anything but "light" means dark, which is
     * the app's own default.
     */
    private fun applyStartupBackground() {
        val light = createSettingsStore().getString("theme") == "light"
        window.setBackgroundDrawable(ColorDrawable(if (light) 0xFFFDF8FF.toInt() else 0xFF0E0D14.toInt()))
    }

    /**
     * Leaving the app while something is playing puts the video in a corner
     * rather than stopping it dead — which is what "I want to reply to this
     * message without losing my place" actually needs, and the only way the
     * picture survives leaving at all.
     */
    override fun onUserLeaveHint() {
        if (!PipRequest.enter(this)) super.onUserLeaveHint()
    }

    override fun onDestroy() {
        AndroidFilePicker.unregister(this)
        super.onDestroy()
    }
}
