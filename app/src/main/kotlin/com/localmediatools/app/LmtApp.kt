package com.localmediatools.app

import android.app.Application
import com.localmediatools.core.OutputStore
import com.localmediatools.core.Workload
import com.localmediatools.export.ExportManager
import com.localmediatools.export.ExportNotifications

class LmtApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Workload.init(this)
        ExportNotifications.createChannels(this)
        ExportManager.init(this)
        Thread { OutputStore.cleanupStalePending(this) }.apply { isDaemon = true }.start()
    }

    companion object {
        lateinit var instance: LmtApp
            private set
    }
}
