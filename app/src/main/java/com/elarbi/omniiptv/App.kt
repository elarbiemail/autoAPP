package com.elarbi.omniiptv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.home.HomeSyncWorker

class App : Application() {
    companion object {
        /** Nombre d'écrans de l'application actuellement visibles. */
        @Volatile
        var foreground = 0
    }

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        HomeSyncWorker.schedulePeriodic(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { foreground++ }
            override fun onActivityStopped(activity: Activity) { foreground = (foreground - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
