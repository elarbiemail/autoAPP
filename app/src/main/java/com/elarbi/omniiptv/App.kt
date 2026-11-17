package com.elarbi.omniiptv

import android.app.Application
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.home.HomeSyncWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        HomeSyncWorker.schedulePeriodic(this)
    }
}
