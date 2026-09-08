package com.daview.app

import android.app.Application
import com.daview.app.platform.AndroidContextHolder

class DaViewApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidContextHolder.context = applicationContext
    }
}
