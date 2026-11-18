package com.elarbi.omniiptv.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Mémorise l'adresse finale après redirection et la réutilise pour les ouvertures suivantes
 * (rechargements de la playlist HLS en direct, reprises après coupure).
 *
 * Les portails Stalker / MAG donnent un lien « play_token » à usage unique qui redirige vers le serveur
 * de diffusion : sans cette mémoire, chaque rechargement repasse par le lien déjà consommé et le flux se coupe.
 * Même principe que le client open source stalkerhek (redirections suivies à la main, lien final conservé).
 */
@UnstableApi
class StickyRedirectDataSource(
    private val upstream: DataSource,
    private val redirects: ConcurrentHashMap<String, String>
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val original = dataSpec.uri.toString()
        val target = redirects[original]
        if (target != null) {
            try {
                return upstream.open(dataSpec.withUri(Uri.parse(target)))
            } catch (e: IOException) {
                // L'adresse finale a expiré : on oublie la redirection et on repasse par l'adresse d'origine.
                redirects.remove(original)
                try { upstream.close() } catch (_: IOException) {}
            }
        }
        val n = upstream.open(dataSpec)
        val finalUri = upstream.uri?.toString()
        if (finalUri != null && finalUri != original) redirects[original] = finalUri
        return n
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        /** Une mémoire par lecture (nouvelle chaîne = nouvelle mémoire). */
        private val redirects = ConcurrentHashMap<String, String>()
        override fun createDataSource(): DataSource = StickyRedirectDataSource(upstream.createDataSource(), redirects)
    }
}
