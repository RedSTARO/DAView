package com.daview.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.daview.app.platform.AndroidContextHolder
import com.daview.app.platform.AndroidFilePicker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidContextHolder.context = applicationContext
        // Has to happen while the activity is being created; registering an
        // activity-result launcher any later throws.
        AndroidFilePicker.register(this)
        enableEdgeToEdge()
        setContent { App() }
    }

    override fun onDestroy() {
        AndroidFilePicker.unregister(this)
        super.onDestroy()
    }
}
