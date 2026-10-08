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
        com.localmediatools.ui.Fonts.init(this)
        com.localmediatools.edit.PatchStore.cleanupAll(this)
        Workload.init(this)
        ExportNotifications.createChannels(this)
        com.localmediatools.gallery.GalleryIndexService.createChannel(this)
        ExportManager.init(this)
        Thread { OutputStore.cleanupStalePending(this) }.apply { isDaemon = true }.start()
    }

    companion object {
        lateinit var instance: LmtApp
            private set
    }
}
