package com.elarbi.omniiptv.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.text.TextPaint
import com.bumptech.glide.Glide
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.Programme
import com.elarbi.omniiptv.ui.Ui
import java.io.File
import java.util.concurrent.TimeUnit

/** Génère des vignettes 16:9 « façon chaîne TV » : logo, programme en cours, horaire, progression. */
object Posters {
    private const val W = 640
    private const val H = 360

    private fun dir(ctx: Context) = File(ctx.cacheDir, "posters").apply { mkdirs() }

    fun uri(ctx: Context, name: String): Uri = Uri.parse("content://${ctx.packageName}.posters/$name")

    private fun logo(ctx: Context, url: String): Bitmap? = try {
        if (url.isBlank()) null
        else Glide.with(ctx.applicationContext).asBitmap().load(url).submit(320, 180).get(8, TimeUnit.SECONDS)
    } catch (e: Exception) {
        null
    }

    fun make(ctx: Context, ch: Channel, now: Programme?, next: Programme?): Uri {
        val name = "p" + Integer.toHexString((ch.key + (now?.start ?: 0) + ch.logo).hashCode()).replace("-", "m") + ".png"
        val f = File(dir(ctx), name)
        if (f.exists()) return uri(ctx, name)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), Color.parseColor("#1E4D7B"), Color.parseColor("#0B1320"), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)
        // Logo à gauche
        val lg = logo(ctx, ch.logo)
        val box = RectF(28f, 40f, 268f, 175f)
        c.drawRoundRect(box, 18f, 18f, Paint().apply { color = Color.argb(40, 255, 255, 255) })
        if (lg != null) {
            val s = minOf((box.width() - 20) / lg.width, (box.height() - 20) / lg.height)
            val w = lg.width * s
            val h = lg.height * s
            c.drawBitmap(lg, null, RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
            c.drawText(TextUtils.ellipsize(ch.name, tp, box.width() - 20, TextUtils.TruncateAt.END).toString(), box.centerX(), box.centerY() + 12, tp)
        }
        // Badge EN DIRECT
        val badge = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
        c.drawRoundRect(RectF(292f, 44f, 432f, 80f), 8f, 8f, Paint().apply { color = Color.parseColor("#E53935") })
        c.drawText("EN DIRECT", 304f, 70f, badge)
        // Nom de la chaîne
        val chp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF9F1C"); textSize = 28f; typeface = Typeface.DEFAULT_BOLD }
        c.drawText(TextUtils.ellipsize(ch.name, chp, 320f, TextUtils.TruncateAt.END).toString(), 292f, 120f, chp)
        val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B0BEC5"); textSize = 22f }
        c.drawText(TextUtils.ellipsize(ch.group, sub, 320f, TextUtils.TruncateAt.END).toString(), 292f, 156f, sub)
        // Programme en cours
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 36f; typeface = Typeface.DEFAULT_BOLD }
        val t1 = now?.title?.ifBlank { null } ?: "En direct"
        c.drawText(TextUtils.ellipsize(t1, title, W - 56f, TextUtils.TruncateAt.END).toString(), 28f, 240f, title)
        if (now != null) {
            c.drawText("${Ui.hm(now.start)} – ${Ui.hm(now.end)}", 28f, 276f, sub)
            val t = System.currentTimeMillis()
            val pct = ((t - now.start).toFloat() / (now.end - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
            c.drawRect(28f, 296f, W - 28f, 304f, Paint().apply { color = Color.argb(70, 255, 255, 255) })
            c.drawRect(28f, 296f, 28f + (W - 56f) * pct, 304f, Paint().apply { color = Color.parseColor("#FF9F1C") })
        }
        if (next != null) {
            c.drawText(TextUtils.ellipsize("Ensuite ${Ui.hm(next.start)} · ${next.title}", sub, W - 56f, TextUtils.TruncateAt.END).toString(), 28f, 338f, sub)
        }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return uri(ctx, name)
    }

    /** Supprime les vignettes de plus de 2 jours. */
    fun cleanup(ctx: Context) {
        val limit = System.currentTimeMillis() - 2 * 24 * 3600_000L
        dir(ctx).listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }
}
