package com.elarbi.omniiptv.data

import android.content.Context
import com.elarbi.omniiptv.model.Source
import com.elarbi.omniiptv.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs

/**
 * Dépôts à la manière de CloudStream : un fichier JSON hébergé en ligne qui liste des contenus
 * installables (listes M3U, Xtream, portails MAC, chaînes YouTube Live, autres dépôts).
 *
 * Format :
 * { "name": "...", "description": "...", "author": "...", "version": 1,
 *   "items": [ { "type": "m3u|xtream|stalker|youtube|repo", "name": "...", "url": "...",
 *                "username": "", "password": "", "mac": "", "epg": "", "group": "", "description": "" } ] }
 */
object Repos {
    const val BUILTIN = "builtin"

    data class RepoItem(
        val type: String, val name: String, val url: String, val username: String = "",
        val password: String = "", val mac: String = "", val epg: String = "",
        val group: String = "", val description: String = ""
    ) {
        /** Identifiant stable : réinstaller met à jour la même source au lieu d'en créer une autre. */
        val sourceId: String get() = "r" + abs((type + url + username).hashCode()).toString(36)
    }

    data class Repository(
        val url: String, val name: String, val description: String,
        val author: String, val items: List<RepoItem>
    )

    private val cache = LinkedHashMap<String, Repository>()
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("repos", Context.MODE_PRIVATE)

    /** Accepte une URL complète, « utilisateur/dépôt » (GitHub) ou « utilisateur/dépôt/fichier.json ». */
    fun normalize(input: String): String {
        val t = input.trim()
        if (t == BUILTIN || t.startsWith("http://") || t.startsWith("https://") || t.startsWith("/")) return t
        val parts = t.trim('/').split('/')
        return when {
            parts.size == 2 -> "https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/main/omni-repo.json"
            parts.size >= 3 -> "https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/main/" + parts.drop(2).joinToString("/")
            else -> "https://$t"
        }
    }

    @Synchronized
    fun list(ctx: Context): List<Repository> {
        if (!loaded) {
            loaded = true
            try {
                val a = JSONArray(prefs(ctx).getString("repos", "[]"))
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    cache[o.getString("url")] = parse(o.getString("url"), o.getString("json"))
                }
            } catch (_: Exception) {
            }
            if (cache.isEmpty()) {
                try { cache[BUILTIN] = parse(BUILTIN, readBuiltin(ctx)); save(ctx) } catch (_: Exception) {}
            }
        }
        return cache.values.toList()
    }

    private fun readBuiltin(ctx: Context): String =
        ctx.assets.open("builtin_repo.json").use { String(it.readBytes(), Charsets.UTF_8) }

    @Synchronized
    private fun save(ctx: Context) {
        val a = JSONArray()
        cache.values.forEach { r -> a.put(JSONObject().put("url", r.url).put("json", toJson(r).toString())) }
        prefs(ctx).edit().putString("repos", a.toString()).apply()
    }

    private fun toJson(r: Repository): JSONObject {
        val items = JSONArray()
        r.items.forEach { i ->
            items.put(
                JSONObject().put("type", i.type).put("name", i.name).put("url", i.url)
                    .put("username", i.username).put("password", i.password).put("mac", i.mac)
                    .put("epg", i.epg).put("group", i.group).put("description", i.description)
            )
        }
        return JSONObject().put("name", r.name).put("description", r.description)
            .put("author", r.author).put("items", items)
    }

    fun parse(url: String, text: String): Repository {
        val t = text.trim().removePrefix("﻿")
        if (t.startsWith("#EXTM3U")) {
            val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "Liste M3U" }
            return Repository(url, name, "Liste M3U ajoutée comme dépôt", "", listOf(RepoItem("m3u", name, url)))
        }
        val o = JSONObject(t)
        val arr = o.optJSONArray("items") ?: o.optJSONArray("sources") ?: JSONArray()
        val items = ArrayList<RepoItem>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val type = e.str("type").lowercase().ifBlank { "m3u" }
            val u = e.str("url")
            if (u.isBlank()) continue
            items.add(
                RepoItem(
                    type, e.str("name").ifBlank { u }, u, e.str("username"), e.str("password"),
                    e.str("mac"), e.str("epg"), e.str("group"), e.str("description")
                )
            )
        }
        // Guides TV inclus (sauvegardes) : ajoutés aux guides supplémentaires.
        o.optJSONArray("epgGuides")?.let { g ->
            val extra = (0 until g.length()).map { g.optString(it) }.filter { it.startsWith("http") }
            if (extra.isNotEmpty()) Repo.setGlobalEpgUrls(Repo.globalEpgUrls() + extra)
        }
        return Repository(
            url, o.str("name").ifBlank { url }, o.str("description"), o.str("author"), items
        )
    }

    private suspend fun fetch(ctx: Context, url: String): Repository = withContext(Dispatchers.IO) {
        if (url == BUILTIN) parse(BUILTIN, readBuiltin(ctx)) else parse(url, Http.get(url))
    }

    /** Ajoute un dépôt (et les dépôts qu'il référence, sur un niveau). */
    suspend fun add(ctx: Context, input: String): Repository {
        val url = normalize(input)
        val r = fetch(ctx, url)
        synchronized(this) { list(ctx); cache[url] = r; save(ctx) }
        r.items.filter { it.type == "repo" }.forEach { sub ->
            try {
                val su = normalize(sub.url)
                val sr = fetch(ctx, su)
                synchronized(this) { cache[su] = sr; save(ctx) }
            } catch (_: Exception) {
            }
        }
        return r
    }

    suspend fun refreshAll(ctx: Context): Int {
        var ok = 0
        list(ctx).forEach { r ->
            try {
                val n = fetch(ctx, r.url)
                synchronized(this) { cache[r.url] = n }
                ok++
            } catch (_: Exception) {
            }
        }
        synchronized(this) { save(ctx) }
        return ok
    }

    @Synchronized
    fun remove(ctx: Context, url: String) {
        list(ctx)
        cache.remove(url)
        save(ctx)
    }

    fun isInstalled(item: RepoItem): Boolean = when (item.type) {
        "youtube" -> Repo.youtubeChannels().any { it.url == YouTube.normalize(item.url) }
        "repo" -> synchronized(this) { cache.containsKey(normalize(item.url)) }
        else -> Repo.sources.any { it.id == item.sourceId }
    }

    /** Installe un élément ; renvoie un message lisible. */
    suspend fun install(ctx: Context, item: RepoItem): String {
        return when (item.type) {
            "youtube" -> {
                Repo.addYoutube(item.name, item.url, item.group)
                "« ${item.name} » ajoutée aux chaînes YouTube Live"
            }
            "repo" -> {
                val r = add(ctx, item.url)
                "Dépôt « ${r.name} » ajouté (${r.items.size} éléments)"
            }
            "m3u", "xtream", "stalker" -> {
                val type = when (item.type) {
                    "xtream" -> SourceType.XTREAM
                    "stalker" -> SourceType.STALKER
                    else -> SourceType.M3U
                }
                val s = Source(
                    id = item.sourceId, type = type, name = item.name, url = item.url,
                    username = item.username, password = item.password,
                    mac = if (type == SourceType.STALKER) item.mac.ifBlank { Repo.deviceMac }.uppercase() else "",
                    epgUrl = item.epg
                )
                Repo.upsertSource(s)
                if (Repo.activeSource() == null) Repo.activeSourceId = s.id
                val n = Repo.refresh(s)
                "« ${item.name} » installée ($n chaînes)"
            }
            else -> throw IOException("Type « ${item.type} » non pris en charge")
        }
    }
}
