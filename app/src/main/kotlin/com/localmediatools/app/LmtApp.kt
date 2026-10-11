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
        AppLanguage.apply(this)
        com.localmediatools.ui.Fonts.init(this)
        com.localmediatools.edit.PatchStore.cleanupAll(this)
        Workload.init(this)
        ExportNotifications.createChannels(this)
        com.localmediatools.gallery.GalleryIndexService.createChannel(this)
        ExportManager.init(this)
        Thread { OutputStore.cleanupStalePending(this) }.apply { isDaemon = true }.start()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Following the phone's language: it may have just changed.
        if (AppLanguage.chosen(this) == null) {
            AppLanguage.apply(this)
            ExportNotifications.createChannels(this)
            com.localmediatools.gallery.GalleryIndexService.createChannel(this)
        }
    }

    companion object {
        lateinit var instance: LmtApp
            private set
    }
}
