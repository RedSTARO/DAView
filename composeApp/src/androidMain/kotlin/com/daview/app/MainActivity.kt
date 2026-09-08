package com.daview.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.daview.app.platform.AndroidContextHolder
import com.daview.app.platform.AndroidFilePicker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidContextHolder.context = applicationContext
        // Has to happen while the activity is being created; registering an
        // activity-result launcher any later throws.
        AndroidFilePicker.register(this)
        requestNotificationPermission()
        enableEdgeToEdge()
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

    override fun onDestroy() {
        AndroidFilePicker.unregister(this)
        super.onDestroy()
    }
}
