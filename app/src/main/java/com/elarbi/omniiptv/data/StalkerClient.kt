package com.elarbi.omniiptv.data

import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.Programme
import com.elarbi.omniiptv.model.Source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.TimeZone

/**
 * Client des portails Stalker / Ministra et portails MAG (portal.php), authentification par adresse MAC.
 *
 * Déroulement : handshake (jeton) → get_profile (identité MAG : sn, device_id, signature, métriques)
 * → do_auth facultatif (identifiant / mot de passe) → appels authentifiés (Bearer + cookie mac encodé).
 * Le jeton est renouvelé automatiquement s'il expire (HTTP 401/403, « Authorization failed », 50 min).
 */
class StalkerClient(private val s: Source) {
    companion object {
        const val MAG_UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        private const val X_UA = "Model: MAG250; Link: WiFi"
        private const val MAX_CHANNEL_PAGES = 400
        private const val MAX_VOD_PAGES = 150
        private const val TOKEN_LIFETIME = 50 * 60_000L

        private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
        private fun hash(algo: String, v: String) = hex(MessageDigest.getInstance(algo).digest(v.toByteArray()))
        private fun randomHex(n: Int): String {
            val b = ByteArray(n / 2)
            SecureRandom().nextBytes(b)
            return hex(b)
        }

        /** Retire les préfixes de lecteur (ffmpeg, ffrt, auto) d'une commande de lecture. */
        fun stripPrefix(v: String): String {
            val t = v.trim()
            for (p in listOf("ffrt3", "ffrt2", "ffrt", "ffmpeg", "auto")) {
                if (t.startsWith("$p ")) return t.removePrefix(p).trim()
            }
            return t
        }
    }

    /** Jeton expiré ou refusé : il faut refaire la connexion. */
    private class AuthExpired : IOException("Session expirée")

    private val input: String
    private var token = ""
    private var tokenTime = 0L
    private var api = ""

    private val mac = s.mac.trim().uppercase()

    /** Modèle de boîtier imité : champ « User-Agent » de la source = MAG250 (défaut), MAG254, MAG270, MAG322, MAG420, ou un User-Agent complet. */
    val model: String = Regex("^MAG\\d{3}$", RegexOption.IGNORE_CASE).find(s.userAgent.trim())?.value?.uppercase() ?: "MAG250"
    val userAgent: String = when {
        s.userAgent.isNotBlank() && !s.userAgent.trim().uppercase().matches(Regex("^MAG\\d{3}$")) -> s.userAgent.trim()
        model == "MAG250" -> MAG_UA
        else -> "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2721 Mobile Safari/533.3"
    }
    val xUserAgent: String get() = "Model: $model; Link: Ethernet"
    private val tz: String = TimeZone.getDefault().id
    private val sn = hash("MD5", mac).take(13)
    private val deviceId = hash("SHA-256", mac)
    private val signature = hash("SHA-256", sn + mac)
    private val hwVersion2 = hash("SHA-1", mac).lowercase()

    init {
        var u = s.url.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        if (u.endsWith("/index.html")) u = u.removeSuffix("/index.html")
        if (u.endsWith("/c")) u = u.removeSuffix("/c")
        input = u
    }

    // ---------------- Adresses ----------------
    private fun candidates(): List<String> = when {
        input.endsWith(".php") -> listOf(input)
        input.endsWith("/stalker_portal") -> listOf("$input/server/load.php", "${input.removeSuffix("/stalker_portal")}/portal.php")
        else -> listOf("$input/portal.php", "$input/stalker_portal/server/load.php", "$input/server/load.php")
    }

    private fun portalBase(endpoint: String = api) =
        endpoint.removeSuffix("/server/load.php").removeSuffix("/portal.php").removeSuffix("/load.php")

    private fun hostBase(endpoint: String = api): String = try {
        val u = URI(endpoint)
        "${u.scheme}://${u.rawAuthority}"
    } catch (e: Exception) {
        portalBase(endpoint)
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun headers(endpoint: String): Map<String, String> {
        val cookie = "mac=${enc(mac)}; stb_lang=fr; timezone=${enc(tz)}" + if (token.isNotEmpty()) "; token=$token" else ""
        val h = mutableMapOf(
            "User-Agent" to userAgent,
            "X-User-Agent" to xUserAgent,
            "Cookie" to cookie,
            "Referer" to "${portalBase(endpoint)}/c/",
            "Accept" to "*/*"
        )
        if (token.isNotEmpty()) h["Authorization"] = "Bearer $token"
        return h
    }

    /** Requête brute vers un point d'accès du portail. Renvoie le contenu de « js ». */
    private fun raw(endpoint: String, query: String): Any? {
        val txt = try {
            Http.get("$endpoint?$query&JsHttpRequest=1-xml", headers(endpoint))
        } catch (e: IOException) {
            val m = e.message.orEmpty()
            if (m.contains("401") || m.contains("403")) throw AuthExpired()
            throw e
        }
        if (txt.contains("Authorization failed", ignoreCase = true)) throw AuthExpired()
        val start = txt.indexOf('{')
        if (start < 0) throw IOException("Réponse inattendue du portail")
        return JSONObject(txt.substring(start)).opt("js")
    }

    /** Appel authentifié, avec reconnexion automatique si le jeton a expiré. */
    private fun call(query: String): Any? {
        login()
        return try {
            raw(api, query)
        } catch (e: AuthExpired) {
            synchronized(this) { token = "" }
            login()
            raw(api, query)
        }
    }

    // ---------------- Connexion ----------------
    @Synchronized
    fun login() {
        if (token.isNotEmpty() && api.isNotEmpty() && System.currentTimeMillis() - tokenTime < TOKEN_LIFETIME) return
        if (!Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(mac)) throw IOException("Adresse MAC invalide : $mac")
        val list = (listOfNotNull(api.ifBlank { null }) + candidates()).distinct()
        var last: Exception? = null
        for ((n, c) in list.withIndex()) {
            try {
                Loading.step("Connexion au portail (adresse ${n + 1}/${list.size})…")
                token = ""
                var js = try {
                    raw(c, "type=stb&action=handshake&token=") as? JSONObject
                } catch (e: IOException) {
                    if (e is AuthExpired || e.message.orEmpty().contains("404")) null else throw e
                }
                if (js == null || js.str("token").isBlank()) {
                    // Certains portails exigent un jeton proposé par l'appareil et son « prehash ».
                    val gen = randomHex(32)
                    js = raw(c, "type=stb&action=handshake&token=$gen&prehash=${hash("SHA-1", gen)}") as? JSONObject
                }
                val t = js?.str("token").orEmpty()
                if (t.isBlank()) continue
                token = t
                tokenTime = System.currentTimeMillis()
                api = c
                Loading.step("Authentification de l'adresse MAC…")
                profile()
                if (s.username.isNotBlank()) doAuth()
                return
            } catch (e: Exception) {
                token = ""
                last = e
                // Message explicite du portail (MAC refusée, compte bloqué…) : inutile d'essayer d'autres adresses.
                if (e is PortalMessage) throw IOException(e.message)
            }
        }
        throw IOException("Portail injoignable ou adresse MAC non autorisée" + (last?.message?.let { " ($it)" } ?: ""))
    }

    private class PortalMessage(msg: String) : IOException(msg)

    private fun metrics(): String = JSONObject()
        .put("mac", mac).put("sn", sn).put("type", "STB").put("model", model)
        .put("uid", deviceId).put("random", randomHex(40).lowercase())
        .toString()

    /** Profil de l'appareil : obligatoire pour activer la session sur la plupart des portails. */
    private fun profile() {
        val ver = "ImageDescription: 0.2.18-r23-250; ImageDate: Fri Jan 15 15:20:44 EET 2016; PORTAL version: 5.6.1; " +
            "API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c"
        val q = "type=stb&action=get_profile&hd=1&ver=${enc(ver)}&num_banks=2&sn=$sn&stb_type=$model&client_type=STB" +
            "&image_version=218&video_out=hdmi&device_id=$deviceId&device_id2=$deviceId&signature=$signature" +
            "&auth_second_step=1&hw_version=1.7-BD-00&not_valid_token=0&metrics=${enc(metrics())}" +
            "&hw_version_2=$hwVersion2&timestamp=${System.currentTimeMillis() / 1000}&api_signature=262&prehash="
        val js = try { raw(api, q) as? JSONObject } catch (e: AuthExpired) { throw PortalMessage("Adresse MAC refusée par le portail") }
        js?.str("token")?.takeIf { it.isNotBlank() }?.let { token = it }
        val block = js?.str("block_msg").orEmpty()
        if (block.isNotBlank()) throw PortalMessage("Message du portail : $block")
    }

    /** Portails avec identifiant et mot de passe en plus de la MAC. */
    private fun doAuth() {
        val r = raw(
            api, "type=stb&action=do_auth&login=${enc(s.username)}&password=${enc(s.password)}" +
                "&device_id=$deviceId&device_id2=$deviceId"
        )
        if (r == false || r?.toString() == "false") throw PortalMessage("Identifiant ou mot de passe refusé par le portail")
    }

    // ---------------- Pagination ----------------
    /** Récupère toutes les pages d'une liste (total_items / max_page_items). */
    private fun paged(
        baseQuery: String, maxPages: Int, onPage: ((page: Int, pages: Int, count: Int) -> Unit)? = null
    ): Pair<List<JSONObject>, Int> {
        val out = ArrayList<JSONObject>()
        var p = 1
        var pages = 1
        var fetched = 0
        while (p <= pages && p <= maxPages) {
            val js = call("$baseQuery&p=$p") as? JSONObject ?: break
            fetched++
            val data = js.optJSONArray("data") ?: break
            for (i in 0 until data.length()) data.optJSONObject(i)?.let { out += it }
            if (data.length() == 0) break
            val total = js.str("total_items").toIntOrNull() ?: 0
            val per = js.str("max_page_items").toIntOrNull()?.takeIf { it > 0 } ?: data.length()
            pages = if (total > 0) (total + per - 1) / per else p
            onPage?.invoke(p, minOf(pages, maxPages), out.size)
            p++
        }
        return out to fetched
    }

    private fun categories(type: String, action: String): List<Pair<String, String>> = try {
        val a = call("type=$type&action=$action") as? JSONArray ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.str("id") to it.str("title") }
    } catch (e: Exception) {
        emptyList()
    }

    private fun logoUrl(v: String): String = when {
        v.isBlank() -> ""
        v.startsWith("http") -> v
        v.startsWith("/") -> hostBase() + v
        else -> "${portalBase()}/misc/logos/320/$v"
    }

    private fun imageUrl(v: String): String = when {
        v.isBlank() -> ""
        v.startsWith("http") -> v
        v.startsWith("/") -> hostBase() + v
        else -> "${portalBase()}/$v"
    }

    // ---------------- Chaînes ----------------
    fun channels(): List<Channel> {
        login()
        Loading.step("Catégories de chaînes…")
        val genres = categories("itv", "get_genres").toMap()
        val items = ArrayList<JSONObject>()
        try {
            Loading.step("Liste complète des chaînes…")
            val arr = when (val js = call("type=itv&action=get_all_channels")) {
                is JSONObject -> js.optJSONArray("data")
                is JSONArray -> js
                else -> null
            }
            if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items += it }
        } catch (_: Exception) {
        }
        if (items.isEmpty()) {
            // Portails qui n'autorisent que la liste paginée.
            items += paged("type=itv&action=get_ordered_list&genre=*&fav=0&sortby=number", MAX_CHANNEL_PAGES) { p, pages, n ->
                Loading.step("Chaînes : page $p / $pages", p, pages)
                Loading.items(n)
            }.first
        }
        if (items.isEmpty()) throw IOException("Aucune chaîne renvoyée par le portail (abonnement inactif ?)")
        Loading.items(items.size)
        return items.distinctBy { it.str("id") }.map { o ->
            val id = o.str("id")
            val adult = o.str("censored") == "1"
            val group = (genres[o.str("tv_genre_id")]?.ifBlank { null } ?: "Chaînes") + if (adult) " (18+)" else ""
            Channel(
                key = "${s.id}|stk$id", sourceId = s.id, name = o.str("name"),
                url = "stalker:" + o.str("cmd"), logo = logoUrl(o.str("logo")), group = group,
                epgId = o.str("xmltv_id").ifBlank { "stk:$id" }, userAgent = userAgent
            )
        }
    }

    /** Lien de lecture temporaire d'une chaîne (type itv) ou d'un film / épisode (type vod). */
    fun createLink(cmd: String, type: String = "itv", series: String = ""): String {
        val direct = clean(cmd)
        var lastError: Exception? = null
        for (attempt in 0..1) {
            try {
                if (attempt == 1) synchronized(this) { token = "" }   // second essai avec une session neuve
                val js = call(
                    "type=$type&action=create_link&cmd=${enc(cmd)}&series=${enc(series)}" +
                        "&forced_storage=undefined&disable_ad=0&download=0"
                ) as? JSONObject
                val c = clean(js?.str("url")?.ifBlank { null } ?: js?.str("cmd").orEmpty())
                if (c.isNotBlank() && !isPlaceholder(c)) return c
                val err = js?.str("error").orEmpty()
                if (err.isNotBlank()) lastError = IOException("Le portail refuse la lecture : $err")
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (!isPlaceholder(direct) && direct.startsWith("http")) return direct
        throw IOException(
            "Le portail n'a pas fourni de lien de lecture pour cette chaîne" +
                (lastError?.message?.let { " ($it)" } ?: " (chaîne non incluse dans l'abonnement, ou connexion déjà utilisée ailleurs)")
        )
    }

    /** Commande « fictive » (http://localhost/…) qui ne peut être lue sans create_link. */
    private fun isPlaceholder(u: String) = u.contains("://localhost") || u.isBlank() || !u.contains("://")

    private fun clean(v: String): String {
        val t = stripPrefix(v)
        return if (t.startsWith("/") && api.isNotEmpty()) hostBase() + t else t
    }

    // ---------------- Films et séries ----------------
    /** Films et séries du portail (limité pour garder un chargement rapide). */
    /** Nombre d'éléments par catégorie au dernier chargement (pour ne recharger que ce qui a changé). */
    val vodCounts = HashMap<String, Int>()

    fun vod(
        previous: List<Channel> = emptyList(), savedCounts: Map<String, Int> = emptyMap(),
        onBatch: ((List<Channel>) -> Unit)? = null
    ): List<Channel> {
        val previousByGroup = previous.groupBy { it.group }
        var reused = 0
        login()
        val out = ArrayList<Channel>()
        var budget = MAX_VOD_PAGES
        val base = Loading.items
        Loading.step("Catégories de films et séries…")
        val cats = categories("vod", "get_categories").filter { it.first != "*" && it.first.isNotBlank() }
        for ((ci, cat) in cats.withIndex()) {
            val (cid, title) = cat
            if (budget <= 0) break
            Loading.step("Films et séries : « $title » (${ci + 1}/${cats.size})", ci + 1, cats.size)
            // Comparaison rapide : si le nombre d'éléments de la catégorie n'a pas changé, on garde la liste enregistrée.
            val first = try { call("type=vod&action=get_ordered_list&category=$cid&sortby=added&p=1") as? JSONObject } catch (e: Exception) { null }
            val total = first?.str("total_items")?.toIntOrNull() ?: -1
            if (total >= 0) vodCounts[cid] = total
            val kept = (previousByGroup["Films · $title"].orEmpty() + previousByGroup["Séries · $title"].orEmpty())
            if (total > 0 && savedCounts[cid] == total && kept.isNotEmpty()) {
                out += kept
                reused++
                Loading.step("Films et séries : « $title » inchangée (${ci + 1}/${cats.size})", ci + 1, cats.size)
                onBatch?.invoke(ArrayList(out))
                continue
            }
            if (total == 0) continue
            val (items, used) = paged("type=vod&action=get_ordered_list&category=$cid&sortby=added", minOf(budget, 20)) { p, pages, n ->
                Loading.step("Films et séries : « $title » (${ci + 1}/${cats.size}) — page $p/$pages", ci + 1, cats.size)
                Loading.items(base + out.size + n)
            }
            budget -= used
            for (o in items) {
                val id = o.str("id")
                val img = imageUrl(o.str("screenshot_uri").ifBlank { o.str("logo") })
                if (o.str("is_series") == "1") {
                    // Ancien format : liste de numéros d'épisodes et commande unique.
                    val nums = o.optJSONArray("series")?.let { a -> (0 until a.length()).joinToString(",") { a.optString(it) } }.orEmpty()
                    val extra = if (nums.isNotBlank()) "|$nums|${o.str("cmd")}" else ""
                    out += Channel(
                        key = "${s.id}|SS$id", sourceId = s.id, name = o.str("name"), url = "stkseries:$id$extra",
                        logo = img, group = "Séries · $title", userAgent = userAgent, isVod = true
                    )
                } else {
                    out += Channel(
                        key = "${s.id}|SV$id", sourceId = s.id, name = o.str("name"), url = "stkvod::" + o.str("cmd"),
                        logo = img, group = "Films · $title", userAgent = userAgent, isVod = true
                    )
                }
            }
            // Enregistrement progressif, catégorie par catégorie.
            onBatch?.invoke(ArrayList(out))
        }
        if (reused > 0) Loading.step("Films et séries : $reused catégorie(s) inchangée(s) reprises de la liste enregistrée")
        return out
    }

    /** Épisodes d'une série (saisons → épisodes, ou ancien format numéroté). */
    fun episodes(seriesUrl: String, seriesName: String): List<Channel> {
        val spec = seriesUrl.removePrefix("stkseries:")
        val id = spec.substringBefore('|')
        val out = ArrayList<Channel>()
        val seasons = (call("type=vod&action=get_ordered_list&movie_id=$id&season_id=0&episode_id=0") as? JSONObject)
            ?.optJSONArray("data")
        val seasonObjs = (0 until (seasons?.length() ?: 0)).mapNotNull { seasons?.optJSONObject(it) }
            .filter { it.str("is_season") == "1" || it.str("is_season") == "true" }
        for ((n, season) in seasonObjs.withIndex()) {
            val sid = season.str("id")
            val sNum = season.str("season_number").ifBlank { (n + 1).toString() }
            val eps = (call("type=vod&action=get_ordered_list&movie_id=$id&season_id=$sid&episode_id=0") as? JSONObject)
                ?.optJSONArray("data") ?: continue
            for (i in 0 until eps.length()) {
                val e = eps.optJSONObject(i) ?: continue
                out += Channel(
                    key = "${s.id}|E${sid}_${e.str("id")}", sourceId = s.id,
                    name = "S$sNum E${e.str("series_number").ifBlank { (i + 1).toString() }} · ${e.str("name")}",
                    url = "stkvod::" + e.str("cmd"), logo = imageUrl(e.str("screenshot_uri")),
                    group = seriesName, userAgent = userAgent, isVod = true
                )
            }
        }
        if (out.isEmpty() && spec.contains('|')) {
            val parts = spec.split('|', limit = 3)
            val nums = parts.getOrNull(1).orEmpty().split(',').filter { it.isNotBlank() }
            val cmd = parts.getOrNull(2).orEmpty()
            nums.forEach { num ->
                out += Channel(
                    key = "${s.id}|E${id}_$num", sourceId = s.id, name = "Épisode $num", url = "stkvod:$num:$cmd",
                    group = seriesName, userAgent = userAgent, isVod = true
                )
            }
        }
        return out
    }

    // ---------------- Guide TV ----------------
    private fun ts(o: JSONObject, vararg keys: String): Long {
        for (k in keys) {
            val v = o.str(k)
            val n = v.toLongOrNull() ?: continue
            return if (n > 10_000_000_000L) n else n * 1000
        }
        return 0L
    }

    private fun programmes(a: JSONArray): List<Programme> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
        Programme(
            ts(o, "start_timestamp", "start"), ts(o, "stop_timestamp", "end", "stop"),
            o.str("name").ifBlank { o.str("title") }, o.str("descr").ifBlank { o.str("description") }
        )
    }.filter { it.start > 0 && it.end > it.start }.sortedBy { it.start }

    fun epg(): Map<String, List<Programme>> {
        val js = try { call("type=itv&action=get_epg_info&period=24") as? JSONObject } catch (e: Exception) { null } ?: return emptyMap()
        val data = js.optJSONObject("data") ?: return emptyMap()
        val out = HashMap<String, List<Programme>>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val l = programmes(data.optJSONArray(id) ?: continue)
            if (l.isNotEmpty()) out["stk:$id"] = l
        }
        return out
    }

    /** Guide court d'une chaîne (secours quand le guide global est vide). */
    fun shortEpg(chId: String): List<Programme> {
        val js = call("type=itv&action=get_short_epg&ch_id=$chId&size=10")
        val a = when (js) {
            is JSONArray -> js
            is JSONObject -> js.optJSONArray("data") ?: js.optJSONArray("epg")
            else -> null
        } ?: return emptyList()
        return programmes(a)
    }

    // ---------------- Compte ----------------
    /** Informations du compte (identifiant, date d'expiration, offre). */
    fun accountInfo(): String {
        val js = call("type=account_info&action=get_main_info") as? JSONObject ?: return "Aucune information fournie par le portail."
        val lines = ArrayList<String>()
        js.str("login").takeIf { it.isNotBlank() }?.let { lines += "Identifiant : $it" }
        lines += "Adresse MAC : ${js.str("mac").ifBlank { mac }}"
        val exp = js.str("expire_billing_date").ifBlank { js.str("phone") }.ifBlank { js.str("end_date") }
        if (exp.isNotBlank()) lines += "Expiration : $exp"
        js.str("tariff_plan").takeIf { it.isNotBlank() }?.let { lines += "Offre : $it" }
        js.str("status").takeIf { it.isNotBlank() }?.let { lines += "Statut : " + if (it == "1" || it == "0") (if (it == "1") "actif" else "inactif") else it }
        lines += "Numéro de série (sn) : $sn"
        return lines.joinToString("\n")
    }

    /** Signal de présence (certains portails coupent la session sans lui). */
    fun watchdog() {
        try { call("type=watchdog&action=get_events&event_active_id=0&init=0&cur_play_type=1") } catch (_: Exception) {}
    }

    /** Diagnostic pour l'écran des sources. */
    fun diagnostic(): String = buildString {
        appendLine("Adresse saisie : ${s.url}")
        appendLine("Adresse MAC : $mac   (sn $sn)")
        appendLine("Boîtier imité : $model")
        try {
            login()
            appendLine("Point d'accès : $api")
            appendLine("Connexion : OK (jeton obtenu)")
        } catch (e: Exception) {
            appendLine("Connexion : ÉCHEC — ${e.message}")
            return@buildString
        }
        try { appendLine(accountInfo()) } catch (e: Exception) { appendLine("Compte : ${e.message}") }
        // Test de lecture sur la première chaîne
        try {
            val ch = channels().firstOrNull() ?: run { appendLine("Chaînes : aucune"); return@buildString }
            appendLine("Chaînes : ${channels().size} — test sur « ${ch.name} »")
            appendLine("Commande : ${ch.url.removePrefix("stalker:").take(90)}")
            val link = createLink(ch.url.removePrefix("stalker:"))
            appendLine("Lien obtenu : ${link.replace(Regex("(play_token|token)=[^&]+"), "$1=…").take(140)}")
            appendLine(probe(link))
        } catch (e: Exception) {
            appendLine("Test de lecture : ÉCHEC — ${e.message}")
        }
    }

    /** Ouvre le lien comme le lecteur (en-têtes MAG) et identifie le format reçu. */
    private fun probe(link: String): String = try {
        val h = mapOf(
            "User-Agent" to userAgent, "X-User-Agent" to xUserAgent,
            "Cookie" to "mac=${enc(mac)}; stb_lang=fr; timezone=${enc(tz)}"
        )
        Http.open(link, h).use { input ->
            val buf = ByteArray(2048)
            var n = 0
            while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
            val head = String(buf, 0, minOf(n, 64), Charsets.ISO_8859_1)
            val format = when {
                n >= 189 && buf[0] == 0x47.toByte() && buf[188] == 0x47.toByte() -> "flux MPEG-TS valide"
                head.startsWith("#EXTM3U") -> "liste HLS (m3u8) valide"
                head.contains("ftyp") -> "fichier MP4"
                head.trimStart().startsWith("<") || head.trimStart().startsWith("{") -> "page d'erreur du serveur : " + head.trim().take(60)
                n == 0 -> "réponse vide"
                else -> "format inconnu (${n} octets reçus)"
            }
            "Test de lecture : réponse reçue — $format"
        }
    } catch (e: Exception) {
        "Test de lecture : ÉCHEC — ${e.message} (lien refusé, expiré, ou serveur de diffusion injoignable)"
    }
}
