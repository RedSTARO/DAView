package com.daview.app

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.daview.app.platform.AndroidContextHolder
import com.daview.app.platform.AndroidFilePicker
import com.daview.app.platform.NotificationPermission
import com.daview.app.platform.createSettingsStore
import com.daview.app.ui.PipRequest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidContextHolder.context = applicationContext
        // Both have to happen while the activity is being created; registering
        // an activity-result launcher any later throws. The permission itself is
        // asked for when a scan or a download first needs its notification.
        AndroidFilePicker.register(this)
        NotificationPermission.register(this)
        enableEdgeToEdge()
        // What shows between the window appearing and the first composed frame.
        // The theme's own value is the dark ground, so a light-theme user would
        // otherwise get a near-black flash on every cold start.
        applyStartupBackground()
        setContent { App() }
    }

    /**
     * Reads the same key [com.daview.app.data.AppState] reads, before there is
     * a composition to ask. "light" and "dark" are what they say; anything else
     * follows the system's night setting, which is the default.
     */
    private fun applyStartupBackground() {
        val stored = createSettingsStore().getString("theme")
        val systemNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val light = when (stored) {
            "light" -> true
            "dark" -> false
            else -> !systemNight
        }
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
        NotificationPermission.unregister(this)
        super.onDestroy()
    }
}
