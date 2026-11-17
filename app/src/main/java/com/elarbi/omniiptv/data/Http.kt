package com.elarbi.omniiptv.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

object Http {
    const val DEFAULT_UA = "VLC/3.0.20 LibVLC/3.0.20"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val quick: OkHttpClient = client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /** Ouvre une URL http(s) ou un fichier local. L'appelant doit fermer le flux. */
    fun open(url: String, headers: Map<String, String> = emptyMap()): InputStream {
        if (url.startsWith("/")) return File(url).inputStream()
        if (url.startsWith("file://")) return File(url.removePrefix("file://")).inputStream()
        val b = Request.Builder().url(url)
        if (headers.keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", DEFAULT_UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        val resp = client.newCall(b.build()).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw IOException("HTTP $code")
        }
        return resp.body?.byteStream() ?: throw IOException("Réponse vide")
    }

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url).post(json.toRequestBody("application/json".toMediaType()))
        if (headers.keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", DEFAULT_UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return body
        }
    }

    /** Téléchargement avec suivi de progression (listes M3U, guides TV). */
    fun openTracked(url: String, label: String, headers: Map<String, String> = emptyMap()): InputStream {
        if (url.startsWith("/") || url.startsWith("file://")) return open(url, headers)
        val b = Request.Builder().url(url)
        if (headers.keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", DEFAULT_UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        val resp = client.newCall(b.build()).execute()
        if (!resp.isSuccessful) { val c = resp.code; resp.close(); throw IOException("HTTP $c") }
        val body = resp.body ?: throw IOException("Réponse vide")
        return Loading.track(body.byteStream(), label, body.contentLength())
    }

    fun getTracked(url: String, label: String, headers: Map<String, String> = emptyMap()): String =
        openTracked(url, label, headers).use { String(it.readBytes(), Charsets.UTF_8) }

    fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        open(url, headers).use { String(it.readBytes(), Charsets.UTF_8) }

    /** Vérifie rapidement qu'un flux répond. Les protocoles non HTTP sont considérés comme valides. */
    fun isAlive(url: String, ua: String?): Boolean {
        if (!url.startsWith("http")) return true
        return try {
            val r = Request.Builder().url(url)
                .header("User-Agent", if (ua.isNullOrBlank()) DEFAULT_UA else ua)
                .header("Range", "bytes=0-2047")
                .build()
            quick.newCall(r).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}

/** optString qui renvoie "" pour les valeurs JSON null. */
fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)
