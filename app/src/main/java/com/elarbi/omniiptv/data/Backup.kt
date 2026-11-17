package com.elarbi.omniiptv.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.elarbi.omniiptv.model.SourceType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sauvegarde de la configuration au format « dépôt » : réimportable via Outils > Dépôts. */
object Backup {
    fun json(): String {
        val items = JSONArray()
        Repo.sources.forEach { s ->
            val type = when (s.type) {
                SourceType.M3U -> "m3u"; SourceType.XTREAM -> "xtream"; SourceType.STALKER -> "stalker"
                SourceType.YOUTUBE, SourceType.TUNER -> return@forEach
            }
            if (s.url.startsWith("/")) return@forEach // listes locales non transférables
            items.put(
                JSONObject().put("type", type).put("name", s.name).put("url", s.url)
                    .put("username", s.username).put("password", s.password).put("mac", s.mac).put("epg", s.epgUrl)
            )
        }
        Repo.youtubeChannels().forEach { c ->
            items.put(JSONObject().put("type", "youtube").put("name", c.name).put("url", c.url).put("group", c.group))
        }
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date())
        return JSONObject()
            .put("name", "Sauvegarde Omni IPTV du $date")
            .put("description", "Sources, comptes et chaînes YouTube. Contient des identifiants : ne pas publier.")
            .put("author", "Omni IPTV")
            .put("items", items)
            .put("epgGuides", JSONArray(Repo.globalEpgUrls()))
            .toString(2)
    }

    /** Écrit le fichier dans « Téléchargements » et renvoie son emplacement. */
    fun export(ctx: Context): String {
        val name = "omni-iptv-sauvegarde.json"
        val data = json().toByteArray()
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                return "Téléchargements/$name"
            }
        }
        val f = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, name)
        f.writeBytes(data)
        return f.absolutePath
    }
}
