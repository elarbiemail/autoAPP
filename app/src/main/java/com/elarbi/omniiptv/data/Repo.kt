package com.elarbi.omniiptv.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.Programme
import com.elarbi.omniiptv.model.Source
import com.elarbi.omniiptv.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    /** Travaux longs (chargement des sources) indépendants des écrans : ils continuent si l'on quitte l'écran. */
    /** Travaux de fond : une erreur (même de mémoire) y est consignée sans fermer l'application. */
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> CrashLog.record("Tâche de fond", e) }
    )

    /** Date du dernier chargement réussi d'une source. */
    fun lastRefresh(sourceId: String): Long = prefs.getLong("lastrefresh_$sourceId", 0L)

    /** Enregistre immédiatement une liste (permet de conserver un chargement partiel). */
    fun commit(sourceId: String, list: List<Channel>) {
        saveChannels("ch_$sourceId.json", list)
        synchronized(this) { channelCache[sourceId] = list }
        bump()
    }

    /** Résumé de la dernière comparaison serveur / liste enregistrée. */
    fun lastDiff(sourceId: String): String = prefs.getString("lastdiff_$sourceId", "").orEmpty()

    /** Compare la liste du serveur à la liste enregistrée. */
    fun compare(old: List<Channel>, new: List<Channel>): String {
        if (old.isEmpty()) return "première liste enregistrée (${new.size} éléments)"
        val before = old.associateBy { it.key }
        val after = new.associateBy { it.key }
        val added = after.keys.count { it !in before }
        val removed = before.keys.count { it !in after }
        val changed = after.count { (k, c) ->
            val o = before[k]
            o != null && (o.name != c.name || o.url != c.url || o.logo != c.logo || o.group != c.group)
        }
        return if (added + removed + changed == 0) "aucun changement (${new.size} éléments)"
        else listOfNotNull(
            if (added > 0) "+$added nouveaux" else null,
            if (removed > 0) "−$removed retirés" else null,
            if (changed > 0) "$changed modifiés" else null
        ).joinToString(", ") + " (${new.size} éléments)"
    }

    /** Met à jour les copies gardées dans les favoris et les récents (nouveau lien, nouveau nom…). */
    @Synchronized
    private fun updateSnapshots(list: List<Channel>) {
        val byKey = list.associateBy { it.key }
        var fav = false
        favorites.keys.toList().forEach { k -> byKey[k]?.let { if (it != favorites[k]) { favorites[k] = it; fav = true } } }
        if (fav) saveChannels("favorites.json", favorites.values)
        var rec = false
        for (i in recents.indices) byKey[recents[i].key]?.let { if (it != recents[i]) { recents[i] = it; rec = true } }
        if (rec) saveChannels("recents.json", recents)
    }

    private fun vodCounts(sourceId: String): Map<String, Int> = try {
        val o = JSONObject(prefs.getString("vodcounts_$sourceId", "{}") ?: "{}")
        val m = HashMap<String, Int>()
        val keys = o.keys()
        while (keys.hasNext()) { val k = keys.next(); m[k] = o.getInt(k) }
        m
    } catch (e: Exception) { emptyMap() }

    private fun saveVodCounts(sourceId: String, m: Map<String, Int>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString("vodcounts_$sourceId", o.toString()).apply()
    }

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
        try { JsonStream.readChannels(file("favorites.json")).forEach { favorites[it.key] = it } } catch (_: Exception) {}
        try { recents.addAll(JsonStream.readChannels(file("recents.json"))) } catch (_: Exception) {}
        Library.init()
    }

    private fun file(name: String) = File(app.filesDir, name)
    private fun readArray(name: String): JSONArray? = file(name).takeIf { it.exists() }?.let {
        try { JSONArray(it.readText()) } catch (e: Exception) { null }
    }
    private fun saveChannels(name: String, list: Collection<Channel>) {
        JsonStream.writeChannels(file(name), list)
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

    /** Sources dont la liste n'a pas pu être chargée faute de mémoire. */
    val loadFailed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** Liste déjà en mémoire (sans lecture du disque). */
    fun cachedChannels(sourceId: String): List<Channel>? = synchronized(this) { channelCache[sourceId] }

    /** Taille du fichier de la liste enregistrée (octets). */
    fun listFileSize(sourceId: String): Long = file("ch_$sourceId.json").length()

    /**
     * Liste d'une source. Le fichier est lu hors du verrou général : une grosse liste qui se charge
     * ne bloque pas le reste de l'application (favoris, écran, lecteur).
     */
    fun channels(sourceId: String): List<Channel> {
        synchronized(this) { channelCache[sourceId]?.let { return it } }
        val list = try {
            JsonStream.readChannels(file("ch_$sourceId.json")).also { loadFailed.remove(sourceId) }
        } catch (e: OutOfMemoryError) {
            Log.w("Repo", "liste trop grosse", e)
            loadFailed.add(sourceId)
            CrashLog.record("Liste trop grosse pour la mémoire (${file("ch_$sourceId.json").length() / 1048576} Mo)", e)
            return emptyList()
        } catch (e: Exception) {
            Log.w("Repo", "liste", e); emptyList()
        }
        synchronized(this) { return channelCache.getOrPut(sourceId) { list } }
    }

    /** Vide la liste enregistrée d'une source (et son guide) ; elle sera rechargée à la prochaine actualisation. */
    fun clearList(sourceId: String) {
        file("ch_$sourceId.json").delete()
        file("epg_$sourceId.json").delete()
        prefs.edit().remove("vodcounts_$sourceId").remove("lastrefresh_$sourceId").apply()
        synchronized(this) { channelCache.remove(sourceId); epgCache.remove(sourceId) }
        loadFailed.remove(sourceId)
        Shown.invalidate()
        bump()
    }

    /** Libère la mémoire des listes et guides (les fichiers restent enregistrés). */
    fun trimMemory() {
        synchronized(this) { channelCache.clear(); epgCache.clear() }
        Shown.invalidate()
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
        Loading.start(s.name)
        try {
            refreshInner(s, withEpg)
        } catch (e: OutOfMemoryError) {
            synchronized(this@Repo) { channelCache.remove(s.id) }
            System.gc()
            throw IOException("Mémoire insuffisante pour cette liste (le portail envoie trop de données à la fois). " +
                "La liste déjà reçue est conservée ; réessayez, ou désactivez les films et séries du portail.")
        } finally { Loading.finish() }
    }

    private suspend fun refreshInner(s: Source, withEpg: Boolean): Int = withContext(Dispatchers.IO) {
        var autoEpg = ""
        checkSpace()
        val liveOnly = s.id in Prefs.liveOnlySources
        val old = channels(s.id)   // liste enregistrée, pour la comparaison
        val raw: List<Channel> = when (s.type) {
            SourceType.M3U -> {
                val r = Http.openTracked(s.url, "Téléchargement et lecture de la liste", uaHeaders(s)).use { input ->
                    input.bufferedReader(Charsets.UTF_8).useLines { lines -> M3uParser.parseLines(lines, s.id) }
                }
                autoEpg = r.epgUrl
                if (liveOnly) r.channels.filter { !it.isVod } else r.channels
            }
            SourceType.XTREAM -> {
                val x = XtreamClient(s)
                autoEpg = x.epgUrl()
                x.channels(withVod = !liveOnly)
            }
            SourceType.STALKER -> {
                synchronized(stalkers) { stalkers.remove(s.id) }
                val c = stalker(s.id)
                val previousVod = old.filter { it.isVod }
                val live = c.channels()
                // Les chaînes sont enregistrées tout de suite : elles restent disponibles même si la suite est interrompue.
                commit(s.id, live + previousVod)
                prefs.edit().putLong("lastrefresh_${s.id}", System.currentTimeMillis()).apply()
                val counts = vodCounts(s.id)
                val vod = if (liveOnly) emptyList() else try {
                    c.vod(previousVod, counts) { partial -> if (partial.size > previousVod.size) commit(s.id, live + partial) }
                } catch (e: Exception) { Log.w("Repo", "stalker vod", e); emptyList() }
                if (vod.isNotEmpty()) saveVodCounts(s.id, c.vodCounts)
                if (liveOnly) live else live + vod.ifEmpty { previousVod }
            }
            SourceType.YOUTUBE -> return@withContext channels(s.id).size
            SourceType.TUNER -> TunerSource.channels(app)
        }
        if (raw.isEmpty()) throw IOException("Aucune chaîne trouvée")
        // Films et séries mélangés (M3U, catégories VOD) : les épisodes sont regroupés en fiches séries.
        val list = if (s.type == SourceType.YOUTUBE || s.type == SourceType.TUNER) raw else SeriesGrouper.apply(s.id, raw)
        Loading.items(list.size)
        // Comparaison avec la liste enregistrée : nouveautés, retraits, modifications.
        val summary = compare(old, list)
        prefs.edit().putString("lastdiff_${s.id}", summary).apply()
        Loading.step("Comparaison avec la liste enregistrée : $summary")
        updateSnapshots(list)
        saveChannels("ch_${s.id}.json", list)
        synchronized(this@Repo) { channelCache[s.id] = list }
        loadFailed.remove(s.id)
        syncSaved(s.id, list)
        if (autoEpg.isNotBlank()) prefs.edit().putString("autoepg_${s.id}", autoEpg).apply()
        prefs.edit().putLong("lastrefresh_${s.id}", System.currentTimeMillis()).apply()
        bump()
        if (withEpg) {
            Loading.step("Guide TV…")
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
        val from = now - 3 * 3600_000L
        val to = now + 24 * 3600_000L
        val merged = HashMap<String, List<Programme>>()
        val icons = HashMap<String, String>()
        val index = ArrayList<Pair<String, String>>()
        if (s.type == SourceType.STALKER) {
            try {
                Loading.step("Guide TV du portail…")
                val stk = stalker(s.id).epg()
                // Guide du portail rangé une seule fois par chaîne (clé « stk:id », reconnue par epgKeys) :
                // le recopier sous plusieurs clés doublait ou triplait sa place sur le disque.
                merged.putAll(stk)
            } catch (e: Exception) { Log.w("Repo", "stalker epg", e) }
        }
        if (s.type == SourceType.TUNER) merged.putAll(TunerSource.programmes(app, chans, from, to))
        val own = s.epgUrl.ifBlank { autoEpgUrl(s.id) }.split(',').map { it.trim() }.filter { it.isNotBlank() }.take(3)
        val urls = (own + globalEpgUrls()).distinct()
        val ids = chans.filter { it.epgId.isNotBlank() }.map { it.epgId.lowercase() }.toSet()
        val names = chans.map { EpgNorm.norm(it.name) }.filter { it.isNotEmpty() }.toSet()
        var errors = 0
        for (u in urls) {
            try {
                val r = Http.openTracked(u, "Guide TV ${urls.indexOf(u) + 1}/${urls.size}", uaHeaders(s)).use { Xmltv.parse(it, ids, names, from, to) }
                r.programmes.forEach { (k, v) -> merged.putIfAbsent(k, v) }
                r.icons.forEach { (k, v) -> icons.putIfAbsent(k, v) }
                index.addAll(r.index)
            } catch (e: Exception) {
                errors++
                Log.w("Repo", "EPG $u", e)
            }
        }
        if (merged.isEmpty() && urls.isNotEmpty() && errors == urls.size) throw IOException("Guide TV injoignable")
        val trimmed = trimEpg(merged)
        merged.clear()
        merged.putAll(trimmed)
        JsonStream.writeEpg(file("epg_${s.id}.json"), merged)
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

    /**
     * Guide limité selon la mémoire du téléviseur : chaînes des listes de l'utilisateur d'abord,
     * 30 programmes par chaîne, descriptions gardées pour les 4 premiers seulement.
     */
    private fun trimEpg(m: Map<String, List<Programme>>): Map<String, List<Programme>> {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val budget = (am.largeMemoryClass * 400).coerceIn(30_000, 200_000)
        val now = System.currentTimeMillis()
        val prio = LinkedHashSet<String>()
        (favorites.values.toList() + recents.toList() + FavLists.lists().flatMap { FavLists.items(it.id) })
            .forEach { c -> epgKeys(c).forEach { k -> if (m.containsKey(k)) prio += k } }
        val out = HashMap<String, List<Programme>>()
        var total = 0
        for (k in prio.asSequence() + m.keys.asSequence().filter { it !in prio }) {
            if (total >= budget) break
            val l = m[k] ?: continue
            val kept = l.asSequence().filter { it.end > now - 2 * 3600_000L }.take(30).toList()
                .mapIndexed { i, p -> if (i < 4 || p.desc.isEmpty()) p else p.copy(desc = "") }
            if (kept.isEmpty()) continue
            out[k] = kept
            total += kept.size
        }
        return out
    }

    fun epgKeys(ch: Channel): List<String> = listOfNotNull(
        ch.epgId.takeIf { it.isNotBlank() }?.lowercase(),
        ch.key.substringAfter("|stk", "").takeIf { it.isNotEmpty() }?.let { "stk:$it" },
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
        if (s.type != SourceType.XTREAM && s.type != SourceType.STALKER) return@withContext
        val sid = ch.key.substringAfter(if (s.type == SourceType.XTREAM) "|L" else "|stk", "")
        if (sid.isBlank()) return@withContext
        try {
            val l = if (s.type == SourceType.XTREAM) XtreamClient(s).shortEpg(sid) else stalker(s.id).shortEpg(sid)
            if (l.isNotEmpty()) synchronized(this@Repo) { shortCache[ch.key] = l }
        } catch (_: Exception) {
        }
    }

    private val epgLoading: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /**
     * Guide d'une source. Sur le fil de l'écran, un guide pas encore en mémoire est chargé en arrière-plan
     * (rien n'est bloqué) ; il apparaît au rafraîchissement suivant.
     */
    fun epg(sourceId: String): Map<String, List<Programme>> {
        synchronized(this) { epgCache[sourceId]?.let { return it } }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            if (epgLoading.add(sourceId)) scope.launch {
                try { epg(sourceId) } finally { epgLoading.remove(sourceId) }
            }
            return emptyMap()
        }
        val m = try { JsonStream.readEpg(file("epg_$sourceId.json")) } catch (e: Throwable) { emptyMap() }
        synchronized(this) { return epgCache.getOrPut(sourceId) { m } }
    }

    // ---------- Stockage ----------
    data class Storage(val lists: Long, val guides: Long, val images: Long, val free: Long)

    private fun dirSize(f: File): Long = if (f.isDirectory) (f.listFiles() ?: emptyArray()).sumOf { dirSize(it) } else f.length()

    fun storage(): Storage {
        val files = app.filesDir.listFiles() ?: emptyArray()
        return Storage(
            lists = files.filter { it.name.startsWith("ch_") || it.name.startsWith("favlist_") }.sumOf { it.length() },
            guides = files.filter { it.name.startsWith("epg") }.sumOf { it.length() },
            images = dirSize(app.cacheDir),
            free = app.filesDir.usableSpace
        )
    }

    /** Supprime les fichiers inutiles : écritures interrompues, fichiers de sources supprimées. */
    fun cleanup() {
        val ids = sources.map { it.id }.toHashSet() + YT_ID
        for (f in app.filesDir.listFiles() ?: emptyArray()) {
            val n = f.name
            if (n.endsWith(".tmp")) { f.delete(); continue }
            val m = Regex("^(?:ch|epg|epgicons|epgindex|edits|free)_(.+?)\\.(?:json|m3u)$").find(n) ?: continue
            if (m.groupValues[1] !in ids) f.delete()
        }
    }

    /** Libère de l'espace : guides TV (retéléchargés à la prochaine actualisation) et images en cache. */
    fun freeSpace() {
        for (f in app.filesDir.listFiles() ?: emptyArray()) if (f.name.startsWith("epg")) f.delete()
        synchronized(this) { epgCache.clear() }
        try { com.bumptech.glide.Glide.get(app).clearDiskCache() } catch (_: Throwable) {}
        app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        cleanup()
    }

    /** Refuse d'écrire une grosse liste quand le téléviseur n'a presque plus de place. */
    fun checkSpace(minMb: Long = 40) {
        val free = app.filesDir.usableSpace / 1048576
        if (free < minMb) throw IOException(
            "Stockage du téléviseur presque plein ($free Mo libres). Libérez de l'espace " +
                "(Sources > Libérer de l'espace, ou désinstallez des applications), puis réessayez. La liste enregistrée est conservée."
        )
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

    /** Remplace la liste « Favoris » (nouvel ordre ou nouveaux éléments). */
    @Synchronized
    fun setFavorites(list: List<Channel>) {
        favorites.clear()
        list.forEach { favorites[it.key] = it }
        saveChannels("favorites.json", favorites.values)
        bump()
    }

    @Synchronized
    fun addRecent(ch: Channel) {
        recents.removeAll { it.key == ch.key }
        recents.add(0, ch)
        while (recents.size > 30) recents.removeAt(recents.size - 1)
        saveChannels("recents.json", recents)
        bump()
    }

    /** Source modifiée : la liste enregistrée et les favoris prennent aussitôt le nouveau serveur / les nouveaux identifiants. */
    fun applySourceChange(old: Source, new: Source) {
        if (old == new) return
        synchronized(stalkers) { stalkers.remove(new.id) }
        if (new.type == SourceType.XTREAM && (old.url != new.url || old.username != new.username ||
                old.password != new.password || old.userAgent != new.userAgent)) {
            val list = channels(new.id).map { c ->
                c.copy(url = StreamResolver.xtreamUrl(new.id, c.url), userAgent = new.userAgent)
            }
            if (list.isNotEmpty()) commit(new.id, list)
            syncSaved(new.id, list)
        }
        if (new.type == SourceType.STALKER && (old.mac != new.mac || old.url != new.url)) {
            prefs.edit().remove("vodcounts_${new.id}").apply()
        }
    }

    /** Après rechargement (ou modification du serveur) : favoris, récents et bibliothèque reprennent les liens à jour. */
    fun syncSaved(sourceId: String, list: List<Channel>) {
        val byKey = list.associateBy { it.key }
        synchronized(this) {
            var fav = false
            for ((k, c) in favorites.entries.toList()) if (c.sourceId == sourceId) byKey[k]?.let { if (it != c) { favorites[k] = it; fav = true } }
            if (fav) saveChannels("favorites.json", favorites.values)
            var rec = false
            for (i in recents.indices) { val c = recents[i]; if (c.sourceId == sourceId) byKey[c.key]?.let { if (it != c) { recents[i] = it; rec = true } } }
            if (rec) saveChannels("recents.json", recents)
            playContext = playContext.map { c -> if (c.sourceId == sourceId) byKey[c.key] ?: c else c }
        }
        Library.sync(sourceId, byKey)
        FavLists.sync(sourceId, byKey)
    }

    /** Recherche dans les listes courtes seulement (lecture, favoris, récents, reprises, listes) : instantané. */
    fun findChannelQuick(key: String): Channel? {
        playContext.firstOrNull { it.key == key }?.let { return it }
        favorites[key]?.let { return Edits.apply(it) }
        recents.firstOrNull { it.key == key }?.let { return Edits.apply(it) }
        Library.find(key)?.let { return it }
        for (l in FavLists.lists()) FavLists.items(l.id).firstOrNull { it.key == key }?.let { return Edits.apply(it) }
        return cachedChannels(key.substringBefore('|'))?.firstOrNull { it.key == key }?.let { Edits.apply(it) }
    }

    fun findChannel(key: String): Channel? {
        findChannelQuick(key)?.let { return it }
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
