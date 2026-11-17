package com.elarbi.omniiptv.data

import android.util.Xml
import com.elarbi.omniiptv.model.Programme
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/** Lecteur de guide TV XMLTV (.xml ou .xml.gz), en flux, filtré sur les chaînes utiles. */
object Xmltv {
    fun parse(
        input: InputStream, epgIds: Set<String>, names: Set<String>, from: Long, to: Long
    ): Map<String, List<Programme>> {
        val fmtZ = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun time(raw: String?): Long {
            if (raw == null) return 0L
            var t = raw.trim()
            return try {
                if (t.length > 14) {
                    if (t[14] != ' ') t = t.substring(0, 14) + " " + t.substring(14)
                    fmtZ.parse(t)?.time ?: 0L
                } else fmt.parse(t.take(14))?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }

        val bis = BufferedInputStream(input, 65536)
        bis.mark(4)
        val b1 = bis.read()
        val b2 = bis.read()
        bis.reset()
        val stream: InputStream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(bis, 65536) else bis

        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(stream, null)

        val alias = HashMap<String, String>()
        val res = HashMap<String, MutableList<Programme>>()
        var curChannel: String? = null
        var wanted: Set<String>? = null
        var progCh: String? = null
        var ps = 0L
        var pe = 0L
        var title = ""
        var desc = ""

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> curChannel = p.getAttributeValue(null, "id")?.lowercase()
                    "display-name" -> {
                        val c = curChannel
                        if (c != null) {
                            val n = p.nextText().lowercase().trim()
                            if (n in names) alias[n] = c
                        }
                    }
                    "programme" -> {
                        val w = wanted ?: (epgIds + alias.values).also { wanted = it }
                        val id = p.getAttributeValue(null, "channel")?.lowercase()
                        progCh = null
                        if (id != null && id in w) {
                            ps = time(p.getAttributeValue(null, "start"))
                            pe = time(p.getAttributeValue(null, "stop"))
                            if (pe > from && ps < to) {
                                progCh = id; title = ""; desc = ""
                            }
                        }
                    }
                    "title" -> if (progCh != null && title.isEmpty()) title = p.nextText()
                    "desc" -> if (progCh != null && desc.isEmpty()) desc = p.nextText()
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "channel" -> curChannel = null
                    "programme" -> {
                        val c = progCh
                        if (c != null) res.getOrPut(c) { mutableListOf() }.add(Programme(ps, pe, title, desc))
                        progCh = null
                    }
                }
            }
            ev = p.next()
        }
        val out = HashMap<String, List<Programme>>()
        res.forEach { (k, v) -> out[k] = v.sortedBy { it.start } }
        alias.forEach { (n, id) -> out[id]?.let { out["name:$n"] = it } }
        return out
    }
}
