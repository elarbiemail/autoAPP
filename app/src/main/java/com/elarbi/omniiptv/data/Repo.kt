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
        Library.init()
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
        file("epgicons_$id.json").delete()
        file("epgindex_$id.json").delete()
        file("edits_$id.json").delete()
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
            SourceType.TUNER -> TunerSource.channels(app)
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

    // ---------- Guides TV ----------
    /** Guides XMLTV ajoutés par l'utilisateur, utilisés pour toutes les sources. */
    fun globalEpgUrls(): List<String> =
        prefs.getString("global_epg", "").orEmpty().split('\n').map { it.trim() }.filter { it.isNotBlank() }

    fun setGlobalEpgUrls(list: List<String>) {
        prefs.edit().putString("global_epg", list.distinct().joinToString("\n")).apply()
        bump()
    }

    fun autoEpgUrl(sourceId: String): String = prefs.getString("autoepg_$sourceId", "").orEmpty()

    fun epgStat(sourceId: String): String = prefs.getString("epgstat_$sourceId", "").orEmpty()

    suspend fun refreshEpg(s: Source): Unit = withContext(Dispatchers.IO) {
        val chans = channels(s.id).filter { !it.isVod }.map { Edits.apply(it) }
        if (chans.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val from = now - 24 * 3600_000L
        val to = now + 48 * 3600_000L
        val merged = HashMap<String, List<Programme>>()
        val icons = HashMap<String, String>()
        val index = ArrayList<Pair<String, String>>()
        if (s.type == SourceType.STALKER) {
            try { merged.putAll(stalker(s.id).epg()) } catch (e: Exception) { Log.w("Repo", "stalker epg", e) }
        }
        if (s.type == SourceType.TUNER) merged.putAll(TunerSource.programmes(app, chans, from, to))
        val own = s.epgUrl.ifBlank { autoEpgUrl(s.id) }.split(',').map { it.trim() }.filter { it.isNotBlank() }.take(3)
        val urls = (own + globalEpgUrls()).distinct()
        val ids = chans.filter { it.epgId.isNotBlank() }.map { it.epgId.lowercase() }.toSet()
        val names = chans.map { EpgNorm.norm(it.name) }.filter { it.isNotEmpty() }.toSet()
        var errors = 0
        for (u in urls) {
            try {
                val r = Http.open(u, uaHeaders(s)).use { Xmltv.parse(it, ids, names, from, to) }
                r.programmes.forEach { (k, v) -> merged.putIfAbsent(k, v) }
                r.icons.forEach { (k, v) -> icons.putIfAbsent(k, v) }
                index.addAll(r.index)
            } catch (e: Exception) {
                errors++
                Log.w("Repo", "EPG $u", e)
            }
        }
        if (merged.isEmpty() && urls.isNotEmpty() && errors == urls.size) throw IOException("Guide TV injoignable")
        val o = JSONObject()
        merged.forEach { (k, list) ->
            val a = JSONArray()
            list.forEach { p -> a.put(JSONArray().put(p.start).put(p.end).put(p.title).put(p.desc)) }
            o.put(k, a)
        }
        file("epg_${s.id}.json").writeText(o.toString())
        val io = JSONObject()
        icons.forEach { (k, v) -> io.put(k, v) }
        file("epgicons_${s.id}.json").writeText(io.toString())
        val ix = JSONArray()
        index.distinctBy { it.first }.forEach { ix.put(JSONArray().put(it.first).put(it.second)) }
        file("epgindex_${s.id}.json").writeText(ix.toString())
        synchronized(this@Repo) {
            epgCache[s.id] = merged
            iconCache[s.id] = icons
        }
        val matched = chans.count { c -> epgKeys(c).any { merged.containsKey(it) } }
        prefs.edit().putString("epgstat_${s.id}", "$matched/${chans.size}").apply()
        bump()
    }

    private val iconCache = HashMap<String, Map<String, String>>()
    private val shortCache = HashMap<String, List<Programme>>()

    fun epgKeys(ch: Channel): List<String> = listOfNotNull(
        ch.epgId.takeIf { it.isNotBlank() }?.lowercase(),
        "name:" + EpgNorm.norm(ch.name)
    )

    @Synchronized
    fun epgIcon(ch: Channel): String {
        val m = iconCache.getOrPut(ch.sourceId) {
            val f = file("epgicons_${ch.sourceId}.json")
            if (!f.exists()) return@getOrPut emptyMap()
            try {
                val o = JSONObject(f.readText())
                val r = HashMap<String, String>()
                val keys = o.keys()
                while (keys.hasNext()) { val k = keys.next(); r[k] = o.getString(k) }
                r
            } catch (e: Exception) { emptyMap() }
        }
        if (m.isEmpty()) return ""
        for (k in epgKeys(ch)) m[k]?.let { return it }
        return ""
    }

    /** Liste (identifiant, nom) des chaînes présentes dans les guides, pour l'association manuelle. */
    fun epgIndex(sourceId: String): List<Pair<String, String>> = try {
        val a = JSONArray(file("epgindex_$sourceId.json").readText())
        List(a.length()) { a.getJSONArray(it).let { e -> e.getString(0) to e.getString(1) } }
    } catch (e: Exception) {
        emptyList()
    }

    /** Guide de secours : EPG court du serveur Xtream pour une chaîne sans programmes. */
    suspend fun fetchShortEpg(ch: Channel) = withContext(Dispatchers.IO) {
        if (programmes(ch).isNotEmpty()) return@withContext
        val s = sources.firstOrNull { it.id == ch.sourceId } ?: return@withContext
        if (s.type != SourceType.XTREAM) return@withContext
        val sid = ch.key.substringAfter("|L", "")
        if (sid.isBlank()) return@withContext
        try {
            val l = XtreamClient(s).shortEpg(sid)
            if (l.isNotEmpty()) synchronized(this@Repo) { shortCache[ch.key] = l }
        } catch (_: Exception) {
        }
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
        val now = System.currentTimeMillis()
        synchronized(this) { shortCache[ch.key] }?.filter { it.end > now }?.takeIf { it.isNotEmpty() }?.let { return it }
        val m = epg(ch.sourceId)
        for (k in epgKeys(ch)) {
            val l = m[k]?.filter { it.end > now }
            if (!l.isNullOrEmpty()) return l
        }
        return emptyList()
    }

    /** Tous les programmes connus (passés compris), pour la grille du guide et le replay. */
    fun allProgrammes(ch: Channel): List<Programme> {
        synchronized(this) { shortCache[ch.key] }?.takeIf { it.isNotEmpty() }?.let { return it }
        val m = epg(ch.sourceId)
        for (k in epgKeys(ch)) m[k]?.takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
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
        playContext.firstOrNull { it.key == key }?.let { return it }
        favorites[key]?.let { return Edits.apply(it) }
        recents.firstOrNull { it.key == key }?.let { return Edits.apply(it) }
        Library.find(key)?.let { return it }
        return channels(key.substringBefore('|')).firstOrNull { it.key == key }?.let { Edits.apply(it) }
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
