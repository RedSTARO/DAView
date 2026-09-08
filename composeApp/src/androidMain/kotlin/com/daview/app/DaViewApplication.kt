package com.daview.app

import android.app.Application
import com.daview.app.platform.AndroidContextHolder

class DaViewApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The library itself is built with the UI. Nothing needs to be running
        // before the first frame any more, so start-up does no work here.
        AndroidContextHolder.context = applicationContext
    }
}
