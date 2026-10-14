package com.mialifeapp

import android.app.Application
import com.mialifeapp.core.Notifications
import com.mialifeapp.core.WatchdogWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        WatchdogWorker.schedule(this)
    }
}
