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
        if (inputData.getBoolean("epg", true)) {
            val ids = (listOfNotNull(Repo.activeSourceId) + Repo.favorites.values.map { it.sourceId }).distinct()
            ids.mapNotNull { id -> Repo.sources.firstOrNull { it.id == id } }.forEach { s ->
                try { Repo.refreshEpg(s) } catch (_: Exception) {}
            }
        }
        try { HomeScreen.sync(applicationContext) } catch (_: Exception) {}
        try { TifSync.sync(applicationContext) } catch (_: Exception) {}
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
