package com.elarbi.omniiptv.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Le lanceur Android TV demande l'initialisation des rangées (installation, redémarrage). */
class InitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HomeSyncWorker.runNow(context, epg = true)
    }
}
