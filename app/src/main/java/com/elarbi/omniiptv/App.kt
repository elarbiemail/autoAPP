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
        com.elarbi.omniiptv.data.CrashLog.install(this)
        // Cache des logos et affiches limité à 80 Mo (250 Mo par défaut : trop pour les téléviseurs à petit stockage).
        try {
            com.bumptech.glide.Glide.init(this, com.bumptech.glide.GlideBuilder()
                .setDiskCache(com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory(this, 80L * 1024 * 1024)))
        } catch (_: Throwable) {}
        Repo.init(this)
        // Ménage : écritures interrompues et fichiers de sources supprimées.
        Thread { try { Repo.cleanup() } catch (_: Throwable) {} }.start()
        HomeSyncWorker.schedulePeriodic(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { foreground++ }
            override fun onActivityStopped(activity: Activity) { foreground = (foreground - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                try { com.elarbi.omniiptv.ui.ThemeManager.apply(activity) } catch (_: Exception) {}
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Le téléviseur manque de mémoire : on libère les listes et guides en mémoire (ils restent enregistrés). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (foreground == 0 && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            try { Repo.trimMemory() } catch (_: Throwable) {}
        }
    }
}
