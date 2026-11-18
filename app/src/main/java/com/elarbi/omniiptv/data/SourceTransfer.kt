package com.elarbi.omniiptv.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.elarbi.omniiptv.model.Source
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * Envoi des sources d'un appareil à un autre sur le réseau local (téléphone → téléviseur).
 * Le téléviseur ouvre un petit serveur et s'annonce sur le réseau (« _omniiptv._tcp ») ;
 * le téléphone le trouve, puis envoie ses sources avec le code à 4 chiffres affiché sur le téléviseur.
 * Rien ne passe par Internet.
 */
object SourceTransfer {
    const val SERVICE = "_omniiptv._tcp."
    const val PORT = 47860

    // ---------------- Contenu échangé ----------------
    fun export(withLists: Boolean): JSONObject {
        val o = JSONObject().put("app", "omniiptv").put("v", 1)
        val a = JSONArray()
        Repo.sources.filter { it.id != Repo.YT_ID && it.type != com.elarbi.omniiptv.model.SourceType.TUNER }.forEach { a.put(it.toJson()) }
        o.put("sources", a)
        if (withLists) {
            val l = JSONArray()
            for (fl in FavLists.lists()) {
                val items = JSONArray()
                FavLists.items(fl.id).forEach { items.put(it.toJson()) }
                l.put(JSONObject().put("name", fl.name).put("default", fl.id == FavLists.DEFAULT).put("items", items))
            }
            o.put("lists", l)
        }
        return o
    }

    /** Ajoute (ou met à jour) les sources reçues ; renvoie un résumé. */
    fun import(o: JSONObject): String {
        if (o.optString("app") != "omniiptv") throw IllegalArgumentException("Données non reconnues")
        val a = o.optJSONArray("sources") ?: JSONArray()
        var added = 0; var updated = 0
        for (i in 0 until a.length()) {
            val s = Source.fromJson(a.getJSONObject(i))
            // Même serveur et mêmes identifiants déjà présents : mise à jour plutôt que doublon.
            val same = Repo.sources.firstOrNull { it.id == s.id } ?: Repo.sources.firstOrNull {
                it.type == s.type && it.url.trimEnd('/') == s.url.trimEnd('/') && it.username == s.username && it.mac.equals(s.mac, true)
            }
            if (same != null) { Repo.upsertSource(s.copy(id = same.id)); updated++ } else { Repo.upsertSource(s); added++ }
        }
        var lists = 0
        o.optJSONArray("lists")?.let { ls ->
            for (i in 0 until ls.length()) {
                val l = ls.getJSONObject(i)
                val items = l.optJSONArray("items") ?: continue
                val chans = (0 until items.length()).mapNotNull { j -> try { com.elarbi.omniiptv.model.Channel.fromJson(items.getJSONObject(j)) } catch (e: Exception) { null } }
                if (chans.isEmpty()) continue
                val id = if (l.optBoolean("default")) FavLists.DEFAULT
                else FavLists.lists().firstOrNull { it.name == l.optString("name") }?.id ?: FavLists.create(l.optString("name", "Liste reçue")).id
                FavLists.add(id, chans)
                lists++
            }
        }
        if (Repo.activeSourceId == null) Repo.sources.firstOrNull()?.let { Repo.activeSourceId = it.id }
        Repo.bump()
        return buildString {
            append("$added source(s) ajoutée(s)")
            if (updated > 0) append(", $updated mise(s) à jour")
            if (lists > 0) append(", $lists liste(s) de favoris")
        }
    }

    // ---------------- Réception (téléviseur) ----------------
    class Receiver(private val ctx: Context, val code: String, private val onReceived: (String) -> Unit) {
        private var server: ServerSocket? = null
        private var reg: NsdManager.RegistrationListener? = null
        @Volatile private var running = false
        var port = PORT; private set

        fun start() {
            server = try { ServerSocket(PORT) } catch (e: Exception) { ServerSocket(0) }
            port = server!!.localPort
            running = true
            Thread({ loop() }, "omni-transfer").start()
            try {
                val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
                val serverPort = port
                val info = NsdServiceInfo().apply {
                    serviceName = "Omni IPTV · " + android.os.Build.MODEL
                    serviceType = SERVICE
                    setPort(serverPort)
                }
                val l = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(i: NsdServiceInfo) {}
                    override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                    override fun onServiceUnregistered(i: NsdServiceInfo) {}
                    override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
                }
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
                reg = l
            } catch (_: Throwable) {}
        }

        fun stop() {
            running = false
            try { server?.close() } catch (_: Exception) {}
            try { reg?.let { (ctx.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(it) } } catch (_: Throwable) {}
            reg = null
        }

        private fun loop() {
            while (running) {
                val sock = try { server?.accept() } catch (e: Exception) { null } ?: break
                try { handle(sock) } catch (_: Throwable) {} finally { try { sock.close() } catch (_: Exception) {} }
            }
        }

        /** Lit une ligne d'en-tête (octets jusqu'à CRLF). */
        private fun readLine(input: java.io.InputStream): String? {
            val b = java.io.ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
                if (c == '\n'.code) break
                if (c != '\r'.code) b.write(c)
                if (b.size() > 8192) break
            }
            return b.toString("UTF-8")
        }

        private fun handle(sock: Socket) {
            sock.soTimeout = 15_000
            val input = java.io.BufferedInputStream(sock.getInputStream())
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len > 5_000_000) { reply(sock, 413, "Trop gros"); return }
            // Corps lu en octets (la longueur annoncée est en octets, pas en caractères).
            val bytes = ByteArray(len)
            var n = 0
            while (n < len) { val k = input.read(bytes, n, len - n); if (k <= 0) break; n += k }
            val body = String(bytes, 0, n, Charsets.UTF_8)
            when {
                requestLine.startsWith("GET /hello") -> reply(sock, 200, JSONObject().put("app", "omniiptv").put("name", android.os.Build.MODEL).toString())
                !requestLine.startsWith("POST /import") -> reply(sock, 404, "Inconnu")
                headers["x-omni-code"] != code -> reply(sock, 403, "Code incorrect")
                else -> {
                    val summary = try { import(JSONObject(body)) } catch (e: Exception) { reply(sock, 400, "Données invalides : ${e.message}"); return }
                    reply(sock, 200, summary)
                    onReceived(summary)
                }
            }
        }

        private fun reply(sock: Socket, status: Int, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            sock.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
        }
    }

    /** Adresse IPv4 locale de l'appareil (Wi-Fi ou Ethernet). */
    fun localAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
    } catch (e: Exception) { null }

    // ---------------- Envoi (téléphone) ----------------
    data class Target(val name: String, val host: String, val port: Int)

    /** Recherche des téléviseurs Omni IPTV prêts à recevoir, pendant [ms] millisecondes. */
    fun discover(ctx: Context, ms: Long = 4000): List<Target> {
        val found = java.util.concurrent.CopyOnWriteArrayList<Target>()
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val lock = try { wifi?.createMulticastLock("omni")?.apply { setReferenceCounted(false); acquire() } } catch (e: Throwable) { null }
        val pending = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) { pending.offer(i) }
        }
        try {
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
            val end = System.currentTimeMillis() + ms
            // Résolution une par une (Android n'en accepte qu'une à la fois).
            while (System.currentTimeMillis() < end) {
                val i = pending.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                val done = java.util.concurrent.CountDownLatch(1)
                @Suppress("DEPRECATION")
                nsd.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) { done.countDown() }
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = s.host?.hostAddress
                        if (host != null && found.none { it.host == host }) found += Target(s.serviceName, host, s.port)
                        done.countDown()
                    }
                })
                done.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        } catch (_: Throwable) {
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Throwable) {}
            try { lock?.release() } catch (_: Throwable) {}
        }
        return found.toList()
    }

    /** Envoie les sources au téléviseur ; renvoie sa réponse (résumé) ou lève une erreur lisible. */
    fun send(host: String, port: Int, code: String, withLists: Boolean): String {
        val c = URL("http://$host:$port/import").openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 20000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("X-Omni-Code", code)
        c.outputStream.use { it.write(export(withLists).toString().toByteArray(Charsets.UTF_8)) }
        val status = c.responseCode
        val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (status == 403) throw IllegalStateException("Code incorrect : vérifiez le code affiché sur le téléviseur.")
        if (status !in 200..299) throw IllegalStateException("Le téléviseur a refusé l'envoi ($status) : $text")
        return text
    }
}
