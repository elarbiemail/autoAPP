package com.elarbi.omniiptv.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal des erreurs : les plantages (et les erreurs de mémoire rattrapées) sont enregistrés avec
 * l'état de l'appareil, puis affichés au démarrage suivant et dans Réglages > Sauvegarde et aide.
 */
object CrashLog {
    private lateinit var dir: File
    private lateinit var ctx: Context
    private val file get() = File(dir, "journal.txt")
    private val pending get() = File(dir, "crash_pending.txt")

    fun install(c: Context) {
        ctx = c.applicationContext
        dir = ctx.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val text = entry("PLANTAGE (fil « ${t.name} »)", e)
                pending.writeText(text)
                append(text)
            } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }

    /** Erreur rattrapée (l'application continue) : seulement consignée. */
    fun record(where: String, e: Throwable) {
        try { append(entry(where, e)) } catch (_: Throwable) {}
    }

    private fun entry(title: String, e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val trace = sw.toString().lines().take(40).joinToString("\n")
        return "=== ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date())} — $title ===\n" +
            device() + "\n" + trace + "\n"
    }

    fun device(): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        val version = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" }
        return "Omni IPTV $version • ${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}\n" +
            "Mémoire appli : ${(rt.totalMemory() - rt.freeMemory()) / 1048576}/${rt.maxMemory() / 1048576} Mo • " +
            "téléviseur : ${mi.availMem / 1048576}/${mi.totalMem / 1048576} Mo libres${if (mi.lowMemory) " (presque pleine)" else ""} • " +
            "stockage libre : ${dir.usableSpace / 1048576} Mo"
    }

    private fun append(text: String) {
        val old = if (file.exists()) file.readText().takeLast(60_000) else ""
        file.writeText(old + text + "\n")
    }

    /** Plantage survenu lors de la dernière utilisation (lu une seule fois). */
    fun takePending(): String? {
        if (!::dir.isInitialized || !pending.exists()) return null
        val t = try { pending.readText() } catch (e: Exception) { null }
        pending.delete()
        return t
    }

    fun journal(): String = if (::dir.isInitialized && file.exists()) file.readText() else ""

    fun clear() { if (::dir.isInitialized) { file.delete(); pending.delete() } }
}
