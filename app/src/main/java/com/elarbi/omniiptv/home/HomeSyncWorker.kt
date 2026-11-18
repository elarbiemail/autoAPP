package com.elarbi.omniiptv.home

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.tif.TifSync
import java.util.concurrent.TimeUnit

/** Met à jour le guide TV puis les rangées d'accueil et l'onglet Direct. */
class HomeSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // Actualisation automatique des listes (désactivée par défaut : la liste chargée est conservée).
        val days = com.elarbi.omniiptv.data.Prefs.autoRefreshDays
        if (days > 0) {
            Repo.sources.filter { it.type != com.elarbi.omniiptv.model.SourceType.YOUTUBE && it.type != com.elarbi.omniiptv.model.SourceType.TUNER }
                .filter { System.currentTimeMillis() - Repo.lastRefresh(it.id) > days * 24 * 3600_000L }
                .forEach { s -> try { Repo.refresh(s) } catch (_: Exception) {} }
        }
        if (inputData.getBoolean("epg", true)) {
            val ids = (listOfNotNull(Repo.activeSourceId) + Repo.favorites.values.map { it.sourceId }).distinct()
            ids.mapNotNull { id -> Repo.sources.firstOrNull { it.id == id } }.forEach { s ->
                try { Repo.refreshEpg(s) } catch (_: Exception) {}
            }
        }
        try { HomeScreen.sync(applicationContext) } catch (_: Throwable) {}
        if (TifSync.enabled(applicationContext)) try { TifSync.sync(applicationContext) } catch (_: Throwable) {}
        try { Shortcuts.update(applicationContext) } catch (_: Throwable) {}
        com.elarbi.omniiptv.data.Prefs.lastHomeSync = System.currentTimeMillis()
        return Result.success()
    }

    companion object {
        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<HomeSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("home_sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(ctx: Context, epg: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<HomeSyncWorker>().setInputData(workDataOf("epg" to epg)).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("home_sync_now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
