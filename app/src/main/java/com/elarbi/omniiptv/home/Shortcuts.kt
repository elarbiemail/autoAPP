package com.elarbi.omniiptv.home

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import com.bumptech.glide.Glide
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Library
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.ui.PlayerActivity
import java.util.concurrent.TimeUnit

/**
 * Raccourcis automatiques (appui long sur l'icône de l'application, téléphones et tablettes) :
 * dernière chaîne, dernier film, dernier épisode — avec leur logo ou leur affiche.
 * Épinglage d'un raccourci sur l'écran d'accueil à la demande.
 */
object Shortcuts {
    private fun icon(ctx: Context, ch: Channel): Icon {
        val bmp: Bitmap? = try {
            if (ch.logo.isBlank()) null
            else Glide.with(ctx.applicationContext).asBitmap().load(ch.logo).centerCrop().submit(192, 192).get(6, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
        return if (bmp != null) Icon.createWithBitmap(bmp) else Icon.createWithResource(ctx, R.mipmap.ic_launcher)
    }

    private fun info(ctx: Context, id: String, ch: Channel, short: String, long: String, rank: Int): ShortcutInfo {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("omniiptv://play?key=" + Uri.encode(ch.key)))
            .setClass(ctx, PlayerActivity::class.java)
        return ShortcutInfo.Builder(ctx, id)
            .setShortLabel(short.take(24))
            .setLongLabel(long.take(48))
            .setIcon(icon(ctx, ch))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    /** Met à jour les raccourcis dynamiques (à appeler hors du fil principal). */
    fun update(ctx: Context) {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
        try {
            val list = ArrayList<ShortcutInfo>()
            Repo.recents.firstOrNull { !it.isVod }?.let { list += info(ctx, "last_channel", it, it.name, "Dernière chaîne : ${it.name}", 0) }
            val resume = Library.resume.values.toList()
            resume.firstOrNull { it.key.contains("|E") }?.let {
                list += info(ctx, "last_episode", it, it.group.ifBlank { it.name }, "Reprendre : ${it.group} — ${it.name}", 1)
            }
            resume.firstOrNull { !it.key.contains("|E") }?.let { list += info(ctx, "last_movie", it, it.name, "Reprendre le film : ${it.name}", 2) }
            val max = sm.maxShortcutCountPerActivity.coerceAtLeast(1)
            sm.dynamicShortcuts = list.take(maxOf(1, max - 2))
        } catch (_: Exception) {
        }
    }

    /** Épingle un raccourci (logo ou affiche) sur l'écran d'accueil. Renvoie false si non pris en charge. */
    fun pin(ctx: Context, ch: Channel): Boolean {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return false
        if (!sm.isRequestPinShortcutSupported) return false
        Thread {
            try { sm.requestPinShortcut(info(ctx, "pin_" + ch.key.hashCode(), ch, ch.name, ch.name, 0), null) } catch (_: Exception) {}
        }.start()
        return true
    }
}
