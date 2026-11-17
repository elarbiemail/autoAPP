package com.elarbi.omniiptv.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.Programme
import com.elarbi.omniiptv.model.Source
import com.elarbi.omniiptv.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Random

/** Stockage central : sources, chaînes, guide TV, favoris, récents. */
object Repo {
    lateinit var app: Context
        private set
    private lateinit var prefs: SharedPreferences

    val sources = mutableListOf<Source>()
    val favorites = LinkedHashMap<String, Channel>()
    val recents = ArrayList<Channel>()
    private val channelCache = HashMap<String, List<Channel>>()
    private val epgCache = HashMap<String, Map<String, List<Programme>>>()
    private val stalkers = HashMap<String, StalkerClient>()

    /** Incrémenté à chaque modification, pour savoir quand reconstruire l'interface. */
    @Volatile
    var version = 0
        private set

    /** Liste de chaînes utilisée pour le zapping dans le lecteur. */
    var playContext: List<Channel> = emptyList()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("omni", Context.MODE_PRIVATE)
        try {
            val a = JSONArray(prefs.getString("sources", "[]"))
            for (i in 0 until a.length()) sources.add(Source.fromJson(a.getJSONObject(i)))
        } catch (e: Exception) {
            Log.w("Repo", "sources", e)
        }
        readArray("favorites.json")?.let { a ->
            for (i in 0 until a.length()) Channel.fromJson(a.getJSONObject(i)).let { favorites[it.key] = it }
        }
        readArray("recents.json")?.let { a ->
            for (i in 0 until a.length()) recents.add(Channel.fromJson(a.getJSONObject(i)))
        }
    }

    private fun file(name: String) = File(app.filesDir, name)
    private fun readArray(name: String): JSONArray? = file(name).takeIf { it.exists() }?.let {
        try { JSONArray(it.readText()) } catch (e: Exception) { null }
    }
    private fun saveChannels(name: String, list: Collection<Channel>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        file(name).writeText(a.toString())
    }

    fun bump() { version++ }

    // ---------- Adresse MAC virtuelle ----------
    val deviceMac: String
        get() {
            var m = prefs.getString("mac", null)
            if (m == null) {
                m = randomMac()
                prefs.edit().putString("mac", m).apply()
            }
            return m
        }

    fun regenerateMac(): String = randomMac().also { prefs.edit().putString("mac", it).apply() }

    fun randomMac(): String {
        val r = Random()
        return "00:1A:79:" + (1..3).joinToString(":") { "%02X".format(r.nextInt(256)) }
    }

    // ---------- Sources ----------
    var activeSourceId: String?
        get() = prefs.getString("active", null)?.takeIf { id -> sources.any { it.id == id } } ?: sources.firstOrNull()?.id
        set(v) {
            prefs.edit().putString("active", v).apply()
            bump()
        }

    fun activeSource(): Source? = sources.firstOrNull { it.id == activeSourceId }

    @Synchronized
    private fun saveSources() {
        val a = JSONArray()
        sources.forEach { a.put(it.toJson()) }
        prefs.edit().putString("sources", a.toString()).apply()
    }

    @Synchronized
    fun upsertSource(s: Source) {
        val i = sources.indexOfFirst { it.id == s.id }
        if (i >= 0) sources[i] = s else sources.add(s)
        synchronized(stalkers) { stalkers.remove(s.id) }
        saveSources()
        bump()
    }

    @Synchronized
    fun removeSource(id: String) {
        sources.removeAll { it.id == id }
        channelCache.remove(id)
        epgCache.remove(id)
        file("ch_$id.json").delete()
        file("epg_$id.json").delete()
        file("free_$id.m3u").delete()
        saveSources()
        bump()
    }

    @Synchronized
    fun channels(sourceId: String): List<Channel> = channelCache.getOrPut(sourceId) {
        readArray("ch_$sourceId.json")?.let { a -> List(a.length()) { Channel.fromJson(a.getJSONObject(it)) } } ?: emptyList()
    }

    fun stalker(sourceId: String): StalkerClient = synchronized(stalkers) {
        stalkers.getOrPut(sourceId) {
            StalkerClient(sources.firstOrNull { it.id == sourceId } ?: throw IOException("Source supprimée"))
        }
    }

    private fun uaHeaders(s: Source): Map<String, String> =
        if (s.userAgent.isBlank()) emptyMap() else mapOf("User-Agent" to s.userAgent)

    /** Télécharge la liste des chaînes d'une source (et son guide TV). Renvoie le nombre de chaînes. */
    suspend fun refresh(s: Source, withEpg: Boolean = true): Int = withContext(Dispatchers.IO) {
        var autoEpg = ""
        val list: List<Channel> = when (s.type) {
            SourceType.M3U -> {
                val r = M3uParser.parse(Http.get(s.url, uaHeaders(s)), s.id)
                autoEpg = r.epgUrl
                r.channels
            }
            SourceType.XTREAM -> {
                val x = XtreamClient(s)
                autoEpg = x.epgUrl()
                x.channels()
            }
            SourceType.STALKER -> {
                synchronized(stalkers) { stalkers.remove(s.id) }
                stalker(s.id).channels()
            }
            SourceType.YOUTUBE -> return@withContext channels(s.id).size
        }
        if (list.isEmpty()) throw IOException("Aucune chaîne trouvée")
        saveChannels("ch_${s.id}.json", list)
        synchronized(this@Repo) { channelCache[s.id] = list }
        if (autoEpg.isNotBlank()) prefs.edit().putString("autoepg_${s.id}", autoEpg).apply()
        bump()
        if (withEpg) {
            try { refreshEpg(s) } catch (e: Exception) { Log.w("Repo", "EPG", e) }
        }
        list.size
    }

    suspend fun refreshEpg(s: Source): Unit = withContext(Dispatchers.IO) {
        val chans = channels(s.id).filter { !it.isVod }
        if (chans.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val map: Map<String, List<Programme>> = if (s.type == SourceType.STALKER) {
            stalker(s.id).epg()
        } else {
            val url = s.epgUrl.ifBlank { prefs.getString("autoepg_${s.id}", "").orEmpty() }
                .split(',').first().trim()
            if (url.isBlank()) return@withContext
            val ids = chans.filter { it.epgId.isNotBlank() }.map { it.epgId.lowercase() }.toSet()
            val names = chans.filter { it.epgId.isBlank() }.map { it.name.lowercase().trim() }.toSet()
            Http.open(url, uaHeaders(s)).use { Xmltv.parse(it, ids, names, now - 2 * 3600_000L, now + 36 * 3600_000L) }
        }
        val o = JSONObject()
        map.forEach { (k, list) ->
            val a = JSONArray()
            list.forEach { p -> a.put(JSONArray().put(p.start).put(p.end).put(p.title).put(p.desc)) }
            o.put(k, a)
        }
        file("epg_${s.id}.json").writeText(o.toString())
        synchronized(this@Repo) { epgCache[s.id] = map }
        bump()
    }

    @Synchronized
    fun epg(sourceId: String): Map<String, List<Programme>> = epgCache.getOrPut(sourceId) {
        val f = file("epg_$sourceId.json")
        if (!f.exists()) return@getOrPut emptyMap()
        try {
            val o = JSONObject(f.readText())
            val m = HashMap<String, List<Programme>>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val a = o.getJSONArray(k)
                m[k] = List(a.length()) {
                    val e = a.getJSONArray(it)
                    Programme(e.getLong(0), e.getLong(1), e.optString(2), e.optString(3))
                }
            }
            m
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun programmes(ch: Channel): List<Programme> {
        val list = epg(ch.sourceId)[ch.epgKey] ?: return emptyList()
        val now = System.currentTimeMillis()
        return list.filter { it.end > now }
    }

    fun nowNext(ch: Channel): Pair<Programme?, Programme?> {
        val l = programmes(ch)
        val now = System.currentTimeMillis()
        return l.firstOrNull { it.start <= now && it.end > now } to l.firstOrNull { it.start > now }
    }

    // ---------- Favoris et récents ----------
    @Synchronized
    fun isFavorite(key: String) = favorites.containsKey(key)

    @Synchronized
    fun toggleFavorite(ch: Channel): Boolean {
        val added = if (favorites.remove(ch.key) != null) false else { favorites[ch.key] = ch; true }
        saveChannels("favorites.json", favorites.values)
        bump()
        return added
    }

    @Synchronized
    fun addRecent(ch: Channel) {
        recents.removeAll { it.key == ch.key }
        recents.add(0, ch)
        while (recents.size > 30) recents.removeAt(recents.size - 1)
        saveChannels("recents.json", recents)
        bump()
    }

    fun findChannel(key: String): Channel? {
        favorites[key]?.let { return it }
        recents.firstOrNull { it.key == key }?.let { return it }
        playContext.firstOrNull { it.key == key }?.let { return it }
        return channels(key.substringBefore('|')).firstOrNull { it.key == key }
    }

    // ---------- YouTube Live ----------
    const val YT_ID = "youtube"

    @Synchronized
    fun youtubeChannels(): List<Channel> = channels(YT_ID)

    /** Ajoute une chaîne YouTube Live à la source « YouTube Live » (créée au besoin). */
    @Synchronized
    fun addYoutube(name: String, rawUrl: String, group: String): Channel {
        if (sources.none { it.id == YT_ID }) {
            sources.add(Source(YT_ID, SourceType.YOUTUBE, "YouTube Live", "youtube"))
            saveSources()
        }
        val url = YouTube.normalize(rawUrl)
        val ch = Channel(
            key = "$YT_ID|${url.hashCode()}", sourceId = YT_ID, name = name, url = url,
            logo = YouTube.thumbnail(url), group = group.ifBlank { "YouTube Live" }
        )
        val list = channels(YT_ID).filter { it.key != ch.key } + ch
        saveChannels("ch_$YT_ID.json", list)
        channelCache[YT_ID] = list
        bump()
        return ch
    }

    @Synchronized
    fun removeYoutube(key: String) {
        val list = channels(YT_ID).filter { it.key != key }
        saveChannels("ch_$YT_ID.json", list)
        channelCache[YT_ID] = list
        favorites.remove(key)?.let { saveChannels("favorites.json", favorites.values) }
        bump()
    }

    fun allChannels(): Sequence<Channel> = sources.asSequence().flatMap { channels(it.id).asSequence() }
}
