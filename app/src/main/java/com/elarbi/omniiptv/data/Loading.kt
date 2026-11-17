package com.elarbi.omniiptv.data

import java.io.InputStream

/**
 * État du chargement en cours (lu par la fenêtre de progression).
 * Mis à jour depuis les fils de travail ; lu périodiquement par l'interface.
 */
object Loading {
    @Volatile var active = false; private set
    @Volatile var title = ""; private set
    @Volatile var step = ""; private set
    @Volatile var done = 0; private set
    @Volatile var total = 0; private set
    @Volatile var items = 0; private set
    @Volatile var startedAt = 0L; private set

    fun start(t: String) {
        title = t; step = "Préparation…"; done = 0; total = 0; items = 0
        startedAt = System.currentTimeMillis(); active = true
    }

    /** Étape avec progression (total = 0 : progression indéterminée). */
    fun step(text: String, done: Int = 0, total: Int = 0) {
        this.step = text; this.done = done; this.total = total
    }

    fun items(n: Int) { items = n }

    fun finish() { active = false }

    /** Enveloppe un flux pour suivre le téléchargement (octets reçus / taille annoncée). */
    fun track(input: InputStream, label: String, size: Long): InputStream = object : InputStream() {
        private var read = 0L
        private var last = 0L
        private fun upd() {
            if (read - last < 64 * 1024) return
            last = read
            val mb = String.format(java.util.Locale.FRANCE, "%.1f", read / 1_048_576.0)
            if (size > 0) step("$label : $mb Mo / ${String.format(java.util.Locale.FRANCE, "%.1f", size / 1_048_576.0)} Mo",
                (read / 1024).toInt(), (size / 1024).toInt())
            else step("$label : $mb Mo reçus")
        }
        override fun read(): Int = input.read().also { if (it >= 0) { read++; upd() } }
        override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len).also { if (it > 0) { read += it; upd() } }
        override fun close() = input.close()
    }
}
