package com.daview.app

import android.app.Application
import com.daview.app.platform.AndroidContextHolder

class DaViewApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidContextHolder.context = applicationContext
        // Started here rather than in the activity so the port is listening by
        // the time the UI tries to connect to it.
        EmbeddedServer.start(applicationContext)
    }
}
